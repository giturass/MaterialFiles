/* Copyright (c) 2026 Material Files contributors. All Rights Reserved. */

#include <cstddef>
#include <cstdint>
#include <cstdio>

#include <jni.h>

namespace {

// Only the COM ABI declarations used to configure the existing upstream engine are reproduced here.
// Source: omicronapps/7-Zip-JBinding-4Android, Release-16.02-2.03,
// commit 875f38aac441f41e6eb693177e020e97971dca97 (also pinned in docs/7zip-jbinding-source.json).
// Under sevenzipjbinding/src/main/cpp/7zip/CPP/:
//   Common/MyWindows.h, Common/MyGuidDef.h, 7zip/IDecl.h, 7zip/Archive/IArchive.h.
// The JavaToCPPOutArchiveImpl.cpp GetArchive() helper restores this same IOutArchive* handle.
// No codec implementation, archive data or password crosses this bridge.
struct Guid {
    uint32_t data1;
    uint16_t data2;
    uint16_t data3;
    uint8_t data4[8];
};

struct PropertyVariant {
    uint16_t type;
    uint16_t reserved1;
    uint16_t reserved2;
    uint16_t reserved3;
    union {
        uint32_t unsignedValue;
        uint64_t largeValue;
        int16_t booleanValue;
        wchar_t* stringValue;
    } value;
};

struct Unknown {
    virtual int32_t queryInterface(const Guid& id, void** object) = 0;
    virtual uint32_t addRef() = 0;
    virtual uint32_t release() = 0;
    // The Android/p7zip IUnknown ABI has virtual destructor slots after Release().
    // Omitting these would call a destructor in place of SetProperties().
    virtual ~Unknown() = default;
};

struct SetProperties : Unknown {
    virtual int32_t setProperties(
            const wchar_t* const* names, const PropertyVariant* values, uint32_t count) = 0;
};

constexpr Guid kSetPropertiesId = {
        0x23170F69, 0x40C1, 0x278A, {0, 0, 0, 6, 0, 3, 0, 0}};
constexpr uint16_t kString = 8;
constexpr uint16_t kBoolean = 11;
constexpr uint16_t kUnsigned = 19;

static_assert(sizeof(wchar_t) == 4, "JBinding's Android ABI requires 32-bit wchar_t");
static_assert(sizeof(Guid) == 16 && offsetof(Guid, data4) == 8, "Unexpected GUID layout");
static_assert(sizeof(PropertyVariant) == 16, "Unexpected PROPVARIANT size");
static_assert(offsetof(PropertyVariant, value) == 8, "Unexpected PROPVARIANT layout");
static_assert(sizeof(uintptr_t) <= sizeof(jlong), "Java cannot hold the native archive pointer");

void throwIOException(JNIEnv* env, const char* message) {
    if (env->ExceptionCheck()) return;
    jclass exceptionClass = env->FindClass("java/io/IOException");
    if (!exceptionClass) return;
    env->ThrowNew(exceptionClass, message);
    env->DeleteLocalRef(exceptionClass);
}

void throwResult(JNIEnv* env, const char* operation, int32_t result) {
    char message[128];
    std::snprintf(message, sizeof(message), "%s failed (7-Zip result 0x%08x)",
                  operation, static_cast<unsigned>(result));
    throwIOException(env, message);
}

struct PropertiesReference {
    SetProperties* properties = nullptr;

    ~PropertiesReference() {
        if (properties) properties->release();
    }
};

}  // namespace

extern "C" JNIEXPORT void JNICALL
Java_me_zhanghai_android_files_provider_archive_archiver_SevenZipArchiveProperties_nativeConfigure(
        JNIEnv* env, jobject, jobject archive, jint level, jboolean encryptHeaders, jboolean zipAes) {
    if (!archive || level < 0 || level > 9 || (encryptHeaders && zipAes)) {
        throwIOException(env, "Invalid 7-Zip archive properties");
        return;
    }
    jclass archiveClass = env->FindClass("net/sf/sevenzipjbinding/impl/OutArchiveImpl");
    if (!archiveClass) return;
    if (!env->IsInstanceOf(archive, archiveClass)) {
        env->DeleteLocalRef(archiveClass);
        throwIOException(env, "Unsupported 7-Zip writer implementation");
        return;
    }
    jfieldID handleField = env->GetFieldID(archiveClass, "sevenZipArchiveInstance", "J");
    env->DeleteLocalRef(archiveClass);
    if (!handleField) return;
    jlong handle = env->GetLongField(archive, handleField);
    if (env->ExceptionCheck()) return;
    if (!handle) {
        throwIOException(env, "The 7-Zip writer is closed");
        return;
    }

    try {
        // QueryInterface adjusts the pointer for the handler's multiple COM interfaces and AddRefs
        // the result. Keep that reference until SetProperties has finished, including exception paths.
        auto* unknown = reinterpret_cast<Unknown*>(static_cast<uintptr_t>(handle));
        PropertiesReference reference;
        void* properties = nullptr;
        int32_t result = unknown->queryInterface(kSetPropertiesId, &properties);
        reference.properties = static_cast<SetProperties*>(properties);
        if (result != 0) {
            throwResult(env, "Querying 7-Zip archive properties", result);
            return;
        }
        if (!reference.properties) {
            throwIOException(env, "7-Zip returned no archive properties interface");
            return;
        }

        const wchar_t* names[2] = {L"x", nullptr};
        PropertyVariant values[2] = {};
        values[0].type = kUnsigned;
        values[0].value.unsignedValue = static_cast<uint32_t>(level);
        uint32_t count = 1;
        // ZipHandlerOut.cpp copies this NUL-terminated value into UString during SetProperties;
        // it does not retain, free or inspect a BSTR length prefix on the caller's buffer.
        wchar_t aes256[] = L"AES256";
        if (zipAes) {
            names[1] = L"em";
            values[1].type = kString;
            values[1].value.stringValue = aes256;
            count = 2;
        } else if (encryptHeaders) {
            names[1] = L"he";
            values[1].type = kBoolean;
            values[1].value.booleanValue = -1;
            count = 2;
        }
        // A single call is required: the upstream 7z and ZIP handlers reset all existing properties
        // at the start of SetProperties. Separate x/he/em calls silently lose previous settings.
        result = reference.properties->setProperties(names, values, count);
        if (result != 0) throwResult(env, "Setting 7-Zip archive properties", result);
    } catch (...) {
        // ZIP's property parser can allocate without a COM exception guard. Never unwind into JNI.
        throwIOException(env, "Unable to set 7-Zip archive properties");
    }
}
