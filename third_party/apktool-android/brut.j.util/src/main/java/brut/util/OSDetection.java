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
package brut.util;

public final class OSDetection {
    private static final String OS = System.getProperty("os.name").toLowerCase();
    private static final String VM_NAME = System.getProperty("java.vm.name", "").toLowerCase();
    private static final String BIT;
    private static final boolean ANDROID;
    static {
        // On ART, `os.name` is "Linux" but `java.vm.name` is "Dalvik". This is
        // the only signal that distinguishes a phone from a desktop Linux JDK.
        ANDROID = VM_NAME.contains("dalvik")
            || "The Android Project".equals(System.getProperty("java.vendor"));

        // sun.arch.data.model is JDK-specific and not present on ART. Fall back
        // to os.arch parsing on Android.
        String dataModel = System.getProperty("sun.arch.data.model");
        if (dataModel == null) {
            String arch = System.getProperty("os.arch", "").toLowerCase();
            BIT = (arch.contains("64")) ? "64" : "32";
        } else {
            BIT = dataModel.toLowerCase();
        }
    }

    private OSDetection() {
        // Private constructor for utility class.
    }

    public static boolean isWindows() {
        return !ANDROID && OS.contains("win");
    }

    public static boolean isMacOSX() {
        return !ANDROID && OS.contains("mac");
    }

    public static boolean isUnix() {
        // Treat Android as its own platform; callers that want generic Unix
        // semantics on a phone must add `|| isAndroid()`.
        return !ANDROID && (OS.contains("nix") || OS.contains("nux")
            || OS.contains("aix") || OS.contains("sunos"));
    }

    public static boolean isAndroid() {
        return ANDROID;
    }

    public static boolean is64Bit() {
        if (isWindows()) {
            String arch = System.getenv("PROCESSOR_ARCHITECTURE");
            String wow64Arch = System.getenv("PROCESSOR_ARCHITEW6432");

            return arch != null && arch.endsWith("64") || wow64Arch != null && wow64Arch.endsWith("64");
        }
        return BIT.equals("64");
    }

    public static String returnOS() {
        return ANDROID ? "android" : OS;
    }
}
