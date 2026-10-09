/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.coil

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import androidx.core.graphics.createBitmap
import androidx.core.graphics.drawable.toDrawable
import coil.ImageLoader
import coil.fetch.DrawableResult
import coil.fetch.FetchResult
import coil.fetch.Fetcher
import coil.request.Options
import java.io.IOException
import java8.nio.file.Path
import me.zhanghai.android.files.R
import me.zhanghai.android.files.util.getDimensionPixelSize
import me.zhanghai.android.files.util.getPackageArchiveInfoCompat
import kotlin.math.min
import kotlin.math.roundToInt

class ApkIconFetcher(
    private val path: Path,
    private val options: Options,
    private val iconSize: Int
) : Fetcher {
    override suspend fun fetch(): FetchResult {
        val packageManager = options.context.packageManager
        val (packageInfo, closeable) = packageManager.getPackageArchiveInfoCompat(path, 0)
        val icon = closeable.use {
            val applicationInfo = packageInfo?.applicationInfo
                ?: throw IOException("ApplicationInfo is null")
            renderCircularIcon(applicationInfo.loadUnbadgedIcon(packageManager).mutate())
        }
        return DrawableResult(icon.toDrawable(options.context.resources), false, path.dataSource)
    }

    private fun renderCircularIcon(drawable: Drawable): Bitmap {
        val layers = createBitmap(iconSize, iconSize)
        val canvas = Canvas(layers)
        // Give transparent legacy icons a circular backing, as adaptive icon wrappers do.
        canvas.drawColor(Color.WHITE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && drawable is AdaptiveIconDrawable) {
            // Setting the adaptive icon bounds also sets the documented extra inset on each
            // layer. Draw those layers directly so the device's icon mask is not baked in.
            drawable.setBounds(0, 0, iconSize, iconSize)
            drawable.background?.draw(canvas)
            drawable.foreground?.draw(canvas)
        } else {
            val width = drawable.intrinsicWidth.takeIf { it > 0 } ?: iconSize
            val height = drawable.intrinsicHeight.takeIf { it > 0 } ?: iconSize
            val scale = min(iconSize.toFloat() / width, iconSize.toFloat() / height)
            val scaledWidth = (width * scale).roundToInt()
            val scaledHeight = (height * scale).roundToInt()
            val left = (iconSize - scaledWidth) / 2
            val top = (iconSize - scaledHeight) / 2
            drawable.setBounds(left, top, left + scaledWidth, top + scaledHeight)
            drawable.draw(canvas)
        }
        val icon = createBitmap(iconSize, iconSize)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
            shader = BitmapShader(layers, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        }
        val radius = iconSize / 2f
        Canvas(icon).drawCircle(radius, radius, radius, paint)
        return icon
    }

    class Factory(context: Context) : Fetcher.Factory<Path> {
        private val iconSize = context.getDimensionPixelSize(R.dimen.large_icon_size)

        override fun create(data: Path, options: Options, imageLoader: ImageLoader): Fetcher =
            ApkIconFetcher(data, options, iconSize)
    }
}
