/*
 * Copyright (c) 2018 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.apksigner

import android.app.Dialog
import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatDialogFragment
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java8.nio.file.Path
import java8.nio.file.StandardOpenOption
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.parcelize.Parcelize
import kotlinx.parcelize.WriteWith
import me.zhanghai.android.files.R
import me.zhanghai.android.files.databinding.CreateKeyStoreDialogBinding
import me.zhanghai.android.files.filelist.name
import me.zhanghai.android.files.provider.common.newOutputStream
import me.zhanghai.android.files.util.ParcelableArgs
import me.zhanghai.android.files.util.ParcelableParceler
import me.zhanghai.android.files.util.args
import me.zhanghai.android.files.util.asFileNameOrNull
import me.zhanghai.android.files.util.layoutInflater
import me.zhanghai.android.files.util.putArgs
import me.zhanghai.android.files.util.show
import me.zhanghai.android.files.util.showToast
import me.zhanghai.android.files.util.takeIfNotEmpty
import java.io.File

/** Creates a keystore holding a single self-signed key, ready to sign APKs with. */
class CreateKeyStoreDialogFragment : AppCompatDialogFragment() {
    private val args by args<Args>()

    private lateinit var binding: CreateKeyStoreDialogBinding

    private val listener: Listener
        get() = requireParentFragment() as Listener

    private var isCreating = false

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        binding = CreateKeyStoreDialogBinding.inflate(requireContext().layoutInflater)
        if (savedInstanceState == null) {
            binding.fileNameEdit.setText(DEFAULT_FILE_NAME)
            binding.aliasEdit.setText(DEFAULT_ALIAS)
            binding.validityEdit.setText(KeyStoreGenerator.DEFAULT_VALIDITY_YEARS.toString())
        }
        return MaterialAlertDialogBuilder(requireContext(), theme)
            .setTitle(R.string.create_key_store_title)
            .setView(binding.root)
            .setPositiveButton(android.R.string.ok, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create()
            .apply {
                // Override the listener so that a validation failure keeps the dialog open.
                setOnShowListener {
                    getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { onOk() }
                }
            }
    }

    private fun onOk() {
        if (isCreating) {
            return
        }
        val fileName = binding.fileNameEdit.text?.toString()?.trim().orEmpty().let {
            if (it.endsWith(".jks", true) || it.endsWith(".keystore", true)
                || it.endsWith(".p12", true) || it.endsWith(".bks", true)) {
                it
            } else {
                "$it$DEFAULT_EXTENSION"
            }
        }
        if (fileName.asFileNameOrNull() == null) {
            binding.fileNameLayout.error = getString(R.string.file_name_error_invalid)
            return
        }
        binding.fileNameLayout.error = null
        val password = binding.passwordEdit.text?.toString().orEmpty()
        if (password.length < MIN_PASSWORD_LENGTH) {
            binding.passwordLayout.error =
                getString(R.string.create_key_store_password_too_short)
            return
        }
        binding.passwordLayout.error = null
        if (password != binding.confirmPasswordEdit.text?.toString()) {
            binding.confirmPasswordLayout.error =
                getString(R.string.create_key_store_password_mismatch)
            return
        }
        binding.confirmPasswordLayout.error = null
        val alias = binding.aliasEdit.text?.toString()?.trim()?.takeIfNotEmpty() ?: DEFAULT_ALIAS
        val commonName = binding.commonNameEdit.text?.toString()?.trim().orEmpty()
        if (commonName.isEmpty()) {
            binding.commonNameLayout.error =
                getString(R.string.create_key_store_common_name_required)
            return
        }
        binding.commonNameLayout.error = null
        val validityYears = binding.validityEdit.text?.toString()?.trim()?.toIntOrNull()
            ?.takeIf { it > 0 } ?: KeyStoreGenerator.DEFAULT_VALIDITY_YEARS
        val distinguishedName = KeyStoreGenerator.DistinguishedName(
            commonName,
            binding.organizationalUnitEdit.text?.toString(),
            binding.organizationEdit.text?.toString(),
            binding.localityEdit.text?.toString(),
            binding.stateEdit.text?.toString(),
            binding.countryEdit.text?.toString()
        )
        create(fileName, password.toCharArray(), alias, distinguishedName, validityYears)
    }

    private fun create(
        fileName: String,
        password: CharArray,
        alias: String,
        distinguishedName: KeyStoreGenerator.DistinguishedName,
        validityYears: Int
    ) {
        isCreating = true
        setUiEnabled(false)
        val targetPath = args.directory.resolve(fileName)
        lifecycleScope.launch {
            val throwable = try {
                withContext(Dispatchers.IO) {
                    // Key generation needs a real file, so build it in the cache and then place it
                    // wherever the user asked for, which may be a remote share.
                    val cacheFile = File.createTempFile("keystore", ".p12", requireContext().cacheDir)
                    try {
                        // PKCS #12 protects the key with the keystore password, so they are one and
                        // the same here.
                        KeyStoreGenerator.generate(
                            cacheFile, password, alias, password, distinguishedName, validityYears
                        )
                        targetPath.newOutputStream(
                            StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE
                        ).use { outputStream ->
                            cacheFile.inputStream().use { it.copyTo(outputStream) }
                        }
                    } finally {
                        cacheFile.delete()
                    }
                }
                null
            } catch (e: Exception) {
                e.printStackTrace()
                e
            }
            isCreating = false
            if (!isAdded) {
                return@launch
            }
            if (throwable != null) {
                setUiEnabled(true)
                showToast(throwable.toString())
                return@launch
            }
            showToast(getString(R.string.create_key_store_success_format, targetPath.name))
            listener.onKeyStoreCreated(targetPath, alias, String(password))
            dismiss()
        }
    }

    private fun setUiEnabled(enabled: Boolean) {
        (dialog as AlertDialog?)?.apply {
            getButton(AlertDialog.BUTTON_POSITIVE)?.isEnabled = enabled
            getButton(AlertDialog.BUTTON_NEGATIVE)?.isEnabled = enabled
        }
        binding.root.isEnabled = enabled
    }

    companion object {
        private const val DEFAULT_FILE_NAME = "keystore.jks"
        private const val DEFAULT_EXTENSION = ".jks"
        private const val DEFAULT_ALIAS = "key0"
        private const val MIN_PASSWORD_LENGTH = 6

        fun show(directory: Path, fragment: Fragment) {
            CreateKeyStoreDialogFragment().putArgs(Args(directory)).show(fragment)
        }
    }

    @Parcelize
    class Args(val directory: @WriteWith<ParcelableParceler> Path) : ParcelableArgs

    interface Listener {
        /**
         * Called after the keystore has been written, with the password so that the caller can go
         * straight on to signing without asking for it again.
         */
        fun onKeyStoreCreated(path: Path, alias: String, password: String)
    }
}
