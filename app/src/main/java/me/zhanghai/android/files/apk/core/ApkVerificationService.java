/* Copyright (c) 2026 Material Files contributors. All Rights Reserved. */

package me.zhanghai.android.files.apk.core;

import com.android.apksig.ApkVerifier;
import com.android.apksig.apk.ApkFormatException;
import com.android.apksig.util.DataSources;
import java.io.File;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.RandomAccessFile;
import java.security.GeneralSecurityException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Verifies the actual APK bytes through the Android apksig API. This does not test installation. */
public final class ApkVerificationService {
    /** A minimum of zero asks apksig to use the APK's declared minimum Android version. */
    public Result verify(File apk, int minSdk, ApkOperationCallback callback)
            throws IOException, GeneralSecurityException {
        if (minSdk < 0 || minSdk > 10000) throw new IllegalArgumentException("Invalid minimum SDK");
        callback = ApkIo.callback(callback);
        ApkIo.check(callback);
        apk = ApkIo.input(apk);
        callback.onProgress("verify", 0, -1);
        try (RandomAccessFile input = new RandomAccessFile(apk, "r")) {
            ApkVerifier.Builder builder = new ApkVerifier.Builder(new ApkIo.Source(
                    DataSources.asDataSource(input), callback, "verify"));
            if (minSdk != 0) builder.setMinCheckedPlatformVersion(minSdk);
            ApkVerifier.Result result = builder.build().verify();
            ApkIo.check(callback);
            List<String> schemes = new ArrayList<>();
            if (result.isVerifiedUsingV1Scheme()) schemes.add("v1");
            if (result.isVerifiedUsingV2Scheme()) schemes.add("v2");
            if (result.isVerifiedUsingV3Scheme()) schemes.add("v3");
            if (result.isVerifiedUsingV31Scheme()) schemes.add("v3.1");
            if (result.isVerifiedUsingV4Scheme()) schemes.add("v4");
            Set<String> errors = new LinkedHashSet<>();
            Set<String> warnings = new LinkedHashSet<>();
            addIssues(errors, result.getAllErrors());
            addIssues(warnings, result.getWarnings());
            Map<String, ApkCertificateInfo> certificates = new LinkedHashMap<>();
            for (X509Certificate certificate : result.getSignerCertificates()) {
                addCertificate(certificates, certificate);
            }
            // Invalid signatures may still carry parseable certificates. Result.verified remains
            // the sole trust decision; the UI may inspect these certificates without trusting them.
            for (ApkVerifier.Result.V1SchemeSignerInfo signer : result.getV1SchemeSigners()) {
                addCertificate(certificates, signer.getCertificate());
                addIssues(warnings, signer.getWarnings());
            }
            for (ApkVerifier.Result.V1SchemeSignerInfo signer : result.getV1SchemeIgnoredSigners()) {
                addIssues(warnings, signer.getWarnings());
            }
            for (ApkVerifier.Result.V2SchemeSignerInfo signer : result.getV2SchemeSigners()) {
                addCertificate(certificates, signer.getCertificate());
                addIssues(warnings, signer.getWarnings());
            }
            for (ApkVerifier.Result.V3SchemeSignerInfo signer : result.getV3SchemeSigners()) {
                addCertificate(certificates, signer.getCertificate());
                addIssues(warnings, signer.getWarnings());
            }
            for (ApkVerifier.Result.V3SchemeSignerInfo signer : result.getV31SchemeSigners()) {
                addCertificate(certificates, signer.getCertificate());
                addIssues(warnings, signer.getWarnings());
            }
            if (result.getSourceStampInfo() != null) {
                addIssues(warnings, result.getSourceStampInfo().getWarnings());
            }
            if (!result.isVerified() && errors.isEmpty()) errors.add("APK signature is missing or invalid");
            callback.onProgress("verify", apk.length(), apk.length());
            return new Result(result.isVerified(), schemes, new ArrayList<>(certificates.values()),
                    new ArrayList<>(errors), new ArrayList<>(warnings));
        } catch (InterruptedIOException exception) {
            throw exception;
        } catch (ApkFormatException exception) {
            ApkIo.check(callback);
            return new Result(false, Collections.emptyList(), Collections.emptyList(),
                    Collections.singletonList("Malformed APK: " + exception.getMessage()),
                    Collections.emptyList());
        }
    }

    private static void addIssues(Set<String> result, List<ApkVerifier.IssueWithParams> issues) {
        for (ApkVerifier.IssueWithParams issue : issues) result.add(issue.toString());
    }

    private static void addCertificate(Map<String, ApkCertificateInfo> result,
            X509Certificate certificate) throws GeneralSecurityException {
        if (certificate == null) return;
        ApkCertificateInfo info = new ApkCertificateInfo(certificate);
        result.put(info.sha256Fingerprint, info);
    }

    public static final class Result {
        public final boolean verified;
        public final List<String> schemes;
        public final List<ApkCertificateInfo> certificates;
        public final List<String> errors;
        public final List<String> warnings;

        Result(boolean verified, List<String> schemes, List<ApkCertificateInfo> certificates,
                List<String> errors, List<String> warnings) {
            this.verified = verified;
            this.schemes = Collections.unmodifiableList(new ArrayList<>(schemes));
            this.certificates = Collections.unmodifiableList(new ArrayList<>(certificates));
            this.errors = Collections.unmodifiableList(new ArrayList<>(errors));
            this.warnings = Collections.unmodifiableList(new ArrayList<>(warnings));
        }
    }
}
