/*
 * Copyright (c) 2018 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.compat

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.storage.StorageVolume
import me.zhanghai.android.files.util.lazyReflectedMethod
import java.io.File

// Work around @SuppressLint not applicable to top level property with delegate.
@SuppressLint("NewApi")
private val storageVolumeClass = StorageVolume::class.java

private val getPathMethod by lazyReflectedMethod(storageVolumeClass, "getPath")

val StorageVolume.pathCompat: String
    get() = getPathMethod.invoke(this) as String

val StorageVolume.pathFileCompat: File
    get() = File(pathCompat)

val StorageVolume.directoryCompat: File?
    get() = directory

@SuppressLint("NewApi")
fun StorageVolume.getDescriptionCompat(context: Context): String = getDescription(context)

val StorageVolume.isPrimaryCompat: Boolean
    @SuppressLint("NewApi")
    get() = isPrimary

val StorageVolume.isRemovableCompat: Boolean
    @SuppressLint("NewApi")
    get() = isRemovable

val StorageVolume.isEmulatedCompat: Boolean
    @SuppressLint("NewApi")
    get() = isEmulated

val StorageVolume.uuidCompat: String?
    @SuppressLint("NewApi")
    get() = uuid

val StorageVolume.stateCompat: String
    @SuppressLint("NewApi")
    get() = state

fun StorageVolume.createOpenDocumentTreeIntentCompat(): Intent = createOpenDocumentTreeIntent()
