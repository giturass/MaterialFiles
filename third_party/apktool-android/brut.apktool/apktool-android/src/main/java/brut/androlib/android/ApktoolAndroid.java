/*
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
package brut.androlib.android;

import android.content.Context;

import brut.androlib.Config;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * Single integration point for hosts that want to drive Apktool from inside
 * an Android app.
 *
 * <p>Wires three things that have no sensible defaults on Android:</p>
 * <ul>
 *   <li><b>aapt2 binary</b> — points at the {@code libaapt2.so} extracted
 *       from the AAR's {@code jniLibs/} into
 *       {@link android.content.pm.ApplicationInfo#nativeLibraryDir}. This is
 *       the only directory tree on Android API 29+ that allows the app to
 *       {@code exec()} a binary out of its private data.</li>
 *   <li><b>framework directory</b> — under {@code Context#getFilesDir()} so
 *       framework APKs survive across runs.</li>
 *   <li><b>temp directory</b> — under {@code Context#getCacheDir()}; ART's
 *       {@code java.io.tmpdir} resolves to the non-existent {@code /tmp}.</li>
 * </ul>
 *
 * <p>Typical usage:</p>
 *
 * <pre>{@code
 * Config config = ApktoolAndroid.configure(context);
 * config.setForced(true);
 * new ApkDecoder(apkFile, config).decode(outDir);
 * }</pre>
 *
 * <p>The configured {@link Config} is otherwise a normal Apktool config; all
 * mutators (decode sources, debug mode, framework tag, ...) work as on
 * desktop.</p>
 *
 * <p><b>Heap sizing for hosts.</b> Apktool keeps the parsed resource table,
 * a {@code ResId} interning pool and aapt2 staging buffers in heap during
 * decode/build. ART's default per-app cap is ~192 MB, which is too tight
 * for typical Play-Store APKs (50&ndash;150 MB). Hosts that decode APKs of
 * that size should declare {@code android:largeHeap="true"} on their
 * {@code <application>} element to raise the cap to ~512 MB. Without it
 * decode of large APKs will surface as
 * {@link java.lang.OutOfMemoryError} from {@code ResId.of} or the
 * resource-table parser.</p>
 */
public final class ApktoolAndroid {

    /** Default version tag stamped into ApkInfo when the host doesn't override. */
    private static final String DEFAULT_VERSION = "android";

    /** Filename of the bundled aapt2 binary inside {@code jniLibs/<abi>/}. */
    public static final String AAPT2_LIBRARY_NAME = "libaapt2.so";

    private ApktoolAndroid() {
        // Static utility class.
    }

    /**
     * Builds an Android-ready {@link Config} for the given app context.
     *
     * <p>Equivalent to {@link #configure(Context, String)} with a default
     * version tag.</p>
     */
    public static Config configure(Context context) {
        return configure(context, resolveVersion());
    }

    /**
     * Builds an Android-ready {@link Config} stamped with the given Apktool
     * version label. The label only appears in log lines and in the
     * {@code apktool.yml} written by {@code ApkDecoder}; it does not change
     * decode/build behaviour.
     */
    public static Config configure(Context context, String version) {
        if (context == null) {
            throw new NullPointerException("context");
        }
        Context appCtx = context.getApplicationContext();
        if (appCtx == null) {
            // Some test harnesses return null from getApplicationContext().
            appCtx = context;
        }

        Config config = new Config(version != null ? version : DEFAULT_VERSION);

        File aaptBinary = new File(appCtx.getApplicationInfo().nativeLibraryDir, AAPT2_LIBRARY_NAME);
        // We don't sanity-check existence here because some hosts may want
        // to override with their own aapt2 path before any decode/build call.
        // ApkBuilder will surface a clear error if the path is wrong.
        config.setAaptBinary(aaptBinary.getAbsolutePath());

        File frameworkDir = new File(appCtx.getFilesDir(), "apktool/framework");
        if (!frameworkDir.exists()) {
            //noinspection ResultOfMethodCallIgnored
            frameworkDir.mkdirs();
        }
        config.setFrameworkDirectory(frameworkDir.getAbsolutePath());

        File tempDir = new File(appCtx.getCacheDir(), "apktool");
        if (!tempDir.exists()) {
            //noinspection ResultOfMethodCallIgnored
            tempDir.mkdirs();
        }
        config.setTempDirectory(tempDir);

        return config;
    }

    /**
     * Resolves the version label from an optional {@code apktool.properties}
     * resource on the classpath. Hosts that depend on a Maven-published
     * {@code apktool-cli} jar will get the desktop version string here;
     * standalone {@code apktool-android} consumers fall through to
     * {@link #DEFAULT_VERSION}.
     */
    private static String resolveVersion() {
        try (InputStream in = ApktoolAndroid.class.getResourceAsStream("/apktool.properties")) {
            if (in != null) {
                Properties props = new Properties();
                props.load(in);
                String v = props.getProperty("application.version");
                if (v != null && !v.isEmpty() && !v.startsWith("${")) {
                    return v;
                }
            }
        } catch (IOException ignored) {
            // Fall through to default.
        }
        return DEFAULT_VERSION;
    }
}
