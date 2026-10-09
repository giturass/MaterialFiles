package me.zhanghai.android.files.app

import android.app.Application
import android.content.Context
import android.content.res.Configuration

class AppApplication : Application() {
    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(AppLocaleHelper.wrap(base))
        // Providers initialize notification channels before Application.onCreate().
        AppLocaleHelper.apply(this)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        AppLocaleHelper.apply(this)
        super.onConfigurationChanged(newConfig)
    }
}
