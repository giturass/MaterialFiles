/* Copyright (c) 2026 Material Files contributors. All Rights Reserved. */

#include <cstdint>
#include <cstdio>

#include <jni.h>

#include "7zip/Archive/IArchive.h"

namespace {

// Compiled against the same official headers as lib7-Zip-JBinding.so. Since 7-Zip 23,
// the default Unix IUnknown ABI has no virtual destructor; do not reproduce that ABI by hand.
constexpr GUID kSetPropertiesId = {
        0x23170F69, 0x40C1, 0x278A, {0, 0, 0, 6, 0, 3, 0, 0}};
constexpr jint kSevenZip = 0;
constexpr jint kZip = 1;
constexpr jint kXz = 2;

static_assert(sizeof(uintptr_t) <= sizeof(jlong), "Java cannot hold the native archive pointer");

void throwIOException(JNIEnv* env, const char* message) {
    if (env->ExceptionCheck()) return;
    jclass exceptionClass = env->FindClass("java/io/IOException");
    if (!exceptionClass) return;
    env->ThrowNew(exceptionClass, message);
    env->DeleteLocalRef(exceptionClass);
}

void throwResult(JNIEnv* env, const char* operation, HRESULT result) {
    char message[128];
    std::snprintf(message, sizeof(message), "%s failed (7-Zip result 0x%08x)",
                  operation, static_cast<unsigned>(result));
    throwIOException(env, message);
}

struct PropertiesReference {
    ISetProperties* properties = nullptr;

    ~PropertiesReference() {
        if (properties) properties->Release();
    }
};

struct LocalReference {
    JNIEnv* env;
    jobject object;

    LocalReference(JNIEnv* env, jobject object) : env(env), object(object) {}
    LocalReference(const LocalReference&) = delete;
    LocalReference& operator=(const LocalReference&) = delete;

    ~LocalReference() {
        if (object) env->DeleteLocalRef(object);
    }
};

jlong getNewWriterHandle(JNIEnv* env, jobject archive) {
    jclass archiveClass = env->FindClass("net/sf/sevenzipjbinding/impl/OutArchiveImpl");
    LocalReference classReference(env, archiveClass);
    if (!archiveClass) return 0;
    if (!archive || !env->IsInstanceOf(archive, archiveClass)) {
        throwIOException(env, "Unsupported 7-Zip writer implementation");
        return 0;
    }
    jfieldID handleField = env->GetFieldID(archiveClass, "sevenZipArchiveInstance", "J");
    if (!handleField) return 0;
    jfieldID sessionField = env->GetFieldID(archiveClass, "jbindingSession", "J");
    if (!sessionField) return 0;
    jfieldID closedField = env->GetFieldID(archiveClass, "closed", "Z");
    if (!closedField) return 0;
    jfieldID inArchiveField = env->GetFieldID(
            archiveClass, "inArchive", "Lnet/sf/sevenzipjbinding/IInArchive;");
    if (!inArchiveField) return 0;
    jlong handle = env->GetLongField(archive, handleField);
    jlong session = env->GetLongField(archive, sessionField);
    jboolean closed = env->GetBooleanField(archive, closedField);
    jobject inArchive = env->GetObjectField(archive, inArchiveField);
    LocalReference inArchiveReference(env, inArchive);
    if (env->ExceptionCheck()) return 0;
    if (!handle || !session || closed || inArchive) {
        throwIOException(env, "Archive creation properties require an open, new writer");
        return 0;
    }
    return handle;
}

}  // namespace

extern "C" JNIEXPORT jint JNICALL
Java_me_zhanghai_android_files_provider_archive_archiver_SevenZipArchiveProperties_nativeGetInterfaceVersion(
        JNIEnv*, jobject) {
    // Version 1 uses the official Unix COM ABI and passes an explicit archive format.
    return 1;
}

extern "C" JNIEXPORT void JNICALL
Java_me_zhanghai_android_files_provider_archive_archiver_SevenZipArchiveProperties_nativeConfigure(
        JNIEnv* env, jobject, jobject archive, jint format, jint level,
        jboolean encryptHeaders, jboolean zipAes) {
    if (level < 0 || level > 9 || format < kSevenZip || format > kXz
            || (encryptHeaders && format != kSevenZip) || (zipAes && format != kZip)) {
        throwIOException(env, "Invalid 7-Zip archive properties");
        return;
    }
    jlong handle = getNewWriterHandle(env, archive);
    if (!handle) return;

    try {
        auto* output = reinterpret_cast<IOutArchive*>(static_cast<uintptr_t>(handle));
        PropertiesReference reference;
        void* properties = nullptr;
        HRESULT result = output->QueryInterface(kSetPropertiesId, &properties);
        reference.properties = static_cast<ISetProperties*>(properties);
        if (result != S_OK) {
            throwResult(env, "Querying 7-Zip archive properties", result);
            return;
        }
        if (!reference.properties) {
            throwIOException(env, "7-Zip returned no archive properties interface");
            return;
        }

        const wchar_t* names[3] = {L"x", nullptr, nullptr};
        PROPVARIANT values[3] = {};
        values[0].vt = VT_UI4;
        values[0].ulVal = static_cast<UInt32>(level);
        UInt32 count = 1;
        if (format == kSevenZip || format == kXz) {
            // Retain the previous presets' dictionary sizes: 64 KiB / 16 MiB / 32 MiB.
            // Modern defaults can use 128 MiB at level 7. MethodProps.cpp interprets a
            // VT_UI4 dictionary property as log2(bytes), not as a byte count.
            names[count] = format == kSevenZip ? L"0d" : L"d";
            values[count].vt = VT_UI4;
            values[count].ulVal = level <= 5 ? static_cast<UInt32>(level * 2 + 14) : 25;
            ++count;
        }
        // The official parser reads this NUL-terminated string immediately; it does not
        // retain/free the caller's buffer or use a BSTR length prefix for this property.
        wchar_t aes256[] = L"AES256";
        if (zipAes) {
            names[count] = L"em";
            values[count].vt = VT_BSTR;
            values[count].bstrVal = aes256;
            ++count;
        } else if (encryptHeaders) {
            names[count] = L"he";
            values[count].vt = VT_BOOL;
            values[count].boolVal = VARIANT_TRUE;
            ++count;
        }
        // Every SetProperties call resets the handler, so submit all settings at once.
        result = reference.properties->SetProperties(names, values, count);
        if (result != S_OK) throwResult(env, "Setting 7-Zip archive properties", result);
    } catch (...) {
        throwIOException(env, "Unable to set 7-Zip archive properties");
    }
}
