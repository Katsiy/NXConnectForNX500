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

import com.nxwa.nxconnect.data.CameraInfo
import com.nxwa.nxconnect.data.CameraOverview
import com.nxwa.nxconnect.data.CameraStatus
import com.nxwa.nxconnect.data.DirListing
import com.nxwa.nxconnect.data.MediaEntry
import com.nxwa.nxconnect.data.MiniJson
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * NX500 相机客户端。
 *
 * 端点契约全部来自相机端源码（nx-remote-controller-daemon + NX-WA 的 8080 CGI），
 * 详见工程根目录的 NX500_PROTOCOL.md。
 *
 * 两个端口的分工：
 *  - [port] 80   : daemon —— 实时取景、按键注入、快门、状态、/DCIM 原图
 *  - [cgiPort] 8080: busybox httpd + CGI —— 实时目录列表、缩略图、拍摄参数
 *
 * 两个端口都可覆盖：真机是 80/8080，但用 PC 上的模拟相机联调时通常是 8081/8082。
 *
 * **本类是纯 JVM 的**（只依赖 org.json 与 java.net），网络绑定通过 [binder] 注入，
 * 因此可以在单元测试里直接对着一个本地 HTTP 服务器跑真实实现。
 */
class NxCamera(
    val ip: String,
    val port: Int = PORT,
    val cgiPort: Int = CGI_PORT,
    private val binder: HttpBinder = HttpBinder.None,
) {

    companion object {
        const val PORT = 80
        const val CGI_PORT = 8080

        /** liveview 单请求超时：源码里 JS 用 3s，这里给 4s 留余量 */
        const val LIVEVIEW_TIMEOUT_MS = 4_000
    }

    /**
     * 第二轮真机已验证两路可以并行；各路内部仍串行，最多各一个预览请求在途。
     * 页面取消时阻塞 HTTP 未必立即返回，因此锁必须属于 Camera，不能属于某个页面。
     * 这里只隔离预览流，不锁按键、快门或 B 门请求，也不改变它们的时序。
     */
    private val liveViewRequestMutex = Mutex()
    private val osdRequestMutex = Mutex()

    val baseUrl: String get() = if (port == 80) "http://$ip" else "http://$ip:$port"
    val cgiUrl: String get() = if (cgiPort == 80) "http://$ip" else "http://$ip:$cgiPort"

    // ---------------------------------------------------------------- 元信息

    suspend fun info(): CameraInfo {
        val text = NxHttp.getText("$baseUrl/api/v1/camera/info", 3_000, binder)
        return parseInfo(text)
    }

    suspend fun status(): CameraStatus {
        val text = NxHttp.getText("$baseUrl/api/v1/camera/status", 3_000, binder)
        return parseStatus(text)
    }

    /**
     * 解析 `/api/v1/camera/info`。
     * 真机字段名是 `fw_ver` / `mac_address`（不是 swagger 描述的 firmware/mac），
     * 这里同时兼容两种写法。
     */
    internal fun parseInfo(text: String): CameraInfo = try {
        val o = MiniJson.parseObject(text)
        CameraInfo(
            model = MiniJson.strAny(o, "model", "camera", "modelName"),
            firmware = MiniJson.strAny(o, "fw_ver", "firmware", "version", "firmwareVersion"),
            mac = MiniJson.strAny(o, "mac_address", "mac", "macAddress"),
            raw = text,
        )
    } catch (e: Exception) {
        CameraInfo(raw = text)
    }

    /**
     * 解析 `/api/v1/camera/status`。
     *
     * 真机字段名是蛇形（`battery_charging` / `battery_percent` / `battery_level`），
     * 并且 **`battery_percent = -1` 表示未知** —— 负值一律报 null，避免 UI 显示"-1%"。
     */
    internal fun parseStatus(text: String): CameraStatus = try {
        val o = MiniJson.parseObject(text)
        CameraStatus(
            batteryPercent = MiniJson.intAny(o, "battery_percent", "batteryPercent", "battery")
                ?.takeIf { it >= 0 },
            batteryLevel = MiniJson.intAny(o, "battery_level", "batteryLevel"),
            charging = MiniJson.boolAny(o, "battery_charging", "batteryCharging", "charging"),
            mode = MiniJson.strAny(o, "mode"),
            drive = MiniJson.strAny(o, "drive"),
            hevc = MiniJson.strAny(o, "hevc"),
            cameras = MiniJson.stringList(o["cameras"]),
            raw = text,
        )
    } catch (e: Exception) {
        CameraStatus(raw = text)
    }

    // ----------------------------------------------------------------- 相册

    /**
     * 实时目录列表。
     *
     * 两级回退，覆盖三种相机固件状态：
     *  1. 8080 `dirlist` CGI（装了 NX-WA / NX-KS 的相机；**每次都实时 readdir**，首选）
     *  2. 80 端口 `/DCIM` 的 HTML 目录页（mewlips daemon 的 mongoose / busybox httpd 目录列表；
     *     注意 daemon 版是**启动快照**，新拍照片可能不出现）
     *
     * 源码依据：dirlist-cgi 每次请求实时 readdir SD 卡；daemon 目录页只在启动时生成一次。
     */
    suspend fun listDir(path: String = ""): DirListing = withContext(Dispatchers.IO) {
        // ---- 1) 8080 CGI ----
        val cgiErr = try {
            val url = "$cgiUrl/cgi-bin/dirlist?p=" + urlEncode(path)
            val text = NxHttp.getText(url, 8_000, binder)
            val o = MiniJson.parseObject(text)
            if (MiniJson.bool(o, "ok") == true) {
                return@withContext DirListing(
                    path = MiniJson.str(o, "path") ?: path,
                    dirs = MiniJson.stringList(o["dirs"]),
                    files = MiniJson.stringList(o["files"]),
                )
            }
            MiniJson.str(o, "err") ?: "unknown"
        } catch (e: Exception) {
            e.message ?: "8080 不可用"
        }

        // ---- 2) 80 端口 HTML 目录页 ----
        try {
            val html = NxHttp.getText("$baseUrl/DCIM/" + path.trim('/'), 8_000, binder)
            val listing = parseHtmlListing(html, path)
            if (listing != null) return@withContext listing
        } catch (e: Exception) {
            // 落到下面统一报错
        }

        DirListing(path, emptyList(), emptyList(), ok = false, err = "无法列目录：$cgiErr")
    }

    /**
     * 解析 mongoose / busybox httpd 风格的目录页（委托给 [ListingParser]，便于单测）。
     */
    internal fun parseHtmlListing(html: String, path: String): DirListing? {
        val parsed = ListingParser.parse(html) ?: return null
        return DirListing(
            path = path,
            dirs = parsed.dirs,
            files = parsed.files,
            sizes = parsed.sizes,
            modified = parsed.modified,
        )
    }

    /**
     * 取某个目录下每个文件的真实字节数。
     *
     * 为什么需要单独一步：8080 的 `dirlist` **只返回文件名**，而真机目录里全是
     * 19–34MB 的 `.SRW` RAW —— 用户点「下载所选」之前必须知道要拉多少数据。
     * 大小只在 80 端口的 mongoose 目录页里有（带脚本的表格）。
     *
     * @return 文件名 → 字节数；拿不到时返回空表（不影响浏览）
     */
    suspend fun fetchFolderSizes(folder: String): Pair<Map<String, Long>, Map<String, String>> =
        withContext(Dispatchers.IO) {
            try {
                val html = NxHttp.getText("$baseUrl/DCIM/" + folder.trim('/'), 10_000, binder)
                val parsed = ListingParser.parse(html, onlyMedia = false)
                if (parsed == null) {
                    emptyMap<String, Long>() to emptyMap<String, String>()
                } else {
                    parsed.sizes to parsed.modified
                }
            } catch (e: Exception) {
                NxLog("读取 $folder 文件大小失败：${e.message}")
                emptyMap<String, Long>() to emptyMap<String, String>()
            }
        }

    /**
     * 轻量日志钩子。
     * 协议层刻意不依赖 Android（可被 JVM 单测直接跑），所以日志由真机侧注入
     * （见 `NxApp.connect` 里接到 [com.nxwa.nxconnect.NxApp.log]）。
     */
    var logger: ((String) -> Unit)? = null

    private fun NxLog(msg: String) {
        logger?.invoke(msg)
    }

    /** 列出 DCIM 根下的所有相片目录（按名称倒序 = 最新在前） */
    suspend fun listFolders(): List<String> {
        val root = listDir("")
        if (!root.ok) throw NxException("读取 DCIM 失败：${root.err}")
        return root.dirs.sortedDescending()
    }

    /** 列出某目录下的媒体文件（照片+视频+RAW），按序号倒序 = 最新在前 */
    suspend fun listMedia(folder: String): List<MediaEntry> {
        val listing = listDir(folder)
        if (!listing.ok) throw NxException("读取 $folder 失败：${listing.err}")
        return listing.files
            .filter { MediaEntry.isMedia(it) }
            .sortedWith(
                compareByDescending<String> { MediaEntry.sequenceOf(it) ?: -1 }
                    .thenByDescending { it },
            )
            .map {
                MediaEntry(
                    folder = folder,
                    name = it,
                    size = listing.sizes[it] ?: -1L,
                    modified = listing.modified[it],
                )
            }
    }

    /**
     * 缩略图 URL。
     * 注意：thumb-cgi 失败时也返回 HTTP 200 + text/plain "thumb error: …"，
     * 调用方必须检查 Content-Type（[fetchThumb] 已代劳）。
     */
    fun thumbUrl(relPath: String, width: Int = 400, quality: Int = 82): String {
        val w = width.coerceIn(64, 1024)
        val q = quality.coerceIn(50, 95)
        return "$cgiUrl/cgi-bin/thumb?f=" + urlEncode(relPath) + "&w=$w&q=$q"
    }

    /** 原图 URL（80 端口静态文件服务） */
    fun fileUrl(relPath: String): String =
        "$baseUrl/DCIM/" + relPath.split('/').joinToString("/") { urlEncode(it) }

    /**
     * 取缩略图。失败时抛 [NxException]，**不会**把 "thumb error: …" 文本当图片返回。
     */
    suspend fun fetchThumb(relPath: String, width: Int = 400, quality: Int = 82): ByteArray {
        val r = NxHttp.getBytes(thumbUrl(relPath, width, quality), 20_000, binder)
        if (!r.isJpeg) {
            val msg = r.bytes.toString(Charsets.UTF_8).take(150)
            throw NxException("缩略图生成失败：$msg")
        }
        return r.bytes
    }

    /** 下载原图，边下边回调 */
    suspend fun downloadMedia(
        entry: MediaEntry,
        onProgress: (Long, Long) -> Unit,
        onChunk: (ByteArray, Int) -> Unit,
    ): Long = NxHttp.download(fileUrl(entry.relPath), 180_000, binder, onProgress, onChunk)

    // ----------------------------------------------------------------- 遥控

    /**
     * 取一帧实时画面。
     *
     * 用严格模式：端点不存在时立即抛异常并说明原因，而不是把 404 正文当帧返回
     * （后者会让 UI 刷好几帧才报"无法识别的取景帧"，误导排查方向）。
     *
     * 用 keep-alive：取景是每秒多次的高频请求，一次性连接每次都要重新握手，
     * 在单核相机上这部分开销不可忽略（用户实测只有 1–2fps）。
     */
    suspend fun liveView(low: Boolean = false): ByteArray = liveViewRequestMutex.withLock {
        val path = if (low) "/api/v1/liveview/get_low" else "/api/v1/liveview/get"
        NxHttp.getBytesStrict("$baseUrl$path", LIVEVIEW_TIMEOUT_MS, binder, keepAlive = true)
    }

    /**
     * OSD 是唯一的 POST 接口。hashs 必须是客户端当前持有的 108 个服务端哈希。
     * 默认值保留旧的无参调用兼容性，仅适合首帧；连续轮询必须传入更新后的哈希。
     * 空体表示暂不可用，由 UI 重试；这里不调用解析器。
     */
    suspend fun osd(
        hashs: String = NxOsd.buildHashsParam(IntArray(108), 108),
    ): ByteArray = osdRequestMutex.withLock {
        require(hashs.length == 108 * 8 && hashs.all { it in '0'..'9' || it in 'a'..'f' }) {
            "OSD hashs 必须是 108 组 8 位小写十六进制哈希"
        }
        NxHttp.postFormBytesStrict(
            "$baseUrl/api/v1/osd/get",
            "hashs=$hashs",
            5_000,
            binder,
            keepAlive = true,
        )
    }

    /** 注入一次按键（键名见 KeyMap，X11 keysym） */
    suspend fun sendKey(keysym: String) {
        NxHttp.getText("$baseUrl/api/v1/input/inject?key=" + urlEncode(keysym), 4_000, binder)
    }

    suspend fun keyDown(keysym: String) {
        NxHttp.getText("$baseUrl/api/v1/input/inject?keydown=" + urlEncode(keysym), 4_000, binder)
    }

    suspend fun keyUp(keysym: String) {
        NxHttp.getText("$baseUrl/api/v1/input/inject?keyup=" + urlEncode(keysym), 4_000, binder)
    }

    /** 保活：相机长时间无操作会休眠，遥控期间应定期调用 */
    suspend fun keepAlive() {
        NxHttp.getText("$baseUrl/api/v1/input/inject_keep_alive", 4_000, binder)
    }

    /**
     * 快门。
     *
     * 相机端实现是 `st cap capdtm setusr ADJUSTSHUTTERTYPE 0x750000/0x750001`，
     * 即**可能只切换快门类型而不触发拍摄**（详见 NX500_PROTOCOL.md 3.1）。
     * 调用方应准备按键注入（S2 = Super_R）作为回退。
     */
    suspend fun shutter(silent: Boolean = false) {
        NxHttp.getText("$baseUrl/api/v1/shutter/" + if (silent) "silent" else "normal", 6_000, binder)
    }

    suspend fun lcd(on: Boolean) {
        NxHttp.getText("$baseUrl/api/v1/lcd/" + if (on) "on" else "off", 4_000, binder)
    }

    suspend fun led(on: Boolean) {
        NxHttp.getText("$baseUrl/api/v1/led/" + if (on) "on" else "off", 4_000, binder)
    }

    suspend fun mouseClick(x: Int, y: Int) {
        NxHttp.getText("$baseUrl/api/v1/input/inject?mousemove=$x-$y", 4_000, binder)
        NxHttp.getText("$baseUrl/api/v1/input/inject?mousedown=1", 4_000, binder)
        NxHttp.getText("$baseUrl/api/v1/input/inject?mouseup=1", 4_000, binder)
    }

    /**
     * 读取相机概览（主界面信息面板用）。
     *
     * 数据来源分两层，失败的字段留 null 而不是抛异常：
     *  - **HTTP（可靠）**：机型/固件/MAC 来自 `/api/v1/camera/info`，
     *    电量档位/充电/模式来自 `/api/v1/camera/status`
     *  - **shell（需要相机端装了 mod 的 telnet）**：相机时间、时间偏差、SD 卡空间、uptime
     *
     * 真机实测的限制（会写进 notes 让用户知道为什么某些项是未知）：
     *  - `battery_percent` 恒为 -1：相机硬件不暴露百分比，只能给档位
     *  - `drive`/`mode` 可能是 `UNKNOWN`：daemon 读不到
     *  - **镜头信息拿不到**：`st cap lens` 返回
     *    `Cann't execute capture command over MCB!`（相机 App 正占用 MCB 总线）
     */
    suspend fun readOverview(ip: String, includeShell: Boolean = true): CameraOverview =
        withContext(Dispatchers.IO) {
            val notes = mutableListOf<String>()
            var info: CameraInfo? = null
            var status: CameraStatus? = null

            try {
                info = info()
            } catch (e: Exception) {
                notes += "读取机型信息失败：${e.message?.take(60)}"
            }
            try {
                status = status()
            } catch (e: Exception) {
                notes += "读取状态失败：${e.message?.take(60)}"
            }

            var shellOk = false
            var camTime: String? = null
            var drift: Long? = null
            var sdFree: String? = null
            var sdTotal: String? = null
            var uptime: String? = null

            if (includeShell) {
                val res = NxShell.run(
                    ip,
                    listOf(
                        "date '+%Y-%m-%d %H:%M:%S %Z'",
                        "df -h /mnt/mmc 2>/dev/null | tail -n 1",
                        "cat /proc/uptime",
                    ),
                )
                if (res.ok) {
                    shellOk = true
                    val lines = res.output.lines().map { it.trim() }
                    camTime = lines.firstOrNull {
                        it.matches(Regex("""\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}.*"""))
                    }
                    // 时间偏差按**墙上时间**算：相机时区可能设错，
                    // 用户关心的是"相机显示的时间是否等于手机时间"
                    camTime?.let { ct ->
                        drift = runCatching {
                            val fmt = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
                            val cam = fmt.parse(ct.substring(0, 19))
                            val phoneWall = fmt.parse(fmt.format(java.util.Date()))
                            if (cam != null && phoneWall != null) (cam.time - phoneWall.time) / 1000 else null
                        }.getOrNull()
                    }
                    // df 行形如：/dev/mmcblk1p1 15G 3.1G 12G 21% /opt/storage/sdcard
                    lines.firstOrNull { it.contains("%") && it.split(Regex("\\s+")).size >= 5 }?.let { dfLine ->
                        val parts = dfLine.split(Regex("\\s+"))
                        if (parts.size >= 4) {
                            sdTotal = parts[1]
                            sdFree = parts[3]
                        }
                    }
                    lines.firstOrNull { it.matches(Regex("""\d+\.\d+ \d+\.\d+""")) }?.let { up ->
                        up.split(' ').firstOrNull()?.toDoubleOrNull()?.let { secs ->
                            val h = (secs / 3600).toInt()
                            val m = ((secs % 3600) / 60).toInt()
                            uptime = if (h > 0) "${h}小时${m}分" else "${m}分"
                        }
                    }
                } else {
                    notes += "读取相机系统信息失败：${res.error}（时间同步需要相机端 telnet 可用）"
                }
            }

            if (status?.batteryPercent == null) {
                notes += "相机硬件不提供电量百分比，只能显示档位"
            }

            CameraOverview(
                ip = ip,
                model = info?.model,
                firmware = info?.firmware,
                mac = info?.mac,
                batteryLevel = status?.batteryLevel,
                batteryPercent = status?.batteryPercent,
                charging = status?.charging,
                mode = status?.mode,
                drive = status?.drive,
                cameraTime = camTime,
                timeDriftSeconds = drift,
                sdFreeText = sdFree,
                sdTotalText = sdTotal,
                uptime = uptime,
                shellAvailable = shellOk,
                notes = notes,
            )
        }

    private fun urlEncode(s: String): String =
        java.net.URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    // ------------------------------------------------------------- 能力探测

    /**
     * 运行期探测相机端到底装了什么。
     * 三种形态的判据：
     *  - daemonOk    : `/api/v1/camera/info` 有响应 → 装了 nx-remote-controller-mod / NX-KS / NX-WA
     *  - cgiDirlistOk: 8080 `/cgi-bin/dirlist` 返回合法 JSON → 装了 NX-WA 的 8080 服务群
     *  - cgiThumbOk  : 8080 `/cgi-bin/thumb` 返回 image/jpeg → 缩略图可用
     */
    suspend fun detectCapabilities(): CameraCapabilities = withContext(Dispatchers.IO) {
        val notes = mutableListOf<String>()

        val daemonOk = try {
            val t = NxHttp.getText("$baseUrl/api/v1/camera/info", 3_000, binder)
            t.isNotBlank() && (t.trimStart().startsWith("{") || t.contains("model", true))
        } catch (e: Exception) {
            false
        }

        var cgiDirlistOk = false
        try {
            val cgiText = NxHttp.getText("$cgiUrl/cgi-bin/dirlist?p=", 6_000, binder)
            cgiDirlistOk = try {
                MiniJson.bool(MiniJson.parseObject(cgiText), "ok") == true
            } catch (e: Exception) {
                false
            }
        } catch (e: Exception) {
            // 8080 不存在：正常，说明只装了 80 端口的 daemon
        }

        // 目录是否可读（既可能是 CGI，也可能是 HTML 目录页回退）
        val listing = try {
            listDir("")
        } catch (e: Exception) {
            null
        }
        val listingOk = listing?.ok == true
        if (listingOk && listing!!.dirs.isEmpty() && listing.files.isEmpty()) {
            notes += "目录列表为空：SD 卡未插或 DCIM 为空"
        }

        var thumbOk = false
        if (listingOk) {
            try {
                val firstFolder = listFolders().firstOrNull()
                if (firstFolder != null) {
                    val firstMedia = listMedia(firstFolder).firstOrNull { it.isImage }
                    if (firstMedia != null) {
                        try {
                            fetchThumb(firstMedia.relPath, 320, 75)
                            thumbOk = true
                        } catch (e: NxException) {
                            notes += "缩略图生成失败（相机端 convert 可能缺失）：${e.message?.take(60)}"
                        }
                    } else {
                        notes += "未找到可做缩略图自检的图片"
                    }
                }
            } catch (e: Exception) {
                notes += "缩略图自检跳过：${e.message?.take(60)}"
            }
        }

        val mode = when {
            daemonOk && listingOk -> CameraMode.FULL_MOD
            listingOk || daemonOk -> CameraMode.STATIC_ONLY
            else -> CameraMode.STOCK
        }
        if (mode == CameraMode.STOCK) {
            notes += "端口 80 无可用服务：请在相机端启用遥控服务（见 README 的「前提」一节）"
        }

        CameraCapabilities(
            mode = mode,
            daemonOk = daemonOk,
            cgiDirlistOk = cgiDirlistOk,
            cgiThumbOk = thumbOk,
            notes = notes,
        )
    }
}
