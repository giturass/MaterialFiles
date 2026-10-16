/*
 *  Modified for Material Files (2026); see third_party/apktool-android/README.md.
 *  Copyright (C) 2010 Ryszard Wiśniewski <brut.alll@gmail.com>
 *  Copyright (C) 2010 Connor Tumbleson <connor.tumbleson@gmail.com>
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *       https://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package brut.util;

import brut.common.BrutException;
import brut.common.Log;

import java.io.Closeable;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public final class OS {
    private static final String TAG = "";

    /**
     * Thread-scoped override for the directory in which transient files and
     * directories are created. When {@code null}, {@link File#createTempFile}
     * uses {@code java.io.tmpdir}.
     *
     * On Android, {@code java.io.tmpdir} defaults to {@code /tmp} which does
     * not exist; the host must call this (or {@link brut.androlib.Config#setTempDirectory})
     * with an app-writable location before any decode/build call.
     */
    // Decode/build bind their Config for the duration of an operation. Worker
    // threads inherit that binding, so a subsequent task cannot redirect a
    // previous task's temporary files into its own working directory.
    private static final InheritableThreadLocal<File> sDefaultTempDirectory = new InheritableThreadLocal<>();

    private static final long EXEC_TIMEOUT_SECONDS = 10 * 60;

    private OS() {
        // Private constructor for utility class.
    }

    public static void setDefaultTempDirectory(File dir) {
        if (dir != null) {
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
        }
        if (dir == null) {
            sDefaultTempDirectory.remove();
        } else {
            sDefaultTempDirectory.set(dir);
        }
    }

    public static File getDefaultTempDirectory() {
        return sDefaultTempDirectory.get();
    }

    public static File createTempFile(String prefix, String suffix) throws IOException {
        throwIfInterrupted();
        File parent = getDefaultTempDirectory();
        if (parent != null) {
            return File.createTempFile(prefix, suffix, parent);
        }
        return File.createTempFile(prefix, suffix);
    }

    public static void mkdir(String dir) {
        mkdir(new File(dir));
    }

    public static void mkdir(File dir) {
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
    }

    public static void rmfile(String file) {
        rmfile(new File(file));
    }

    public static void rmfile(File file) {
        //noinspection ResultOfMethodCallIgnored
        file.delete();
    }

    public static void rmdir(String dir) {
        rmdir(new File(dir));
    }

    public static void rmdir(File dir) {
        if (!dir.isDirectory()) {
            return;
        }

        File[] files = dir.listFiles();
        if (files == null) {
            return;
        }

        for (File file : files) {
            if (file.isDirectory()) {
                rmdir(file);
            } else {
                rmfile(file);
            }
        }
        rmfile(dir);
    }

    public static void mvfile(String src, String dest) throws BrutException {
        mvfile(new File(src), new File(dest));
    }

    public static void mvfile(File src, File dest) throws BrutException {
        try {
            Files.move(src.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException ex) {
            throw new BrutException("Could not move file: " + src, ex);
        }
    }

    public static void cpfile(String src, String dest) throws BrutException {
        cpfile(new File(src), new File(dest));
    }

    public static void cpfile(File src, File dest) throws BrutException {
        if (!src.isFile()) {
            return;
        }

        try {
            Files.copy(src.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException ex) {
            throw new BrutException("Could not copy file: " + src, ex);
        }
    }

    public static void cpdir(String src, String dest) throws BrutException {
        cpdir(new File(src), new File(dest));
    }

    public static void cpdir(File src, File dest) throws BrutException {
        if (!src.isDirectory()) {
            return;
        }

        mkdir(dest);

        File[] files = src.listFiles();
        if (files == null) {
            return;
        }

        for (File file : files) {
            File destFile = new File(dest, file.getName());
            if (file.isDirectory()) {
                cpdir(file, destFile);
            } else {
                cpfile(file, destFile);
            }
        }
    }

    public static void exec(String[] cmd) throws BrutException {
        exec(cmd, EXEC_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    /** Runs a non-interactive command, always reaping it before returning. */
    public static void exec(String[] cmd, long timeout, TimeUnit unit) throws BrutException {
        try {
            runProcess(cmd, timeout, unit, true);
        } catch (IOException ex) {
            throw new BrutException("could not exec: " + Arrays.toString(cmd), ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new BrutException("Execution cancelled: " + Arrays.toString(cmd), ex);
        }
    }

    public static String execAndReturn(String[] cmd) {
        return execAndReturn(cmd, 15, TimeUnit.SECONDS);
    }

    public static String execAndReturn(String[] cmd, long timeout, TimeUnit unit) {
        try {
            return runProcess(cmd, timeout, unit, false);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return null;
        } catch (IOException | BrutException ignored) {
            return null;
        }
    }

    private static String runProcess(String[] cmd, long timeout, TimeUnit unit, boolean log)
            throws IOException, InterruptedException, BrutException {
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedException("Execution cancelled before launch");
        }
        if (timeout <= 0) {
            throw new IllegalArgumentException("timeout must be positive");
        }
        Process process = null;
        ExecutorService executor = null;
        try {
            ProcessBuilder builder = new ProcessBuilder(cmd);
            File tempDirectory = getDefaultTempDirectory();
            if (tempDirectory != null) {
                builder.environment().put("TMPDIR", tempDirectory.getAbsolutePath());
            }
            process = builder.start();
            process.getOutputStream().close();
            executor = Executors.newFixedThreadPool(2, task -> {
                Thread thread = new Thread(task, "apktool-process-output");
                thread.setDaemon(true);
                return thread;
            });
            StreamCollector stdout = new StreamCollector(process.getInputStream(), log, false);
            StreamCollector stderr = new StreamCollector(process.getErrorStream(), log, true);
            Future<?> outTask = executor.submit(stdout);
            Future<?> errTask = executor.submit(stderr);
            if (!process.waitFor(timeout, unit)) {
                throw new BrutException("Execution timed out: " + Arrays.toString(cmd));
            }
            try {
                outTask.get(5, TimeUnit.SECONDS);
                errTask.get(5, TimeUnit.SECONDS);
            } catch (ExecutionException | TimeoutException ex) {
                throw new BrutException("Could not collect process output: " + Arrays.toString(cmd), ex);
            }
            if (process.exitValue() != 0) {
                throw new BrutException("Execution failed (exit code = " + process.exitValue() + "): "
                    + Arrays.toString(cmd) + System.lineSeparator() + stderr.get());
            }
            return stdout.get();
        } finally {
            // Cancellation must not leave aapt2 running while its working
            // directory is deleted or another clone job starts.
            boolean interrupted = Thread.interrupted();
            if (process != null) {
                if (process.isAlive()) {
                    process.destroy();
                    try {
                        if (!process.waitFor(1, TimeUnit.SECONDS)) {
                            process.destroyForcibly();
                        }
                    } catch (InterruptedException ex) {
                        interrupted = true;
                        process.destroyForcibly();
                    }
                }
                while (true) {
                    try {
                        process.waitFor();
                        break;
                    } catch (InterruptedException ex) {
                        interrupted = true;
                        process.destroyForcibly();
                    }
                }
                closeQuietly(process.getOutputStream());
                closeQuietly(process.getInputStream());
                closeQuietly(process.getErrorStream());
            }
            if (executor != null) {
                executor.shutdownNow();
                while (!executor.isTerminated()) {
                    try {
                        executor.awaitTermination(1, TimeUnit.SECONDS);
                    } catch (InterruptedException ex) {
                        interrupted = true;
                    }
                }
            }
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static void closeQuietly(Closeable stream) {
        try {
            stream.close();
        } catch (IOException ignored) {
        }
    }

    public static void throwIfInterrupted() {
        if (Thread.currentThread().isInterrupted()) {
            throw new CancellationException("Apktool operation cancelled");
        }
    }

    public static File createTempDirectory() throws BrutException {
        throwIfInterrupted();
        try {
            File parent = getDefaultTempDirectory();
            return (parent != null ? Files.createTempDirectory(parent.toPath(), "BRUT")
                : Files.createTempDirectory("BRUT")).toFile();
        } catch (IOException ex) {
            throw new BrutException("Could not create tmp dir", ex);
        }
    }

    private static class StreamCollector implements Runnable {
        private static final int CAPTURE_LIMIT_CHARS = 16 * 1024;
        private final InputStream mIn;
        private final boolean mLog;
        private final boolean mError;
        private final StringBuilder mBuffer = new StringBuilder();

        StreamCollector(InputStream in, boolean log, boolean error) {
            mIn = in;
            mLog = log;
            mError = error;
        }

        @Override
        public void run() {
            try (InputStreamReader reader = new InputStreamReader(mIn)) {
                char[] buffer = new char[1024];
                int count;
                while ((count = reader.read(buffer)) != -1) {
                    synchronized (mBuffer) {
                        mBuffer.append(buffer, 0, count);
                        if (mBuffer.length() > CAPTURE_LIMIT_CHARS) {
                            mBuffer.delete(0, mBuffer.length() - CAPTURE_LIMIT_CHARS);
                        }
                    }
                    if (mLog) {
                        String text = new String(buffer, 0, count);
                        if (mError) {
                            Log.w(TAG, text);
                        } else {
                            Log.i(TAG, text);
                        }
                    }
                }
            } catch (IOException ignored) {
            }
        }

        public String get() {
            synchronized (mBuffer) {
                return mBuffer.toString();
            }
        }
    }
}
