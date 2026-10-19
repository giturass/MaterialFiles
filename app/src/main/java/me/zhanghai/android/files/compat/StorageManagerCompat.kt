/*
 * Copyright (c) 2018 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.compat

import android.os.Handler
import android.os.ParcelFileDescriptor
import android.os.storage.StorageManager
import android.os.storage.StorageVolume
import java.io.IOException

val StorageManager.storageVolumesCompat: List<StorageVolume>
    get() = storageVolumes

@Throws(IOException::class)
fun StorageManager.openProxyFileDescriptorCompat(
    mode: Int,
    callback: ProxyFileDescriptorCallbackCompat,
    handler: Handler
): ParcelFileDescriptor =
    openProxyFileDescriptor(mode, callback.toProxyFileDescriptorCallback(), handler)
