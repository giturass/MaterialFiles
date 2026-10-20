package me.zhanghai.android.files.httpserver

internal data class HttpByteRange(val start: Long, val end: Long) {
    val length: Long
        get() = end - start + 1

    companion object {
        /** Null means serve the complete file. Multipart ranges are deliberately ignored. */
        fun parse(header: String?, size: Long): HttpByteRange? {
            require(size >= 0)
            if (header == null || !header.startsWith("bytes=") || ',' in header) {
                return null
            }
            val match = Regex("bytes=(\\d*)-(\\d*)").matchEntire(header.trim())
                ?: throw IllegalArgumentException("Invalid range")
            val (first, last) = match.destructured
            require(size > 0)
            if (first.isEmpty()) {
                val suffix = last.toLongOrNull() ?: throw IllegalArgumentException("Invalid suffix")
                require(suffix > 0)
                return HttpByteRange(maxOf(0, size - suffix), size - 1)
            }
            val start = first.toLongOrNull() ?: throw IllegalArgumentException("Invalid start")
            val end = if (last.isEmpty()) size - 1 else {
                last.toLongOrNull() ?: throw IllegalArgumentException("Invalid end")
            }
            require(start < size && end >= start)
            return HttpByteRange(start, minOf(end, size - 1))
        }
    }
}
