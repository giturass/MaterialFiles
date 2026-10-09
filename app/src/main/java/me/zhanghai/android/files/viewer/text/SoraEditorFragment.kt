/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.viewer.text

import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.text.InputType
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.doOnLayout
import androidx.core.view.isInvisible
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import io.github.rosemoe.sora.event.ContentChangeEvent
import io.github.rosemoe.sora.event.LayoutStateChangeEvent
import io.github.rosemoe.sora.event.ScrollEvent
import io.github.rosemoe.sora.event.SelectionChangeEvent
import io.github.rosemoe.sora.event.TextSizeChangeEvent
import io.github.rosemoe.sora.lang.EmptyLanguage
import io.github.rosemoe.sora.lang.Language
import io.github.rosemoe.sora.langs.textmate.TextMateLanguage
import io.github.rosemoe.sora.text.Content
import io.github.rosemoe.sora.text.LineSeparator
import io.github.rosemoe.sora.widget.CodeEditor
import io.github.rosemoe.sora.widget.component.EditorAutoCompletion
import java8.nio.file.Path
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.parcelize.Parcelize
import me.zhanghai.android.files.R
import me.zhanghai.android.files.databinding.SoraEditorFragmentBinding
import me.zhanghai.android.files.util.ActionState
import me.zhanghai.android.files.util.DataState
import me.zhanghai.android.files.util.ParcelableArgs
import me.zhanghai.android.files.util.addOnBackPressedCallback
import me.zhanghai.android.files.util.args
import me.zhanghai.android.files.util.dpToDimensionPixelSize
import me.zhanghai.android.files.util.extraPath
import me.zhanghai.android.files.util.getColorByAttr
import me.zhanghai.android.files.util.isReady
import me.zhanghai.android.files.util.showToast
import me.zhanghai.android.files.util.viewModels
import java.nio.charset.Charset
import java.util.Locale

class SoraEditorFragment : Fragment(), ConfirmReloadDialogFragment.Listener,
    ConfirmCloseDialogFragment.Listener {
    private val args by args<Args>()
    private lateinit var argsFile: Path
    private val viewModel by viewModels { { SoraEditorViewModel(argsFile) } }

    private var _binding: SoraEditorFragmentBinding? = null
    private val binding get() = _binding!!
    private lateinit var preferences: SoraEditorPreferences
    private var searchController: SoraEditorSearchController? = null
    private var menu: Menu? = null
    private var activeDialog: AlertDialog? = null
    private val charsetItems = mutableMapOf<MenuItem, Charset>()

    private var languageJob: Job? = null
    private var language: SoraEditorLanguages.Entry? = null
    private var languageLoading = false
    private var layoutBusy = false
    private var pendingScroll: SoraEditorViewModel.EditorViewState? = null

    private val onBackPressedCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            if (viewModel.searchVisible) {
                searchController?.close()
            } else {
                ConfirmCloseDialogFragment.show(this@SoraEditorFragment)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setHasOptionsMenu(true)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = SoraEditorFragmentBinding.inflate(inflater, container, false)
        .also { _binding = it }.root

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val file = args.intent.extraPath
        if (file == null) {
            showToast(R.string.sora_editor_file_missing)
            finish()
            return
        }
        argsFile = file
        preferences = SoraEditorPreferences(requireContext())
        val activity = requireActivity() as AppCompatActivity
        activity.setSupportActionBar(binding.toolbar)
        activity.supportActionBar!!.setDisplayHomeAsUpEnabled(true)
        addOnBackPressedCallback(onBackPressedCallback)

        val editor = binding.editor
        // Content lives in the ViewModel, never in the Activity's saved-state Bundle.
        editor.isSaveEnabled = false
        editor.setEditable(false)
        editor.subscribeEvent(LayoutStateChangeEvent::class.java) { event, _ ->
            layoutBusy = event.isLayoutBusy
            if (!layoutBusy) {
                editor.post {
                    if (!editor.isReleased) {
                        restoreScroll()
                        updateMenu()
                        searchController?.updateActions()
                    }
                }
            }
        }
        editor.subscribeEvent(ContentChangeEvent::class.java) { event, _ ->
            if (event.action != ContentChangeEvent.ACTION_SET_NEW_TEXT
                && editor.text === viewModel.textState.value.data) {
                pendingScroll = null
                viewModel.onTextChanged()
            }
            // UndoManager updates its stack pointer after dispatching content events.
            editor.post {
                if (!editor.isReleased) {
                    updateMenu()
                    updateStatus()
                }
            }
        }
        editor.subscribeEvent(SelectionChangeEvent::class.java) { _, _ -> updateStatus() }
        editor.subscribeEvent(ScrollEvent::class.java) { event, _ ->
            binding.appBarLayout.isLifted = event.endY > 0
        }

        applyPreferences()
        val content = viewModel.textState.value.data
        if (content != null) editor.setText(content)
        viewModel.editorViewState?.let {
            editor.setTextSizePx(it.textSizePx)
            if (it.content === content) pendingScroll = it
        }
        editor.doOnLayout { restoreScroll() }
        editor.subscribeEvent(TextSizeChangeEvent::class.java) { event, _ ->
            preferences.fontSize = event.newTextSize / resources.displayMetrics.scaledDensity
        }

        searchController = SoraEditorSearchController(
            binding, viewModel, viewLifecycleOwner.lifecycleScope, ::updateBackCallback
        )
        setupSymbols()
        updateBackCallback()
        applyLanguage()

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { viewModel.textState.collect { onTextStateChanged(it) } }
                launch {
                    viewModel.encoding.collect {
                        updateMenu()
                        updateStatus()
                    }
                }
                launch {
                    viewModel.isTextChanged.collect {
                        updateTitle()
                        updateBackCallback()
                    }
                }
                launch { viewModel.writeFileState.collect { onWriteFileStateChanged(it) } }
            }
        }
    }

    private fun applyPreferences() {
        binding.editor.apply {
            setTextSize(preferences.fontSize)
            val density = resources.displayMetrics.scaledDensity
            setScaleTextSizes(8f * density, 32f * density)
            isWordwrap = preferences.wordWrap
            isLineNumberEnabled = preferences.lineNumbers
            setPinLineNumber(true)
            tabWidth = preferences.tabWidth
            props.autoIndent = true
            props.stickyScroll = preferences.stickyScroll
            isHighlightBracketPair = true
            isBlockLineEnabled = true
        }
        applyWhitespace()
        applyAutoCompletion()
    }

    private fun applyWhitespace() {
        binding.editor.nonPrintablePaintingFlags = if (preferences.whitespace) {
            CodeEditor.FLAG_DRAW_WHITESPACE_LEADING or
                CodeEditor.FLAG_DRAW_WHITESPACE_INNER or
                CodeEditor.FLAG_DRAW_WHITESPACE_TRAILING or
                CodeEditor.FLAG_DRAW_WHITESPACE_FOR_EMPTY_LINE or
                CodeEditor.FLAG_DRAW_LINE_SEPARATOR
        } else {
            0
        }
    }

    private fun applyAutoCompletion() {
        binding.editor.getComponent(EditorAutoCompletion::class.java).isEnabled =
            preferences.autoCompletion && !viewModel.isReadOnly
        binding.editor.props.symbolPairAutoCompletion = preferences.autoCompletion
    }

    private fun setupSymbols() {
        val display = arrayOf(
            getString(R.string.sora_editor_symbols_tab), "{", "}", "(", ")", "[", "]",
            ";", ",", ".", "\"", "'", "=", "+", "-", "*", "/", "<", ">", ":", "_", "&", "|", "!", "?"
        )
        val insert = display.copyOf().apply { this[0] = "\t" }
        binding.symbolInputView.apply {
            bindEditor(binding.editor)
            addSymbols(display, insert)
            textColor = requireContext().getColorByAttr(com.google.android.material.R.attr.colorOnSurface)
            forEachButton {
                it.minWidth = dpToDimensionPixelSize(40)
                it.minimumWidth = dpToDimensionPixelSize(40)
            }
        }
    }

    private fun applyLanguage() {
        languageJob?.cancel()
        val entry = if (viewModel.languageId == "auto") {
            SoraEditorLanguages.detect(argsFile.fileName.toString())
        } else {
            SoraEditorLanguages.forId(viewModel.languageId)
        }
        language = entry
        val editor = binding.editor
        if (entry.scope == null) {
            editor.setEditorLanguage(EmptyLanguage())
            editor.applyThemeColors()
            languageLoading = false
            updateStatus()
            updateMenu()
            return
        }
        val dark = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
            Configuration.UI_MODE_NIGHT_YES
        val context = requireContext().applicationContext
        languageLoading = true
        updateStatus()
        languageJob = viewLifecycleOwner.lifecycleScope.launch {
            var createdLanguage: Language? = null
            var installed = false
            try {
                val scheme = withContext(Dispatchers.IO) {
                    SoraEditorLanguages.initialize(context)
                    createdLanguage = SoraEditorLanguages.createLanguage(entry, dark)
                    SoraEditorLanguages.createColorScheme(dark)
                }
                ensureActive()
                (createdLanguage as? TextMateLanguage)?.tabSize = preferences.tabWidth
                editor.colorScheme = scheme
                editor.applyThemeColors()
                editor.setEditorLanguage(createdLanguage!!)
                installed = true
                languageLoading = false
                updateStatus()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ensureActive()
                e.printStackTrace()
                editor.setEditorLanguage(EmptyLanguage())
                language = SoraEditorLanguages.forId("plain")
                viewModel.languageId = "plain"
                languageLoading = false
                updateStatus()
                showToast(R.string.sora_editor_language_failed)
            } finally {
                if (!installed) createdLanguage?.let { releaseLanguage(it) }
            }
            updateMenu()
        }
    }

    private fun releaseLanguage(language: Language) {
        // TextMate's analyzer registers a theme listener before being attached to an editor.
        // Language.destroy() alone does not unregister it when an asynchronous load is canceled.
        language.analyzeManager.setReceiver(null)
        language.analyzeManager.destroy()
        language.formatter.setReceiver(null)
        language.formatter.destroy()
        language.destroy()
    }

    private fun onTextStateChanged(state: DataState<Content>) {
        val loaded = state is DataState.Success
        binding.progress.isVisible = state is DataState.Loading
        binding.errorText.isVisible = state is DataState.Error
        binding.editor.isInvisible = !loaded
        when (state) {
            is DataState.Success -> {
                if (binding.editor.text !== state.data) {
                    pendingScroll = null
                    binding.editor.setText(state.data)
                }
                binding.editor.lineSeparator = state.data.getLine(0).lineSeparator
                    .takeUnless { it == LineSeparator.NONE } ?: LineSeparator.LF
            }
            is DataState.Error -> {
                state.throwable.printStackTrace()
                binding.errorText.text = state.throwable.toString()
            }
            is DataState.Loading -> Unit
        }
        updateEditability()
        searchController?.setLoaded(loaded)
        updateTitle()
        updateStatus()
        updateMenu()
    }

    private fun updateEditability() {
        val editable = viewModel.textState.value is DataState.Success && !viewModel.isReadOnly
        binding.editor.setEditable(editable)
        binding.symbolScrollView.isVisible = !viewModel.isReadOnly && !viewModel.searchVisible
        binding.symbolInputView.forEachButton { it.isEnabled = editable }
        applyAutoCompletion()
        searchController?.updateActions()
    }

    private fun restoreScroll() {
        val state = pendingScroll ?: return
        val editor = _binding?.editor ?: return
        if (layoutBusy || !editor.isLaidOut || editor.isReleased) return
        pendingScroll = null
        editor.eventHandler.scrollBy(
            (state.scrollX - editor.offsetX).toFloat(),
            (state.scrollY - editor.offsetY).toFloat(), false
        )
    }

    private fun saveViewState() {
        val editor = _binding?.editor ?: return
        if (!this::argsFile.isInitialized) return
        viewModel.editorViewState = SoraEditorViewModel.EditorViewState(
            editor.text, editor.offsetX, editor.offsetY, editor.textSizePx
        )
    }

    override fun onSaveInstanceState(outState: Bundle) {
        saveViewState()
        super.onSaveInstanceState(outState)
    }

    override fun onDestroyView() {
        saveViewState()
        languageJob?.cancel()
        languageJob = null
        activeDialog?.dismiss()
        activeDialog = null
        searchController?.destroy()
        searchController = null
        binding.editor.release()
        pendingScroll = null
        layoutBusy = false
        menu = null
        charsetItems.clear()
        _binding = null
        super.onDestroyView()
    }

    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        super.onCreateOptionsMenu(menu, inflater)
        inflater.inflate(R.menu.sora_editor, menu)
        this.menu = menu
        charsetItems.clear()
        val encodingMenu = menu.findItem(R.id.action_encoding).subMenu!!
        for (charset in Charset.availableCharsets().values) {
            val item = encodingMenu.add(ENCODING_GROUP, Menu.NONE, Menu.NONE, charset.displayName())
            charsetItems[item] = charset
        }
        encodingMenu.setGroupCheckable(ENCODING_GROUP, true, true)
        updateMenu()
    }

    override fun onPrepareOptionsMenu(menu: Menu) {
        super.onPrepareOptionsMenu(menu)
        updateMenu()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (_binding == null || !this::argsFile.isInitialized) return super.onOptionsItemSelected(item)
        charsetItems[item]?.let {
            if (viewModel.writeFileState.value.isReady) viewModel.encoding.value = it
            return true
        }
        LANGUAGE_ITEMS[item.itemId]?.let {
            viewModel.languageId = it
            applyLanguage()
            updateMenu()
            return true
        }
        val editor = binding.editor
        when (item.itemId) {
            R.id.action_save -> save()
            R.id.action_undo -> if (editor.isEditable) editor.undo()
            R.id.action_redo -> if (editor.isEditable) editor.redo()
            R.id.action_search_replace -> searchController?.open()
            R.id.action_go_to_line -> goToLine()
            R.id.action_select_all -> editor.selectAll()
            R.id.action_indent -> if (editor.isEditable) editor.indentLines(false)
            R.id.action_unindent -> if (editor.isEditable) editor.unindentSelection()
            R.id.action_duplicate_line -> duplicateLineOrSelection()
            R.id.action_font_size -> chooseFontSize()
            R.id.action_tab_width -> chooseTabWidth()
            R.id.action_word_wrap -> {
                preferences.wordWrap = !preferences.wordWrap
                editor.isWordwrap = preferences.wordWrap
            }
            R.id.action_line_numbers -> {
                preferences.lineNumbers = !preferences.lineNumbers
                editor.isLineNumberEnabled = preferences.lineNumbers
            }
            R.id.action_read_only -> {
                viewModel.isReadOnly = !viewModel.isReadOnly
                updateEditability()
                updateStatus()
            }
            R.id.action_whitespace -> {
                preferences.whitespace = !preferences.whitespace
                applyWhitespace()
            }
            R.id.action_auto_completion -> {
                preferences.autoCompletion = !preferences.autoCompletion
                applyAutoCompletion()
            }
            R.id.action_sticky_scroll -> {
                preferences.stickyScroll = !preferences.stickyScroll
                editor.props.stickyScroll = preferences.stickyScroll
                editor.invalidate()
            }
            R.id.action_reload -> onReload()
            else -> return super.onOptionsItemSelected(item)
        }
        updateMenu()
        return true
    }

    private fun updateMenu() {
        val menu = menu ?: return
        if (_binding == null || !this::argsFile.isInitialized) return
        val editor = binding.editor
        val loaded = viewModel.textState.value is DataState.Success
        val editable = loaded && editor.isEditable
        val writeReady = viewModel.writeFileState.value.isReady
        menu.findItem(R.id.action_save).isEnabled = loaded && writeReady && !viewModel.isReadOnly
        menu.findItem(R.id.action_undo).isEnabled = editable && editor.canUndo()
        menu.findItem(R.id.action_redo).isEnabled = editable && editor.canRedo()
        menu.findItem(R.id.action_search_replace).isEnabled = loaded
        menu.findItem(R.id.action_go_to_line).isEnabled = loaded && !layoutBusy
        menu.findItem(R.id.action_select_all).isEnabled = loaded && !layoutBusy
        menu.findItem(R.id.action_indent).isEnabled = editable
        menu.findItem(R.id.action_unindent).isEnabled = editable
        menu.findItem(R.id.action_duplicate_line).isEnabled = editable
        menu.findItem(R.id.action_reload).isEnabled = writeReady
        menu.findItem(R.id.action_encoding).isEnabled = writeReady
        menu.findItem(R.id.action_word_wrap).isChecked = preferences.wordWrap
        menu.findItem(R.id.action_line_numbers).isChecked = preferences.lineNumbers
        menu.findItem(R.id.action_read_only).isChecked = viewModel.isReadOnly
        menu.findItem(R.id.action_whitespace).isChecked = preferences.whitespace
        menu.findItem(R.id.action_auto_completion).isChecked = preferences.autoCompletion
        menu.findItem(R.id.action_sticky_scroll).isChecked = preferences.stickyScroll
        for ((id, language) in LANGUAGE_ITEMS) {
            menu.findItem(id).isChecked = language == viewModel.languageId
        }
        for ((item, charset) in charsetItems) {
            item.isChecked = charset == viewModel.encoding.value
        }
    }

    private fun updateTitle() {
        requireActivity().title = getString(
            if (viewModel.isTextChanged.value) R.string.text_editor_title_changed_format
            else R.string.text_editor_title_format, argsFile.fileName.toString()
        )
    }

    private fun updateStatus() {
        val binding = _binding ?: return
        if (!this::argsFile.isInitialized) return
        val editor = binding.editor
        val cursor = editor.cursor
        val languageName = if (languageLoading) {
            getString(R.string.sora_editor_language_loading)
        } else {
            language?.displayName ?: getString(R.string.text_editor_language_plain)
        }
        binding.statusText.text = buildString {
            append(getString(
                R.string.sora_editor_status, cursor.rightLine + 1, cursor.rightColumn + 1,
                viewModel.encoding.value.name(), languageName, editor.lineSeparator.name
            ))
            if (cursor.isSelected) {
                append(getString(R.string.sora_editor_status_selection, cursor.right - cursor.left))
            }
            if (viewModel.isReadOnly) append(getString(R.string.sora_editor_status_read_only))
        }
    }

    private fun updateBackCallback() {
        onBackPressedCallback.isEnabled = viewModel.searchVisible || viewModel.isTextChanged.value
        _binding?.symbolScrollView?.isVisible = !viewModel.isReadOnly && !viewModel.searchVisible
    }

    fun onSupportNavigateUp(): Boolean {
        if (!onBackPressedCallback.isEnabled) return false
        onBackPressedCallback.handleOnBackPressed()
        return true
    }

    private fun duplicateLineOrSelection() {
        val editor = binding.editor
        if (!editor.isEditable) return
        val cursor = editor.cursor
        if (cursor.isSelected) {
            editor.duplicateSelection()
            return
        }
        val line = cursor.rightLine
        val column = cursor.rightColumn
        val text = editor.text.getLineString(line)
        val separator = editor.text.getLine(line).lineSeparator
            .takeUnless { it == LineSeparator.NONE } ?: editor.lineSeparator
        // Sora's duplicateLine() inserts a literal LF and skips empty lines. Preserve the
        // document's line ending, including when duplicating an empty line.
        editor.setSelection(line, text.length)
        editor.commitText(separator.content + text, false)
        editor.setSelection(line + 1, column)
    }

    fun onEditorShortcut(keyCode: Int): Boolean {
        if (_binding == null || !this::argsFile.isInitialized
            || viewModel.textState.value !is DataState.Success) return false
        when (keyCode) {
            KeyEvent.KEYCODE_S -> save()
            KeyEvent.KEYCODE_F -> searchController?.open()
            KeyEvent.KEYCODE_H -> searchController?.open(true)
            KeyEvent.KEYCODE_G -> goToLine()
            else -> return false
        }
        return true
    }

    private fun goToLine() {
        if (layoutBusy || viewModel.textState.value !is DataState.Success) return
        val count = binding.editor.lineCount
        val input = TextInputEditText(requireContext()).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setSingleLine()
            setText(String.format(Locale.ROOT, "%d", binding.editor.cursor.rightLine + 1))
            selectAll()
        }
        val inputLayout = TextInputLayout(requireContext()).apply {
            hint = getString(R.string.sora_editor_line_hint, count)
            val margin = dpToDimensionPixelSize(24)
            setPadding(margin, dpToDimensionPixelSize(8), margin, 0)
            addView(input)
        }
        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.text_editor_go_to_line)
            .setView(inputLayout)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val line = input.text.toString().toIntOrNull()
                if (line == null || line !in 1..count) {
                    inputLayout.error = getString(R.string.sora_editor_line_error, count)
                } else {
                    binding.editor.setSelection(line - 1, 0)
                    dialog.dismiss()
                }
            }
        }
        showEditorDialog(dialog)
    }

    private fun chooseFontSize() {
        val sizes = intArrayOf(8, 10, 12, 14, 16, 18, 20, 24, 28, 32)
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.text_editor_font_size)
            .setSingleChoiceItems(
                sizes.map { getString(R.string.sora_editor_font_size_value, it) }.toTypedArray(),
                sizes.indexOf(preferences.fontSize.toInt())
            ) { dialog, which ->
                preferences.fontSize = sizes[which].toFloat()
                binding.editor.setTextSize(preferences.fontSize)
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
            .also { showEditorDialog(it) }
    }

    private fun chooseTabWidth() {
        val widths = intArrayOf(2, 4, 8)
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.text_editor_tab_width)
            .setSingleChoiceItems(
                widths.map { getString(R.string.sora_editor_tab_width_value, it) }.toTypedArray(),
                widths.indexOf(preferences.tabWidth)
            ) { dialog, which ->
                preferences.tabWidth = widths[which]
                binding.editor.tabWidth = preferences.tabWidth
                (binding.editor.editorLanguage as? TextMateLanguage)?.let {
                    it.tabSize = preferences.tabWidth
                    it.analyzeManager.rerun()
                }
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
            .also { showEditorDialog(it) }
    }

    private fun showEditorDialog(dialog: AlertDialog) {
        activeDialog?.dismiss()
        activeDialog = dialog
        dialog.setOnDismissListener {
            if (activeDialog === dialog) activeDialog = null
        }
        dialog.show()
    }

    private fun onReload() {
        if (!viewModel.writeFileState.value.isReady) return
        if (viewModel.isTextChanged.value) {
            ConfirmReloadDialogFragment.show(this)
        } else {
            reload()
        }
    }

    override fun reload() {
        if (!viewModel.writeFileState.value.isReady) return
        viewModel.isTextChanged.value = false
        viewModel.reload()
    }

    private fun save() {
        if (viewModel.textState.value !is DataState.Success
            || !viewModel.writeFileState.value.isReady || viewModel.isReadOnly) return
        viewModel.writeFile(argsFile, binding.editor.text.toString(), requireContext())
    }

    private fun onWriteFileStateChanged(state: ActionState<Pair<Path, String>, Unit>) {
        when (state) {
            is ActionState.Ready, is ActionState.Running -> Unit
            is ActionState.Success -> {
                showToast(R.string.text_editor_save_success)
                viewModel.finishWritingFile()
            }
            is ActionState.Error -> viewModel.finishWritingFile()
        }
        updateMenu()
    }

    override fun finish() {
        requireActivity().finish()
    }

    @Parcelize
    class Args(val intent: Intent) : ParcelableArgs

    companion object {
        private const val ENCODING_GROUP = 1

        private val LANGUAGE_ITEMS = mapOf(
            R.id.action_language_auto to "auto", R.id.action_language_plain to "plain",
            R.id.action_language_c to "c", R.id.action_language_cpp to "cpp",
            R.id.action_language_java to "java", R.id.action_language_kotlin to "kotlin",
            R.id.action_language_javascript to "javascript", R.id.action_language_typescript to "typescript",
            R.id.action_language_json to "json", R.id.action_language_xml to "xml",
            R.id.action_language_html to "html", R.id.action_language_css to "css",
            R.id.action_language_markdown to "markdown", R.id.action_language_python to "python",
            R.id.action_language_shell to "shell", R.id.action_language_yaml to "yaml"
        )
    }
}
