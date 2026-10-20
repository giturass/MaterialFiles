/*
 * Copyright (c) 2023 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.ftpserver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import me.zhanghai.android.files.settings.Settings
import me.zhanghai.android.files.util.RuntimeBroadcastReceiver
import me.zhanghai.android.files.util.getLocalAddress
import me.zhanghai.android.files.util.valueCompat
import java.net.InetAddress

object FtpServerUrl {
    fun getUrl(): String? {
        val localAddress = InetAddress::class.getLocalAddress() ?: return null
        val protocol = Settings.SERVER_PROTOCOL.valueCompat
        val username = if (
            protocol == ServerProtocol.FTP && !Settings.FTP_SERVER_ANONYMOUS_LOGIN.valueCompat
        ) {
            Settings.FTP_SERVER_USERNAME.valueCompat
        } else {
            null
        }
        val host = localAddress.hostAddress!!.let { if (':' in it) "[$it]" else it }
        val port = when (protocol) {
            ServerProtocol.FTP -> Settings.FTP_SERVER_PORT.valueCompat
            ServerProtocol.HTTP -> Settings.HTTP_SERVER_PORT.valueCompat
        }
        val scheme = when (protocol) {
            ServerProtocol.FTP -> "ftp"
            ServerProtocol.HTTP -> "http"
        }
        return "$scheme://${if (username != null) "$username@" else ""}$host:$port/"
    }

    fun createChangeReceiver(context: Context, onChange: () -> Unit): RuntimeBroadcastReceiver =
        RuntimeBroadcastReceiver(
            IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION), object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    onChange()
                }
            }, context
        )
}
