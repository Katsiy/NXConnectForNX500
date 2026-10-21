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

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.os.Looper
import android.util.AttributeSet
import android.view.View
import com.nxwa.nxconnect.net.NxOsd
import com.nxwa.nxconnect.net.NxOsdValidator
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor

/**
 * NX500 的透明 UI 叠加层。作为 LiveViewView 上方的同尺寸兄弟 View 使用。
 * 原始 RGBA / 服务端哈希与显示用 Alpha 完全分离，不改协议数据。
 * 变化块在后台做纯黑键控，在主线程通过 copyPixelsFromBuffer 更新持久 Bitmap。
 *
 * invalidate(rect) 只提交脏区提示；Android 21 起系统可能忽略矩形。
 * 下层 liveview 更新也需要重新合成叠加层，因此不承诺 GPU 只重绘变化块。
 */
class OsdView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    /** prepareFrame 返回后，调用方必须把 frame 的 Map / ByteArray 视为只读。 */
    class PreparedFrame internal constructor(
        internal val frame: NxOsd.Frame,
        internal val keyedBlocks: Map<Int, ByteArray>,
        internal val opaqueCounts: Map<Int, Int>,
    ) {
        val changedBlockCount: Int get() = frame.blocks.size
    }

    companion object {
        // 块尺寸是协议常量（守护进程 osd.c 的 #define，不分机型）
        private const val BLOCK_W = NxOsdValidator.BLOCK_W
        private const val BLOCK_H = NxOsdValidator.BLOCK_H
        private const val BLOCK_BYTES = NxOsdValidator.BLOCK_BYTES

        /** 0 字节 HTTP 体由轮询器处理；这里仍严格复用已有解析器和校验器。 */
        @Throws(NxOsd.OsdParseException::class)
        fun parseResponse(raw: ByteArray): NxOsd.Frame = NxOsdValidator.parseStrict(raw)

        /** 在 Dispatchers.Default 调用；只处理本次变化块，绝不计算或改写协议哈希。 */
        @Throws(NxOsd.OsdParseException::class)
        fun prepareFrame(frame: NxOsd.Frame): PreparedFrame {
            NxOsdValidator.validateFrame(frame)
            val keyed = LinkedHashMap<Int, ByteArray>(frame.blocks.size)
            val counts = LinkedHashMap<Int, Int>(frame.blocks.size)
            for ((index, block) in frame.blocks) {
                val pixels = block.rgba.copyOf()
                var opaque = 0
                var p = 0
                while (p < pixels.size) {
                    // 阈值严格为 0。RGB=(1,0,0) 等近黑 UI 仍完全保留。
                    val black = pixels[p] == 0.toByte() &&
                        pixels[p + 1] == 0.toByte() && pixels[p + 2] == 0.toByte()
                    // 按 RGB 黑键控生成显示 Alpha；原 Alpha 的语义未约定，原值另行保留。
                    // Alpha=0 时 RGB 也为 0，Alpha=255 时保留原 RGB，符合预乘格式。
                    pixels[p + 3] = if (black) 0.toByte() else 0xFF.toByte()
                    if (!black) opaque++
                    p += 4
                }
                keyed[index] = pixels
                counts[index] = opaque
            }
            return PreparedFrame(frame, keyed, counts)
        }
    }

    /**
     * 帧尺寸**以协议帧头为准**，不能写死：
     * NX1/NX500 是 720×480（9×12 = 108 块），NX300 是 800×480（10×12 = 120 块）。
     * 首帧到达时才按真实尺寸分配画布。
     */
    private var frameW = NxOsd.EXPECTED_FRAME_W
    private var frameH = NxOsd.EXPECTED_FRAME_H

    /** 整帧 RGBA 画布；长度 = frameW*frameH*4，随帧头变化时重分配。 */
    private var rgba = ByteArray(0)

    // 这三张表按**协议上限** 120 分配，避免机型切换时重新分配；
    // 只使用前 [blockCount] 项（NX500 用 108 项）。
    private val hashes = IntArray(NxOsd.MAX_BLOCKS)
    private val received = BooleanArray(NxOsd.MAX_BLOCKS)
    private val opaquePixels = IntArray(NxOsd.MAX_BLOCKS)
    private var bitmap: Bitmap? = null
    private var bitmapCanvas: Canvas? = null
    private var blockBitmap: Bitmap? = null
    private val destination = RectF()
    private var imageScale = 0f
    private var viewportCompatible = false
    private val displayPaint = Paint(Paint.FILTER_BITMAP_FLAG) // 默认 SRC_OVER，叠在取景之上。
    private val copyPaint = Paint().apply {
        isFilterBitmap = false
        // 更新缓存时必须 SRC：新块中的透明像素要擦掉旧图标，SRC_OVER 会留下残影。
        xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC)
    }

    init {
        contentDescription = "相机屏幕叠加层"
        isClickable = false
        isFocusable = false
    }

    /** 当前机型一帧的块数：108（720×480）或 120（800×480） */
    private val blockCount: Int get() = (frameW / BLOCK_W) * (frameH / BLOCK_H)

    val hasCompleteFrame: Boolean
        get() {
            checkMainThread()
            // 只检查本机型实际存在的块；120 项数组在多出来的位置保持 false
            return (0 until blockCount).all { received[it] }
        }

    val hasVisibleContent: Boolean
        get() { checkMainThread(); return opaquePixels.any { it > 0 } }

    /** 使用 LiveViewView 给出的同一显示矩形，避免两个 View 独立取整造成错位。 */
    fun setViewport(rect: RectF, compatible: Boolean) {
        checkMainThread()
        require(rect.left.isFinite() && rect.top.isFinite() &&
            rect.right.isFinite() && rect.bottom.isFinite()) { "显示矩形包含非有限值" }
        if (compatible && !rect.isEmpty) {
            // 宽高比按**当前机型的帧尺寸**校验，不再写死 3:2
            // （NX500 是 3:2，NX300 的 800×480 是 5:3）
            require(abs(rect.width() / frameW - rect.height() / frameH) < 0.001f) {
                "OSD 显示矩形必须与帧尺寸 ${frameW}×$frameH 同比例"
            }
        }
        if (destination == rect && viewportCompatible == compatible) return
        destination.set(rect)
        viewportCompatible = compatible
        imageScale = if (compatible && !rect.isEmpty) rect.width() / frameW else 0f
        invalidate() // 几何或可见性改变必须清除旧位置。
    }

    /** 只在新会话清零；空体、零块和可重试网络错误绝不能重置增量基线。 */
    fun reset() {
        checkMainThread()
        if (rgba.isNotEmpty()) rgba.fill(0)
        hashes.fill(0)
        received.fill(false)
        opaquePixels.fill(0)
        bitmap?.eraseColor(Color.TRANSPARENT)
        invalidate()
    }

    fun hashsParam(): String {
        checkMainThread()
        // ★ 恒定发**协议上限** 120 个哈希，而不是本机型的块数。
        //   守护进程的 `last_hashs[NUM_BLOCKS]`（120）按请求体长度循环写入：
        //   发少了，超出部分会拿未初始化的栈值比较 → 那些块每次都被重发（浪费）；
        //   发多了，会写越界（栈溢出）。所以 120 是唯一安全的通用值，也与机型无关。
        return NxOsd.buildHashsParam(hashes, NxOsd.MAX_BLOCKS)
    }

    /** 保留旧调用入口；轮询器应优先使用后台 prepareFrame + 主线程 submitPrepared。 */
    @Throws(NxOsd.OsdParseException::class)
    fun submitFrame(frame: NxOsd.Frame) {
        checkMainThread()
        submitPrepared(prepareFrame(frame))
    }

    @Throws(NxOsd.OsdParseException::class)
    fun submitPrepared(prepared: PreparedFrame) {
        checkMainThread()
        val frame = prepared.frame
        NxOsdValidator.validateFrame(frame) // 任何写入前检查所有原始块。
        check(prepared.keyedBlocks.keys == frame.blocks.keys &&
            prepared.opaqueCounts.keys == frame.blocks.keys) { "键控块与协议帧不一致" }
        for (index in frame.blocks.keys) {
            check(prepared.keyedBlocks.getValue(index).size == BLOCK_BYTES) { "键控块长度错误" }
            check(prepared.opaqueCounts.getValue(index) in 0..BLOCK_W * BLOCK_H)
        }
        if (frame.blocks.isEmpty()) return
        ensureFrame(frame.frameW, frame.frameH)
        ensureBitmaps()
        val tile = checkNotNull(blockBitmap)
        val target = checkNotNull(bitmapCanvas)
        for ((index, block) in frame.blocks) {
            // ARGB_8888 是 Bitmap 的配置名；ByteBuffer 仍携带 R,G,B,A 字节。
            tile.copyPixelsFromBuffer(ByteBuffer.wrap(prepared.keyedBlocks.getValue(index)))
            target.drawBitmap(tile, block.x.toFloat(), block.y.toFloat(), copyPaint)
        }
        frame.applyTo(rgba) // 复用协议层合成，绝不把显示 Alpha 写回原始画布。
        frame.updateHashes(hashes) // 全部合成成功后，才声明持有这些服务端哈希。
        for (index in frame.blocks.keys) {
            received[index] = true
            opaquePixels[index] = prepared.opaqueCounts.getValue(index)
        }
        for (block in frame.blocks.values) invalidateBlock(block.x, block.y)
    }

    /**
     * 帧尺寸变化时重建画布。
     *
     * 正常情况下整个会话只有一种尺寸；这里仍处理变化，是为了"换相机/换机型"时不崩：
     * 块索引在有意义的尺寸变化后不再对应，所以哈希与已完成标记一起清空。
     */
    private fun ensureFrame(w: Int, h: Int) {
        if (w == frameW && h == frameH && rgba.isNotEmpty()) return
        frameW = w
        frameH = h
        rgba = NxOsd.newCanvas(w, h)
        hashes.fill(0)
        received.fill(false)
        opaquePixels.fill(0)
        // 整帧位图尺寸也变了，必须重建；旧的可能仍被 display list 引用，交给 GC。
        bitmapCanvas = null
        bitmap = null
        imageScale = if (viewportCompatible && !destination.isEmpty) destination.width() / w else 0f
    }

    private fun ensureBitmaps() {
        if (bitmap == null) {
            bitmap = alphaBitmap(frameW, frameH).also { it.eraseColor(Color.TRANSPARENT) }
            bitmapCanvas = Canvas(checkNotNull(bitmap))
        }
        if (blockBitmap == null) {
            blockBitmap = alphaBitmap(BLOCK_W, BLOCK_H).also {
                check(it.rowBytes == BLOCK_W * 4 && it.byteCount == BLOCK_BYTES) {
                    "Bitmap 行跨度不符合 RGBA 块布局：${it.rowBytes}"
                }
            }
        }
    }

    private fun alphaBitmap(w: Int, h: Int): Bitmap =
        Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply {
            density = Bitmap.DENSITY_NONE
            setHasAlpha(true)
        }

    @Suppress("DEPRECATION")
    private fun invalidateBlock(x: Int, y: Int) {
        if (!viewportCompatible || destination.isEmpty) return
        // 外扩一个源像素，覆盖双线性过滤的影响；向外取整，避免缩放漏线。
        val left = floor((destination.left + maxOf(0, x - 1) * imageScale).toDouble()).toInt()
        val top = floor((destination.top + maxOf(0, y - 1) * imageScale).toDouble()).toInt()
        val right = ceil((destination.left + minOf(frameW, x + BLOCK_W + 1) * imageScale).toDouble()).toInt()
        val bottom = ceil((destination.top + minOf(frameH, y + BLOCK_H + 1) * imageScale).toDouble()).toInt()
        val l = maxOf(0, left)
        val t = maxOf(0, top)
        val r = minOf(width, right)
        val b = minOf(height, bottom)
        if (l < r && t < b) invalidate(l, t, r, b)
    }

    override fun isOpaque(): Boolean = false

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        // 不能 drawColor(BLACK) 或清空父 Canvas，否则会遮住下层取景。
        val image = bitmap ?: return
        if (viewportCompatible && !destination.isEmpty) {
            canvas.drawBitmap(image, null, destination, displayPaint)
        }
    }

    override fun onDetachedFromWindow() {
        bitmapCanvas = null
        bitmap = null // 可能仍被硬件 display list 引用，交给 GC。
        blockBitmap?.recycle() // 临时块只用于同步的软件 Canvas。
        blockBitmap = null
        if (rgba.isNotEmpty()) rgba.fill(0)
        hashes.fill(0)
        received.fill(false)
        opaquePixels.fill(0)
        super.onDetachedFromWindow()
    }

    private fun checkMainThread() {
        check(Looper.myLooper() == Looper.getMainLooper()) { "OsdView 状态只能在主线程访问" }
    }
}
