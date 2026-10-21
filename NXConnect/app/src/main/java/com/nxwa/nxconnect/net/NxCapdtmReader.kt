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

/**
 * 拍摄参数读取器：管着一条 telnet 连接，按需读一次快照。
 *
 * ## 为什么单独一层
 *
 * [NxTelnet] 是阻塞 IO，[NxCapdtm] 只懂命令格式。这一层负责：
 *
 * - **按需连接 + 复用**：一条 telnet 握手在单核相机上要几百毫秒，
 *   不能每读一次就重连。
 * - **失败即静默降级**：telnet 是可选能力（原厂固件没有），
 *   拿不到就返回 null，绝不抛到界面上。
 * - **主动断线**：相机端 inetd 对空闲连接有回收，复用前先探活。
 *
 * ## 只读
 *
 * 本类**不提供写入**。实测 `setvar` 只是改写影子变量：
 * 写 `0x7FFF` 给 TV 也能原样回读、相机不做任何范围校验，
 * 说明曝光引擎没消费它（"值变了 ≠ 被消费"）。
 * 要真正改曝光仍需走按键注入，或先找到让相机重载参数的方法。
 */
class NxCapdtmReader(private val ip: String) {

    private var telnet: NxTelnet? = null
    private var capdtm: NxCapdtm? = null

    /** 最近一次成功读到的快照 */
    @Volatile
    var lastSnapshot: CapdtmSnapshot? = null
        private set

    /**
     * 读一次快照。
     *
     * @return 拿不到就返回 null（不抛异常）
     */
    suspend fun read(): CapdtmSnapshot? = withContext(Dispatchers.IO) {
        try {
            val api = ensureConnected() ?: return@withContext null
            val snapshot = api.snapshot()
            if (!snapshot.isEmpty) lastSnapshot = snapshot
            lastSnapshot
        } catch (_: IOException) {
            // 连接坏了：下次重连
            disconnect()
            null
        } catch (_: Exception) {
            disconnect()
            null
        }
    }

    /** 是否有可用连接 */
    val isConnected: Boolean
        get() = telnet?.isConnected == true

    fun disconnect() {
        runCatching { telnet?.close() }
        telnet = null
        capdtm = null
    }

    /**
     * 确保连接可用。
     *
     * 复用前先跑一条最轻的命令探活：相机端会回收空闲连接，
     * 而 `isConnected` 只反映本地 socket 状态，探不出来。
     */
    private fun ensureConnected(): NxCapdtm? {
        capdtm?.let { api ->
            val t = telnet
            if (t != null && t.isConnected && t.tryRun("true", PROBE_TIMEOUT_MS) != null) {
                return api
            }
        }
        disconnect()
        val t = NxTelnet(ip)
        return try {
            t.connect()
            // 确认 st 存在，避免把"没有 st"误当成"没有 telnet"
            if (t.tryRun("which st", PROBE_TIMEOUT_MS)?.contains("/st") != true) {
                t.close()
                return null
            }
            val api = NxCapdtm(t)
            telnet = t
            capdtm = api
            api
        } catch (_: IOException) {
            runCatching { t.close() }
            null
        }
    }

    private companion object {
        const val PROBE_TIMEOUT_MS = 3_000
    }
}
