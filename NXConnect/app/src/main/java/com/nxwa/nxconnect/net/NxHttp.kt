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
package com.nxwa.nxconnect.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/** 相机侧错误（区别于手机本地网络错误，便于给用户不同的提示） */
class NxException(message: String, cause: Throwable? = null) : IOException(message, cause)

/** 一次二进制响应的结果：状态码 + Content-Type + 字节 */
class NxBinary(
    val code: Int,
    val contentType: String?,
    val bytes: ByteArray,
) {
    val isJpeg: Boolean
        get() = contentType?.contains("image/jpeg", ignoreCase = true) == true ||
            (bytes.size > 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte())
}

/**
 * 极简 HTTP 客户端（纯 JVM，不依赖任何 Android 类）。
 *
 * 为什么不用 OkHttp：相机是局域网明文 HTTP，不需要连接池/HTTP2/TLS；
 * 少一个依赖就少一份构建与镜像风险，且 HttpURLConnection 对低速单核相机足够。
 *
 * 网络绑定通过 [HttpBinder] 注入，因此这个文件可以在 JVM 单元测试里直接跑。
 */
object NxHttp {

    /** 单张 28MP JPG 可达 8MB，低速 WiFi 下要给足时间 */
    const val DEFAULT_CONNECT_TIMEOUT_MS = 6_000
    const val DEFAULT_READ_TIMEOUT_MS = 30_000

    /** 读取缓冲区：64 KB。见 [readAllBuffered] 里为什么不能用 readBytes()。 */
    private const val READ_CHUNK_BYTES = 64 * 1024

    /** 预分配初值：够放一帧高清取景（518,400 字节）而不用中途扩容 */
    private const val INITIAL_BODY_BYTES = 512 * 1024

    private fun open(
        url: String,
        timeoutMs: Int,
        binder: HttpBinder,
        keepAlive: Boolean = false,
    ): HttpURLConnection {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = minOf(timeoutMs, DEFAULT_CONNECT_TIMEOUT_MS)
        conn.readTimeout = timeoutMs
        conn.instanceFollowRedirects = true
        conn.useCaches = false
        conn.setRequestProperty("Accept", "*/*")
        // 默认关闭 keep-alive：某些 mongoose 版本对长连接处理不完善，一次性请求更稳。
        // 但**取景**是每秒多次的高频请求，握手开销占比很大（实测用户只有 1-2fps），
        // 所以取景显式走 keep-alive，让 HttpURLConnection 复用连接。
        conn.setRequestProperty("Connection", if (keepAlive) "keep-alive" else "close")
        binder.bind(conn)
        return conn
    }

    suspend fun getBytes(
        url: String,
        timeoutMs: Int = DEFAULT_READ_TIMEOUT_MS,
        binder: HttpBinder = HttpBinder.None,
    ): NxBinary = withContext(Dispatchers.IO) {
        val conn = open(url, timeoutMs, binder)
        try {
            val code = conn.responseCode
            val stream: InputStream = if (code in 200..299) {
                conn.inputStream
            } else {
                conn.errorStream ?: conn.inputStream
            }
            val body = stream?.use { it.readBytes() } ?: ByteArray(0)
            NxBinary(code, conn.contentType, body)
        } catch (e: IOException) {
            throw NxException("请求失败：$url（${e.message}）", e)
        } finally {
            conn.disconnect()
        }
    }

    suspend fun getText(
        url: String,
        timeoutMs: Int = DEFAULT_READ_TIMEOUT_MS,
        binder: HttpBinder = HttpBinder.None,
    ): String = withContext(Dispatchers.IO) {
        val conn = open(url, timeoutMs, binder)
        try {
            val code = conn.responseCode
            val stream: InputStream = if (code in 200..299) {
                conn.inputStream
            } else {
                conn.errorStream ?: conn.inputStream
            }
            stream?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
        } catch (e: IOException) {
            throw NxException("请求失败：$url（${e.message}）", e)
        } finally {
            conn.disconnect()
        }
    }

    /**
     * 取字节，**非 2xx 直接抛异常**。
     *
     * 用于取景这类"必须拿到正确内容才算成功"的场景：
     * 相机没装 mod 时 `/api/v1/liveview/get` 会返回 404 正文（如 "not found"），
     * 若把这段文本当帧返回，客户端要白刷好几帧才报"无法识别的取景帧"，
     * 错误信息也指向不到真正原因（端点不存在）。
     *
     * @param keepAlive 高频轮询（取景）时置 true，复用 TCP 连接以降低握手开销
     */
    suspend fun getBytesStrict(
        url: String,
        timeoutMs: Int = DEFAULT_READ_TIMEOUT_MS,
        binder: HttpBinder = HttpBinder.None,
        keepAlive: Boolean = false,
    ): ByteArray = withContext(Dispatchers.IO) {
        val conn = open(url, timeoutMs, binder, keepAlive)
        try {
            val code = conn.responseCode
            if (code !in 200..299) {
                val hint = if (code == 404) {
                    "端点不存在——相机端可能没装 mod，或不在拍摄模式"
                } else {
                    "相机返回 HTTP $code"
                }
                throw NxException("$hint：$url")
            }
            readAllBuffered(conn.inputStream)
        } catch (e: NxException) {
            throw e
        } catch (e: IOException) {
            throw NxException("请求失败：$url（${e.message}）", e)
        } finally {
            // keep-alive 时不要 disconnect：那会关掉底层 socket，复用就没了。
            // 只要 body 被完整读走，HttpURLConnection 会把连接放回池子。
            if (!keepAlive) conn.disconnect()
        }
    }

    /**
     * 分块读完整流。
     *
     * ★ 不要用 `InputStream.readBytes()`：`HttpURLConnection.getInputStream()` 返回的流
     *   默认缓冲区只有约 8 KB，518,400 字节的取景帧要读 **63 次**，每次一个 syscall。
     *   实测这会让高清取景的网络耗时从 199 ms 涨到 **288 ms（慢 45%）**，
     *   而低清（259,200 字节，32 次）只慢一点点 —— 正好解释了"只有高清对不上"的现象。
     *
     * 改成 64 KB 分块：518 KB 只需 8 次读取，且不必像 `readBytes()` 那样
     * 先按 contentLength 预分配再反复扩容。
     */
    private fun readAllBuffered(stream: java.io.InputStream?): ByteArray {
        if (stream == null) return ByteArray(0)
        val out = java.io.ByteArrayOutputStream(INITIAL_BODY_BYTES)
        val buf = ByteArray(READ_CHUNK_BYTES)
        stream.use { input ->
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
            }
        }
        return out.toByteArray()
    }

    /**
     * POST 表单并返回原始二进制 body。OSD 必须走此方法；HTTP 200 空体原样返回。
     * 不跟随重定向，防止 POST 被改为 GET。失败连接关闭，成功完整读完才允许复用。
     * formBody 由调用方构造；OSD 的 hashs 只含十六进制字符，无需额外转义。
     */
    suspend fun postFormBytesStrict(
        url: String,
        formBody: String,
        timeoutMs: Int = DEFAULT_READ_TIMEOUT_MS,
        binder: HttpBinder = HttpBinder.None,
        keepAlive: Boolean = false,
    ): ByteArray = withContext(Dispatchers.IO) {
        val body = formBody.toByteArray(Charsets.UTF_8)
        val conn = open(url, timeoutMs, binder, keepAlive)
        var fullyRead = false
        try {
            conn.instanceFollowRedirects = false
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            conn.setFixedLengthStreamingMode(body.size)
            conn.outputStream.use { it.write(body) }
            val code = conn.responseCode
            if (code !in 200..299) {
                throw NxException("相机返回 HTTP $code：POST $url")
            }
            val bytes = conn.inputStream.use { it.readBytes() }
            fullyRead = true
            bytes
        } catch (e: NxException) {
            throw e
        } catch (e: IOException) {
            throw NxException("POST 请求失败：$url（${e.message}）", e)
        } finally {
            if (!keepAlive || !fullyRead) conn.disconnect()
        }
    }

    /**
     * 流式下载，边读边回调进度，避免把 8MB 原图整个塞进内存。
     *
     * @param onProgress (已读字节, 总字节或 -1)
     */
    suspend fun download(
        url: String,
        timeoutMs: Int = 120_000,
        binder: HttpBinder = HttpBinder.None,
        onProgress: (Long, Long) -> Unit = { _, _ -> },
        onChunk: (ByteArray, Int) -> Unit,
    ): Long = withContext(Dispatchers.IO) {
        val conn = open(url, timeoutMs, binder)
        try {
            val code = conn.responseCode
            if (code !in 200..299) {
                throw NxException("相机返回 HTTP $code：$url")
            }
            val total = conn.contentLengthLong
            val buf = ByteArray(64 * 1024)
            var read = 0L
            conn.inputStream.use { input ->
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    onChunk(buf, n)
                    read += n
                    onProgress(read, total)
                }
            }
            read
        } catch (e: IOException) {
            throw NxException("下载中断：${e.message}", e)
        } finally {
            conn.disconnect()
        }
    }

    /** 只回状态码与 Content-Type，用于探测（不读 body） */
    suspend fun probe(
        url: String,
        timeoutMs: Int = 2_500,
        binder: HttpBinder = HttpBinder.None,
    ): Pair<Int, String?> = withContext(Dispatchers.IO) {
        val conn = open(url, timeoutMs, binder)
        try {
            conn.requestMethod = "GET"
            conn.setRequestProperty("Range", "bytes=0-0")
            Pair(conn.responseCode, conn.contentType)
        } catch (e: IOException) {
            throw NxException(e.message ?: "probe failed", e)
        } finally {
            conn.disconnect()
        }
    }
}
