/* Copyright (c) 2026 Material Files contributors. All Rights Reserved. */
package me.zhanghai.android.files.apk

import android.app.Dialog
import android.content.Context
import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatDialogFragment
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewModelScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java8.nio.file.Path
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.parcelize.Parcelize
import kotlinx.parcelize.WriteWith
import me.zhanghai.android.files.R
import me.zhanghai.android.files.databinding.ApkEditorDecodeDialogBinding
import me.zhanghai.android.files.util.*

class ApkDecodeDialogFragment : AppCompatDialogFragment() {
    private val args by args<Args>()
    private val model by viewModels { { ApkDecodeViewModel() } }
    private var binding: ApkEditorDecodeDialogBinding? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) { model.state.collect { render() } }
        }
    }
    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val builder = MaterialAlertDialogBuilder(requireContext(), theme)
        val view = ApkEditorDecodeDialogBinding.inflate(builder.context.layoutInflater)
        binding = view
        view.description.isVisible = !args.info
        view.outputLayout.isVisible = !args.info
        view.inputText.text = getString(R.string.apk_editor_inputs, args.inputs.size)
        view.outputName.setText(savedInstanceState?.getString("outputName")
            ?: args.inputs.first().fileName.toString().substringBeforeLast('.') + "-decoded.zip")
        return builder.setTitle(if (args.info) R.string.apk_editor_info else R.string.apk_editor_decode).setView(view.root)
            .setPositiveButton(android.R.string.ok, null)
            .setNegativeButton(android.R.string.cancel, null).create()
    }
    override fun onStart() {
        super.onStart()
        (dialog as AlertDialog).getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            if (args.info || model.state.value.output != null) dismiss()
            else model.decode(requireContext(), args.inputs, binding?.outputName?.text.toString().trim())
        }
        (dialog as AlertDialog).getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener {
            if (model.state.value.busy) model.cancel() else dismiss()
        }
        if (args.info) model.info(requireContext(), args.inputs)
        render()
    }
    private fun render() {
        val view = binding ?: return
        val state = model.state.value
        isCancelable = !state.busy
        view.outputName.isEnabled = !state.busy && state.output == null
        view.progress.isVisible = state.busy
        view.status.text = state.output?.let { getString(R.string.apk_editor_decode_done, it.toString()) }
            ?: state.message.orEmpty()
        (dialog as? AlertDialog)?.getButton(AlertDialog.BUTTON_POSITIVE)?.isEnabled = !state.busy
    }
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("outputName", binding?.outputName?.text?.toString())
    }
    override fun onDestroyView() { binding = null; super.onDestroyView() }

    companion object {
        fun show(inputs: List<Path>, fragment: Fragment, info: Boolean = false) {
            val manager = fragment.childFragmentManager
            val tag = ApkDecodeDialogFragment::class.java.name
            if (!fragment.isAdded || manager.isStateSaved || manager.findFragmentByTag(tag) != null) return
            ApkDecodeDialogFragment().putArgs(Args(inputs, info)).showNow(manager, tag)
        }
    }
    @Parcelize
    private class Args(val inputs: List<@WriteWith<ParcelableParceler> Path>, val info: Boolean) : ParcelableArgs
}

class ApkDecodeViewModel : ViewModel() {
    data class State(val busy: Boolean = false, val message: String? = null, val output: Path? = null)
    private val mutableState = MutableStateFlow(State())
    val state = mutableState.asStateFlow()
    private var job: Job? = null
    fun decode(context: Context, inputs: List<Path>, name: String) {
        if (state.value.busy || state.value.output != null) return
        mutableState.value = State(busy = true)
        val application = context.applicationContext
        job = viewModelScope.launch {
            try {
                val output = runInterruptible(Dispatchers.IO) {
                    ApkOperations.decode(application, inputs, name) { progress ->
                        mutableState.update { it.copy(message = progress.stage) }
                    }
                }
                mutableState.value = State(output = output)
            } catch (_: CancellationException) {
                mutableState.value = State(message = application.getString(R.string.apk_signing_cancel))
            } catch (error: Exception) {
                mutableState.value = State(message = error.message ?: error.javaClass.simpleName)
            } finally { mutableState.update { it.copy(busy = false) } }
        }
    }
    private var infoLoaded = false
    fun info(context: Context, inputs: List<Path>) {
        if (infoLoaded || state.value.busy) return
        infoLoaded = true
        val application = context.applicationContext
        mutableState.value = State(busy = true)
        job = viewModelScope.launch {
            try {
                val text = runInterruptible(Dispatchers.IO) {
                    ApkOperations.editorInfo(application, inputs) { progress ->
                        mutableState.update { it.copy(message = progress.stage) }
                    }
                }
                mutableState.value = State(message = text)
            } catch (_: CancellationException) {
                mutableState.value = State(message = application.getString(R.string.apk_signing_cancel))
            } catch (error: Exception) { mutableState.value = State(message = error.message) }
            finally { mutableState.update { it.copy(busy = false) } }
        }
    }
    fun cancel() { job?.cancel() }
}
