/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.filejob

import android.app.Dialog
import android.os.Bundle
import android.view.LayoutInflater
import androidx.appcompat.app.AppCompatDialogFragment
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.Observer
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.text.NumberFormat
import me.zhanghai.android.files.R
import me.zhanghai.android.files.databinding.ArchiveJobProgressDialogBinding
import me.zhanghai.android.files.databinding.FileJobArchiveProgressItemBinding

class ArchiveJobProgressDialogFragment : AppCompatDialogFragment() {
    private var binding: ArchiveJobProgressDialogBinding? = null
    private val progressBindings = mutableMapOf<Int, FileJobArchiveProgressItemBinding>()
    private val cancelingJobIds = mutableSetOf<Int>()
    private val percentFormat = NumberFormat.getPercentInstance()
    private val progressObserver = Observer<List<ArchiveJobProgress>> { updateProgress(it) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        savedInstanceState?.getIntArray(STATE_CANCELING_JOBS)?.let {
            cancelingJobIds.addAll(it.toList())
        }
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog =
        MaterialAlertDialogBuilder(requireContext(), theme)
            .setTitle(R.string.file_list_archive_jobs)
            .apply {
                val binding = ArchiveJobProgressDialogBinding.inflate(LayoutInflater.from(context))
                this@ArchiveJobProgressDialogFragment.binding = binding
                setView(binding.root)
                updateProgress(ArchiveJobProgressLiveData.value.orEmpty())
            }
            .setPositiveButton(R.string.close, null)
            .create()

    override fun onStart() {
        super.onStart()

        // A DialogFragment built with onCreateDialog has no fragment view lifecycle. Observe its
        // own lifecycle only while the dialog is shown, and release the observer before teardown.
        ArchiveJobProgressLiveData.observe(this, progressObserver)
    }

    override fun onStop() {
        ArchiveJobProgressLiveData.removeObserver(progressObserver)

        super.onStop()
    }

    override fun onDestroyView() {
        ArchiveJobProgressLiveData.removeObserver(progressObserver)
        progressBindings.clear()
        binding = null

        super.onDestroyView()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)

        outState.putIntArray(STATE_CANCELING_JOBS, cancelingJobIds.toIntArray())
    }

    private fun updateProgress(jobs: List<ArchiveJobProgress>) {
        val binding = binding ?: return
        val jobIds = jobs.mapTo(mutableSetOf()) { it.id }
        cancelingJobIds.retainAll(jobIds)
        val iterator = progressBindings.iterator()
        while (iterator.hasNext()) {
            val (id, progressBinding) = iterator.next()
            if (id !in jobIds) {
                binding.progressLayout.removeView(progressBinding.root)
                iterator.remove()
            }
        }
        for (job in jobs) {
            val progressBinding = progressBindings.getOrPut(job.id) {
                FileJobArchiveProgressItemBinding.inflate(
                    LayoutInflater.from(binding.root.context), binding.progressLayout, false
                ).also {
                    binding.progressLayout.addView(it.root)
                    it.cancelButton.setOnClickListener { view ->
                        cancelingJobIds += job.id
                        view.isEnabled = false
                        it.detailText.setText(R.string.file_job_archive_canceling)
                        FileJobService.cancelJob(job.id)
                    }
                }
            }
            val canceling = job.id in cancelingJobIds
            progressBinding.titleText.text = job.title
            progressBinding.detailText.text = if (canceling) {
                getString(R.string.file_job_archive_canceling)
            } else {
                job.text
            }
            progressBinding.cancelButton.apply {
                isEnabled = !canceling
                contentDescription = getString(R.string.file_job_archive_cancel_format, job.title)
            }
            val maximum = job.max.coerceAtLeast(1)
            val current = job.progress.coerceIn(0, maximum)
            val indeterminate = job.indeterminate || job.max <= 0
            progressBinding.progress.apply {
                max = maximum
                isIndeterminate = indeterminate
                if (!indeterminate) {
                    setProgressCompat(current, isLaidOut)
                }
            }
            progressBinding.percentText.text = if (!indeterminate) {
                percentFormat.format(current.toDouble() / maximum)
            } else {
                null
            }
        }
        binding.progressLayout.isVisible = jobs.isNotEmpty()
        binding.emptyText.isVisible = jobs.isEmpty()
    }

    companion object {
        private const val STATE_CANCELING_JOBS = "cancelingJobs"
        private val TAG = ArchiveJobProgressDialogFragment::class.java.name

        fun show(fragment: Fragment) {
            if (!fragment.isAdded) {
                return
            }
            val fragmentManager = fragment.childFragmentManager
            if (fragmentManager.isStateSaved || fragmentManager.findFragmentByTag(TAG) != null) {
                return
            }
            // Commit immediately so a second tap cannot enqueue a duplicate dialog. A dialog
            // restored after rotation keeps the same tag and is reused by the guard above.
            ArchiveJobProgressDialogFragment().showNow(fragmentManager, TAG)
        }
    }
}
