/*
 * Copyright (c) 2020 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.coil

import coil.Coil
import coil.ImageLoader
import coil.decode.ImageDecoderDecoder
import coil.decode.SvgDecoder
import me.zhanghai.android.files.app.application

fun initializeCoil() {
    Coil.setImageLoader(
        ImageLoader.Builder(application)
            .components {
                add(AppIconApplicationInfoKeyer())
                add(AppIconApplicationInfoFetcherFactory(application))
                add(AppIconPackageNameKeyer())
                add(AppIconPackageNameFetcherFactory(application))
                add(PathAttributesKeyer())
                add(PathAttributesFetcher.Factory(application))
                add(ImageDecoderDecoder.Factory())
                add(SvgDecoder.Factory(false))
            }
            .build()
    )
}
