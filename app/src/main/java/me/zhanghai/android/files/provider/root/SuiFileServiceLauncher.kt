/*
 * Copyright (c) 2021 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.root

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.annotation.ChecksSdkIntAtLeast
import androidx.annotation.Keep
import androidx.annotation.RequiresApi
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import me.zhanghai.android.files.BuildConfig
import me.zhanghai.android.files.app.application
import me.zhanghai.android.files.provider.remote.IRemoteFileService
import me.zhanghai.android.files.provider.remote.RemoteFileServiceInterface
import me.zhanghai.android.files.provider.remote.RemoteFileSystemException
import rikka.shizuku.Shizuku
import rikka.sui.Sui
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.system.exitProcess

object ShizukuFileServiceLauncher {
    enum class State {
        UNAVAILABLE,
        UNSUPPORTED,
        PERMISSION_REQUIRED,
        PERMISSION_DENIED,
        GRANTED
    }

    enum class PermissionResult {
        GRANTED,
        UNAVAILABLE,
        DENIED,
        ERROR
    }

    private const val REQUEST_PERMISSION_CODE = 0x4D46
    private const val REQUEST_BINDER_TIMEOUT_MILLIS = 3_000L
    private const val REQUEST_PERMISSION_TIMEOUT_MILLIS = 120_000L

    private val initializationLock = Any()
    private val serviceLock = Any()
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var isBinderAvailable = false
    @Volatile
    private var isInitialized = false
    private var pendingPermissionRequest: PendingPermissionRequest? = null

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        isBinderAvailable = true
        continuePermissionRequest()
    }
    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        isBinderAvailable = false
        finishPermissionRequest(PermissionResult.UNAVAILABLE)
    }
    private val permissionResultListener = Shizuku.OnRequestPermissionResultListener {
            requestCode, grantResult ->
        if (pendingPermissionRequest?.requestCode == requestCode) {
            finishPermissionRequest(
                if (grantResult == PackageManager.PERMISSION_GRANTED) {
                    PermissionResult.GRANTED
                } else {
                    PermissionResult.DENIED
                }
            )
        }
    }
    private val permissionTimeoutRunnable = Runnable {
        finishPermissionRequest(PermissionResult.ERROR)
    }
    private val binderTimeoutRunnable = Runnable {
        finishPermissionRequest(PermissionResult.UNAVAILABLE)
    }

    private val userServiceArgs by lazy {
        Shizuku.UserServiceArgs(
            ComponentName(application, ShizukuFileServiceInterface::class.java)
        )
            .debuggable(BuildConfig.DEBUG)
            .daemon(false)
            .tag("material_files_file_service")
            .processNameSuffix("shizuku")
            .version(BuildConfig.VERSION_CODE)
    }

    fun initialize(context: Context = application) {
        if (isInitialized) {
            return
        }
        synchronized(initializationLock) {
            if (isInitialized) {
                return
            }
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
                isInitialized = true
                return
            }
            // ShizukuProvider initializes Sui too, but explicitly initializing here also covers
            // processes whose provider has not run yet.
            try {
                Sui.init(context.applicationContext.packageName)
            } catch (_: Throwable) {
                // ShizukuProvider may still deliver a Shizuku binder.
            }
            isBinderAvailable = Shizuku.pingBinder()
            Shizuku.addBinderReceivedListenerSticky(binderReceivedListener, mainHandler)
            Shizuku.addBinderDeadListener(binderDeadListener, mainHandler)
            Shizuku.addRequestPermissionResultListener(permissionResultListener, mainHandler)
            isInitialized = true
        }
    }

    @ChecksSdkIntAtLeast(api = Build.VERSION_CODES.M)
    fun isShizukuAvailable(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return false
        }
        initialize()
        return isBinderAvailable
    }

    fun isPermissionGranted(): Boolean {
        return getState() == State.GRANTED
    }

    fun getState(): State {
        if (!isShizukuAvailable()) {
            return State.UNAVAILABLE
        }
        return try {
            when {
                Shizuku.isPreV11() -> State.UNSUPPORTED
                Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED -> State.GRANTED
                Shizuku.shouldShowRequestPermissionRationale() -> State.PERMISSION_DENIED
                else -> State.PERMISSION_REQUIRED
            }
        } catch (_: Throwable) {
            State.UNAVAILABLE
        }
    }

    /**
     * Requests permission from the foreground app process. Permission dialogs must not be
     * initiated from the background file-service worker, otherwise Shizuku cannot reliably show
     * the confirmation UI.
     */
    fun requestPermission(callback: (PermissionResult) -> Unit) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { requestPermission(callback) }
            return
        }
        pendingPermissionRequest?.let {
            it.callbacks += callback
            return
        }
        pendingPermissionRequest = PendingPermissionRequest(
            REQUEST_PERMISSION_CODE, mutableListOf(callback)
        )
        continuePermissionRequest()
    }

    private fun continuePermissionRequest() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { continuePermissionRequest() }
            return
        }
        val request = pendingPermissionRequest ?: return
        when (getState()) {
            State.GRANTED -> finishPermissionRequest(PermissionResult.GRANTED)
            State.UNAVAILABLE -> {
                mainHandler.removeCallbacks(binderTimeoutRunnable)
                mainHandler.postDelayed(
                    binderTimeoutRunnable, REQUEST_BINDER_TIMEOUT_MILLIS
                )
            }
            State.UNSUPPORTED -> finishPermissionRequest(PermissionResult.ERROR)
            State.PERMISSION_DENIED -> finishPermissionRequest(PermissionResult.DENIED)
            State.PERMISSION_REQUIRED -> {
                // The binder is there, so whatever we were waiting for it has happened either way.
                mainHandler.removeCallbacks(binderTimeoutRunnable)
                if (request.isPermissionRequested) {
                    return
                }
                request.isPermissionRequested = true
                mainHandler.postDelayed(
                    permissionTimeoutRunnable, REQUEST_PERMISSION_TIMEOUT_MILLIS
                )
                try {
                    Shizuku.requestPermission(request.requestCode)
                } catch (_: Throwable) {
                    finishPermissionRequest(PermissionResult.ERROR)
                }
            }
        }
    }

    private fun finishPermissionRequest(result: PermissionResult) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { finishPermissionRequest(result) }
            return
        }
        // Dropped whether or not there is still a request, so that a timeout armed for one that has
        // already been answered cannot fire against the next.
        mainHandler.removeCallbacks(binderTimeoutRunnable)
        mainHandler.removeCallbacks(permissionTimeoutRunnable)
        val request = pendingPermissionRequest ?: return
        pendingPermissionRequest = null
        request.callbacks.forEach { it(result) }
    }

    @RequiresApi(Build.VERSION_CODES.M)
    @Throws(RemoteFileSystemException::class)
    fun launchService(): IRemoteFileService {
        if (BuildConfig.DEBUG && Looper.myLooper() == Looper.getMainLooper()) {
            throw IllegalStateException(
                "launchService() blocks for up to ${RootFileService.TIMEOUT_MILLIS} ms and must" +
                    " not be called on the main thread"
            )
        }
        // Checked before the lock as well as under it: a state that cannot produce a service should
        // say so at once, rather than wait out another thread's bind attempt to say the same thing.
        checkState()
        synchronized(serviceLock) {
            checkState()
            return try {
                runBlocking {
                    try {
                        withTimeout(RootFileService.TIMEOUT_MILLIS) {
                            suspendCancellableCoroutine { continuation ->
                                val connection = object : ServiceConnection {
                                    private var isDetached = false

                                    private fun detach() {
                                        if (isDetached) {
                                            return
                                        }
                                        isDetached = true
                                        try {
                                            Shizuku.unbindUserService(
                                                userServiceArgs, this, false
                                            )
                                        } catch (_: Throwable) {
                                            // The Shizuku binder may already be unavailable.
                                        }
                                    }

                                    private fun fail(message: String) {
                                        if (continuation.isActive) {
                                            continuation.resumeWithException(
                                                RemoteFileSystemException(message)
                                            )
                                        }
                                        detach()
                                    }

                                    override fun onServiceConnected(
                                        name: ComponentName,
                                        service: IBinder
                                    ) {
                                        if (!continuation.isActive) {
                                            detach()
                                            return
                                        }
                                        if (!service.pingBinder()) {
                                            fail("Shizuku returned a dead service binder")
                                            return
                                        }
                                        val serviceInterface =
                                            IRemoteFileService.Stub.asInterface(service)
                                        continuation.resume(serviceInterface)
                                    }

                                    override fun onServiceDisconnected(name: ComponentName) {
                                        fail("Shizuku service disconnected")
                                    }

                                    override fun onBindingDied(name: ComponentName) {
                                        fail("Shizuku binding died")
                                    }

                                    override fun onNullBinding(name: ComponentName) {
                                        fail("Shizuku binding is null")
                                    }
                                }
                                continuation.invokeOnCancellation {
                                    try {
                                        Shizuku.unbindUserService(
                                            userServiceArgs, connection, true
                                        )
                                    } catch (_: Throwable) {
                                        // The Shizuku binder may have died while the bind timed out.
                                    }
                                }
                                try {
                                    Shizuku.bindUserService(userServiceArgs, connection)
                                } catch (e: Throwable) {
                                    if (continuation.isActive) {
                                        continuation.resumeWithException(
                                            RemoteFileSystemException(e)
                                        )
                                        try {
                                            Shizuku.unbindUserService(
                                                userServiceArgs, connection, false
                                            )
                                        } catch (_: Throwable) {
                                            // Binding may have failed before a connection existed.
                                        }
                                    }
                                }
                            }
                        }
                    } catch (e: TimeoutCancellationException) {
                        throw RemoteFileSystemException(e)
                    }
                }
            } catch (e: InterruptedException) {
                throw RemoteFileSystemException(e)
            } catch (e: RemoteFileSystemException) {
                throw e
            } catch (e: Throwable) {
                throw RemoteFileSystemException(e)
            }
        }
    }

    /** Throws unless the current state is one a service can actually be launched from. */
    @Throws(RemoteFileSystemException::class)
    private fun checkState() {
        when (getState()) {
            State.UNAVAILABLE -> throw RemoteFileSystemException(
                "Shizuku is not running"
            )
            State.UNSUPPORTED -> throw RemoteFileSystemException(
                "This version of Shizuku is not supported"
            )
            State.PERMISSION_REQUIRED -> throw RemoteFileSystemException(
                "Shizuku authorization is required; grant it from Settings first"
            )
            State.PERMISSION_DENIED -> throw RemoteFileSystemException(
                "Shizuku authorization was denied; allow My Files in Shizuku"
            )
            State.GRANTED -> Unit
        }
    }

    private data class PendingPermissionRequest(
        val requestCode: Int,
        val callbacks: MutableList<(PermissionResult) -> Unit>,
        var isPermissionRequested: Boolean = false
    )
}

@Keep
@RequiresApi(Build.VERSION_CODES.M)
class ShizukuFileServiceInterface : RemoteFileServiceInterface {
    constructor() : super() {
        RootFileService.main()
    }

    // Shizuku v13 supplies a package context to UserService constructors. Prefer it over hidden
    // ActivityThread reflection while keeping the default constructor for older compatible servers.
    constructor(context: Context) : super() {
        RootFileService.main(context)
    }

    override fun destroy() {
        exitProcess(0)
    }
}
