/*
 * Copyright (c) 2019 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.viewer.text

import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.TooltipCompat
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.slider.Slider
import io.github.rosemoe.sora.event.ContentChangeEvent
import io.github.rosemoe.sora.event.PublishSearchResultEvent
import io.github.rosemoe.sora.event.SelectionChangeEvent
import io.github.rosemoe.sora.event.TextSizeChangeEvent
import io.github.rosemoe.sora.util.regex.RegexBackrefGrammar
import io.github.rosemoe.sora.widget.EditorSearcher
import java8.nio.file.Path
import kotlinx.coroutines.launch
import kotlinx.parcelize.Parcelize
import me.zhanghai.android.files.R
import me.zhanghai.android.files.databinding.TextEditorFragmentBinding
import me.zhanghai.android.files.settings.Settings
import me.zhanghai.android.files.util.ActionState
import me.zhanghai.android.files.util.DataState
import me.zhanghai.android.files.util.ParcelableArgs
import me.zhanghai.android.files.util.addOnBackPressedCallback
import me.zhanghai.android.files.util.args
import me.zhanghai.android.files.util.extraPath
import me.zhanghai.android.files.util.fadeInUnsafe
import me.zhanghai.android.files.util.fadeOutUnsafe
import me.zhanghai.android.files.util.hideSoftInput
import me.zhanghai.android.files.util.isReady
import me.zhanghai.android.files.util.showSoftInput
import me.zhanghai.android.files.util.showToast
import me.zhanghai.android.files.util.valueCompat
import me.zhanghai.android.files.util.viewModels
import java.nio.charset.Charset
import java.util.regex.PatternSyntaxException
import kotlin.math.roundToInt

class TextEditorFragment : Fragment(), ConfirmReloadDialogFragment.Listener,
    ConfirmCloseDialogFragment.Listener {
    private val args by args<Args>()
    private lateinit var argsFile: Path

    private lateinit var binding: TextEditorFragmentBinding
    private lateinit var menuBinding: MenuBinding

    private val viewModel by viewModels { { TextEditorViewModel(argsFile) } }

    private lateinit var onBackPressedCallback: OnBackPressedCallback

    private var isSettingText = false
    private var hasEditorText = false
    private var pendingEditorState: TextEditorViewModel.EditorState? = null
    private var activeSearchQuery: String? = null
    private var activeSearchOptions: SearchOptionsState? = null
    private var isSearchPending = false
    private var isSearchRegexInvalid = false
    private var pendingSearchAction: SearchAction? = null
    private var updatingSearchOptions = false
    private var restoringSearchState = false
    private var pendingFontSize: Int? = null
    private val persistFontSizeRunnable = Runnable { persistPendingFontSize() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setHasOptionsMenu(true)

        // Created here rather than inside the collector below, because onSupportNavigateUp() may
        // reach it before this fragment has ever been started.
        onBackPressedCallback = object : OnBackPressedCallback(false) {
            override fun handleOnBackPressed() {
                if (binding.searchPanel.isVisible) {
                    hideSearch()
                } else {
                    ConfirmCloseDialogFragment.show(this@TextEditorFragment)
                }
            }
        }
        lifecycleScope.launch {
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { viewModel.isTextChanged.collect { updateBackPressedCallback() } }
                launch { viewModel.encoding.collect { onEncodingChanged() } }
                launch { viewModel.textState.collect { onTextStateChanged(it) } }
                launch { viewModel.isTextChanged.collect { onIsTextChangedChanged() } }
                launch { viewModel.writeFileState.collect { onWriteFileStateChanged(it) } }
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View =
        TextEditorFragmentBinding.inflate(inflater, container, false)
            .also { binding = it }
            .root

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val argsFile = args.intent.extraPath
        if (argsFile == null) {
            finish()
            return
        }
        this.argsFile = argsFile

        addOnBackPressedCallback(onBackPressedCallback)

        val activity = requireActivity() as AppCompatActivity
        activity.setSupportActionBar(binding.toolbar)
        activity.supportActionBar!!.setDisplayHomeAsUpEnabled(true)

        val displayMetrics = resources.displayMetrics
        binding.editor.apply {
            isSaveEnabled = false
            setTypefaceText(Typeface.MONOSPACE)
            setTypefaceLineNumber(Typeface.MONOSPACE)
            setTextSize(Settings.TEXT_EDITOR_FONT_SIZE.valueCompat.toFloat())
            setScaleTextSizes(
                TypedValue.applyDimension(
                    TypedValue.COMPLEX_UNIT_SP, MIN_FONT_SIZE.toFloat(), displayMetrics
                ),
                TypedValue.applyDimension(
                    TypedValue.COMPLEX_UNIT_SP, MAX_FONT_SIZE.toFloat(), displayMetrics
                )
            )
            setWordwrap(Settings.TEXT_EDITOR_WORD_WRAP.valueCompat)
            setLineNumberEnabled(Settings.TEXT_EDITOR_LINE_NUMBERS.valueCompat)
            setHighlightCurrentLine(true)
            setHighlightCurrentBlock(true)
            setFirstLineNumberAlwaysVisible(true)
            setScrollBarEnabled(true)
            setScalable(true)
            setTabWidth(4)
            subscribeEvent(ContentChangeEvent::class.java) { _, _ ->
                if (activeSearchQuery != null) {
                    isSearchPending = true
                    updateSearchResultText()
                }
                if (isSettingText || viewModel.textState.value !is DataState.Success) {
                    return@subscribeEvent
                }
                viewModel.isTextChanged.value = true
                updateEditMenuItems()
            }
            subscribeEvent(PublishSearchResultEvent::class.java) { _, _ ->
                isSearchPending = false
                updateSearchResultText()
                val action = pendingSearchAction
                pendingSearchAction = null
                if (activeSearchQuery != null && action != null) {
                    performSearchAction(action)
                }
            }
            subscribeEvent(SelectionChangeEvent::class.java) { _, _ ->
                updateCursorStatus()
                updateSearchResultText()
                updateEditMenuItems()
            }
            subscribeEvent(TextSizeChangeEvent::class.java) { event, _ ->
                val size = (event.newTextSize / displayMetrics.scaledDensity).roundToInt()
                    .coerceIn(MIN_FONT_SIZE, MAX_FONT_SIZE)
                pendingFontSize = size
                removeCallbacks(persistFontSizeRunnable)
                postDelayed(persistFontSizeRunnable, FONT_SIZE_SAVE_DELAY_MILLIS)
            }
        }
        viewModel.languageId = TextEditorLanguage.configure(
            binding.editor,
            argsFile.fileName.toString(),
            requireContext(),
            viewModel.languageId
        ).id
        updateLanguageStatus()
        val density = displayMetrics.density
        binding.editor.apply {
            setDividerWidth(density)
            setDividerMargin(4 * density, 8 * density)
        }
        updateCursorStatus()
        pendingEditorState = viewModel.removeEditorState()

        binding.findEdit.doAfterTextChanged {
            if (!restoringSearchState && binding.searchPanel.isVisible) {
                updateSearch()
            }
        }
        binding.findEdit.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                goToNextMatch()
                true
            } else {
                false
            }
        }
        binding.replaceEdit.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                replaceCurrentMatch()
                true
            } else {
                false
            }
        }
        binding.previousButton.setOnClickListener { goToPreviousMatch() }
        binding.nextButton.setOnClickListener { goToNextMatch() }
        binding.closeSearchButton.setOnClickListener { hideSearch() }
        binding.toggleReplaceButton.setOnClickListener {
            binding.replaceSection.isVisible = !binding.replaceSection.isVisible
            if (binding.replaceSection.isVisible) {
                binding.replaceEdit.requestFocus()
                binding.replaceEdit.post { binding.replaceEdit.showSoftInput() }
            } else {
                binding.findEdit.requestFocus()
            }
        }
        binding.replaceButton.setOnClickListener { replaceCurrentMatch() }
        binding.replaceAllButton.setOnClickListener { replaceAllMatches() }
        binding.languageStatus.setOnClickListener { showLanguageDialog() }
        binding.encodingStatus.setOnClickListener { showEncodingDialog() }
        binding.matchCaseChip.setOnCheckedChangeListener { _, _ -> onSearchOptionsChanged() }
        binding.wholeWordChip.setOnCheckedChangeListener { _, checked ->
            if (updatingSearchOptions) {
                return@setOnCheckedChangeListener
            }
            if (checked && binding.regexChip.isChecked) {
                updatingSearchOptions = true
                binding.regexChip.isChecked = false
                updatingSearchOptions = false
            }
            onSearchOptionsChanged()
        }
        binding.regexChip.setOnCheckedChangeListener { _, checked ->
            if (updatingSearchOptions) {
                return@setOnCheckedChangeListener
            }
            if (checked && binding.wholeWordChip.isChecked) {
                updatingSearchOptions = true
                binding.wholeWordChip.isChecked = false
                updatingSearchOptions = false
            }
            onSearchOptionsChanged()
        }
        TooltipCompat.setTooltipText(
            binding.previousButton, getString(R.string.text_editor_previous_match)
        )
        TooltipCompat.setTooltipText(
            binding.nextButton, getString(R.string.text_editor_next_match)
        )
        TooltipCompat.setTooltipText(binding.closeSearchButton, getString(R.string.close))

        val searchVisible = savedInstanceState?.getBoolean(STATE_SEARCH_VISIBLE) == true
        binding.searchPanel.isVisible = searchVisible
        binding.replaceSection.isVisible =
            savedInstanceState?.getBoolean(STATE_REPLACE_VISIBLE) == true
        restoringSearchState = true
        binding.findEdit.setText(savedInstanceState?.getString(STATE_SEARCH_QUERY).orEmpty())
        binding.replaceEdit.setText(savedInstanceState?.getString(STATE_REPLACEMENT).orEmpty())
        updatingSearchOptions = true
        binding.matchCaseChip.isChecked =
            savedInstanceState?.getBoolean(STATE_MATCH_CASE) == true
        binding.wholeWordChip.isChecked =
            savedInstanceState?.getBoolean(STATE_WHOLE_WORD) == true
        binding.regexChip.isChecked = savedInstanceState?.getBoolean(STATE_REGEX) == true
        updatingSearchOptions = false
        restoringSearchState = false
        if (searchVisible) {
            updateSearch(force = true)
        } else {
            updateSearchResultText()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        saveEditorState()
        outState.putBoolean(STATE_SEARCH_VISIBLE, binding.searchPanel.isVisible)
        outState.putBoolean(STATE_REPLACE_VISIBLE, binding.replaceSection.isVisible)
        outState.putString(STATE_SEARCH_QUERY, binding.findEdit.text?.toString())
        outState.putString(STATE_REPLACEMENT, binding.replaceEdit.text?.toString())
        outState.putBoolean(STATE_MATCH_CASE, binding.matchCaseChip.isChecked)
        outState.putBoolean(STATE_WHOLE_WORD, binding.wholeWordChip.isChecked)
        outState.putBoolean(STATE_REGEX, binding.regexChip.isChecked)
    }

    override fun onDestroyView() {
        if (this::binding.isInitialized && !binding.editor.isReleased) {
            binding.editor.removeCallbacks(persistFontSizeRunnable)
            persistPendingFontSize()
            saveEditorState()
            binding.editor.release()
        }
        activeSearchQuery = null
        activeSearchOptions = null
        isSearchPending = false
        isSearchRegexInvalid = false
        pendingSearchAction = null
        super.onDestroyView()
    }

    private fun saveEditorState() {
        if (!hasEditorText) {
            pendingEditorState?.let(viewModel::setEditorState)
            return
        }
        val state = binding.editor.cursor.let { cursor ->
            TextEditorViewModel.EditorState(
                // Only kept when it has actually been edited: an untouched document is restored
                // from the view model's own copy instead of being held a second time.
                binding.editor.text.toString().takeIf { viewModel.isTextChanged.value },
                cursor.leftLine,
                cursor.leftColumn,
                cursor.rightLine,
                cursor.rightColumn,
                binding.editor.offsetX,
                binding.editor.offsetY
            )
        }
        viewModel.setEditorState(state)
    }

    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        super.onCreateOptionsMenu(menu, inflater)
        menuBinding = MenuBinding.inflate(menu, inflater)
        updateEncodingMenuItems()
        updateEditMenuItems()
    }

    override fun onPrepareOptionsMenu(menu: Menu) {
        super.onPrepareOptionsMenu(menu)
        updateSaveMenuItem()
        updateEncodingMenuItems()
        updateEditMenuItems()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean =
        when (item.itemId) {
            R.id.action_save -> {
                save()
                true
            }
            R.id.action_search -> {
                showSearch()
                true
            }
            R.id.action_undo -> {
                if (binding.editor.canUndo()) {
                    binding.editor.undo()
                    updateEditMenuItems()
                }
                true
            }
            R.id.action_redo -> {
                if (binding.editor.canRedo()) {
                    binding.editor.redo()
                    updateEditMenuItems()
                }
                true
            }
            R.id.action_reload -> {
                onReload()
                true
            }
            R.id.action_encoding -> {
                showEncodingDialog()
                true
            }
            R.id.action_language -> {
                showLanguageDialog()
                true
            }
            R.id.action_word_wrap -> {
                val enabled = !binding.editor.isWordwrap
                binding.editor.setWordwrap(enabled)
                Settings.TEXT_EDITOR_WORD_WRAP.putValue(enabled)
                updateEditMenuItems()
                true
            }
            R.id.action_line_numbers -> {
                val enabled = !binding.editor.isLineNumberEnabled
                binding.editor.isLineNumberEnabled = enabled
                Settings.TEXT_EDITOR_LINE_NUMBERS.putValue(enabled)
                updateEditMenuItems()
                true
            }
            R.id.action_font_size -> {
                showFontSizeDialog()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }

    fun onSupportNavigateUp(): Boolean {
        if (onBackPressedCallback.isEnabled) {
            onBackPressedCallback.handleOnBackPressed()
            return true
        }
        return false
    }

    override fun finish() {
        requireActivity().finish()
    }

    private fun onEncodingChanged() {
        updateEncodingMenuItems()
        if (this::binding.isInitialized) {
            binding.encodingStatus.text = viewModel.encoding.value.name()
        }
    }

    private fun updateEncodingMenuItems() {
        if (!this::menuBinding.isInitialized) {
            return
        }
        val charsetName = viewModel.encoding.value.name()
        menuBinding.encodingItem.title = getString(
            R.string.text_editor_encoding_value_format, charsetName
        )
    }

    private fun onTextStateChanged(state: DataState<String>) {
        updateTitle()
        updateSaveMenuItem()
        when (state) {
            is DataState.Loading -> {
                if (hasEditorText) {
                    binding.progress.fadeOutUnsafe()
                    binding.errorText.fadeOutUnsafe()
                    binding.editor.fadeInUnsafe()
                } else {
                    binding.progress.fadeInUnsafe()
                    binding.errorText.fadeOutUnsafe()
                    binding.editor.fadeOutUnsafe()
                }
            }
            is DataState.Success -> {
                binding.progress.fadeOutUnsafe()
                binding.errorText.fadeOutUnsafe()
                binding.editor.fadeInUnsafe()
                val editorState = pendingEditorState
                if (editorState != null) {
                    val changed = viewModel.isTextChanged.value
                    setText(editorState.text ?: state.data, changed)
                    restoreSelection(editorState)
                    pendingEditorState = null
                } else if (!hasEditorText ||
                    (!viewModel.isTextChanged.value &&
                        binding.editor.text.toString() != state.data)
                ) {
                    setText(state.data, false)
                }
            }
            is DataState.Error -> {
                state.throwable.printStackTrace()
                binding.progress.fadeOutUnsafe()
                binding.errorText.fadeInUnsafe()
                binding.errorText.text = state.throwable.toString()
                binding.editor.fadeOutUnsafe()
            }
        }
    }

    private fun setText(text: String?, changed: Boolean) {
        isSettingText = true
        try {
            binding.editor.setText(text)
        } finally {
            isSettingText = false
        }
        hasEditorText = true
        viewModel.isTextChanged.value = changed
        updateCursorStatus()
        updateEditMenuItems()
    }

    private fun restoreSelection(state: TextEditorViewModel.EditorState) {
        try {
            binding.editor.setSelectionRegion(
                state.leftLine,
                state.leftColumn,
                state.rightLine,
                state.rightColumn
            )
            binding.editor.post {
                if (!binding.editor.isReleased) {
                    binding.editor.scroller.startScroll(
                        state.offsetX, state.offsetY, 0, 0, 0
                    )
                    binding.editor.scroller.abortAnimation()
                    binding.editor.postInvalidate()
                }
            }
        } catch (e: IndexOutOfBoundsException) {
            e.printStackTrace()
        }
    }

    private fun onIsTextChangedChanged() {
        updateTitle()
    }

    private fun updateTitle() {
        val fileName = viewModel.file.value.fileName.toString()
        requireActivity().title = getString(
            if (viewModel.isTextChanged.value) {
                R.string.text_editor_title_changed_format
            } else {
                R.string.text_editor_title_format
            }, fileName
        )
    }

    private fun onReload() {
        if (viewModel.isTextChanged.value) {
            ConfirmReloadDialogFragment.show(this)
        } else {
            reload()
        }
    }

    override fun reload() {
        pendingEditorState = null
        hasEditorText = false
        viewModel.isTextChanged.value = false
        viewModel.reload()
    }

    private fun save() {
        viewModel.writeFile(argsFile, binding.editor.text.toString(), requireContext())
    }

    private fun onWriteFileStateChanged(state: ActionState<Pair<Path, String>, Unit>) {
        when (state) {
            is ActionState.Ready, is ActionState.Running -> updateSaveMenuItem()
            is ActionState.Success -> {
                showToast(R.string.text_editor_save_success)
                viewModel.finishWritingFile()
                if (binding.editor.text.toString() == state.argument.second) {
                    viewModel.isTextChanged.value = false
                }
            }
            is ActionState.Error -> viewModel.finishWritingFile()
        }
    }

    private fun updateSaveMenuItem() {
        if (!this::menuBinding.isInitialized) {
            return
        }
        menuBinding.saveItem.isEnabled = viewModel.writeFileState.value.isReady
            && viewModel.textState.value is DataState.Success
    }

    private fun updateEditMenuItems() {
        if (!this::menuBinding.isInitialized || !this::binding.isInitialized) {
            return
        }
        menuBinding.undoItem.isEnabled = binding.editor.canUndo()
        menuBinding.redoItem.isEnabled = binding.editor.canRedo()
        menuBinding.wordWrapItem.isChecked = binding.editor.isWordwrap
        menuBinding.lineNumbersItem.isChecked = binding.editor.isLineNumberEnabled
    }

    private fun updateCursorStatus() {
        if (!this::binding.isInitialized || binding.editor.isReleased) {
            return
        }
        val cursor = binding.editor.cursor
        binding.cursorStatus.text = getString(
            R.string.text_editor_cursor_position_format,
            cursor.leftLine + 1,
            cursor.leftColumn + 1
        )
    }

    private fun showSearch() {
        if (!binding.searchPanel.isVisible) {
            binding.searchPanel.isVisible = true
            val cursor = binding.editor.cursor
            if (binding.findEdit.text.isNullOrEmpty() && cursor.isSelected) {
                binding.findEdit.setText(
                    binding.editor.text.substring(cursor.left, cursor.right)
                )
            }
            updateBackPressedCallback()
        }
        binding.findEdit.requestFocus()
        binding.findEdit.selectAll()
        binding.findEdit.post { binding.findEdit.showSoftInput() }
        updateSearch(force = activeSearchQuery == null)
    }

    private fun hideSearch() {
        binding.searchPanel.isVisible = false
        binding.findEdit.hideSoftInput()
        binding.editor.searcher.stopSearch()
        activeSearchQuery = null
        activeSearchOptions = null
        isSearchPending = false
        isSearchRegexInvalid = false
        pendingSearchAction = null
        binding.editor.requestFocus()
        updateSearchResultText()
        updateBackPressedCallback()
    }

    private fun currentSearchOptions(): SearchOptionsState = SearchOptionsState(
        caseSensitive = binding.matchCaseChip.isChecked,
        wholeWord = binding.wholeWordChip.isChecked,
        regularExpression = binding.regexChip.isChecked
    )

    private fun onSearchOptionsChanged() {
        if (!updatingSearchOptions && binding.searchPanel.isVisible) {
            updateSearch(force = true)
        }
    }

    private fun updateSearch(force: Boolean = false): Boolean {
        val query = binding.findEdit.text?.toString().orEmpty()
        if (query.isEmpty()) {
            if (activeSearchQuery != null) {
                binding.editor.searcher.stopSearch()
                activeSearchQuery = null
                activeSearchOptions = null
            }
            isSearchPending = false
            isSearchRegexInvalid = false
            pendingSearchAction = null
            updateSearchResultText()
            return false
        }
        val options = currentSearchOptions()
        if (force || query != activeSearchQuery || options != activeSearchOptions) {
            activeSearchQuery = query
            activeSearchOptions = options
            isSearchPending = true
            isSearchRegexInvalid = false
            pendingSearchAction = null
            try {
                binding.editor.searcher.search(query, options.toEditorOptions())
            } catch (_: PatternSyntaxException) {
                isSearchPending = false
                isSearchRegexInvalid = true
                binding.editor.searcher.stopSearch()
                updateSearchResultText()
            } catch (_: IllegalArgumentException) {
                isSearchPending = false
                isSearchRegexInvalid = options.regularExpression
                binding.editor.searcher.stopSearch()
                updateSearchResultText()
            }
        }
        updateSearchResultText()
        return true
    }

    private fun updateSearchResultText() {
        if (!this::binding.isInitialized) {
            return
        }
        val query = binding.findEdit.text?.toString().orEmpty()
        if (query.isEmpty()) {
            binding.searchResultText.text = getString(R.string.text_editor_search_hint)
            setSearchControlsEnabled(false)
            return
        }
        if (isSearchRegexInvalid) {
            binding.searchResultText.text = getString(R.string.text_editor_search_invalid_regex)
            setSearchControlsEnabled(false)
            return
        }
        if (isSearchPending) {
            binding.searchResultText.text = getString(R.string.text_editor_searching)
            setSearchControlsEnabled(false)
            return
        }
        val searcher = binding.editor.searcher
        val count = try {
            searcher.getMatchedPositionCount()
        } catch (_: IllegalStateException) {
            0
        }
        if (count == 0) {
            binding.searchResultText.text = getString(R.string.text_editor_search_no_results)
            setSearchControlsEnabled(false)
            return
        }
        val current = try {
            searcher.getCurrentMatchedPositionIndex()
        } catch (_: IllegalStateException) {
            -1
        }
        binding.searchResultText.text = if (current >= 0) {
            getString(R.string.text_editor_search_result_format, current + 1, count)
        } else {
            getString(R.string.text_editor_search_matches_count, count)
        }
        setSearchControlsEnabled(true)
    }

    private fun setSearchControlsEnabled(enabled: Boolean) {
        binding.previousButton.isEnabled = enabled
        binding.nextButton.isEnabled = enabled
        binding.replaceButton.isEnabled = enabled
        binding.replaceAllButton.isEnabled = enabled
    }

    private fun goToPreviousMatch() {
        runSearchAction(SearchAction.Previous)
    }

    private fun goToNextMatch() {
        runSearchAction(SearchAction.Next)
    }

    private fun replaceCurrentMatch() {
        runSearchAction(
            SearchAction.ReplaceCurrent(binding.replaceEdit.text?.toString().orEmpty())
        )
    }

    private fun replaceAllMatches() {
        runSearchAction(SearchAction.ReplaceAll(binding.replaceEdit.text?.toString().orEmpty()))
    }

    private fun runSearchAction(action: SearchAction) {
        if (!updateSearch()) {
            return
        }
        if (isSearchPending) {
            pendingSearchAction = action
        } else {
            performSearchAction(action)
        }
    }

    private fun performSearchAction(action: SearchAction) {
        try {
            when (action) {
                SearchAction.Previous -> binding.editor.searcher.gotoPrevious()
                SearchAction.Next -> binding.editor.searcher.gotoNext()
                is SearchAction.ReplaceCurrent -> {
                    if (!binding.editor.searcher.isMatchedPositionSelected()) {
                        binding.editor.searcher.gotoNext()
                    }
                    binding.editor.searcher.replaceCurrentMatch(action.replacement)
                }
                is SearchAction.ReplaceAll ->
                    binding.editor.searcher.replaceAll(action.replacement) {
                        updateSearchResultText()
                    }
            }
        } catch (_: IllegalStateException) {
            // Search results can be invalidated by an edit between tapping the button and this
            // callback. The new PublishSearchResultEvent will enable the controls again.
        }
        binding.editor.post { updateSearchResultText() }
    }

    private fun showFontSizeDialog() {
        val currentSize = Settings.TEXT_EDITOR_FONT_SIZE.valueCompat
        val padding = (24 * resources.displayMetrics.density).toInt()
        val preview = TextView(requireContext()).apply {
            text = "Aa 123  { }  < >"
            typeface = Typeface.MONOSPACE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, currentSize.toFloat())
        }
        val slider = Slider(requireContext()).apply {
            valueFrom = MIN_FONT_SIZE.toFloat()
            valueTo = MAX_FONT_SIZE.toFloat()
            stepSize = 1f
            value = currentSize.coerceIn(MIN_FONT_SIZE, MAX_FONT_SIZE).toFloat()
            addOnChangeListener { _, value, _ ->
                preview.setTextSize(TypedValue.COMPLEX_UNIT_SP, value)
            }
        }
        val content = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding / 2, padding, 0)
            addView(
                preview,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            )
            addView(
                slider,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            )
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.text_editor_font_size)
            .setView(content)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val size = slider.value.toInt()
                Settings.TEXT_EDITOR_FONT_SIZE.putValue(size)
                binding.editor.setTextSize(size.toFloat())
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showLanguageDialog() {
        val definitions = TextEditorLanguage.definitions
        val labels = definitions.map { getString(it.nameRes) }.toTypedArray()
        val selected = definitions.indexOfFirst { it.id == viewModel.languageId }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.text_editor_language)
            .setSingleChoiceItems(labels, selected) { dialog, which ->
                val definition = definitions[which]
                viewModel.languageId = TextEditorLanguage.configure(
                    binding.editor,
                    argsFile.fileName.toString(),
                    requireContext(),
                    definition.id
                ).id
                updateLanguageStatus()
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showEncodingDialog() {
        val charsets = orderedCharsets
        val labels = charsets.map { it.displayName() }.toTypedArray()
        val selected = charsets.indexOfFirst { it.name() == viewModel.encoding.value.name() }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.text_editor_encoding)
            .setSingleChoiceItems(labels, selected) { dialog, which ->
                viewModel.encoding.value = charsets[which]
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun updateBackPressedCallback() {
        if (this::onBackPressedCallback.isInitialized) {
            onBackPressedCallback.isEnabled =
                binding.searchPanel.isVisible || viewModel.isTextChanged.value
        }
    }

    private fun updateLanguageStatus() {
        if (!this::binding.isInitialized) {
            return
        }
        binding.languageStatus.text = getString(TextEditorLanguage.find(viewModel.languageId).nameRes)
    }

    private fun persistPendingFontSize() {
        val size = pendingFontSize ?: return
        pendingFontSize = null
        if (Settings.TEXT_EDITOR_FONT_SIZE.valueCompat != size) {
            Settings.TEXT_EDITOR_FONT_SIZE.putValue(size)
        }
    }

    @Parcelize
    class Args(val intent: Intent) : ParcelableArgs

    private class MenuBinding private constructor(
        val saveItem: MenuItem,
        val undoItem: MenuItem,
        val redoItem: MenuItem,
        val encodingItem: MenuItem,
        val wordWrapItem: MenuItem,
        val lineNumbersItem: MenuItem
    ) {
        companion object {
            fun inflate(menu: Menu, inflater: MenuInflater): MenuBinding {
                inflater.inflate(R.menu.text_editor, menu)
                return MenuBinding(
                    menu.findItem(R.id.action_save),
                    menu.findItem(R.id.action_undo),
                    menu.findItem(R.id.action_redo),
                    menu.findItem(R.id.action_encoding),
                    menu.findItem(R.id.action_word_wrap),
                    menu.findItem(R.id.action_line_numbers)
                )
            }
        }
    }

    companion object {
        private const val STATE_SEARCH_VISIBLE = "searchVisible"
        private const val STATE_REPLACE_VISIBLE = "replaceVisible"
        private const val STATE_SEARCH_QUERY = "searchQuery"
        private const val STATE_REPLACEMENT = "replacement"
        private const val STATE_MATCH_CASE = "matchCase"
        private const val STATE_WHOLE_WORD = "wholeWord"
        private const val STATE_REGEX = "regex"
        private const val MIN_FONT_SIZE = 10
        private const val MAX_FONT_SIZE = 40
        private const val FONT_SIZE_SAVE_DELAY_MILLIS = 250L

        /** Offered first in the encoding picker, in this order. Anything missing is skipped. */
        private val COMMON_CHARSET_NAMES = listOf(
            "UTF-8", "GB18030", "GBK", "Big5", "Shift_JIS", "EUC-KR", "UTF-16", "UTF-16LE",
            "UTF-16BE", "windows-1252", "ISO-8859-1", "US-ASCII"
        )

        /**
         * The charsets the encoding picker offers, in the order it shows them.
         *
         * Built once: the platform ships around 170 of them in an alphabetical order that buries
         * the handful anyone actually picks, and enumerating them all is not something to do on the
         * main thread every time the dialog opens. Which charsets exist cannot change while the
         * process is alive, and the labels are formatted per dialog so a locale change is still
         * picked up.
         */
        private val orderedCharsets: List<Charset> by lazy {
            val available = Charset.availableCharsets().values.toList()
            val common = COMMON_CHARSET_NAMES.mapNotNull { name ->
                available.firstOrNull { it.name().equals(name, ignoreCase = true) }
            }
            val commonSet = common.toSet()
            common + available.filterNot { it in commonSet }
        }
    }

    private data class SearchOptionsState(
        val caseSensitive: Boolean,
        val wholeWord: Boolean,
        val regularExpression: Boolean
    ) {
        fun toEditorOptions(): EditorSearcher.SearchOptions = EditorSearcher.SearchOptions(
            when {
                regularExpression -> EditorSearcher.SearchOptions.TYPE_REGULAR_EXPRESSION
                wholeWord -> EditorSearcher.SearchOptions.TYPE_WHOLE_WORD
                else -> EditorSearcher.SearchOptions.TYPE_NORMAL
            },
            !caseSensitive,
            if (regularExpression) RegexBackrefGrammar.DEFAULT else null
        )
    }

    private sealed interface SearchAction {
        data object Previous : SearchAction
        data object Next : SearchAction
        data class ReplaceCurrent(val replacement: String) : SearchAction
        data class ReplaceAll(val replacement: String) : SearchAction
    }
}
