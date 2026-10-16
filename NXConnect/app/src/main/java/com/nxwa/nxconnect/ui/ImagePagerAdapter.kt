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
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.nxwa.nxconnect.NxApp
import com.nxwa.nxconnect.R
import com.nxwa.nxconnect.data.MediaEntry
import com.nxwa.nxconnect.net.NxHttp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 原图查看翻页器（ViewPager2 用）。
 *
 * 相机原图 4–8MB / 28MP，必须降采样后再解码，否则一翻页就 OOM。
 * 这里先 `inJustDecodeBounds` 读尺寸，再把 inSampleSize 提到「长边 ≤ 2048」。
 */
class ImagePagerAdapter(
    private val folder: String,
    private val names: List<String>,
) : RecyclerView.Adapter<ImagePagerAdapter.VH>() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val jobs = mutableMapOf<Int, Job>()
    private val cache = mutableMapOf<Int, Bitmap>()

    inner class VH(v: View) : RecyclerView.ViewHolder(v) {
        val image: ZoomImageView = v.findViewById(R.id.image)
        val progress: ProgressBar = v.findViewById(R.id.progress)
        val error: TextView = v.findViewById(R.id.error)
    }

    override fun getItemCount(): Int = names.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_pager_image, parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        val cached = cache[position]
        if (cached != null && !cached.isRecycled) {
            holder.image.setImageBitmap(cached)
            holder.progress.visibility = View.GONE
            holder.error.visibility = View.GONE
            return
        }

        holder.image.setImageDrawable(null)
        holder.progress.visibility = View.VISIBLE
        holder.error.visibility = View.GONE

        jobs[position]?.cancel()
        jobs[position] = scope.launch {
            val bmp = load(folder, names[position])
            if (holder.bindingAdapterPosition != position) return@launch
            if (bmp != null) {
                cache[position] = bmp
                holder.image.setImageBitmap(bmp)
                holder.progress.visibility = View.GONE
            } else {
                holder.progress.visibility = View.GONE
                holder.error.text = "这张图加载失败"
                holder.error.visibility = View.VISIBLE
            }
        }
    }

    override fun onViewRecycled(holder: VH) {
        jobs.remove(holder.bindingAdapterPosition)?.cancel()
        super.onViewRecycled(holder)
    }

    fun dispose() {
        jobs.values.forEach { it.cancel() }
        jobs.clear()
        scope.cancel()
        cache.values.forEach { if (!it.isRecycled) it.recycle() }
        cache.clear()
    }

    private suspend fun load(folder: String, name: String): Bitmap? {
        val cam = NxApp.camera ?: return null
        val entry = MediaEntry(folder, name)
        return try {
            val bin = withContext(Dispatchers.IO) { NxHttp.getBytes(cam.fileUrl(entry.relPath), 90_000) }
            if (bin.bytes.size < 100) return null

            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bin.bytes, 0, bin.bytes.size, bounds)
            var sample = 1
            while (bounds.outWidth / sample > 2048 || bounds.outHeight / sample > 2048) {
                sample *= 2
            }
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            withContext(Dispatchers.IO) {
                BitmapFactory.decodeByteArray(bin.bytes, 0, bin.bytes.size, opts)
            }
        } catch (e: Exception) {
            NxApp.log("原图加载失败 $name：${e.message?.take(60)}")
            null
        }
    }
}
