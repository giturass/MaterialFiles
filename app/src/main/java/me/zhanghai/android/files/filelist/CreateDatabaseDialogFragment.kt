/*
 * Copyright (c) 2018 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.filelist

import androidx.annotation.StringRes
import androidx.fragment.app.Fragment
import me.zhanghai.android.files.R
import me.zhanghai.android.files.database.hasDatabaseExtension
import me.zhanghai.android.files.util.show

class CreateDatabaseDialogFragment : FileNameDialogFragment() {
    override val listener: Listener
        get() = super.listener as Listener

    @StringRes
    override val titleRes: Int = R.string.file_create_database_title

    /**
     * The file is routed to the database editor by its name, so make sure it ends up with an
     * extension that says what it is.
     */
    override val name: String
        get() = super.name.let { if (it.hasDatabaseExtension()) it else "$it$DEFAULT_EXTENSION" }

    override fun onOk(name: String) {
        listener.createDatabase(name)
    }

    companion object {
        private const val DEFAULT_EXTENSION = ".db"

        fun show(fragment: Fragment) {
            CreateDatabaseDialogFragment().show(fragment)
        }
    }

    interface Listener : FileNameDialogFragment.Listener {
        fun createDatabase(name: String)
    }
}
