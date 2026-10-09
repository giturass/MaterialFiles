package me.zhanghai.android.files.filelist

import android.app.Dialog
import android.os.Bundle
import androidx.appcompat.app.AppCompatDialogFragment
import androidx.fragment.app.Fragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java8.nio.file.Path
import kotlinx.parcelize.Parcelize
import kotlinx.parcelize.WriteWith
import me.zhanghai.android.files.R
import me.zhanghai.android.files.util.ParcelableArgs
import me.zhanghai.android.files.util.ParcelableParceler
import me.zhanghai.android.files.util.args
import me.zhanghai.android.files.util.putArgs
import me.zhanghai.android.files.util.show

class ExtractFilesDialogFragment : AppCompatDialogFragment() {
    private val args by args<Args>()

    private var intoSeparateDirectories = false

    private val listener: Listener
        get() = requireParentFragment() as Listener

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        intoSeparateDirectories =
            savedInstanceState?.getBoolean(STATE_INTO_SEPARATE_DIRECTORIES) ?: false
        val destinations = arrayOf(
            getString(R.string.file_extract_current_directory),
            getString(R.string.file_extract_separate_directory)
        )
        return MaterialAlertDialogBuilder(requireContext(), theme)
            .setTitle(R.string.file_item_action_extract)
            .setSingleChoiceItems(destinations, if (intoSeparateDirectories) 1 else 0) { _, which ->
                intoSeparateDirectories = which == 1
            }
            .setPositiveButton(R.string.file_item_action_extract) { _, _ ->
                listener.extractFiles(args.files, args.targetDirectory, intoSeparateDirectories)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_INTO_SEPARATE_DIRECTORIES, intoSeparateDirectories)
    }

    companion object {
        private const val STATE_INTO_SEPARATE_DIRECTORIES = "intoSeparateDirectories"

        fun show(files: FileItemSet, targetDirectory: Path, fragment: Fragment) {
            ExtractFilesDialogFragment()
                .putArgs(Args(files.toCollection(fileItemSetOf()), targetDirectory))
                .show(fragment)
        }
    }

    @Parcelize
    class Args(
        val files: FileItemSet,
        val targetDirectory: @WriteWith<ParcelableParceler> Path
    ) : ParcelableArgs

    interface Listener {
        fun extractFiles(
            files: FileItemSet,
            targetDirectory: Path,
            intoSeparateDirectories: Boolean
        )
    }
}
