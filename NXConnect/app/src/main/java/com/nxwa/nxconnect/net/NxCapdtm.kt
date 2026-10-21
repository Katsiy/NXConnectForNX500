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

import java.io.IOException

/**
 * 通过 `st cap capdtm` 读写拍摄参数。
 *
 * ## 用法（每一处都是踩过的坑）
 *
 * ```sh
 * st cap capdtm getusr ISO        # -> UserData is ISO_100 (0x50001)
 * st cap capdtm getvar TV         # -> Variable Data is 0x00000000_0000ffb0
 * st cap capdtm setvar EVC 0x0e 4 # -> VariableData is set
 * st cap capdtm usrlist           # 129 个参数
 * st cap capdtm varlist           # 66 个变量
 * ```
 *
 * ## ★ `getusr` 只认**短名**
 *
 * `usrlist` 里参数叫 `USERDATA_ISO`，但 `getusr` 必须传 `ISO`。
 * 带前缀会返回 `Invalid argument[2]=USERDATA_ISO` —— 看起来像"参数不存在"，
 * 我们曾因此误判"capdtm 只能读 1 个参数"，并绕了很大一圈。
 * 详见 `docs/capdtm-reference.md`。
 *
 * ## ★ 值变了 ≠ 被消费
 *
 * `setvar` 返回 `VariableData is set`、回读也变成了新值，**但这只证明变量被改写**，
 * 不等于相机真的用了它。实测在 S 模式下 `setvar TV` 后 `AV` 并未联动，
 * 说明可能只写到影子寄存器。
 * 因此本类只提供读写原语，**是否真的生效需要在真机上用相机屏幕核对**。
 */
class NxCapdtm(private val telnet: NxTelnet) {

    /** 读用户态参数，返回值名（如 `ISO_100`） */
    @Throws(IOException::class)
    fun getUsr(name: String): String? =
        parseUserData(telnet.run("st cap capdtm getusr $name", COMMAND_TIMEOUT_MS))

    /** 读用户态参数，同时拿到值 ID（如 `ISO_100` → 0x50001） */
    @Throws(IOException::class)
    fun getUsrFull(name: String): Pair<String, Long>? =
        parseUserDataFull(telnet.run("st cap capdtm getusr $name", COMMAND_TIMEOUT_MS))

    /** 读变量，返回原始 u32 */
    @Throws(IOException::class)
    fun getVar(name: String): Long? =
        parseVariable(telnet.run("st cap capdtm getvar $name", COMMAND_TIMEOUT_MS))

    /**
     * 写变量。
     *
     * @param value 原始值（会按 [length] 字节零扩展）
     * @return 相机是否回了 `VariableData is set`
     */
    @Throws(IOException::class)
    fun setVar(name: String, value: Long, length: Int = 4): Boolean {
        require(length in 1..4) { "长度只能是 1..4 字节" }
        val hex = String.format("0x%0${length * 2}x", value and maskOf(length))
        val out = telnet.run("st cap capdtm setvar $name $hex $length", COMMAND_TIMEOUT_MS)
        return out.contains("VariableData is set", ignoreCase = true)
    }

    /**
     * 读一次完整快照。
     *
     * 单项失败不影响其它项（对应字段为 null）—— 相机在不同拍摄模式下
     * 可能拒绝某些参数，不该因此整块失败。
     */
    @Throws(IOException::class)
    fun snapshot(): CapdtmSnapshot {
        fun usr(name: String): String? = try {
            getUsr(name)
        } catch (_: IOException) {
            null
        }
        fun variable(name: String): Long? = try {
            getVar(name)
        } catch (_: IOException) {
            null
        }
        return CapdtmSnapshot(
            iso = usr("ISO"),
            whiteBalance = usr("WB"),
            metering = usr("METERING"),
            driveMode = usr("DRIVE"),
            imageQuality = usr("IMAGEQUALITY"),
            imageSize = usr("IMAGESIZE"),
            afMode = usr("AFMODE"),
            shootingMode = usr("SHOOTINGMODE"),
            apertureRaw = variable("AV"),
            shutterRaw = variable("TV"),
            isoRaw = variable("SV"),
            evCompRaw = variable("EVC"),
            bulbTimeRaw = variable("BULBTIME"),
        )
    }

    private fun maskOf(length: Int): Long = when (length) {
        1 -> 0xFFL
        2 -> 0xFFFFL
        3 -> 0xFFFFFFL
        else -> 0xFFFFFFFFL
    }

    internal companion object {
        /** 单条命令的超时；单核相机上 `st` 偶尔要好几秒 */
        const val COMMAND_TIMEOUT_MS = 10_000

        /** `UserData is ISO_100 (0x50001)` */
        val USERDATA_RE = Regex("UserData\\s+is\\s+(\\S+)(?:\\s+\\((0x[0-9A-Fa-f]+)\\))?")

        /** `Variable Data is 0x00000000_0000ffb0` */
        val VARIABLE_RE = Regex("0x([0-9A-Fa-f]{8})_([0-9A-Fa-f]{8})")

        internal fun parseHex(text: String): Long? = try {
            // 注意：Kotlin 的 Long 没有 parseLong，要用 toLong(radix)
            text.trim().removePrefix("0x").removePrefix("0X").toLong(16)
        } catch (_: NumberFormatException) {
            null
        }

        /** 从 `UserData is ISO_100 (0x50001)` 里取值名 */
        internal fun parseUserData(output: String): String? =
            USERDATA_RE.find(output)?.groupValues?.get(1)

        /** 从 `UserData is ISO_100 (0x50001)` 里取值名与值 ID */
        internal fun parseUserDataFull(output: String): Pair<String, Long>? {
            val m = USERDATA_RE.find(output) ?: return null
            val value = m.groupValues[1]
            val hex = m.groupValues.getOrNull(2)?.takeIf { it.isNotBlank() }?.let { parseHex(it) }
            return if (hex != null) value to hex else null
        }

        /**
         * 从 `Variable Data is 0x00000000_0000ffb0` 里取低 32 位。
         *
         * 相机输出是 `<高32位>_<低32位>`；有符号量（如 TV=-80 存成 0x0000ffb0）
         * 这里按**无符号**返回，解码时自行按需转有符号。
         */
        internal fun parseVariable(output: String): Long? {
            val m = VARIABLE_RE.find(output) ?: return null
            return parseHex(m.groupValues[2])
        }
    }
}

/**
 * 一次参数快照。
 *
 * 名字型字段（[iso]、[whiteBalance] …）来自 `getusr`，值是相机给的**确切枚举名**，
 * 不需要任何换算 —— 这是本功能最可靠的部分。
 *
 * `*Raw` 字段来自 `getvar`，是原始定点数，**编码尚未校准**，
 * 不要直接当光圈/快门显示（见 [ExposureCodec]）。
 */
data class CapdtmSnapshot(
    val iso: String? = null,
    val whiteBalance: String? = null,
    val metering: String? = null,
    val driveMode: String? = null,
    val imageQuality: String? = null,
    val imageSize: String? = null,
    val afMode: String? = null,
    val shootingMode: String? = null,
    val apertureRaw: Long? = null,
    val shutterRaw: Long? = null,
    val isoRaw: Long? = null,
    val evCompRaw: Long? = null,
    val bulbTimeRaw: Long? = null,
) {
    /** 是否有任何可用信息 */
    val isEmpty: Boolean
        get() = iso == null && whiteBalance == null && metering == null &&
            driveMode == null && imageQuality == null && afMode == null

    /** 主界面展示用的「ISO · 白平衡 · 测光」一行 */
    fun summaryLine(): String {
        val parts = buildList {
            iso?.let { add("ISO ${CapdtmLabels.iso(it)}") }
            whiteBalance?.let { add(CapdtmLabels.whiteBalance(it)) }
            metering?.let { add(CapdtmLabels.metering(it)) }
            driveMode?.let { add(CapdtmLabels.drive(it)) }
        }
        return parts.joinToString(" · ")
    }
}
