/*
 * NXConnectForNX500 — 三星 NX500 相机的照片传输与遥控 App
 * Copyright (C) 2026 Katsiy and contributors
 *
 * 本程序是自由软件：你可以依据 GNU Affero 通用公共许可证第 3 版
 * （或任何更新版本）的条款重新分发和/或修改它。
 * 本程序按"有用"的目的分发，但不提供任何担保。
 * 详见随附的 LICENSE 文件，或 <https://www.gnu.org/licenses/>。
 *
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.nxwa.nxconnect.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import com.nxwa.nxconnect.NxApp
import com.nxwa.nxconnect.data.MediaEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * 缩略图加载与缓存。
 *
 * 两个硬约束（来自相机端源码/文档）：
 *  1. **并发必须 ≤ 2**。相机是单核 ARM，缩略图由相机端 ImageMagick `convert` 现场生成，
 *     CPU 打满会饿死 80 端口的心跳，前端会误报"相机已断开"。
 *  2. 失败时 CGI 也返回 **HTTP 200 + text/plain "thumb error: …"**，所以必须校验
 *     Content-Type / JPEG 魔数，否则会把错误文本当图片解码失败。
 *
 * 另外支持"未部署 CGI"的降级：直接取原图再压缩（慢，但至少能看到内容）。
 */
object ThumbLoader {

    private val memory = object : LruCache<String, Bitmap>(48) {
        override fun sizeOf(key: String, value: Bitmap): Int = 1
    }

    /** 相机端并发上限 */
    private val gate = Semaphore(2)

    /** 同一张图只让一个协程去取，其余等待复用结果（避免重复生成） */
    private val inflight = ConcurrentHashMap<String, Mutex>()

    private fun key(relPath: String, width: Int) = "$relPath@$width"

    fun cached(relPath: String, width: Int): Bitmap? = memory.get(key(relPath, width))

    fun peekAny(relPath: String): Bitmap? {
        for (w in intArrayOf(320, 400, 1024, 0)) {
            memory.get(key(relPath, w))?.let { return it }
        }
        return null
    }

    /**
     * 取缩略图。
     *
     * **RAW（.SRW 等）不走这条路**：真机实测相机端 `thumb-cgi` 的扩展名白名单是
     * `jpg|jpeg|png|gif|bmp|tif|tiff`，不含 `srw`；而退化成下载原图意味着
     * 每张要传 19–34MB —— 对手机网格浏览完全不可接受。
     * 所以 RAW 在 UI 上显示带标识的占位格，用户点开时再按需拉原图。
     *
     * @param allowFullFallback CGI 不可用时，是否退化为下载原图并本地缩放
     * @return 缩略图；RAW 或无法取得时返回 null
     */
    suspend fun load(
        entry: MediaEntry,
        width: Int = 320,
        quality: Int = 75,
        allowFullFallback: Boolean = true,
    ): Bitmap? = withContext(Dispatchers.IO) {
        if (entry.isVideo) return@withContext null
        if (entry.isRaw) return@withContext null

        val k = key(entry.relPath, width)
        memory.get(k)?.let { return@withContext it }

        val lock = inflight.computeIfAbsent(k) { Mutex() }
        try {
            lock.withLock {
                memory.get(k)?.let { return@withContext it }

                val cam = NxApp.camera ?: return@withLock fullFallback(entry, width, allowFullFallback)

                // 1) 相机端缩略图 CGI（只支持普通图片格式）
                val bmp = try {
                    gate.withPermit {
                        val bytes = cam.fetchThumb(entry.relPath, width, quality)
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    }
                } catch (e: Exception) {
                    NxApp.log("缩略图失败 ${entry.name}：${e.message?.take(60)}")
                    null
                }

                if (bmp != null) {
                    memory.put(k, bmp)
                    return@withLock bmp
                }

                // 2) 降级：拉原图本地缩放（仅用于小尺寸 JPG；RAW 已在入口拦掉）
                return@withLock fullFallback(entry, width, allowFullFallback)
            }
        } finally {
            inflight.remove(k)
        }
    }

    private suspend fun fullFallback(entry: MediaEntry, width: Int, allow: Boolean): Bitmap? {
        if (!allow) return null
        // RAW 动辄 20–34MB，绝不能为了网格预览去拉原图
        if (entry.isRaw || entry.isVideo) return null
        // 原图超过 12MB 的也不值得为预览下载
        if (entry.size > 12L * 1024 * 1024) return null

        val cam = NxApp.camera ?: return null
        return try {
            gate.withPermit {
                // 原图可能 4-8MB，缩小采样率再解码，避免 OOM
                val bytes = com.nxwa.nxconnect.net.NxHttp.getBytes(cam.fileUrl(entry.relPath), 60_000)
                if (!bytes.isJpeg && bytes.code !in 200..299) return@withPermit null
                val opts = BitmapFactory.Options().apply { inSampleSize = 8 }
                val decoded = BitmapFactory.decodeByteArray(bytes.bytes, 0, bytes.bytes.size, opts)
                decoded?.let { b ->
                    val scaled = if (b.width > width) {
                        Bitmap.createScaledBitmap(b, width, (b.height.toFloat() / b.width * width).toInt(), true)
                    } else {
                        b
                    }
                    memory.put(key(entry.relPath, width), scaled)
                    scaled
                }
            }
        } catch (e: Exception) {
            null
        }
    }

    fun clear() = memory.evictAll()
}
