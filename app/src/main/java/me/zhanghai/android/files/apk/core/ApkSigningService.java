/* Copyright (c) 2026 Material Files contributors. All Rights Reserved. */

package me.zhanghai.android.files.apk.core;

import com.android.apksig.ApkSigner;
import com.android.apksig.apk.ApkFormatException;
import com.android.apksig.util.DataSinks;
import com.android.apksig.util.DataSources;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.security.GeneralSecurityException;
import java.util.Collections;

/**
 * A synchronous worker API. Callers stage provider files in app private storage before calling it.
 * Signing always creates a separate output, and a failed or cancelled operation removes its output.
 */
public final class ApkSigningService {
    /** Checks bounded ZIP metadata before a decoder or ZipFile allocates an expanded work tree. */
    public void validateArchiveStructure(File input, ApkOperationCallback callback) throws IOException {
        callback = ApkIo.callback(callback);
        ApkZipArchive.inspect(ApkIo.input(input), callback);
    }

    public void validateContainer(File input, ApkOperationCallback callback) throws IOException {
        callback = ApkIo.callback(callback);
        ApkZipArchive archive = ApkZipArchive.inspectContainer(ApkIo.input(input), callback);
        archive.requireExpandedLimit(1024L * 1024 * 1024);
        archive.validateContents(input, callback);
    }

    public void validateInput(File input, ApkOperationCallback callback) throws IOException {
        callback = ApkIo.callback(callback);
        input = ApkIo.input(input);
        ApkZipArchive.inspect(input, callback).validateContents(input, callback);
    }

    public ApkVerificationService.Result sign(File input, File output, File workDirectory,
            SigningKeyStoreService.SigningKey key, Options options, ApkOperationCallback callback)
            throws IOException, GeneralSecurityException {
        callback = ApkIo.callback(callback);
        ApkIo.check(callback);
        options.validate();
        input = ApkIo.input(input);
        output = ApkIo.newOutput(output, input);
        workDirectory = workDirectory.getCanonicalFile();
        if (!workDirectory.isDirectory()) throw new IOException("APK working directory does not exist");
        long originalLength = input.length();
        long originalModified = input.lastModified();
        File aligned = null;
        File signed = null;
        boolean outputCreated = false;
        boolean complete = false;
        Throwable failure = null;
        try {
            ApkZipArchive archive = ApkZipArchive.inspect(input, callback);
            archive.validateContents(input, callback);
            ApkIo.check(callback);
            aligned = File.createTempFile("apk-aligned-", ".apk", workDirectory);
            archive.writeAligned(input, aligned, callback);
            ApkZipArchive.inspect(aligned, callback).requireAligned();
            signed = File.createTempFile("apk-signed-", ".apk", workDirectory);
            callback.onProgress("sign", 0, -1);
            ApkSigner.SignerConfig signer = new ApkSigner.SignerConfig.Builder("CERT",
                    key.privateKey(), key.certificateChain()).build();
            try (RandomAccessFile source = new RandomAccessFile(aligned, "r");
                    RandomAccessFile destination = new RandomAccessFile(signed, "rw")) {
                new ApkSigner.Builder(Collections.singletonList(signer))
                        .setInputApk(new ApkIo.Source(DataSources.asDataSource(source), callback, "sign"))
                        .setOutputApk(new ApkIo.Sink(DataSinks.asDataSink(destination), callback, "sign"),
                                new ApkIo.Source(DataSources.asDataSource(destination), callback, "sign"))
                        .setMinSdkVersion(options.minSdk)
                        .setV1SigningEnabled(options.v1Enabled)
                        .setV2SigningEnabled(options.v2Enabled)
                        .setV3SigningEnabled(options.v3Enabled)
                        .setV4SigningEnabled(false)
                        .setOtherSignersSignaturesPreserved(false)
                        .setAlignmentPreserved(true)
                        .setLibraryPageAlignmentBytes(16 * 1024)
                        .setCreatedBy("Material Files (apksig 9.4.1)")
                        .build().sign();
                destination.getFD().sync();
            } catch (ApkFormatException exception) {
                throw new IOException("APK cannot be signed: " + exception.getMessage(), exception);
            }
            ApkIo.check(callback);
            ApkZipArchive.inspect(signed, callback).requireAligned();
            ApkVerificationService.Result result = new ApkVerificationService().verify(
                    signed, options.minSdk, callback);
            if (!result.verified) {
                throw new GeneralSecurityException("Signed APK did not pass verification: "
                        + join(result.errors));
            }
            if (result.certificates.size() != 1 || !result.certificates.get(0).sha256Fingerprint.equals(
                    key.certificates.get(0).sha256Fingerprint)) {
                throw new GeneralSecurityException("Output APK does not have the selected signing certificate");
            }
            if (input.length() != originalLength || input.lastModified() != originalModified) {
                throw new IOException("Input APK changed during signing");
            }
            ApkIo.check(callback);
            ApkIo.createPrivate(output);
            outputCreated = true;
            ApkIo.copy(signed, output, callback);
            complete = true;
            return result;
        } catch (IOException | GeneralSecurityException | RuntimeException | Error exception) {
            try {
                ApkIo.check(callback);
            } catch (java.io.InterruptedIOException cancelled) {
                if (cancelled != exception) cancelled.addSuppressed(exception);
                failure = cancelled;
                throw cancelled;
            }
            failure = exception;
            throw exception;
        } finally {
            IOException cleanup = ApkIo.delete(aligned, failure);
            IOException signedCleanup = ApkIo.delete(signed, failure != null ? failure : cleanup);
            if (cleanup == null) cleanup = signedCleanup;
            if (!complete && outputCreated) {
                IOException outputCleanup = ApkIo.delete(output, failure != null ? failure : cleanup);
                if (cleanup == null) cleanup = outputCleanup;
            }
            if (failure == null && cleanup != null) throw cleanup;
        }
    }

    private static String join(java.util.List<String> values) {
        StringBuilder result = new StringBuilder();
        for (String value : values) {
            if (result.length() != 0) result.append("; ");
            result.append(value);
        }
        return result.toString();
    }

    public static final class Options {
        public final int minSdk;
        public final boolean v1Enabled;
        public final boolean v2Enabled;
        public final boolean v3Enabled;

        public Options(int minSdk, boolean v1Enabled, boolean v2Enabled, boolean v3Enabled) {
            this.minSdk = minSdk;
            this.v1Enabled = v1Enabled;
            this.v2Enabled = v2Enabled;
            this.v3Enabled = v3Enabled;
            validate();
        }

        public static Options forMinSdk(int minSdk) {
            return new Options(minSdk, minSdk < 24, true, true);
        }

        private void validate() {
            if (minSdk < 1 || minSdk > 10000) throw new IllegalArgumentException("Invalid minimum Android SDK");
            if (!v1Enabled && !v2Enabled && !v3Enabled) {
                throw new IllegalArgumentException("Choose at least one APK signing scheme");
            }
            if (minSdk < 24 && !v1Enabled) {
                throw new IllegalArgumentException("Android 6.0 and earlier require v1 signing");
            }
            if (minSdk < 28 && !v1Enabled && !v2Enabled) {
                throw new IllegalArgumentException("Android 8.1 and earlier require v1 or v2 signing");
            }
        }
    }
}
