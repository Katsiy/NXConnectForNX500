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

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.viewpager2.widget.ViewPager2
import com.nxwa.nxconnect.NxApp
import com.nxwa.nxconnect.R
import com.nxwa.nxconnect.data.MediaEntry
import com.nxwa.nxconnect.databinding.ActivityViewerBinding
import com.nxwa.nxconnect.transfer.DownloadService

/**
 * 原图查看：左右滑动翻页，底部一键下载（原图，不是屏幕上这张降采样图）。
 */
class ViewerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_FOLDER = "folder"
        const val EXTRA_NAMES = "names"
        const val EXTRA_INDEX = "index"
    }

    private lateinit var b: ActivityViewerBinding
    private var folder: String = ""
    private var names: List<String> = emptyList()
    private var adapter: ImagePagerAdapter? = null

    private val storagePermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> if (!granted) toast("没有存储权限，无法保存到相册") }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityViewerBinding.inflate(layoutInflater)
        setContentView(b.root)

        // 全屏看图：与其它页面统一用「根布局吃系统 inset」的做法。
        // 原来用 padTop(b.toolbar)，该写法在遥控页被用户实测确认会被状态栏挡住。
        EdgeToEdge.setup(this)
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(b.root) { v, insets ->
            val top = insets.getInsets(
                androidx.core.view.WindowInsetsCompat.Type.statusBars() or
                    androidx.core.view.WindowInsetsCompat.Type.displayCutout(),
            ).top
            val bottom = insets.getInsets(
                androidx.core.view.WindowInsetsCompat.Type.navigationBars(),
            ).bottom
            v.setPadding(v.paddingLeft, top, v.paddingRight, bottom)
            androidx.core.view.WindowInsetsCompat.CONSUMED
        }
        androidx.core.view.ViewCompat.requestApplyInsets(b.root)
        EdgeToEdge.padBottom(b.btnDownload, extraDp = 16)

        folder = intent.getStringExtra(EXTRA_FOLDER).orEmpty()
        names = intent.getStringArrayExtra(EXTRA_NAMES)?.toList().orEmpty()
        val startIndex = intent.getIntExtra(EXTRA_INDEX, 0)

        if (!NxApp.isConnected || names.isEmpty()) {
            toast("没有可查看的图片")
            finish()
            return
        }

        adapter = ImagePagerAdapter(folder, names)
        b.pager.adapter = adapter
        b.pager.setCurrentItem(startIndex.coerceIn(0, names.size - 1), false)

        b.toolbar.setNavigationOnClickListener { finish() }
        b.btnDownload.setOnClickListener { downloadCurrent() }

        b.pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                updateTitle(position)
            }
        })
        updateTitle(b.pager.currentItem)
    }

    private fun updateTitle(position: Int) {
        b.toolbar.title = getString(R.string.image_of, position + 1, names.size)
        b.toolbar.subtitle = names.getOrNull(position)
    }

    private fun downloadCurrent() {
        val index = b.pager.currentItem
        val name = names.getOrNull(index) ?: return
        val ip = NxApp.cameraIp ?: return
        ensureStoragePermission()
        DownloadService.start(this, ip, listOf(MediaEntry(folder, name)))
        toast("开始下载 $name")
    }

    private fun ensureStoragePermission() {
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
            != PackageManager.PERMISSION_GRANTED
        ) {
            storagePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
    }

    override fun onDestroy() {
        adapter?.dispose()
        adapter = null
        super.onDestroy()
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
