/*
 * Copyright (c) 2019 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.app

import android.content.Context
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import me.zhanghai.android.files.filejob.OpenCacheManager
import me.zhanghai.android.files.theme.custom.CustomThemeHelper
import me.zhanghai.android.files.theme.night.NightModeHelper
import me.zhanghai.android.files.util.extraPathList
import me.zhanghai.android.files.util.saveAsPath
import java.io.Closeable

abstract class AppActivity : AppCompatActivity() {
    private var isDelegateCreated = false
    private val openCacheLeases = mutableListOf<Closeable>()

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLocaleHelper.wrap(newBase))
    }

    override fun getDelegate(): AppCompatDelegate {
        val delegate = super.getDelegate()

        if (!isDelegateCreated) {
            isDelegateCreated = true
            NightModeHelper.apply(this)
        }
        return delegate
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        CustomThemeHelper.apply(this)

        super.onCreate(savedInstanceState)

        // Internal viewers read paths directly and may reopen them after loading/rotation.
        val paths = try {
            (intent.extraPathList + listOfNotNull(intent.saveAsPath)).distinct()
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
        for (path in paths) {
            try {
                OpenCacheManager.acquire(path)?.let { openCacheLeases += it }
            } catch (e: Exception) {
                // Let the viewer report a missing/unavailable file through its normal UI.
                e.printStackTrace()
            }
        }
    }

    override fun onDestroy() {
        openCacheLeases.forEach { it.close() }
        openCacheLeases.clear()
        super.onDestroy()
    }

    override fun onSupportNavigateUp(): Boolean {
        if (!super.onSupportNavigateUp()) {
            finish()
        }
        return true
    }
}
