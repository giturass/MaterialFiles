/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.ui

import android.content.Context
import android.util.AttributeSet
import android.view.View
import androidx.annotation.AttrRes
import androidx.annotation.StyleRes
import com.google.android.material.shape.MaterialShapeDrawable
import com.google.android.material.shape.RelativeCornerSize
import com.google.android.material.shape.ShapeAppearanceModel
import me.zhanghai.android.files.R

class DirectoryIconBackgroundView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    @AttrRes defStyleAttr: Int = 0,
    @StyleRes defStyleRes: Int = 0
) : View(context, attrs, defStyleAttr, defStyleRes) {
    init {
        background = MaterialShapeDrawable().apply {
            shapeAppearanceModel = ShapeAppearanceModel.builder()
                .setAllCornerSizes(RelativeCornerSize(0.5f))
                .build()
            fillColor = context.getColorStateList(R.color.directory_icon_background)
            elevation = resources.getDimension(R.dimen.directory_icon_background_elevation)
            setShadowColor(context.getColor(R.color.directory_icon_shadow))
            // Keep the shadow in this layer, below the folder icon and its badges.
            shadowCompatibilityMode = MaterialShapeDrawable.SHADOW_COMPAT_MODE_ALWAYS
        }
    }
}
