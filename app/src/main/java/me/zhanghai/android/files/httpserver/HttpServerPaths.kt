package me.zhanghai.android.files.httpserver

import java.net.URLEncoder

internal object HttpServerPaths {
    fun segments(uri: String): List<String> {
        require(uri.startsWith('/'))
        val segments = uri.removePrefix("/").split('/').filter { it.isNotEmpty() }
        require(segments.none { segment ->
            segment == "." || segment == ".." || '\\' in segment || segment.any { it <= '\u001F' }
        })
        return segments
    }

    fun url(segments: List<String>, directory: Boolean): String =
        "/" + segments.joinToString("/") { encode(it) } +
            if (directory && segments.isNotEmpty()) "/" else ""

    fun encode(value: String): String =
        URLEncoder.encode(value, "UTF-8").replace("+", "%20").replace("*", "%2A")
}
