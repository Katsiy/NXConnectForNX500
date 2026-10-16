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
package com.nxwa.nxconnect.transfer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.nxwa.nxconnect.NxApp
import com.nxwa.nxconnect.data.MediaEntry
import com.nxwa.nxconnect.net.NxCamera
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * 前台下载服务。
 *
 * 为什么用前台服务：一张 28MP JPG 有 4–8MB，相机热点下往往十几秒一张，
 * 批量下载时用户会切走 App，后台普通协程会被系统杀掉。
 */
class DownloadService : Service() {

    companion object {
        private const val TAG = "NXDownload"
        private const val CHANNEL_ID = "nx_download"
        private const val NOTIF_ID = 4211

        const val EXTRA_IP = "ip"
        const val EXTRA_FOLDER = "folder"
        const val EXTRA_NAMES = "names"

        fun start(ctx: Context, ip: String, entries: List<MediaEntry>) {
            if (entries.isEmpty()) return
            val i = Intent(ctx, DownloadService::class.java).apply {
                putExtra(EXTRA_IP, ip)
                putExtra(EXTRA_FOLDER, entries.first().folder)
                putExtra(EXTRA_NAMES, entries.map { it.name }.toTypedArray())
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ctx.startForegroundService(i)
            } else {
                ctx.startService(i)
            }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var nm: NotificationManager

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        createChannel()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                CHANNEL_ID,
                "照片下载",
                NotificationManager.IMPORTANCE_LOW,
            ).apply { description = "从相机下载照片到手机相册" }
            nm.createNotificationChannel(ch)
        }
    }

    private fun notification(text: String, progress: Int, max: Int, indeterminate: Boolean): Notification {
        val b = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        b.setContentTitle("NX 连接")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
        if (indeterminate) {
            b.setProgress(0, 0, true)
        } else {
            b.setProgress(max, progress, false)
        }
        return b.build()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val ip = intent?.getStringExtra(EXTRA_IP)
        val folder = intent?.getStringExtra(EXTRA_FOLDER) ?: ""
        val names = intent?.getStringArrayExtra(EXTRA_NAMES) ?: emptyArray()

        if (ip.isNullOrBlank() || names.isEmpty()) {
            stopSelf()
            return START_NOT_STICKY
        }

        startForeground(NOTIF_ID, notification("准备下载…", 0, names.size, true))

        scope.launch {
            val cam = NxApp.camera ?: run {
                stopSelf()
                return@launch
            }
            var ok = 0
            var failed = 0
            /** 第一个失败原因：下载结束后弹给用户看，而不是只留在一行通知里 */
            var firstError: String? = null
            var lastNotify = 0L

            for ((index, name) in names.withIndex()) {
                val entry = MediaEntry(folder = folder, name = name)
                val label = "${index + 1}/${names.size}  $name"
                notify(label, index, names.size, false)

                val target = try {
                    MediaStoreWriter.createImageTarget(this@DownloadService, name)
                } catch (e: Exception) {
                    val msg = "$name 创建文件失败：${e.message}"
                    NxApp.log("下载失败 $msg")
                    if (firstError == null) firstError = msg
                    failed++
                    continue
                }

                try {
                    var lastPct = -1
                    cam.downloadMedia(
                        entry = entry,
                        onProgress = { done, total ->
                            val now = System.currentTimeMillis()
                            val pct = if (total > 0) ((done * 100 / total) / 5 * 5).toInt() else -1
                            if (now - lastNotify > 1500 && (pct != lastPct || total <= 0)) {
                                lastPct = pct
                                lastNotify = now
                                val pctText = if (total > 0) " ${done * 100 / total}%" else " ${done / 1024}KB"
                                notify("$label$pctText", index, names.size, false)
                            }
                        },
                        onChunk = { buf, len ->
                            target.output?.write(buf, 0, len)
                        },
                    )
                    MediaStoreWriter.finish(this@DownloadService, target)
                    ok++
                    NxApp.log("下载完成 $name → ${target.displayPath}")
                } catch (e: Exception) {
                    val msg = "$name 下载失败：${e.message?.take(120)}"
                    NxApp.log(msg)
                    if (firstError == null) firstError = msg
                    MediaStoreWriter.abort(this@DownloadService, target)
                    failed++
                }
            }

            val summary = "完成：成功 $ok 个" + if (failed > 0) "，失败 $failed 个" else ""
            notify(summary, names.size, names.size, false)

            // 有失败就弹一条 Toast：用户需要在屏幕上看到原因，而不是去翻通知
            if (failed > 0 && firstError != null) {
                try {
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                        android.widget.Toast.makeText(
                            this@DownloadService,
                            "下载失败：$firstError",
                            android.widget.Toast.LENGTH_LONG,
                        ).show()
                    }
                } catch (e: Exception) {
                    // 后台线程弹 Toast 在个别 ROM 上会失败，忽略
                }
            }

            kotlinx.coroutines.delay(2500)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_DETACH)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(false)
            }
            nm.cancel(NOTIF_ID)
            stopSelf()
        }

        return START_NOT_STICKY
    }

    private fun notify(text: String, progress: Int, max: Int, indeterminate: Boolean) {
        try {
            nm.notify(NOTIF_ID, notification(text, progress, max, indeterminate))
        } catch (e: Exception) {
            // 通知权限被拒时忽略
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
