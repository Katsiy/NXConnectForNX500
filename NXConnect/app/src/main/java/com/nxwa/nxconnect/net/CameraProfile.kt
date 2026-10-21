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
 * 机型档案：把**与机型相关**的那几个常量集中到一处。
 *
 * ## 依据
 *
 * 全部来自上游 `nx-remote-controller-mod` 的源码与官方发布包，
 * 详细考证见 `docs/model-compatibility.md`。要点：
 *
 * 1. 守护进程把机型分两档：`is_new_nx_model()` = NX1/NX500，
 *    其余（NX300/NX300M/NX2000/NX3000）是 old 档。
 * 2. **OSD 帧宽**：new 档 720，old 档 800；**帧高恒 480**。
 *    块尺寸 80×40 是守护进程的编译期常量（`osd.c` 的 `#define`），**不分机型**。
 * 3. **取景分辨率**与 OSD 同宽：720×480 或 800×480。
 * 4. **EV 键 keysym**：上游 `keyinput.js` 里唯一按机型分支的键 ——
 *    NX1 用 `XF86WWW`，其余用 `XF86Reload`。
 * 5. `MODE_CUSTOM1/2` 是 NX1/NX500 专有；`MODE_WIFI` 是 NX300 专有
 *    （见网页 UI 的 `nx1-nx500-only` / `nx300-only` 标记）。
 * 6. **NX1 在 EVF（取景器）模式下，`/api/v1/liveview/get` 返回 0 字节**
 *    （`liveview.c` 里显式 `Content-Length: 0`），所以用 NX1 时必须在 LCD 上。
 *
 * ## 为什么要单独抽出来
 *
 * 这些值原本散落在 `NxOsd`、`NxOsdValidator`、`OsdView`、`LiveViewView`、`KeyMap`
 * 里，而且全都是按 NX500 写死的。集中到一处之后，加机型的改动面只有这个文件
 * 加各处的引用点。
 *
 * ## 未知机型怎么办
 *
 * 按 new 档（720×480）处理 —— 这是**协议里最常见的一档**，且 NX500 已验证。
 * `verified = false` 会记录下来，供 UI 提示"未验证机型"。
 *
 * @param model   `/api/v1/camera/info` 返回的 `model` 原值（可能为 null）
 * @param osdFrameW OSD 帧宽，也是取景宽
 * @param osdFrameH OSD 帧高，也是取景高（恒 480）
 * @param evKeysym  曝光补偿键的 X keysym
 * @param hasCustomModes 是否有自定义 1/2 拍摄模式
 * @param hasWifiMode    是否有独立的 Wi-Fi 模式键
 * @param verified       这个机型是否被真机验证过
 */
data class CameraProfile(
    val model: String?,
    val osdFrameW: Int,
    val osdFrameH: Int,
    val evKeysym: String,
    val hasCustomModes: Boolean,
    val hasWifiMode: Boolean,
    val verified: Boolean,
) {

    /** 该机型一帧 OSD 的块总数：9×12 = 108（new）或 10×12 = 120（old） */
    val blockCount: Int get() = osdFrameW / BLOCK_W * (osdFrameH / BLOCK_H)

    /** 展示用的机型名；未知时给出可读的替代 */
    val displayName: String get() = model?.takeIf { it.isNotBlank() } ?: "未知机型"

    companion object {
        // 块尺寸是守护进程的编译期常量，不分机型（见 osd.c 的 #define）
        const val BLOCK_W = 80
        const val BLOCK_H = 40

        /** new 档：NX1 / NX500 */
        const val NEW_FRAME_W = 720

        /** old 档：NX300 / NX300M / NX2000 / NX3000 */
        const val OLD_FRAME_W = 800

        const val FRAME_H = 480

        /**
         * EV 键 keysym —— 与 `remote.KeyMap.EV_NX1` / `EV_NX500` 保持一致。
         *
         * 这里用字面量而不是引用 `KeyMap`，是为了避免 `net` ↔ `remote` 两个包
         * 互相依赖（`KeyMap.groupsFor` 需要读本类）。一致性由单元测试保证：
         * `CameraProfileTest` 会断言这两个值与 `KeyMap` 的常量相等。
         */
        const val EV_KEYSYM_NX1 = "XF86WWW"
        const val EV_KEYSYM_DEFAULT = "XF86Reload"

        /** NX500：唯一被真机验证过的机型 */
        val NX500 = CameraProfile(
            model = "NX500",
            osdFrameW = NEW_FRAME_W,
            osdFrameH = FRAME_H,
            evKeysym = EV_KEYSYM_DEFAULT,
            hasCustomModes = true,
            hasWifiMode = false,
            verified = true,
        )

        /** NX1：尺寸与 NX500 相同，但 EV 键 keysym 不同；从未真机验证 */
        val NX1 = CameraProfile(
            model = "NX1",
            osdFrameW = NEW_FRAME_W,
            osdFrameH = FRAME_H,
            evKeysym = EV_KEYSYM_NX1,
            hasCustomModes = true,
            hasWifiMode = false,
            verified = false,
        )

        /** NX300：old 档 800×480，无自定义模式，多一个 Wi-Fi 模式 */
        val NX300 = CameraProfile(
            model = "NX300",
            osdFrameW = OLD_FRAME_W,
            osdFrameH = FRAME_H,
            evKeysym = EV_KEYSYM_DEFAULT,
            hasCustomModes = false,
            hasWifiMode = true,
            verified = false,
        )

        /** 未知机型回落到 NX500 规格（new 档 720×480），但标记未验证 */
        val UNKNOWN = CameraProfile(
            model = null,
            osdFrameW = NEW_FRAME_W,
            osdFrameH = FRAME_H,
            evKeysym = EV_KEYSYM_DEFAULT,
            hasCustomModes = true,
            hasWifiMode = false,
            verified = false,
        )

        /**
         * 由 `/api/v1/camera/info` 的 `model` 字段推档案。
         *
         * 匹配规则照抄上游守护进程：**按名字精确比对**（见 `nx_model.c`）。
         * 上游只认 NX1 / NX500 / NX300 / NX300M / NX2000 / NX3000，
         * 其余（含 NX30）都会落到 unknown 分支，这里也一样。
         */
        fun of(raw: String?): CameraProfile {
            val m = raw?.trim()?.uppercase() ?: return UNKNOWN
            return when (m) {
                "NX1" -> NX1
                "NX500" -> NX500
                "NX300" -> NX300
                // 同属 old 档（守护进程的 is_old_nx_model），但上游自己标了 TODO / 未构建，
                // 因此沿用 NX300 的尺寸与功能集，并保持 verified = false。
                "NX300M", "NX2000", "NX3000" -> NX300.copy(model = raw.trim(), verified = false)
                else -> UNKNOWN.copy(model = raw.trim())
            }
        }
    }
}
