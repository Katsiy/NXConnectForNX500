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
package com.nxwa.nxconnect.remote

import com.nxwa.nxconnect.net.CameraProfile

/**
 * 相机按键 → X11 keysym 映射。
 *
 * 逐字来自相机端 `web_root/js/keyinput.js`，注入路径为
 * `GET /api/v1/input/inject?key=<keysym>`（相机端走 libXtst 注入 X 事件）。
 *
 * 拍摄语义：
 *  - [S1] 半按快门（对焦） = Super_L
 *  - [S2] 全按快门（拍摄） = Super_R
 *  - 另有更直接的 `GET /api/v1/shutter/normal|silent`（NX500 静音快门）
 */
object KeyMap {
    const val UP = "KP_Up"
    const val DOWN = "KP_Down"
    const val LEFT = "KP_Left"
    const val RIGHT = "KP_Right"
    const val OK = "KP_Enter"
    const val MENU = "Menu"
    const val DEL = "KP_Delete"

    /** 快门半按/对焦 */
    const val S1 = "Super_L"

    /** 快门全按/拍摄 */
    const val S2 = "Super_R"

    const val AEL = "XF86Favorites"
    const val REC = "XF86WebCam"
    const val FN = "XF86HomePage"

    /** NX500 的 EV 键；NX1 上是 XF86WWW */
    const val EV_NX500 = "XF86Reload"
    const val EV_NX1 = "XF86WWW"

    const val PB = "XF86Tools"
    const val AF_MODE = "XF86TaskPane"
    const val WB = "XF86Launch6"
    const val ISO = "XF86Launch7"
    const val AF_ON = "XF86Launch9"
    const val LIGHT = "XF86TouchpadToggle"
    const val MF_ZOOM = "XF86TouchpadOff"
    const val WIFI = "XF86Mail"

    /** Wi-Fi 拍摄模式：**仅 NX300**（上游 `keyinput.js`: `MODE_WIFI` → `F3`） */
    const val MODE_WIFI = "F3"

    const val JOG1_CW = "XF86ScrollUp"
    const val JOG1_CCW = "XF86ScrollDown"
    const val JOG2_CW = "parenleft"
    const val JOG2_CCW = "parenright"
    const val JOG_CW = "XF86AudioNext"
    const val JOG_CCW = "XF86AudioPrev"

    const val MODE_P = "XF86MailForward"
    const val MODE_A = "XF86Save"
    const val MODE_S = "XF86Documents"
    const val MODE_M = "XF86Battery"
    const val MODE_CUSTOM1 = "XF86WLAN"
    const val MODE_CUSTOM2 = "XF86Bluetooth"

    // ---- 以下为 A/B/C 组补充，同样逐字来自 keyinput.js ----

    /** 测光模式 */
    const val METER = "Hiragana_Katakana"

    /** 景深预览 */
    const val DEPTH = "Henkan_Mode"

    /** 智能模式 / 场景模式 / SAS */
    const val MODE_SMART = "XF86Reply"
    const val MODE_SCENE = "XF86Send"
    const val MODE_SAS = "XF86KbdBrightnessDown"

    /** 驱动模式 */
    const val DRIVE_SINGLE = "XF86Search"
    const val DRIVE_CONTI_N = "XF86Go"
    const val DRIVE_CONTI_H = "XF86Finance"
    const val DRIVE_TIMER = "XF86Game"
    const val DRIVE_BRACKET = "XF86Shop"

    const val WHEEL_CW = "Left"
    const val WHEEL_CCW = "Right"

    /**
     * 按键分组定义，供遥控面板渲染。
     *
     * `hold = true` 的项表示应当用**按下/松开**语义（keydown/keyup）而不是单击：
     * 相机上这些键本来就是"按住期间生效"的，例如 AE 锁定、曝光补偿调节。
     * 用单击会变成"按一下就松"，与机身操作手感不一致。
     */
    data class KeyDef(val label: String, val keysym: String, val hold: Boolean = false)

    /** 一个机型对应的三组按键 */
    data class Groups(
        val exposure: List<KeyDef>,
        val mode: List<KeyDef>,
        val nav: List<KeyDef>,
    )

    /** B 组：方向键 + 转盘 + 滚轮 —— 与机型无关，三种机型共用 */
    val GROUP_NAV: List<KeyDef> = listOf(
        KeyDef("▲", UP),
        KeyDef("◀", LEFT),
        KeyDef("▶", RIGHT),
        KeyDef("▼", DOWN),
        KeyDef("OK", OK),
        KeyDef("MENU", MENU),
        KeyDef("Fn", FN),
        KeyDef("播放", PB),
        KeyDef("删除", DEL),
        KeyDef("转盘 −", JOG_CCW),
        KeyDef("转盘 +", JOG_CW),
        KeyDef("副盘 −", JOG1_CCW),
        KeyDef("副盘 +", JOG1_CW),
        KeyDef("滚轮 −", WHEEL_CCW),
        KeyDef("滚轮 +", WHEEL_CW),
        KeyDef("录制", REC),
    )

    /**
     * 按机型档案生成三组按键。
     *
     * 三处按机型的差异（依据见 `docs/model-compatibility.md`）：
     *
     * 1. **曝光补偿的 keysym**：NX1 用 `XF86WWW`，其余用 `XF86Reload` ——
     *    上游 `keyinput.js` 里唯一按机型分支的键。用错的话该键在对应机型上无反应。
     * 2. **自定义 1/2 模式**：网页 UI 标记为 `nx1-nx500-only`，NX300 没有。
     * 3. **Wi-Fi 模式**：网页 UI 标记为 `nx300-only`，keysym 是 `F3`。
     */
    fun groupsFor(profile: CameraProfile): Groups {
        val exposure = listOf(
            KeyDef("ISO", ISO),
            KeyDef("白平衡", WB),
            KeyDef("测光", METER),
            KeyDef("对焦模式", AF_MODE),
            KeyDef("对焦触发", AF_ON),
            KeyDef("景深预览", DEPTH),
            // ↓ 唯一随机型变化的 keysym
            KeyDef("曝光补偿", profile.evKeysym, hold = true),
            KeyDef("AE 锁定", AEL, hold = true),
        )

        val mode = mutableListOf(
            KeyDef("P", MODE_P),
            KeyDef("A", MODE_A),
            KeyDef("S", MODE_S),
            KeyDef("M", MODE_M),
            KeyDef("智能", MODE_SMART),
            KeyDef("场景", MODE_SCENE),
            // SAS 的 keysym 相同，但相机端行为不同：NX500 是 SamsungAutoShot，
            // NX300 是「镜头优先」。标签保持中性。
            KeyDef("SAS", MODE_SAS),
        )
        if (profile.hasCustomModes) {
            mode += KeyDef("自定义1", MODE_CUSTOM1)
            mode += KeyDef("自定义2", MODE_CUSTOM2)
        }
        if (profile.hasWifiMode) {
            mode += KeyDef("Wi-Fi", MODE_WIFI)
        }
        mode += KeyDef("单张", DRIVE_SINGLE)
        mode += KeyDef("连拍", DRIVE_CONTI_N)
        mode += KeyDef("高速连拍", DRIVE_CONTI_H)
        mode += KeyDef("定时", DRIVE_TIMER)
        mode += KeyDef("包围", DRIVE_BRACKET)

        return Groups(exposure, mode, GROUP_NAV)
    }

    /** NX500 的默认分组（保留旧引用；与 `groupsFor(CameraProfile.NX500)` 等价） */
    private val DEFAULT_GROUPS: Groups = groupsFor(CameraProfile.NX500)

    /** A 组：曝光相关专用键（NX500 版） */
    val GROUP_EXPOSURE: List<KeyDef> = DEFAULT_GROUPS.exposure

    /** C 组：拍摄模式与驱动模式（NX500 版） */
    val GROUP_MODE: List<KeyDef> = DEFAULT_GROUPS.mode

    /** 兼容旧引用：所有按键的扁平列表（显示名 → keysym） */
    val PANEL: List<Pair<String, String>> =
        (GROUP_EXPOSURE + GROUP_MODE + GROUP_NAV).map { it.label to it.keysym }
}
