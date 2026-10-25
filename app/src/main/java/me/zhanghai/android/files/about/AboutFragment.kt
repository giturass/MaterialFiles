/*
 * Copyright (c) 2018 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.about

import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import me.zhanghai.android.files.R
import me.zhanghai.android.files.databinding.AboutFragmentBinding
import me.zhanghai.android.files.databinding.AboutLinkItemBinding
import me.zhanghai.android.files.ui.LicensesDialogFragment
import me.zhanghai.android.files.util.createViewIntent
import me.zhanghai.android.files.util.startActivitySafe

class AboutFragment : Fragment() {
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View =
        AboutFragmentBinding.inflate(inflater, container, false).root

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val binding = AboutFragmentBinding.bind(view)
        val activity = requireActivity() as AppCompatActivity
        activity.setSupportActionBar(binding.toolbar)
        activity.supportActionBar!!.setDisplayHomeAsUpEnabled(true)

        binding.versionText.text = getString(
            R.string.about_version_format, getString(R.string.app_version)
        )
        addLinks(binding.projectLinks, PROJECT_LINKS)
        addLinks(binding.recommendedAppLinks, RECOMMENDED_APP_LINKS)
        addLinks(binding.componentLinks, COMPONENT_LINKS)

        binding.zhanghaiXButton.bindAuthorLink(
            R.string.about_author_name_title, "https://x.com/zhanghai95"
        )
        binding.ericLeeXButton.bindAuthorLink(
            R.string.about_author_eric_lee, "https://x.com/x_ericlee"
        )
        binding.licensesItem.bindItem(
            R.drawable.text_file_icon_white_24dp, R.string.about_all_licenses_title,
            R.string.about_all_licenses_summary, R.drawable.arrow_end_icon_white_24dp
        ) { LicensesDialogFragment.show(this) }
        binding.privacyPolicyItem.bindItem(
            R.drawable.lock_icon_white_24dp, R.string.about_privacy_policy_title,
            R.string.about_privacy_policy_summary, R.drawable.arrow_end_icon_white_24dp
        ) { PrivacyPolicyDialogFragment.show(this) }
    }

    private fun addLinks(parent: ViewGroup, links: List<Link>) {
        for (link in links) {
            val item = AboutLinkItemBinding.inflate(layoutInflater, parent, false)
            item.bindItem(link.iconRes, link.titleRes, link.summaryRes) { openLink(link.url) }
            parent.addView(item.root)
        }
    }

    private fun AboutLinkItemBinding.bindItem(
        @DrawableRes iconRes: Int,
        @StringRes titleRes: Int,
        @StringRes summaryRes: Int,
        @DrawableRes actionIconRes: Int = R.drawable.open_in_new_icon_white_24dp,
        action: () -> Unit
    ) {
        iconView.setImageResource(iconRes)
        titleText.setText(titleRes)
        summaryText.setText(summaryRes)
        actionIconView.setImageResource(actionIconRes)
        root.setOnClickListener { action() }
    }

    private fun View.bindAuthorLink(@StringRes nameRes: Int, url: String) {
        contentDescription = getString(R.string.about_author_x_description, getString(nameRes))
        setOnClickListener { openLink(url) }
    }

    private fun openLink(url: String) {
        startActivitySafe(Uri.parse(url).createViewIntent())
    }

    private class Link(
        @DrawableRes val iconRes: Int,
        @StringRes val titleRes: Int,
        @StringRes val summaryRes: Int,
        val url: String
    )

    companion object {
        private val PROJECT_LINKS = listOf(
            Link(
                R.drawable.github_icon_white_24dp, R.string.about_repository_title,
                R.string.about_repository_summary, "https://github.com/giturass/MaterialFiles"
            ),
            Link(
                R.drawable.github_icon_white_24dp, R.string.about_upstream_title,
                R.string.about_upstream_summary, "https://github.com/zhanghai/MaterialFiles"
            )
        )

        private val RECOMMENDED_APP_LINKS = listOf(
            Link(
                R.drawable.video_icon_white_24dp, R.string.about_vlc_title,
                R.string.about_vlc_summary, "https://www.videolan.org/vlc/download-android.html"
            ),
            Link(
                R.drawable.computer_icon_white_24dp, R.string.about_rsaf_title,
                R.string.about_rsaf_summary, "https://github.com/chenxiaolong/RSAF"
            )
        )

        private val COMPONENT_LINKS = listOf(
            Link(
                R.drawable.file_code_icon, R.string.about_component_sora_title,
                R.string.about_component_sora_summary, "https://github.com/Rosemoe/sora-editor"
            ),
            Link(
                R.drawable.file_code_icon, R.string.about_component_textmate_title,
                R.string.about_component_textmate_summary,
                "https://github.com/Rosemoe/sora-editor/tree/0.24.6/language-textmate"
            ),
            Link(
                R.drawable.archive_icon_control_normal_24dp,
                R.string.about_component_sevenzip_title,
                R.string.about_component_sevenzip_summary, "https://www.7-zip.org/"
            ),
            Link(
                R.drawable.archive_icon_control_normal_24dp,
                R.string.about_component_jbinding_title,
                R.string.about_component_jbinding_summary,
                "https://github.com/borisbrodski/sevenzipjbinding"
            ),
            Link(
                R.drawable.file_certificate_icon, R.string.about_component_apksig_title,
                R.string.about_component_apksig_summary,
                "https://android.googlesource.com/platform/tools/apksig/"
            ),
            Link(
                R.drawable.lock_icon_white_24dp, R.string.about_component_bouncy_castle_title,
                R.string.about_component_bouncy_castle_summary, "https://www.bouncycastle.org/"
            ),
            Link(
                R.drawable.file_apk_icon, R.string.about_component_apkeditor_title,
                R.string.about_component_apkeditor_summary,
                "https://github.com/REAndroid/APKEditor"
            ),
            Link(
                R.drawable.file_apk_icon, R.string.about_component_arsclib_title,
                R.string.about_component_arsclib_summary, "https://github.com/REAndroid/ARSCLib"
            )
        )
    }
}
