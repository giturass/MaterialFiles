/*
 * Copyright (c) 2018 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.compat

import android.icu.text.ListFormatter

object ListFormatterCompat {
    fun format(vararg items: Any?): String = ListFormatter.getInstance().format(*items)

    fun format(items: Collection<*>): String = ListFormatter.getInstance().format(items)
}
