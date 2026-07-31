/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.viewer.text

import android.content.Context
import android.content.res.Configuration
import androidx.annotation.StringRes
import androidx.core.graphics.ColorUtils
import io.github.rosemoe.sora.lang.EmptyLanguage
import io.github.rosemoe.sora.langs.textmate.TextMateColorScheme
import io.github.rosemoe.sora.langs.textmate.TextMateLanguage
import io.github.rosemoe.sora.langs.textmate.registry.FileProviderRegistry
import io.github.rosemoe.sora.langs.textmate.registry.GrammarRegistry
import io.github.rosemoe.sora.langs.textmate.registry.ThemeRegistry
import io.github.rosemoe.sora.langs.textmate.registry.model.ThemeModel
import io.github.rosemoe.sora.langs.textmate.registry.provider.AssetsFileResolver
import io.github.rosemoe.sora.widget.CodeEditor
import io.github.rosemoe.sora.widget.schemes.EditorColorScheme
import me.zhanghai.android.files.R
import me.zhanghai.android.files.util.getColorByAttr
import org.eclipse.tm4e.core.registry.IThemeSource

/** Language definitions shared by the file-name detector and the manual language picker. */
object TextEditorLanguage {
    data class Definition(
        val id: String,
        @StringRes val nameRes: Int,
        val scopeName: String?,
        val extensions: Set<String>
    )

    val definitions: List<Definition> = listOf(
        Definition("plain", R.string.text_editor_plain_text, null, emptySet()),
        Definition("java", R.string.text_editor_language_java, "source.java", setOf("java")),
        Definition("kotlin", R.string.text_editor_language_kotlin, "source.kotlin", setOf("kt", "kts")),
        Definition("python", R.string.text_editor_language_python, "source.python", setOf("py", "pyw")),
        Definition("xml", R.string.text_editor_language_xml, "text.xml", setOf("xml", "xhtml", "svg")),
        Definition("html", R.string.text_editor_language_html, "text.html.basic", setOf("html", "htm")),
        Definition(
            "javascript", R.string.text_editor_language_javascript, "source.js",
            setOf("js", "jsx", "mjs", "cjs")
        ),
        // The JavaScript TextMate grammar also provides useful JSON highlighting.
        Definition("json", R.string.text_editor_language_json, "source.js", setOf("json")),
        Definition(
            "markdown", R.string.text_editor_language_markdown, "text.html.markdown",
            setOf("md", "markdown")
        ),
        Definition(
            "shell", R.string.text_editor_language_shell, "source.shell",
            setOf("sh", "bash", "zsh", "ksh")
        ),
        Definition("lua", R.string.text_editor_language_lua, "source.lua", setOf("lua"))
    )

    private val lock = Any()
    private var initialized = false

    fun detect(fileName: String): Definition {
        val extension = fileName.substringAfterLast('.', "").lowercase()
        return definitions.firstOrNull { extension in it.extensions } ?: definitions.first()
    }

    fun find(id: String?): Definition =
        definitions.firstOrNull { it.id == id } ?: definitions.first()

    fun configure(
        editor: CodeEditor,
        fileName: String,
        context: Context,
        languageId: String? = null
    ): Definition {
        val definition = if (languageId == null) detect(fileName) else find(languageId)
        val colorScheme = if (definition.scopeName == null) {
            EditorColorScheme()
        } else {
            try {
                synchronized(lock) {
                    ensureInitialized(context.applicationContext)
                    val isNight =
                        (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                            Configuration.UI_MODE_NIGHT_YES
                    val themeName = if (isNight) THEME_DARK else THEME_LIGHT
                    val themeRegistry = ThemeRegistry.getInstance()
                    themeRegistry.setTheme(themeName)
                    val theme = themeRegistry.currentThemeModel
                    TextMateColorScheme.create(themeRegistry, theme).apply { setTheme(theme) }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                EditorColorScheme()
            }
        }
        applyMaterialColors(colorScheme, context)
        editor.colorScheme = colorScheme
        val languageApplied = applyLanguage(editor, definition)
        // TextMate theme changes can reset generic editor colors. Reapply them after the language
        // is installed; plain text deliberately uses a non-TextMate scheme without that listener.
        applyMaterialColors(colorScheme, context)
        return if (languageApplied) definition else definitions.first()
    }

    fun applyLanguage(editor: CodeEditor, definition: Definition): Boolean {
        try {
            editor.setEditorLanguage(
                if (definition.scopeName == null) {
                    EmptyLanguage()
                } else {
                    TextMateLanguage.create(definition.scopeName, false)
                }
            )
            return true
        } catch (e: Exception) {
            // A missing optional grammar must never make the editor unusable. Plain text is a
            // predictable fallback and the status bar still reports the user's selected language.
            e.printStackTrace()
            editor.setEditorLanguage(EmptyLanguage())
            return false
        }
    }

    private fun ensureInitialized(context: Context) {
        if (initialized) {
            return
        }
        FileProviderRegistry.getInstance().addFileProvider(AssetsFileResolver(context.assets))
        val themeRegistry = ThemeRegistry.getInstance()
        loadTheme(themeRegistry, THEME_LIGHT, false)
        loadTheme(themeRegistry, THEME_DARK, true)
        themeRegistry.setTheme(THEME_LIGHT)
        GrammarRegistry.getInstance().loadGrammars(LANGUAGES_PATH)
        initialized = true
    }

    private fun loadTheme(themeRegistry: ThemeRegistry, name: String, isDark: Boolean) {
        val path = "textmate/$name.json"
        val source = IThemeSource.fromInputStream(
            FileProviderRegistry.getInstance().tryGetInputStream(path), path, null
        )
        themeRegistry.loadTheme(ThemeModel(source, name).apply { setDark(isDark) }, false)
    }

    private fun applyMaterialColors(colorScheme: EditorColorScheme, context: Context) {
        fun color(attr: Int): Int = context.getColorByAttr(attr)
        val surface = color(com.google.android.material.R.attr.colorSurface)
        val surfaceContainer = color(com.google.android.material.R.attr.colorSurfaceContainer)
        val primary = color(androidx.appcompat.R.attr.colorPrimary)
        val primaryContainer = color(com.google.android.material.R.attr.colorPrimaryContainer)
        val tertiaryContainer = color(com.google.android.material.R.attr.colorTertiaryContainer)
        val outline = color(com.google.android.material.R.attr.colorOutline)
        val outlineVariant = color(com.google.android.material.R.attr.colorOutlineVariant)
        colorScheme.setColor(EditorColorScheme.WHOLE_BACKGROUND, surface)
        colorScheme.setColor(EditorColorScheme.LINE_NUMBER_BACKGROUND, surfaceContainer)
        colorScheme.setColor(
            EditorColorScheme.TEXT_NORMAL,
            color(com.google.android.material.R.attr.colorOnSurface)
        )
        colorScheme.setColor(EditorColorScheme.LINE_NUMBER, outline)
        colorScheme.setColor(EditorColorScheme.LINE_NUMBER_CURRENT, primary)
        colorScheme.setColor(EditorColorScheme.LINE_DIVIDER, outlineVariant)
        colorScheme.setColor(EditorColorScheme.BLOCK_LINE, outlineVariant)
        colorScheme.setColor(EditorColorScheme.BLOCK_LINE_CURRENT, primary)
        colorScheme.setColor(EditorColorScheme.SELECTION_INSERT, primary)
        colorScheme.setColor(EditorColorScheme.SELECTION_HANDLE, primary)
        colorScheme.setColor(
            EditorColorScheme.SELECTED_TEXT_BACKGROUND,
            ColorUtils.setAlphaComponent(primaryContainer, 0xB3)
        )
        colorScheme.setColor(
            EditorColorScheme.MATCHED_TEXT_BACKGROUND,
            ColorUtils.setAlphaComponent(tertiaryContainer, 0xCC)
        )
        colorScheme.setColor(EditorColorScheme.CURRENT_LINE, surfaceContainer)
        colorScheme.setColor(EditorColorScheme.SCROLL_BAR_THUMB, outline)
        colorScheme.setColor(EditorColorScheme.SCROLL_BAR_THUMB_PRESSED, primary)
    }

    private const val LANGUAGES_PATH = "textmate/languages.json"
    private const val THEME_LIGHT = "quietlight"
    private const val THEME_DARK = "darcula"
}
