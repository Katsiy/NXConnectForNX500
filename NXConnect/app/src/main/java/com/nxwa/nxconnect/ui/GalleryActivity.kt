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
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import com.nxwa.nxconnect.NxApp
import com.nxwa.nxconnect.R
import com.nxwa.nxconnect.data.MediaEntry
import com.nxwa.nxconnect.databinding.ActivityGalleryBinding
import com.nxwa.nxconnect.transfer.DownloadService
import com.nxwa.nxconnect.transfer.MediaStoreWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/**
 * 相册：目录 → 照片网格 → 多选下载。
 *
 * 目录列表走相机端 8080 的实时 readdir CGI，所以刚拍的照片立刻可见；
 * 缩略图同样由相机端生成（15–30KB），避免手机上直接拉 4–8MB 原图。
 */
class GalleryActivity : AppCompatActivity() {

    private lateinit var b: ActivityGalleryBinding
    private lateinit var folderAdapter: FolderAdapter
    private lateinit var mediaAdapter: MediaAdapter

    private var currentFolder: String? = null
    private var foldersLoaded = false

    private val storagePermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (!granted) toast("没有存储权限，无法保存到相册")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityGalleryBinding.inflate(layoutInflater)
        setContentView(b.root)

        // ---- 状态栏适配：与遥控页保持同一套做法 ----
        //
        // 这里原来用的是 `padTop(b.toolbar)`，而遥控页用同样的写法被用户实测确认坏掉
        // （工具栏被状态栏挡住）。改成对**根布局**直接施加系统 inset：
        // 只加一处内边距，不再依赖工具栏如何解释自己的 padding。
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

        // 底部操作条与列表的左右留白仍按原样处理（这两处用户未反馈问题）
        EdgeToEdge.padBottom(b.bottomBar, extraDp = 6)
        EdgeToEdge.padHorizontal(b.list, extraDp = 6)

        if (!NxApp.isConnected) {
            toast("尚未连接相机")
            finish()
            return
        }

        folderAdapter = FolderAdapter { row -> openFolder(row.name) }
        mediaAdapter = MediaAdapter(
            onClick = { entry -> if (mediaAdapter.selectionMode) refreshSelectionUi() else openViewer(entry) },
            onLongClick = { entry ->
                mediaAdapter.enterSelection(entry)
                refreshSelectionUi()
            },
        )

        b.list.layoutManager = GridLayoutManager(this, 1)
        b.list.adapter = folderAdapter

        b.toolbar.setNavigationOnClickListener { onBack() }
        b.swipe.setOnRefreshListener { reload() }
        b.selectAllText.setOnClickListener {
            mediaAdapter.selectAll()
            refreshSelectionUi()
        }
        b.btnClear.setOnClickListener {
            mediaAdapter.clearSelection()
            refreshSelectionUi()
        }
        b.btnDownload.setOnClickListener {
            val selected = mediaAdapter.selectedEntries()
            if (selected.isEmpty()) {
                toast("请先选择要下载的照片")
            } else {
                confirmDownload(selected)
            }
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (mediaAdapter.selectionMode) {
                    mediaAdapter.clearSelection()
                    refreshSelectionUi()
                } else {
                    onBack()
                }
            }
        })

        loadFolders()
    }

    private fun onBack() {
        if (currentFolder != null) {
            loadFolders()
        } else {
            finish()
        }
    }

    private fun setLoading(on: Boolean, text: String? = null) {
        b.loading.visibility = if (on) View.VISIBLE else View.GONE
        b.swipe.isRefreshing = false
        b.empty.visibility = View.GONE
        text?.let { b.crumbText.text = it }
    }

    /**
     * 显示空状态。
     *
     * 布局改版后 `empty` 是一个「插图 + 文案」的容器（LinearLayout），
     * 文案写进它的子 `emptyLabel`，不能对容器 setText（会崩）。
     */
    private fun showEmpty(text: String) {
        b.emptyLabel.text = text
        b.empty.visibility = View.VISIBLE
    }

    private fun reload() {
        if (currentFolder != null) loadMedia(currentFolder!!) else loadFolders()
    }

    // ------------------------------------------------------------------ 目录

    private fun loadFolders() {
        currentFolder = null
        b.toolbar.title = getString(R.string.gallery_title)
        b.crumbRow.visibility = View.GONE
        b.bottomBar.visibility = View.GONE
        b.list.layoutManager = GridLayoutManager(this, 1)
        b.list.adapter = folderAdapter
        setLoading(true, "正在读取目录…")

        lifecycleScope.launch {
            val cam = NxApp.camera ?: return@launch
            try {
                val names = withContext(Dispatchers.IO) { cam.listFolders() }
                if (names.isEmpty()) {
                    setLoading(false)
                    showEmpty("相机 DCIM 里没有目录（SD 卡是否已插？）")
                    return@launch
                }
                folderAdapter.submitList(names.map { FolderRow(it, -1, null) })
                setLoading(false)
                foldersLoaded = true
                NxApp.log("读取到 ${names.size} 个目录")
                enrichFolders(names)
            } catch (e: Exception) {
                setLoading(false)
                showEmpty("读取目录失败：${e.message}")
                NxApp.log("读取目录失败：${e.message}")
            }
        }
    }

    /** 后台补齐每个目录的媒体数量与封面（限并发 3，避免拖垮单核相机） */
    private fun enrichFolders(names: List<String>) {
        lifecycleScope.launch {
            val cam = NxApp.camera ?: return@launch
            val gate = Semaphore(3)
            val limit = minOf(names.size, 16)
            coroutineScope {
                (0 until limit).forEach { idx ->
                    launch {
                        gate.withPermit {
                            val name = names[idx]
                            val media = try {
                                withContext(Dispatchers.IO) { cam.listMedia(name) }
                            } catch (e: Exception) {
                                emptyList()
                            }
                            val cover = media.firstOrNull { it.isImage }
                                ?: media.firstOrNull()
                            val updated = folderAdapter.currentList.map { row ->
                                if (row.name == name) FolderRow(name, media.size, cover) else row
                            }
                            withContext(Dispatchers.Main) {
                                folderAdapter.submitList(updated)
                            }
                        }
                    }
                }
            }
            NxApp.log("目录统计完成")
        }
    }

    // ------------------------------------------------------------------ 媒体

    private fun openFolder(folder: String) {
        currentFolder = folder
        loadMedia(folder)
    }

    private fun loadMedia(folder: String) {
        b.toolbar.title = folder
        b.crumbRow.visibility = View.VISIBLE
        b.crumbText.text = "DCIM / $folder"
        b.bottomBar.visibility = View.GONE
        b.list.layoutManager = GridLayoutManager(this, 3)
        mediaAdapter.clearSelection()
        b.list.adapter = mediaAdapter
        setLoading(true, "正在读取 $folder …")

        lifecycleScope.launch {
            val cam = NxApp.camera ?: return@launch
            try {
                // 8080 dirlist 只给文件名；大小来自 80 端口的 mongoose 目录页。
                // 先并发取大小（相机端要现场读 SD 卡目录，给点耐心），失败也能继续浏览。
                val sizesJob = async {
                    try {
                        withContext(Dispatchers.IO) { cam.fetchFolderSizes(folder) }
                    } catch (e: Exception) {
                        emptyMap<String, Long>() to emptyMap<String, String>()
                    }
                }

                val media = withContext(Dispatchers.IO) { cam.listMedia(folder) }
                if (media.isEmpty()) {
                    setLoading(false)
                    showEmpty("该目录里没有照片或视频")
                    return@launch
                }
                // 先渲染（此时可能还没有大小），拿到后立刻刷新
                mediaAdapter.submitList(media)
                setLoading(false)
                NxApp.log("$folder 共 ${media.size} 个媒体文件")

                val (sizes, modified) = sizesJob.await()
                if (sizes.isNotEmpty()) {
                    val enriched = media.map { m ->
                        m.copy(size = sizes[m.name] ?: m.size, modified = modified[m.name])
                    }
                    mediaAdapter.submitList(enriched) { updateFolderSummary(folder, enriched) }
                } else {
                    updateFolderSummary(folder, media)
                    NxApp.log("$folder 未取得文件大小（80 端口目录页可能不可用）")
                }
            } catch (e: Exception) {
                setLoading(false)
                showEmpty("读取失败：${e.message}")
            }
        }
    }

    /** 面包屑里显示条目数与总体积——相机里全是 20-34MB 的 RAW，不显示体积没法决定下不下 */
    private fun updateFolderSummary(folder: String, media: List<MediaEntry>) {
        val known = media.map { it.size }.filter { it > 0 }
        val totalText = if (known.size == media.size && known.isNotEmpty()) {
            val total = known.sum()
            " · 共 " + when {
                total >= 1024L * 1024 * 1024 -> "%.2f GB".format(total / 1024.0 / 1024 / 1024)
                else -> "%.0f MB".format(total / 1024.0 / 1024)
            }
        } else {
            ""
        }
        b.crumbText.text = "DCIM / $folder · ${media.size} 个文件$totalText"
    }

    private fun refreshSelectionUi() {
        val selected = mediaAdapter.selectedEntries()
        val count = selected.size
        b.bottomBar.visibility = if (count > 0) View.VISIBLE else View.GONE
        val known = selected.map { it.size }.filter { it > 0 }
        val sizeText = if (known.isNotEmpty() && known.size == count) {
            val total = known.sum()
            " · " + when {
                total >= 1024L * 1024 * 1024 -> "%.2f GB".format(total / 1024.0 / 1024 / 1024)
                else -> "%.0f MB".format(total / 1024.0 / 1024)
            }
        } else {
            ""
        }
        b.selectedCount.text = "已选 $count 项$sizeText"
        // 选中状态变化时刷新可见单元格的勾选标记
        mediaAdapter.notifyDataSetChanged()
    }

    private fun openViewer(entry: MediaEntry) {
        if (!entry.isPlainImage) {
            // RAW 与视频：Android 解不了（.SRW 无原生解码器），直接提供下载
            confirmDownload(listOf(entry), reason = "${entry.kindLabel} 无法在手机上预览")
            return
        }
        val folder = entry.folder
        val names = mediaAdapter.currentList.filter { it.isPlainImage }.map { it.name }
        if (names.isEmpty()) {
            confirmDownload(listOf(entry), reason = "这张图无法预览")
            return
        }
        startActivity(
            Intent(this, ViewerActivity::class.java)
                .putExtra(ViewerActivity.EXTRA_FOLDER, folder)
                .putExtra(ViewerActivity.EXTRA_NAMES, names.toTypedArray())
                .putExtra(ViewerActivity.EXTRA_INDEX, names.indexOf(entry.name).coerceAtLeast(0)),
        )
    }

    /**
     * 大文件下载前确认体积。
     *
     * 你的相机里单张 RAW 有 19–34MB，全选一个目录可能上 GB —— 不报体积就让用户点下载
     * 是不负责任的（早期版本甚至没给 RAW 提供下载入口，用户实测反馈过）。
     */
    private fun confirmDownload(entries: List<MediaEntry>, reason: String? = null) {
        if (entries.isEmpty()) return
        val known = entries.map { it.size }.filter { it > 0 }
        val totalText = if (known.isNotEmpty() && known.size == entries.size) {
            val total = known.sum()
            when {
                total >= 1024L * 1024 * 1024 -> "%.2f GB".format(total / 1024.0 / 1024 / 1024)
                else -> "%.0f MB".format(total / 1024.0 / 1024)
            }
        } else {
            null
        }
        val big = (known.sum() > 200L * 1024 * 1024) || (totalText == null && entries.size > 3)

        val message = buildString {
            reason?.let { append(it).append("\n\n") }
            append("即将下载 ${entries.size} 个文件")
            totalText?.let { append("，共 ").append(it) }
            append("。\n")
            if (entries.any { it.isRaw }) {
                append("\nRAW 会存到 App 专属目录：")
                append("\n").append(MediaStoreWriter.rawDir(this@GalleryActivity))
                append("\n（用数据线连电脑后可在此取走做后期）")
            }
            if (big) append("\n\n数据量较大，建议保持屏幕常亮、靠近相机。")
        }

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(if (entries.size == 1) entries[0].name else "批量下载")
            .setMessage(message)
            .setPositiveButton("开始下载") { _, _ -> startDownload(entries) }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun startDownload(entries: List<MediaEntry>) {
        val ip = NxApp.cameraIp ?: return
        ensureStoragePermission()
        DownloadService.start(this, ip, entries)
        toast("已开始下载 ${entries.size} 个文件，可在通知栏查看进度")
        mediaAdapter.clearSelection()
        refreshSelectionUi()
    }

    private fun ensureStoragePermission() {
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED
            ) {
                storagePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
