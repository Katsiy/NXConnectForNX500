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

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * OSD 响应的**接入层严格校验**（纯 JVM，可被单元测试覆盖）。
 *
 * ## 为什么需要它
 *
 * [NxOsd.parse] 是协议解析器，但它**不校验块坐标**，而且用 `Map<块索引, Block>` 存结果 ——
 * 同索引的记录会互相覆盖。这带来两个风险：
 *
 * 1. 非法坐标（越界 / 未对齐）在解析阶段不会被发现，会一路传到渲染层
 * 2. 一条非法记录可能被**后面同索引的合法记录掩盖**，导致校验形同虚设
 *
 * 所以这里在**调用解析器之前**逐条检查原始记录，再解析、最后复核解析结果的索引与长度。
 *
 * ## 多机型：以帧头为准，但守住协议常量
 *
 * 响应帧头自带 `frameW/frameH/blockW/blockH`，所以**帧尺寸不能写死**：
 *
 * | 机型档 | 帧尺寸 | 块数 |
 * |---|---|---|
 * | NX1 / NX500（new） | 720×480 | 108 |
 * | NX300 等（old） | 800×480 | 120 |
 *
 * 但**块尺寸 80×40 是协议常量**：守护进程 `osd.c` 里是 `#define`，没有机型分支，
 * 所以这里仍然强制要求 80×40。同理，块总数上限是守护进程里
 * `last_hashs[NUM_BLOCKS]` 的数组长度 **120** —— 超过它服务端会栈溢出，
 * 我们自己也按 120 分配哈希表，因此这是硬上限。
 *
 * 于是校验规则是：
 *
 * - 块必须是 80×40（协议常量）
 * - 帧尺寸必须能被块整除
 * - 块总数必须落在 `1..120`（自然把帧宽限制在 ≤800）
 * - 定长记录的整除关系、末尾结束标记、每条记录的坐标对齐与越界
 *
 * ## 长度公式
 *
 * 每条记录 = `8 + 80*40*4` = **12808** 字节（x/y/hash + RGBA 像素）。
 * 一条合法响应的长度是 `12 + N × 12808`，其中 `12` = 8 字节帧头 + 4 字节结束标记。
 *
 * ```
 * NX500 全量：108 × 12808 + 12 = 1,383,276   ← 与真机实测一致
 * NX500 增量：  4 × 12808 + 12 =    51,244   ← 与真机实测一致
 * NX300 全量：120 × 12808 + 12 = 1,536,972   ← 按协议推算，未实测
 * ```
 *
 * ## 与解析器的职责分工
 *
 * - 本对象：**准入**（能进就保证坐标、对齐、索引、长度全部合法）
 * - [NxOsd.parse]：**解码**（按已验证的结构读出像素）
 *
 * 重复的**合法**块仍按原实现"最后一块生效"，这里不改变该行为。
 *
 * 空 HTTP 响应（相机未出流时返回 200 + 空体）**不应**传到这里 ——
 * 那是"暂不可用"，由调用方重试。
 */
object NxOsdValidator {

    /** 块宽：守护进程编译期常量，不分机型 */
    const val BLOCK_W = CameraProfile.BLOCK_W       // 80

    /** 块高：同上 */
    const val BLOCK_H = CameraProfile.BLOCK_H       // 40

    /** 单块像素字节数 = 80*40*4 */
    const val BLOCK_BYTES = BLOCK_W * BLOCK_H * 4   // 12800

    /** 每条记录的字节数：x(2) + y(2) + hash(4) + 像素(12800) */
    const val RECORD_BYTES = 8 + BLOCK_BYTES        // 12808

    /** 最小合法长度：8 字节帧头 + 4 字节结束标记（零个块，即"无变化"） */
    const val MIN_BYTES = 12

    /** 块总数硬上限：守护进程 `last_hashs[NUM_BLOCKS]` 的数组长度 */
    const val MAX_BLOCKS = NxOsd.MAX_BLOCKS         // 120

    /** 结束标记 */
    private const val END = 0xFF.toByte()

    /**
     * 校验并解析。
     *
     * @throws NxOsd.OsdParseException 任何一处不符合契约
     */
    @Throws(NxOsd.OsdParseException::class)
    fun parseStrict(raw: ByteArray): NxOsd.Frame {
        if (raw.size < MIN_BYTES) bad("OSD 响应过短：${raw.size} 字节（至少 $MIN_BYTES）")

        // ---- 帧头：尺寸从响应里读，但必须落在协议允许的范围内 ----
        val header = ByteBuffer.wrap(raw, 0, 8).order(ByteOrder.BIG_ENDIAN)
        val w = header.short.toInt() and 0xFFFF
        val h = header.short.toInt() and 0xFFFF
        val bw = header.short.toInt() and 0xFFFF
        val bh = header.short.toInt() and 0xFFFF

        if (bw != BLOCK_W || bh != BLOCK_H) {
            bad("OSD 块尺寸不符合协议常量：${bw}×$bh，应为 $BLOCK_W×$BLOCK_H")
        }
        if (w <= 0 || h <= 0) bad("OSD 帧尺寸非法：${w}×$h")
        if (w % BLOCK_W != 0 || h % BLOCK_H != 0) {
            bad("OSD 帧尺寸 ${w}×$h 不能被块 ${BLOCK_W}×$BLOCK_H 整除")
        }
        val columns = w / BLOCK_W
        val rows = h / BLOCK_H
        val blockCount = columns * rows
        if (blockCount !in 1..MAX_BLOCKS) {
            bad("OSD 块总数超出上限：${w}×$h / $BLOCK_W×$BLOCK_H = $blockCount，上限 $MAX_BLOCKS")
        }

        // ---- 长度必须能被"定长记录 + 头尾"整除 ----
        if ((raw.size - MIN_BYTES) % RECORD_BYTES != 0) {
            bad("OSD 记录被截断或含多余字节：${raw.size} 字节")
        }

        // ---- 末尾必须是 FFFF FFFF ----
        val end = raw.size - 4
        for (i in end until raw.size) {
            if (raw[i] != END) bad("OSD 缺少末尾结束标记 FFFF FFFF")
        }

        // ---- 逐条检查坐标：必须在解析前做，否则会被同索引的后续记录掩盖 ----
        var offset = 8
        while (offset < end) {
            val x = ((raw[offset].toInt() and 0xFF) shl 8) or (raw[offset + 1].toInt() and 0xFF)
            val y = ((raw[offset + 2].toInt() and 0xFF) shl 8) or (raw[offset + 3].toInt() and 0xFF)
            validatePosition(x, y, w, h)
            offset += RECORD_BYTES
        }

        // ---- 交给解析器，再复核它的输出 ----
        return NxOsd.parse(raw).also { validateFrame(it) }
    }

    /** 坐标必须在帧内、且对齐到块的整数倍 */
    fun validatePosition(x: Int, y: Int, frameW: Int, frameH: Int) {
        if (x !in 0..(frameW - BLOCK_W) || y !in 0..(frameH - BLOCK_H) ||
            x % BLOCK_W != 0 || y % BLOCK_H != 0
        ) {
            bad("OSD 块坐标越界或未对齐：($x,$y)，帧 ${frameW}×$frameH")
        }
    }

    /** 复核解析结果：尺寸合法性、每个块的索引与像素长度 */
    fun validateFrame(frame: NxOsd.Frame) {
        if (frame.blockW != BLOCK_W || frame.blockH != BLOCK_H) {
            bad("OSD 块尺寸必须为 $BLOCK_W×$BLOCK_H，实际 ${frame.blockW}×${frame.blockH}")
        }
        if (frame.frameW <= 0 || frame.frameH <= 0 ||
            frame.frameW % BLOCK_W != 0 || frame.frameH % BLOCK_H != 0
        ) {
            bad("OSD 帧尺寸非法：${frame.frameW}×${frame.frameH}")
        }
        if (frame.blockCount !in 1..MAX_BLOCKS) {
            bad("OSD 块总数超出上限：${frame.blockCount}，上限 $MAX_BLOCKS")
        }
        val columns = frame.columns
        for ((index, block) in frame.blocks) {
            validatePosition(block.x, block.y, frame.frameW, frame.frameH)
            val expectedIndex = (block.y / BLOCK_H) * columns + block.x / BLOCK_W
            if (index !in 0 until frame.blockCount || index != expectedIndex) {
                bad("OSD 块索引不匹配：$index，期望 $expectedIndex")
            }
            if (block.rgba.size != BLOCK_BYTES) {
                bad("OSD 块像素长度错误：${block.rgba.size}，期望 $BLOCK_BYTES")
            }
        }
    }

    /** 给定帧尺寸时的块总数 */
    fun blockCountFor(frameW: Int, frameH: Int): Int = (frameW / BLOCK_W) * (frameH / BLOCK_H)

    /** 给定块数时，一条合法响应应有的字节数（供测试与调用方自检） */
    fun expectedSizeForBlocks(blockCount: Int): Int = MIN_BYTES + blockCount * RECORD_BYTES

    private fun bad(message: String): Nothing = throw NxOsd.OsdParseException(message)
}
