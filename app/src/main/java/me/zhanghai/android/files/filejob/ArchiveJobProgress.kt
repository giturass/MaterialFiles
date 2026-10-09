/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.filejob

import androidx.lifecycle.LiveData
import me.zhanghai.android.files.app.mainExecutor

data class ArchiveJobProgress(
    val id: Int,
    val title: CharSequence,
    val text: CharSequence?,
    val max: Int,
    val progress: Int,
    val indeterminate: Boolean
)

/** Progress for jobs shown in the file list, retained when the list is recreated or resumed. */
object ArchiveJobProgressLiveData : LiveData<List<ArchiveJobProgress>>(emptyList()) {
    private val jobs = linkedMapOf<Int, ArchiveJobProgress>()

    internal fun update(progress: ArchiveJobProgress) {
        mainExecutor.execute {
            jobs[progress.id] = progress
            value = jobs.values.toList()
        }
    }

    internal fun remove(id: Int) {
        // Serialize both updates and removals on the main thread. In particular, a delayed
        // progress update must not bring back a job after cancellation or completion.
        mainExecutor.execute {
            if (jobs.remove(id) != null) {
                value = jobs.values.toList()
            }
        }
    }
}
