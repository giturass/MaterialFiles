/* Copyright (c) 2026 Material Files contributors. All Rights Reserved. */

package me.zhanghai.android.files.apk.core;

import java.security.MessageDigest;
import java.security.GeneralSecurityException;
import java.security.cert.X509Certificate;

/** Public certificate information only; this model never contains a private key or a password. */
public final class ApkCertificateInfo {
    public final String subject;
    public final String issuer;
    public final String serialNumber;
    public final String sha256Fingerprint;
    public final String publicKeyAlgorithm;
    public final long notBefore;
    public final long notAfter;

    public ApkCertificateInfo(X509Certificate certificate) throws GeneralSecurityException {
        subject = certificate.getSubjectX500Principal().getName();
        issuer = certificate.getIssuerX500Principal().getName();
        serialNumber = certificate.getSerialNumber().toString(16).toUpperCase(java.util.Locale.ROOT);
        publicKeyAlgorithm = certificate.getPublicKey().getAlgorithm();
        notBefore = certificate.getNotBefore().getTime();
        notAfter = certificate.getNotAfter().getTime();
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(certificate.getEncoded());
        char[] hex = "0123456789ABCDEF".toCharArray();
        StringBuilder fingerprint = new StringBuilder(digest.length * 3 - 1);
        for (int i = 0; i < digest.length; ++i) {
            if (i != 0) fingerprint.append(':');
            int value = digest[i] & 0xff;
            fingerprint.append(hex[value >>> 4]).append(hex[value & 15]);
        }
        sha256Fingerprint = fingerprint.toString();
    }
}
