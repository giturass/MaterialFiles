/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.viewer.text

import android.content.Context
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import io.github.rosemoe.sora.event.ContentChangeEvent
import io.github.rosemoe.sora.event.PublishSearchResultEvent
import io.github.rosemoe.sora.event.SelectionChangeEvent
import io.github.rosemoe.sora.text.Content
import io.github.rosemoe.sora.util.regex.RegexBackrefGrammar
import io.github.rosemoe.sora.widget.EditorSearcher.SearchOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.zhanghai.android.files.R
import me.zhanghai.android.files.databinding.SoraEditorFragmentBinding
import me.zhanghai.android.files.util.DataState
import java.util.regex.PatternSyntaxException

/** Sora highlights search results; replacement commits a validated snapshot as one undoable edit. */
class SoraEditorSearchController(
    private val binding: SoraEditorFragmentBinding,
    private val viewModel: SoraEditorViewModel,
    private val scope: CoroutineScope,
    private val onVisibilityChanged: () -> Unit
) {
    private val editor = binding.editor
    private val searcher = editor.searcher
    private var loaded = false
    private var destroyed = false
    private var changingOptions = false
    private var searching = false
    private var replacing = false
    private var applyingReplacement = false
    private var documentVersion = 0L
    private var queryGeneration = 0L
    private var operationGeneration = 0L
    private var operationJob: Job? = null
    private var selectedMatch: SelectedMatch? = null

    init {
        binding.searchPanel.isVisible = viewModel.searchVisible
        binding.searchQuery.setText(viewModel.searchQuery)
        binding.replacementText.setText(viewModel.replacementText)
        binding.searchMatchCase.isChecked = viewModel.searchMatchCase
        binding.searchWholeWord.isChecked = viewModel.searchWholeWord
        binding.searchRegex.isChecked = viewModel.searchRegex
        binding.searchQuery.doAfterTextChanged {
            viewModel.searchQuery = it.toString()
            search()
        }
        binding.replacementText.doAfterTextChanged {
            cancelOperation()
            viewModel.replacementText = it.toString()
            binding.replacementText.error = null
            updateActions()
        }
        binding.searchMatchCase.setOnCheckedChangeListener { _, checked ->
            viewModel.searchMatchCase = checked
            search()
        }
        binding.searchWholeWord.setOnCheckedChangeListener { _, checked ->
            if (changingOptions) return@setOnCheckedChangeListener
            changingOptions = true
            if (checked) binding.searchRegex.isChecked = false
            viewModel.searchWholeWord = checked
            viewModel.searchRegex = binding.searchRegex.isChecked
            changingOptions = false
            search()
        }
        binding.searchRegex.setOnCheckedChangeListener { _, checked ->
            if (changingOptions) return@setOnCheckedChangeListener
            changingOptions = true
            if (checked) binding.searchWholeWord.isChecked = false
            viewModel.searchRegex = checked
            viewModel.searchWholeWord = binding.searchWholeWord.isChecked
            changingOptions = false
            search()
        }
        binding.searchPrevious.setOnClickListener { navigate(false) }
        binding.searchNext.setOnClickListener { navigate(true) }
        binding.searchClose.setOnClickListener { close() }
        binding.replaceCurrent.setOnClickListener { replace(false) }
        binding.replaceAll.setOnClickListener { replace(true) }
        binding.searchQuery.setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_SEARCH) {
                navigate(true)
                true
            } else {
                false
            }
        }
        binding.replacementText.setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_DONE) {
                replace(false)
                true
            } else {
                false
            }
        }
        editor.subscribeEvent(PublishSearchResultEvent::class.java) { _, _ ->
            // Sora clears its worker pointer after publishing this event.
            val version = documentVersion
            val generation = queryGeneration
            editor.post {
                if (!destroyed && version == documentVersion && generation == queryGeneration) {
                    searching = false
                    updateActions()
                }
            }
        }
        editor.subscribeEvent(SelectionChangeEvent::class.java) { _, _ -> updateActions() }
        editor.subscribeEvent(ContentChangeEvent::class.java) { _, _ ->
            ++documentVersion
            selectedMatch = null
            if (!applyingReplacement) cancelOperation()
            searching = searcher.hasQuery()
            updateActions()
        }
        updateActions()
    }

    fun setLoaded(loaded: Boolean) {
        val changed = this.loaded != loaded
        this.loaded = loaded
        if (changed) search() else updateActions()
    }

    fun open(focusReplacement: Boolean = false) {
        if (!loaded) return
        viewModel.searchVisible = true
        binding.searchPanel.isVisible = true
        val cursor = editor.cursor
        if (cursor.isSelected && cursor.right - cursor.left <= 256) {
            val selection = editor.text.substring(cursor.left, cursor.right)
            if ('\n' !in selection && '\r' !in selection) {
                binding.searchQuery.setText(selection)
            }
        }
        search()
        val input = if (focusReplacement && editor.isEditable) {
            binding.replacementText
        } else {
            binding.searchQuery
        }
        input.requestFocus()
        input.selectAll()
        input.post {
            if (!destroyed && viewModel.searchVisible && input.hasFocus()) {
                val manager = input.context.getSystemService(Context.INPUT_METHOD_SERVICE)
                    as InputMethodManager
                manager.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
            }
        }
        onVisibilityChanged()
    }

    fun close() {
        cancelOperation()
        ++queryGeneration
        selectedMatch = null
        viewModel.searchVisible = false
        binding.searchPanel.isVisible = false
        searcher.stopSearch()
        searching = false
        editor.requestFocus()
        updateActions()
        onVisibilityChanged()
    }

    private fun search() {
        cancelOperation()
        ++queryGeneration
        selectedMatch = null
        binding.searchQuery.error = null
        binding.replacementText.error = null
        val query = viewModel.searchQuery
        if (!loaded || !viewModel.searchVisible || query.isEmpty()) {
            searcher.stopSearch()
            searching = false
            updateActions()
            return
        }
        try {
            searching = true
            searcher.search(query, searchOptions())
        } catch (e: PatternSyntaxException) {
            // A rejected query leaves Sora's old search active unless explicitly stopped.
            searcher.stopSearch()
            searching = false
            binding.searchQuery.error = editor.context.getString(R.string.sora_editor_invalid_regex)
        }
        updateActions()
    }

    private fun navigate(next: Boolean) {
        if (destroyed || !loaded || !searcher.hasQuery()) return
        cancelOperation()
        updateActions()
        val snapshot = snapshot()
        val generation = operationGeneration
        val query = viewModel.searchQuery
        val options = searchOptions()
        val actualLeft = editor.cursor.left
        val actualRight = editor.cursor.right
        val selected = currentSelectedMatch()
        val left = selected?.match?.start ?: actualLeft
        val right = selected?.match?.end ?: actualRight
        val skipCurrentZeroWidth = selected?.match?.let { it.start == it.end } == true
        operationJob = scope.launch {
            try {
                val matches = withContext(Dispatchers.Default) {
                    SoraEditorReplacements.findMatches(snapshot.text, query, options) { ensureActive() }
                }
                ensureActive()
                if (!operationIsCurrent(snapshot, generation)
                    || !snapshot.isCurrent(editor.text, documentVersion)
                    || editor.cursor.left != actualLeft || editor.cursor.right != actualRight) {
                    return@launch
                }
                val match = SoraEditorReplacements.findNext(
                    matches, left, right, next, skipCurrentZeroWidth
                ) ?: return@launch
                val focus = focusedSearchInput()
                selectedMatch = null
                val start = editor.text.indexer.getCharPosition(match.start)
                val end = editor.text.indexer.getCharPosition(match.end)
                editor.setSelectionRegion(
                    start.line, start.column.coerceAtMost(editor.text.getColumnCount(start.line)),
                    end.line, end.column.coerceAtMost(editor.text.getColumnCount(end.line)),
                    SelectionChangeEvent.CAUSE_SEARCH
                )
                // Sora normalizes selections inside CRLF and UTF-16 surrogate pairs. Retain the
                // logical regex range separately so navigation and replacement still use it.
                selectedMatch = SelectedMatch(
                    snapshot.content, documentVersion, queryGeneration, match, matches.indexOf(match),
                    editor.cursor.left, editor.cursor.right
                )
                restoreSearchFocus(focus)
                updateActions()
            } catch (e: CancellationException) {
                throw e
            } catch (e: PatternSyntaxException) {
                if (!destroyed) {
                    binding.searchQuery.error =
                        editor.context.getString(R.string.sora_editor_invalid_regex)
                }
            } finally {
                if (generation == operationGeneration) operationJob = null
            }
        }
    }

    private fun replace(all: Boolean) {
        if (destroyed || !loaded || !editor.isEditable || !searcher.hasQuery()) return
        cancelOperation()
        val snapshot = snapshot()
        val generation = operationGeneration
        val query = viewModel.searchQuery
        val replacement = viewModel.replacementText
        val options = searchOptions()
        val actualLeft = editor.cursor.left
        val actualRight = editor.cursor.right
        val selected = currentSelectedMatch()
        val left = selected?.match?.start ?: actualLeft
        val right = selected?.match?.end ?: actualRight
        replacing = true
        operationJob = scope.launch {
            try {
                val result = withContext(Dispatchers.Default) {
                    SoraEditorReplacements.replace(
                        snapshot.text, query, replacement, options, left, right, all
                    ) { ensureActive() }
                } ?: return@launch
                ensureActive()
                if (!operationIsCurrent(snapshot, generation)
                    || !all && (editor.cursor.left != actualLeft || editor.cursor.right != actualRight)) {
                    return@launch
                }
                val focus = focusedSearchInput()
                applyingReplacement = true
                val applied = try {
                    SoraEditorReplacements.applyIfUnchanged(
                        snapshot, editor.text, documentVersion,
                        editor.isEditable && !viewModel.isReadOnly, result
                    )
                } finally {
                    applyingReplacement = false
                }
                if (applied) {
                    val position = editor.text.indexer.getCharPosition(result.cursor)
                    editor.setSelection(
                        position.line,
                        position.column.coerceAtMost(editor.text.getColumnCount(position.line))
                    )
                    restoreSearchFocus(focus)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: PatternSyntaxException) {
                if (!destroyed) {
                    binding.searchQuery.error =
                        editor.context.getString(R.string.sora_editor_invalid_regex)
                }
            } catch (e: IllegalArgumentException) {
                showReplacementError()
            } catch (e: IndexOutOfBoundsException) {
                showReplacementError()
            } finally {
                if (generation == operationGeneration) {
                    replacing = false
                    operationJob = null
                    updateActions()
                }
            }
        }
        updateActions()
    }

    private fun showReplacementError() {
        if (!destroyed) {
            binding.replacementText.error =
                editor.context.getString(R.string.sora_editor_invalid_replacement)
        }
    }

    private fun searchOptions(): SearchOptions {
        val type = when {
            viewModel.searchRegex -> SearchOptions.TYPE_REGULAR_EXPRESSION
            viewModel.searchWholeWord -> SearchOptions.TYPE_WHOLE_WORD
            else -> SearchOptions.TYPE_NORMAL
        }
        return SearchOptions(type, !viewModel.searchMatchCase, RegexBackrefGrammar.DEFAULT)
    }

    private fun snapshot() = SoraEditorReplacements.Snapshot(
        editor.text, documentVersion, editor.text.toString()
    )

    private fun operationIsCurrent(snapshot: SoraEditorReplacements.Snapshot, generation: Long) =
        !destroyed && loaded && viewModel.searchVisible && generation == operationGeneration &&
            viewModel.textState.value is DataState.Success &&
            snapshot.content === viewModel.textState.value.data &&
            snapshot.content === editor.text && snapshot.version == documentVersion

    private fun cancelOperation() {
        ++operationGeneration
        operationJob?.cancel()
        operationJob = null
        replacing = false
    }

    private fun focusedSearchInput(): View? = when {
        binding.searchQuery.hasFocus() -> binding.searchQuery
        binding.replacementText.hasFocus() -> binding.replacementText
        else -> null
    }

    private fun restoreSearchFocus(view: View?) {
        if (viewModel.searchVisible && view?.isEnabled == true) view.requestFocus()
    }

    private fun currentSelectedMatch(): SelectedMatch? = selectedMatch?.takeIf {
        it.content === editor.text && it.version == documentVersion &&
            it.queryGeneration == queryGeneration &&
            it.cursorLeft == editor.cursor.left && it.cursorRight == editor.cursor.right
    }

    fun updateActions() {
        if (destroyed) return
        val count = if (searcher.hasQuery()) searcher.matchedPositionCount else 0
        val index = if (count > 0) {
            currentSelectedMatch()?.index ?: searcher.currentMatchedPositionIndex
        } else {
            -1
        }
        binding.searchMatchCount.text = when {
            replacing -> editor.context.getString(R.string.sora_editor_replacing)
            searching -> editor.context.getString(R.string.sora_editor_searching)
            count == 0 -> editor.context.getString(R.string.text_editor_search_no_matches)
            else -> editor.context.getString(R.string.sora_editor_search_count, index + 1, count)
        }
        binding.searchQuery.isEnabled = loaded
        binding.searchMatchCase.isEnabled = loaded
        binding.searchWholeWord.isEnabled = loaded
        binding.searchRegex.isEnabled = loaded
        binding.searchNext.isEnabled = loaded && count > 0
        binding.searchPrevious.isEnabled = loaded && count > 0
        val canReplace = loaded && editor.isEditable
        binding.replacementText.isEnabled = canReplace
        binding.replaceCurrent.isEnabled = canReplace && count > 0 && !replacing
        binding.replaceAll.isEnabled = canReplace && count > 0 && !replacing
    }

    fun destroy() {
        destroyed = true
        cancelOperation()
        searcher.stopSearch()
    }

    private data class SelectedMatch(
        val content: Content,
        val version: Long,
        val queryGeneration: Long,
        val match: SoraEditorReplacements.Match,
        val index: Int,
        val cursorLeft: Int,
        val cursorRight: Int
    )
}
