/* Copyright (c) 2026 Material Files contributors. All Rights Reserved. */

package me.zhanghai.android.files.ui

import android.content.Context
import de.psdev.licensesdialog.licenses.License
import me.zhanghai.android.files.R

/** Includes the unRAR restriction shipped by the complete upstream decoder. */
class SevenZipLicense : License() {
    override fun getName(): String = "7-Zip license (LGPL, BSD and unRAR restriction)"

    override fun readSummaryTextFromResources(context: Context): String =
        getContent(context, R.raw.license_7zip_jbinding)

    override fun readFullTextFromResources(context: Context): String =
        getContent(context, R.raw.license_7zip_jbinding)

    override fun getVersion(): String = "2.1"

    override fun getUrl(): String =
        "https://github.com/ip7z/7zip/blob/26.04/DOC/License.txt"

    companion object {
        private const val serialVersionUID = 1L
    }
}
