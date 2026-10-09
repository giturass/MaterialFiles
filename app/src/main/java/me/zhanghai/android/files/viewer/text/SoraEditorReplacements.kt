/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.viewer.text

import io.github.rosemoe.sora.text.Content
import io.github.rosemoe.sora.text.TextUtils
import io.github.rosemoe.sora.util.regex.RegexBackrefGrammar
import io.github.rosemoe.sora.util.regex.RegexBackrefHelper
import io.github.rosemoe.sora.util.regex.RegexBackrefToken
import io.github.rosemoe.sora.widget.EditorSearcher.SearchOptions
import java.util.regex.Matcher
import java.util.regex.Pattern

/** Matches like Sora's searcher, retaining the whole document for regex lookaround and groups. */
object SoraEditorReplacements {
    data class Match(val start: Int, val end: Int)

    data class Result(val text: String, val cursor: Int, val replacementCount: Int)

    data class Snapshot(val content: Content, val version: Long, val text: String) {
        fun isCurrent(currentContent: Content, currentVersion: Long): Boolean =
            content === currentContent && version == currentVersion &&
                text.length == currentContent.length && text == currentContent.toString()
    }

    @JvmStatic
    @JvmOverloads
    fun findMatches(
        text: String,
        query: String,
        options: SearchOptions,
        checkCancelled: () -> Unit = {}
    ): List<Match> = buildList {
        forEachMatch(text, query, options, checkCancelled) { match, _ ->
            add(match)
            true
        }
    }

    /** Skip a previously visited zero-width match when moving forward from its unchanged cursor. */
    @JvmStatic
    fun findNext(
        matches: List<Match>,
        selectionStart: Int,
        selectionEnd: Int,
        next: Boolean,
        skipCurrentZeroWidth: Boolean
    ): Match? {
        if (matches.isEmpty()) return null
        val selected = matches.indexOfFirst {
            it.start == selectionStart && it.end == selectionEnd
        }
        if (selected >= 0 && (selectionStart != selectionEnd || skipCurrentZeroWidth)) {
            val index = (selected + if (next) 1 else matches.size - 1) % matches.size
            return matches[index]
        }
        return if (next) {
            matches.firstOrNull { it.start >= selectionEnd } ?: matches.first()
        } else {
            matches.lastOrNull { it.start < selectionStart } ?: matches.last()
        }
    }

    @JvmStatic
    @JvmOverloads
    fun replace(
        text: String,
        query: String,
        replacement: String,
        options: SearchOptions,
        selectionStart: Int,
        selectionEnd: Int,
        replaceAll: Boolean,
        checkCancelled: () -> Unit = {}
    ): Result? {
        val target = if (replaceAll) {
            null
        } else {
            val matches = findMatches(text, query, options, checkCancelled)
            matches.firstOrNull { it.start == selectionStart && it.end == selectionEnd }
                ?: matches.firstOrNull { it.start >= selectionEnd }
                ?: matches.firstOrNull()
                ?: return null
        }
        val output = StringBuilder(text.length)
        var copiedThrough = 0
        var count = 0
        var cursor = selectionStart
        var delta = 0
        var backrefTokens: List<RegexBackrefToken>? = null
        forEachMatch(text, query, options, checkCancelled) { match, matcher ->
            if (!replaceAll && match != target) {
                return@forEachMatch true
            }
            val inserted = if (options.type == SearchOptions.TYPE_REGULAR_EXPRESSION
                && options.regexBackrefGrammar != null) {
                val regexMatcher = matcher!!
                val tokens = backrefTokens ?: parseBackrefs(
                    replacement, options.regexBackrefGrammar, regexMatcher.groupCount()
                ).also { backrefTokens = it }
                RegexBackrefHelper.computeReplacement(regexMatcher, tokens)
            } else {
                replacement
            }
            output.append(text, copiedThrough, match.start).append(inserted)
            copiedThrough = match.end
            ++count
            if (!replaceAll) {
                cursor = match.start + inserted.length
            } else if (match.end <= selectionStart) {
                cursor += inserted.length - (match.end - match.start)
            } else if (match.start < selectionStart) {
                cursor = match.start + delta + inserted.length
            }
            delta += inserted.length - (match.end - match.start)
            replaceAll
        }
        if (count == 0) return null
        output.append(text, copiedThrough, text.length)
        return Result(output.toString(), cursor.coerceIn(0, output.length), count)
    }

    /** A late result must never overwrite another document, newer input, or a read-only buffer. */
    @JvmStatic
    fun applyIfUnchanged(
        snapshot: Snapshot,
        content: Content,
        version: Long,
        editable: Boolean,
        result: Result
    ): Boolean {
        if (!editable || !snapshot.isCurrent(content, version)) return false
        if (result.text != snapshot.text) {
            content.replace(
                0, 0, content.lineCount - 1, content.getColumnCount(content.lineCount - 1), result.text
            )
        }
        return true
    }

    /** Sora 0.24.6's parser skips the character after a group, breaking adjacent $2$1 references. */
    private fun parseBackrefs(
        replacement: String,
        grammar: RegexBackrefGrammar,
        groupCount: Int
    ): List<RegexBackrefToken> = buildList {
        val literal = StringBuilder()
        var index = 0
        while (index < replacement.length) {
            val character = replacement[index]
            if (character == grammar.escapeChar) {
                require(index + 1 < replacement.length) { "Incomplete replacement escape" }
                val escaped = replacement[index + 1]
                if (escaped != grammar.escapeChar && escaped != grammar.backrefStartChar) {
                    literal.append(character)
                }
                literal.append(escaped)
                index += 2
                continue
            }
            val firstDigit = replacement.getOrNull(index + 1)
            if (character == grammar.backrefStartChar && firstDigit != null
                && firstDigit in '0'..'9' && firstDigit - '0' <= groupCount) {
                var group = firstDigit - '0'
                index += 2
                while (index < replacement.length && replacement[index] in '0'..'9') {
                    val nextGroup = group.toLong() * 10 + (replacement[index] - '0')
                    if (nextGroup > groupCount) break
                    group = nextGroup.toInt()
                    ++index
                }
                if (literal.isNotEmpty()) {
                    add(RegexBackrefToken(false, literal.toString(), -1))
                    literal.setLength(0)
                }
                add(RegexBackrefToken(true, null, group))
                continue
            }
            literal.append(character)
            ++index
        }
        if (literal.isNotEmpty()) add(RegexBackrefToken(false, literal.toString(), -1))
    }

    private inline fun forEachMatch(
        text: String,
        query: String,
        options: SearchOptions,
        checkCancelled: () -> Unit,
        action: (Match, Matcher?) -> Boolean
    ) {
        require(query.isNotEmpty())
        if (options.type == SearchOptions.TYPE_NORMAL) {
            var start = 0
            while (start < text.length) {
                checkCancelled()
                val found = TextUtils.indexOf(text, query, options.caseInsensitive, start)
                if (found < 0) return
                val match = Match(found, found + query.length)
                if (!action(match, null)) return
                start = match.end
            }
            return
        }
        val pattern = if (options.type == SearchOptions.TYPE_WHOLE_WORD) {
            "\\b${Pattern.quote(query)}\\b"
        } else {
            query
        }
        val flags = Pattern.MULTILINE or if (options.caseInsensitive) Pattern.CASE_INSENSITIVE else 0
        val matcher = Pattern.compile(pattern, flags).matcher(text)
        while (true) {
            checkCancelled()
            if (!matcher.find()) return
            val match = Match(matcher.start(), matcher.end())
            // Sora ends its scan at the first match reaching the end of the document.
            if (!action(match, matcher) || match.end == text.length) return
        }
    }
}
