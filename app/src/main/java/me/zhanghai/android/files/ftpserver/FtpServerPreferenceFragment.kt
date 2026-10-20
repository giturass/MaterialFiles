/*
 * Copyright (c) 2019 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.ftpserver

import android.os.Bundle
import androidx.preference.Preference
import me.zhanghai.android.files.R
import me.zhanghai.android.files.settings.Settings
import me.zhanghai.android.files.ui.PreferenceFragmentCompat
import me.zhanghai.android.files.util.showToast

class FtpServerPreferenceFragment : PreferenceFragmentCompat() {
    override fun onCreatePreferencesFix(savedInstanceState: Bundle?, rootKey: String?) {
        addPreferencesFromResource(R.xml.ftp_server)
        val ftpPort = findPreference<Preference>(getString(R.string.pref_key_ftp_server_port))!!
        val httpPort = findPreference<Preference>(getString(R.string.pref_key_http_server_port))!!
        for (preference in listOf(ftpPort, httpPort)) {
            preference.setOnPreferenceChangeListener { _, value ->
                val valid = value.toString().toIntOrNull()?.let { it in 1..65535 } == true
                if (!valid) {
                    requireContext().showToast(R.string.server_port_error)
                }
                valid
            }
        }
        Settings.SERVER_PROTOCOL.observe(this) { protocol ->
            ftpPort.isVisible = protocol == ServerProtocol.FTP
            httpPort.isVisible = protocol == ServerProtocol.HTTP
        }
    }
}
