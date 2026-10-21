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

import com.nxwa.nxconnect.net.CapdtmLabels
import com.nxwa.nxconnect.net.CapdtmSnapshot
import com.nxwa.nxconnect.net.NxCapdtm
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * capdtm 输出解析与枚举标签的单元测试。
 *
 * 样例字符串**逐字取自真机**（NX500 固件 1.12），见 `docs/capdtm-reference.md`。
 * 这些用例的价值在于：解析一旦写错，界面就会显示成空或者把错误信息当值。
 */
class CapdtmParsingTest {

    // ------------------------------------------------------------------
    // getusr 输出
    // ------------------------------------------------------------------

    @Test
    fun `getusr 正常输出取值名`() {
        assertEquals("ISO_100", NxCapdtm.parseUserData("UserData is ISO_100 (0x50001)"))
        assertEquals(
            "METERING_CENTERWEIGHTED",
            NxCapdtm.parseUserData("UserData is METERING_CENTERWEIGHTED (0xd0001)"),
        )
        assertEquals("WB_AUTO", NxCapdtm.parseUserData("UserData is WB_AUTO (0x60000)"))
        assertEquals(
            "IMAGEQUALITY_RAW_SUPERFINE",
            NxCapdtm.parseUserData("UserData is IMAGEQUALITY_RAW_SUPERFINE (0x40004)"),
        )
    }

    @Test
    fun `getusr 输出前面带 NUL 也能解析`() {
        // 真机 st 的输出前面确实有 \x00
        assertEquals("ISO_100", NxCapdtm.parseUserData("\u0000UserData is ISO_100 (0x50001)"))
    }

    @Test
    fun `getusr 同时取到值 ID`() {
        val full = NxCapdtm.parseUserDataFull("UserData is ISO_100 (0x50001)")
        assertEquals("ISO_100", full?.first)
        assertEquals(0x50001L, full?.second)

        // 没有 hex 时 parseUserDataFull 返回 null（它要的就是「名 + ID」这一对）；
        // 只要名字的话用 parseUserData。
        assertNull("没有 hex 时返回 null", NxCapdtm.parseUserDataFull("UserData is WB_DAYLIGHT"))
        assertEquals("WB_DAYLIGHT", NxCapdtm.parseUserData("UserData is WB_DAYLIGHT"))
    }

    @Test
    fun `getusr 的错误输出不应被当成值`() {
        // 参数名带了 USERDATA_ 前缀时相机就回这个 —— 我们曾因此误判很久
        assertNull(NxCapdtm.parseUserData(" Invalid argument[2]=USERDATA_ISO"))
        assertNull(NxCapdtm.parseUserData("Unkonwn Operation"))
        assertNull(NxCapdtm.parseUserData(""))
    }

    // ------------------------------------------------------------------
    // getvar 输出
    // ------------------------------------------------------------------

    @Test
    fun `getvar 正常输出取低 32 位`() {
        assertEquals(0xFFB0L, NxCapdtm.parseVariable("Variable Data is 0x00000000_0000ffb0"))
        assertEquals(0x60L, NxCapdtm.parseVariable("Variable Data is 0x00000000_00000060"))
        assertEquals(0x0AL, NxCapdtm.parseVariable("Variable Data is 0x00000000_0000000a"))
    }

    @Test
    fun `getvar 的负值按无符号返回`() {
        // TV = -80 在相机里存成 0x0000ffb0，这里按无符号给出，解码方自行转有符号
        val tv = NxCapdtm.parseVariable("Variable Data is 0x00000000_0000ffb0")
        assertEquals(0xFFB0L, tv)
        assertEquals(-80L, tv!!.toInt().toShort().toLong())
    }

    @Test
    fun `getvar 的占位输出不应被当成 0`() {
        // varlist 里这些变量的位置是 ----------
        assertNull(NxCapdtm.parseVariable("VARIABLE_PWCOLOR |----------|----------|"))
        assertNull(NxCapdtm.parseVariable(""))
    }

    // ------------------------------------------------------------------
    // 标签映射
    // ------------------------------------------------------------------

    @Test
    fun `ISO 标签`() {
        assertEquals("100", CapdtmLabels.iso("ISO_100"))
        assertEquals("25600", CapdtmLabels.iso("ISO_25600"))
        assertEquals("自动", CapdtmLabels.iso("ISO_AUTO"))
    }

    @Test
    fun `白平衡与测光标签`() {
        assertEquals("自动", CapdtmLabels.whiteBalance("WB_AUTO"))
        assertEquals("日光", CapdtmLabels.whiteBalance("WB_DAYLIGHT"))
        assertEquals("多重测光", CapdtmLabels.metering("METERING_MULTI"))
        assertEquals("中央重点", CapdtmLabels.metering("METERING_CENTERWEIGHTED"))
        assertEquals("点测光", CapdtmLabels.metering("METERING_SPOT"))
    }

    @Test
    fun `驱动与画质标签`() {
        assertEquals("单张", CapdtmLabels.drive("DRIVE_SINGLE"))
        assertEquals("RAW + 超精细", CapdtmLabels.imageQuality("IMAGEQUALITY_RAW_SUPERFINE"))
        assertEquals("仅 RAW", CapdtmLabels.imageQuality("IMAGEQUALITY_RAW"))
        assertEquals("单次", CapdtmLabels.afMode("AFMODE_SINGLE"))
    }

    @Test
    fun `未知枚举值要能显示而不是变空`() {
        // 固件升级可能新增枚举；界面不能因此空掉
        val unknown = CapdtmLabels.metering("METERING_SOMETHINGNEW")
        assertTrue("未知值应有回退文案：$unknown", unknown.isNotBlank())
        assertEquals("SOMETHINGNEW", unknown)

        assertTrue(CapdtmLabels.whiteBalance("WB_WEIRD").isNotBlank())
        assertTrue(CapdtmLabels.iso("ISO_999999").isNotBlank())
    }

    // ------------------------------------------------------------------
    // 快照
    // ------------------------------------------------------------------

    @Test
    fun `空快照能被识别`() {
        assertTrue(CapdtmSnapshot().isEmpty)
    }

    @Test
    fun `有任一字段就不算空`() {
        assertFalse(CapdtmSnapshot(iso = "ISO_100").isEmpty)
        assertFalse(CapdtmSnapshot(metering = "METERING_SPOT").isEmpty)
    }

    @Test
    fun `摘要行按 ISO 白平衡 测光 驱动 拼接`() {
        val s = CapdtmSnapshot(
            iso = "ISO_400",
            whiteBalance = "WB_DAYLIGHT",
            metering = "METERING_SPOT",
            driveMode = "DRIVE_SINGLE",
        )
        assertEquals("ISO 400 · 日光 · 点测光 · 单张", s.summaryLine())
    }

    @Test
    fun `摘要行在缺字段时不留下多余分隔符`() {
        val s = CapdtmSnapshot(iso = "ISO_AUTO", metering = "METERING_MULTI")
        assertEquals("ISO 自动 · 多重测光", s.summaryLine())
        assertEquals("", CapdtmSnapshot().summaryLine())
    }
}
