/* Copyright (c) 2026 Material Files contributors. All Rights Reserved. */

package me.zhanghai.android.files.ui

import android.content.Context
import de.psdev.licensesdialog.licenses.License
import me.zhanghai.android.files.R

/** Preserves the notice from org.bouncycastle.LICENSE in bcprov-jdk15to18 1.86. */
class BouncyCastleLicense : License() {
    override fun getName(): String = "Bouncy Castle Licence"

    override fun readSummaryTextFromResources(context: Context): String =
        getContent(context, R.raw.license_bouncy_castle)

    override fun readFullTextFromResources(context: Context): String =
        getContent(context, R.raw.license_bouncy_castle)

    override fun getVersion(): String = ""

    override fun getUrl(): String = "https://www.bouncycastle.org/licence.html"

    companion object {
        private const val serialVersionUID = 1L
    }
}
