/* Copyright (c) 2026 Material Files contributors. All Rights Reserved. */

package me.zhanghai.android.files.ui

import android.content.Context
import de.psdev.licensesdialog.licenses.License
import me.zhanghai.android.files.R

/** Includes the unRAR restriction shipped by the complete upstream decoder. */
class SevenZipLicense : License() {
    override fun getName(): String = "LGPL 2.1 with unRAR restriction"

    override fun readSummaryTextFromResources(context: Context): String =
        getContent(context, R.raw.license_7zip_jbinding)

    override fun readFullTextFromResources(context: Context): String =
        getContent(context, R.raw.license_7zip_jbinding)

    override fun getVersion(): String = "2.1"

    override fun getUrl(): String =
        "https://github.com/omicronapps/7-Zip-JBinding-4Android/blob/Release-16.02-2.03/License.txt"

    companion object {
        private const val serialVersionUID = 1L
    }
}
