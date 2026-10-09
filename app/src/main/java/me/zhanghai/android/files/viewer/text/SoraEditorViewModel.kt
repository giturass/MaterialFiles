/*
 * Copyright (c) 2019 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.viewer.text

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.rosemoe.sora.text.Content
import java8.nio.file.Path
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import me.zhanghai.android.files.filejob.FileJobService
import me.zhanghai.android.files.provider.common.readAllBytes
import me.zhanghai.android.files.provider.common.size
import me.zhanghai.android.files.util.ActionState
import me.zhanghai.android.files.util.DataState
import me.zhanghai.android.files.util.isFinished
import me.zhanghai.android.files.util.isReady
import me.zhanghai.android.files.util.toError
import me.zhanghai.android.files.util.toLoading
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.charset.Charset

class SoraEditorViewModel(file: Path) : ViewModel() {
    private val _file = MutableStateFlow(file)
    val file = _file.asStateFlow()

    private val _bytesState = MutableStateFlow<DataState<ByteArray>>(DataState.Loading())

    private var loadJob: Job? = null
    private var reloadJob: Job? = null

    init {
        viewModelScope.launch {
            _file.collectLatest {
                loadJob?.cancel()?.also { loadJob = null }
                reloadJob?.cancel()?.also { reloadJob = null }
                loadJob = launch {
                    mapFileToBytesState(it)
                    if (isActive) {
                        loadJob = null
                    }
                }
            }
        }
    }

    fun reload() {
        viewModelScope.launch {
            loadJob?.cancel()?.also { loadJob = null }
            reloadJob?.cancel()?.also { reloadJob = null }
            reloadJob = launch {
                mapFileToBytesState(_file.value)
                if (isActive) {
                    reloadJob = null
                }
            }
        }
    }

    private suspend fun mapFileToBytesState(file: Path) {
        _bytesState.value = _bytesState.value.toLoading()
        try {
            val bytes = runInterruptible(Dispatchers.IO) {
                val size = file.size()
                if (size > MAX_FILE_SIZE) {
                    throw IOException("File size $size is too large")
                }
                file.readAllBytes()
            }
            currentCoroutineContext().ensureActive()
            _bytesState.value = DataState.Success(bytes)
        } catch (e: CancellationException) {
            e.printStackTrace()
        } catch (e: Exception) {
            _bytesState.value = _bytesState.value.toError(e)
        }
    }

    val encoding = MutableStateFlow(StandardCharsets.UTF_8)

    private val document = SoraEditorDocument()
    private var decodedEncoding: Charset? = null

    private val _textState = MutableStateFlow<DataState<Content>>(DataState.Loading())
    val textState = _textState.asStateFlow()

    init {
        viewModelScope.launch {
            _bytesState.combine(encoding) { bytesState, encoding -> bytesState to encoding }
                .collectLatest { (bytesState, encoding) ->
                    when (bytesState) {
                        is DataState.Loading -> _textState.value = _textState.value.toLoading()
                        is DataState.Success -> {
                            // Reloads already emit Loading. Saving in the same encoding must keep
                            // the editor and IME visible while the new saved snapshot is decoded.
                            if (encoding != decodedEncoding) {
                                _textState.value = _textState.value.toLoading()
                            }
                            try {
                                val text = withContext(Dispatchers.Default) {
                                    String(bytesState.data, encoding)
                                }
                                currentCoroutineContext().ensureActive()
                                document.load(text, isTextChanged.value)
                                decodedEncoding = encoding
                                _textState.value = DataState.Success(document.content)
                                onTextChanged()
                            } catch (e: CancellationException) {
                                e.printStackTrace()
                            } catch (e: Exception) {
                                _textState.value = _textState.value.toError(e)
                            }
                        }
                        is DataState.Error ->
                            _textState.value = _textState.value.toError(bytesState.throwable)
                    }
                }
        }
    }

    val isTextChanged = MutableStateFlow(false)

    fun onTextChanged() {
        isTextChanged.value = document.isChanged
    }

    private val _writeFileState =
        MutableStateFlow<ActionState<Pair<Path, String>, Unit>>(ActionState.Ready())
    val writeFileState = _writeFileState.asStateFlow()

    fun writeFile(path: Path, text: String, context: Context) {
        viewModelScope.launch {
            check(_writeFileState.value.isReady)
            val argument = path to text
            _writeFileState.value = ActionState.Running(argument)
            val encoding = encoding.value
            val bytes = withContext(Dispatchers.Default) {
                text.toByteArray(encoding)
            }
            FileJobService.write(path, bytes, context) { successful ->
                if (successful) {
                    loadJob?.cancel()?.also { loadJob = null }
                    reloadJob?.cancel()?.also { reloadJob = null }
                    document.markSaved(text)
                    onTextChanged()
                    _bytesState.value = DataState.Success(bytes)
                }
                _writeFileState.value = if (successful) {
                    ActionState.Success(argument, Unit)
                } else {
                    // The error will be toasted by service so we should never show it in UI, but we
                    // need a non-null value here.
                    ActionState.Error(argument, Throwable())
                }
            }
        }
    }

    fun finishWritingFile() {
        viewModelScope.launch {
            check(_writeFileState.value.isFinished)
            _writeFileState.value = ActionState.Ready()
        }
    }

    var editorViewState: EditorViewState? = null

    var languageId = "auto"
    var isReadOnly = false
    var searchVisible = false
    var searchQuery = ""
    var replacementText = ""
    var searchMatchCase = false
    var searchWholeWord = false
    var searchRegex = false

    data class EditorViewState(
        val content: Content,
        val scrollX: Int,
        val scrollY: Int,
        val textSizePx: Float
    )

    companion object {
        private const val MAX_FILE_SIZE = 1024 * 1024.toLong()
    }
}
