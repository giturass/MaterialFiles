/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.viewer.text

import io.github.rosemoe.sora.text.Content

/** The document outlives the editor view, including its cursor and undo history. */
class SoraEditorDocument {
    var content = Content()
        private set

    private var savedText = ""

    val isChanged: Boolean
        get() = !hasText(savedText)

    fun load(text: String, preserveChanges: Boolean) {
        if (!preserveChanges && !hasText(text)) {
            content = Content(text)
        }
        savedText = text
    }

    fun markSaved(text: String) {
        // A write can finish after more input. Update only the saved snapshot, never the buffer.
        savedText = text
    }

    private fun hasText(text: String): Boolean =
        content.length == text.length && content.toString() == text
}
