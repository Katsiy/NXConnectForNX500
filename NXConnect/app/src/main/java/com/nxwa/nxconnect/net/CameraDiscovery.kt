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

import android.content.Context
import android.net.NetworkCapabilities
import android.util.Log
import com.nxwa.nxconnect.data.CameraInfo
import com.nxwa.nxconnect.data.MiniJson
import com.nxwa.nxconnect.data.NxNetworkFacts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 相机发现。
 *
 * 分两步：
 *  1. 网络候选绑定：NX500 做热点时**没有外网**，手机同时有蜂窝数据时请求会被路由到蜂窝，
 *     所以要把进程依次绑到每个 Wi-Fi/以太网网络上再探测。
 *  2. 地址并发探测：命中判据是端口 80 的 `/api/v1/camera/info` 返回 200。
 *
 * 注意：这些端点**不是原厂固件自带的**，由相机端 nx-remote-controller-daemon 提供，
 * 所以要能区分「能连上但不是相机」与「是相机但 mod 没启动」。
 */
object CameraDiscovery {

    private const val TAG = "CameraDiscovery"

    sealed class Result {
        data class Found(
            val ip: String,
            val info: CameraInfo,
            val hasDaemon: Boolean = true,
        ) : Result()

        /** 端口 80 通了但没有 daemon —— 相机可能在线但 mod 未启动 */
        data class CameraWithoutDaemon(val ip: String, val hint: String) : Result()

        data class NotFound(val tried: Int, val reason: String) : Result()
    }

    private sealed class Probe {
        data class Daemon(val info: CameraInfo) : Probe()
        data class HttpOnly(val hint: String) : Probe()
        object Dead : Probe()
    }

    private class Scan(
        val found: MutableList<Pair<String, Probe.Daemon>> = mutableListOf(),
        val httpOnly: MutableList<Pair<String, Probe.HttpOnly>> = mutableListOf(),
        var tried: Int = 0,
    )

    /**
     * 探测相机。
     * @param explicitIp 用户手填的地址；给定时只验证它，不做全网段扫描
     */
    suspend fun discover(
        ctx: Context,
        explicitIp: String? = null,
        onProgress: (String) -> Unit = {},
    ): Result = withContext(Dispatchers.IO) {
        val scan = Scan()

        if (!explicitIp.isNullOrBlank()) {
            val ip = explicitIp.trim()
            onProgress("正在验证 $ip …")
            scan.tried = 1
            // ★ 同样要绑网络：用户手填 IP 时，手机多半正连着"无外网的相机热点"，
            //   不绑的话请求会走蜂窝，必然超时（与广播发现是同一个根因）。
            NetworkBinder.bindTo(orderedNetworks(ctx).firstOrNull())
            val result = when (val p = probe(ip)) {
                is Probe.Daemon -> Result.Found(ip, p.info)
                is Probe.HttpOnly -> Result.CameraWithoutDaemon(ip, p.hint)
                Probe.Dead -> Result.NotFound(1, "地址 $ip 无响应：请确认手机已连上相机的网络")
            }
            NetworkBinder.unbind()
            return@withContext result
        }

        val seen = mutableSetOf<String>()

        // ---- 0) 按网络逐个试：**先绑网络，再做 UDP 发现，最后做 HTTP 扫描** ----
        //
        // ★ 顺序很关键（这是"相机/手机做热点都连不上"的根因）：
        //   UDP 广播必须**在绑定到相机所在网络之后**才发/收。
        //   手机连着"无外网的相机热点"时，系统默认路由通常仍是蜂窝，
        //   不绑网络就直接发广播 → 从蜂窝网卡出去，相机永远收不到。
        //   旧实现把 UDP 发现放在绑定循环之前，所以热点场景下几乎必然失败。
        for (net in orderedNetworks(ctx) + listOf<android.net.Network?>(null)) {
            NetworkBinder.bindTo(net)

            // 0.1) 在该网络上做 UDP 广播发现
            if (net != null) {
                onProgress("正在监听相机广播（UDP ${NxBeaconParser.BROADCAST_PORT}）…")
                val prefix = NetworkBinder.linkProperties(ctx)?.linkAddresses
                    ?.firstOrNull { it.address is java.net.Inet4Address }
                    ?.address?.hostAddress?.substringBeforeLast('.')?.plus(".")
                val beacons = NxUdpDiscovery.listen(2_000, ctx, net, prefix)
                if (beacons.isNotEmpty()) {
                    Log.i(TAG, "网络 $net 上广播发现 ${beacons.size} 台相机：${beacons.map { it.ip }}")
                }
                for (b in beacons) {
                    if (!seen.add(b.ip)) continue
                    onProgress("广播发现 ${b.ip}，正在验证…")
                    when (val p = probe(b.ip)) {
                        is Probe.Daemon -> {
                            val info =
                                if (p.info.model == null && b.model != null) b.toCameraInfo() else p.info
                            return@withContext Result.Found(b.ip, info)
                        }

                        is Probe.HttpOnly -> {
                            scan.httpOnly.add(Pair(b.ip, p))
                            scan.tried++
                        }

                        Probe.Dead -> {
                            scan.tried++
                            Log.w(TAG, "广播里的 ${b.ip} 无法通过 HTTP 访问")
                        }
                    }
                }
            }

            // 0.2) 该网络的 HTTP 地址扫描
            val cands = roundOne(ctx).filter { seen.add(it) }
            if (cands.isEmpty()) continue

            onProgress("网络 ${net?.toString()?.takeLast(8) ?: "默认"}：探测 ${cands.size} 个地址…")
            coroutineScope {
                cands.map { ip ->
                    async {
                        val p = probe(ip)
                        synchronized(scan) {
                            scan.tried++
                            when (p) {
                                is Probe.Daemon -> scan.found.add(Pair(ip, p))
                                is Probe.HttpOnly -> scan.httpOnly.add(Pair(ip, p))
                                Probe.Dead -> Unit
                            }
                        }
                    }
                }.forEach { it.await() }
            }

            scan.found.firstOrNull()?.let { (ip, d) ->
                Log.i(TAG, "found daemon at $ip")
                return@withContext Result.Found(ip, d.info)
            }
        }

        // ---- 第二轮：本网段都没找到，才试文档里的历史地址（相机换过网段就是这种情况）----
        val fallback = roundTwo(ctx).filter { seen.add(it) }
        if (fallback.isNotEmpty()) {
            onProgress("本网段未找到，回退探测 ${fallback.size} 个历史地址…")
            scanFound(scan, fallback)
            scan.found.firstOrNull()?.let { (ip, d) ->
                Log.i(TAG, "found daemon at $ip (fallback)")
                return@withContext Result.Found(ip, d.info)
            }
        }

        // ---- 第三轮：整段扫一遍 ----
        //
        // ★ 为什么需要这一步：相机做热点时的自身地址、以及相机接入手机热点时拿到的地址，
        //   都由对端的 DHCP 池决定，**猜不得**。AP_HOSTS 那种"常见主机位"列表
        //   在真机上就漏过（实测相机落在 .39，而列表里没有）。
        //   既然广播可能被 AP 隔离挡住，就补一次全 /24 扫描：64 并发下约 4 秒。
        val fullScan = fullSubnetCandidates(ctx).filter { seen.add(it) }
        if (fullScan.isNotEmpty()) {
            onProgress("正在扫描本网段 ${fullScan.size} 个地址…")
            NetworkBinder.bindTo(orderedNetworks(ctx).firstOrNull())
            scanFound(scan, fullScan)
            scan.found.firstOrNull()?.let { (ip, d) ->
                Log.i(TAG, "found daemon at $ip (full subnet scan)")
                return@withContext Result.Found(ip, d.info)
            }
        }

        NetworkBinder.unbind()
        scan.httpOnly.firstOrNull()?.let { (ip, h) -> return@withContext Result.CameraWithoutDaemon(ip, h.hint) }
        Result.NotFound(scan.tried, "已探测 ${scan.tried} 个地址，均无响应")
    }

    /** 并发探测一批地址，把结果并进 [scan] */
    private suspend fun scanFound(scan: Scan, ips: List<String>) = coroutineScope {
        // 限流：一次最多 64 个并发，避免把手机网络栈打满
        ips.chunked(64).forEach { chunk ->
            chunk.map { ip ->
                async {
                    val p = probe(ip)
                    synchronized(scan) {
                        scan.tried++
                        when (p) {
                            is Probe.Daemon -> scan.found.add(Pair(ip, p))
                            is Probe.HttpOnly -> scan.httpOnly.add(Pair(ip, p))
                            Probe.Dead -> Unit
                        }
                    }
                }
            }.forEach { it.await() }
        }
    }

    /**
     * 本机所在网段的全部可用主机位（.1 ~ .254）。
     * 只在前面几轮都没命中时才用，代价是几秒钟。
     */
    private suspend fun fullSubnetCandidates(ctx: Context): List<String> = withContext(Dispatchers.IO) {
        val prefixes = mutableListOf<String>()
        NetworkBinder.localIpv4(ctx)?.takeIf { it.count { c -> c == '.' } == 3 }?.let {
            prefixes += it.substringBeforeLast('.') + "."
        }
        // 兜底：直接枚举本机所有局域网网卡（绑定可能失败，此时 linkProperties 拿不到）
        prefixes += NxUdpDiscovery.localPrefixes()
        prefixes.distinct()
            .flatMap { prefix -> (1..254).map { "$prefix$it" } }
            .distinct()
    }

    /** 第一轮：本机所在网段的候选（含网关） */
    private suspend fun roundOne(ctx: Context): List<String> = withContext(Dispatchers.IO) {
        candidates(ctx)
    }

    /** 第二轮：文档记录过的历史地址 */
    private suspend fun roundTwo(ctx: Context): List<String> = withContext(Dispatchers.IO) {
        fallbackCandidates(
            localIpv4 = NetworkBinder.localIpv4(ctx),
            gateway = NetworkBinder.linkProperties(ctx)?.routes?.firstOrNull()?.gateway?.hostAddress,
        )
    }

    private fun fallbackCandidates(localIpv4: String?, gateway: String?): List<String> =
        fallbackCandidatesForTest(localIpv4, gateway)

    /**
     * 验证某个地址。
     *
     * @param port 显式端口；为 null 时走默认 80。给测试与非标准端口部署留出余地。
     */
    suspend fun verify(ip: String, port: Int? = null): Result {
        if (port == null) {
            return when (val p = probe(ip.trim())) {
                is Probe.Daemon -> Result.Found(ip.trim(), p.info)
                is Probe.HttpOnly -> Result.CameraWithoutDaemon(ip.trim(), p.hint)
                Probe.Dead -> Result.NotFound(1, "地址 $ip 无响应")
            }
        }
        val target = ip.trim()
        val client = NxCamera(target, port, port, HttpBinder.None)
        // 1) daemon 是否在
        try {
            val raw = NxHttp.getText("${client.baseUrl}/api/v1/camera/info", 3_000, HttpBinder.None)
            if (raw.isNotBlank() && raw.trimStart().startsWith("{")) {
                return Result.Found(target, client.parseInfo(raw))
            }
        } catch (e: Exception) {
            // 继续下面判定
        }
        // 2) 端口是否有别的 HTTP 服务
        try {
            val html = NxHttp.getText("${client.baseUrl}/", 2_000, HttpBinder.None)
            if (html.isNotBlank()) {
                return Result.CameraWithoutDaemon(
                    target,
                    if (html.contains("nx-remote-controller", true)) {
                        "检测到遥控服务页面，但 API 未响应（daemon 可能启动中）"
                    } else {
                        "端口 $port 有 HTTP 服务，但不是 NX 遥控 daemon"
                    },
                )
            }
        } catch (e: Exception) {
            // 落到无响应
        }
        return Result.NotFound(1, "地址 $target:$port 无响应")
    }

    /**
     * 网络尝试顺序。
     *
     * 排序依据（经验性，但都有理由）：
     *  1. Wi-Fi 优先——相机几乎总是通过 Wi-Fi 连的
     *  2. 同类型里**没有外网**的优先——相机热点/相机所在网段通常 validated=false，
     *     而手机若同时连着家用路由，那个网络是 validated=true，先试它会白跑一轮
     *  3. 以太网次之
     *  4. 最后用 null（系统默认路由）兜底——用户可能把相机接在家里路由器上，
     *     手机也在同一网段，此时默认路由本来就是对的
     */
    private fun orderedNetworks(ctx: Context): List<android.net.Network> {
        val cm = NetworkBinder.connectivity(ctx)
        fun transportRank(n: android.net.Network): Int {
            val c = cm?.getNetworkCapabilities(n)
            return when {
                c?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true -> 0
                c?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true -> 2
                else -> 3
            }
        }

        return NetworkBinder.candidateNetworks(ctx).sortedWith(
            compareBy(
                { transportRank(it) + if (NetworkBinder.hasInternet(ctx, it)) 1 else 0 },
            ),
        )
    }

    /**
     * 候选地址：网关 → 已知历史地址 → 本机同网段常见主机位。
     *
     * 注意实际组网的两种情况（真机都出现过）：
     *  - 相机做热点：相机常常就是网关（.1），也可能自报 .90/.100
     *  - 相机接家用路由器：**相机是 DHCP 客户端，IP 随机**（实测 192.168.5.39），
     *    此时靠这里的主机位列表只能碰运气，真正的解法是 UDP 广播（见 discover）
     */
    fun candidates(ctx: Context): List<String> {
        return candidatesForTest(
            localIpv4 = NetworkBinder.localIpv4(ctx),
            gateway = NetworkBinder.linkProperties(ctx)?.routes?.firstOrNull()?.gateway?.hostAddress,
        )
    }

    /**
     * 候选地址推导（不触网）。
     *
     * 真机实测：相机接家用路由器时是 DHCP 客户端，IP 是 `192.168.5.39`，
     * 而网关是 `192.168.5.1` —— 所以这里必须包含"本机同网段的常见主机位"。
     * 历史网段（192.168.0.x / 192.168.43.x）**不混进来**，否则每次探测都要为
     * 不在本网段的地址白等超时（实测会拖慢十几秒）；它们由 [fallbackCandidatesForTest]
     * 作为第二轮兜底。
     */
    fun candidatesForTest(localIpv4: String?, gateway: String? = null): List<String> {
        val out = LinkedHashSet<String>()
        gateway?.let { out += it }

        if (localIpv4 != null && localIpv4.count { it == '.' } == 3) {
            val prefix = localIpv4.substringBeforeLast('.') + "."
            // 相机自建热点时占 .1/.90/.100；接路由器时是 DHCP 池里的随机地址，
            // 补一批常见主机位提高命中率（真机实测落在 .39）
            (NxNetworkFacts.AP_HOSTS + listOf(2, 20, 30, 39, 50, 100, 103, 104, 105))
                .forEach { out += "$prefix$it" }
        } else {
            NxNetworkFacts.AP_SUBNETS.forEach { sub ->
                NxNetworkFacts.AP_HOSTS.forEach { out += "$sub$it" }
            }
        }
        return out.filter { it.count { c -> c == '.' } == 3 }.distinct()
    }

    /**
     * 第二轮兜底：文档里记录过的历史地址。
     * 只有在第一轮（本网段）完全没找到相机时才值得一试——相机接的网段变了就是这种情况。
     */
    fun fallbackCandidatesForTest(localIpv4: String?, gateway: String? = null): List<String> {
        val tried = candidatesForTest(localIpv4, gateway).toSet()
        return NxNetworkFacts.KNOWN_IPS.filter { it !in tried }
    }

    private suspend fun probe(ip: String): Probe {
        val base = "http://$ip"

        // 1) daemon 的 API 是权威判据
        val infoText = try {
            withTimeoutOrNull(2_500) { NxHttp.getText("$base/api/v1/camera/info", 2_200) }
        } catch (e: Exception) {
            null
        }
        if (!infoText.isNullOrBlank()) {
            return Probe.Daemon(parseInfo(infoText))
        }

        // 2) 退一步看端口 80 是否有别的 HTTP 服务
        val plain = try {
            withTimeoutOrNull(2_000) { NxHttp.getText("$base/", 1_600) }
        } catch (e: Exception) {
            null
        }
        if (plain != null) {
            val lower = plain.lowercase()
            val hint = when {
                lower.contains("nx-remote-controller") -> "检测到遥控服务页面，但 API 未响应（daemon 可能启动中）"
                lower.contains("<title>") -> "端口 80 有其它 HTTP 服务，可能不是相机"
                else -> "端口 80 有响应，但内容无法识别"
            }
            return Probe.HttpOnly(hint)
        }
        return Probe.Dead
    }

    internal fun parseInfo(text: String): CameraInfo = try {
        val o = MiniJson.parseObject(text)
        CameraInfo(
            model = MiniJson.strAny(o, "model", "camera", "modelName"),
            firmware = MiniJson.strAny(o, "firmware", "version", "firmwareVersion"),
            mac = MiniJson.strAny(o, "mac", "macAddress", "mac_address"),
            raw = text,
        )
    } catch (e: Exception) {
        CameraInfo(raw = text)
    }
}
