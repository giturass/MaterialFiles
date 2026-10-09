/*
 * Copyright (c) 2018 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.theme.night

import android.app.Activity
import android.app.Application
import android.content.res.Configuration
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.appcompat.app.AppCompatDelegateCompat
import me.zhanghai.android.files.app.application
import me.zhanghai.android.files.settings.Settings
import me.zhanghai.android.files.theme.custom.CustomThemeHelper
import me.zhanghai.android.files.util.SimpleActivityLifecycleCallbacks
import me.zhanghai.android.files.util.valueCompat

// We take over the activity creation when setting the default night mode from AppCompat so that:
// 1. We can recreate all activities upon change, instead of only started activities.
// 2. We can have custom handling of the change, instead of being forced to either recreate or
//    update resources configuration which is shared among activities.
object NightModeHelper {
    private val activities = mutableSetOf<AppCompatActivity>()

    fun initialize(application: Application) {
        application.registerActivityLifecycleCallbacks(object : SimpleActivityLifecycleCallbacks {
                override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
                    check(activity in activities) { "Activity must extend AppActivity: $activity" }
                }

                override fun onActivityDestroyed(activity: Activity) {
                    activities -= activity as AppCompatActivity
                }
            })
    }

    fun apply(activity: AppCompatActivity) {
        activities += activity
        activity.delegate.localNightMode = nightMode
    }

    fun sync() {
        // A theme change already recreates the activity with its new night mode. Avoid scheduling
        // a second recreation when entering or leaving the black theme.
        val themeChangedActivities = CustomThemeHelper.sync()
        val nightMode = nightMode
        for (activity in activities) {
            if (activity.isFinishing || activity in themeChangedActivities) {
                continue
            }
            if (activity is OnNightModeChangedListener
                && getUiModeNight(activity.delegate.localNightMode, activity)
                    != getUiModeNight(nightMode, activity)) {
                activity.onNightModeChangedFromHelper(nightMode)
            } else {
                activity.delegate.localNightMode = nightMode
            }
        }
    }

    private val nightMode: Int
        get() = Settings.NIGHT_MODE.valueCompat.value

    /*
     * @see androidx.appcompat.app.AppCompatDelegateImpl#updateForNightMode(int, boolean)
     */
    private fun getUiModeNight(nightMode: Int, activity: AppCompatActivity): Int =
        when (AppCompatDelegateCompat.mapNightMode(activity.delegate, application, nightMode)) {
            AppCompatDelegate.MODE_NIGHT_YES -> Configuration.UI_MODE_NIGHT_YES
            AppCompatDelegate.MODE_NIGHT_NO -> Configuration.UI_MODE_NIGHT_NO
            else ->
                (activity.applicationContext.resources.configuration.uiMode
                    and Configuration.UI_MODE_NIGHT_MASK)
        }

    fun isInNightMode(activity: AppCompatActivity): Boolean =
        (getUiModeNight(activity.delegate.localNightMode, activity)
            == Configuration.UI_MODE_NIGHT_YES)

    interface OnNightModeChangedListener {
        fun onNightModeChangedFromHelper(nightMode: Int)
    }
}
