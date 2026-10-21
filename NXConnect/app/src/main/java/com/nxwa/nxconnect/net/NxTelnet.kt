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

import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket

/**
 * 相机端 telnet（23 端口）的最小客户端。
 *
 * ## 为什么需要它
 *
 * App 的能力原本被 HTTP 接口限制住了：只能取景、注入按键、镜像屏幕。
 * 但相机自带一个调试 shell（`st` 命令集），通过它可以直接读写拍摄参数 ——
 * 见 [NxCapdtm]。这条路比"镜像屏幕 + 注入按键"干净得多。
 *
 * ## telnet 从哪来
 *
 * **原厂固件没有 telnet。** 它由相机端第三方组件（NX-KS 等）或本 mod 的 inetd
 * 提供，root 免密。拿不到连接时必须安静降级，不能影响主流程。
 *
 * ## 协议细节（都是踩过的坑）
 *
 * 1. **必须过滤 IAC 协商**：服务端会先发 `FF FB xx` 之类，不过滤会让回显里
 *    混进乱码，提示符匹配随之失效。规则：`FF` 后跟 `FB..FE` 时整段 3 字节跳过，
 *    否则跳 2 字节。
 * 2. **提示符形如** `[root@drime5 /]# ` 或 `# `，用正则 `(\[[^\]]*\]\s*)?#\s*$` 匹配。
 * 3. **输出里带 `\x00`**：`st` 的部分输出前面有 NUL，必须剔除。
 * 4. **单核相机很慢**：一条 `st` 命令可能要几百毫秒到几秒，超时给足。
 *
 * ## 线程模型
 *
 * 全部是**阻塞 IO**，只能在 `Dispatchers.IO` 上用。同一连接不能并发：
 * 内部用 [lock] 串行化。
 */
class NxTelnet(
    private val ip: String,
    private val port: Int = 23,
    private val connectTimeoutMs: Int = 4_000,
) : Closeable {

    private var socket: Socket? = null
    private var output: OutputStream? = null
    private var input: InputStream? = null

    /** 累积的已解码字符（已剔除 IAC 与 NUL） */
    private val buffer = StringBuilder(8 * 1024)

    private val lock = Any()

    @Volatile
    private var closed = false

    val isConnected: Boolean
        get() = !closed && socket?.isConnected == true

    /**
     * 建立连接并登录。
     *
     * @throws IOException 连不上、或登录超时
     */
    fun connect() {
        close()
        closed = false
        val s = Socket()
        s.soTimeout = READ_POLL_MS
        s.connect(InetSocketAddress(ip, port), connectTimeoutMs)
        socket = s
        output = s.getOutputStream()
        input = s.getInputStream()
        buffer.setLength(0)

        if (!awaitText("login:", LOGIN_TIMEOUT_MS)) {
            close()
            throw IOException("相机没有给出 login 提示（telnet 可能未启用）")
        }
        buffer.setLength(0)
        sendLine("root")
        if (!awaitText("#", LOGIN_TIMEOUT_MS)) {
            close()
            throw IOException("root 登录失败（提示符未出现）")
        }
        buffer.setLength(0)
    }

    /**
     * 执行一条命令并返回输出（已去掉回显行与末尾提示符）。
     *
     * @param command 单行命令，不能含换行
     * @param timeoutMs 等待提示符的超时；`st cap capdtm usrlist` 这类要放宽
     */
    @Throws(IOException::class)
    fun run(command: String, timeoutMs: Int = DEFAULT_COMMAND_TIMEOUT_MS): String = synchronized(lock) {
        require(!command.contains('\n')) { "命令不能含换行" }
        if (!isConnected) throw IOException("telnet 未连接")

        buffer.setLength(0)
        sendLine(command)

        val deadline = System.currentTimeMillis() + timeoutMs
        var matched = false
        while (System.currentTimeMillis() < deadline) {
            if (PROMPT.containsMatchIn(buffer)) {
                matched = true
                break
            }
            if (!pump()) throw IOException("连接已断开")
        }
        if (!matched) throw IOException("命令超时：$command")

        val text = buffer.toString()
        buffer.setLength(0)
        return clean(text, command)
    }

    /** 命令是否可能可用（探测用）：失败返回 null 而不是抛异常 */
    fun tryRun(command: String, timeoutMs: Int = DEFAULT_COMMAND_TIMEOUT_MS): String? =
        try {
            run(command, timeoutMs)
        } catch (e: IOException) {
            null
        }

    override fun close() {
        closed = true
        runCatching { input?.close() }
        runCatching { output?.close() }
        runCatching { socket?.close() }
        input = null
        output = null
        socket = null
        buffer.setLength(0)
    }

    // ---------------------------------------------------------------- 内部

    private fun sendLine(command: String) {
        val os = output ?: throw IOException("telnet 未连接")
        os.write((command + "\n").toByteArray(Charsets.US_ASCII))
        os.flush()
    }

    /**
     * 读一批字节到 [buffer]。
     *
     * @return false 表示对端已关闭
     */
    private fun pump(): Boolean {
        val ins = input ?: return false
        val chunk = ByteArray(4096)
        val n = try {
            ins.read(chunk)
        } catch (_: java.net.SocketTimeoutException) {
            return true // 只是这一轮没数据，交给外层按截止时间判断
        }
        if (n < 0) return false
        decode(chunk, n)
        return true
    }

    /** telnet 字节流 → 纯文本：跳过 IAC 协商序列，丢弃 NUL 与回车 */
    private fun decode(chunk: ByteArray, length: Int) {
        var i = 0
        while (i < length) {
            val b = chunk[i].toInt() and 0xFF
            if (b == IAC) {
                if (i + 1 >= length) break // 半截序列，丢掉
                val cmd = chunk[i + 1].toInt() and 0xFF
                i += if (cmd in WILL..DONT) 3 else 2
                continue
            }
            when (b) {
                0 -> Unit          // NUL：st 的输出里常见
                '\r'.code -> Unit  // 统一成 \n
                else -> buffer.append(b.toChar())
            }
            i++
        }
    }

    private fun awaitText(text: String, timeoutMs: Int): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (buffer.contains(text)) return true
            if (!pump()) return false
        }
        return buffer.contains(text)
    }

    /** 去掉第一行回显与末尾提示符 */
    private fun clean(raw: String, command: String): String {
        var text = raw
        val lines = text.split('\n').toMutableList()
        // 回显行：通常以命令本身开头（可能有前缀提示符残留）
        if (lines.isNotEmpty() && lines.first().contains(command.take(24))) {
            lines.removeAt(0)
        }
        text = lines.joinToString("\n")
        return PROMPT.replace(text, "").trim()
    }

    private companion object {
        const val IAC = 255
        const val WILL = 0xFB
        const val DONT = 0xFE

        /** socket 读超时：短一点，让外层能按自己的截止时间循环 */
        const val READ_POLL_MS = 300

        const val LOGIN_TIMEOUT_MS = 6_000
        const val DEFAULT_COMMAND_TIMEOUT_MS = 12_000

        /** `[root@drime5 /]# ` 或 `# ` */
        val PROMPT = Regex("(\\[[^\\]]*\\]\\s*)?#\\s*$", RegexOption.MULTILINE)
    }
}
