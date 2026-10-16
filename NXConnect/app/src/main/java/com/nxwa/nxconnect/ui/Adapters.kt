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

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.nxwa.nxconnect.R
import com.nxwa.nxconnect.data.MediaEntry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 两个列表共用的缩略图装载逻辑。
 *
 * 竞态保护：每次绑定都取消该 View 上一次的加载任务，且回填前校验 tag，
 * 避免快速滑动时旧图片落到新位置（RecyclerView 复用 ViewHolder 的经典坑）。
 */
class ThumbBinder(private val view: ImageView) {
    private var job: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    fun bind(entry: MediaEntry, width: Int, placeholderTransparent: Boolean = true) {
        view.tag = entry.relPath
        job?.cancel()

        val cached = ThumbLoader.peekAny(entry.relPath)
        if (cached != null) {
            view.setImageBitmap(cached)
            return
        }
        if (placeholderTransparent) view.setImageResource(android.R.color.transparent)
        if (entry.isVideo) return

        job = scope.launch {
            val bmp = try {
                ThumbLoader.load(entry, width)
            } catch (e: Exception) {
                null
            }
            if (bmp != null && view.tag == entry.relPath) {
                view.setImageBitmap(bmp)
            }
        }
    }

    fun dispose() {
        job?.cancel()
        scope.cancel()
    }

    /** 改为占位显示时清掉旧图，避免复用出错位 */
    fun clear() {
        job?.cancel()
        view.tag = null
        view.setImageDrawable(null)
    }
}

/** 目录列表 */
data class FolderRow(val name: String, val count: Int, val cover: MediaEntry?)

class FolderAdapter(
    private val onClick: (FolderRow) -> Unit,
) : ListAdapter<FolderRow, FolderAdapter.VH>(DIFF) {

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<FolderRow>() {
            override fun areItemsTheSame(a: FolderRow, b: FolderRow) = a.name == b.name
            override fun areContentsTheSame(a: FolderRow, b: FolderRow) =
                a.name == b.name && a.count == b.count && a.cover?.relPath == b.cover?.relPath
        }
    }

    inner class VH(v: View) : RecyclerView.ViewHolder(v) {
        val cover: ImageView = v.findViewById(R.id.cover)
        val name: TextView = v.findViewById(R.id.name)
        val sub: TextView = v.findViewById(R.id.sub)
        val binder = ThumbBinder(cover)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_folder, parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        val row = getItem(position)
        holder.name.text = row.name
        holder.sub.text = if (row.count >= 0) "${row.count} 个媒体文件" else "点击查看"
        holder.itemView.setOnClickListener { onClick(row) }
        val cover = row.cover
        if (cover == null) {
            holder.cover.setImageResource(android.R.color.transparent)
        } else {
            holder.binder.bind(cover, 160)
        }
    }

    override fun onViewRecycled(holder: VH) {
        holder.binder.dispose()
        super.onViewRecycled(holder)
    }
}

/** 媒体网格 */
class MediaAdapter(
    private val onClick: (MediaEntry) -> Unit,
    private val onLongClick: (MediaEntry) -> Unit,
) : ListAdapter<MediaEntry, MediaAdapter.VH>(DIFF) {

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<MediaEntry>() {
            override fun areItemsTheSame(a: MediaEntry, b: MediaEntry) = a.relPath == b.relPath
            override fun areContentsTheSame(a: MediaEntry, b: MediaEntry) = a == b
        }
    }

    val selected = linkedSetOf<String>()

    var selectionMode: Boolean = false
        private set

    fun enterSelection(entry: MediaEntry) {
        selected.add(entry.relPath)
        selectionMode = true
        notifyItemChanged(currentList.indexOf(entry))
    }

    fun toggle(entry: MediaEntry) {
        if (!selected.remove(entry.relPath)) selected.add(entry.relPath)
        selectionMode = selected.isNotEmpty()
        notifyItemChanged(currentList.indexOf(entry))
    }

    fun selectAll() {
        currentList.forEach { selected.add(it.relPath) }
        selectionMode = true
        notifyDataSetChanged()
    }

    fun clearSelection() {
        selected.clear()
        selectionMode = false
        notifyDataSetChanged()
    }

    fun selectedEntries(): List<MediaEntry> = currentList.filter { it.relPath in selected }

    inner class VH(v: View) : RecyclerView.ViewHolder(v) {
        val thumb: ImageView = v.findViewById(R.id.thumb)
        val name: TextView = v.findViewById(R.id.name)
        val badge: TextView = v.findViewById(R.id.videoBadge)
        val check: ImageView = v.findViewById(R.id.check)
        val placeholder: View = v.findViewById(R.id.placeholder)
        val kindLabel: TextView = v.findViewById(R.id.kindLabel)
        val sizeLabel: TextView = v.findViewById(R.id.sizeLabel)
        val binder = ThumbBinder(thumb)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_media, parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        val entry = getItem(position)
        holder.name.text = if (entry.sizeText != null) "${entry.name} · ${entry.sizeText}" else entry.name
        holder.badge.visibility = if (entry.isVideo) View.VISIBLE else View.GONE
        holder.check.visibility = if (entry.relPath in selected) View.VISIBLE else View.GONE

        // RAW 与视频取不到缩略图：显示带标识的占位格，而不是灰块
        val needsPlaceholder = entry.isRaw || entry.isVideo
        if (needsPlaceholder) {
            holder.binder.clear()
            holder.thumb.visibility = View.GONE
            holder.placeholder.visibility = View.VISIBLE
            holder.kindLabel.text = entry.kindLabel
            holder.sizeLabel.text = listOfNotNull(entry.sizeText, entry.placeholderHint)
                .joinToString(" · ")
                .ifBlank { "点击下载" }
        } else {
            holder.placeholder.visibility = View.GONE
            holder.thumb.visibility = View.VISIBLE
            holder.binder.bind(entry, 320)
        }

        holder.itemView.setOnClickListener {
            if (selectionMode) toggle(entry) else onClick(entry)
        }
        holder.itemView.setOnLongClickListener {
            onLongClick(entry)
            true
        }
    }

    override fun onViewRecycled(holder: VH) {
        holder.binder.dispose()
        super.onViewRecycled(holder)
    }
}
