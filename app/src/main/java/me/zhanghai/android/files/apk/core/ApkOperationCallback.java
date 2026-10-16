/* Copyright (c) 2026 Material Files contributors. All Rights Reserved. */

package me.zhanghai.android.files.apk.core;

import java.io.InterruptedIOException;

/** Callbacks run on the worker thread. A negative total means that the amount is unknown. */
public interface ApkOperationCallback {
    ApkOperationCallback NONE = new ApkOperationCallback() {
        @Override public void checkCancelled() throws InterruptedIOException {}
        @Override public void onProgress(String phase, long completed, long total) {}
    };

    void checkCancelled() throws InterruptedIOException;

    void onProgress(String phase, long completed, long total);
}
