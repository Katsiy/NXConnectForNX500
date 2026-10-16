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
import android.graphics.Matrix
import android.graphics.PointF
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import androidx.appcompat.widget.AppCompatImageView
import kotlin.math.abs

/**
 * 支持双指缩放 / 双击放大 / 拖动查看的 ImageView。
 *
 * 自己写而不用第三方库（如 PhotoView）：本工程刻意把依赖压到最少，
 * 少一个依赖就少一份构建与镜像风险。
 */
class ZoomImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0,
) : AppCompatImageView(context, attrs, defStyle) {

    private val matrix = Matrix()
    private var scale = 1f
    private var baseScale = 1f
    private val maxScale = 6f

    private val lastTouch = PointF()
    private var dragging = false

    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val factor = detector.scaleFactor
                val target = (scale * factor).coerceIn(baseScale, maxScale)
                val realFactor = target / scale
                scale = target
                matrix.postScale(realFactor, realFactor, detector.focusX, detector.focusY)
                clamp()
                imageMatrix = matrix
                return true
            }
        },
    )

    private val gestureDetector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (scale > baseScale * 1.05f) {
                    reset()
                } else {
                    val target = (baseScale * 2.5f).coerceAtMost(maxScale)
                    scale = target
                    matrix.reset()
                    matrix.postScale(target, target, e.x, e.y)
                    clamp()
                    imageMatrix = matrix
                }
                return true
            }

            override fun onDown(e: MotionEvent): Boolean = true
        },
    )

    init {
        scaleType = ScaleType.MATRIX
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastTouch.set(event.x, event.y)
                dragging = true
                parent?.requestDisallowInterceptTouchEvent(scale > baseScale * 1.05f)
            }

            MotionEvent.ACTION_MOVE -> {
                if (dragging && !scaleDetector.isInProgress && scale > baseScale * 1.05f) {
                    val dx = event.x - lastTouch.x
                    val dy = event.y - lastTouch.y
                    matrix.postTranslate(dx, dy)
                    clamp()
                    imageMatrix = matrix
                }
                lastTouch.set(event.x, event.y)
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                dragging = false
                parent?.requestDisallowInterceptTouchEvent(false)
            }
        }
        return true
    }

    override fun setImageDrawable(drawable: Drawable?) {
        super.setImageDrawable(drawable)
        post { fitToView() }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        fitToView()
    }

    /** 图片按 FIT_CENTER 铺进控件，作为 baseScale */
    private fun fitToView() {
        val d = drawable ?: return
        if (width == 0 || height == 0 || d.intrinsicWidth <= 0 || d.intrinsicHeight <= 0) return
        val sx = width.toFloat() / d.intrinsicWidth
        val sy = height.toFloat() / d.intrinsicHeight
        baseScale = minOf(sx, sy)
        scale = baseScale
        reset()
    }

    private fun reset() {
        val d = drawable ?: return
        matrix.reset()
        matrix.postScale(baseScale, baseScale)
        val dx = (width - d.intrinsicWidth * baseScale) / 2f
        val dy = (height - d.intrinsicHeight * baseScale) / 2f
        matrix.postTranslate(dx, dy)
        scale = baseScale
        imageMatrix = matrix
    }

    /** 限制平移范围，避免把图片拖出屏幕 */
    private fun clamp() {
        val d = drawable ?: return
        val values = FloatArray(9)
        matrix.getValues(values)
        val transX = values[Matrix.MTRANS_X]
        val transY = values[Matrix.MTRANS_Y]
        val currentScale = values[Matrix.MSCALE_X]

        val contentW = d.intrinsicWidth * currentScale
        val contentH = d.intrinsicHeight * currentScale

        val maxDx = if (contentW > width) (contentW - width) / 2f else 0f
        val maxDy = if (contentH > height) (contentH - height) / 2f else 0f

        val centerX = (width - contentW) / 2f
        val centerY = (height - contentH) / 2f

        var fixX = 0f
        var fixY = 0f
        if (contentW > width) {
            if (transX > centerX + maxDx) fixX = centerX + maxDx - transX
            if (transX < centerX - maxDx) fixX = centerX - maxDx - transX
            if (abs(transX - centerX) < 1f) fixX = 0f
        } else {
            fixX = centerX - transX
        }
        if (contentH > height) {
            if (transY > centerY + maxDy) fixY = centerY + maxDy - transY
            if (transY < centerY - maxDy) fixY = centerY - maxDy - transY
        } else {
            fixY = centerY - transY
        }
        if (fixX != 0f || fixY != 0f) matrix.postTranslate(fixX, fixY)
    }
}
