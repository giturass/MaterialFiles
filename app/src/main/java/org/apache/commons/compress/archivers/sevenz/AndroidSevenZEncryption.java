/*
 * Copyright (c) 2026 Material Files contributors
 * All Rights Reserved.
 */

package org.apache.commons.compress.archivers.sevenz;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.security.SecureRandom;

/**
 * Small bridge to the AES implementation in Commons Compress 1.28.0. Its public password
 * constructor uses SecureRandom.getInstanceStrong(), which Android only provides from API 26.
 * Keep this package-local integration in sync when updating Commons Compress; see docs/7z.md.
 */
public final class AndroidSevenZEncryption {
    private static final SecureRandom RANDOM = new SecureRandom();

    private AndroidSevenZEncryption() {}

    private static AES256Options options(char[] password) throws IOException {
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedIOException();
        }
        byte[] salt = new byte[16];
        byte[] iv = new byte[16];
        RANDOM.nextBytes(salt);
        RANDOM.nextBytes(iv);
        // 2^19 SHA-256 rounds, matching 7-Zip's default. Never reuse an IV between entries.
        AES256Options options = new AES256Options(password, salt, iv, 19);
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedIOException();
        }
        return options;
    }

    public static SevenZMethodConfiguration newEncryption(char[] password) throws IOException {
        return new SevenZMethodConfiguration(SevenZMethod.AES256SHA256, options(password));
    }

    public static EncryptedHeader encryptHeader(byte[] header, char[] password) throws IOException {
        AES256Options options = options(password);
        AES256SHA256Decoder coder = new AES256SHA256Decoder();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (OutputStream encrypted = coder.encode(bytes, options)) {
            encrypted.write(header);
        }
        return new EncryptedHeader(bytes.toByteArray(), coder.getOptionsAsProperties(options));
    }

    public static final class EncryptedHeader {
        public final byte[] data;
        public final byte[] properties;

        private EncryptedHeader(byte[] data, byte[] properties) {
            this.data = data;
            this.properties = properties;
        }
    }
}
