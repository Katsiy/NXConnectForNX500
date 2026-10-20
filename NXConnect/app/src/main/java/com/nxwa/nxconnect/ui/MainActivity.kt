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

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.nxwa.nxconnect.NxApp
import com.nxwa.nxconnect.R
import com.nxwa.nxconnect.data.CameraOverview
import com.nxwa.nxconnect.data.NxNetworkFacts
import com.nxwa.nxconnect.databinding.ActivityMainBinding
import com.nxwa.nxconnect.net.CameraDiscovery
import com.nxwa.nxconnect.net.NxCamera
import com.nxwa.nxconnect.net.NxShell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding

    private val notifPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { /* 拒绝也不影响核心功能 */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        b.helpText.text = NxNetworkFacts.HELP_TEXT.trim()
        b.versionLine.text = buildString {
            append("v").append(com.nxwa.nxconnect.BuildConfig.VERSION_NAME)
            append(" · ").append(com.nxwa.nxconnect.BuildConfig.BUILD_STAMP)
        }
        b.deviceLine.text = "本机地址：" + (
            com.nxwa.nxconnect.net.NetworkBinder.localIpv4(this) ?: "未获取到局域网地址"
            )

        // 系统栏适配：与其它页面统一用「根布局吃系统 inset」的做法。
        // 原来用 padTop(header)，该写法在遥控页被用户实测确认内容会被状态栏挡住。
        EdgeToEdge.setup(this)
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(b.root) { v, insets ->
            val top = insets.getInsets(
                androidx.core.view.WindowInsetsCompat.Type.statusBars() or
                    androidx.core.view.WindowInsetsCompat.Type.displayCutout(),
            ).top
            val bottom = insets.getInsets(
                androidx.core.view.WindowInsetsCompat.Type.navigationBars(),
            ).bottom
            v.setPadding(v.paddingLeft, top, v.paddingRight, bottom)
            androidx.core.view.WindowInsetsCompat.CONSUMED
        }
        androidx.core.view.ViewCompat.requestApplyInsets(b.root)
        EdgeToEdge.padBottom(findViewById(R.id.scroll), extraDp = 8)

        // 恢复上次连接的 IP 与端口
        val prefs = getSharedPreferences("nx", MODE_PRIVATE)
        prefs.getString("last_ip", null)?.let { b.ipInput.setText(it) }
        b.portInput.setText(prefs.getInt("daemon_port", NxCamera.PORT).toString())
        b.cgiPortInput.setText(prefs.getInt("cgi_port", NxCamera.CGI_PORT).toString())
        NxApp.cameraIp?.let {
            b.ipInput.setText(it)
            refreshConnectedUi()
        }

        b.btnDetect.setOnClickListener { detect() }
        b.btnConnect.setOnClickListener { connect(b.ipInput.text?.toString().orEmpty()) }
        b.btnDisconnect.setOnClickListener {
            NxApp.disconnect()
            refreshConnectedUi()
            toast("已断开")
        }
        b.btnGallery.setOnClickListener {
            startActivity(Intent(this, GalleryActivity::class.java))
        }
        b.btnRemote.setOnClickListener {
            startActivity(Intent(this, RemoteActivity::class.java))
        }
        b.btnSyncTime.setOnClickListener { syncCameraTime(manual = true) }

        askNotificationPermission()
        showCrashReportIfAny()
    }

    /**
     * 上次会话崩溃过就把记录弹出来。
     *
     * 背景：真机出现过"切低画质 → UI 卡 1 秒 → 直接返回上一级、连接中断"，
     * 那是进程崩溃。用户不方便连数据线取 logcat，所以让 App 自己把崩溃摊在屏幕上，
     * 截图即可反馈。
     */
    private fun showCrashReportIfAny() {
        val report = com.nxwa.nxconnect.CrashLog.readLast(this) ?: return
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("上次运行时崩溃了")
            .setMessage(
                "把下面这段截图发给开发者即可定位。\n\n" +
                    report.take(3000) +
                    if (report.length > 3000) "\n\n…（已截断）" else "",
            )
            .setPositiveButton("知道了") { _, _ -> com.nxwa.nxconnect.CrashLog.clear(this) }
            .setNeutralButton("保留") { _, _ -> }
            .show()
    }

    override fun onResume() {
        super.onResume()
        refreshConnectedUi()
    }

    private fun askNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    private fun setBusy(busy: Boolean, text: String? = null) {
        b.progress.visibility = if (busy) View.VISIBLE else View.GONE
        b.btnConnect.isEnabled = !busy
        b.btnDetect.isEnabled = !busy
        text?.let { b.statusLine.text = it }
    }

    private fun status(text: String, colorRes: Int = R.color.nx_text_dim) {
        b.statusLine.text = text
        b.statusLine.setTextColor(ContextCompat.getColor(this, colorRes))
        // 状态点跟着变色，一眼能看出连接状态
        val dotColor = when (colorRes) {
            R.color.nx_ok -> R.color.nx_ok
            R.color.nx_warn -> R.color.nx_warn
            R.color.nx_err -> R.color.nx_error
            else -> R.color.nx_outline
        }
        TintUtil.tintBackground(b.statusDot.background, dotColor)?.let { b.statusDot.background = it }
    }

    private fun refreshConnectedUi() {
        val connected = NxApp.isConnected
        b.actionRow.visibility = if (connected) View.VISIBLE else View.GONE
        b.infoCard.visibility = if (connected) View.VISIBLE else View.GONE
        if (connected) {
            status("已连接：${NxApp.cameraIp}", R.color.nx_ok)
            loadOverview(autoSyncTime = true)
        } else {
            status(getString(R.string.status_not_connected))
            b.infoTable.removeAllViews()
            b.infoNotes.text = ""
        }
    }

    /**
     * 读取并展示相机概览。
     *
     * @param autoSyncTime 连接后自动同步一次相机时间（只在偏差 > 60 秒时才真的写）。
     *   相机时钟实测停在 2015 年，不同步的话照片时间戳全是错的。
     */
    private fun loadOverview(autoSyncTime: Boolean) {
        val cam = NxApp.camera ?: return
        val ip = NxApp.cameraIp ?: return
        // 读取中：先清空表格并显示一行提示
        b.infoTable.removeAllViews()
        b.infoNotes.text = "正在读取相机信息…"
        lifecycleScope.launch {
            val overview = cam.readOverview(ip)
            renderOverview(overview)

            if (autoSyncTime && overview.shellAvailable && overview.timeNeedsSync) {
                NxApp.log("相机时间偏差 ${overview.timeDriftText}，自动同步")
                syncCameraTime(manual = false)
            }
        }
    }

    private fun renderOverview(o: CameraOverview) {
        // 用表格呈现键值对，比一长串文本易读
        val rows = buildList {
            add("机型" to (o.model ?: "未知"))
            o.firmware?.let { add("固件" to it) }
            add(
                "电量" to buildString {
                    append(o.batteryText)
                    if (o.chargingText.isNotEmpty()) append("（").append(o.chargingText).append("）")
                },
            )
            o.mode?.takeIf { it != "UNKNOWN" }?.let { add("拍摄模式" to it) }
            o.drive?.takeIf { it != "UNKNOWN" }?.let { add("驱动" to it) }
            add(
                "相机时间" to buildString {
                    append(o.cameraTime ?: "未知")
                    o.timeDriftText?.takeIf { it != "已同步" }?.let { append("（").append(it).append("）") }
                },
            )
            if (o.sdTotalText != null) {
                add("SD 卡" to "剩 ${o.sdFreeText ?: "?"} / 共 ${o.sdTotalText}")
            }
            o.uptime?.let { add("运行时间" to it) }
            o.mac?.let { add("MAC" to it) }
        }

        b.infoTable.removeAllViews()
        for ((label, value) in rows) {
            val row = android.widget.TableRow(this).apply {
                layoutParams = android.widget.TableLayout.LayoutParams(
                    android.widget.TableLayout.LayoutParams.MATCH_PARENT,
                    android.widget.TableLayout.LayoutParams.WRAP_CONTENT,
                )
            }
            row.addView(
                TextView(this).apply {
                    text = label
                    setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall)
                    setTextColor(ContextCompat.getColor(this@MainActivity, R.color.nx_on_surface_variant))
                    setPadding(0, dp(7), dp(12), dp(7))
                },
            )
            row.addView(
                TextView(this).apply {
                    text = value
                    setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium)
                    setTextColor(ContextCompat.getColor(this@MainActivity, R.color.nx_on_surface))
                    setPadding(0, dp(7), 0, dp(7))
                },
            )
            b.infoTable.addView(row)
        }

        // 说明文字统一用浅灰，不再用醒目的警告色
        b.infoNotes.text = o.notes.joinToString("\n") { "· $it" }

        if (o.timeNeedsSync) {
            b.btnSyncTime.text = "同步时间（${o.timeDriftText}）"
        } else {
            b.btnSyncTime.setText(R.string.sync_time)
        }
    }

    private fun dp(v: Int): Int = (resources.displayMetrics.density * v).toInt()

    /** 同步相机时间（用手机当前本地时间） */
    private fun syncCameraTime(manual: Boolean) {
        val ip = NxApp.cameraIp ?: return
        if (!manual && !NxApp.isConnected) return
        b.btnSyncTime.isEnabled = false
        if (manual) toast(getString(R.string.syncing_time))
        lifecycleScope.launch {
            val (ok, msg) = NxShell.setTime(ip)
            if (ok) {
                NxApp.log("相机时间同步成功：$msg")
                toast("相机时间已同步为 $msg")
                // 同步后重读一次，刷新偏差显示
                NxApp.camera?.let { cam -> renderOverview(cam.readOverview(ip)) }
            } else {
                NxApp.log("相机时间同步失败：$msg")
                toast("同步失败：$msg")
            }
            b.btnSyncTime.isEnabled = true
        }
    }

    private fun detect() {
        setBusy(true, "开始探测…")
        lifecycleScope.launch {
            val result = CameraDiscovery.discover(this@MainActivity) { msg ->
                runOnUiThread { b.statusLine.text = msg }
            }
            setBusy(false)
            when (result) {
                is CameraDiscovery.Result.Found -> {
                    b.ipInput.setText(result.ip)
                    status("发现相机 ${result.ip}", R.color.nx_ok)
                    connect(result.ip)
                }

                is CameraDiscovery.Result.CameraWithoutDaemon -> {
                    status("${result.ip}：${result.hint}", R.color.nx_warn)
                    toast("连上了设备，但相机端遥控服务没有响应。请确认相机端 mod 已启用（见帮助）。")
                }

                is CameraDiscovery.Result.NotFound -> {
                    status("未找到相机（${result.reason}）", R.color.nx_err)
                }
            }
        }
    }

    private fun connect(ipRaw: String) {
        val ip = ipRaw.trim().removePrefix("http://").removePrefix("https://").substringBefore('/')
        if (ip.isBlank()) {
            toast("请先填写相机 IP，或点「自动探测」")
            return
        }
        val daemonPort = b.portInput.text?.toString()?.toIntOrNull()?.takeIf { it in 1..65535 }
            ?: NxCamera.PORT
        val cgiPort = b.cgiPortInput.text?.toString()?.toIntOrNull()?.takeIf { it in 1..65535 }
            ?: NxCamera.CGI_PORT

        setBusy(true, "正在连接 $ip …")
        lifecycleScope.launch {
            try {
                // 先绑网络，再验证 daemon 是否在线
                withContext(Dispatchers.IO) {
                    com.nxwa.nxconnect.net.NetworkBinder.candidateNetworks(this@MainActivity)
                        .firstOrNull()?.let { com.nxwa.nxconnect.net.NetworkBinder.bindTo(it) }
                }
                val cam = NxCamera(ip, daemonPort, cgiPort)
                val verify = CameraDiscovery.verify(ip)
                when (verify) {
                    is CameraDiscovery.Result.Found -> {
                        NxApp.connect(ip, daemonPort, cgiPort)
                        getSharedPreferences("nx", MODE_PRIVATE).edit()
                            .putString("last_ip", ip)
                            .putInt("daemon_port", daemonPort)
                            .putInt("cgi_port", cgiPort)
                            .apply()
                        val info = verify.info
                        val detail = listOfNotNull(
                            info.model?.let { "机型 $it" },
                            info.firmware?.let { "固件 $it" },
                            info.mac?.let { "MAC $it" },
                        ).joinToString(" · ")
                        status("已连接 $ip" + if (detail.isNotEmpty()) "（$detail）" else "", R.color.nx_ok)
                        refreshConnectedUi()

                        // 运行期能力探测：装了 mod 才是完全体，缺 8080 则只能列目录+下原图
                        val caps = withContext(Dispatchers.IO) { cam.detectCapabilities() }
                        status(caps.summary, if (caps.mode == com.nxwa.nxconnect.net.CameraMode.FULL_MOD) R.color.nx_ok else R.color.nx_warn)
                        caps.notes.forEach { NxApp.log("能力探测：$it") }
                        if (caps.notes.isNotEmpty()) {
                            toast(caps.notes.first())
                        }
                    }

                    is CameraDiscovery.Result.CameraWithoutDaemon -> {
                        status("$ip 可访问，但相机端遥控服务未响应", R.color.nx_warn)
                        toast(verify.hint)
                    }

                    is CameraDiscovery.Result.NotFound -> {
                        status("连接失败：${verify.reason}", R.color.nx_err)
                    }
                }
            } catch (e: Exception) {
                status("连接出错：${e.message}", R.color.nx_err)
            } finally {
                setBusy(false)
            }
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
}
