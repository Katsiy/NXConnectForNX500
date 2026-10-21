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

import com.nxwa.nxconnect.net.CameraProfile
import com.nxwa.nxconnect.net.NxOsd
import com.nxwa.nxconnect.net.NxOsdValidator
import com.nxwa.nxconnect.remote.KeyMap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 机型档案的单元测试。
 *
 * 这些断言的目的不是"复述实现"，而是**锁住从上游源码考证出来的机型差异**，
 * 防止以后有人把它"统一"掉。依据见 `docs/model-compatibility.md`：
 *
 * - 守护进程按 `is_old_nx_model()` 分两档：NX1/NX500 是 720 宽，其余 800 宽
 * - `keyinput.js` 里唯一按机型分支的键是 EV：NX1 用 `XF86WWW`，其余 `XF86Reload`
 * - `MODE_CUSTOM1/2` 是 nx1-nx500-only；`MODE_WIFI`（F3）是 nx300-only
 */
class CameraProfileTest {

    // ------------------------------------------------------------------
    // 三款已知机型
    // ------------------------------------------------------------------

    @Test
    fun `NX500 是 720x480 且已验证`() {
        val p = CameraProfile.of("NX500")
        assertEquals(720, p.osdFrameW)
        assertEquals(480, p.osdFrameH)
        assertTrue("NX500 是唯一真机验证过的机型", p.verified)
        assertTrue("NX500 有自定义模式", p.hasCustomModes)
        assertFalse("NX500 没有独立 Wi-Fi 模式键", p.hasWifiMode)
        assertEquals(108, p.blockCount)
    }

    @Test
    fun `NX1 尺寸同 NX500 但 EV 键 keysym 不同`() {
        val p = CameraProfile.of("NX1")
        assertEquals("NX1 与 NX500 同属 new 档，尺寸相同", 720, p.osdFrameW)
        assertEquals(480, p.osdFrameH)
        assertEquals(
            "上游 keyinput.js：NX1 的 EV 是 XF86WWW",
            "XF86WWW",
            p.evKeysym,
        )
        assertEquals(108, p.blockCount)
        assertFalse("NX1 从未被真机验证", p.verified)
    }

    @Test
    fun `NX300 是 800x480 且无自定义模式`() {
        val p = CameraProfile.of("NX300")
        assertEquals("old 档帧宽 800", 800, p.osdFrameW)
        assertEquals(480, p.osdFrameH)
        assertEquals("NX300 EV 键与 NX500 相同", "XF86Reload", p.evKeysym)
        assertFalse("NX300 没有自定义模式", p.hasCustomModes)
        assertTrue("NX300 有独立 Wi-Fi 模式键", p.hasWifiMode)
        assertEquals("10 列 × 12 行", 120, p.blockCount)
        assertFalse("NX300 未经本项目真机验证", p.verified)
    }

    // ------------------------------------------------------------------
    // 未知与边界
    // ------------------------------------------------------------------

    @Test
    fun `未知机型回落到 720x480 且标记未验证`() {
        for (raw in listOf(null, "", "   ", "NX30", "NX400", "SOMETHING")) {
            val p = CameraProfile.of(raw)
            assertEquals("未知机型按 new 档处理：$raw", 720, p.osdFrameW)
            assertEquals(480, p.osdFrameH)
            assertFalse("未知机型不可标记为已验证：$raw", p.verified)
        }
    }

    @Test
    fun `机型名大小写与空白被容错`() {
        assertEquals(720, CameraProfile.of(" nx500 ").osdFrameW)
        assertEquals(800, CameraProfile.of("Nx300").osdFrameW)
        assertEquals("XF86WWW", CameraProfile.of("nx1").evKeysym)
    }

    @Test
    fun `NX300M 与 NX2000 沿用 old 档尺寸但不标记验证`() {
        for (m in listOf("NX300M", "NX2000")) {
            val p = CameraProfile.of(m)
            assertEquals("$m 属 old 档", 800, p.osdFrameW)
            assertFalse("$m 未验证", p.verified)
            assertEquals("机型名要保留原值", m, p.model)
        }
    }

    // ------------------------------------------------------------------
    // 与协议层的自洽
    // ------------------------------------------------------------------

    @Test
    fun `档案的块数与校验器算出来的一致`() {
        for (p in listOf(CameraProfile.NX500, CameraProfile.NX1, CameraProfile.NX300)) {
            assertEquals(
                "块数必须与校验器一致：${p.model}",
                NxOsdValidator.blockCountFor(p.osdFrameW, p.osdFrameH),
                p.blockCount,
            )
        }
    }

    @Test
    fun `块尺寸与协议常量一致`() {
        assertEquals(NxOsdValidator.BLOCK_W, CameraProfile.BLOCK_W)
        assertEquals(NxOsdValidator.BLOCK_H, CameraProfile.BLOCK_H)
        assertEquals(12800, CameraProfile.BLOCK_W * CameraProfile.BLOCK_H * 4)
    }

    /**
     * EV keysym 在 `CameraProfile` 里是字面量（避免 net ↔ remote 互相依赖），
     * 这里断言它与 `KeyMap` 的常量一致，防止两边漂移。
     */
    @Test
    fun `EV keysym 字面量与 KeyMap 常量一致`() {
        assertEquals(KeyMap.EV_NX1, CameraProfile.EV_KEYSYM_NX1)
        assertEquals(KeyMap.EV_NX500, CameraProfile.EV_KEYSYM_DEFAULT)
        assertNotEquals("NX1 与其它机型的 EV 键必须不同", KeyMap.EV_NX1, KeyMap.EV_NX500)
    }

    // ------------------------------------------------------------------
    // 按键面板按机型变化
    // ------------------------------------------------------------------

    @Test
    fun `NX1 的曝光补偿键用 XF86WWW`() {
        val ev = KeyMap.groupsFor(CameraProfile.NX1).exposure.first { it.label == "曝光补偿" }
        assertEquals("XF86WWW", ev.keysym)
        assertTrue("曝光补偿必须是按住生效", ev.hold)
    }

    @Test
    fun `NX500 与 NX300 的曝光补偿键用 XF86Reload`() {
        for (p in listOf(CameraProfile.NX500, CameraProfile.NX300)) {
            val ev = KeyMap.groupsFor(p).exposure.first { it.label == "曝光补偿" }
            assertEquals("XF86Reload", ev.keysym)
        }
    }

    @Test
    fun `NX300 没有自定义模式但有 Wi-Fi 模式`() {
        val labels = KeyMap.groupsFor(CameraProfile.NX300).mode.map { it.label }
        assertFalse("NX300 不应出现自定义1：$labels", labels.contains("自定义1"))
        assertFalse("NX300 不应出现自定义2：$labels", labels.contains("自定义2"))
        assertTrue("NX300 应有 Wi-Fi 模式：$labels", labels.contains("Wi-Fi"))
        val wifi = KeyMap.groupsFor(CameraProfile.NX300).mode.first { it.label == "Wi-Fi" }
        assertEquals("上游 keyinput.js：MODE_WIFI → F3", "F3", wifi.keysym)
    }

    @Test
    fun `NX500 与 NX1 有自定义模式且没有 Wi-Fi 模式`() {
        for (p in listOf(CameraProfile.NX500, CameraProfile.NX1)) {
            val labels = KeyMap.groupsFor(p).mode.map { it.label }
            assertTrue("${p.model} 应有自定义1", labels.contains("自定义1"))
            assertTrue("${p.model} 应有自定义2", labels.contains("自定义2"))
            assertFalse("${p.model} 不应有 Wi-Fi 模式", labels.contains("Wi-Fi"))
        }
    }

    @Test
    fun `三组按键在三种机型上都不为空`() {
        for (p in listOf(CameraProfile.NX500, CameraProfile.NX1, CameraProfile.NX300, CameraProfile.UNKNOWN)) {
            val g = KeyMap.groupsFor(p)
            assertTrue("${p.model} 曝光组为空", g.exposure.isNotEmpty())
            assertTrue("${p.model} 模式组为空", g.mode.isNotEmpty())
            assertTrue("${p.model} 导航组为空", g.nav.isNotEmpty())
            // 所有 keysym 都不应为空，否则注入会失败
            (g.exposure + g.mode + g.nav).forEach {
                assertTrue("${p.model} 的键 ${it.label} 没有 keysym", it.keysym.isNotBlank())
            }
        }
    }

    @Test
    fun `NX500 的按键组与旧的静态分组等价`() {
        val g = KeyMap.groupsFor(CameraProfile.NX500)
        assertEquals(KeyMap.GROUP_EXPOSURE, g.exposure)
        assertEquals(KeyMap.GROUP_MODE, g.mode)
        assertEquals(KeyMap.GROUP_NAV, g.nav)
    }

    // ------------------------------------------------------------------
    // OSD 请求参数与机型无关
    // ------------------------------------------------------------------

    /**
     * `hashs` 恒为 120 组（960 字符），不随机型变化。
     *
     * 理由：守护进程用 `int last_hashs[120]` 按请求体长度写入 —— 少发会让多出的块
     * 拿未初始化的栈值比较（每次重发），多发会写越界。120 是唯一安全值。
     */
    @Test
    fun `hashs 参数恒为 120 组且与机型无关`() {
        val zeros = NxOsd.buildHashsParam(IntArray(NxOsd.MAX_BLOCKS), NxOsd.MAX_BLOCKS)
        assertEquals(960, zeros.length)
        assertEquals(120, NxOsd.MAX_BLOCKS)
        // 108 块的机型也要发 120 组
        assertEquals(960, NxOsd.buildHashsParam(IntArray(108), NxOsd.MAX_BLOCKS).length)
        // 超过上限会被截断，绝不发出 120 组以上（否则服务端栈溢出）
        assertEquals(960, NxOsd.buildHashsParam(IntArray(500), 500).length)
    }
}
