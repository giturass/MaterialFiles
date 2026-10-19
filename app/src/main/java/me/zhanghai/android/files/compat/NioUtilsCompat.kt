/*
 * Copyright (c) 2018 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.compat

import me.zhanghai.android.files.hiddenapi.RestrictedHiddenApi
import me.zhanghai.android.files.util.lazyReflectedMethod
import java.io.Closeable
import java.io.FileDescriptor
import java.nio.channels.FileChannel

object NioUtilsCompat {
    @RestrictedHiddenApi
    private val newFileChannelMethod by lazyReflectedMethod(
        "java.nio.NioUtils", "newFileChannel", Closeable::class.java, FileDescriptor::class.java,
        Int::class.java
    )

    fun newFileChannel(ioObject: Closeable, fd: FileDescriptor, flags: Int): FileChannel =
        newFileChannelMethod.invoke(null, ioObject, fd, flags) as FileChannel
}
