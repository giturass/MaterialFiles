/*
 * Copyright (c) 2018 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.settings

import android.os.Build
import android.os.Bundle
import androidx.preference.Preference
import androidx.preference.SwitchPreferenceCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import me.zhanghai.android.files.R
import me.zhanghai.android.files.provider.root.RootStrategy
import me.zhanghai.android.files.provider.root.ShizukuFileServiceLauncher
import me.zhanghai.android.files.theme.custom.CustomThemeHelper
import me.zhanghai.android.files.theme.night.NightMode
import me.zhanghai.android.files.theme.night.NightModeHelper
import me.zhanghai.android.files.ui.PreferenceFragmentCompat
import me.zhanghai.android.files.util.showToast
import me.zhanghai.android.files.util.valueCompat
import rikka.shizuku.Shizuku

class SettingsPreferenceFragment : PreferenceFragmentCompat() {
    private lateinit var localePreference: LocalePreference
    private lateinit var shizukuPreference: SwitchPreferenceCompat
    private var shizukuRequestInProgress = false
    private val shizukuBinderReceivedListener =
        Shizuku.OnBinderReceivedListener { validateShizukuPreference() }

    override fun onCreatePreferencesFix(savedInstanceState: Bundle?, rootKey: String?) {
        addPreferencesFromResource(R.xml.settings)

        localePreference = preferenceScreen.findPreference(getString(R.string.pref_key_locale))!!
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            localePreference.setApplicationLocalesPre33 = { locales ->
                val activity = requireActivity() as SettingsActivity
                activity.setApplicationLocalesPre33(locales)
            }
        }

        shizukuPreference = preferenceScreen.findPreference(
            getString(R.string.pref_key_shizuku_enabled)
        )!!
        shizukuPreference.onPreferenceChangeListener =
            Preference.OnPreferenceChangeListener { _, newValue ->
                if (newValue as Boolean) {
                    requestShizukuPermission()
                    // Persist the switch only after Shizuku has granted the permission. This is
                    // important because the file service cannot display a permission dialog.
                    false
                } else {
                    Settings.SHIZUKU_ENABLED.putValue(false)
                    shizukuPreference.isChecked = false
                    false
                }
            }
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        super.onActivityCreated(savedInstanceState)

        val viewLifecycleOwner = viewLifecycleOwner
        // The following may end up passing the same lambda instance to the observer because it has
        // no capture, and result in an IllegalArgumentException "Cannot add the same observer with
        // different lifecycles" if activity is finished and instantly started again. To work around
        // this, always use an instance method reference.
        // https://stackoverflow.com/a/27524543
        Settings.NIGHT_MODE.observe(viewLifecycleOwner, this::onNightModeChanged)
        Settings.BLACK_NIGHT_MODE.observe(viewLifecycleOwner, this::onBlackNightModeChanged)
        Settings.SHIZUKU_ENABLED.observe(viewLifecycleOwner, this::onShizukuEnabledChanged)
    }

    private fun onNightModeChanged(nightMode: NightMode) {
        NightModeHelper.sync()
    }

    private fun onBlackNightModeChanged(blackNightMode: Boolean) {
        CustomThemeHelper.sync()
    }

    private fun onShizukuEnabledChanged(enabled: Boolean) {
        if (::shizukuPreference.isInitialized && shizukuPreference.isChecked != enabled) {
            shizukuPreference.isChecked = enabled
        }
    }

    override fun onStart() {
        super.onStart()
        Shizuku.addBinderReceivedListenerSticky(shizukuBinderReceivedListener)
    }

    override fun onStop() {
        Shizuku.removeBinderReceivedListener(shizukuBinderReceivedListener)
        super.onStop()
    }

    override fun onResume() {
        super.onResume()

        validateShizukuPreference()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Refresh locale preference summary because we aren't notified for an external change
            // between system default and the locale that's the current system default.
            localePreference.notifyChanged()
        }
    }

    private fun requestShizukuPermission() {
        if (shizukuRequestInProgress) {
            return
        }
        shizukuRequestInProgress = true
        shizukuPreference.isEnabled = false
        ShizukuFileServiceLauncher.requestPermission { result ->
            shizukuRequestInProgress = false
            Settings.SHIZUKU_ENABLED.putValue(
                result == ShizukuFileServiceLauncher.PermissionResult.GRANTED
            )
            if (!::shizukuPreference.isInitialized || !isAdded) {
                if (result == ShizukuFileServiceLauncher.PermissionResult.GRANTED) {
                    enableAutomaticRootStrategy()
                }
                return@requestPermission
            }
            shizukuPreference.isEnabled = true
            when (result) {
                ShizukuFileServiceLauncher.PermissionResult.GRANTED -> {
                    enableAutomaticRootStrategy()
                    shizukuPreference.isChecked = true
                }
                ShizukuFileServiceLauncher.PermissionResult.UNAVAILABLE -> {
                    shizukuPreference.isChecked = false
                    showToast(R.string.settings_shizuku_not_running_message)
                }
                ShizukuFileServiceLauncher.PermissionResult.DENIED -> {
                    shizukuPreference.isChecked = false
                    showShizukuDeniedDialog()
                }
                ShizukuFileServiceLauncher.PermissionResult.ERROR -> {
                    shizukuPreference.isChecked = false
                    showToast(R.string.settings_shizuku_error_message)
                }
            }
        }
    }

    private fun enableAutomaticRootStrategy() {
        // Android/data and Android/obb are selected automatically in AUTOMATIC mode.
        if (Settings.ROOT_STRATEGY.valueCompat == RootStrategy.NEVER) {
            Settings.ROOT_STRATEGY.putValue(RootStrategy.AUTOMATIC)
        }
    }

    private fun showShizukuDeniedDialog() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.settings_shizuku_denied_title)
            .setMessage(R.string.settings_shizuku_denied_message)
            .setPositiveButton(R.string.settings_shizuku_open_app) { _, _ ->
                val intent = requireContext().packageManager.getLaunchIntentForPackage(
                    SHIZUKU_MANAGER_PACKAGE
                )
                if (intent != null) {
                    try {
                        startActivity(intent)
                    } catch (_: Exception) {
                        showToast(R.string.settings_shizuku_error_message)
                    }
                } else {
                    showToast(R.string.settings_shizuku_error_message)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun validateShizukuPreference() {
        if (!isResumed || !::shizukuPreference.isInitialized || shizukuRequestInProgress
            || !shizukuPreference.isChecked
        ) {
            return
        }
        when (ShizukuFileServiceLauncher.getState()) {
            ShizukuFileServiceLauncher.State.GRANTED -> enableAutomaticRootStrategy()
            ShizukuFileServiceLauncher.State.PERMISSION_REQUIRED -> requestShizukuPermission()
            ShizukuFileServiceLauncher.State.PERMISSION_DENIED -> {
                Settings.SHIZUKU_ENABLED.putValue(false)
                shizukuPreference.isChecked = false
            }
            ShizukuFileServiceLauncher.State.UNAVAILABLE,
            ShizukuFileServiceLauncher.State.UNSUPPORTED -> Unit
        }
    }

    companion object {
        private const val SHIZUKU_MANAGER_PACKAGE = "moe.shizuku.privileged.api"
    }
}
