/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

import android.os.Build;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.github.rosemoe.sora.langs.textmate.TextMateAnalyzer;
import io.github.rosemoe.sora.langs.textmate.TextMateColorScheme;
import io.github.rosemoe.sora.langs.textmate.TextMateLanguage;
import io.github.rosemoe.sora.langs.textmate.registry.FileProviderRegistry;
import io.github.rosemoe.sora.langs.textmate.registry.GrammarRegistry;
import io.github.rosemoe.sora.langs.textmate.registry.ThemeRegistry;
import io.github.rosemoe.sora.langs.textmate.registry.model.ThemeModel;
import io.github.rosemoe.sora.widget.schemes.EditorColorScheme;
import me.zhanghai.android.files.viewer.text.SoraEditorLanguages;
import org.eclipse.tm4e.core.grammar.IGrammar;
import org.eclipse.tm4e.core.grammar.IStateStack;
import org.eclipse.tm4e.core.grammar.IToken;
import org.eclipse.tm4e.core.grammar.ITokenizeLineResult;
import org.eclipse.tm4e.core.internal.grammar.tokenattrs.EncodedTokenAttributes;
import org.eclipse.tm4e.core.registry.IThemeSource;

/** Exercises the actual app detector and bundled grammars on the unmodified TextMate engine. */
public final class SoraEditorLanguagesTest {
    private static final Duration TOKENIZATION_LIMIT = Duration.ofSeconds(10);
    private static int checks;

    public static void main(String[] arguments) throws Exception {
        require(arguments.length == 1, "Expected the app/src/main/assets directory");
        File assets = new File(arguments[0]);
        require(new File(assets, "sora/languages.json").isFile(), "Missing grammar manifest");
        detectFileNames();
        FileProviderRegistry.getInstance().addFileProvider(path -> {
            try {
                return new FileInputStream(new File(assets, path));
            } catch (FileNotFoundException exception) {
                return null;
            }
        });
        ThemeModel originalGlobalTheme = ThemeRegistry.getInstance().getCurrentThemeModel();
        for (boolean dark : new boolean[] {false, true}) {
            checkTheme(assets, dark);
        }
        require(ThemeRegistry.getInstance().getCurrentThemeModel() == originalGlobalTheme,
                "Editor initialization changed the global TextMate theme");
        System.out.println("PASS: " + checks + " language checks on Android API "
                + Build.VERSION.SDK_INT + "; file detection, all grammar/configuration assets, "
                + "light/dark token colors, multiline states, HTML JS/CSS, TSX and Markdown embeds.");
    }

    private static void detectFileNames() {
        String[][] cases = {
                {"notes.txt", "plain"}, {"unknown.custom", "plain"}, {"README", "plain"},
                {"App.java", "java"}, {"Main.kt", "kotlin"}, {"build.gradle.kts", "kotlin"},
                {"index.js", "javascript"}, {"Component.jsx", "javascript"},
                {"module.mjs", "javascript"}, {"config.cjs", "javascript"},
                {"types.d.ts", "typescript"}, {"Screen.tsx", "typescript"},
                {"package.json", "json"}, {"settings.jsonc", "json"}, {".eslintrc", "json"},
                {"manifest.webmanifest", "json"}, {"composer.lock", "json"},
                {"AndroidManifest.xml", "xml"}, {"image.svg", "xml"},
                {"index.HTML", "html"}, {"style.css", "css"}, {"README.md", "markdown"},
                {"script.py", "python"}, {"types.pyi", "python"}, {"script.sh", "shell"},
                {".bashrc", "shell"}, {".zshenv", "shell"}, {"gradlew", "shell"},
                {"settings.YAML", "yaml"}, {"build.yml", "yaml"}, {"main.c", "c"},
                {"main.C", "cpp"}, {"header.H", "cpp"}, {"header.hpp", "cpp"},
                {"/storage/中文/project/file.kt", "kotlin"}, {"C:\\code\\main.py", "python"}
        };
        for (String[] value : cases) {
            require(value[1].equals(SoraEditorLanguages.INSTANCE.detect(value[0]).getId()),
                    "Wrong automatic language for " + value[0]);
        }
        require("source.tsx".equals(
                        SoraEditorLanguages.INSTANCE.detect("Component.tsx").getScope()),
                "TSX did not select the JSX grammar");
        require("source.ts".equals(SoraEditorLanguages.INSTANCE.detect("module.ts").getScope()),
                "Ordinary TypeScript selected a JSX-only grammar");
        require("plain".equals(SoraEditorLanguages.INSTANCE.forId("obsolete-language").getId()),
                "An unknown saved language choice did not fall back to plain text");
    }

    private static void checkTheme(File assets, boolean dark) throws Exception {
        String path = "sora/themes/" + (dark ? "dark" : "light") + ".json";
        ThemeModel model;
        try (InputStream stream = new FileInputStream(new File(assets, path))) {
            model = new ThemeModel(IThemeSource.fromInputStream(stream, path, StandardCharsets.UTF_8));
            model.setDark(dark);
            model.load();
        }
        require(model.isLoaded() && model.getTheme().getColorMap().size() > 2,
                "Theme has no syntax color palette: " + path);
        ThemeRegistry themes = new ThemeRegistry();
        themes.loadTheme(model);
        GrammarRegistry grammars = new GrammarRegistry(null);
        progress("Loading grammar registry for " + path);
        List<IGrammar> loaded = grammars.loadGrammars("sora/languages.json");
        progress("Loaded grammar registry for " + path);
        grammars.setTheme(model);
        TextMateColorScheme scheme = TextMateColorScheme.create(new ThemeRegistry(), model);
        require(scheme.isDark() == dark, "Color scheme brightness differs from its grammar palette");
        Map<String, String> samples = samples();
        require(loaded.size() == samples.size(), "A bundled grammar has no smoke-test sample");
        Set<String> auxiliaryScopes = new HashSet<>(Arrays.asList(
                "source.cpp.embedded.macro", "text.html.derivative"));
        for (IGrammar grammar : loaded) {
            String scope = grammar.getScopeName();
            progress("Tokenizing " + scope + " with " + (dark ? "dark" : "light") + " theme");
            String sample = samples.get(scope);
            require(sample != null, "Missing sample for " + scope);
            Tokenization result = tokenize(grammar, sample, model, scheme);
            require(result.foregrounds.size() > 1,
                    path + " renders " + scope + " without distinct syntax colors");
            require(result.hasDetailedScopes(), "No syntax scopes were produced for " + scope);
            if (!auxiliaryScopes.contains(scope)) {
                require(grammars.findLanguageConfiguration(scope) != null,
                        "Language configuration failed to parse for " + scope);
                TextMateLanguage language = TextMateLanguage.create(scope, grammars, themes, true);
                try {
                    require(language.getAnalyzeManager() instanceof TextMateAnalyzer,
                            "TextMate silently fell back to an empty analyzer for " + scope);
                    require(language.isAutoCompleteEnabled(), "Identifier completion disabled for " + scope);
                    require(language.getLanguageConfiguration() != null,
                            "TextMate did not apply the configuration for " + scope);
                    require(language.getLanguageConfiguration().getFolding() != null,
                            "Code block analysis and sticky scroll are unavailable for " + scope);
                    require(language.getNewlineHandlers().length > 0 && language.getSymbolPairs() != null,
                            "Missing indentation/bracket handlers for " + scope);
                } finally {
                    language.getAnalyzeManager().destroy();
                    language.destroy();
                }
            }
        }
        for (SoraEditorLanguages.Entry entry : SoraEditorLanguages.INSTANCE.getLanguages()) {
            if (entry.getScope() != null) {
                require(grammars.findGrammar(entry.getScope()) != null,
                        "Menu language is absent from the grammar manifest: " + entry.getId());
            }
        }
        checkEmbeddedLanguages(grammars, model, scheme);
        checkMultilineState(grammars, model, scheme);
        int markerColor = 0xFF123456;
        scheme.setColor(EditorColorScheme.CURRENT_LINE, markerColor);
        TextMateColorScheme.create(new ThemeRegistry(), model);
        require(scheme.getColor(EditorColorScheme.CURRENT_LINE) == markerColor,
                "Creating another editor reset the first editor's UI colors");
        grammars.dispose();
        themes.dispose();
    }

    private static Map<String, String> samples() {
        Map<String, String> samples = new LinkedHashMap<>();
        samples.put("source.java", "public class Main {\n  int count = 42; String name = \"中文\";\n}");
        samples.put("source.kotlin", "fun main() {\n  val count: Int = 42\n  println(\"中文\")\n}");
        samples.put("source.python", "def answer(value):\n    return \"中文\" if value else 42");
        samples.put("source.js", "const answer = (value) => value + 42;\nconsole.log(\"中文\");");
        samples.put("source.ts", "interface Result { count: number; }\nconst answer: string = \"中文\";");
        samples.put("source.tsx", "const count: number = 42;\nconst view = <section>{count}</section>;");
        samples.put("source.json", "{ \"count\": 42, \"enabled\": true, \"name\": \"中文\" }");
        samples.put("source.json.comments", "// Settings\n{ \"count\": 42, \"enabled\": true }");
        samples.put("source.css", "body { color: red; margin: 8px; }\n/* 中文 */");
        samples.put("source.shell", "#!/bin/sh\nif [ \"$USER\" = \"root\" ]; then echo \"中文\"; fi");
        samples.put("source.yaml", "name: \"中文\"\nenabled: true\nvalues: [1, 2]");
        samples.put("source.c", "#include <stdio.h>\nint main(void) { puts(\"中文\"); return 42; }");
        samples.put("source.cpp", "#define VALUE(x) ((x) + 1)\nint main() { auto name = \"中文\"; return VALUE(2); }");
        samples.put("source.cpp.embedded.macro", "((value) + 42) /* 中文 */");
        samples.put("text.xml", "<?xml version=\"1.0\"?>\n<item name=\"中文\">42</item>");
        samples.put("text.html.basic", "<div class=\"content\">中文 <strong>42</strong></div>");
        samples.put("text.html.derivative", "<div class=\"content\">中文 <strong>42</strong></div>");
        samples.put("text.html.markdown", "# Heading\n**bold** and `code` [link](https://example.com)");
        return samples;
    }

    private static void checkEmbeddedLanguages(GrammarRegistry grammars, ThemeModel model,
            TextMateColorScheme scheme) {
        String html = "<html>\n<style>\nbody { margin: 8px; }\n</style>\n<script>\n"
                + "const answer = 42;\n</script>\n</html>";
        Tokenization htmlResult = tokenize(grammars.findGrammar("text.html.basic"), html, model, scheme);
        htmlResult.requireScopeAt("margin", "css", "HTML stylesheet was not tokenized as CSS");
        htmlResult.requireScopeAt("const", "storage.type", "HTML script lost JavaScript keyword scopes");

        String tsx = "const view = <section className=\"content\">{42}</section>;";
        Tokenization tsxResult = tokenize(grammars.findGrammar("source.tsx"), tsx, model, scheme);
        tsxResult.requireScopeAt("section", "tag", "TSX tags were not recognized");
        tsxResult.requireScopeAt("className", "attribute", "TSX attributes were not recognized");

        String markdown = "# Code\n\n```python\ndef answer():\n    return 42\n```\n\n"
                + "```javascript\nconst result = 42;\n```\n\n"
                + "```css\nbody { margin: 8px; }\n```\n\n<div class=\"content\">hello</div>";
        Tokenization markdownResult = tokenize(grammars.findGrammar("text.html.markdown"),
                markdown, model, scheme);
        markdownResult.requireScopeAt("return", "keyword", "Markdown Python fence lost keyword scopes");
        markdownResult.requireScopeAt("const", "storage.type", "Markdown JavaScript fence lost keyword scopes");
        markdownResult.requireScopeAt("margin", "css", "Markdown CSS fence was not tokenized as CSS");
        markdownResult.requireScopeAt("div", "tag", "Markdown inline HTML derivative grammar was not resolved");
    }

    private static void checkMultilineState(GrammarRegistry grammars, ThemeModel model,
            TextMateColorScheme scheme) {
        Tokenization result = tokenize(grammars.findGrammar("source.java"),
                "/* start\ncontinued comment\n*/ int value = 42;", model, scheme);
        result.requireScopeAt("continued", "comment", "Block comments did not retain their line state");
        result.requireScopeAt("42", "constant.numeric", "Ending a multiline comment did not restore code scopes");
    }

    private static Tokenization tokenize(IGrammar grammar, String source, ThemeModel model,
            TextMateColorScheme scheme) {
        require(grammar != null, "Missing grammar for a tokenization fixture");
        String[] sourceLines = source.split("\n", -1);
        Tokenization result = new Tokenization(sourceLines);
        IStateStack scopedState = null;
        IStateStack binaryState = null;
        for (String line : sourceLines) {
            ITokenizeLineResult<IToken[]> scoped = grammar.tokenizeLine(line, scopedState, TOKENIZATION_LIMIT);
            require(!scoped.isStoppedEarly(), "Scoped tokenization timed out: " + grammar.getScopeName());
            require(scoped.getTokens().length > 0, "No tokens produced: " + grammar.getScopeName());
            result.lines.add(scoped.getTokens());
            scopedState = scoped.getRuleStack();

            ITokenizeLineResult<int[]> binary = grammar.tokenizeLine2(line, binaryState, TOKENIZATION_LIMIT);
            require(!binary.isStoppedEarly(), "Colored tokenization timed out: " + grammar.getScopeName());
            int[] tokens = binary.getTokens();
            require(tokens.length >= 2 && tokens.length % 2 == 0, "Malformed binary token stream");
            for (int index = 1; index < tokens.length; index += 2) {
                int foreground = EncodedTokenAttributes.getForeground(tokens[index]);
                // Sora deliberately starts ColorMap IDs at zero, unlike upstream vscode-textmate.
                require(foreground >= 0 && foreground < model.getTheme().getColorMap().size(),
                        "Token color index " + foreground + " does not match palette size "
                                + model.getTheme().getColorMap().size() + ": " + grammar.getScopeName());
                result.foregrounds.add(scheme.getColor(255 + foreground));
            }
            binaryState = binary.getRuleStack();
        }
        return result;
    }

    private static final class Tokenization {
        final String[] sourceLines;
        final List<IToken[]> lines = new ArrayList<>();
        final Set<Integer> foregrounds = new HashSet<>();

        Tokenization(String[] sourceLines) {
            this.sourceLines = sourceLines;
        }

        boolean hasDetailedScopes() {
            for (IToken[] line : lines) {
                for (IToken token : line) {
                    if (token.getScopes().size() > 1) {
                        return true;
                    }
                }
            }
            return false;
        }

        void requireScopeAt(String needle, String scopeFragment, String message) {
            for (int lineIndex = 0; lineIndex < sourceLines.length; ++lineIndex) {
                int column = sourceLines[lineIndex].indexOf(needle);
                if (column < 0) {
                    continue;
                }
                for (IToken token : lines.get(lineIndex)) {
                    if (token.getStartIndex() <= column && token.getEndIndex() > column) {
                        for (String scope : token.getScopes()) {
                            if (scope.contains(scopeFragment)) {
                                require(true, message);
                                return;
                            }
                        }
                        throw new AssertionError(message + ": " + token.getScopes());
                    }
                }
                throw new AssertionError("No token covers fixture text: " + needle);
            }
            throw new AssertionError("Fixture text was not found: " + needle);
        }
    }

    private static void require(boolean condition, String message) {
        ++checks;
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static void progress(String message) {
        Runtime runtime = Runtime.getRuntime();
        long used = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024);
        long limit = runtime.maxMemory() / (1024 * 1024);
        System.out.println(message + " (Java heap " + used + "/" + limit + " MiB)");
        System.out.flush();
    }
}
