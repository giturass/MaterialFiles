/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

import android.os.Build;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import io.github.rosemoe.sora.text.Content;
import io.github.rosemoe.sora.text.Cursor;
import io.github.rosemoe.sora.text.LineSeparator;
import io.github.rosemoe.sora.text.UndoManager;
import me.zhanghai.android.files.viewer.text.SoraEditorDocument;

/** Runs against the compiled application document and the unmodified Sora content implementation. */
public final class SoraEditorDocumentTest {
    private static int checks;

    public static void main(String[] arguments) {
        roundTripText();
        retainContentAndHistory();
        preserveEditsDuringSave();
        reloadDocument();
        changeEncoding();
        detectSameLengthChanges();
        System.out.println("PASS: " + checks + " checks on Android API " + Build.VERSION.SDK_INT
                + "; line endings, Unicode, cursor/history retention, save races, reload and encoding.");
    }

    private static void roundTripText() {
        String[] texts = {
                "", "\n", "\r", "\r\n", "first\r\nsecond\rlast\n",
                "中文\r\n第二行\r末行\n",
                "\uFEFF中文 🐈\uD83D\uDD10 e\u0301 العربية\r\n\t终点\u0000\n",
                "没有末尾换行 🧑‍💻"
        };
        for (String text : texts) {
            SoraEditorDocument document = new SoraEditorDocument();
            document.load(text, false);
            Content content = document.getContent();
            equal(text, content.toString(), "Loading changed document characters");
            require(Arrays.equals(text.getBytes(StandardCharsets.UTF_8),
                    content.toString().getBytes(StandardCharsets.UTF_8)),
                    "Loading changed the UTF-8 representation");
            require(!document.isChanged(), "Freshly loaded text is dirty");
            require(!content.canUndo(), "Loading created an undo action");

            appendEdit(content, "新增 🐈");
            require(document.isChanged(), "Unicode edit is not dirty");
            content.undo();
            equal(text, content.toString(), "Undo changed original characters or line endings");
            require(!document.isChanged(), "Undo to the loaded document is dirty");
            content.redo();
            equal(text + "新增 🐈", content.toString(), "Redo lost Unicode text");
        }

        SoraEditorDocument document = new SoraEditorDocument();
        document.load("first\r\nsecond\rlast\n", false);
        Content content = document.getContent();
        require(content.getLineCount() == 4, "Mixed line endings were not parsed as four lines");
        require(content.getLine(0).getLineSeparator() == LineSeparator.CRLF, "CRLF was normalized");
        require(content.getLine(1).getLineSeparator() == LineSeparator.CR, "CR was normalized");
        require(content.getLine(2).getLineSeparator() == LineSeparator.LF, "LF was normalized");
        require(content.getLine(3).getLineSeparator() == LineSeparator.NONE, "EOF gained a newline");
    }

    private static void retainContentAndHistory() {
        SoraEditorDocument document = new SoraEditorDocument();
        String initial = "first\r\n中文";
        document.load(initial, false);
        Content retained = document.getContent();
        appendEdit(retained, " edited");
        Cursor cursor = retained.getCursor();
        cursor.set(1, 2);
        UndoManager undoManager = retained.getUndoManager();

        // A view recreation observes the disk state again while this document survives in its model.
        document.load(initial, true);
        require(document.getContent() == retained, "Observing disk state replaced an edited buffer");
        require(retained.getCursor() == cursor, "Observing disk state replaced the cursor");
        require(cursor.getLeftLine() == 1 && cursor.getLeftColumn() == 2,
                "Observing disk state moved the cursor");
        require(retained.getUndoManager() == undoManager && retained.canUndo(),
                "Observing disk state lost undo history");
        require(document.isChanged(), "Observing disk state cleared unsaved changes");

        String snapshot = retained.toString();
        document.markSaved(snapshot);
        document.load(snapshot, false);
        require(document.getContent() == retained, "Saving unchanged text replaced the buffer");
        require(retained.getUndoManager() == undoManager && retained.canUndo(),
                "Saving unchanged text cleared undo history");
        require(cursor.getLeftLine() == 1 && cursor.getLeftColumn() == 2,
                "Saving unchanged text moved the cursor");
        require(!document.isChanged(), "Saved snapshot is dirty");
        retained.undo();
        equal(initial, retained.toString(), "Undo after saving did not restore the earlier text");
        require(document.isChanged(), "Undo away from the saved snapshot is not dirty");
        retained.redo();
        equal(snapshot, retained.toString(), "Redo after saving lost the saved text");
        require(!document.isChanged(), "Redo to the saved snapshot is dirty");
    }

    private static void preserveEditsDuringSave() {
        SoraEditorDocument document = new SoraEditorDocument();
        document.load("original\r\n", false);
        Content retained = document.getContent();
        appendEdit(retained, "saved edit");
        String writingSnapshot = retained.toString();
        appendEdit(retained, " plus newer input");
        String currentText = retained.toString();

        // The asynchronous write completes after the user has made another edit.
        document.markSaved(writingSnapshot);
        require(document.getContent() == retained, "Write completion replaced the buffer");
        equal(currentText, retained.toString(), "Write completion discarded newer input");
        require(document.isChanged(), "Write completion marked newer input as saved");
        document.load(writingSnapshot, true);
        equal(currentText, retained.toString(), "Saved disk emission discarded newer input");
        require(document.isChanged(), "Saved disk emission cleared newer input's dirty state");

        retained.undo();
        equal(writingSnapshot, retained.toString(), "Undo did not reach the completed write snapshot");
        require(!document.isChanged(), "Undo to the completed write snapshot is dirty");
        retained.redo();
        equal(currentText, retained.toString(), "Redo lost the input typed during a write");
        require(document.isChanged(), "Redo of unsaved input is not dirty");
    }

    private static void reloadDocument() {
        SoraEditorDocument document = new SoraEditorDocument();
        document.load("old text", false);
        Content oldContent = document.getContent();
        appendEdit(oldContent, " unsaved");
        document.load("replacement\r\n文件", false);
        Content replacement = document.getContent();
        require(replacement != oldContent, "Confirmed reload did not replace the edited buffer");
        equal("replacement\r\n文件", replacement.toString(), "Reload displayed stale text");
        require(!document.isChanged(), "Reloaded text is dirty");
        require(!replacement.canUndo(), "Reload can undo into the discarded document");
        equal("old text unsaved", oldContent.toString(), "Reload mutated the previous buffer");

        document.load("", false);
        equal("", document.getContent().toString(), "Reloading an empty file retained old text");
        require(!document.isChanged(), "Reloaded empty file is dirty");
    }

    private static void changeEncoding() {
        byte[] bytes = "中文\r\n".getBytes(StandardCharsets.UTF_8);
        String utf8 = new String(bytes, StandardCharsets.UTF_8);
        String latin1 = new String(bytes, StandardCharsets.ISO_8859_1);
        SoraEditorDocument document = new SoraEditorDocument();
        document.load(utf8, false);
        Content retained = document.getContent();
        appendEdit(retained, "正在编辑");
        String edited = retained.toString();
        document.load(latin1, true);
        require(document.getContent() == retained, "Encoding switch replaced an unsaved buffer");
        equal(edited, retained.toString(), "Encoding switch lost unsaved Unicode text");
        require(document.isChanged(), "Encoding switch cleared unsaved changes");
        retained.undo();
        equal(utf8, retained.toString(), "Encoding switch lost undo history");
        require(document.isChanged(), "Undo ignored the newly decoded disk baseline");

        document.load(latin1, false);
        equal(latin1, document.getContent().toString(), "Confirmed decode did not replace the buffer");
        require(!document.isChanged(), "Freshly decoded text is dirty");
        document.load(utf8, false);
        equal(utf8, document.getContent().toString(), "Switching back to UTF-8 changed Unicode text");
        require(!document.isChanged(), "Switching back to UTF-8 left a dirty document");
    }

    private static void detectSameLengthChanges() {
        SoraEditorDocument document = new SoraEditorDocument();
        document.load("猫🐈", false);
        Content content = document.getContent();
        content.replace(0, content.length(), "狗🐕");
        require(document.isChanged(), "Same-length Unicode replacement was not detected");
        content.undo();
        equal("猫🐈", content.toString(), "Undo corrupted a supplementary character");
        require(!document.isChanged(), "Same-length replacement undo did not restore clean state");
    }

    private static void appendEdit(Content content, String text) {
        // Separate user actions must not depend on Sora's time-based keystroke coalescing.
        content.beginBatchEdit();
        try {
            int lastLine = content.getLineCount() - 1;
            content.insert(lastLine, content.getColumnCount(lastLine), text);
        } finally {
            content.endBatchEdit();
        }
    }

    private static void equal(String expected, String actual, String message) {
        require(expected.equals(actual), message);
    }

    private static void require(boolean condition, String message) {
        ++checks;
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
