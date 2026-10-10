/*
 * Copyright (c) 2023 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.archive

import android.content.Context
import java8.nio.file.Path
import me.zhanghai.android.files.R
import me.zhanghai.android.files.fileaction.ArchivePasswordDialogActivity
import me.zhanghai.android.files.fileaction.ArchivePasswordDialogFragment
import me.zhanghai.android.files.provider.common.UserAction
import me.zhanghai.android.files.provider.common.UserActionRequiredException
import me.zhanghai.android.files.util.createIntent
import me.zhanghai.android.files.util.putArgs
import me.zhanghai.android.files.util.showToast
import java.io.InterruptedIOException
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class ArchivePasswordRequiredException(
    private val file: Path,
    reason: String?
) :
    UserActionRequiredException(file.toString(), null, reason) {

    val isPasswordIncorrect: Boolean
        get() = reason == "Incorrect passphrase"

    override fun getUserAction(continuation: Continuation<Boolean>, context: Context): UserAction {
        return UserAction(
            ArchivePasswordDialogActivity::class.createIntent().putArgs(
                ArchivePasswordDialogFragment.Args(file, isPasswordIncorrect) { successful ->
                    if (successful) {
                        continuation.resume(true)
                    } else {
                        context.showToast(R.string.file_action_archive_password_cancelled)
                        continuation.resumeWithException(
                            InterruptedIOException().apply {
                                initCause(this@ArchivePasswordRequiredException)
                            }
                        )
                    }
                }
            ), ArchivePasswordDialogFragment.getTitle(context),
            ArchivePasswordDialogFragment.getMessage(file.archiveFile, context)
        )
    }
}
