/*
 * Copyright (c) 2018 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.apksigner

import android.app.Dialog
import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatDialogFragment
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java8.nio.file.Path
import java8.nio.file.Paths
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.parcelize.Parcelize
import kotlinx.parcelize.WriteWith
import me.zhanghai.android.files.R
import me.zhanghai.android.files.databinding.SignApkDialogBinding
import me.zhanghai.android.files.file.MimeType
import me.zhanghai.android.files.filejob.FileJobService
import me.zhanghai.android.files.filelist.FileItemSet
import me.zhanghai.android.files.filelist.FileListActivity
import me.zhanghai.android.files.filelist.name
import me.zhanghai.android.files.filelist.toUserFriendlyString
import me.zhanghai.android.files.settings.Settings
import me.zhanghai.android.files.ui.UnfilteredArrayAdapter
import me.zhanghai.android.files.util.ParcelableArgs
import me.zhanghai.android.files.util.ParcelableParceler
import me.zhanghai.android.files.util.args
import me.zhanghai.android.files.util.asFileName
import me.zhanghai.android.files.util.asFileNameOrNull
import me.zhanghai.android.files.util.copyToCacheFile
import me.zhanghai.android.files.util.layoutInflater
import me.zhanghai.android.files.util.localFileOrNull
import me.zhanghai.android.files.util.putArgs
import me.zhanghai.android.files.util.show
import me.zhanghai.android.files.util.showToast
import me.zhanghai.android.files.util.takeIfNotEmpty
import me.zhanghai.android.files.util.valueCompat
import java.io.File
import java.net.URI

/**
 * Collects a keystore, key and signature schemes, then hands the actual signing off to
 * [me.zhanghai.android.files.filejob.SignApkFileJob].
 *
 * The keystore is unlocked here rather than in the job so that a wrong password is reported while
 * the user is still looking at the fields they typed it into.
 */
class SignApkDialogFragment : AppCompatDialogFragment(),
    CreateKeyStoreDialogFragment.Listener {
    private val args by args<Args>()

    private lateinit var binding: SignApkDialogBinding

    private val chooseKeyStoreLauncher = registerForActivityResult(
        FileListActivity.OpenFileContract(), this::onKeyStoreChosen
    )

    private var keyStorePath: Path? = null
    private var aliases: List<String> = emptyList()
    private var isUnlocking = false

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        binding = SignApkDialogBinding.inflate(requireContext().layoutInflater)
        val files = args.files.toList()
        binding.apkText.text = if (files.size == 1) {
            files.single().path.name
        } else {
            getString(R.string.sign_apk_multiple_format, files.size)
        }
        binding.outputNameLayout.isVisible = files.size == 1

        if (savedInstanceState != null) {
            keyStorePath = savedInstanceState.getString(STATE_KEY_STORE)?.toPathOrNull()
            aliases = savedInstanceState.getStringArrayList(STATE_ALIASES).orEmpty()
            if (files.size == 1) {
                binding.outputNameEdit.setText(savedInstanceState.getString(STATE_OUTPUT_NAME))
            }
        } else {
            keyStorePath = Settings.SIGN_APK_KEY_STORE.valueCompat.takeIfNotEmpty()?.toPathOrNull()
            if (files.size == 1) {
                binding.outputNameEdit.setText(files.single().path.name.toSignedName())
            }
            binding.v1CheckBox.isChecked = Settings.SIGN_APK_V1.valueCompat
            binding.v2CheckBox.isChecked = Settings.SIGN_APK_V2.valueCompat
            binding.v3CheckBox.isChecked = Settings.SIGN_APK_V3.valueCompat
        }
        updateKeyStoreText()
        updateAliases(aliases)

        binding.chooseKeyStoreButton.setOnClickListener {
            chooseKeyStoreLauncher.launch(listOf(MimeType.ANY))
        }
        binding.createKeyStoreButton.setOnClickListener {
            CreateKeyStoreDialogFragment.show(args.directory, this)
        }
        binding.unlockButton.setOnClickListener { unlock() }

        return MaterialAlertDialogBuilder(requireContext(), theme)
            .setTitle(R.string.sign_apk_title)
            .setView(binding.root)
            .setPositiveButton(R.string.sign_apk_sign, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create()
            .apply {
                // Override the listener so that validation failures keep the dialog open.
                setOnShowListener {
                    getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { onSign() }
                }
            }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)

        outState.putString(STATE_KEY_STORE, keyStorePath?.toUri()?.toString())
        outState.putStringArrayList(STATE_ALIASES, ArrayList(aliases))
        outState.putString(STATE_OUTPUT_NAME, binding.outputNameEdit.text?.toString())
    }

    private fun onKeyStoreChosen(path: Path?) {
        path ?: return
        keyStorePath = path
        updateAliases(emptyList())
        updateKeyStoreText()
    }

    override fun onKeyStoreCreated(path: Path, alias: String, password: String) {
        keyStorePath = path
        binding.storePasswordEdit.setText(password)
        updateKeyStoreText()
        // The keystore was just written, so its contents are known and unlocking will succeed.
        unlock()
    }

    private fun updateKeyStoreText() {
        val path = keyStorePath
        binding.keyStoreText.text = path?.toUserFriendlyString()
            ?: getString(R.string.sign_apk_keystore_none)
    }

    private fun updateAliases(aliases: List<String>) {
        this.aliases = aliases
        val hasAliases = aliases.isNotEmpty()
        binding.aliasLayout.isVisible = hasAliases
        binding.keyPasswordLayout.isVisible = hasAliases
        if (!hasAliases) {
            return
        }
        binding.aliasEdit.setAdapter(
            UnfilteredArrayAdapter(
                binding.aliasEdit.context, R.layout.dropdown_item, objects = aliases
            )
        )
        val remembered = Settings.SIGN_APK_KEY_ALIAS.valueCompat
        val selected = binding.aliasEdit.text?.toString()?.takeIf { it in aliases }
            ?: remembered.takeIf { it in aliases }
            ?: aliases.first()
        binding.aliasEdit.setText(selected, false)
    }

    private fun unlock() {
        val keyStorePath = keyStorePath
        if (keyStorePath == null) {
            showToast(R.string.sign_apk_keystore_required)
            return
        }
        if (isUnlocking) {
            return
        }
        isUnlocking = true
        binding.unlockButton.isEnabled = false
        binding.unlockButton.setText(R.string.sign_apk_unlocking)
        val storePassword = binding.storePasswordEdit.text?.toString().orEmpty().toCharArray()
        lifecycleScope.launch {
            val result = try {
                withContext(Dispatchers.IO) {
                    val file = keyStorePath.toLocalOrCacheFile()
                    Result.success(KeyStores.load(file, storePassword).aliases)
                }
            } catch (e: Exception) {
                e.printStackTrace()
                Result.failure(e)
            }
            isUnlocking = false
            if (!isAdded) {
                return@launch
            }
            binding.unlockButton.isEnabled = true
            binding.unlockButton.setText(R.string.sign_apk_unlock)
            result.onSuccess { aliases ->
                binding.storePasswordLayout.error = null
                if (aliases.isEmpty()) {
                    binding.storePasswordLayout.error = getString(R.string.sign_apk_no_keys)
                    updateAliases(emptyList())
                } else {
                    updateAliases(aliases)
                }
            }.onFailure {
                binding.storePasswordLayout.error = it.message ?: it.toString()
                updateAliases(emptyList())
            }
        }
    }

    private fun onSign() {
        val keyStorePath = keyStorePath
        if (keyStorePath == null) {
            showToast(R.string.sign_apk_keystore_required)
            return
        }
        if (aliases.isEmpty()) {
            unlock()
            return
        }
        val schemes = ApkSigningSchemes(
            binding.v1CheckBox.isChecked, binding.v2CheckBox.isChecked,
            binding.v3CheckBox.isChecked
        )
        if (schemes.isEmpty) {
            showToast(R.string.sign_apk_scheme_required)
            return
        }
        val alias = binding.aliasEdit.text?.toString()?.takeIf { it in aliases } ?: aliases.first()
        val storePassword = binding.storePasswordEdit.text?.toString().orEmpty()
        val keyPassword = binding.keyPasswordEdit.text?.toString()?.takeIfNotEmpty() ?: storePassword
        val files = args.files.toList()
        val outputs = if (files.size == 1) {
            val name = binding.outputNameEdit.text?.toString()?.trim().orEmpty()
            if (name.asFileNameOrNull() == null) {
                binding.outputNameLayout.error = getString(R.string.file_name_error_invalid)
                return
            }
            binding.outputNameLayout.error = null
            listOf(files.single().path to args.directory.resolve(name))
        } else {
            files.map { it.path to it.path.resolveSibling(it.path.name.toSignedName()) }
        }

        // Check the key password before starting a background job, so that a typo is reported here
        // instead of as a notification later on.
        lifecycleScope.launch {
            val throwable = try {
                withContext(Dispatchers.IO) {
                    val file = keyStorePath.toLocalOrCacheFile()
                    KeyStores.load(file, storePassword.toCharArray())
                        .getSigningKey(alias, keyPassword.toCharArray())
                }
                null
            } catch (e: Exception) {
                e.printStackTrace()
                e
            }
            if (!isAdded) {
                return@launch
            }
            if (throwable != null) {
                binding.keyPasswordLayout.error = throwable.message ?: throwable.toString()
                return@launch
            }
            binding.keyPasswordLayout.error = null
            Settings.SIGN_APK_KEY_STORE.putValue(keyStorePath.toUri().toString())
            Settings.SIGN_APK_KEY_ALIAS.putValue(alias)
            Settings.SIGN_APK_V1.putValue(schemes.v1)
            Settings.SIGN_APK_V2.putValue(schemes.v2)
            Settings.SIGN_APK_V3.putValue(schemes.v3)
            for ((input, output) in outputs) {
                FileJobService.signApk(
                    input, output, keyStorePath, storePassword.toCharArray(), alias,
                    keyPassword.toCharArray(), schemes, requireContext()
                )
            }
            showToast(R.string.sign_apk_started)
            dismiss()
        }
    }

    /** apksig and the keystore readers both need a real file, so stage remote ones in the cache. */
    private fun Path.toLocalOrCacheFile(): File =
        localFileOrNull ?: copyToCacheFile(requireContext(), KEY_STORE_CACHE_DIRECTORY)

    companion object {
        private const val STATE_KEY_STORE = "keyStore"
        private const val STATE_ALIASES = "aliases"
        private const val STATE_OUTPUT_NAME = "outputName"
        private const val KEY_STORE_CACHE_DIRECTORY = "key_store"

        fun show(files: FileItemSet, directory: Path, fragment: Fragment) {
            SignApkDialogFragment().putArgs(Args(files, directory)).show(fragment)
        }
    }

    @Parcelize
    class Args(
        val files: FileItemSet,
        val directory: @WriteWith<ParcelableParceler> Path
    ) : ParcelableArgs
}

/** Turns `app.apk` into `app-signed.apk`, keeping any other extension intact. */
private fun String.toSignedName(): String {
    val fileName = asFileName()
    val extensions = fileName.extensions
    return if (extensions.isNotEmpty()) {
        "${fileName.baseName}-signed.$extensions"
    } else {
        "$this-signed"
    }
}

private fun String.toPathOrNull(): Path? =
    try {
        Paths.get(URI(this))
    } catch (e: Exception) {
        e.printStackTrace()
        null
    }
