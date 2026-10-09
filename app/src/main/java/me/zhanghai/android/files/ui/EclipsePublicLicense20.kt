/*
 * Copyright (c) 2026 Material Files contributors
 * All Rights Reserved.
 */

package me.zhanghai.android.files.ui

import android.content.Context
import de.psdev.licensesdialog.licenses.License
import me.zhanghai.android.files.R

/** The TM4E sources bundled by Sora use EPL 2.0; LicensesDialog only includes EPL 1.0. */
class EclipsePublicLicense20 : License() {
    override fun getName(): String = "Eclipse Public License 2.0"

    override fun readSummaryTextFromResources(context: Context): String =
        getContent(context, R.raw.license_epl_2)

    override fun readFullTextFromResources(context: Context): String =
        getContent(context, R.raw.license_epl_2)

    override fun getVersion(): String = "2.0"

    override fun getUrl(): String = "https://www.eclipse.org/legal/epl-2.0/"

    companion object {
        private const val serialVersionUID = 1L
    }
}
