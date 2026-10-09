/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.viewer.text

import android.content.Context
import android.content.res.AssetManager
import androidx.annotation.WorkerThread
import io.github.rosemoe.sora.lang.EmptyLanguage
import io.github.rosemoe.sora.lang.Language
import io.github.rosemoe.sora.langs.textmate.TextMateColorScheme
import io.github.rosemoe.sora.langs.textmate.TextMateLanguage
import io.github.rosemoe.sora.langs.textmate.registry.FileProviderRegistry
import io.github.rosemoe.sora.langs.textmate.registry.GrammarRegistry
import io.github.rosemoe.sora.langs.textmate.registry.ThemeRegistry
import io.github.rosemoe.sora.langs.textmate.registry.model.ThemeModel
import io.github.rosemoe.sora.langs.textmate.registry.provider.AssetsFileResolver
import java.util.Locale
import org.eclipse.tm4e.core.registry.IThemeSource

/** Offline TextMate grammars and language configuration for Sora. */
object SoraEditorLanguages {
    data class Entry(
        val id: String,
        val displayName: String,
        val scope: String?,
        val extensions: Set<String>
    )

    val languages = listOf(
        Entry("plain", "纯文本", null, setOf("txt", "text", "log")),
        Entry("java", "Java", "source.java", setOf("java")),
        Entry("kotlin", "Kotlin", "source.kotlin", setOf("kt", "kts")),
        Entry("javascript", "JavaScript", "source.js", setOf("js", "jsx", "mjs", "cjs")),
        Entry("typescript", "TypeScript", "source.ts", setOf("ts", "tsx", "mts", "cts")),
        Entry("json", "JSON", "source.json.comments", setOf("json", "jsonc", "ipynb", "webmanifest")),
        Entry("xml", "XML", "text.xml", setOf("xml", "svg", "xsl", "xslt", "xsd", "plist", "pom")),
        Entry("html", "HTML", "text.html.basic", setOf("html", "htm", "xhtml")),
        Entry("css", "CSS", "source.css", setOf("css")),
        Entry("markdown", "Markdown", "text.html.markdown", setOf("md", "markdown", "mdown", "mkd")),
        Entry("python", "Python", "source.python", setOf("py", "pyw", "pyi", "pyx")),
        Entry("shell", "Shell", "source.shell", setOf("sh", "bash", "zsh", "ksh", "bashrc", "zshrc")),
        Entry("yaml", "YAML", "source.yaml", setOf("yaml", "yml")),
        Entry("c", "C", "source.c", setOf("c")),
        Entry("cpp", "C++", "source.cpp", setOf("cpp", "cc", "cxx", "h", "hh", "hpp", "hxx", "ino"))
    )

    private val languagesById = languages.associateBy { it.id }
    private val languagesByExtension = languages.flatMap { entry ->
        entry.extensions.map { it to entry }
    }.toMap()

    fun forId(id: String): Entry = languagesById[id] ?: languages.first()

    fun detect(fileName: String): Entry {
        val name = fileName.substringAfterLast('/').substringAfterLast('\\')
        // Unix C++ source and headers can use an uppercase suffix.
        if (name.endsWith(".C") || name.endsWith(".H")) {
            return forId("cpp")
        }
        val lowerName = name.lowercase(Locale.ROOT)
        val namedLanguage = when (lowerName) {
            ".bashrc", ".bash_profile", ".bash_login", ".bash_logout", ".profile",
            ".zshrc", ".zprofile", ".zshenv", ".zlogin", ".zlogout", "gradlew", "configure" -> "shell"
            ".eslintrc", ".babelrc", ".prettierrc", ".jshintrc", ".stylelintrc",
            "composer.lock", "package-lock.json", "manifest.webmanifest" -> "json"
            "pom.xml", "androidmanifest.xml" -> "xml"
            else -> null
        }
        if (namedLanguage != null) {
            return forId(namedLanguage)
        }
        val extension = lowerName.substringAfterLast('.', "")
        val entry = languagesByExtension[extension] ?: forId("plain")
        return if (extension == "tsx") entry.copy(scope = "source.tsx") else entry
    }

    private lateinit var assets: AssetManager
    private val resourcesByDark = mutableMapOf<Boolean, ThemeResources>()

    /** Retains only application resources. Parsing is deferred until a language or theme is needed. */
    @WorkerThread
    @Synchronized
    fun initialize(context: Context) {
        if (::assets.isInitialized) {
            return
        }
        assets = context.applicationContext.assets
        FileProviderRegistry.getInstance().addFileProvider(AssetsFileResolver(assets))
    }

    /** The caller owns the result and its analyzer, even when cancellation prevents attaching it. */
    @WorkerThread
    @Synchronized
    fun createLanguage(entry: Entry, dark: Boolean): Language {
        val scope = entry.scope ?: return EmptyLanguage()
        val resources = getResources(dark)
        return TextMateLanguage.create(
            scope, resources.grammars, resources.themes, true
        ).apply {
            tabSize = 4
            useTab(false)
            isAutoCompleteEnabled = true
            keywords[entry.id]?.let { setCompleterKeywords(it.split(' ').toTypedArray()) }
        }
    }

    /** Assign the scheme before applying the editor's Material surface and popup colors. */
    @WorkerThread
    @Synchronized
    fun createColorScheme(dark: Boolean): TextMateColorScheme {
        // The two-argument overload is intentional: Sora 0.24.6's one-argument overload uses
        // the global ThemeRegistry. A private registry also prevents opening another editor
        // from resetting the Material colors on an already attached scheme.
        return TextMateColorScheme.create(ThemeRegistry(), getResources(dark).theme)
    }

    private fun getResources(dark: Boolean): ThemeResources {
        check(::assets.isInitialized) { "Initialize SoraEditorLanguages with an application context" }
        return resourcesByDark.getOrPut(dark) {
            val path = if (dark) "sora/themes/dark.json" else "sora/themes/light.json"
            val theme = assets.open(path).use { stream ->
                ThemeModel(IThemeSource.fromInputStream(stream, path, Charsets.UTF_8)).apply {
                    isDark = dark
                    load()
                }
            }
            // Each brightness has an immutable theme and its own grammar color map. These
            // registries never broadcast a theme change to another editor or activity.
            val themes = ThemeRegistry().apply { loadTheme(theme) }
            val grammars = GrammarRegistry(null).apply {
                loadGrammars("sora/languages.json")
                setTheme(theme)
            }
            ThemeResources(theme, themes, grammars)
        }
    }

    private class ThemeResources(
        val theme: ThemeModel,
        val themes: ThemeRegistry,
        val grammars: GrammarRegistry
    )

    // TextMate supplies syntax, indentation and bracket rules. Sora completes these keywords
    // and identifiers collected from the document; this is deliberately independent of LSP.
    private val keywords = mapOf(
        "java" to "abstract assert boolean break byte case catch char class const continue " +
            "default do double else enum extends final finally float for if implements import " +
            "instanceof int interface long native new package private protected public record " +
            "return sealed short static strictfp super switch synchronized this throw throws " +
            "transient try var void volatile while yield true false null",
        "kotlin" to "abstract actual annotation as break by catch class companion const constructor " +
            "continue crossinline data delegate do dynamic else enum expect external false field " +
            "file final finally for fun get if import in infix init inline inner interface internal " +
            "is lateinit noinline null object open operator out override package param private " +
            "property protected public receiver reified return sealed set setparam super suspend " +
            "tailrec this throw true try typealias typeof val value var vararg when where while",
        "javascript" to "async await break case catch class const continue debugger default delete " +
            "do else export extends false finally for from function get if import in instanceof " +
            "let new null of return set static super switch this throw true try typeof undefined " +
            "var void while with yield",
        "typescript" to "abstract any as asserts async await bigint boolean break case catch class " +
            "const constructor continue declare default delete do else enum export extends false " +
            "finally for from function get if implements import in infer instanceof interface " +
            "is keyof let module namespace never new null number object of override private " +
            "protected public readonly require return satisfies set static string super switch " +
            "symbol this throw true try type typeof undefined unique unknown var void while yield",
        "json" to "true false null",
        "python" to "False None True and as assert async await break case class continue def del " +
            "elif else except finally for from global if import in is lambda match nonlocal not " +
            "or pass raise return try while with yield",
        "shell" to "break case continue coproc do done elif else esac export fi for function if " +
            "in local readonly return select then time until while",
        "yaml" to "true false null",
        "c" to "auto break case char const continue default do double else enum extern float for " +
            "goto if inline int long register restrict return short signed sizeof static struct " +
            "switch typedef union unsigned void volatile while _Alignas _Alignof _Atomic _Bool " +
            "_Complex _Generic _Imaginary _Noreturn _Static_assert _Thread_local",
        "cpp" to "alignas alignof and and_eq asm auto bitand bitor bool break case catch char " +
            "char8_t char16_t char32_t class compl concept const consteval constexpr constinit " +
            "const_cast continue co_await co_return co_yield decltype default delete do double " +
            "dynamic_cast else enum explicit export extern false float for friend goto if inline " +
            "int long mutable namespace new noexcept not not_eq nullptr operator or or_eq private " +
            "protected public register reinterpret_cast requires return short signed sizeof static " +
            "static_assert static_cast struct switch template this thread_local throw true try " +
            "typedef typeid typename union unsigned using virtual void volatile wchar_t while xor xor_eq"
    )
}
