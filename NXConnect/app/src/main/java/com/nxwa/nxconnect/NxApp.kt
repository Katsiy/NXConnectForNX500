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
package com.nxwa.nxconnect

import android.app.Application
import android.content.Context
import android.util.Log
import com.nxwa.nxconnect.net.AndroidHttpBinder
import com.nxwa.nxconnect.net.NxCamera
import com.nxwa.nxconnect.net.NetworkBinder
/**
 * 全局单例：保存当前连接的相机、网络绑定状态，以及一个便于用户回报问题的日志缓冲。
 */
class NxApp : Application() {

    override fun onCreate() {
        super.onCreate()
        instance = this
        // 尽早安装：真机出现过"切画质后进程直接没了"，没有日志就无从定位
        CrashLog.install(this)
        NetworkBinder.appContext = this
        Log.i(TAG, "NX 连接启动 v${BuildConfig.VERSION_NAME} (${BuildConfig.BUILD_STAMP})")
    }

    companion object {
        private const val TAG = "NxApp"

        @Volatile
        lateinit var instance: NxApp
            private set

        /** 当前已连接的相机；未连接为 null */
        @Volatile
        var camera: NxCamera? = null
            private set

        /** 当前相机 IP（便于 UI 恢复） */
        @Volatile
        var cameraIp: String? = null

        /** 实际使用的端口（真机 80/8080；联调模拟相机时可能是 8081/8082） */
        @Volatile
        var daemonPort: Int = NxCamera.PORT

        @Volatile
        var cgiPort: Int = NxCamera.CGI_PORT

        private val logBuffer = ArrayDeque<String>()
        private const val LOG_LIMIT = 300

        fun connect(ip: String, port: Int = daemonPort, cgi: Int = cgiPort) {
            cameraIp = ip
            daemonPort = port
            cgiPort = cgi
            // Android 侧传入网络绑定实现；协议层本身是纯 JVM 的，测试时用 HttpBinder.None
            camera = NxCamera(ip, port, cgi, AndroidHttpBinder(instance)).also { cam ->
                // 协议层不依赖 Android，日志通过钩子接回这里
                cam.logger = { msg -> log(msg) }
            }
            log("已连接相机 $ip（daemon:$port cgi:$cgi）")
        }

        fun disconnect() {
            log("断开相机 ${cameraIp ?: "-"}")
            camera = null
            cameraIp = null
            NetworkBinder.unbind()
        }

        val isConnected: Boolean get() = camera != null

        fun log(msg: String) {
            val line = "${System.currentTimeMillis() % 100000}  $msg"
            Log.i(TAG, msg)
            synchronized(logBuffer) {
                logBuffer.addLast(line)
                while (logBuffer.size > LOG_LIMIT) logBuffer.removeFirst()
            }
        }

        fun logs(): List<String> = synchronized(logBuffer) { logBuffer.toList() }

        fun ctx(): Context = instance
    }
}
