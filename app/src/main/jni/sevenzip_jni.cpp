/* Copyright (c) 2026 Material Files contributors. All Rights Reserved. */
#include <jni.h>

#include <algorithm>
#include <atomic>
#include <climits>
#include <cstdint>
#include <cstdio>
#include <cwchar>
#include <limits>
#include <memory>
#include <mutex>
#include <vector>

// Defines the official COM interface IDs once, before any interface declarations.
#include "7zip/CPP/Common/MyInitGuid.h"
#include "7zip/CPP/Common/MyCom.h"
#include "7zip/CPP/Windows/PropVariant.h"
#include "7zip/CPP/7zip/IPassword.h"
#include "7zip/CPP/7zip/Archive/7z/7zHandler.h"
#include "sevenzip_memory.h"

using NWindows::NCOM::CPropVariant;
using materialfiles::MemoryBudget;

#define MF7Z_COM_METHOD(method) Z7_COM7F_IMF(method) override

namespace {

constexpr size_t kBufferSize = 64 * 1024;
constexpr uint64_t kCreateMemoryLimit = 512ULL * 1024 * 1024;
constexpr int64_t kFileTimeEpochMillis = 11644473600000LL;
constexpr int64_t kMissingTime = std::numeric_limits<int64_t>::min();
constexpr char kClassPrefix[] = "me/zhanghai/android/files/provider/archive/archiver/NativeSevenZip";

JavaVM *gVm;

struct JniIds {
    jclass entry;
    jclass ioException;
    jclass interruptedException;
    jclass archiveException;
    jclass thread;
    jmethodID entryConstructor;
    jmethodID ioExceptionConstructor;
    jmethodID archiveExceptionConstructor;
    jmethodID currentThread;
    jmethodID isInterrupted;
    jmethodID channelRead;
    jmethodID channelWrite;
    jmethodID channelSeek;
    jmethodID channelSetSize;
    jmethodID inputRead;
    jmethodID inputClose;
    jmethodID outputWrite;
    jmethodID extractOpen;
    jmethodID extractResult;
    jmethodID createOpen;
    jmethodID createProgress;
    jmethodID createResult;
    jfieldID index;
    jfieldID name;
    jfieldID directory;
    jfieldID size;
    jfieldID encrypted;
    jfieldID hasAttributes;
    jfieldID attributes;
    jfieldID times[3];
} g;

class ThreadEnvironment {
public:
    ThreadEnvironment() {
        if (gVm->GetEnv(reinterpret_cast<void **>(&env_), JNI_VERSION_1_6) == JNI_EDETACHED) {
            if (gVm->AttachCurrentThread(
#ifdef __ANDROID__
                    &env_,
#else
                    reinterpret_cast<void **>(&env_),
#endif
                    nullptr)
                    == JNI_OK) {
                attached_ = true;
            }
        }
    }
    ~ThreadEnvironment() {
        if (attached_) {
            gVm->DetachCurrentThread();
        }
    }
    JNIEnv *get() const { return env_; }
private:
    JNIEnv *env_ = nullptr;
    bool attached_ = false;
};

class Environment {
public:
    Environment() {
        // Codec worker threads stay attached for their lifetime instead of attaching for every
        // 64 KiB buffer. Java-owned threads are never detached by this library.
        static thread_local ThreadEnvironment thread;
        env_ = thread.get();
        if (env_) frame_ = env_->PushLocalFrame(32) == JNI_OK;
    }
    ~Environment() {
        if (frame_) env_->PopLocalFrame(nullptr);
    }
    JNIEnv *get() const { return env_; }
private:
    JNIEnv *env_ = nullptr;
    bool frame_ = false;
};

class Context {
public:
    explicit Context(JNIEnv *env, jstring password) : passwordDefined(password != nullptr) {
        begin(env);
        if (password) {
            copyString(env, password, password_);
        }
    }
    ~Context() {
        Environment environment;
        JNIEnv *env = environment.get();
        if (env) {
            if (thread_) env->DeleteGlobalRef(thread_);
            if (failure_) env->DeleteGlobalRef(failure_);
        }
        password_.Wipe_and_Empty();
    }

    void begin(JNIEnv *env) {
        if (thread_) env->DeleteGlobalRef(thread_);
        if (failure_) env->DeleteGlobalRef(failure_);
        thread_ = nullptr;
        failure_ = nullptr;
        failed_.store(false);
        passwordRequested.store(false);
        jobject thread = env->CallStaticObjectMethod(g.thread, g.currentThread);
        if (!capture(env)) {
            thread_ = env->NewGlobalRef(thread);
            env->DeleteLocalRef(thread);
            capture(env);
        }
    }

    bool capture(JNIEnv *env) {
        if (!env) {
            failed_.store(true);
            return true;
        }
        jthrowable exception = env->ExceptionOccurred();
        if (!exception) {
            return failed_.load();
        }
        env->ExceptionClear();
        remember(env, exception);
        env->DeleteLocalRef(exception);
        return true;
    }

    void remember(JNIEnv *env, jthrowable exception) {
        std::lock_guard<std::mutex> lock(failureMutex_);
        if (!failure_ && exception) {
            failure_ = static_cast<jthrowable>(env->NewGlobalRef(exception));
            // A failure to allocate the global ref must not escape on an attached worker.
            if (env->ExceptionCheck()) env->ExceptionClear();
        }
        failed_.store(true);
    }

    HRESULT check(JNIEnv *env) {
        if (!env || capture(env)) return E_ABORT;
        if (env->CallBooleanMethod(thread_, g.isInterrupted)) {
            env->ThrowNew(g.interruptedException, "7z operation interrupted");
        }
        return capture(env) ? E_ABORT : S_OK;
    }

    HRESULT check() {
        Environment environment;
        return check(environment.get());
    }

    bool failed() const { return failed_.load(); }

    void throwSaved(JNIEnv *env) {
        std::lock_guard<std::mutex> lock(failureMutex_);
        if (failure_) {
            env->Throw(failure_);
        } else if (failed()) {
            env->ThrowNew(g.ioException, "7z Java callback failed");
        }
    }

    jthrowable error(JNIEnv *env, const char *message, bool password = false) const {
        jstring text = env->NewStringUTF(message);
        if (!text) return nullptr;
        jthrowable result = static_cast<jthrowable>(password
                ? env->NewObject(g.archiveException, g.archiveExceptionConstructor, -1, text)
                : env->NewObject(g.ioException, g.ioExceptionConstructor, text));
        env->DeleteLocalRef(text);
        return result;
    }

    jthrowable passwordError(JNIEnv *env) const {
        return error(env, passwordDefined ? "Incorrect passphrase"
                : "Passphrase required for this entry", true);
    }

    HRESULT fail(JNIEnv *env, const char *message) {
        env->ThrowNew(g.ioException, message);
        capture(env);
        return E_ABORT;
    }

    HRESULT password(BSTR *password) {
        *password = nullptr;
        passwordRequested.store(true);
        Environment environment;
        JNIEnv *env = environment.get();
        RINOK(check(env))
        if (!passwordDefined) {
            jthrowable exception = passwordError(env);
            if (exception) remember(env, exception);
            else capture(env);
            return E_ABORT;
        }
        *password = SysAllocStringLen(password_.Ptr(), password_.Len());
        return *password ? S_OK : E_OUTOFMEMORY;
    }

    bool copyString(JNIEnv *env, jstring source, UString &destination) {
        if (!source) {
            fail(env, "A 7z entry has no name");
            return false;
        }
        jsize length = env->GetStringLength(source);
        std::vector<jchar> chars(static_cast<size_t>(length));
        if (length) env->GetStringRegion(source, 0, length, chars.data());
        if (capture(env)) return false;
        wchar_t *buffer = destination.GetBuf(static_cast<unsigned>(length));
        for (jsize i = 0; i < length; ++i) {
            if (chars[i] == 0) {
                destination.ReleaseBuf_SetLen(0);
                fail(env, "7z names and passwords must not contain NUL");
                return false;
            }
            // Official 7z COM APIs use UTF-16 code units even when wchar_t is 32 bits.
            buffer[i] = chars[i];
        }
        destination.ReleaseBuf_SetLen(static_cast<unsigned>(length));
        std::fill(chars.begin(), chars.end(), 0);
        return true;
    }

    const bool passwordDefined;
    std::atomic<bool> passwordRequested{false};
private:
    UString_Wipe password_;
    jobject thread_ = nullptr;
    jthrowable failure_ = nullptr;
    std::atomic<bool> failed_{false};
    std::mutex failureMutex_;
};

void reportError(JNIEnv *env, Context &context, HRESULT result, bool passwordFailure = false,
        bool memoryExceeded = false) {
    if (context.failed()) {
        context.throwSaved(env);
        return;
    }
    if (result == S_OK) return;
    if (memoryExceeded || result == E_OUTOFMEMORY) {
        env->ThrowNew(g.ioException, "The 7z archive exceeds the available native memory limit");
        return;
    }
    if (passwordFailure && (result == S_FALSE || result == E_FAIL)) {
        jthrowable exception = context.passwordError(env);
        if (exception) env->Throw(exception);
        return;
    }
    if (result == E_ABORT) {
        env->ThrowNew(g.interruptedException, "7z operation interrupted");
        return;
    }
    const char *message = result == E_NOTIMPL ? "Unsupported 7z compression method"
            : result == S_FALSE ? "Invalid or damaged 7z archive" : "7z archive operation failed";
    char buffer[128];
    std::snprintf(buffer, sizeof(buffer), "%s (0x%08x)", message, static_cast<unsigned>(result));
    env->ThrowNew(g.ioException, buffer);
}

class ChannelStream final : public IInStream, public IOutStream, public CMyUnknownImp {
    Z7_COM_UNKNOWN_IMP_4(ISequentialInStream, IInStream, ISequentialOutStream, IOutStream)
public:
    ChannelStream(JNIEnv *env, Context &context, jobject channel) : context_(context) {
        channel_ = env->NewGlobalRef(channel);
        context_.capture(env);
    }
    ~ChannelStream() {
        Environment environment;
        if (environment.get() && channel_) environment.get()->DeleteGlobalRef(channel_);
    }
    MF7Z_COM_METHOD(Read(void *data, UInt32 size, UInt32 *processed)) {
        if (processed) *processed = 0;
        if (size == 0) return S_OK;
        Environment environment;
        JNIEnv *env = environment.get();
        RINOK(context_.check(env))
        const UInt32 amount = std::min<UInt32>(size, static_cast<UInt32>(kBufferSize));
        jobject buffer = env->NewDirectByteBuffer(data, amount);
        if (context_.capture(env)) return E_ABORT;
        jint count = env->CallIntMethod(channel_, g.channelRead, buffer);
        if (context_.capture(env)) return E_ABORT;
        if (count < -1 || count > static_cast<jint>(amount)) {
            return context_.fail(env, "The archive channel returned an invalid read count");
        }
        if (processed && count > 0) *processed = static_cast<UInt32>(count);
        return S_OK;
    }
    MF7Z_COM_METHOD(Write(const void *data, UInt32 size, UInt32 *processed)) {
        if (processed) *processed = 0;
        if (size == 0) return S_OK;
        Environment environment;
        JNIEnv *env = environment.get();
        RINOK(context_.check(env))
        const UInt32 amount = std::min<UInt32>(size, static_cast<UInt32>(kBufferSize));
        jobject buffer = env->NewDirectByteBuffer(const_cast<void *>(data), amount);
        if (context_.capture(env)) return E_ABORT;
        jint count = env->CallIntMethod(channel_, g.channelWrite, buffer);
        if (context_.capture(env)) return E_ABORT;
        if (count <= 0 || count > static_cast<jint>(amount)) {
            return context_.fail(env, "The archive channel returned an invalid write count");
        }
        if (processed) *processed = static_cast<UInt32>(count);
        return S_OK;
    }
    MF7Z_COM_METHOD(Seek(Int64 offset, UInt32 origin, UInt64 *position)) {
        Environment environment;
        JNIEnv *env = environment.get();
        RINOK(context_.check(env))
        jlong result = env->CallLongMethod(channel_, g.channelSeek,
                static_cast<jlong>(offset), static_cast<jint>(origin));
        if (context_.capture(env)) return E_ABORT;
        if (position) *position = static_cast<UInt64>(result);
        return S_OK;
    }
    MF7Z_COM_METHOD(SetSize(UInt64 size)) {
        Environment environment;
        JNIEnv *env = environment.get();
        RINOK(context_.check(env))
        if (size > static_cast<UInt64>(INT64_MAX)) {
            return context_.fail(env, "The 7z archive is too large for its channel");
        }
        env->CallVoidMethod(channel_, g.channelSetSize, static_cast<jlong>(size));
        return context_.capture(env) ? E_ABORT : S_OK;
    }
private:
    Context &context_;
    jobject channel_ = nullptr;
};

class InputStream final : public ISequentialInStream, public CMyUnknownImp {
    Z7_COM_UNKNOWN_IMP_1(ISequentialInStream)
public:
    InputStream(JNIEnv *env, Context &context, jobject input) : context_(context) {
        input_ = env->NewGlobalRef(input);
        if (context_.capture(env)) {
            if (!input_) {
                env->CallVoidMethod(input, g.inputClose);
                context_.capture(env);
            }
            return;
        }
        jbyteArray buffer = env->NewByteArray(kBufferSize);
        if (!context_.capture(env)) buffer_ = static_cast<jbyteArray>(env->NewGlobalRef(buffer));
        context_.capture(env);
    }
    ~InputStream() {
        Environment environment;
        JNIEnv *env = environment.get();
        if (!env) return;
        if (input_) {
            // Always close the source, including on cancellation and codec errors.
            env->CallVoidMethod(input_, g.inputClose);
            context_.capture(env);
            env->DeleteGlobalRef(input_);
        }
        if (buffer_) env->DeleteGlobalRef(buffer_);
    }
    MF7Z_COM_METHOD(Read(void *data, UInt32 size, UInt32 *processed)) {
        if (processed) *processed = 0;
        if (size == 0) return S_OK;
        Environment environment;
        JNIEnv *env = environment.get();
        RINOK(context_.check(env))
        jint amount = static_cast<jint>(std::min<size_t>(size, kBufferSize));
        jint count = env->CallIntMethod(input_, g.inputRead, buffer_, 0, amount);
        if (context_.capture(env)) return E_ABORT;
        if (count == 0 || count < -1 || count > amount) {
            return context_.fail(env, "The 7z source returned an invalid read count");
        }
        if (count > 0) {
            env->GetByteArrayRegion(buffer_, 0, count, static_cast<jbyte *>(data));
            if (context_.capture(env)) return E_ABORT;
            if (processed) *processed = static_cast<UInt32>(count);
        }
        return S_OK;
    }
private:
    Context &context_;
    jobject input_ = nullptr;
    jbyteArray buffer_ = nullptr;
};

class OutputStream final : public ISequentialOutStream, public CMyUnknownImp {
    Z7_COM_UNKNOWN_IMP_1(ISequentialOutStream)
public:
    OutputStream(JNIEnv *env, Context &context, jobject output) : context_(context) {
        output_ = env->NewGlobalRef(output);
        if (context_.capture(env)) return;
        jbyteArray buffer = env->NewByteArray(kBufferSize);
        if (!context_.capture(env)) buffer_ = static_cast<jbyteArray>(env->NewGlobalRef(buffer));
        context_.capture(env);
    }
    ~OutputStream() {
        Environment environment;
        if (environment.get()) {
            if (output_) environment.get()->DeleteGlobalRef(output_);
            if (buffer_) environment.get()->DeleteGlobalRef(buffer_);
        }
    }
    MF7Z_COM_METHOD(Write(const void *data, UInt32 size, UInt32 *processed)) {
        if (processed) *processed = 0;
        if (size == 0) return S_OK;
        Environment environment;
        JNIEnv *env = environment.get();
        RINOK(context_.check(env))
        jint count = static_cast<jint>(std::min<size_t>(size, kBufferSize));
        env->SetByteArrayRegion(buffer_, 0, count, static_cast<const jbyte *>(data));
        if (context_.capture(env)) return E_ABORT;
        env->CallVoidMethod(output_, g.outputWrite, buffer_, 0, count);
        if (context_.capture(env)) return E_ABORT;
        if (processed) *processed = static_cast<UInt32>(count);
        return S_OK;
    }
private:
    Context &context_;
    jobject output_ = nullptr;
    jbyteArray buffer_ = nullptr;
};

class OpenCallback final : public IArchiveOpenCallback, public ICryptoGetTextPassword,
        public CMyUnknownImp {
    Z7_COM_UNKNOWN_IMP_2(IArchiveOpenCallback, ICryptoGetTextPassword)
public:
    explicit OpenCallback(Context &context) : context_(context) {}
    MF7Z_COM_METHOD(SetTotal(const UInt64 *, const UInt64 *)) { return context_.check(); }
    MF7Z_COM_METHOD(SetCompleted(const UInt64 *, const UInt64 *)) { return context_.check(); }
    MF7Z_COM_METHOD(CryptoGetTextPassword(BSTR *password)) { return context_.password(password); }
private:
    Context &context_;
};

bool propertyBool(IInArchive *archive, UInt32 index, PROPID property) {
    CPropVariant value;
    return archive->GetProperty(index, property, &value) == S_OK
            && value.vt == VT_BOOL && value.boolVal != VARIANT_FALSE;
}

class ExtractCallback final : public IArchiveExtractCallback, public ICryptoGetTextPassword,
        public IArchiveExtractCallbackMessage2, public CMyUnknownImp {
    Z7_COM_UNKNOWN_IMP_4(IProgress, IArchiveExtractCallback, ICryptoGetTextPassword,
            IArchiveExtractCallbackMessage2)
public:
    ExtractCallback(JNIEnv *env, Context &context, IInArchive *archive,
            const std::vector<UInt32> &indices, jobject callback, bool testMode)
            : context_(context), archive_(archive), indices_(indices), testMode_(testMode) {
        callback_ = env->NewGlobalRef(callback);
        context_.capture(env);
    }
    ~ExtractCallback() {
        Environment environment;
        if (environment.get() && callback_) environment.get()->DeleteGlobalRef(callback_);
    }
    MF7Z_COM_METHOD(SetTotal(UInt64)) { return context_.check(); }
    MF7Z_COM_METHOD(SetCompleted(const UInt64 *)) { return context_.check(); }
    MF7Z_COM_METHOD(GetStream(UInt32 index, ISequentialOutStream **output, Int32 askMode)) {
        *output = nullptr;
        activeIndex_ = index;
        active_ = std::binary_search(indices_.begin(), indices_.end(), index)
                && (askMode == NArchive::NExtract::NAskMode::kExtract
                    || askMode == NArchive::NExtract::NAskMode::kTest);
        Environment environment;
        JNIEnv *env = environment.get();
        RINOK(context_.check(env))
        // If decoding fails before producing bytes (for example with a wrong password), the
        // official handler switches remaining selected entries to kTest while reporting their
        // errors. They still need onResult, but must not open an output stream.
        if (!active_ || testMode_ || askMode != NArchive::NExtract::NAskMode::kExtract) return S_OK;
        jobject stream = env->CallObjectMethod(callback_, g.extractOpen, static_cast<jint>(index));
        if (context_.capture(env)) return E_ABORT;
        if (!stream) return S_OK;
        CMyComPtr<ISequentialOutStream> wrapper =
                new (std::nothrow) OutputStream(env, context_, stream);
        if (!wrapper) return E_OUTOFMEMORY;
        if (context_.failed()) return E_ABORT;
        *output = wrapper.Detach();
        return S_OK;
    }
    MF7Z_COM_METHOD(PrepareOperation(Int32)) { return context_.check(); }
    MF7Z_COM_METHOD(SetOperationResult(Int32 result)) {
        Environment environment;
        JNIEnv *env = environment.get();
        // The pipe owner also receives failures; onResult is the completion boundary for CRC.
        if (!env || context_.capture(env)) return E_ABORT;
        if (!active_) return context_.check(env);
        jthrowable failure = result == NArchive::NExtract::NOperationResult::kOK
                ? nullptr : operationError(env, activeIndex_, result);
        if (context_.capture(env)) return E_ABORT;
        env->CallVoidMethod(callback_, g.extractResult, static_cast<jint>(activeIndex_), failure);
        active_ = false;
        if (context_.capture(env)) return E_ABORT;
        if (failure) {
            context_.remember(env, failure);
            return E_ABORT;
        }
        return context_.check(env);
    }
    MF7Z_COM_METHOD(CryptoGetTextPassword(BSTR *password)) { return context_.password(password); }
    MF7Z_COM_METHOD(ReportExtractResult(UInt32 indexType, UInt32 index, Int32 result)) {
        if (result == NArchive::NExtract::NOperationResult::kOK) return context_.check();
        Environment environment;
        JNIEnv *env = environment.get();
        if (!env || context_.capture(env)) return E_ABORT;
        const UInt32 item = indexType == NArchive::NEventIndexType::kInArcIndex
                ? index : activeIndex_;
        jthrowable failure = operationError(env, item, result);
        if (!context_.capture(env)) context_.remember(env, failure);
        return E_ABORT;
    }
private:
    jthrowable operationError(JNIEnv *env, UInt32 index, Int32 result) {
        using namespace NArchive::NExtract::NOperationResult;
        const bool encrypted = propertyBool(archive_, index, kpidEncrypted);
        if (result == kWrongPassword || (encrypted
                && (result == kCRCError || result == kDataError))) {
            return context_.passwordError(env);
        }
        const char *message;
        switch (result) {
            case kUnsupportedMethod: message = "Unsupported 7z compression method"; break;
            case kCRCError: message = "7z entry CRC verification failed"; break;
            case kUnexpectedEnd: message = "Unexpected end of 7z data"; break;
            case kUnavailable: message = "The 7z entry data is unavailable"; break;
            case kDataAfterEnd: message = "Unexpected data after the 7z stream"; break;
            case kHeadersError: message = "Damaged 7z archive headers"; break;
            default: message = "Damaged 7z entry data"; break;
        }
        return context_.error(env, message);
    }
    Context &context_;
    IInArchive *archive_;
    const std::vector<UInt32> &indices_;
    const bool testMode_;
    jobject callback_ = nullptr;
    UInt32 activeIndex_ = 0;
    bool active_ = false;
};

struct CreationEntry {
    UString name;
    bool directory;
    bool hasAttributes;
    UInt32 attributes;
    UInt64 size;
    int64_t times[3];
};

class CreateCallback final : public IArchiveUpdateCallback, public ICryptoGetTextPassword2,
        public CMyUnknownImp {
    Z7_COM_UNKNOWN_IMP_3(IProgress, IArchiveUpdateCallback, ICryptoGetTextPassword2)
public:
    CreateCallback(JNIEnv *env, Context &context, const std::vector<CreationEntry> &entries,
            jobject callback) : context_(context), entries_(entries) {
        callback_ = env->NewGlobalRef(callback);
        context_.capture(env);
    }
    ~CreateCallback() {
        Environment environment;
        if (environment.get() && callback_) environment.get()->DeleteGlobalRef(callback_);
    }
    MF7Z_COM_METHOD(SetTotal(UInt64)) { return context_.check(); }
    MF7Z_COM_METHOD(SetCompleted(const UInt64 *completed)) {
        Environment environment;
        JNIEnv *env = environment.get();
        RINOK(context_.check(env))
        if (completed) {
            env->CallVoidMethod(callback_, g.createProgress, static_cast<jlong>(*completed));
        }
        return context_.capture(env) ? E_ABORT : S_OK;
    }
    MF7Z_COM_METHOD(GetUpdateItemInfo(UInt32 index, Int32 *newData, Int32 *newProperties,
            UInt32 *archiveIndex)) {
        if (index >= entries_.size()) return E_INVALIDARG;
        *newData = 1;
        *newProperties = 1;
        *archiveIndex = static_cast<UInt32>(-1);
        return context_.check();
    }
    MF7Z_COM_METHOD(GetProperty(UInt32 index, PROPID property, PROPVARIANT *value)) try {
        if (index >= entries_.size()) return E_INVALIDARG;
        RINOK(context_.check())
        const CreationEntry &entry = entries_[index];
        CPropVariant result;
        switch (property) {
            case kpidPath: result = entry.name; break;
            case kpidIsDir: result = entry.directory; break;
            case kpidIsAnti: result = false; break;
            case kpidSize: result = entry.size; break;
            case kpidAttrib: if (entry.hasAttributes) result = entry.attributes; break;
            case kpidMTime:
            case kpidATime:
            case kpidCTime: {
                const int timeIndex = property == kpidMTime ? 0 : property == kpidATime ? 1 : 2;
                const int64_t millis = entry.times[timeIndex];
                if (millis != kMissingTime) {
                    if (millis < -kFileTimeEpochMillis
                            || millis > static_cast<int64_t>(UINT64_MAX / 10000)
                                    - kFileTimeEpochMillis) return E_INVALIDARG;
                    const UInt64 ticks = static_cast<UInt64>(millis + kFileTimeEpochMillis) * 10000;
                    FILETIME fileTime;
                    fileTime.dwLowDateTime = static_cast<DWORD>(ticks);
                    fileTime.dwHighDateTime = static_cast<DWORD>(ticks >> 32);
                    result = fileTime;
                }
                break;
            }
            default: break;
        }
        return result.Detach(value);
    } catch (...) {
        return E_OUTOFMEMORY;
    }
    MF7Z_COM_METHOD(GetStream(UInt32 index, ISequentialInStream **input)) {
        *input = nullptr;
        if (index >= entries_.size()) return E_INVALIDARG;
        activeIndex_ = index;
        Environment environment;
        JNIEnv *env = environment.get();
        RINOK(context_.check(env))
        if (entries_[index].directory) return S_OK;
        jobject source = env->CallObjectMethod(callback_, g.createOpen, static_cast<jint>(index));
        if (context_.capture(env)) return E_ABORT;
        if (!source) return context_.fail(env, "The 7z source callback returned no input stream");
        CMyComPtr<ISequentialInStream> wrapper =
                new (std::nothrow) InputStream(env, context_, source);
        if (!wrapper) {
            env->CallVoidMethod(source, g.inputClose);
            context_.capture(env);
            return E_OUTOFMEMORY;
        }
        if (context_.failed()) return E_ABORT;
        *input = wrapper.Detach();
        return S_OK;
    }
    MF7Z_COM_METHOD(SetOperationResult(Int32 result)) {
        Environment environment;
        JNIEnv *env = environment.get();
        RINOK(context_.check(env))
        if (activeIndex_ == static_cast<UInt32>(-1)) return S_OK;
        jthrowable failure = result == NArchive::NUpdate::NOperationResult::kOK ? nullptr
                : context_.error(env, "7z could not add the input entry");
        if (context_.capture(env)) return E_ABORT;
        env->CallVoidMethod(callback_, g.createResult, static_cast<jint>(activeIndex_), failure);
        activeIndex_ = static_cast<UInt32>(-1);
        if (context_.capture(env)) return E_ABORT;
        if (failure) {
            context_.remember(env, failure);
            return E_ABORT;
        }
        return S_OK;
    }
    MF7Z_COM_METHOD(CryptoGetTextPassword2(Int32 *defined, BSTR *password)) {
        *defined = context_.passwordDefined ? 1 : 0;
        *password = nullptr;
        return context_.passwordDefined ? context_.password(password) : context_.check();
    }
private:
    Context &context_;
    const std::vector<CreationEntry> &entries_;
    jobject callback_ = nullptr;
    UInt32 activeIndex_ = static_cast<UInt32>(-1);
};

struct Archive {
    Archive(JNIEnv *env, jobject channel, jstring password, uint64_t limit)
            : memory(limit), context(env, password) {
        MemoryBudget::Scope scope(memory);
        if (context.failed()) return;
        stream = new ChannelStream(env, context, channel);
        handler = new NArchive::N7z::CHandler;
    }
    // Destruction order keeps the context and budget alive until every stream and decoder closes.
    MemoryBudget memory;
    Context context;
    CMyComPtr<IInStream> stream;
    CMyComPtr<IInArchive> handler;
};

HRESULT configureReader(IInArchive *archive, uint64_t memoryLimit) {
    CMyComPtr<ISetProperties> properties;
    RINOK(archive->QueryInterface(IID_ISetProperties, reinterpret_cast<void **>(&properties)))
    const wchar_t *names[] = {L"mt", L"memuse"};
    CPropVariant values[2];
    values[0] = static_cast<UInt32>(2);
    values[1] = static_cast<UInt64>(memoryLimit);
    return properties->SetProperties(names, values, 2);
}

HRESULT configureWriter(IOutArchive *archive, jint level, jint dictionary, jint threads,
        bool encryptHeaders) {
    CMyComPtr<ISetProperties> properties;
    RINOK(archive->QueryInterface(IID_ISetProperties, reinterpret_cast<void **>(&properties)))
    const wchar_t *names[] = {L"x", L"0", L"0d", L"mt", L"he", L"memuse", L"f",
            L"s", L"tc", L"ta", L"tm"};
    CPropVariant values[11];
    values[0] = static_cast<UInt32>(level);
    values[1] = L"LZMA2";
    // The handler interprets a numeric dictionary value as log2(size), not bytes.
    wchar_t dictionaryValue[32];
    std::swprintf(dictionaryValue, 32, L"%ub", static_cast<unsigned>(dictionary));
    values[2] = dictionaryValue;
    values[3] = static_cast<UInt32>(threads);
    values[4] = encryptHeaders;
    values[5] = static_cast<UInt64>(kCreateMemoryLimit);
    // Avoid reopening remote inputs for automatic executable-filter analysis.
    values[6] = false;
    // Bound solid blocks while preserving the single-pass extraction benefit.
    values[7] = L"64m";
    values[8] = true;
    values[9] = true;
    values[10] = true;
    return properties->SetProperties(names, values, 11);
}

jstring javaString(JNIEnv *env, BSTR source) {
    const UINT length = source ? SysStringLen(source) : 0;
    std::vector<jchar> chars(length);
    for (UINT i = 0; i < length; ++i) chars[i] = static_cast<jchar>(source[i]);
    static const jchar empty = 0;
    return env->NewString(length ? chars.data() : &empty, static_cast<jsize>(length));
}

jobjectArray readEntries(JNIEnv *env, Archive &archive) {
    UInt32 count = 0;
    HRESULT result = archive.handler->GetNumberOfItems(&count);
    if (result != S_OK) {
        reportError(env, archive.context, result);
        return nullptr;
    }
    if (count > static_cast<UInt32>(INT_MAX)) {
        env->ThrowNew(g.ioException, "Too many entries in the 7z archive");
        return nullptr;
    }
    jobjectArray entries = env->NewObjectArray(static_cast<jsize>(count), g.entry, nullptr);
    if (!entries) return nullptr;
    for (UInt32 index = 0; index < count; ++index) {
        if (archive.context.check(env) != S_OK) {
            archive.context.throwSaved(env);
            return nullptr;
        }
        jobject entry = env->NewObject(g.entry, g.entryConstructor);
        if (!entry) return nullptr;
        env->SetIntField(entry, g.index, static_cast<jint>(index));
        CPropVariant value;
        result = archive.handler->GetProperty(index, kpidPath, &value);
        if (result != S_OK || value.vt != VT_BSTR) {
            if (result == S_OK) result = E_FAIL;
            reportError(env, archive.context, result);
            return nullptr;
        }
        jstring name = javaString(env, value.bstrVal);
        if (!name) return nullptr;
        env->SetObjectField(entry, g.name, name);
        env->DeleteLocalRef(name);
        env->SetBooleanField(entry, g.directory, propertyBool(archive.handler, index, kpidIsDir));
        env->SetBooleanField(entry, g.encrypted, propertyBool(archive.handler, index, kpidEncrypted));
        value.Clear();
        result = archive.handler->GetProperty(index, kpidSize, &value);
        if (result != S_OK) {
            reportError(env, archive.context, result);
            return nullptr;
        }
        const UInt64 size = value.vt == VT_UI8 ? value.uhVal.QuadPart : 0;
        if (size > static_cast<UInt64>(INT64_MAX)) {
            env->ThrowNew(g.ioException, "A 7z entry is too large");
            return nullptr;
        }
        env->SetLongField(entry, g.size, static_cast<jlong>(size));
        value.Clear();
        result = archive.handler->GetProperty(index, kpidAttrib, &value);
        if (result != S_OK) {
            reportError(env, archive.context, result);
            return nullptr;
        }
        if (value.vt == VT_UI4) {
            env->SetBooleanField(entry, g.hasAttributes, JNI_TRUE);
            env->SetIntField(entry, g.attributes, static_cast<jint>(value.ulVal));
        }
        const PROPID properties[] = {kpidMTime, kpidATime, kpidCTime};
        for (int i = 0; i < 3; ++i) {
            value.Clear();
            result = archive.handler->GetProperty(index, properties[i], &value);
            if (result != S_OK) {
                reportError(env, archive.context, result);
                return nullptr;
            }
            if (value.vt == VT_FILETIME) {
                UInt64 ticks = (static_cast<UInt64>(value.filetime.dwHighDateTime) << 32)
                        | value.filetime.dwLowDateTime;
                env->SetLongField(entry, g.times[i], static_cast<jlong>(ticks / 10000)
                        - kFileTimeEpochMillis);
            }
        }
        env->SetObjectArrayElement(entries, static_cast<jsize>(index), entry);
        env->DeleteLocalRef(entry);
        if (env->ExceptionCheck()) return nullptr;
    }
    return entries;
}

bool copyEntries(JNIEnv *env, Context &context, jobjectArray source,
        std::vector<CreationEntry> &entries) {
    const jsize count = env->GetArrayLength(source);
    entries.resize(static_cast<size_t>(count));
    for (jsize index = 0; index < count; ++index) {
        if (context.check(env) != S_OK) return false;
        jobject entry = env->GetObjectArrayElement(source, index);
        if (!entry) {
            context.fail(env, "A 7z entry is null");
            return false;
        }
        jstring name = static_cast<jstring>(env->GetObjectField(entry, g.name));
        CreationEntry &copy = entries[index];
        if (!context.copyString(env, name, copy.name)) return false;
        env->DeleteLocalRef(name);
        copy.directory = env->GetBooleanField(entry, g.directory);
        copy.hasAttributes = env->GetBooleanField(entry, g.hasAttributes);
        copy.attributes = static_cast<UInt32>(env->GetIntField(entry, g.attributes));
        const jlong size = env->GetLongField(entry, g.size);
        if (size < 0) {
            context.fail(env, "A 7z input has an invalid size");
            return false;
        }
        copy.size = copy.directory ? 0 : static_cast<UInt64>(size);
        for (int i = 0; i < 3; ++i) copy.times[i] = env->GetLongField(entry, g.times[i]);
        env->DeleteLocalRef(entry);
        if (context.capture(env)) return false;
    }
    return true;
}

jclass globalClass(JNIEnv *env, const char *name) {
    jclass local = env->FindClass(name);
    if (!local) return nullptr;
    jclass result = static_cast<jclass>(env->NewGlobalRef(local));
    env->DeleteLocalRef(local);
    return result;
}

void unexpected(JNIEnv *env) {
    if (!env->ExceptionCheck()) {
        env->ThrowNew(g.ioException, "7z native allocation or archive processing failed");
    }
}

}  // namespace

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *) {
    gVm = vm;
    JNIEnv *env = nullptr;
    if (vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) != JNI_OK) return JNI_ERR;
    char name[160];
    std::snprintf(name, sizeof(name), "%s$Entry", kClassPrefix);
    g.entry = globalClass(env, name);
    g.ioException = globalClass(env, "java/io/IOException");
    g.interruptedException = globalClass(env, "java/io/InterruptedIOException");
    g.archiveException = globalClass(env, "me/zhanghai/android/libarchive/ArchiveException");
    g.thread = globalClass(env, "java/lang/Thread");
    if (env->ExceptionCheck()) return JNI_ERR;
    g.entryConstructor = env->GetMethodID(g.entry, "<init>", "()V");
    g.ioExceptionConstructor = env->GetMethodID(g.ioException, "<init>", "(Ljava/lang/String;)V");
    g.archiveExceptionConstructor = env->GetMethodID(g.archiveException, "<init>",
            "(ILjava/lang/String;)V");
    g.currentThread = env->GetStaticMethodID(g.thread, "currentThread", "()Ljava/lang/Thread;");
    g.isInterrupted = env->GetMethodID(g.thread, "isInterrupted", "()Z");
    g.index = env->GetFieldID(g.entry, "index", "I");
    g.name = env->GetFieldID(g.entry, "name", "Ljava/lang/String;");
    g.directory = env->GetFieldID(g.entry, "directory", "Z");
    g.size = env->GetFieldID(g.entry, "size", "J");
    g.encrypted = env->GetFieldID(g.entry, "encrypted", "Z");
    g.hasAttributes = env->GetFieldID(g.entry, "hasAttributes", "Z");
    g.attributes = env->GetFieldID(g.entry, "attributes", "I");
    g.times[0] = env->GetFieldID(g.entry, "lastModifiedTime", "J");
    g.times[1] = env->GetFieldID(g.entry, "lastAccessTime", "J");
    g.times[2] = env->GetFieldID(g.entry, "creationTime", "J");
    std::snprintf(name, sizeof(name), "%s$Channel", kClassPrefix);
    jclass channel = env->FindClass(name);
    if (!channel) return JNI_ERR;
    g.channelRead = env->GetMethodID(channel, "read", "(Ljava/nio/ByteBuffer;)I");
    g.channelWrite = env->GetMethodID(channel, "write", "(Ljava/nio/ByteBuffer;)I");
    g.channelSeek = env->GetMethodID(channel, "seek", "(JI)J");
    g.channelSetSize = env->GetMethodID(channel, "setSize", "(J)V");
    env->DeleteLocalRef(channel);
    jclass input = env->FindClass("java/io/InputStream");
    if (!input) return JNI_ERR;
    g.inputRead = env->GetMethodID(input, "read", "([BII)I");
    g.inputClose = env->GetMethodID(input, "close", "()V");
    env->DeleteLocalRef(input);
    jclass output = env->FindClass("java/io/OutputStream");
    if (!output) return JNI_ERR;
    g.outputWrite = env->GetMethodID(output, "write", "([BII)V");
    env->DeleteLocalRef(output);
    std::snprintf(name, sizeof(name), "%s$ExtractCallback", kClassPrefix);
    jclass extraction = env->FindClass(name);
    if (!extraction) return JNI_ERR;
    g.extractOpen = env->GetMethodID(extraction, "openOutput", "(I)Ljava/io/OutputStream;");
    g.extractResult = env->GetMethodID(extraction, "onResult", "(ILjava/io/IOException;)V");
    env->DeleteLocalRef(extraction);
    std::snprintf(name, sizeof(name), "%s$CreateCallback", kClassPrefix);
    jclass creation = env->FindClass(name);
    if (!creation) return JNI_ERR;
    g.createOpen = env->GetMethodID(creation, "openInput", "(I)Ljava/io/InputStream;");
    g.createProgress = env->GetMethodID(creation, "onProgress", "(J)V");
    g.createResult = env->GetMethodID(creation, "onResult", "(ILjava/io/IOException;)V");
    env->DeleteLocalRef(creation);
    return env->ExceptionCheck() ? JNI_ERR : JNI_VERSION_1_6;
}

#define JNI_METHOD(name) Java_me_zhanghai_android_files_provider_archive_archiver_NativeSevenZip_##name

extern "C" JNIEXPORT jlong JNICALL JNI_METHOD(nativeOpen)(JNIEnv *env, jclass,
        jobject channel, jstring password, jlong memoryLimit) {
    try {
        std::unique_ptr<Archive> archive(new Archive(env, channel, password,
                static_cast<uint64_t>(memoryLimit)));
        MemoryBudget::Scope scope(archive->memory);
        if (archive->context.failed()) {
            archive->context.throwSaved(env);
            return 0;
        }
        HRESULT result = configureReader(archive->handler, static_cast<uint64_t>(memoryLimit));
        CMyComPtr<IArchiveOpenCallback> callback = new OpenCallback(archive->context);
        if (result == S_OK) result = archive->stream->Seek(0, STREAM_SEEK_SET, nullptr);
        const UInt64 searchLimit = 0;
        if (result == S_OK) result = archive->handler->Open(archive->stream, &searchLimit, callback);
        if (result != S_OK || archive->context.failed()) {
            reportError(env, archive->context, result, archive->context.passwordRequested.load(),
                    archive->memory.exceeded());
            return 0;
        }
        return static_cast<jlong>(reinterpret_cast<intptr_t>(archive.release()));
    } catch (...) {
        unexpected(env);
        return 0;
    }
}

extern "C" JNIEXPORT jobjectArray JNICALL JNI_METHOD(nativeReadEntries)(JNIEnv *env, jclass,
        jlong handle) {
    Archive &archive = *reinterpret_cast<Archive *>(static_cast<intptr_t>(handle));
    try {
        archive.context.begin(env);
        archive.memory.resetFailure();
        MemoryBudget::Scope scope(archive.memory);
        return readEntries(env, archive);
    } catch (...) {
        if (archive.context.failed()) archive.context.throwSaved(env);
        else unexpected(env);
        return nullptr;
    }
}

extern "C" JNIEXPORT void JNICALL JNI_METHOD(nativeExtract)(JNIEnv *env, jclass,
        jlong handle, jintArray requested, jobject callback, jboolean testMode) {
    Archive &archive = *reinterpret_cast<Archive *>(static_cast<intptr_t>(handle));
    try {
        archive.context.begin(env);
        archive.memory.resetFailure();
        MemoryBudget::Scope scope(archive.memory);
        const jsize count = env->GetArrayLength(requested);
        if (count == 0) return;
        std::vector<jint> javaIndices(static_cast<size_t>(count));
        env->GetIntArrayRegion(requested, 0, count, javaIndices.data());
        if (archive.context.capture(env)) {
            archive.context.throwSaved(env);
            return;
        }
        UInt32 entryCount;
        HRESULT result = archive.handler->GetNumberOfItems(&entryCount);
        std::vector<UInt32> indices;
        indices.reserve(static_cast<size_t>(count));
        for (jint index : javaIndices) {
            if (index < 0 || static_cast<UInt32>(index) >= entryCount
                    || (!indices.empty() && static_cast<UInt32>(index) <= indices.back())) {
                env->ThrowNew(g.ioException, "Invalid 7z extraction indices");
                return;
            }
            indices.push_back(static_cast<UInt32>(index));
        }
        CMyComPtr<IArchiveExtractCallback> extraction = new ExtractCallback(env, archive.context,
                archive.handler, indices, callback, testMode);
        if (result == S_OK && !archive.context.failed()) {
            result = archive.handler->Extract(indices.data(), static_cast<UInt32>(indices.size()),
                    testMode ? 1 : 0, extraction);
        }
        extraction.Release();
        reportError(env, archive.context, result, false, archive.memory.exceeded());
    } catch (...) {
        if (archive.context.failed()) archive.context.throwSaved(env);
        else unexpected(env);
    }
}

extern "C" JNIEXPORT void JNICALL JNI_METHOD(nativeClose)(JNIEnv *env, jclass, jlong handle) {
    try {
        std::unique_ptr<Archive> archive(reinterpret_cast<Archive *>(static_cast<intptr_t>(handle)));
        // Closing does not consult a possibly interrupted thread, so resources are always released.
        HRESULT result = archive->handler->Close();
        if (result != S_OK) reportError(env, archive->context, result);
    } catch (...) {
        unexpected(env);
    }
}

extern "C" JNIEXPORT void JNICALL JNI_METHOD(nativeCreate)(JNIEnv *env, jclass, jobject channel,
        jobjectArray inputEntries, jobject callback, jstring password, jboolean encryptHeaders,
        jint level, jint dictionary, jint threads) {
    try {
        MemoryBudget memory(kCreateMemoryLimit);
        MemoryBudget::Scope scope(memory);
        Context context(env, password);
        std::vector<CreationEntry> entries;
        if (!copyEntries(env, context, inputEntries, entries)) {
            context.throwSaved(env);
            return;
        }
        CMyComPtr<IOutArchive> archive = new NArchive::N7z::CHandler;
        CMyComPtr<IOutStream> stream = new ChannelStream(env, context, channel);
        CMyComPtr<IArchiveUpdateCallback> creation = new CreateCallback(env, context, entries, callback);
        HRESULT result = configureWriter(archive, level, dictionary, threads, encryptHeaders);
        if (result == S_OK) result = stream->Seek(0, STREAM_SEEK_SET, nullptr);
        if (result == S_OK) result = stream->SetSize(0);
        if (result == S_OK && !context.failed()) {
            result = archive->UpdateItems(stream, static_cast<UInt32>(entries.size()), creation);
        }
        // Destroy all source streams before examining the saved exception, including close errors.
        archive.Release();
        creation.Release();
        stream.Release();
        reportError(env, context, result, false, memory.exceeded());
    } catch (...) {
        unexpected(env);
    }
}
