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

/**
 * 把 capdtm 返回的枚举名翻成界面文案。
 *
 * 枚举名来自真机 `st cap capdtm usrlist`（NX500 固件 1.12），
 * 完整清单见 `docs/capdtm-reference.md`。
 *
 * 设计原则：**未知值也要能显示**。所有函数在查不到映射时回退到
 * "去掉前缀 + 首字母大写"的形式，而不是返回空 —— 相机固件一改，
 * 界面就整块空掉是最糟的体验。
 */
object CapdtmLabels {

    /** `ISO_100` → `100`；`ISO_AUTO` → `自动` */
    fun iso(raw: String): String = when (raw) {
        "ISO_AUTO" -> "自动"
        else -> raw.removePrefix("ISO_").replace('_', '.').ifBlank { raw }
    }

    private val WB = mapOf(
        "WB_AUTO" to "自动",
        "WB_DAYLIGHT" to "日光",
        "WB_CLOUDY" to "阴天",
        "WB_FLUORESCENT" to "荧光灯",
        "WB_TUNGSTEN" to "白炽灯",
        "WB_FLASH" to "闪光灯",
        "WB_CUSTOM" to "自定义",
    )

    fun whiteBalance(raw: String): String = WB[raw] ?: fallback(raw, "WB_")

    private val METERING = mapOf(
        "METERING_MULTI" to "多重测光",
        "METERING_CENTERWEIGHTED" to "中央重点",
        "METERING_SPOT" to "点测光",
    )

    fun metering(raw: String): String = METERING[raw] ?: fallback(raw, "METERING_")

    private val DRIVE = mapOf(
        "DRIVE_SINGLE" to "单张",
        "DRIVE_CONTI_N" to "连拍",
        "DRIVE_CONTI_H" to "高速连拍",
        "DRIVE_TIMER" to "定时",
        "DRIVE_BRACKET" to "包围曝光",
    )

    fun drive(raw: String): String = DRIVE[raw] ?: fallback(raw, "DRIVE_")

    private val QUALITY = mapOf(
        "IMAGEQUALITY_RAW_SUPERFINE" to "RAW + 超精细",
        "IMAGEQUALITY_RAW_FINE" to "RAW + 精细",
        "IMAGEQUALITY_RAW_NORMAL" to "RAW + 标准",
        "IMAGEQUALITY_RAW" to "仅 RAW",
        "IMAGEQUALITY_SUPERFINE" to "超精细",
        "IMAGEQUALITY_FINE" to "精细",
        "IMAGEQUALITY_NORMAL" to "标准",
    )

    fun imageQuality(raw: String): String = QUALITY[raw] ?: fallback(raw, "IMAGEQUALITY_")

    private val AF = mapOf(
        "AFMODE_SINGLE" to "单次",
        "AFMODE_CONTINUOUS" to "连续",
        "AFMODE_MF" to "手动",
    )

    fun afMode(raw: String): String = AF[raw] ?: fallback(raw, "AFMODE_")

    private val SHOOTING = mapOf(
        "SHOOTINGMODE_SHUTTERSPEED" to "S 快门优先",
        "SHOOTINGMODE_APERTURE" to "A 光圈优先",
        "SHOOTINGMODE_MANUAL" to "M 手动",
        "SHOOTINGMODE_PROGRAM" to "P 程序",
        "SHOOTINGMODE_AUTO" to "自动",
        "SHOOTINGMODE_SMART" to "智能",
        "SHOOTINGMODE_SCENE" to "场景",
        "SHOOTINGMODE_MOVIE" to "视频",
    )

    fun shootingMode(raw: String): String = SHOOTING[raw] ?: fallback(raw, "SHOOTINGMODE_")

    /** `IMAGESIZE_NORMAL_28M` → `28M` */
    fun imageSize(raw: String): String = raw
        .removePrefix("IMAGESIZE_NORMAL_")
        .takeIf { it != raw } ?: fallback(raw, "IMAGESIZE_")

    /** 通用回退：去掉前缀、下划线换空格 */
    private fun fallback(raw: String, prefix: String): String {
        val stripped = raw.removePrefix(prefix).replace('_', ' ').trim()
        return stripped.ifBlank { raw }
    }
}
