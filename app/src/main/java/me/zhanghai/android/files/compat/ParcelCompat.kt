/*
 * Copyright (c) 2020 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.compat

import android.os.Parcel
import android.os.Parcelable
import androidx.core.os.ParcelCompat

fun Parcel.readBooleanCompat(): Boolean = ParcelCompat.readBoolean(this)

fun Parcel.writeBooleanCompat(value: Boolean) {
    ParcelCompat.writeBoolean(this, value)
}

fun <E : Parcelable?, L : MutableList<E>> Parcel.readParcelableListCompat(
    list: L,
    classLoader: ClassLoader?
): L {
    @Suppress("UNCHECKED_CAST")
    return readParcelableList(list, classLoader) as L
}

fun <T : Parcelable?> Parcel.writeParcelableListCompat(value: List<T>?, flags: Int) {
    writeParcelableList(value, flags)
}

@Suppress("UNCHECKED_CAST")
fun <T> Parcel.readSerializableCompat(): T? = readSerializable() as T?
