/* Copyright (c) 2026 Material Files contributors. All Rights Reserved. */

import android.app.Application;
import android.content.Context;

import java.io.File;
import java.lang.reflect.Field;

/** Supplies only the app cache directory needed for provider spooling and compound archives. */
public final class ArchiveTestSupport {
    private ArchiveTestSupport() {}

    public static void installApplication(File cache) throws ReflectiveOperationException {
        if (!cache.isDirectory() && !cache.mkdirs()) {
            throw new AssertionError("Cannot create test app cache: " + cache);
        }
        Field field = Class.forName("me.zhanghai.android.files.app.AppProviderKt")
                .getDeclaredField("application");
        field.setAccessible(true);
        field.set(null, new Application() {
            @Override public String getPackageName() { return "me.zhanghai.android.files"; }
            @Override public Context getApplicationContext() { return this; }
            @Override public File getCacheDir() { return cache; }
            @Override public File getFilesDir() { return cache; }
        });
    }

    public static void requireEmptyCache(File cache) {
        String[] files = cache.list();
        if (files == null || files.length != 0) {
            throw new AssertionError("Archive operation leaked temporary files: "
                    + java.util.Arrays.toString(files));
        }
    }
}
