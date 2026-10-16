/* Copyright (c) 2026 Material Files contributors. All Rights Reserved. */

import android.app.Application;
import android.content.ContentResolver;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.ProviderInfo;
import android.content.res.AssetManager;
import android.content.res.Resources;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Build;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import android.provider.DocumentsProvider;
import android.system.Os;
import android.system.OsConstants;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.RandomAccessFile;
import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.Provider;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Executes the APK's real private-key operations, Linux provider, JNI and root router on ART.
 * The Application supplies isolated CE/DE directories, disk-backed test preferences and the APK's
 * resources; platform services come from the hosting Termux context. No signing, file IO, settings
 * default or root-selection result is mocked. Provider identity is observed without starting a root
 * service. This is not an installed-app/UI test or a real device-protected-storage lifecycle test.
 * Export uses a locally wrapped DocumentsProvider with real file descriptors in the isolated work
 * directory. It tests document IO and injected descriptor failures, not system picker grants or a
 * remote provider. Its authority is borrowed from the host's installed provider only for Android's
 * document-URI recognition; no operation is sent to the host provider.
 * Batch recovery consumes persisted transaction journals with actual file identities and hashes;
 * its interrupted states are constructed explicitly, without forcibly killing an application.
 * app_process is not registered with ActivityManager, so the optional asynchronous media-scanner
 * callback cannot acquire MediaProvider. That specific background exception is recorded separately;
 * it does not replace or bypass any file operation or influence root routing.
 */
public final class ApkPrivateKeyAndroidTest {
    private static final String PASSWORD = "disposable-private-key-art-password";
    private static final String SECOND_PASSWORD = "different-disposable-private-key-art-password";
    private static final String ALIAS = "私有签名";
    private static final long MAX_KEY_BYTES = 16L * 1024 * 1024;
    private static final int MAX_ARCHIVE_KEYS = 32;
    private static final long MAX_ARCHIVE_BYTES = 65L * 1024 * 1024;
    private static final AtomicInteger unavailableMediaScans = new AtomicInteger();
    private static TestApplication application;
    private static Object operations;
    private static Object generated;
    private static Object provider;
    private static Object localProvider;
    private static Object rootProvider;
    private static Object rootSetting;
    private static Class<?> pathType;
    private static Class<?> functionType;
    private static Class<?> callbackType;
    private static Method pathsGet;
    private static Method stage;
    private static Method readAliases;
    private static Method route;
    private static File work;
    private static int checks;

    public static void main(String[] arguments) {
        Thread testThread = Thread.currentThread();
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            if (thread != testThread && isUnregisteredMediaScanner(error)) {
                unavailableMediaScans.incrementAndGet();
                System.out.println("ART_SERVICE_LIMITATION media_scanner=unregistered_app_process");
                error.printStackTrace(System.out);
            } else {
                fail(error);
            }
        });
        try {
            run(arguments);
            // The real provider can start platform media-scanner workers.
            System.exit(0);
        } catch (Throwable error) {
            fail(error);
        }
    }

    private static void run(String[] arguments) throws Exception {
        require(arguments.length == 3, "Pass private work, fixture and APK paths");
        require("Dalvik".equals(System.getProperty("java.vm.name")), "Android ART is required");
        System.out.println("ANDROID_RUNTIME api=" + Build.VERSION.SDK_INT + " release="
                + Build.VERSION.RELEASE + " vm=" + System.getProperty("java.vm.name"));
        work = new File(arguments[0], "私有密钥 中文").getCanonicalFile();
        mkdir(work);
        if (Looper.getMainLooper() == null) Looper.prepareMainLooper();

        // Match the app's existing hidden-API initializer before its Linux JNI/NIO bridge is used.
        Object hiddenApi = singleton("me.zhanghai.android.files.hiddenapi.HiddenApi");
        invoke(hiddenApi.getClass().getMethod("disableHiddenApiChecks"), hiddenApi);
        Class<?> activityThread = Class.forName("android.app.ActivityThread");
        Object thread = invoke(activityThread.getDeclaredMethod("systemMain"), null);
        Context system = (Context) invoke(activityThread.getDeclaredMethod("getSystemContext"), thread);
        Context packageContext = system.createPackageContext("com.termux", Context.CONTEXT_IGNORE_SECURITY);
        // createPackageContext() intentionally retains its caller's op-package ("android" here).
        // Create an ordinary app Context from the actual LoadedApk so services use Termux's UID/name.
        Object loadedApk = field(packageContext.getClass(), "mPackageInfo", packageContext);
        Context host = (Context) invoke(packageContext.getClass().getDeclaredMethod("createAppContext",
                activityThread, loadedApk.getClass()), null, thread, loadedApk);
        require(host.getApplicationInfo().uid == android.os.Process.myUid(),
                "The harness must run under the Termux application UID");
        application = new TestApplication(host, work, new File(arguments[2]));
        // Environment/StorageManager obtains its calling package from ActivityThread's Application.
        // Use the actual hosting UID/package instead of systemMain()'s default "android" identity.
        Field initialApplication = activityThread.getDeclaredField("mInitialApplication");
        initialApplication.setAccessible(true);
        initialApplication.set(thread, application);
        System.out.println("ART_TEST_CONTEXT package=" + application.getPackageName() + " opPackage="
                + application.getOpPackageName() + " currentOpPackage="
                + invoke(activityThread.getDeclaredMethod("currentOpPackageName"), null)
                + " uid=" + android.os.Process.myUid());
        Field appField = Class.forName("me.zhanghai.android.files.app.AppProviderKt")
                .getDeclaredField("application");
        appField.setAccessible(true);
        appField.set(null, application);

        pathType = Class.forName("java8.nio.file.Path");
        functionType = Class.forName("kotlin.jvm.functions.Function1");
        callbackType = Class.forName("me.zhanghai.android.files.apk.core.ApkOperationCallback");
        Class<?> providerType = Class.forName("java8.nio.file.spi.FileSystemProvider");
        provider = singleton("me.zhanghai.android.files.provider.linux.LinuxFileSystemProvider");
        invoke(providerType.getMethod("installDefaultProvider", providerType), null, provider);
        pathsGet = Class.forName("java8.nio.file.Paths").getMethod("get", String.class, String[].class);
        Class<?> rootableProvider = Class.forName(
                "me.zhanghai.android.files.provider.root.RootableFileSystemProvider");
        localProvider = field(rootableProvider, "localProvider", provider);
        rootProvider = field(rootableProvider, "rootProvider", provider);
        route = Class.forName("me.zhanghai.android.files.provider.root.RootablePathKt")
                .getMethod("callRootable", pathType, boolean.class, Object.class, Object.class,
                        functionType);
        Object settings = singleton("me.zhanghai.android.files.settings.Settings");
        rootSetting = invoke(settings.getClass().getMethod("getROOT_STRATEGY"), settings);
        require(!(Boolean) field(Class.forName(
                        "me.zhanghai.android.files.provider.root.RootFileServiceKt"),
                        "isRunningAsRoot", null), "This must exercise an unprivileged app process");
        operations = singleton("me.zhanghai.android.files.apk.ApkOperations");
        readAliases = operations.getClass().getMethod("readAliases", Context.class, pathType,
                String.class, char[].class);
        stage = operations.getClass().getDeclaredMethod("stage", Context.class, pathType,
                File.class, long.class, callbackType, int.class);
        stage.setAccessible(true);

        check("default AUTOMATIC routes CE, DE and canonical aliases locally", () -> {
            Object strategy = invoke(rootSetting.getClass().getMethod("getValue"), rootSetting);
            require("AUTOMATIC".equals(strategy.toString()), "The real default is not AUTOMATIC");
            require(application.preferences.getAll().isEmpty(), "Test preferences were preseeded");
            for (File directory : Arrays.asList(application.getDataDir(),
                    application.getDataDir().getCanonicalFile(), application.deviceData,
                    application.deviceData.getCanonicalFile())) {
                assertLocal(path(directory));
                assertLocal(path(new File(directory, "no_backup/apk-signing/keys/new.p12")));
            }
        });

        check("same-prefix siblings and parent traversal retain root routing", () -> {
            for (File outside : Arrays.asList(
                    new File(application.getDataDir().getPath() + ".other/no_backup/key.p12"),
                    new File(application.getDataDir(), "../other-app/no_backup/key.p12"),
                    new File(application.deviceData.getPath() + ".other/no_backup/key.p12"),
                    new File(application.deviceData, "../other-app/no_backup/key.p12"),
                    new File("/data/system"))) {
                for (boolean attributes : new boolean[]{false, true}) {
                    require(selectedProvider(path(outside), attributes) == rootProvider,
                            "Unrelated path was treated as this application's data: " + outside);
                }
            }
        });

        check("generate, enumerate and read aliases from the app-private key", () -> {
            char[] password = PASSWORD.toCharArray();
            generated = invoke(operations.getClass().getMethod("generateKey", Context.class,
                    String.class, char[].class, String.class), operations, application, ALIAS,
                    password, "Disposable ART regression key");
            require(erased(password), "Key generation retained the password");
            require(generated.getClass().getName().equals(
                    "me.zhanghai.android.files.provider.linux.LinuxPath"),
                    "Generation did not return the actual LinuxPath");
            File file = toFile(generated);
            require(file.getParentFile().equals(new File(application.getNoBackupFilesDir(),
                            "apk-signing/keys").getCanonicalFile()),
                    "Generated key was not saved to the private key directory");
            assertLocal(generated);
            List<?> keys = (List<?>) invoke(operations.getClass().getMethod("listPrivateKeys",
                    Context.class), operations, application);
            require(keys.size() == 1 && generated.equals(invoke(
                    keys.get(0).getClass().getMethod("getPath"), keys.get(0))),
                    "Private key enumeration lost the newly created key");
            require(aliases(generated).equals(Arrays.asList(ALIAS)), "Generated alias is unreadable");
            assertEmptyOperationCache();
        });

        check("ApkOperations signs and publishes with the newly generated private key", () -> {
            File input = new File(application.getFilesDir(), "原始 with spaces.apk");
            copy(new File(arguments[1], "golden-aligned-in.apk"), input);
            byte[] inputBefore = digest(input);
            byte[] keyBefore = digest(toFile(generated));
            char[] storePassword = PASSWORD.toCharArray();
            char[] keyPassword = PASSWORD.toCharArray();
            Class<?> requestType = Class.forName("me.zhanghai.android.files.apk.ApkSignRequest");
            Constructor<?> requestConstructor = requestType.getConstructor(pathType, pathType,
                    String.class, pathType, String.class, String.class, char[].class, char[].class,
                    int.class, boolean.class, boolean.class, boolean.class, String.class);
            Object request = requestConstructor.newInstance(path(input), path(application.getFilesDir()),
                    "新密钥签名.apk", generated, "PKCS12", ALIAS, storePassword, keyPassword, 23,
                    true, true, true, null);
            Object progress = Proxy.newProxyInstance(functionType.getClassLoader(),
                    new Class<?>[]{functionType}, (proxy, method, values) ->
                            Class.forName("kotlin.Unit").getField("INSTANCE").get(null));
            Object result = invoke(operations.getClass().getMethod("sign", Context.class,
                    requestType, functionType), operations, application, request, progress);
            File output = toFile(invoke(result.getClass().getMethod("getOutput"), result));
            Class<?> verifierType = Class.forName("me.zhanghai.android.files.apk.core.ApkVerificationService");
            Object verified = invoke(verifierType.getMethod("verify", File.class, int.class, callbackType),
                    verifierType.getConstructor().newInstance(), output, 23,
                    callbackType.getField("NONE").get(null));
            require(verified.getClass().getField("verified").getBoolean(verified),
                    "Published APK failed independent verification");
            require(erased(storePassword) && erased(keyPassword), "Signing retained passwords");
            require(Arrays.equals(inputBefore, digest(input))
                    && Arrays.equals(keyBefore, digest(toFile(generated))),
                    "Signing changed the input APK or private key");
            assertEmptyOperationCache();
        });

        check("device-protected directory key aliases use the same local provider", () -> {
            File imported = new File(application.deviceData, "imported key.p12");
            copy(toFile(generated), imported);
            require(aliases(path(imported)).equals(Arrays.asList(ALIAS)), "DE alias was unreadable");
            require(aliases(path(imported.getCanonicalFile())).equals(Arrays.asList(ALIAS)),
                    "Canonical DE alias was unreadable");
        });

        check("private symlink staging and native NOFOLLOW opens still reject links", () -> {
            File original = toFile(generated);
            File link = new File(original.getParentFile(), "linked-key.p12");
            Os.symlink(original.getPath(), link.getPath());
            byte[] before = digest(original);
            char[] password = PASSWORD.toCharArray();
            IOException rejected = expectIOException(() -> invoke(readAliases, operations,
                    application, path(link), "PKCS12", password));
            require(rejected.getMessage().equals(resource("apk_operation_invalid_input")),
                    "Symlink was rejected for an unrelated reason: " + rejected);
            require(erased(password), "Rejected symlink retained the password");
            Class<?> openOption = Class.forName("java8.nio.file.OpenOption");
            Class<?> linkOption = Class.forName("java8.nio.file.LinkOption");
            Object options = Array.newInstance(openOption, 1);
            Array.set(options, 0, linkOption.getField("NOFOLLOW_LINKS").get(null));
            expectIOException(() -> {
                try (InputStream ignored = (InputStream) invoke(providerType.getMethod(
                        "newInputStream", pathType, options.getClass()), provider, path(link), options)) {
                    throw new AssertionError("Native Linux open followed a symlink");
                }
            });
            require(OsConstants.S_ISLNK(Os.lstat(link.getPath()).st_mode)
                    && Arrays.equals(before, digest(original)), "Symlink rejection changed the key");
        });

        for (String mutation : Arrays.asList("inode", "mtime", "size")) {
            check("staging detects real source " + mutation + " changes", () -> mutation(mutation));
        }

        check("explicit ALWAYS still selects the real root provider for private keys", () -> {
            Class<?> strategyType = Class.forName("me.zhanghai.android.files.provider.root.RootStrategy");
            Method putValue = rootSetting.getClass().getMethod("putValue", Object.class);
            Object always = strategyType.getField("ALWAYS").get(null);
            Object automatic = strategyType.getField("AUTOMATIC").get(null);
            try {
                invoke(putValue, rootSetting, always);
                require(invoke(rootSetting.getClass().getMethod("getValue"), rootSetting) == always,
                        "Real test preferences did not apply ALWAYS");
                for (boolean attributes : new boolean[]{false, true}) {
                    require(selectedProvider(generated, attributes) == rootProvider,
                            "Explicit ALWAYS was overridden for a private key");
                }
            } finally {
                invoke(putValue, rootSetting, automatic);
            }
            assertLocal(generated);
            assertEmptyOperationCache();
        });
        keyManagement();
        System.out.println("ART private key operations: " + checks + "/" + checks + " checks passed");
        System.out.println("Scope: actual APK operations/provider/JNI, isolated app metadata and preferences;"
                + " locally wrapped document provider with real descriptor IO; no installation, UI,"
                + " system picker grants, remote provider, media-scanner, privileged service,"
                + " or physical CE/DE lifecycle test.");
        System.out.println("ART_MEDIA_SCANNER_UNAVAILABLE " + unavailableMediaScans.get());
    }

    private static void keyManagement() throws Exception {
        Method importKey = operations.getClass().getMethod("importPrivateKey", Context.class,
                pathType, String.class, char[].class);
        Method exportKey = operations.getClass().getMethod("exportPrivateKey", Context.class,
                pathType, Uri.class);
        Method deleteKey = operations.getClass().getMethod("deletePrivateKey", Context.class, pathType);
        File keysDirectory = toFile(generated).getParentFile();
        File original = new File(application.getFilesDir(), "导入 原件.pfx");
        byte[] generatedDigest = digest(toFile(generated));
        // A copied managed store must deduplicate to generated. Use an independent external key
        // so deleting the imported key cannot consume the original signing/regression fixture.
        Class<?> storeType = Class.forName("me.zhanghai.android.files.apk.core.SigningKeyStoreService");
        char[] fixturePassword = PASSWORD.toCharArray();
        try (AutoCloseable signingKey = (AutoCloseable) invoke(storeType.getMethod("generate",
                File.class, String.class, char[].class, String.class), storeType.getConstructor().newInstance(),
                original, ALIAS, fixturePassword, "Disposable external import key")) {
            require(erased(fixturePassword), "External fixture generation retained its password");
        }
        byte[] originalDigest = digest(original);
        require(!Arrays.equals(originalDigest, generatedDigest), "The import fixture is already managed");
        Object[] imported = new Object[2];
        ProviderInfo providerInfo = application.getPackageManager().resolveContentProvider(
                "com.termux.documents", 0);
        require(providerInfo != null, "The hosting Termux documents authority is unavailable");
        // The backup is also reopened through the real Linux provider below. Keep its backing
        // file inside the isolated app's declared data, not beside it under another /data path.
        TestDocumentsProvider documents = new TestDocumentsProvider(
                new File(application.getFilesDir(), "export-documents"), providerInfo.authority);
        Context providerContext = new ContextWrapper(application) {
            @Override public void revokeUriPermission(Uri uri, int modeFlags) {
                // The wrapped resolver uses no system URI grants. DocumentsProvider still removes
                // the real file, but there is no picker grant to revoke through ActivityManager.
                require(providerInfo.authority.equals(uri.getAuthority()), "Unexpected grant authority");
            }
        };
        documents.attachInfo(providerContext, new ProviderInfo(providerInfo));
        ContentResolver resolver = ContentResolver.wrap(documents);
        Context exportContext = new ContextWrapper(application) {
            @Override public Context getApplicationContext() { return this; }
            @Override public ContentResolver getContentResolver() { return resolver; }
        };

        check("import preserves PKCS12 bytes, source, aliases, metadata and password erasure", () -> {
            Set<Object> before = privateKeyPaths(exportContext);
            char[] password = PASSWORD.toCharArray();
            Object result = invoke(importKey, operations, exportContext, path(original), "AUTO", password);
            require(erased(password), "Import retained the password");
            require(!wasReused(result), "A new imported key was incorrectly reported as reused");
            Object key = invoke(result.getClass().getMethod("getKey"), result);
            imported[0] = invoke(key.getClass().getMethod("getPath"), key);
            File managed = toFile(imported[0]);
            require(managed.getParentFile().equals(keysDirectory) && managed.getName().endsWith(".p12"),
                    "Import was not stored as a direct private PKCS12 key");
            require(Arrays.equals(originalDigest, digest(original))
                            && Arrays.equals(originalDigest, digest(managed)),
                    "Import changed the original or its encrypted key-store bytes");
            require(invoke(result.getClass().getMethod("getAliases"), result).equals(Arrays.asList(ALIAS))
                            && aliases(imported[0], exportContext).equals(Arrays.asList(ALIAS)),
                    "Imported key aliases changed");
            require("PKCS12".equals(invoke(result.getClass().getMethod("getFormat"), result))
                            && "PKCS12".equals(invoke(key.getClass().getMethod("getFormat"), key)),
                    "Import did not retain the detected key-store format");
            require(((Long) invoke(key.getClass().getMethod("getSize"), key)) == managed.length(),
                    "Imported key size is incorrect");
            require(((String) invoke(key.getClass().getMethod("getDisplayName"), key)).contains("导入 原件"),
                    "Import lost the readable source name");
            Set<Object> expected = new HashSet<>(before);
            require(expected.add(imported[0]) && privateKeyPaths(exportContext).equals(expected),
                    "A different key with the same alias was merged or omitted from management");
            assertLocal(imported[0]);
            assertEmptyOperationCache();
        });

        check("repeated and renamed imports reuse the same private key without replacing files", () -> {
            File renamed = new File(application.getFilesDir(), "改名后的相同密钥.keystore");
            copy(original, renamed);
            Set<Object> before = privateKeyPaths(exportContext);
            Set<String> beforeNames = directoryEntries(keysDirectory);
            File managed = toFile(imported[0]);
            long inode = Os.lstat(managed.getPath()).st_ino;
            long modified = managed.lastModified();
            for (File source : Arrays.asList(original, renamed, managed)) {
                char[] password = PASSWORD.toCharArray();
                Object result = invoke(importKey, operations, exportContext, path(source), "AUTO", password);
                require(erased(password), "Repeated import retained the password");
                require(wasReused(result), "Repeated import did not report the reused key");
                Object key = invoke(result.getClass().getMethod("getKey"), result);
                imported[1] = invoke(key.getClass().getMethod("getPath"), key);
                require(imported[0].equals(imported[1]), "Identical content created a second private key");
                require(privateKeyPaths(exportContext).equals(before)
                                && directoryEntries(keysDirectory).equals(beforeNames),
                        "Repeated import added, removed or replaced a managed entry");
                require(Os.lstat(managed.getPath()).st_ino == inode && managed.lastModified() == modified
                                && Arrays.equals(originalDigest, digest(managed))
                                && Arrays.equals(originalDigest, digest(source))
                                && Arrays.equals(generatedDigest, digest(toFile(generated))),
                        "Deduplication rewrote a private key or changed an import source");
                require(invoke(result.getClass().getMethod("getAliases"), result).equals(Arrays.asList(ALIAS))
                                && "PKCS12".equals(invoke(result.getClass().getMethod("getFormat"), result)),
                        "Deduplicated import lost its validated aliases or format");
                assertEmptyOperationCache();
            }
        });

        check("wrong password, invalid, empty and oversized stores leave no imported key", () -> {
            File malformed = new File(application.getFilesDir(), "invalid-store.p12");
            write(malformed, "invalid key store".getBytes(StandardCharsets.UTF_8));
            File empty = new File(application.getFilesDir(), "empty-store.p12");
            Provider crypto = (Provider) field(Class.forName(
                    "me.zhanghai.android.files.apk.core.SigningKeyStoreService"), "PROVIDER", null);
            KeyStore emptyStore = KeyStore.getInstance("PKCS12", crypto);
            char[] emptyPassword = PASSWORD.toCharArray();
            try {
                emptyStore.load(null, emptyPassword);
                try (FileOutputStream output = new FileOutputStream(empty)) {
                    emptyStore.store(output, emptyPassword);
                }
            } finally { Arrays.fill(emptyPassword, '\0'); }
            File oversized = new File(application.getFilesDir(), "oversized-store.p12");
            try (RandomAccessFile file = new RandomAccessFile(oversized, "rw")) {
                file.setLength(MAX_KEY_BYTES + 1);
            }
            Set<String> before = directoryEntries(keysDirectory);
            for (File source : Arrays.asList(original, malformed, empty, oversized)) {
                byte[] sourceDigest = digest(source);
                char[] password = (source.equals(original) ? "incorrect-password" : PASSWORD).toCharArray();
                expectKeyRejection(() -> invoke(importKey, operations, exportContext, path(source),
                        "AUTO", password));
                require(erased(password), "Rejected import retained the password: " + source);
                require(directoryEntries(keysDirectory).equals(before),
                        "Rejected import left a private key or replaced an existing entry");
                require(Arrays.equals(sourceDigest, digest(source)), "Rejected import changed its source");
                assertEmptyOperationCache();
            }
        });

        check("symlink imports fail without changing the link or its original", () -> {
            File link = new File(application.getFilesDir(), "import-link.p12");
            Os.symlink(original.getPath(), link.getPath());
            Set<String> before = directoryEntries(keysDirectory);
            char[] password = PASSWORD.toCharArray();
            expectIOException(() -> invoke(importKey, operations, exportContext, path(link), "AUTO", password));
            require(erased(password), "Rejected link import retained the password");
            require(OsConstants.S_ISLNK(Os.lstat(link.getPath()).st_mode)
                            && Arrays.equals(originalDigest, digest(original)),
                    "Rejected link import changed its source");
            require(directoryEntries(keysDirectory).equals(before), "Rejected link import left a private copy");
            assertEmptyOperationCache();
        });

        Uri exported = documents.newDocument("备份 密钥.p12");
        File exportedFile = documents.file(DocumentsContract.getDocumentId(exported));

        check("export writes the original encrypted bytes to a new document", () -> {
            invoke(exportKey, operations, exportContext, imported[0], exported);
            require(Arrays.equals(originalDigest, digest(exportedFile)), "Export changed the encrypted key bytes");
            assertLocal(path(exportedFile));
            require(aliases(path(exportedFile), exportContext).equals(Arrays.asList(ALIAS)),
                    "Exported key cannot be reopened");
            require(Arrays.equals(originalDigest, digest(toFile(imported[0])))
                            && Arrays.equals(originalDigest, digest(original)),
                    "Export changed the private key or its original");
            require(documents.openCalls > 0 && documents.deleteCalls == 0,
                    "Export did not use the document provider or deleted a completed document");
            assertEmptyOperationCache();
        });

        check("export refuses occupied documents and non-document destinations", () -> {
            Uri occupied = documents.newDocument("occupied.p12");
            File occupiedFile = documents.file(DocumentsContract.getDocumentId(occupied));
            write(occupiedFile, "preserve existing document".getBytes(StandardCharsets.UTF_8));
            byte[] before = digest(occupiedFile);
            int deletions = documents.deleteCalls;
            expectIOException(() -> invoke(exportKey, operations, exportContext, imported[0], occupied));
            require(Arrays.equals(before, digest(occupiedFile)) && documents.deleteCalls == deletions,
                    "Export changed or removed an existing document");
            int opens = documents.openCalls;
            for (Uri uri : Arrays.asList(Uri.fromFile(occupiedFile),
                    Uri.parse("content://" + providerInfo.authority + "/not-a-document"))) {
                expectIOException(() -> invoke(exportKey, operations, exportContext, imported[0], uri));
            }
            require(documents.openCalls == opens && documents.deleteCalls == deletions,
                    "An invalid export URI reached document IO");
            require(Arrays.equals(originalDigest, digest(toFile(imported[0]))),
                    "Rejected export changed the private key");
            assertEmptyOperationCache();
        });

        check("failed document writes preserve private keys and replacement documents", () -> {
            Uri readOnly = documents.newDocument("read-only-failure.p12");
            documents.readOnlyDocument = DocumentsContract.getDocumentId(readOnly);
            expectIOException(() -> invoke(exportKey, operations, exportContext, imported[0], readOnly));
            File failed = documents.file(documents.readOnlyDocument);
            require(!failed.exists() || failed.length() == 0, "A failed write left unexpected document bytes");
            documents.readOnlyDocument = null;

            Uri replacement = documents.newDocument("replaced-during-failure.p12");
            String replacementId = DocumentsContract.getDocumentId(replacement);
            documents.replaceOnOpen = replacementId;
            int deletions = documents.deleteCalls;
            expectIOException(() -> invoke(exportKey, operations, exportContext, imported[0], replacement));
            require(documents.replaceOnOpen == null, "The replacement fault was not reached");
            require(Arrays.equals(MessageDigest.getInstance("SHA-256").digest(TestDocumentsProvider.REPLACEMENT),
                            digest(documents.file(replacementId))) && documents.deleteCalls == deletions,
                    "Failed export removed or changed a different file now owned by the document URI");
            require(Arrays.equals(originalDigest, digest(toFile(imported[0])))
                            && Arrays.equals(originalDigest, digest(original)),
                    "Failed document writes changed the private key or import original");
            assertEmptyOperationCache();
        });

        check("export and delete reject paths outside direct managed regular files", () -> {
            Set<Object> before = privateKeyPaths(exportContext);
            File siblingDirectory = new File(keysDirectory.getPath() + ".other");
            mkdir(siblingDirectory);
            File sibling = new File(siblingDirectory, "key.p12");
            copy(original, sibling);
            File traversal = new File(keysDirectory, "../parent-key.p12");
            copy(original, traversal);
            File nestedDirectory = new File(keysDirectory, "nested");
            mkdir(nestedDirectory);
            File nested = new File(nestedDirectory, "key.p12");
            copy(original, nested);
            File link = new File(keysDirectory, "managed-link.p12");
            Os.symlink(original.getPath(), link.getPath());
            File directory = new File(keysDirectory, "directory.p12");
            mkdir(directory);
            File unsupported = new File(keysDirectory, "unmanaged.txt");
            copy(original, unsupported);
            require(privateKeyPaths(exportContext).equals(before), "Enumeration exposed unmanaged paths as private keys");
            int index = 0;
            for (File source : Arrays.asList(original, sibling, traversal, nested, link, directory, unsupported)) {
                byte[] bytes = source.isDirectory() ? null : digest(source);
                long inode = Os.lstat(source.getPath()).st_ino;
                Uri destination = documents.newDocument("invalid-source-" + index++ + ".p12");
                int opens = documents.openCalls;
                expectIOException(() -> invoke(exportKey, operations, exportContext, path(source), destination));
                require(documents.openCalls == opens, "An unmanaged key reached document export IO");
                expectIOException(() -> invoke(deleteKey, operations, exportContext, path(source)));
                require(Os.lstat(source.getPath()).st_ino == inode
                                && (bytes == null || Arrays.equals(bytes, digest(source))),
                        "Rejected management operation removed or changed an unmanaged path: " + source);
            }
            require(privateKeyPaths(exportContext).equals(before), "Rejected operations changed the managed key list");
            assertEmptyOperationCache();
        });

        check("deleting a deduplicated private key preserves its original, backup and other keys", () -> {
            Set<Object> expected = privateKeyPaths(exportContext);
            require(expected.remove(imported[0]) && expected.contains(generated),
                    "The independently imported key is not isolated from the signing fixture");
            invoke(deleteKey, operations, exportContext, imported[0]);
            require(!toFile(imported[0]).exists() && !privateKeyPaths(exportContext).contains(imported[0]),
                    "Deleted key remains stored or listed");
            require(privateKeyPaths(exportContext).equals(expected),
                    "Deleting one key removed another private key");
            for (File retained : Arrays.asList(original, exportedFile)) {
                require(Arrays.equals(originalDigest, digest(retained)),
                        "Deleting the private copy changed its original or backup");
            }
            require(Arrays.equals(generatedDigest, digest(toFile(generated))),
                    "Deleting the imported key changed the independent signing fixture");
            expectIOException(() -> invoke(deleteKey, operations, exportContext, imported[0]));
            assertEmptyOperationCache();
        });
        batchKeyManagement(exportContext, documents);
    }

    private static void batchKeyManagement(Context context, TestDocumentsProvider documents) throws Exception {
        Method preview = operations.getClass().getMethod("previewKeyImport", Context.class, pathType);
        Method importKeys = operations.getClass().getMethod("importPrivateKeys", Context.class, pathType, Map.class);
        Method exportKeys = operations.getClass().getMethod("exportPrivateKeys", Context.class, List.class, Uri.class);
        Method deleteKey = operations.getClass().getMethod("deletePrivateKey", Context.class, pathType);
        File keysDirectory = toFile(generated).getParentFile();
        File fixtures = new File(application.getFilesDir(), "batch-key-fixtures");
        mkdir(fixtures);
        byte[] firstBytes = readSmallFile(toFile(generated));
        // The single-key deletion above removed this distinct store from management. Its bytes
        // exercise within-batch deduplication before an identical managed key exists.
        byte[] nestedBytes = readSmallFile(new File(application.getFilesDir(), "导入 原件.pfx"));
        byte[][] secondBytes = new byte[1][];
        Object[] secondKey = new Object[1];
        Uri archiveUri = documents.newDocument("多个同名密钥.zip");
        File archive = documents.file(DocumentsContract.getDocumentId(archiveUri));
        File nestedArchive = new File(fixtures, "安全子目录.zip");
        Map<String, byte[]> exportedEntries = new LinkedHashMap<>();
        Map<String, String> entryPasswords = new LinkedHashMap<>();
        List<Object> entries = new ArrayList<>();
        List<Object> nestedEntries = new ArrayList<>();

        check("ZIP export preserves different same-name keys under unique entry names", () -> {
            char[] password = SECOND_PASSWORD.toCharArray();
            secondKey[0] = invoke(operations.getClass().getMethod("generateKey", Context.class,
                    String.class, char[].class, String.class), operations, context, ALIAS, password,
                    "Disposable second batch key");
            require(erased(password), "Batch fixture generation retained its password");
            secondBytes[0] = readSmallFile(toFile(secondKey[0]));
            require(!Arrays.equals(firstBytes, secondBytes[0]), "Batch fixtures are not independent key stores");
            invoke(exportKeys, operations, context, Arrays.asList(generated, secondKey[0]), archiveUri);
            exportedEntries.putAll(readZipEntries(archive));
            require(exportedEntries.size() == 2, "ZIP export lost a selected key or added unexpected entries");
            Set<String> names = new HashSet<>();
            Set<String> matchedPasswords = new HashSet<>();
            for (Map.Entry<String, byte[]> entry : exportedEntries.entrySet()) {
                String name = entry.getKey();
                require(name.indexOf('/') < 0 && name.indexOf('\\') < 0 && name.contains(ALIAS)
                                && name.endsWith(".p12") && names.add(name.toLowerCase(Locale.ROOT)),
                        "ZIP export did not produce distinct, readable flat key names: " + name);
                String secret = Arrays.equals(entry.getValue(), firstBytes) ? PASSWORD
                        : Arrays.equals(entry.getValue(), secondBytes[0]) ? SECOND_PASSWORD : null;
                require(secret != null && matchedPasswords.add(secret), "ZIP changed or duplicated encrypted key bytes");
                entryPasswords.put(name, secret);
            }
            require(Arrays.equals(firstBytes, readSmallFile(toFile(generated)))
                            && Arrays.equals(secondBytes[0], readSmallFile(toFile(secondKey[0]))),
                    "ZIP export changed a selected private key");
            assertEmptyOperationCache();
            assertEmptyKeyExportJournal();
        });

        check("key previews retain stable identities and support single stores and safe ZIP folders", () -> {
            Set<String> before = directoryEntries(keysDirectory);
            byte[] archiveDigest = digest(archive);
            List<?> single = (List<?>) invoke(preview, operations, context, generated);
            require(single.size() == 1 && entrySize(single.get(0)) == firstBytes.length
                            && "PKCS12".equals(entryFormat(single.get(0))),
                    "Standalone key preview metadata is incorrect");
            entries.addAll((List<?>) invoke(preview, operations, context, path(archive)));
            require(entries.size() == 2 && entryIds(entries).size() == 2, "ZIP preview lost distinct entries");
            for (Object entry : entries) {
                byte[] bytes = exportedEntries.get(entryName(entry));
                require(bytes != null && entrySize(entry) == bytes.length && "PKCS12".equals(entryFormat(entry)),
                        "ZIP preview metadata disagrees with its actual entries");
            }
            require(entries.equals(invoke(preview, operations, context, path(archive))),
                    "Unchanged input did not retain stable preview identities");
            writeZip(nestedArchive, new String[]{"目录/", "目录/同名.p12", "另一目录/同名.p12"},
                    new byte[][]{new byte[0], nestedBytes, nestedBytes});
            nestedEntries.addAll((List<?>) invoke(preview, operations, context, path(nestedArchive)));
            require(nestedEntries.size() == 2 && entryIds(nestedEntries).size() == 2,
                    "Safe directories or identical basenames confused preview identities");
            for (Object entry : nestedEntries) {
                require(entrySize(entry) == nestedBytes.length && "PKCS12".equals(entryFormat(entry)),
                        "Nested ZIP preview changed key metadata");
            }
            require(directoryEntries(keysDirectory).equals(before) && Arrays.equals(archiveDigest, digest(archive)),
                    "Preview persisted a key or modified the selected archive");
            assertEmptyOperationCache();
        });

        check("mixed and repeated ZIP imports honor independent passwords and reuse existing keys", () -> {
            byte[] archiveDigest = digest(archive);
            invoke(deleteKey, operations, context, secondKey[0]);
            require(!toFile(secondKey[0]).exists() && privateKeyPaths(context).contains(generated),
                    "The mixed import fixture did not retain just its existing source key");
            for (int repeat = 0; repeat < 2; ++repeat) {
                Set<Object> before = privateKeyPaths(context);
                Set<String> beforeNames = directoryEntries(keysDirectory);
                Map<String, char[]> passwords = passwordsFor(entries, entryPasswords);
                List<?> imported = (List<?>) invoke(importKeys, operations, context, path(archive), passwords);
                assertErased(passwords);
                require(imported.size() == exportedEntries.size(), "Batch import lost a ZIP key");
                Set<Object> importedPaths = new HashSet<>();
                Set<String> contents = new HashSet<>();
                for (Object result : imported) {
                    Object key = invoke(result.getClass().getMethod("getKey"), result);
                    Object privatePath = invoke(key.getClass().getMethod("getPath"), key);
                    File file = toFile(privatePath);
                    require(file.getParentFile().equals(keysDirectory) && importedPaths.add(privatePath),
                            "Batch import duplicated a result path or escaped the private key directory");
                    require(wasReused(result) == before.contains(privatePath),
                            "Batch import reported the wrong reuse status for a new or existing key");
                    byte[] bytes = readSmallFile(file);
                    String sourceName = matchingEntry(bytes, exportedEntries);
                    require(contents.add(sourceName), "Batch import duplicated one key and lost another");
                    if (Arrays.equals(bytes, firstBytes)) {
                        require(generated.equals(privatePath) && before.contains(privatePath),
                                "ZIP import copied an already managed key instead of reusing its path");
                    } else if (repeat == 0) {
                        require(!before.contains(privatePath), "A different same-name key was merged with an existing key");
                        secondKey[0] = privatePath;
                    } else {
                        require(secondKey[0].equals(privatePath) && before.contains(privatePath),
                                "Repeated ZIP import created another copy of its second key");
                    }
                    require(invoke(result.getClass().getMethod("getAliases"), result).equals(Arrays.asList(ALIAS))
                                    && aliases(privatePath, context, entryPasswords.get(sourceName)).equals(Arrays.asList(ALIAS)),
                            "A batch-imported key cannot be reopened using its own password");
                    require("PKCS12".equals(invoke(result.getClass().getMethod("getFormat"), result)),
                            "Batch import lost the key-store format");
                }
                Set<Object> after = privateKeyPaths(context);
                Set<Object> expected = new HashSet<>(before);
                expected.addAll(importedPaths);
                if (repeat == 0) beforeNames.add(toFile(secondKey[0]).getName());
                require(after.equals(expected) && after.size() == before.size() + (repeat == 0 ? 1 : 0)
                                && directoryEntries(keysDirectory).equals(beforeNames),
                        "Mixed or repeated ZIP import added the wrong number of private files");
                require(Arrays.equals(archiveDigest, digest(archive))
                                && Arrays.equals(firstBytes, readSmallFile(toFile(generated)))
                                && Arrays.equals(secondBytes[0], readSmallFile(toFile(secondKey[0]))),
                        "Batch import changed an archive or an original key");
                assertEmptyOperationCache();
            }
        });

        check("wrong, missing or extra passwords and a later empty store roll back the whole batch", () -> {
            Set<String> existingNames = directoryEntries(keysDirectory);
            byte[] archiveDigest = digest(archive);
            String lastId = entryId(entries.get(entries.size() - 1));
            // Both stores already exist: deduplication must still validate every supplied secret.
            for (String failure : Arrays.asList("wrong", "missing", "extra")) {
                Map<String, char[]> passwords = passwordsFor(entries, entryPasswords);
                if (failure.equals("extra")) {
                    passwords.put("not-a-preview-entry", "unused-secret-to-clear".toCharArray());
                } else {
                    Arrays.fill(passwords.remove(lastId), '\0');
                    if (failure.equals("wrong")) passwords.put(lastId, "wrong-last-password".toCharArray());
                }
                expectKeyRejection(() -> invoke(importKeys, operations, context, path(archive), passwords));
                assertErased(passwords);
                require(directoryEntries(keysDirectory).equals(existingNames), "Rejected password map left a partial import");
                require(Arrays.equals(archiveDigest, digest(archive)), "Rejected batch changed the source archive");
                assertEmptyOperationCache();
            }
            invoke(deleteKey, operations, context, secondKey[0]);
            require(!toFile(secondKey[0]).exists(), "The rollback fixture still has its supposedly new key");
            Set<String> before = directoryEntries(keysDirectory);
            Set<Object> beforePaths = privateKeyPaths(context);
            require(beforePaths.contains(generated), "Preparing rollback removed the existing key");
            File mixed = new File(fixtures, "new-then-existing-wrong-password.zip");
            writeZip(mixed, new String[]{"new.p12", "existing.p12"}, new byte[][]{secondBytes[0], firstBytes});
            byte[] mixedDigest = digest(mixed);
            List<?> mixedEntries = (List<?>) invoke(preview, operations, context, path(mixed));
            Map<String, String> mixedSecrets = new LinkedHashMap<>();
            mixedSecrets.put("new.p12", SECOND_PASSWORD);
            mixedSecrets.put("existing.p12", "wrong-existing-password");
            Map<String, char[]> mixedPasswords = passwordsFor(mixedEntries, mixedSecrets);
            expectKeyRejection(() -> invoke(importKeys, operations, context, path(mixed), mixedPasswords));
            assertErased(mixedPasswords);
            require(directoryEntries(keysDirectory).equals(before) && Arrays.equals(mixedDigest, digest(mixed))
                            && Arrays.equals(firstBytes, readSmallFile(toFile(generated))),
                    "A wrong password for an existing duplicate persisted the earlier new key or changed a source");
            assertEmptyOperationCache();

            File emptyStoreArchive = new File(fixtures, "valid-then-empty.zip");
            writeZip(emptyStoreArchive, new String[]{"valid.p12", "empty.p12"}, new byte[][]{
                    secondBytes[0], readSmallFile(new File(application.getFilesDir(), "empty-store.p12"))});
            byte[] beforeArchive = digest(emptyStoreArchive);
            List<?> emptyPreview = (List<?>) invoke(preview, operations, context, path(emptyStoreArchive));
            require(emptyPreview.size() == 2, "The valid empty-store fixture was not previewed");
            Map<String, String> emptySecrets = new LinkedHashMap<>();
            emptySecrets.put("valid.p12", SECOND_PASSWORD);
            emptySecrets.put("empty.p12", PASSWORD);
            Map<String, char[]> passwords = passwordsFor(emptyPreview, emptySecrets);
            expectKeyRejection(() -> invoke(importKeys, operations, context, path(emptyStoreArchive), passwords));
            assertErased(passwords);
            require(directoryEntries(keysDirectory).equals(before)
                            && Arrays.equals(beforeArchive, digest(emptyStoreArchive)),
                    "A later store without a private key left a partial import or changed its archive");
            assertEmptyOperationCache();

            // Restore the deleted second fixture through the real mixed import API for later
            // export, recovery and ViewModel checks; never retain a path to the deleted copy.
            Map<String, char[]> restoredPasswords = passwordsFor(entries, entryPasswords);
            List<?> restored = (List<?>) invoke(importKeys, operations, context, path(archive), restoredPasswords);
            assertErased(restoredPasswords);
            require(restored.size() == 2, "The valid retry did not return both distinct stores");
            Set<Object> restoredPaths = new HashSet<>();
            for (Object result : restored) {
                Object key = invoke(result.getClass().getMethod("getKey"), result);
                Object privatePath = invoke(key.getClass().getMethod("getPath"), key);
                require(restoredPaths.add(privatePath), "The valid retry returned the same key twice");
                require(wasReused(result) == beforePaths.contains(privatePath),
                        "The valid retry reported the wrong reuse status");
                byte[] bytes = readSmallFile(toFile(privatePath));
                if (Arrays.equals(bytes, firstBytes)) {
                    require(generated.equals(privatePath), "The valid retry copied the already managed key");
                } else {
                    require(Arrays.equals(bytes, secondBytes[0]) && !beforePaths.contains(privatePath),
                            "The valid retry did not restore the distinct second key");
                    secondKey[0] = privatePath;
                }
            }
            Set<Object> expected = new HashSet<>(beforePaths);
            expected.addAll(restoredPaths);
            before.add(toFile(secondKey[0]).getName());
            require(privateKeyPaths(context).equals(expected) && expected.size() == beforePaths.size() + 1
                            && directoryEntries(keysDirectory).equals(before)
                            && Arrays.equals(archiveDigest, digest(archive))
                            && Arrays.equals(firstBytes, readSmallFile(toFile(generated)))
                            && Arrays.equals(secondBytes[0], readSmallFile(toFile(secondKey[0]))),
                    "A valid retry after rollback added the wrong files or changed original bytes");
            assertEmptyOperationCache();
        });

        check("changing ZIP entry content or names invalidates the preview password map", () -> {
            File changed = new File(fixtures, "changed-after-preview.zip");
            Set<String> before = directoryEntries(keysDirectory);
            String[] names = exportedEntries.keySet().toArray(new String[0]);
            byte[][] contents = exportedEntries.values().toArray(new byte[0][]);
            for (String mutation : Arrays.asList("content", "name")) {
                writeZip(changed, names, contents);
                List<?> originalPreview = (List<?>) invoke(preview, operations, context, path(changed));
                Map<String, char[]> passwords = passwordsFor(originalPreview, entryPasswords);
                String[] changedNames = names.clone();
                byte[][] changedContents = contents.clone();
                if (mutation.equals("name")) {
                    changedNames[0] = "renamed-" + changedNames[0];
                } else {
                    changedContents[0] = contents[1];
                    // Supply the new content's correct password under the old ID. Only a stale
                    // identity, not a coincidental wrong password, can justify this rejection.
                    for (Object entry : originalPreview) {
                        if (entryName(entry).equals(names[0])) {
                            Arrays.fill(passwords.put(entryId(entry), entryPasswords.get(names[1]).toCharArray()), '\0');
                        }
                    }
                }
                writeZip(changed, changedNames, changedContents);
                byte[] currentDigest = digest(changed);
                List<?> currentPreview = (List<?>) invoke(preview, operations, context, path(changed));
                require(!entryIds(originalPreview).equals(entryIds(currentPreview)),
                        "Preview identity did not bind the ZIP entry's " + mutation);
                expectIOException(() -> invoke(importKeys, operations, context, path(changed), passwords));
                assertErased(passwords);
                require(directoryEntries(keysDirectory).equals(before) && Arrays.equals(currentDigest, digest(changed)),
                        "A stale preview imported keys or changed the current archive");
                assertEmptyOperationCache();
            }
        });

        check("unsafe paths, duplicate names, unsupported entries and broken ZIPs cannot import", () -> {
            Set<String> before = directoryEntries(keysDirectory);
            File parentSentinel = new File(keysDirectory.getParentFile(), "zip-escape.p12");
            File absoluteSentinel = new File(fixtures, "absolute-escape.p12");
            byte[] sentinel = "retain unrelated archive traversal target".getBytes(StandardCharsets.UTF_8);
            write(parentSentinel, sentinel);
            write(absoluteSentinel, sentinel);
            List<File> rejected = new ArrayList<>();
            String[] unsafeNames = {"../zip-escape.p12", "folder/../../zip-escape.p12",
                    absoluteSentinel.getAbsolutePath(), "folder/./key.p12", "folder/../key.p12", "folder\\key.p12"};
            for (int index = 0; index < unsafeNames.length; ++index) {
                File zip = new File(fixtures, "unsafe-path-" + index + ".zip");
                writeZip(zip, new String[]{"valid.p12", unsafeNames[index]}, new byte[][]{firstBytes, firstBytes});
                rejected.add(zip);
            }
            File duplicate = new File(fixtures, "duplicate-case.zip");
            writeZip(duplicate, new String[]{"same.p12", "SAME.P12"}, new byte[][]{firstBytes, secondBytes[0]});
            rejected.add(duplicate);
            File unsupported = new File(fixtures, "unsupported-entry.zip");
            writeZip(unsupported, new String[]{"valid.p12", "README.txt"},
                    new byte[][]{firstBytes, "unsupported".getBytes(StandardCharsets.UTF_8)});
            rejected.add(unsupported);
            File empty = new File(fixtures, "empty.zip");
            writeZip(empty, new String[0], new byte[0][]);
            rejected.add(empty);
            File onlyDirectory = new File(fixtures, "directory-only.zip");
            writeZip(onlyDirectory, new String[]{"empty/"}, new byte[][]{new byte[0]});
            rejected.add(onlyDirectory);
            File truncated = new File(fixtures, "truncated.zip");
            copy(archive, truncated);
            try (RandomAccessFile file = new RandomAccessFile(truncated, "rw")) { file.setLength(file.length() - 11); }
            rejected.add(truncated);
            for (File zip : rejected) {
                byte[] beforeArchive = digest(zip);
                expectIOException(() -> invoke(preview, operations, context, path(zip)));
                expectIOException(() -> invoke(importKeys, operations, context, path(zip), Collections.emptyMap()));
                require(directoryEntries(keysDirectory).equals(before) && Arrays.equals(beforeArchive, digest(zip)),
                        "Rejected ZIP persisted private keys or changed its source: " + zip.getName());
                require(Arrays.equals(sentinel, readSmallFile(parentSentinel))
                                && Arrays.equals(sentinel, readSmallFile(absoluteSentinel)),
                        "A ZIP entry escaped its staging directory");
                assertEmptyOperationCache();
            }
        });

        check("ZIP entry count, individual size, total size, input size and expansion limits apply", () -> {
            Set<String> before = directoryEntries(keysDirectory);
            String[] names = new String[MAX_ARCHIVE_KEYS + 1];
            byte[][] contents = new byte[names.length][];
            for (int index = 0; index < names.length; ++index) {
                names[index] = "key-" + index + ".p12";
                contents[index] = firstBytes;
            }
            File allowed = new File(fixtures, "maximum-key-count.zip");
            writeZip(allowed, Arrays.copyOf(names, MAX_ARCHIVE_KEYS), Arrays.copyOf(contents, MAX_ARCHIVE_KEYS));
            require(((List<?>) invoke(preview, operations, context, path(allowed))).size() == MAX_ARCHIVE_KEYS,
                    "The documented maximum key count was rejected");
            File tooMany = new File(fixtures, "too-many-keys.zip");
            writeZip(tooMany, names, contents);
            File tooLargeEntry = new File(fixtures, "oversized-entry.zip");
            writeZeroZip(tooLargeEntry, 1, MAX_KEY_BYTES + 1, false);
            File tooLargeTotal = new File(fixtures, "oversized-total.zip");
            // Five stored entries stay below 16 MiB each and below 65 MiB as an archive, but
            // together exceed the 64 MiB expanded limit. No compression-ratio limit masks it.
            writeZeroZip(tooLargeTotal, 5, 13L * 1024 * 1024 - 64L * 1024, false);
            require(tooLargeTotal.length() < MAX_ARCHIVE_BYTES, "Aggregate-size fixture exceeds the input-size limit");
            File tooLargeInput = new File(fixtures, "oversized-input.zip");
            copy(archive, tooLargeInput);
            try (RandomAccessFile file = new RandomAccessFile(tooLargeInput, "rw")) {
                file.setLength(MAX_ARCHIVE_BYTES + 1);
            }
            File expansion = new File(fixtures, "excessive-expansion.zip");
            writeZeroZip(expansion, 1, 2L * 1024 * 1024, true);
            require(expansion.length() * 100 < 2L * 1024 * 1024, "Expansion fixture does not exceed the ratio limit");
            for (File zip : Arrays.asList(tooMany, tooLargeEntry, tooLargeTotal, tooLargeInput, expansion)) {
                byte[] beforeArchive = digest(zip);
                expectIOException(() -> invoke(preview, operations, context, path(zip)));
                require(directoryEntries(keysDirectory).equals(before) && Arrays.equals(beforeArchive, digest(zip)),
                        "Rejected archive limit changed the source or private key directory: " + zip.getName());
                assertEmptyOperationCache();
            }
        });

        check("batch export rejects invalid selections and preserves occupied or replaced documents", () -> {
            List<Object> selection = Arrays.asList(generated, secondKey[0]);
            List<List<?>> invalidSelections = Arrays.asList(Collections.emptyList(), Arrays.asList(generated,
                    path(new File(application.getFilesDir(), "导入 原件.pfx"))));
            int index = 0;
            for (List<?> invalid : invalidSelections) {
                Uri destination = documents.newDocument("invalid-batch-" + index++ + ".zip");
                int opens = documents.openCalls;
                expectIOException(() -> invoke(exportKeys, operations, context, invalid, destination));
                require(documents.openCalls == opens
                                && documents.file(DocumentsContract.getDocumentId(destination)).length() == 0,
                        "An invalid selection exported a partial ZIP");
            }
            Uri occupied = documents.newDocument("occupied-batch.zip");
            File occupiedFile = documents.file(DocumentsContract.getDocumentId(occupied));
            byte[] retained = "retain occupied batch document".getBytes(StandardCharsets.UTF_8);
            write(occupiedFile, retained);
            int deletions = documents.deleteCalls;
            expectIOException(() -> invoke(exportKeys, operations, context, selection, occupied));
            require(Arrays.equals(retained, readSmallFile(occupiedFile)) && documents.deleteCalls == deletions,
                    "Batch export changed an existing document");
            Uri readOnly = documents.newDocument("failed-batch-write.zip");
            documents.readOnlyDocument = DocumentsContract.getDocumentId(readOnly);
            expectIOException(() -> invoke(exportKeys, operations, context, selection, readOnly));
            File failed = documents.file(documents.readOnlyDocument);
            require(!failed.exists() || failed.length() == 0, "Failed batch export left unexpected bytes");
            documents.readOnlyDocument = null;
            assertEmptyKeyExportJournal();
            Uri replacement = documents.newDocument("replaced-batch-write.zip");
            documents.replaceOnOpen = DocumentsContract.getDocumentId(replacement);
            deletions = documents.deleteCalls;
            expectIOException(() -> invoke(exportKeys, operations, context, selection, replacement));
            require(documents.replaceOnOpen == null && documents.deleteCalls == deletions
                            && Arrays.equals(TestDocumentsProvider.REPLACEMENT,
                            readSmallFile(documents.file(DocumentsContract.getDocumentId(replacement)))),
                    "Failed batch export changed a replacement document");
            require(Arrays.equals(firstBytes, readSmallFile(toFile(generated)))
                            && Arrays.equals(secondBytes[0], readSmallFile(toFile(secondKey[0]))),
                    "Rejected batch exports changed selected keys");
            assertEmptyOperationCache();
            assertEmptyKeyExportJournal();
        });

        check("duplicate ZIP entries and repeated imports reuse one key and erase shared passwords", () -> {
            Object nestedPath = null;
            byte[] archiveDigest = digest(nestedArchive);
            for (int repeat = 0; repeat < 2; ++repeat) {
                Set<Object> before = privateKeyPaths(context);
                Map<String, char[]> wrongPasswords = passwordsFor(nestedEntries, PASSWORD);
                char[] replacedPassword = wrongPasswords.put(entryId(nestedEntries.get(1)),
                        "wrong-duplicate-password".toCharArray());
                Arrays.fill(replacedPassword, '\0');
                expectKeyRejection(() -> invoke(importKeys, operations, context, path(nestedArchive), wrongPasswords));
                assertErased(wrongPasswords);
                require(privateKeyPaths(context).equals(before)
                                && Arrays.equals(archiveDigest, digest(nestedArchive)),
                        "An incorrect password for a duplicate was skipped or left a partial import");
                assertEmptyOperationCache();
                char[] shared = PASSWORD.toCharArray();
                Map<String, char[]> sharedPasswords = new LinkedHashMap<>();
                for (Object entry : nestedEntries) sharedPasswords.put(entryId(entry), shared);
                List<?> imported = (List<?>) invoke(importKeys, operations, context, path(nestedArchive), sharedPasswords);
                require(imported.size() == 1 && erased(shared),
                        "Identical ZIP entries were not deduplicated or retained their shared password");
                Object result = imported.get(0);
                Object key = invoke(result.getClass().getMethod("getKey"), result);
                Object privatePath = invoke(key.getClass().getMethod("getPath"), key);
                require(wasReused(result) == (repeat != 0)
                                && (repeat == 0 ? !before.contains(privatePath) : privatePath.equals(nestedPath)),
                        "Duplicate ZIP entries lost their first result or reported the wrong reuse status");
                nestedPath = privatePath;
                require(toFile(privatePath).getParentFile().equals(keysDirectory)
                                && Arrays.equals(nestedBytes, readSmallFile(toFile(privatePath))),
                        "Nested ZIP import escaped the private directory or changed its key bytes");
                Set<Object> expected = new HashSet<>(before);
                expected.add(privatePath);
                require(privateKeyPaths(context).equals(expected)
                                && expected.size() == before.size() + (repeat == 0 ? 1 : 0)
                                && Arrays.equals(archiveDigest, digest(nestedArchive)),
                        "Deduplicated ZIP import created extra files or changed its source");
                assertEmptyOperationCache();
            }
            Set<String> beforeCancel = directoryEntries(keysDirectory);
            Map<String, char[]> passwords = passwordsFor(entries, entryPasswords);
            Thread.currentThread().interrupt();
            try {
                IOException error = expectIOException(() -> invoke(importKeys, operations, context, path(archive), passwords));
                require(error instanceof InterruptedIOException && Thread.currentThread().isInterrupted(),
                        "Batch import swallowed cancellation or failed for an unrelated reason");
            } finally { Thread.interrupted(); }
            assertErased(passwords);
            require(directoryEntries(keysDirectory).equals(beforeCancel), "Cancelled batch import persisted keys");
            assertEmptyOperationCache();
        });

        check("interrupted import recovery removes partial batches and preserves complete or changed files", () -> {
            Set<Object> existing = privateKeyPaths(context);
            File journalDirectory = new File(keysDirectory.getParentFile(), "pending-key-imports");
            require(directoryEntries(journalDirectory).isEmpty(), "Earlier batch operations retained import journals");
            for (String state : Arrays.asList("partial", "complete", "modified", "replaced")) {
                File staging = new File(fixtures, "interrupted-" + state);
                mkdir(staging);
                File stagedFirst = new File(staging, "first.p12");
                File stagedSecond = new File(staging, "second.p12");
                write(stagedFirst, firstBytes);
                write(stagedSecond, secondBytes[0]);
                File first = new File(keysDirectory, "recovery-" + state + "-first.p12");
                File second = new File(keysDirectory, "recovery-" + state + "-second.p12");
                require(!first.exists() && !second.exists(), "Recovery fixture would replace an existing key");
                // Production records each staging inode and its future private path before it
                // renames any file. Reproduce that disk state, then publish only the chosen keys.
                JSONArray keys = new JSONArray().put(importJournalEntry(stagedFirst, first))
                        .put(importJournalEntry(stagedSecond, second));
                File journal = new File(journalDirectory, "regression-" + state + ".json");
                require(!journal.exists(), "Recovery fixture would replace an existing journal");
                try (FileOutputStream output = new FileOutputStream(journal)) {
                    output.write(new JSONObject().put("keys", keys).toString().getBytes(StandardCharsets.UTF_8));
                    output.getFD().sync();
                }
                byte[] journalDigest = digest(journal);
                Os.rename(stagedFirst.getPath(), first.getPath());
                long originalInode = Os.lstat(first.getPath()).st_ino;
                if (state.equals("complete")) {
                    Os.rename(stagedSecond.getPath(), second.getPath());
                } else if (state.equals("modified")) {
                    try (RandomAccessFile file = new RandomAccessFile(first, "rw")) {
                        int original = file.read();
                        file.seek(0);
                        file.write(original ^ 0x20);
                        file.getFD().sync();
                    }
                    require(Os.lstat(first.getPath()).st_ino == originalInode && first.length() == firstBytes.length,
                            "The content-change fixture did not preserve the original inode and size");
                } else if (state.equals("replaced")) {
                    File retained = new File(staging, "retained-original-inode.p12");
                    Os.rename(first.getPath(), retained.getPath());
                    copy(retained, first);
                    require(Os.lstat(first.getPath()).st_ino != originalInode
                                    && Arrays.equals(firstBytes, readSmallFile(first)),
                            "The replacement fixture did not isolate an inode-only change");
                }
                byte[] publishedDigest = digest(first);

                // A normal public management call runs the real startup/workspace recovery.
                Set<Object> recovered = privateKeyPaths(context);
                require(recovered.containsAll(existing), "Import recovery removed a preexisting private key");
                if (state.equals("partial")) {
                    require(!first.exists() && !second.exists() && !journal.exists()
                                    && recovered.equals(existing),
                            "Recovery did not roll back an incomplete published batch");
                } else if (state.equals("complete")) {
                    require(!journal.exists() && recovered.containsAll(Arrays.asList(path(first), path(second)))
                                    && Arrays.equals(firstBytes, readSmallFile(first))
                                    && Arrays.equals(secondBytes[0], readSmallFile(second)),
                            "Recovery removed or changed an entirely published batch");
                } else {
                    require(first.exists() && !second.exists() && recovered.contains(path(first))
                                    && Arrays.equals(publishedDigest, digest(first)),
                            "Recovery removed or modified a private file that no longer belongs to the transaction");
                    if (state.equals("modified")) {
                        require(journal.isFile() && Arrays.equals(journalDigest, digest(journal)),
                                "Recovery discarded the retry record for a changed file on the original inode");
                    } else {
                        require(!journal.exists(), "A replaced path did not retire its obsolete transaction record");
                    }
                }
                if (!state.equals("complete")) {
                    require(Arrays.equals(secondBytes[0], readSmallFile(stagedSecond)),
                            "Recovery changed a source outside the managed private directory");
                }
                existing = recovered;
                assertEmptyOperationCache();
            }
            require(Arrays.equals(firstBytes, readSmallFile(toFile(generated)))
                            && Arrays.equals(secondBytes[0], readSmallFile(toFile(secondKey[0]))),
                    "Transaction recovery changed an original signing key");
        });
        keySelectionRegression(context, secondKey[0]);
    }

    private static void keySelectionRegression(Context context, Object secondPath) throws Exception {
        check("real ViewModel selects and deletes whole LinuxPath objects without iterating them", () -> {
            Class<?> modelType = Class.forName("me.zhanghai.android.files.apk.ApkKeyManagementViewModel");
            Class<?> stateType = Class.forName("me.zhanghai.android.files.apk.ApkKeyManagementState");
            Class<?> keyType = Class.forName("me.zhanghai.android.files.apk.ApkPrivateKey");
            File firstFile = toFile(generated);
            File secondFile = toFile(secondPath);
            byte[] firstDigest = digest(firstFile);
            byte[] secondDigest = digest(secondFile);
            File disposable = new File(firstFile.getParentFile(), "selection-delete-key.p12");
            require(!disposable.exists(), "Selection fixture would replace an existing key");
            copy(firstFile, disposable);
            Object deletePath = path(disposable);
            List<Object> keys = new ArrayList<>();
            Constructor<?> keyConstructor = keyType.getConstructor(pathType, long.class, long.class);
            for (Object path : Arrays.asList(generated, secondPath, deletePath)) {
                require(path.getClass().getName().equals("me.zhanghai.android.files.provider.linux.LinuxPath"),
                        "The selection regression requires real production LinuxPath instances");
                File file = toFile(path);
                keys.add(keyConstructor.newInstance(path, file.lastModified(), file.length()));
            }
            Constructor<?> stateConstructor = stateType.getConstructor(List.class, boolean.class,
                    pathType, pathType, List.class,
                    Class.forName("me.zhanghai.android.files.apk.ApkKeyExportRequest"), Uri.class,
                    pathType, Class.forName("me.zhanghai.android.files.apk.ApkKeyOperation"),
                    boolean.class, String.class, Class.forName("me.zhanghai.android.files.apk.ApkKeySelection"),
                    pathType, boolean.class, Set.class, boolean.class, String.class);
            Object initialState = stateConstructor.newInstance(keys, true, generated, null, null, null,
                    null, null, null, false, null, null, null, false, Collections.emptySet(), false, "");
            Object model = modelType.getConstructor().newInstance();
            Field modelContext = modelType.getDeclaredField("context");
            modelContext.setAccessible(true);
            modelContext.set(model, context);
            // Bypass only the asynchronous initial directory refresh. All state, paths, selection
            // methods, deletion IO and the deletion completion callback are production objects.
            Object state = field(modelType, "_state", model);
            invoke(Class.forName("kotlinx.coroutines.flow.MutableStateFlow").getMethod("setValue", Object.class),
                    state, initialState);

            Method start = modelType.getMethod("startSelection", pathType);
            Method toggle = modelType.getMethod("toggleSelection", pathType);
            invoke(start, model, generated);
            assertSelection(model, true, generated);
            invoke(toggle, model, secondPath);
            assertSelection(model, true, generated, secondPath);
            invoke(toggle, model, generated);
            assertSelection(model, true, secondPath);
            invoke(modelType.getMethod("selectAll"), model);
            assertSelection(model, true, generated, secondPath, deletePath);
            invoke(modelType.getMethod("clearSelection"), model);
            assertSelection(model, true);
            invoke(toggle, model, deletePath);
            assertSelection(model, true, deletePath);
            invoke(modelType.getMethod("exitSelection"), model);
            assertSelection(model, false);

            invoke(start, model, generated);
            invoke(toggle, model, deletePath);
            assertSelection(model, true, generated, deletePath);
            invoke(modelType.getMethod("deleteKey", pathType), model, deletePath);
            finishKeyOperation(model);
            assertSelection(model, true, generated);
            Object afterDelete = keyManagementState(model);
            List<?> remaining = (List<?>) invoke(stateType.getMethod("getKeys"), afterDelete);
            Set<Object> remainingPaths = new HashSet<>();
            for (Object key : remaining) remainingPaths.add(invoke(keyType.getMethod("getPath"), key));
            require(!disposable.exists() && remainingPaths.equals(new HashSet<>(Arrays.asList(generated, secondPath)))
                            && generated.equals(invoke(stateType.getMethod("getSelectedPath"), afterDelete))
                            && deletePath.equals(invoke(stateType.getMethod("getDeletedPath"), afterDelete)),
                    "The real deletion callback lost, retained or iterated the wrong selected path");
            invoke(modelType.getMethod("consumeChanges"), model);
            Object consumed = keyManagementState(model);
            require(invoke(stateType.getMethod("getPendingDelete"), consumed) == null
                            && invoke(stateType.getMethod("getDeletedPath"), consumed) == null,
                    "The selection model did not consume the completed deletion");
            invoke(modelType.getMethod("exitSelection"), model);
            assertSelection(model, false);
            require(Arrays.equals(firstDigest, digest(firstFile)) && Arrays.equals(secondDigest, digest(secondFile)),
                    "Selection or deletion changed another private key");
            assertEmptyOperationCache();
        });
    }

    private static Object keyManagementState(Object model) throws Exception {
        Object flow = invoke(model.getClass().getMethod("getState"), model);
        return invoke(Class.forName("kotlinx.coroutines.flow.StateFlow").getMethod("getValue"), flow);
    }

    private static void assertSelection(Object model, boolean selectionMode, Object... expectedPaths) throws Exception {
        Object state = keyManagementState(model);
        require(((Boolean) invoke(state.getClass().getMethod("getSelectionMode"), state)) == selectionMode,
                "Unexpected ViewModel selection mode");
        require(invoke(state.getClass().getMethod("getCheckedPaths"), state)
                        .equals(new HashSet<>(Arrays.asList(expectedPaths))),
                "Selection contains path segments or the wrong whole paths");
    }

    private static void finishKeyOperation(Object model) throws Exception {
        require(Looper.myLooper() == Looper.getMainLooper(), "ViewModel regression must drive the real main Looper");
        Handler handler = Handler.createAsync(Looper.getMainLooper());
        AtomicBoolean timedOut = new AtomicBoolean();
        Runnable timeout = () -> timedOut.set(true);
        handler.postDelayed(timeout, 10_000);
        Method next = Class.forName("android.os.MessageQueue").getDeclaredMethod("next");
        Method recycle = Message.class.getDeclaredMethod("recycleUnchecked");
        try {
            while (invoke(keyManagementState(model).getClass().getMethod("getOperation"), keyManagementState(model)) != null
                    && !timedOut.get()) {
                Message message = (Message) invoke(next, Looper.getMainLooper().getQueue());
                require(message != null && message.getTarget() != null, "The main message queue closed unexpectedly");
                try { message.getTarget().dispatchMessage(message); }
                finally { invoke(recycle, message); }
            }
            require(!timedOut.get(), "The real ViewModel deletion did not finish within 10 seconds");
        } finally { handler.removeCallbacks(timeout); }
    }

    private static boolean wasReused(Object result) throws Exception {
        return (Boolean) invoke(result.getClass().getMethod("getReused"), result);
    }

    private static JSONObject importJournalEntry(File staged, File destination) throws Exception {
        android.system.StructStat stat = Os.lstat(staged.getPath());
        StringBuilder hash = new StringBuilder();
        for (byte value : digest(staged)) hash.append(String.format(Locale.ROOT, "%02x", value & 255));
        return new JSONObject().put("path", destination.getPath()).put("device", stat.st_dev)
                .put("inode", stat.st_ino).put("size", staged.length()).put("sha256", hash.toString());
    }

    private static String entryId(Object entry) throws Exception {
        return (String) invoke(entry.getClass().getMethod("getId"), entry);
    }

    private static String entryName(Object entry) throws Exception {
        return (String) invoke(entry.getClass().getMethod("getName"), entry);
    }

    private static String entryFormat(Object entry) throws Exception {
        return (String) invoke(entry.getClass().getMethod("getFormat"), entry);
    }

    private static long entrySize(Object entry) throws Exception {
        return (Long) invoke(entry.getClass().getMethod("getSize"), entry);
    }

    private static Set<String> entryIds(List<?> entries) throws Exception {
        Set<String> ids = new HashSet<>();
        for (Object entry : entries) ids.add(entryId(entry));
        return ids;
    }

    private static Map<String, char[]> passwordsFor(List<?> entries, Map<String, String> passwordsByName)
            throws Exception {
        Map<String, char[]> passwords = new LinkedHashMap<>();
        for (Object entry : entries) {
            String password = passwordsByName.get(entryName(entry));
            require(password != null, "No fixture password exists for preview entry: " + entryName(entry));
            passwords.put(entryId(entry), password.toCharArray());
        }
        return passwords;
    }

    private static Map<String, char[]> passwordsFor(List<?> entries, String password) throws Exception {
        Map<String, char[]> passwords = new LinkedHashMap<>();
        for (Object entry : entries) passwords.put(entryId(entry), password.toCharArray());
        return passwords;
    }

    private static void assertErased(Map<String, char[]> passwords) {
        for (char[] password : passwords.values()) require(erased(password), "Batch operation retained a supplied password");
    }

    private static void assertEmptyKeyExportJournal() {
        require(directoryEntries(new File(application.getNoBackupFilesDir(),
                        "apk-signing/pending-key-exports")).isEmpty(),
                "A completed or safely cleaned export retained a journal");
    }

    private static String matchingEntry(byte[] bytes, Map<String, byte[]> entries) {
        for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
            if (Arrays.equals(bytes, entry.getValue())) return entry.getKey();
        }
        throw new AssertionError("An imported key does not match any original ZIP entry");
    }

    private static byte[] readSmallFile(File file) throws IOException {
        try (InputStream input = new FileInputStream(file)) { return readSmallStream(input); }
    }

    private static byte[] readSmallStream(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[64 * 1024];
        for (int count; (count = input.read(buffer)) >= 0;) {
            require((long) output.size() + count <= MAX_KEY_BYTES, "Unexpectedly large small fixture");
            output.write(buffer, 0, count);
        }
        return output.toByteArray();
    }

    private static Map<String, byte[]> readZipEntries(File file) throws IOException {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        try (ZipFile zip = new ZipFile(file)) {
            for (ZipEntry entry : Collections.list(zip.entries())) {
                require(!entry.isDirectory(), "Flat exported ZIP unexpectedly contains a directory");
                try (InputStream input = zip.getInputStream(entry)) {
                    require(entries.put(entry.getName(), readSmallStream(input)) == null,
                            "Exported ZIP contains duplicate names");
                }
            }
        }
        return entries;
    }

    private static void writeZip(File target, String[] names, byte[][] contents) throws IOException {
        require(names.length == contents.length, "ZIP fixture names and contents disagree");
        try (ZipOutputStream output = new ZipOutputStream(new FileOutputStream(target))) {
            for (int index = 0; index < names.length; ++index) {
                output.putNextEntry(new ZipEntry(names[index]));
                output.write(contents[index]);
                output.closeEntry();
            }
        }
    }

    private static void writeZeroZip(File target, int count, long bytesPerEntry, boolean compressed)
            throws IOException {
        byte[] zeros = new byte[64 * 1024];
        CRC32 crc = new CRC32();
        for (long remaining = bytesPerEntry; remaining > 0;) {
            int size = (int) Math.min(remaining, zeros.length);
            crc.update(zeros, 0, size);
            remaining -= size;
        }
        try (ZipOutputStream output = new ZipOutputStream(new FileOutputStream(target))) {
            for (int index = 0; index < count; ++index) {
                ZipEntry entry = new ZipEntry("bounded-" + index + ".p12");
                if (!compressed) {
                    entry.setMethod(ZipEntry.STORED);
                    entry.setSize(bytesPerEntry);
                    entry.setCompressedSize(bytesPerEntry);
                    entry.setCrc(crc.getValue());
                }
                output.putNextEntry(entry);
                for (long remaining = bytesPerEntry; remaining > 0;) {
                    int size = (int) Math.min(remaining, zeros.length);
                    output.write(zeros, 0, size);
                    remaining -= size;
                }
                output.closeEntry();
            }
        }
    }

    private static Set<Object> privateKeyPaths(Context context) throws Exception {
        List<?> keys = (List<?>) invoke(operations.getClass().getMethod("listPrivateKeys", Context.class),
                operations, context);
        Set<Object> paths = new HashSet<>();
        for (Object key : keys) paths.add(invoke(key.getClass().getMethod("getPath"), key));
        require(paths.size() == keys.size(), "Private key enumeration contains duplicate paths");
        return paths;
    }

    private static Set<String> directoryEntries(File directory) {
        String[] entries = directory.list();
        require(entries != null, "Cannot enumerate private key directory");
        return new HashSet<>(Arrays.asList(entries));
    }

    private static void expectKeyRejection(CheckedRunnable action) throws Exception {
        try { action.run(); }
        catch (IOException | GeneralSecurityException expected) { return; }
        throw new AssertionError("Expected the invalid key-store import to be rejected");
    }

    private static boolean isUnregisteredMediaScanner(Throwable error) {
        if (!(error instanceof SecurityException) || error.getMessage() == null
                || !error.getMessage().startsWith("Unable to find app for caller ")
                || !error.getMessage().contains("when getting content provider media")) return false;
        for (StackTraceElement frame : error.getStackTrace()) {
            if (frame.getClassName().equals("android.media.MediaScannerConnection")
                    && (frame.getMethodName().equals("scanFile")
                    || frame.getMethodName().startsWith("lambda$scanFile$"))) return true;
        }
        return false;
    }

    private static void mutation(String kind) throws Exception {
        File source = new File(application.getFilesDir(), "change-" + kind + ".bin");
        copy(toFile(generated), source);
        long timestamp = 1_600_000_000_000L;
        require(source.setLastModified(timestamp), "Cannot set fixture timestamp");
        long size = source.length();
        long inode = Os.lstat(source.getPath()).st_ino;
        AtomicBoolean changed = new AtomicBoolean();
        Object callback = Proxy.newProxyInstance(callbackType.getClassLoader(), new Class<?>[]{callbackType},
                (proxy, method, arguments) -> {
                    if (method.getName().equals("onProgress") && arguments[0].equals("copy")
                            && arguments[1].equals(arguments[2]) && changed.compareAndSet(false, true)) {
                        if (kind.equals("inode")) {
                            File retained = new File(source.getParentFile(), "retained-inode.bin");
                            Os.rename(source.getPath(), retained.getPath());
                            copy(retained, source);
                            require(source.setLastModified(timestamp), "Cannot restore replacement timestamp");
                            require(source.length() == size && source.lastModified() == timestamp
                                    && Os.lstat(source.getPath()).st_ino != inode,
                                    "Replacement did not isolate the file-identity change");
                        } else if (kind.equals("mtime")) {
                            try (RandomAccessFile file = new RandomAccessFile(source, "rw")) {
                                int first = file.read();
                                file.seek(0);
                                file.write(first ^ 0x40);
                            }
                            require(source.setLastModified(timestamp + 2000), "Cannot change source timestamp");
                            require(source.length() == size && Os.lstat(source.getPath()).st_ino == inode,
                                    "In-place edit changed the size or inode");
                        } else {
                            try (FileOutputStream output = new FileOutputStream(source, true)) { output.write(7); }
                        }
                    }
                    return null;
                });
        IOException rejected = expectIOException(() -> invoke(stage, operations, application,
                path(source), new File(work, "staged-" + kind), MAX_KEY_BYTES, callback, 1));
        require(changed.get(), "Mutation callback was not reached");
        require(resource("apk_operation_input_changed").equals(rejected.getMessage()),
                "Modified source failed for an unrelated reason: " + rejected);
        require(source.exists(), "Rejected staging deleted the source");
    }

    private static List<?> aliases(Object path) throws Exception {
        return aliases(path, application);
    }

    private static List<?> aliases(Object path, Context context) throws Exception {
        return aliases(path, context, PASSWORD);
    }

    private static List<?> aliases(Object path, Context context, String passwordValue) throws Exception {
        char[] password = passwordValue.toCharArray();
        List<?> aliases = (List<?>) invoke(readAliases, operations, context, path, "PKCS12", password);
        require(erased(password), "Alias reading retained the password");
        return aliases;
    }

    private static void assertLocal(Object path) throws Exception {
        for (boolean attributes : new boolean[]{false, true}) {
            require(selectedProvider(path, attributes) == localProvider,
                    "App-private path incorrectly requires root (attributes=" + attributes + "): " + path);
        }
    }

    private static Object selectedProvider(Object path, boolean attributes) throws Exception {
        // Observe production routing by returning the chosen provider itself; do not replace its IO.
        Object identity = Proxy.newProxyInstance(functionType.getClassLoader(),
                new Class<?>[]{functionType}, (proxy, method, arguments) -> arguments[0]);
        return invoke(route, null, path, attributes, localProvider, rootProvider, identity);
    }

    private static String resource(String name) throws Exception {
        int id = Class.forName("me.zhanghai.android.files.R$string").getField(name).getInt(null);
        return application.getString(id);
    }

    private static Object path(File file) throws Exception {
        return invoke(pathsGet, null, file.getPath(), new String[0]);
    }

    private static File toFile(Object path) throws Exception {
        return (File) invoke(pathType.getMethod("toFile"), path);
    }

    private static Object singleton(String name) throws Exception {
        return Class.forName(name).getField("INSTANCE").get(null);
    }

    private static Object field(Class<?> type, String name, Object receiver) throws Exception {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(receiver);
    }

    private static Object invoke(Method method, Object receiver, Object... arguments) throws Exception {
        method.setAccessible(true);
        try { return method.invoke(receiver, arguments); }
        catch (InvocationTargetException error) {
            Throwable cause = error.getCause();
            if (cause instanceof Exception) throw (Exception) cause;
            if (cause instanceof Error) throw (Error) cause;
            throw error;
        }
    }

    private static IOException expectIOException(CheckedRunnable action) throws Exception {
        try { action.run(); }
        catch (IOException error) { return error; }
        throw new AssertionError("Expected an IOException");
    }

    private static void assertEmptyOperationCache() {
        String[] entries = new File(application.getCacheDir(), "apk-signing").list();
        require(entries != null && entries.length == 0, "Private operation workspace was not cleaned");
    }

    private static byte[] digest(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = new FileInputStream(file)) {
            byte[] buffer = new byte[64 * 1024];
            for (int count; (count = input.read(buffer)) >= 0;) digest.update(buffer, 0, count);
        }
        return digest.digest();
    }

    private static void copy(File source, File target) throws IOException {
        try (InputStream input = new FileInputStream(source); FileOutputStream output = new FileOutputStream(target)) {
            byte[] buffer = new byte[64 * 1024];
            for (int count; (count = input.read(buffer)) >= 0;) output.write(buffer, 0, count);
        }
    }

    private static void write(File file, byte[] bytes) throws IOException {
        try (FileOutputStream output = new FileOutputStream(file)) { output.write(bytes); }
    }

    private static boolean erased(char[] password) {
        for (char value : password) if (value != 0) return false;
        return true;
    }

    private static void mkdir(File directory) {
        require(directory.isDirectory() || directory.mkdirs(), "Cannot create test directory: " + directory);
    }

    private static void check(String name, CheckedRunnable action) throws Exception {
        ++checks;
        JSONObject result = new JSONObject().put("name", name);
        try {
            action.run();
            result.put("passed", true);
        } catch (Throwable error) {
            result.put("passed", false).put("error", error.toString());
            throw error;
        } finally { System.out.println("PRIVATE_KEY_CASE " + result); }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static void fail(Throwable error) {
        error.printStackTrace(System.err);
        System.exit(1);
    }

    private interface CheckedRunnable { void run() throws Exception; }

    /** A real local file provider; only explicit fault modes alter the descriptors it returns. */
    private static final class TestDocumentsProvider extends DocumentsProvider {
        static final byte[] REPLACEMENT = "different document: retain these bytes".getBytes(StandardCharsets.UTF_8);
        private static final String[] DOCUMENT_COLUMNS = {
                DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_SIZE,
                DocumentsContract.Document.COLUMN_FLAGS
        };
        final File directory;
        final String authority;
        int openCalls;
        int deleteCalls;
        String readOnlyDocument;
        String replaceOnOpen;

        TestDocumentsProvider(File directory, String authority) {
            this.directory = directory;
            this.authority = authority;
            mkdir(directory);
        }

        Uri newDocument(String name) throws IOException {
            require(file(name).createNewFile(), "Cannot create a fresh export document: " + name);
            return DocumentsContract.buildDocumentUri(authority, name);
        }

        File file(String documentId) {
            require(!documentId.isEmpty() && !documentId.equals(".") && !documentId.equals("..")
                            && documentId.indexOf('/') < 0, "Unexpected test document ID");
            return new File(directory, documentId);
        }

        @Override public boolean onCreate() { return true; }

        @Override public Cursor queryRoots(String[] projection) {
            return new MatrixCursor(projection != null ? projection : new String[]{DocumentsContract.Root.COLUMN_ROOT_ID});
        }

        @Override public Cursor queryChildDocuments(String parent, String[] projection, String sort) {
            return new MatrixCursor(projection != null ? projection : DOCUMENT_COLUMNS);
        }

        @Override public Cursor queryDocument(String documentId, String[] projection) throws FileNotFoundException {
            File file = file(documentId);
            if (!file.isFile()) throw new FileNotFoundException(documentId);
            String[] columns = projection != null ? projection : DOCUMENT_COLUMNS;
            MatrixCursor cursor = new MatrixCursor(columns);
            MatrixCursor.RowBuilder row = cursor.newRow();
            for (String column : columns) {
                switch (column) {
                    case DocumentsContract.Document.COLUMN_DOCUMENT_ID: row.add(documentId); break;
                    case DocumentsContract.Document.COLUMN_DISPLAY_NAME: row.add(file.getName()); break;
                    case DocumentsContract.Document.COLUMN_MIME_TYPE: row.add("application/octet-stream"); break;
                    case DocumentsContract.Document.COLUMN_SIZE: row.add(file.length()); break;
                    case DocumentsContract.Document.COLUMN_FLAGS:
                        row.add(DocumentsContract.Document.FLAG_SUPPORTS_WRITE
                                | DocumentsContract.Document.FLAG_SUPPORTS_DELETE); break;
                    default: row.add(null);
                }
            }
            return cursor;
        }

        @Override public ParcelFileDescriptor openDocument(String documentId, String mode,
                CancellationSignal signal) throws FileNotFoundException {
            if (signal != null) signal.throwIfCanceled();
            ++openCalls;
            File file = file(documentId);
            boolean replace = "rw".equals(mode) && documentId.equals(replaceOnOpen);
            boolean readOnly = replace || "rw".equals(mode) && documentId.equals(readOnlyDocument);
            ParcelFileDescriptor descriptor = ParcelFileDescriptor.open(file,
                    readOnly ? ParcelFileDescriptor.MODE_READ_ONLY : ParcelFileDescriptor.parseMode(mode));
            if (replace) {
                try {
                    require(file.renameTo(new File(directory, documentId + ".retained")),
                            "Cannot replace the export document fixture");
                    write(file, REPLACEMENT);
                    replaceOnOpen = null;
                } catch (IOException | RuntimeException | Error error) {
                    try { descriptor.close(); } catch (IOException closeError) { error.addSuppressed(closeError); }
                    throw new FileNotFoundException(error.toString());
                }
            }
            return descriptor;
        }

        @Override public void deleteDocument(String documentId) throws FileNotFoundException {
            ++deleteCalls;
            if (!file(documentId).delete()) throw new FileNotFoundException(documentId);
        }
    }

    private static final class TestApplication extends Application {
        final File credentialData;
        final File deviceData;
        final ApplicationInfo info;
        final Resources resources;
        final SharedPreferences preferences;

        TestApplication(Context base, File work, File apk) throws Exception {
            attachBaseContext(base);
            File realCredential = new File(work, "credential-data");
            File realDevice = new File(work, "device-data");
            mkdir(realCredential);
            mkdir(realDevice);
            credentialData = new File(work, "credential-alias");
            deviceData = new File(work, "device-alias");
            Os.symlink(realCredential.getPath(), credentialData.getPath());
            Os.symlink(realDevice.getPath(), deviceData.getPath());
            info = new ApplicationInfo(base.getApplicationInfo());
            info.dataDir = credentialData.getPath();
            info.deviceProtectedDataDir = deviceData.getPath();
            AssetManager assets = AssetManager.class.getDeclaredConstructor().newInstance();
            int cookie = (Integer) invoke(AssetManager.class.getDeclaredMethod("addAssetPath", String.class),
                    assets, apk.getPath());
            require(cookie != 0, "Cannot load the actual APK resource table");
            resources = new Resources(assets, base.getResources().getDisplayMetrics(),
                    base.getResources().getConfiguration());
            mkdir(getCacheDir());
            mkdir(getFilesDir());
            mkdir(getNoBackupFilesDir());
            Constructor<?> constructor = Class.forName("android.app.SharedPreferencesImpl")
                    .getDeclaredConstructor(File.class, int.class);
            constructor.setAccessible(true);
            preferences = (SharedPreferences) constructor.newInstance(new File(work, "test-preferences.xml"),
                    Context.MODE_PRIVATE);
        }

        @Override public Context getApplicationContext() { return this; }
        @Override public File getDataDir() { return credentialData; }
        @Override public ApplicationInfo getApplicationInfo() { return info; }
        @Override public File getFilesDir() { return new File(credentialData, "files"); }
        @Override public File getCacheDir() { return new File(credentialData, "cache"); }
        @Override public File getNoBackupFilesDir() { return new File(credentialData, "no_backup"); }
        @Override public Resources getResources() { return resources; }
        @Override public AssetManager getAssets() { return resources.getAssets(); }
        @Override public SharedPreferences getSharedPreferences(String name, int mode) { return preferences; }
    }
}
