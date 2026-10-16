/* Copyright 2026 Material Files contributors. SPDX-License-Identifier: Apache-2.0 */
package me.zhanghai.android.files.apk.tests;

import brut.common.BrutException;
import brut.androlib.exceptions.AndrolibException;
import brut.androlib.smali.SmaliBuilder;
import brut.androlib.smali.SmaliDecoder;
import brut.util.BackgroundWorker;
import brut.util.OS;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Runs against the actual app DEX under ART, or against the sources on a JVM. */
public final class ApktoolRuntimeLifecycleTest {
    private static final long WAIT_MS = 10000;
    private static final AtomicInteger CHECKS = new AtomicInteger();
    private static File work;

    public static void main(String[] args) throws Exception {
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            error.printStackTrace(System.err);
            System.err.flush();
            System.exit(1);
        });
        work = new File(args[0]);
        Files.createDirectories(work.toPath());
        try {
            check("version".equals(OS.execAndReturn(new String[] {
                "sh", "-c", "printf version"
            })), "stdout was not collected");
            testFailureOutput();
            testProcess(false, false);
            testProcess(false, true);
            testProcess(true, false);
            testProcess(true, true);
            testInterruptedBeforeLaunch();
            testWorkerShutdown();
            testTempIsolation();
            testDeepClassPath();
            checkNoCollectors();
            System.out.println("PASS Apktool runtime lifecycle: timeout, cancellation, process reaping, "
                + "worker shutdown, bounded diagnostics, temp isolation");
            System.out.println("APKTOOL_RUNTIME_LIFECYCLE passed=" + CHECKS.get() + " checks=" + CHECKS.get());
        } finally {
            OS.setDefaultTempDirectory(null);
        }
    }

    private static void testFailureOutput() throws Exception {
        try {
            OS.exec(new String[] { "sh", "-c", "printf diagnostic-tail >&2; exit 7" });
            throw new AssertionError("Nonzero exit was accepted");
        } catch (BrutException expected) {
            check(expected.getMessage().contains("exit code = 7"), "exit code missing");
            check(expected.getMessage().contains("diagnostic-tail"), "stderr missing");
        }
        try {
            OS.exec(new String[] { new File(work, "missing-executable").getAbsolutePath() });
            throw new AssertionError("Missing executable was accepted");
        } catch (BrutException expected) {
            // Launch failure must also clean up its collector executor.
        }
        // Android's mksh launches an external printf. Generate the payload in one invocation so
        // this checks capture/draining rather than timing out after thousands of process launches.
        String bounded = OS.execAndReturn(new String[] {
            "sh", "-c", "printf '%032768d%s' 0 tail"
        });
        check(bounded != null, "large output command failed or timed out");
        check(bounded.length() == 16 * 1024, "process output capture has the wrong length: "
            + bounded.length());
        check(bounded.endsWith("tail"), "process output capture lost its tail");
        for (int i = 0; i < bounded.length() - 4; ++i) {
            if (bounded.charAt(i) != '0') {
                throw new AssertionError("process output capture changed the retained payload at " + i);
            }
        }
    }

    private static void testProcess(boolean returnOutput, boolean cancel) throws Exception {
        File pidFile = new File(work, "pid-" + returnOutput + "-" + cancel);
        Files.deleteIfExists(pidFile.toPath());
        String[] command = { "sh", "-c", "printf '%s' \"$$\" > \"$1\"; exec sleep 60",
            "apktool-lifecycle-test", pidFile.getAbsolutePath() };
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicBoolean interrupted = new AtomicBoolean();
        Thread caller = new Thread(() -> {
            try {
                long timeout = cancel ? 30 : 2;
                if (returnOutput) {
                    check(OS.execAndReturn(command, timeout, TimeUnit.SECONDS) == null,
                        "cancelled/timed-out command returned success");
                } else {
                    try {
                        OS.exec(command, timeout, TimeUnit.SECONDS);
                        throw new AssertionError("cancelled/timed-out command returned success");
                    } catch (BrutException expected) {
                        if (!cancel) {
                            check(expected.getMessage().contains("timed out"), "wrong timeout error");
                        }
                    }
                }
                interrupted.set(Thread.currentThread().isInterrupted());
            } catch (Throwable ex) {
                failure.set(ex);
            }
        }, "apktool-lifecycle-caller");
        caller.start();
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(WAIT_MS);
        String pid = "";
        while (pid.isEmpty() && System.nanoTime() < deadline) {
            if (pidFile.isFile()) {
                pid = new String(Files.readAllBytes(pidFile.toPath()), StandardCharsets.US_ASCII).trim();
            }
            if (pid.isEmpty()) {
                Thread.sleep(10);
            }
        }
        check(pid.matches("[0-9]+"), "child failed to start");
        File childProc = new File("/proc/" + pid);
        check(childProc.exists(), "child was not observed alive before cancellation/timeout");
        if (cancel) {
            caller.interrupt();
        }
        caller.join(WAIT_MS);
        check(!caller.isAlive(), "process cleanup did not return");
        check(failure.get() == null, "process caller failed: " + failure.get());
        check(!childProc.exists(), "child process survived cleanup: " + pid);
        check(interrupted.get() == cancel, "interrupt status was not preserved");
        checkNoCollectors();
    }

    private static void testInterruptedBeforeLaunch() throws Exception {
        File marker = new File(work, "must-not-exist");
        Files.deleteIfExists(marker.toPath());
        Thread.currentThread().interrupt();
        try {
            check(OS.execAndReturn(new String[] {
                "sh", "-c", "touch \"$1\"", "apktool-lifecycle-test", marker.getAbsolutePath()
            }) == null, "pre-interrupted exec returned output");
            check(Thread.currentThread().isInterrupted(), "pre-interrupted exec cleared interruption");
            check(!marker.exists(), "pre-interrupted exec launched its process");
        } finally {
            Thread.interrupted();
        }
    }

    private static void testWorkerShutdown() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        AtomicBoolean taskStopped = new AtomicBoolean();
        AtomicBoolean interrupted = new AtomicBoolean();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread caller = new Thread(() -> {
            BackgroundWorker worker = new BackgroundWorker(1);
            worker.submit(() -> {
                started.countDown();
                try {
                    Thread.sleep(60000);
                } catch (InterruptedException expected) {
                    // Deliberately take time to unwind after Future.cancel(true).
                    long until = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(250);
                    while (System.nanoTime() < until) {
                        Thread.yield();
                    }
                } finally {
                    taskStopped.set(true);
                }
            });
            try {
                worker.waitForFinish();
                failure.set(new AssertionError("interrupted waitForFinish returned success"));
            } catch (RuntimeException expected) {
                interrupted.set(Thread.currentThread().isInterrupted());
                if (!taskStopped.get()) {
                    failure.set(new AssertionError("waitForFinish returned before task stopped"));
                }
            } finally {
                worker.shutdownNow();
            }
        }, "apktool-worker-lifecycle");
        caller.start();
        check(started.await(WAIT_MS, TimeUnit.MILLISECONDS), "worker did not start");
        caller.interrupt();
        caller.join(WAIT_MS);
        check(!caller.isAlive() && taskStopped.get(), "worker survived cancellation");
        check(interrupted.get(), "worker cleanup lost caller interruption");
        check(failure.get() == null, "worker lifecycle failed: " + failure.get());
    }

    private static void testTempIsolation() throws Exception {
        File first = new File(work, "temp-first");
        File second = new File(work, "temp-second");
        OS.setDefaultTempDirectory(first);
        CountDownLatch inherited = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread child = new Thread(() -> {
            try {
                inherited.countDown();
                release.await();
                check(first.equals(OS.getDefaultTempDirectory()), "parent redirected a previous task");
                File scratch = OS.createTempFile("apktool", ".tmp");
                check(first.equals(scratch.getParentFile()), "temporary file escaped its task");
                Files.delete(scratch.toPath());
            } catch (Throwable ex) {
                failure.set(ex);
            }
        });
        child.start();
        check(inherited.await(WAIT_MS, TimeUnit.MILLISECONDS), "temp test child did not start");
        OS.setDefaultTempDirectory(second);
        release.countDown();
        child.join(WAIT_MS);
        check(!child.isAlive() && failure.get() == null, "temp inheritance failed: " + failure.get());
        check(second.getAbsolutePath().equals(OS.execAndReturn(new String[] {
            "sh", "-c", "printf '%s' \"$TMPDIR\""
        })), "aapt2 did not receive its task TMPDIR");
        OS.setDefaultTempDirectory(null);
    }

    private static void checkNoCollectors() {
        for (Thread thread : Thread.getAllStackTraces().keySet()) {
            check(!thread.isAlive() || !thread.getName().equals("apktool-process-output"),
                "process output collector survived cleanup");
        }
    }

    private static void testDeepClassPath() throws Exception {
        File source = new File(work, "deep-class-source");
        Files.createDirectories(source.toPath());
        StringBuilder descriptor = new StringBuilder("L");
        for (int i = 0; i < 32; ++i) {
            descriptor.append("p/");
        }
        descriptor.append("Deep;");
        String smali = ".class public " + descriptor + "\n.super Ljava/lang/Object;\n";
        Files.write(new File(source, "Deep.smali").toPath(), smali.getBytes(StandardCharsets.UTF_8));
        File dex = new File(work, "deep-classes.dex");
        new SmaliBuilder(30).build(source, dex);
        File apk = new File(work, "deep-code.zip");
        try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(apk))) {
            zip.putNextEntry(new ZipEntry("classes.dex"));
            Files.copy(dex.toPath(), zip);
            zip.closeEntry();
        }
        File decoded = new File(work, "deep-code-decoded");
        Files.createDirectories(decoded.toPath());
        try {
            new SmaliDecoder(apk, true).decode("classes.dex", decoded);
            throw new AssertionError("Deep DEX class descriptor was accepted");
        } catch (AndrolibException expected) {
            Throwable cause = expected;
            while (cause.getCause() != null) {
                cause = cause.getCause();
            }
            check(cause.getMessage() != null && cause.getMessage().contains("32 levels"),
                "deep DEX failed for an unrelated reason: " + cause);
        }
        check(!new File(decoded, "smali/p").exists(),
            "deep class directories were created before validation");
    }

    private static void check(boolean condition, String message) {
        CHECKS.incrementAndGet();
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
