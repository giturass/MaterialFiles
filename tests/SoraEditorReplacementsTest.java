/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

import android.os.Build;

import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.PatternSyntaxException;

import io.github.rosemoe.sora.text.Content;
import io.github.rosemoe.sora.util.regex.RegexBackrefGrammar;
import io.github.rosemoe.sora.widget.EditorSearcher.SearchOptions;
import kotlin.Unit;
import me.zhanghai.android.files.viewer.text.SoraEditorReplacements;
import me.zhanghai.android.files.viewer.text.SoraEditorReplacements.Match;
import me.zhanghai.android.files.viewer.text.SoraEditorReplacements.Result;
import me.zhanghai.android.files.viewer.text.SoraEditorReplacements.Snapshot;

/** Exercises the compiled app helper, real Sora regex expansion, and real Sora undoable content. */
public final class SoraEditorReplacementsTest {
    private static int checks;

    public static void main(String[] arguments) {
        preserveRegexContext();
        expandGroups();
        matchLiteralAndWholeWords();
        replaceZeroWidthMatches();
        navigateZeroWidthMatches();
        keepUnicodeAndLineEndings();
        rejectStaleResults();
        preserveUndoAndRedo();
        honorCancellation();
        reportMalformedExpressions();
        System.out.println("PASS: " + checks + " replacement checks on Android API "
                + Build.VERSION.SDK_INT + "; regex context/groups, zero-width navigation, literal"
                + " and whole-word matching, stale/read-only guards, cancellation and undo.");
    }

    private static void preserveRegexContext() {
        equal("fooX foobar", replace("foobar foobar", "(?<=foo)bar", "X", regex(),
                3, 6, false).getText(), "Single lookbehind lost its prefix context");
        equal("foobar! foobar!", replace("foobar foobar", "(?<=foo)bar", "$0!", regex(),
                0, 0, true).getText(), "Replace-all lookbehind lost its prefix context");
        equal("foo!bar foo!bar", replace("foobar foobar", "foo(?=bar)", "$0!", regex(),
                0, 0, true).getText(), "Lookahead lost its suffix context");
        equal("foobar fooX", replace("foobar foobar", "(?<=foo)bar", "X", regex(),
                9, 9, false).getText(), "Single replace did not find the next full-context match");
        equal("fooX foobar", replace("foobar foobar", "(?<=foo)bar", "X", regex(),
                13, 13, false).getText(), "Single replace did not wrap to the first match");
    }

    private static void expandGroups() {
        equal("pre-1a pre-2b", replace("pre-a1 pre-b2", "(?<=pre-)([a-z])(\\d)", "$2$1",
                regex(), 0, 0, true).getText(), "Group expansion lost lookbehind context");
        equal("X aX", replace("b ab", "(a)?b", "$1X", regex(), 0, 0, true).getText(),
                "An unmatched optional group was not empty");
        equal("$1", replace("a", "(a)", "\\$1", regex(), 0, 0, true).getText(),
                "Sora's escaped dollar syntax changed");
        equal("$9", replace("a", "(a)", "$9", regex(), 0, 0, true).getText(),
                "Sora's out-of-range group fallback changed");
        equal("a$2", replace("ab", "(a)(b)", "$1\\$2", regex(), 0, 0, true).getText(),
                "An escape immediately following a group reference was skipped");
        equal("a\\b", replace("ab", "(a)(b)", "$1\\\\$2", regex(), 0, 0, true).getText(),
                "A backslash immediately following a group reference was skipped");
        equal("a2a$a$9", replace("a", "(a)", "$12$01$$1$9", regex(), 0, 0, true).getText(),
                "Numeric references did not consume the longest valid group index");
        equal("ja", replace("abcdefghij", "(a)(b)(c)(d)(e)(f)(g)(h)(i)(j)", "$10$1",
                regex(), 0, 0, true).getText(), "Adjacent multi-digit group references failed");
        equal("\\x$", replace("a", "(a)", "\\x$", regex(), 0, 0, true).getText(),
                "Unknown escapes or a trailing literal dollar were changed");
        equal("", replace("a", "(a)", "", regex(), 0, 0, true).getText(),
                "An empty regex replacement did not remove the match");
        SearchOptions customGrammar = new SearchOptions(SearchOptions.TYPE_REGULAR_EXPRESSION,
                false, new RegexBackrefGrammar('@', '!'));
        equal("ba@1", replace("ab", "(a)(b)", "@2@1!@1", customGrammar, 0, 0, true).getText(),
                "Sora's custom backreference grammar was ignored");
    }

    private static void matchLiteralAndWholeWords() {
        SearchOptions normal = new SearchOptions(SearchOptions.TYPE_NORMAL, false);
        SearchOptions insensitive = new SearchOptions(SearchOptions.TYPE_NORMAL, true);
        SearchOptions wholeWord = new SearchOptions(SearchOptions.TYPE_WHOLE_WORD, true,
                RegexBackrefGrammar.DEFAULT);
        equal("a$1\\xb", replace("a.b", ".", "$1\\x", normal, 0, 0, true).getText(),
                "Literal replacement interpreted regex syntax");
        equal("X X X", replace("AaA aaa Aaa", "aaa", "X", insensitive, 0, 0, true).getText(),
                "Literal ignore-case matching differs from Sora");
        equal("$1 cats scatter $1.", replace("cat cats scatter CAT.", "cat", "$1", wholeWord,
                0, 0, true).getText(), "Whole-word matching or literal replacement changed");
        Result result = replace("aaaa", "aa", "b", normal, 0, 0, true);
        equal("bb", result.getText(), "Normal matches overlap");
        require(result.getReplacementCount() == 2, "Incorrect non-overlapping match count");
        require(SoraEditorReplacements.replace("abc", "z", "X", normal, 0, 0, true) == null,
                "A missing match changed the document");
    }

    private static void replaceZeroWidthMatches() {
        Result lines = replace("aa\nbb\ncc", "^", ">", regex(), 0, 0, true);
        equal(">aa\n>bb\n>cc", lines.getText(), "Zero-width multiline anchors were skipped");
        require(lines.getReplacementCount() == 3, "Incorrect multiline anchor count");
        equal("aa<\nbb<\ncc<", replace("aa\nbb\ncc", "$", "<", regex(), 0, 0, true).getText(),
                "End-of-line anchors were skipped");
        equal("aa\n>bb", replace("aa\nbb", "^", ">", regex(), 3, 3, false).getText(),
                "A single zero-width match could not be replaced");
        equal("x", replace("", "^", "x", regex(), 0, 0, true).getText(),
                "An empty document's zero-width match was skipped");
        equal(">a>a>a>a", replace("aaaa", "(?=a)", ">", regex(), 0, 0, true).getText(),
                "Zero-width lookahead insertion did not terminate correctly");
        Result terminal = replace("a", "a*", "x", regex(), 0, 0, true);
        equal("x", terminal.getText(), "Replacement added a terminal match Sora does not display");
        require(terminal.getReplacementCount() == 1, "Sora's terminal-match stop rule changed");
    }

    private static void navigateZeroWidthMatches() {
        List<Match> matches = SoraEditorReplacements.findMatches("a\nb\nc", "^", regex());
        require(matches.size() == 3, "Wrong zero-width navigation result count");
        equalMatch(0, SoraEditorReplacements.findNext(matches, 0, 0, true, false),
                "First navigation skipped the caret's zero-width match");
        equalMatch(2, SoraEditorReplacements.findNext(matches, 0, 0, true, true),
                "Next navigation got stuck on a zero-width match");
        equalMatch(0, SoraEditorReplacements.findNext(matches, 4, 4, true, true),
                "Forward zero-width navigation did not wrap");
        equalMatch(4, SoraEditorReplacements.findNext(matches, 0, 0, false, true),
                "Backward zero-width navigation did not wrap");
        equalMatch(2, SoraEditorReplacements.findNext(matches, 4, 4, false, true),
                "Backward navigation did not move to the preceding match");
    }

    private static void keepUnicodeAndLineEndings() {
        String original = "\uFEFF猫🐈\r\n猫🐈\r猫🐈\n";
        String expected = "\uFEFF猫🐕\r\n猫🐕\r猫🐕\n";
        equal(expected, replace(original, "🐈", "🐕", new SearchOptions(false, false),
                0, 0, true).getText(), "Replacement changed BOM, surrogate pairs, or line endings");
    }

    private static void rejectStaleResults() {
        Content content = new Content("a a");
        Snapshot snapshot = new Snapshot(content, 4, content.toString());
        Result result = replace(snapshot.getText(), "a", "b", new SearchOptions(false, false),
                0, 0, true);
        content.insert(0, content.getColumnCount(0), " new input");
        require(!SoraEditorReplacements.applyIfUnchanged(snapshot, content, 5, true, result),
                "A delayed replacement overwrote newer input");
        equal("a a new input", content.toString(), "A rejected result still changed newer input");
        require(!SoraEditorReplacements.applyIfUnchanged(snapshot, content, 4, true, result),
                "Text comparison failed to catch an unreported mutation");

        Content anotherDocument = new Content(snapshot.getText());
        require(!SoraEditorReplacements.applyIfUnchanged(snapshot, anotherDocument, 4, true, result),
                "A delayed result overwrote a different document instance");
        equal("a a", anotherDocument.toString(), "A rejected result changed the other document");

        Content readOnly = new Content("a a");
        Snapshot readOnlySnapshot = new Snapshot(readOnly, 0, readOnly.toString());
        require(!SoraEditorReplacements.applyIfUnchanged(readOnlySnapshot, readOnly, 0, false, result),
                "A delayed replacement ignored read-only mode");
        equal("a a", readOnly.toString(), "Read-only content changed");
        require(!SoraEditorReplacements.applyIfUnchanged(readOnlySnapshot, readOnly, 1, true, result),
                "A new document version with identical text was treated as the old snapshot");
    }

    private static void preserveUndoAndRedo() {
        Content content = new Content("a\r\na\n");
        Snapshot snapshot = new Snapshot(content, 0, content.toString());
        Result result = replace(snapshot.getText(), "a", "bb", new SearchOptions(false, false),
                0, 0, true);
        require(SoraEditorReplacements.applyIfUnchanged(snapshot, content, 0, true, result),
                "An unchanged writable snapshot could not be applied");
        equal("bb\r\nbb\n", content.toString(), "Applied replacement changed line endings");
        require(content.canUndo(), "Replacement did not create an undo action");
        content.undo();
        equal(snapshot.getText(), content.toString(), "One undo did not restore the whole document");
        require(!content.canUndo(), "Replace-all was split into multiple undo actions");
        require(content.canRedo(), "Undo discarded replacement redo history");
        content.redo();
        equal(result.getText(), content.toString(), "Redo did not restore the replacement");

        Content unchanged = new Content("a");
        Snapshot unchangedSnapshot = new Snapshot(unchanged, 0, "a");
        Result noop = replace("a", "a", "a", new SearchOptions(false, false), 0, 0, true);
        require(SoraEditorReplacements.applyIfUnchanged(unchangedSnapshot, unchanged, 0, true, noop),
                "A no-op replacement was rejected");
        require(!unchanged.canUndo(), "A no-op replacement changed undo history");
    }

    private static void honorCancellation() {
        AtomicInteger iterations = new AtomicInteger();
        boolean canceled = false;
        try {
            SoraEditorReplacements.replace("a a a a", "a", "b", new SearchOptions(false, false),
                    0, 0, true, () -> {
                        if (iterations.incrementAndGet() == 2) throw new CancellationException();
                        return Unit.INSTANCE;
                    });
        } catch (CancellationException expected) {
            canceled = true;
        }
        require(canceled && iterations.get() == 2, "Replacement did not honor cancellation checks");
    }

    private static void reportMalformedExpressions() {
        boolean invalidRegex = false;
        try {
            SoraEditorReplacements.findMatches("a", "[", regex());
        } catch (PatternSyntaxException expected) {
            invalidRegex = true;
        }
        require(invalidRegex, "An invalid regex was not rejected");
        boolean invalidReplacement = false;
        try {
            replace("a", "(a)", "\\", regex(), 0, 0, true);
        } catch (IllegalArgumentException | IndexOutOfBoundsException expected) {
            invalidReplacement = true;
        }
        require(invalidReplacement, "A malformed Sora backreference expression was not rejected");
    }

    private static SearchOptions regex() {
        return new SearchOptions(SearchOptions.TYPE_REGULAR_EXPRESSION, false,
                RegexBackrefGrammar.DEFAULT);
    }

    private static Result replace(String text, String query, String replacement, SearchOptions options,
                                  int start, int end, boolean all) {
        Result result = SoraEditorReplacements.replace(text, query, replacement, options,
                start, end, all);
        require(result != null, "Expected matches for " + query);
        return result;
    }

    private static void equalMatch(int index, Match match, String message) {
        require(match != null && match.getStart() == index && match.getEnd() == index, message);
    }

    private static void equal(String expected, String actual, String message) {
        require(expected.equals(actual), message + ": expected=" + expected + ", actual=" + actual);
    }

    private static void require(boolean condition, String message) {
        ++checks;
        if (!condition) throw new AssertionError(message);
    }
}
