package me.zhanghai.android.files.app

import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import java.util.Locale

internal object AppLocaleHelper {
    private val appLocale = Locale.SIMPLIFIED_CHINESE

    fun apply(context: Context) {
        Locale.setDefault(appLocale)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // AppCompat cannot find LocaleManager before the first activity is created.
            val localeManager = context.getSystemService(LocaleManager::class.java)!!
            val locales = LocaleList(appLocale)
            if (localeManager.applicationLocales != locales) {
                localeManager.applicationLocales = locales
            }
        } else {
            AppCompatDelegate.setApplicationLocales(LocaleListCompat.create(appLocale))
        }
    }

    fun wrap(context: Context): Context {
        // Keep all other configuration changes, including night mode and font scale, inherited.
        val configuration = Configuration().apply {
            // Configuration() initializes this to 1 on Android 6 instead of leaving it unset.
            fontScale = 0f
            setLocale(appLocale)
        }
        return context.createConfigurationContext(configuration)
    }
}
