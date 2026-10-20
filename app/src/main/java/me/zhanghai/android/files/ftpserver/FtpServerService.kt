/*
 * Copyright (c) 2019 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.ftpserver

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.annotation.WorkerThread
import androidx.core.content.ContextCompat
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import me.zhanghai.android.files.R
import me.zhanghai.android.files.app.AppLocaleHelper
import me.zhanghai.android.files.compat.mainExecutorCompat
import me.zhanghai.android.files.httpserver.HttpServer
import me.zhanghai.android.files.settings.Settings
import me.zhanghai.android.files.util.WakeWifiLock
import me.zhanghai.android.files.util.showToast
import me.zhanghai.android.files.util.valueCompat
import java.util.concurrent.Executors

class FtpServerService : Service() {
    private var state = State.STOPPED
        set(value) {
            field = value
            _stateLiveData.value = value
        }

    private lateinit var wakeWifiLock: WakeWifiLock

    private lateinit var notification: FtpServerNotification

    private val executorService = Executors.newSingleThreadExecutor()

    private var server: LocalServer? = null

    private var destroyed = false

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLocaleHelper.wrap(newBase))
    }

    override fun onCreate() {
        super.onCreate()

        wakeWifiLock = WakeWifiLock(FtpServerService::class.java.simpleName)
        notification = FtpServerNotification(this)
        executeStart()
    }

    override fun onBind(intent: Intent): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        super.onDestroy()

        destroyed = true
        executeStop()
        executorService.shutdown()
    }

    private fun executeStart() {
        if (state == State.STARTING || state == State.RUNNING) {
            return
        }
        wakeWifiLock.isAcquired = true
        notification.startForeground()
        state = State.STARTING
        executorService.execute { doStart() }
    }

    private fun onStartError(exception: Exception) {
        state = State.STOPPED
        if (destroyed) {
            return
        }
        showToast(exception.toString())
        notification.stopForeground()
        wakeWifiLock.isAcquired = false
        stopSelf()
    }

    private fun executeStop() {
        if (state == State.STOPPING || state == State.STOPPED) {
            return
        }
        state = State.STOPPING
        executorService.execute { doStop() }
        notification.stopForeground()
        wakeWifiLock.isAcquired = false
    }

    @WorkerThread
    private fun postState(state: State) {
        mainExecutorCompat.execute {
            if (state != State.RUNNING || !destroyed) {
                this.state = state
            }
        }
    }

    @WorkerThread
    private fun doStart() {
        if (server != null) {
            return
        }
        val username: String
        val password: String?
        if (Settings.FTP_SERVER_ANONYMOUS_LOGIN.valueCompat) {
            username = USERNAME_ANONYMOUS
            password = null
        } else {
            username = Settings.FTP_SERVER_USERNAME.valueCompat
            password = Settings.FTP_SERVER_PASSWORD.valueCompat
        }
        val protocol = Settings.SERVER_PROTOCOL.valueCompat
        val port = when (protocol) {
            ServerProtocol.FTP -> Settings.FTP_SERVER_PORT.valueCompat
            ServerProtocol.HTTP -> Settings.HTTP_SERVER_PORT.valueCompat
        }
        val homeDirectory = Settings.FTP_SERVER_HOME_DIRECTORY.valueCompat
        val writable = Settings.FTP_SERVER_WRITABLE.valueCompat
        try {
            require(port in 1..65535) { getString(R.string.server_port_error) }
            val server = when (protocol) {
                ServerProtocol.FTP -> FtpServer(username, password, port, homeDirectory, writable)
                ServerProtocol.HTTP -> {
                    require(password == null || ':' !in username) {
                        getString(R.string.http_server_username_error)
                    }
                    HttpServer(username, password, port, homeDirectory, writable)
                }
            }
            this.server = server
            server.start()
        } catch (e: Exception) {
            e.printStackTrace()
            runCatching { this.server?.stop() }.onFailure { it.printStackTrace() }
            this.server = null
            mainExecutorCompat.execute { onStartError(e) }
            return
        }
        postState(State.RUNNING)
    }

    @WorkerThread
    private fun doStop() {
        try {
            server?.stop()
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            server = null
            postState(State.STOPPED)
        }
    }

    companion object {
        const val USERNAME_ANONYMOUS = "anonymous"

        private val _stateLiveData = MutableLiveData(State.STOPPED)
        val stateLiveData: LiveData<State>
            get() = _stateLiveData

        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context, Intent(context, FtpServerService::class.java)
            )
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, FtpServerService::class.java))
        }

        fun toggle(context: Context) {
            when (val state = _stateLiveData.valueCompat) {
                State.STARTING, State.STOPPING -> {}
                State.RUNNING -> stop(context)
                State.STOPPED -> start(context)
                else -> throw AssertionError(state)
            }
        }
    }

    enum class State {
        STARTING,
        RUNNING,
        STOPPING,
        STOPPED
    }
}
