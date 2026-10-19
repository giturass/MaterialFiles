/*
 * Copyright (c) 2020 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.compat

import android.text.method.DigitsKeyListener
import java.util.Locale

object DigitsKeyListenerCompat {
    fun getInstance(locale: Locale?, sign: Boolean, decimal: Boolean): DigitsKeyListener =
        DigitsKeyListener.getInstance(locale, sign, decimal)
}
