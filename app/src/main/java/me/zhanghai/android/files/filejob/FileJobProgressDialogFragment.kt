/*
 * Copyright (c) 2019 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.filejob

import android.app.Dialog
import android.os.Bundle
import androidx.appcompat.app.AppCompatDialogFragment
import androidx.core.view.isGone
import androidx.fragment.app.Fragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.parcelize.Parcelize
import me.zhanghai.android.files.R
import me.zhanghai.android.files.databinding.FileJobProgressDialogBinding
import me.zhanghai.android.files.util.ParcelableArgs
import me.zhanghai.android.files.util.args
import me.zhanghai.android.files.util.layoutInflater
import me.zhanghai.android.files.util.putArgs
import me.zhanghai.android.files.util.show

/**
 * Shows what a [FileJob] is doing while the user waits for it, instead of leaving the progress to
 * the notification alone.
 *
 * Dismissing the dialog - with the "run in background" button, with the back gesture, or by
 * leaving the app - doesn't touch the job: it only hands the progress back to the notification.
 * Only the cancel button actually stops it.
 */
class FileJobProgressDialogFragment : AppCompatDialogFragment() {
    private val args by args<Args>()

    private lateinit var binding: FileJobProgressDialogBinding

    private var isObservingProgress = false

    private var isJobCanceled = false

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog =
        MaterialAlertDialogBuilder(requireContext(), theme)
            .setTitle(args.title)
            .apply {
                binding = FileJobProgressDialogBinding.inflate(context.layoutInflater)
                setView(binding.root)
            }
            .setPositiveButton(R.string.file_job_progress_background) { _, _ -> dismiss() }
            .setNegativeButton(android.R.string.cancel) { _, _ ->
                isJobCanceled = true
                FileJobService.cancelJob(args.jobId)
            }
            .create()
            // The buttons say what happens; dismissing by a stray tap says nothing.
            .apply { setCanceledOnTouchOutside(false) }

    override fun onStart() {
        super.onStart()

        val liveData = FileJobProgressManager.getLiveData(args.jobId)
        if (liveData == null) {
            // Finished before we made it on screen, which a small job easily does.
            dismissAllowingStateLoss()
            return
        }
        if (!isObservingProgress) {
            isObservingProgress = true
            liveData.observe(this) { onProgressChanged(it) }
        }
    }

    private fun onProgressChanged(progress: FileJobProgress?) {
        if (progress == null) {
            dismissAllowingStateLoss()
            return
        }
        binding.descriptionText.text = progress.title
        binding.progressText.text = progress.text
        binding.progressText.isGone = progress.text.isNullOrEmpty()
        binding.progress.isIndeterminate = progress.indeterminate
        if (!progress.indeterminate) {
            binding.progress.max = progress.max
            binding.progress.setProgressCompat(progress.progress, true)
        }
    }

    override fun onResume() {
        super.onResume()

        FileJobProgressManager.setShownInForeground(args.jobId, true)
    }

    override fun onPause() {
        super.onPause()

        // Covers both leaving the app and the dialog going away, either of which is the moment the
        // notification should take over. Doing this while we are still resumed also keeps the
        // service allowed to go foreground again on Android 12 and above.
        // A canceled job is about to stop, so bringing its notification back would only make it
        // blink.
        if (!isJobCanceled) {
            FileJobProgressManager.setShownInForeground(args.jobId, false)
        }
    }

    companion object {
        fun show(jobId: Int, title: CharSequence, fragment: Fragment) {
            FileJobProgressDialogFragment().putArgs(Args(jobId, title)).show(fragment)
        }
    }

    @Parcelize
    class Args(val jobId: Int, val title: CharSequence) : ParcelableArgs
}
