package me.zhanghai.android.files.ui

import android.content.Context
import de.psdev.licensesdialog.licenses.License
import me.zhanghai.android.files.R

class BassLicense : License() {
    override fun getName() = "BASS and add-on licences"
    override fun readSummaryTextFromResources(context: Context): String = getContent(context, R.raw.license_bass)
    override fun readFullTextFromResources(context: Context): String = getContent(context, R.raw.license_bass)
    override fun getVersion() = "2.4"
    override fun getUrl() = "https://www.un4seen.com/bass.html#license"
    companion object { private const val serialVersionUID = 1L }
}
