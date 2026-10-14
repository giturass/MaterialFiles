package me.zhanghai.android.files.about

import android.app.Dialog
import android.os.Bundle
import androidx.appcompat.app.AppCompatDialogFragment
import androidx.fragment.app.Fragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import me.zhanghai.android.files.R
import me.zhanghai.android.files.util.show

class PrivacyPolicyDialogFragment : AppCompatDialogFragment() {
    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog =
        MaterialAlertDialogBuilder(requireContext(), theme)
            .setTitle(R.string.about_privacy_policy_title)
            .setMessage(R.string.about_privacy_policy_message)
            .setPositiveButton(R.string.close, null)
            .create()

    companion object {
        fun show(fragment: Fragment) {
            PrivacyPolicyDialogFragment().show(fragment)
        }
    }
}
