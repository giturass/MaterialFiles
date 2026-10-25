package me.zhanghai.android.files.media

import android.content.Context

class MediaPreferences(context: Context) {
    private val preferences = context.getSharedPreferences("media_player", Context.MODE_PRIVATE)
    var decoderMode: String
        get() = preferences.getString("decoder", "auto")!!
        set(value) { preferences.edit().putString("decoder", value).apply() }
    var backgroundVideo: Boolean
        get() = preferences.getBoolean("background_video", false)
        set(value) { preferences.edit().putBoolean("background_video", value).apply() }
    var aspectMode: Int
        get() = preferences.getInt("aspect", 0)
        set(value) { preferences.edit().putInt("aspect", value).apply() }
    var speed: Float
        get() = preferences.getFloat("speed", 1f)
        set(value) { preferences.edit().putFloat("speed", value).apply() }
    var repeatMode: Int
        get() = preferences.getInt("repeat", 0)
        set(value) { preferences.edit().putInt("repeat", value).apply() }
}
