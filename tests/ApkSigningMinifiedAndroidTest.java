/* Copyright (c) 2026 Material Files contributors. All Rights Reserved. */

import android.os.Build;
import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import org.json.JSONObject;

/**
 * Exercises the verifier from an actual APK after R8 has renamed, merged and removed classes.
 * The runner discovers member names from that APK's DEX. This harness links only Android/JDK
 * APIs, so neither an unminified app class nor a replacement crypto library can satisfy a call.
 */
public final class ApkSigningMinifiedAndroidTest {
    private static Method verifier;
    private static Field verified;
    private static Object receiver;
    private static Object callback;
    private static int checks;
    private static int passed;

    public static void main(String[] arguments) {
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> fail(error));
        try {
            run(arguments);
        } catch (Throwable error) {
            fail(error);
        }
    }

    private static void run(String[] arguments) throws Exception {
        if (arguments.length != 6 || !"Dalvik".equals(System.getProperty("java.vm.name"))) {
            throw new IllegalArgumentException("Run on ART with work, fixtures, class, method, callback, verified field");
        }
        System.out.println("ANDROID_RUNTIME api=" + Build.VERSION.SDK_INT + " release="
                + Build.VERSION.RELEASE + " vm=" + System.getProperty("java.vm.name"));
        File work = new File(arguments[0]);
        File fixtures = new File(arguments[1]);
        Class<?> owner = Class.forName(arguments[2]);
        Class<?> callbackType = Class.forName(arguments[4]);
        verifier = owner.getDeclaredMethod(arguments[3], File.class, int.class, callbackType);
        verifier.setAccessible(true);
        verified = verifier.getReturnType().getDeclaredField(arguments[5]);
        verified.setAccessible(true);
        if (verified.getType() != boolean.class || Modifier.isStatic(verified.getModifiers())) {
            throw new AssertionError("The discovered verification result is not an instance boolean");
        }
        if (!Modifier.isStatic(verifier.getModifiers())) {
            Constructor<?> constructor = owner.getDeclaredConstructor();
            constructor.setAccessible(true);
            receiver = constructor.newInstance();
        }
        if (!callbackType.isInterface()) {
            throw new AssertionError("The discovered progress callback is not an interface");
        }
        callback = Proxy.newProxyInstance(callbackType.getClassLoader(), new Class<?>[]{callbackType},
                (proxy, method, values) -> {
                    if (method.getDeclaringClass() == Object.class) {
                        switch (method.getName()) {
                            case "toString": return "Minified verification progress callback";
                            case "hashCode": return System.identityHashCode(proxy);
                            case "equals": return proxy == values[0];
                            default: throw new AssertionError(method);
                        }
                    }
                    if (method.getReturnType() != void.class) throw new AssertionError(method);
                    return null;
                });
        File signed = new File(fixtures, "golden-aligned-v1v2v3-out.apk");
        for (int minSdk : new int[]{23, 24, 28, 30}) {
            check("AOSP signed APK", signed, minSdk, true);
        }
        check("AOSP unsigned APK", new File(fixtures, "golden-aligned-in.apk"), 23, false);
        check("tampered signed APK", new File(work, "tampered.apk"), 23, false);
        check("malformed APK", new File(work, "malformed.apk"), 23, false);
        System.out.println("ART minified APK verification: " + passed + "/" + checks + " checks passed");
        System.out.println("Scope: packaged production verification on ART; no UI, startup or installation test.");
        if (passed != checks) throw new AssertionError("Packaged verification failed " + (checks - passed) + " checks");
    }

    private static void check(String name, File input, int minSdk, boolean expected) throws Exception {
        ++checks;
        JSONObject result = new JSONObject();
        result.put("case", name);
        result.put("min_sdk", minSdk);
        result.put("expected_verified", expected);
        boolean success = false;
        try {
            Object value = verifier.invoke(receiver, input, minSdk, callback);
            boolean actual = verified.getBoolean(value);
            result.put("verified", actual);
            // R8 can merge the result with unrelated classes and remove unused fields. Preserve
            // every surviving value, including the actual verifier errors, without guessing names.
            JSONObject fields = new JSONObject();
            for (Field field : value.getClass().getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers())) continue;
                field.setAccessible(true);
                fields.put(field.getName(), String.valueOf(field.get(value)));
            }
            result.put("result_fields", fields);
            success = actual == expected;
        } catch (Throwable error) {
            Throwable cause = error instanceof InvocationTargetException
                    ? ((InvocationTargetException) error).getCause() : error;
            result.put("exception", cause.toString());
            cause.printStackTrace(System.out);
        }
        result.put("passed", success);
        if (success) ++passed;
        System.out.println("MINIFIED_VERIFY_CASE " + result);
    }

    private static void fail(Throwable error) {
        error.printStackTrace(System.err);
        System.exit(1);
    }
}
