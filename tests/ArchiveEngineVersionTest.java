/* Copyright (c) 2026 Material Files contributors. All Rights Reserved. */

import java.lang.reflect.Method;

/** Rejects stale packaged engines by querying the APK's initialized JNI binding. */
public final class ArchiveEngineVersionTest {
    private ArchiveEngineVersionTest() {}

    public static void main(String[] arguments) {
        try {
            Class<?> runtime = Class.forName(
                    "me.zhanghai.android.files.provider.archive.archiver.SevenZipRuntime");
            runtime.getMethod("ensureInitialized").invoke(runtime.getField("INSTANCE").get(null));
            Class<?> binding = Class.forName("net.sf.sevenzipjbinding.SevenZip");
            Object version = binding.getMethod("getSevenZipVersion").invoke(null);
            Class<?> versionType = version.getClass();
            int major = versionType.getField("major").getInt(version);
            int minor = versionType.getField("minor").getInt(version);
            String number = (String) versionType.getField("version").get(version);
            String date = (String) versionType.getField("date").get(version);
            String bridge = (String) binding.getMethod("getSevenZipJBindingVersion").invoke(null);
            System.out.println("APK archive engine: 7-Zip " + number + " (" + date
                    + "); JBinding " + bridge);
            if (major != 26 || minor != 4 || !"26.04".equals(number)
                    || !"2026-10-05".equals(date)) {
                throw new AssertionError("The APK must load the official 7-Zip 26.04 engine");
            }
            if (!"26.03-2.4".equals(bridge)) {
                throw new AssertionError("Unexpected JBinding bridge version: " + bridge);
            }
            Class<?> properties = Class.forName(
                    "me.zhanghai.android.files.provider.archive.archiver.SevenZipArchiveProperties");
            Object propertiesInstance = properties.getField("INSTANCE").get(null);
            Method ensureLoaded = properties.getDeclaredMethod("ensureLoaded");
            ensureLoaded.setAccessible(true);
            ensureLoaded.invoke(propertiesInstance);
            Method interfaceVersion = properties.getDeclaredMethod("nativeGetInterfaceVersion");
            interfaceVersion.setAccessible(true);
            int propertyVersion = (Integer) interfaceVersion.invoke(propertiesInstance);
            System.out.println("APK archive property adapter interface: " + propertyVersion);
            if (propertyVersion != 1) {
                throw new AssertionError("Unexpected archive property adapter interface: " + propertyVersion);
            }
            System.out.println("PASS: APK JNI engine and JBinding versions match the pinned sources");
        } catch (Throwable failure) {
            failure.printStackTrace(System.err);
            System.exit(1);
        }
    }
}
