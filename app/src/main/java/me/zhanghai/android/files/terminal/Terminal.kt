/*
 * Copyright (c) 2018 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.terminal

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.content.ContextCompat
import me.zhanghai.android.files.app.packageManager

/**
 * Opens a directory in whichever terminal application is installed.
 *
 * Three kinds of terminal are understood, because they are started in three different ways:
 *
 * - "Term Here" style activities, which take the directory as an [Intent.ACTION_SEND] stream.
 * - Termux, which has no such activity and instead runs commands through an exported service, behind
 *   a runtime permission of its own.
 * - Android Terminal Emulator, whose component name is fixed.
 */
object Terminal {
    /** Termux's own runtime permission, which has to be granted before it will run anything. */
    const val TERMUX_RUN_COMMAND_PERMISSION = "com.termux.permission.RUN_COMMAND"

    private const val TERMUX_PACKAGE_NAME = "com.termux"
    private const val TERMUX_RUN_COMMAND_SERVICE_NAME = "com.termux.app.RunCommandService"
    private const val TERMUX_RUN_COMMAND_ACTION = "com.termux.RUN_COMMAND"
    private const val TERMUX_EXTRA_COMMAND_PATH = "com.termux.RUN_COMMAND_PATH"
    private const val TERMUX_EXTRA_WORK_DIRECTORY = "com.termux.RUN_COMMAND_WORKDIR"
    private const val TERMUX_EXTRA_BACKGROUND = "com.termux.RUN_COMMAND_BACKGROUND"
    private const val TERMUX_EXTRA_SESSION_ACTION = "com.termux.RUN_COMMAND_SESSION_ACTION"

    /**
     * `SESSION_ACTION_SWITCH_TO_NEW_SESSION_AND_OPEN_ACTIVITY`, which is what brings Termux to the
     * front. Without it the session is created but the user is left looking at us.
     */
    private const val TERMUX_SESSION_ACTION_OPEN_ACTIVITY = "0"

    /**
     * Termux's login shell, which is what one of its own sessions runs. It has to be named
     * explicitly because Termux takes no command at all as an error rather than as "just a shell".
     */
    private const val TERMUX_LOGIN_SHELL_PATH =
        "/data/data/$TERMUX_PACKAGE_NAME/files/usr/bin/login"

    private const val ANDROID_TERMINAL_EMULATOR_PACKAGE_NAME = "jackpal.androidterm"
    private const val ANDROID_TERMINAL_EMULATOR_ACTIVITY_NAME = "jackpal.androidterm.TermHere"

    /** Whether opening a terminal is worth offering at all, i.e. whether one is installed. */
    fun isAvailable(): Boolean =
        findTermHereComponent() != null || isInstalled(TERMUX_PACKAGE_NAME)
            || isInstalled(ANDROID_TERMINAL_EMULATOR_PACKAGE_NAME)

    fun open(path: String, context: Context): Result {
        findTermHereComponent()?.let {
            if (start(termHereIntent(it, path), context)) {
                return Result.OPENED
            }
        }
        var needsTermuxPermission = false
        if (isInstalled(TERMUX_PACKAGE_NAME)) {
            if (hasTermuxRunCommandPermission(context)) {
                if (openInTermux(path, context)) {
                    return Result.OPENED
                }
            } else {
                needsTermuxPermission = true
            }
        }
        if (isInstalled(ANDROID_TERMINAL_EMULATOR_PACKAGE_NAME)) {
            val component = ComponentName(
                ANDROID_TERMINAL_EMULATOR_PACKAGE_NAME, ANDROID_TERMINAL_EMULATOR_ACTIVITY_NAME
            )
            if (start(termHereIntent(component, path), context)) {
                return Result.OPENED
            }
        }
        // Only worth asking for once nothing that is already allowed has worked.
        return if (needsTermuxPermission) Result.TERMUX_PERMISSION_REQUIRED else Result.NOT_FOUND
    }

    fun hasTermuxRunCommandPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, TERMUX_RUN_COMMAND_PERMISSION) ==
            PackageManager.PERMISSION_GRANTED

    private fun findTermHereComponent(): ComponentName? =
        packageManager.queryIntentActivities(Intent(Intent.ACTION_SEND).setType("*/*"), 0)
            .firstOrNull {
                it.activityInfo.name.endsWith(".TermHere")
                    // Termux registers an ACTION_SEND receiver of its own, which saves the file
                    // instead of opening a shell in it.
                    && it.activityInfo.packageName != TERMUX_PACKAGE_NAME
            }
            ?.activityInfo
            ?.let { ComponentName(it.packageName, it.name) }

    private fun termHereIntent(component: ComponentName, path: String): Intent =
        Intent()
            .setComponent(component)
            .setAction(Intent.ACTION_SEND)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra(Intent.EXTRA_STREAM, Uri.parse(path))

    /**
     * Asks Termux to open a session in [path]. Termux additionally requires `allow-external-apps` to
     * be set in its own `termux.properties`; when it isn't, it refuses and says so itself, which is
     * why the session is asked to come to the front either way.
     */
    private fun openInTermux(path: String, context: Context): Boolean {
        val intent = Intent(TERMUX_RUN_COMMAND_ACTION)
            .setClassName(TERMUX_PACKAGE_NAME, TERMUX_RUN_COMMAND_SERVICE_NAME)
            .putExtra(TERMUX_EXTRA_COMMAND_PATH, TERMUX_LOGIN_SHELL_PATH)
            .putExtra(TERMUX_EXTRA_WORK_DIRECTORY, path)
            .putExtra(TERMUX_EXTRA_BACKGROUND, false)
            .putExtra(TERMUX_EXTRA_SESSION_ACTION, TERMUX_SESSION_ACTION_OPEN_ACTIVITY)
        return try {
            context.startService(intent) != null
        } catch (e: Exception) {
            // A background start restriction, or a Termux build that refuses the command.
            e.printStackTrace()
            // Bringing Termux up is still closer to what was asked for than doing nothing.
            start(packageManager.getLaunchIntentForPackage(TERMUX_PACKAGE_NAME), context)
        }
    }

    private fun start(intent: Intent?, context: Context): Boolean {
        intent ?: return false
        return try {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    private fun isInstalled(packageName: String): Boolean =
        try {
            packageManager.getPackageInfo(packageName, 0)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }

    enum class Result {
        OPENED,
        /** Termux is installed but hasn't granted [TERMUX_RUN_COMMAND_PERMISSION] yet. */
        TERMUX_PERMISSION_REQUIRED,
        /** Nothing is installed that knows how to open a directory in a shell. */
        NOT_FOUND
    }
}
