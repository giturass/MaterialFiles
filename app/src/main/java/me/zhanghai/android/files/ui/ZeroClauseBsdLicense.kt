/*
 * Copyright (c) 2026 Material Files contributors
 * All Rights Reserved.
 */

package me.zhanghai.android.files.ui

import android.content.Context
import de.psdev.licensesdialog.licenses.License
import me.zhanghai.android.files.R

/** XZ for Java 1.10 uses 0BSD, which LicensesDialog 2.1.0 does not include. */
class ZeroClauseBsdLicense : License() {
    override fun getName(): String = "BSD Zero-Clause License"

    override fun readSummaryTextFromResources(context: Context): String =
        getContent(context, R.raw.license_0bsd)

    override fun readFullTextFromResources(context: Context): String =
        getContent(context, R.raw.license_0bsd)

    override fun getVersion(): String = ""

    override fun getUrl(): String = "https://opensource.org/license/0bsd"

    companion object {
        private const val serialVersionUID = 1L
    }
}
