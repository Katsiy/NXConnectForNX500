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

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import java.net.HttpURLConnection

/**
 * Android 实现：把请求绑定到相机所在的网络。
 *
 * 为什么必须这么做：NX500 自己做 AP 时**没有外网**，手机同时开着蜂窝数据时，
 * 系统默认路由会走蜂窝，对 192.168.43.x 的请求可能被送到错误的接口而超时。
 * [ConnectivityManager.bindProcessToNetwork] 能强制整个进程走指定网络。
 */
class AndroidHttpBinder(private val ctx: Context) : HttpBinder {

    override fun bind(conn: HttpURLConnection) {
        // 进程级绑定已在 NetworkBinder.bindTo() 完成。
        // HttpURLConnection 不是 Socket，无法在这里做 per-connection 绑定；
        // 保留此实现的目的是把"是否需要绑定"这件事与协议层解耦。
    }
}
