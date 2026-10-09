# Sora TextMate languages and asset provenance

The editor uses Sora Editor **0.24.6**. Its TextMate implementation supplies
incremental syntax highlighting, language-specific brackets and indentation,
and identifier completion. The app additionally supplies language keywords to
Sora's identifier completer. These features run offline; no language server,
semantic diagnostics, compiler, or code formatter is implied by a TextMate
grammar.

`SoraEditorLanguages` keeps the language choices and file-name detection in one
place. Unknown extensions use Sora's `EmptyLanguage`. Automatic detection also
recognizes common shell dotfiles, `gradlew`, JSON configuration dotfiles, and
uppercase Unix C++ extensions. The manual language choice overrides detection.
TSX files use the separate `source.tsx` grammar in automatic mode. The JSON
choice accepts comments; a strict JSON grammar is also registered for embedded
JSON in other languages.

| Choice | Root scope | Common extensions |
| --- | --- | --- |
| Java | `source.java` | `.java` |
| Kotlin | `source.kotlin` | `.kt`, `.kts` |
| JavaScript | `source.js` | `.js`, `.jsx`, `.mjs`, `.cjs` |
| TypeScript | `source.ts`; `source.tsx` for TSX | `.ts`, `.tsx`, `.mts`, `.cts` |
| JSON | `source.json.comments` | `.json`, `.jsonc`, `.ipynb`, `.webmanifest` |
| XML | `text.xml` | `.xml`, `.svg`, `.xsl`, `.xsd`, `.plist`, `.pom` |
| HTML | `text.html.basic` | `.html`, `.htm`, `.xhtml` |
| CSS | `source.css` | `.css` |
| Markdown | `text.html.markdown` | `.md`, `.markdown`, `.mdown`, `.mkd` |
| Python | `source.python` | `.py`, `.pyw`, `.pyi`, `.pyx` |
| Shell | `source.shell` | `.sh`, `.bash`, `.zsh`, `.ksh` |
| YAML | `source.yaml` | `.yaml`, `.yml` |
| C | `source.c` | `.c` |
| C++ | `source.cpp` | `.cpp`, `.cc`, `.cxx`, `.h`, `.hpp`, `.ino` |

The grammar manifest also registers C++ macros and the HTML derivative grammar
used inside Markdown. Embedded JavaScript/CSS in HTML and supported Markdown
code fences use their registered grammars. Optional foreign-language scopes
referenced by upstream grammars, such as assembly/GLSL/SQL strings inside C++,
or Markdown fences for languages outside the table, do not have an additional
grammar bundled. The enclosing document remains editable and highlighted.

## Loading and ownership

Call `initialize(applicationContext)`, then `createLanguage(entry, dark)` and
`createColorScheme(dark)` on a worker dispatcher. First use of each brightness
parses the themes, grammars and language configurations on that worker. The
cache is synchronized and retains an application `AssetManager`, not an
activity. Reading these packaged assets never requires network access.

Light and dark caches have independent `GrammarRegistry` and `ThemeRegistry`
instances and matching token color maps. Each returned color scheme uses its
own theme registry. No editor changes Sora's global theme: opening another
editor cannot reset the current editor's colors. Use the two-argument
`TextMateColorScheme.create(registry, model)` overload; Sora 0.24.6's
one-argument registry overload uses the global registry internally.

Install the language and color scheme on the main thread. Apply Material UI
colors after setting `editor.colorScheme`, because Sora reapplies its default
colors while attaching a scheme. The host also synchronizes
`TextMateLanguage.tabSize` with the editor's selected tab width. Do not share a
language object between editors. If loading is cancelled before installation,
release its analyzer and formatter as well as the language itself:
`TextMateLanguage.destroy()` alone is a no-op in Sora 0.24.6.

## Pinned sources and modifications

All runtime assets are under [`app/src/main/assets/sora`](../app/src/main/assets/sora).
[`sources.json`](../app/src/main/assets/sora/sources.json) records the exact
download URL, upstream SHA-256 and packaged SHA-256 for each upstream file,
including licenses. This avoids depending on a moving branch or runtime
download. The app-owned `languages.json` only connects those grammars to their
configuration files and embedded scopes.

- Sora's [0.24.6 sample assets](https://github.com/Rosemoe/sora-editor/tree/87055c459e346cac6c619b3350f0bfba076228cc/app/src/main/assets/textmate),
  commit `87055c459e346cac6c619b3350f0bfba076228cc`, provide Java, Kotlin, Python,
  XML, HTML, JavaScript, Markdown, their language configurations, and Quiet Light.
- The additional CSS, TypeScript/TSX, JSON/JSONC, Shell, YAML, C/C++ and macro
  grammars, HTML derivative grammar, configurations and Dark (Visual Studio)
  theme come from [VS Code 1.96.4](https://github.com/microsoft/vscode/tree/cd4ee3b1c348a13bafd8f9ad8060705f6d4b9cba/extensions),
  commit `cd4ee3b1c348a13bafd8f9ad8060705f6d4b9cba`.
- JSONC comments and trailing commas were removed from the dark theme and
  TypeScript, C and C++ language configuration files, then those four files
  were formatted as strict JSON. Their string and regex values were preserved.
- JSON's configuration additionally reuses the upstream JavaScript
  configuration's region-folding markers. Sora 0.24.6 skips indentation-based
  code blocks entirely when `folding` is absent, so this adaptation enables
  block guides and sticky scroll for JSON/JSONC. No new grammar regex was
  introduced. The source file, donor file and packaged hash are recorded.
  All other downloaded files are byte-for-byte unchanged.
- The grammar files retain their upstream `version` and
  `information_for_contributors` fields. Fix grammar rules upstream rather
  than replacing them with app-specific regular expressions.

## Copyright and licenses

Complete license texts and upstream third-party notices ship in the APK under
[`sora/licenses`](../app/src/main/assets/sora/licenses). The original licenses
of the language grammars continue to apply alongside notices from the
distributing repositories.

| Material | Original project / copyright | License copy |
| --- | --- | --- |
| Sora sample resources and integration library | Rosemoe, copyright 2020–2024 | [LGPL 2.1 or later](../app/src/main/assets/sora/licenses/sora-editor-LICENSE.txt) |
| VS Code resources and themes | Microsoft Corporation and contributors | [MIT](../app/src/main/assets/sora/licenses/vscode-LICENSE.txt), [full third-party notices](../app/src/main/assets/sora/licenses/vscode-ThirdPartyNotices.txt) |
| Java grammar | `atom/language-java`, copyright 2014 GitHub Inc. | [MIT](../app/src/main/assets/sora/licenses/atom-language-java-MIT.txt) |
| XML grammar | `atom/language-xml`, copyright 2014 GitHub Inc. | [MIT](../app/src/main/assets/sora/licenses/atom-language-xml-MIT.txt) |
| Python grammar | `MagicStack/MagicPython`, copyright 2015–present MagicStack Inc. | [MIT](../app/src/main/assets/sora/licenses/MagicPython-MIT.txt) |
| Kotlin grammar | Vladimir Kostyukov, copyright 2012–2014; Kotlin language extension contributors | [Apache 2.0](../app/src/main/assets/sora/licenses/kotlin-Apache-2.0.txt) |
| JavaScript and TypeScript grammars | `microsoft/TypeScript-TmLanguage`, Microsoft Corporation | MIT, in the bundled VS Code third-party notices |
| JSON, CSS and Markdown grammars | `microsoft/vscode-JSON.tmLanguage`, `microsoft/vscode-css`, `microsoft/vscode-markdown-tm-grammar` | MIT, in the bundled VS Code third-party notices |
| C, C++ and Shell grammars | `jeff-hykin/better-c-syntax`, `better-cpp-syntax`, `better-shell-syntax`, copyright 2019 Jeff Hykin | MIT, in the bundled VS Code third-party notices |
| HTML and HTML derivative grammars | `textmate/html.tmbundle` authors | TextMate Bundle License, in the bundled VS Code third-party notices |
| YAML grammar | `textmate/yaml.tmbundle`, copyright 2015 FichteFoll | MIT text under the YAML entry of the bundled VS Code third-party notices |

The Sora sample's Kotlin grammar matches
[`mathiasfrohlich/vscode-kotlin` at `090fe4cd054d6142d7eaefdb69c12d4b063a089e`](https://github.com/mathiasfrohlich/vscode-kotlin/blob/090fe4cd054d6142d7eaefdb69c12d4b063a089e/syntaxes/Kotlin.tmLanguage)
with only its XML declaration and DTD preamble omitted. Its adjacent Apache
license, including the original author's copyright, is included unchanged.
The VS Code third-party notice file is preserved in full; its additional
entries do not mean that all VS Code dependencies are bundled in this app.

When updating these assets, preserve their upstream attribution, review
external grammar references, update `sources.json`, and rerun the real-engine
language checks together with the editor build.

## Reproducible verification

Build the current application classes with `:app:compileDebugKotlin` or
`:app:assembleDebug`, then run on Android/Termux:

```sh
python3 tools/verify_sora_editor.py \
  --gradle-home /path/to/gradle-cache \
  --java-home /path/to/jdk \
  --android-jar /path/to/android-sdk/platforms/android-37.2/android.jar \
  --suite languages
```

The script validates every packaged SHA-256 against `sources.json`, compiles
[`SoraEditorLanguagesTest`](../tests/SoraEditorLanguagesTest.java), packages
the application's actual compiled detector and cached Sora/TextMate libraries
with D8, and executes them with Android's `app_process`. It refuses stale
application classes. Its generated files and logs stay under
`~/tmp/materialfiles-sora-check`.

The language suite checks file-name detection, all 18 registered grammars,
language configuration and analyzer creation, both themes' token color
indices, and distinct syntax colors. It exercises Java block comments across
lines, JavaScript and CSS inside HTML, TSX tags and attributes, Markdown code
fences, and Markdown's inline HTML dependency. It also checks that independent
schemes leave the global theme and an existing scheme's UI colors unchanged.
Assets enter through Sora's real file resolver; tokenization, configuration,
themes and Android framework calls use the real runtime. Editor gestures and
keyboard interaction require separate UI verification.

The completed Android API 35 run passed **1,827 language checks**, including
all 18 grammar scopes in both themes. All **41 packaged upstream files**
matched their recorded SHA-256 values. Sora's zero-based color IDs are checked
against the selected theme's palette, including the valid default color at
index zero.
