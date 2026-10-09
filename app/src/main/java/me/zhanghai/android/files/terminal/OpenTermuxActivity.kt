/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.terminal

import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.parcelize.Parcelize
import me.zhanghai.android.files.R
import me.zhanghai.android.files.app.AppActivity
import me.zhanghai.android.files.compat.checkSelfPermissionCompat
import me.zhanghai.android.files.util.ParcelableArgs
import me.zhanghai.android.files.util.args
import me.zhanghai.android.files.util.startActivitySafe

/** Keeps permission requests and their folder across activity recreation. */
class OpenTermuxActivity : AppActivity() {
    private val args by args<Args>()
    private var waitingForPermission = false
    private var waitingForSettings = false
    private var permissionDenied = false
    private var errorMessage: String? = null
    private var dialog: AlertDialog? = null

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        waitingForPermission = false
        onPermissionResult(granted)
    }

    private val settingsLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        waitingForSettings = false
        onPermissionResult(hasPermission())
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Calls ensureSubDecor() for the translucent AppCompat activity.
        findViewById<View>(android.R.id.content)
        waitingForPermission = savedInstanceState?.getBoolean(STATE_WAITING_FOR_PERMISSION) ?: false
        waitingForSettings = savedInstanceState?.getBoolean(STATE_WAITING_FOR_SETTINGS) ?: false
        permissionDenied = savedInstanceState?.getBoolean(STATE_PERMISSION_DENIED) ?: false
        errorMessage = savedInstanceState?.getString(STATE_ERROR_MESSAGE) ?: args.errorMessage
        when {
            errorMessage != null -> showError(errorMessage!!)
            waitingForPermission || waitingForSettings -> Unit
            hasPermission() -> openTermux()
            permissionDenied -> showPermissionDenied()
            else -> showSetup()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_WAITING_FOR_PERMISSION, waitingForPermission)
        outState.putBoolean(STATE_WAITING_FOR_SETTINGS, waitingForSettings)
        outState.putBoolean(STATE_PERMISSION_DENIED, permissionDenied)
        outState.putString(STATE_ERROR_MESSAGE, errorMessage)
    }

    override fun onDestroy() {
        dialog?.dismiss()
        super.onDestroy()
    }

    private fun hasPermission(): Boolean =
        checkSelfPermissionCompat(Termux.PERMISSION_RUN_COMMAND) == PackageManager.PERMISSION_GRANTED

    private fun showSetup() {
        dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.file_list_action_open_in_terminal)
            .setMessage(R.string.terminal_termux_setup_message)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                waitingForPermission = true
                permissionLauncher.launch(Termux.PERMISSION_RUN_COMMAND)
            }
            .setNegativeButton(android.R.string.cancel) { _, _ -> finish() }
            .setOnCancelListener { finish() }
            .show()
    }

    private fun onPermissionResult(granted: Boolean) {
        if (granted) {
            permissionDenied = false
            openTermux()
        } else {
            permissionDenied = true
            showPermissionDenied()
        }
    }

    private fun showPermissionDenied() {
        dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.file_list_action_open_in_terminal)
            .setMessage(R.string.terminal_permission_denied_message)
            .setPositiveButton(R.string.open_settings) { _, _ ->
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    .setData(Uri.fromParts("package", packageName, null))
                waitingForSettings = true
                try {
                    settingsLauncher.launch(intent)
                } catch (_: ActivityNotFoundException) {
                    waitingForSettings = false
                    showError(getString(R.string.terminal_permission_denied_message))
                }
            }
            .setNegativeButton(android.R.string.cancel) { _, _ -> finish() }
            .setOnCancelListener { finish() }
            .show()
    }

    private fun openTermux() {
        val path = args.path
        if (path == null) {
            finish()
            return
        }
        try {
            Termux.open(path, this)
            finish()
        } catch (_: ActivityNotFoundException) {
            showError(getString(R.string.terminal_not_found))
        } catch (_: SecurityException) {
            permissionDenied = true
            showPermissionDenied()
        } catch (e: IllegalStateException) {
            showError(e.localizedMessage ?: getString(R.string.terminal_open_failed))
        }
    }

    private fun showError(message: String) {
        errorMessage = message
        dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.terminal_open_failed)
            .setMessage(getString(R.string.terminal_termux_error_message_format, message))
            .setPositiveButton(R.string.terminal_setup_help) { _, _ ->
                startActivitySafe(Intent(Intent.ACTION_VIEW, Uri.parse(Termux.HELP_URL)))
                finish()
            }
            .setNegativeButton(R.string.close) { _, _ -> finish() }
            .setOnCancelListener { finish() }
            .show()
    }

    @Parcelize
    class Args(val path: String? = null, val errorMessage: String? = null) : ParcelableArgs

    companion object {
        private const val STATE_WAITING_FOR_PERMISSION = "waiting_for_permission"
        private const val STATE_WAITING_FOR_SETTINGS = "waiting_for_settings"
        private const val STATE_PERMISSION_DENIED = "permission_denied"
        private const val STATE_ERROR_MESSAGE = "error_message"
    }
}
