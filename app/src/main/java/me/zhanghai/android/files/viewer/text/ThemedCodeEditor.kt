/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.viewer.text

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.util.AttributeSet
import androidx.core.graphics.ColorUtils
import io.github.rosemoe.sora.widget.CodeEditor
import io.github.rosemoe.sora.widget.schemes.EditorColorScheme
import me.zhanghai.android.files.util.getColorByAttr
import com.google.android.material.R as MaterialR

// Sora draws across its full bounds and does not honor padding. Keeping it a plain View lets
// CoordinatorScrollingFrameLayout reserve bottom system insets with a margin instead of padding.
class ThemedCodeEditor @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : CodeEditor(context, attrs) {
    init {
        typefaceText = Typeface.MONOSPACE
        typefaceLineNumber = Typeface.MONOSPACE
        setTextSize(14f)
        isLineNumberEnabled = true
        isWordwrap = false
        val isDark = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
            Configuration.UI_MODE_NIGHT_YES
        // Each editor owns its scheme; the default Sora scheme is shared across editors.
        colorScheme = object : EditorColorScheme(isDark) {}
        applyThemeColors()
    }

    // Call after assigning a TextMateColorScheme: attaching it resets its default colors.
    // Only override UI colors here, preserving the grammar's token colors and font styles.
    fun applyThemeColors(scheme: EditorColorScheme = colorScheme) {
        val surface = context.getColorByAttr(MaterialR.attr.colorSurface)
        val onSurface = context.getColorByAttr(MaterialR.attr.colorOnSurface)
        val onSurfaceVariant = context.getColorByAttr(MaterialR.attr.colorOnSurfaceVariant)
        val outline = context.getColorByAttr(MaterialR.attr.colorOutlineVariant)
        val primary = context.getColorByAttr(androidx.appcompat.R.attr.colorPrimary)
        val primaryContainer = context.getColorByAttr(MaterialR.attr.colorPrimaryContainer)
        val onPrimaryContainer = context.getColorByAttr(MaterialR.attr.colorOnPrimaryContainer)
        val tertiary = context.getColorByAttr(MaterialR.attr.colorTertiary)
        val tertiaryContainer = context.getColorByAttr(MaterialR.attr.colorTertiaryContainer)
        val popupSurface = context.getColorByAttr(MaterialR.attr.colorSurfaceContainerHigh)
        val currentLine = ColorUtils.blendARGB(surface, onSurface, 0.06f)
        scheme.apply {
            setColor(EditorColorScheme.WHOLE_BACKGROUND, surface)
            setColor(EditorColorScheme.TEXT_NORMAL, onSurface)
            setColor(EditorColorScheme.LINE_NUMBER_BACKGROUND, surface)
            setColor(EditorColorScheme.LINE_NUMBER, onSurfaceVariant)
            setColor(EditorColorScheme.LINE_NUMBER_CURRENT, primary)
            setColor(EditorColorScheme.LINE_DIVIDER, outline)
            setColor(EditorColorScheme.CURRENT_LINE, currentLine)
            setColor(EditorColorScheme.SELECTION_INSERT, primary)
            setColor(EditorColorScheme.SELECTION_HANDLE, primary)
            setColor(EditorColorScheme.SELECTED_TEXT_BACKGROUND, primaryContainer)
            setColor(EditorColorScheme.TEXT_SELECTED, onPrimaryContainer)
            setColor(EditorColorScheme.UNDERLINE, primary)
            setColor(
                EditorColorScheme.SCROLL_BAR_THUMB,
                ColorUtils.setAlphaComponent(onSurfaceVariant, 0x80)
            )
            setColor(EditorColorScheme.SCROLL_BAR_THUMB_PRESSED, primary)
            setColor(EditorColorScheme.SCROLL_BAR_TRACK, Color.TRANSPARENT)
            setColor(EditorColorScheme.LINE_NUMBER_PANEL, primaryContainer)
            setColor(EditorColorScheme.LINE_NUMBER_PANEL_TEXT, onPrimaryContainer)
            setColor(EditorColorScheme.BLOCK_LINE, outline)
            setColor(EditorColorScheme.BLOCK_LINE_CURRENT, primary)
            setColor(EditorColorScheme.SIDE_BLOCK_LINE, primary)
            setColor(EditorColorScheme.STICKY_SCROLL_DIVIDER, outline)
            setColor(EditorColorScheme.NON_PRINTABLE_CHAR, onSurfaceVariant)
            setColor(EditorColorScheme.HARD_WRAP_MARKER, outline)
            setColor(EditorColorScheme.MATCHED_TEXT_BACKGROUND, tertiaryContainer)
            setColor(EditorColorScheme.MATCHED_TEXT_BORDER, tertiary)
            setColor(EditorColorScheme.HIGHLIGHTED_DELIMITERS_BACKGROUND, primaryContainer)
            setColor(EditorColorScheme.HIGHLIGHTED_DELIMITERS_FOREGROUND, onPrimaryContainer)
            setColor(EditorColorScheme.HIGHLIGHTED_DELIMITERS_BORDER, primary)
            setColor(EditorColorScheme.TEXT_ACTION_WINDOW_BACKGROUND, popupSurface)
            setColor(EditorColorScheme.TEXT_ACTION_WINDOW_ICON_COLOR, onSurface)
            setColor(EditorColorScheme.COMPLETION_WND_BACKGROUND, popupSurface)
            setColor(EditorColorScheme.COMPLETION_WND_CORNER, outline)
            setColor(EditorColorScheme.COMPLETION_WND_TEXT_PRIMARY, onSurface)
            setColor(EditorColorScheme.COMPLETION_WND_TEXT_SECONDARY, onSurfaceVariant)
            setColor(EditorColorScheme.COMPLETION_WND_TEXT_MATCHED, primary)
            setColor(EditorColorScheme.COMPLETION_WND_ITEM_CURRENT, currentLine)
            setColor(EditorColorScheme.SNIPPET_BACKGROUND_EDITING, primaryContainer)
            setColor(
                EditorColorScheme.SNIPPET_BACKGROUND_RELATED,
                ColorUtils.blendARGB(surface, primary, 0.12f)
            )
            setColor(EditorColorScheme.SNIPPET_BACKGROUND_INACTIVE, currentLine)
            setColor(EditorColorScheme.TEXT_INLAY_HINT_BACKGROUND, currentLine)
            setColor(EditorColorScheme.TEXT_INLAY_HINT_FOREGROUND, onSurfaceVariant)
            setColor(EditorColorScheme.FUNCTION_CHAR_BACKGROUND_STROKE, outline)
            setColor(EditorColorScheme.DIAGNOSTIC_TOOLTIP_BACKGROUND, popupSurface)
            setColor(EditorColorScheme.DIAGNOSTIC_TOOLTIP_BRIEF_MSG, onSurface)
            setColor(EditorColorScheme.DIAGNOSTIC_TOOLTIP_DETAILED_MSG, onSurfaceVariant)
            setColor(EditorColorScheme.DIAGNOSTIC_TOOLTIP_ACTION, primary)
            setColor(EditorColorScheme.STATIC_SPAN_BACKGROUND, surface)
            setColor(EditorColorScheme.STATIC_SPAN_FOREGROUND, onSurface)
        }
    }
}
