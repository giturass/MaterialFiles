/*
 * Copyright (c) 2019 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.theme.custom

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import androidx.annotation.StyleRes
import me.zhanghai.android.files.R
import me.zhanghai.android.files.compat.recreateCompat
import me.zhanghai.android.files.compat.setThemeCompat
import me.zhanghai.android.files.compat.themeResIdCompat
import me.zhanghai.android.files.settings.Settings
import me.zhanghai.android.files.theme.night.NightMode
import me.zhanghai.android.files.util.SimpleActivityLifecycleCallbacks
import me.zhanghai.android.files.util.valueCompat

object CustomThemeHelper {
    private val activityBaseThemes = mutableMapOf<Activity, Int>()

    fun initialize(application: Application) {
        application.registerActivityLifecycleCallbacks(object : SimpleActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
                check(activityBaseThemes.containsKey(activity)) {
                    "Activity must extend AppActivity: $activity"
                }
            }

            override fun onActivityDestroyed(activity: Activity) {
                activityBaseThemes.remove(activity)
            }
        })
    }

    fun apply(activity: Activity) {
        val baseThemeRes = activity.themeResIdCompat
        activityBaseThemes[activity] = baseThemeRes
        val customThemeRes = getCustomThemeRes(baseThemeRes, activity)
        activity.setThemeCompat(customThemeRes)
    }

    fun sync(): Set<Activity> {
        val changedActivities = mutableSetOf<Activity>()
        for ((activity, baseThemeRes) in activityBaseThemes) {
            if (activity.isFinishing) {
                continue
            }
            val currentThemeRes = activity.themeResIdCompat
            val customThemeRes = getCustomThemeRes(baseThemeRes, activity)
            if (currentThemeRes != customThemeRes) {
                changedActivities += activity
                if (activity is OnThemeChangedListener) {
                    activity.onThemeChanged(customThemeRes)
                } else {
                    activity.recreateCompat()
                }
            }
        }
        return changedActivities
    }

    private fun getCustomThemeRes(@StyleRes baseThemeRes: Int, context: Context): Int {
        val resources = context.resources
        val baseThemeName = resources.getResourceName(baseThemeRes)
        val defaultThemeName = resources.getResourceEntryName(R.style.Theme_MaterialFiles)
        val material3ThemeName =
            resources.getResourceEntryName(R.style.Theme_MaterialFiles_Material3)
        val customThemeName = baseThemeName.replace(defaultThemeName, material3ThemeName) +
            if (Settings.NIGHT_MODE.valueCompat == NightMode.BLACK) ".Black" else ""
        return resources.getIdentifier(customThemeName, null, null)
    }

    interface OnThemeChangedListener {
        fun onThemeChanged(@StyleRes theme: Int)
    }
}
