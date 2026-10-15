#ifndef CPPTOJAVAARCHIVEUPDATECALLBACK_H_
#define CPPTOJAVAARCHIVEUPDATECALLBACK_H_

#include "CPPToJavaAbstract.h"
#include "CPPToJavaProgress.h"
#include "CodecTools.h"
#include "Common/MyCom.h"

#include "7zip/IPassword.h"

class CPPToJavaArchiveUpdateCallback :
    public CPPToJavaAbstract,
    public virtual IArchiveUpdateCallback,
	public virtual ICryptoGetTextPassword2,
	public virtual ICryptoGetTextPassword,
    public virtual CMyUnknownImp {

private:
    CPPToJavaProgress _progress;
    jni::IOutCreateCallback * _iOutCreateCallback;
    jobject _outItem;
    int _outItemLastIndex;
    int _archiveFormatIndex;
    jobject _outArchive;
    jclass _inStreamClass;
    bool _isInArchiveAttached;
    bool _isICryptoGetTextPasswordImplemented;
    jni::ICryptoGetTextPassword * _cryptoGetTextPassword;
    bool _initialized;

public:
    CPPToJavaArchiveUpdateCallback(JBindingSession & jbindingSession, JNIEnv * initEnv,
                                   jobject archiveUpdateCallback, bool isInArchiveAttached,
                                   int archiveFormatIndex, jobject outArchive) :
            CPPToJavaAbstract(jbindingSession, initEnv, archiveUpdateCallback),
            _progress(jbindingSession, initEnv, archiveUpdateCallback),
            _iOutCreateCallback(jni::IOutCreateCallback::_getInstanceFromObject(initEnv, archiveUpdateCallback)),
		    _outItem(NULL),
		    _outItemLastIndex(-1),
		    _archiveFormatIndex(archiveFormatIndex),
		    _outArchive(NULL),
            _inStreamClass(NULL),
		    _isInArchiveAttached(isInArchiveAttached),
            _isICryptoGetTextPasswordImplemented(false),
            _cryptoGetTextPassword(NULL),
            _initialized(false)
    {
        TRACE_OBJECT_CREATION("CPPToJavaArchiveOpenCallback")

        if (initEnv->ExceptionCheck()) return;
        // LZMA2's MtCoder workers can call CFolderInStream::Read -> GetStream. A JNI
        // argument is local to the calling Java thread and cannot be retained for them.
        _outArchive = initEnv->NewGlobalRef(outArchive);
        if (!_outArchive || initEnv->ExceptionCheck()) return;

        // Resolve the application interface while the Java caller's class loader is active.
        // FindClass from a native-created Android thread would use the boot class loader.
        jclass inStreamClass = initEnv->FindClass(INSTREAM_CLASS);
        if (!inStreamClass) return;
        _inStreamClass = static_cast<jclass>(initEnv->NewGlobalRef(inStreamClass));
        initEnv->DeleteLocalRef(inStreamClass);
        if (!_inStreamClass || initEnv->ExceptionCheck()) return;

        _isICryptoGetTextPasswordImplemented = jni::ICryptoGetTextPassword::_isInstance(initEnv, _javaImplementation);
        if (initEnv->ExceptionCheck()) return;
        _initialized = true;
    }

    virtual ~CPPToJavaArchiveUpdateCallback() {
        JNIEnvInstance jniEnvInstance(_jbindingSession);
        freeOutItem(jniEnvInstance);
        if (_inStreamClass) jniEnvInstance->DeleteGlobalRef(_inStreamClass);
        if (_outArchive) jniEnvInstance->DeleteGlobalRef(_outArchive);
    }

    bool isInitialized() const { return _initialized; }

    // STDMETHOD(GetUpdateItemInfo)(UInt32 index, Int32 *newData, /*1 - new data, 0 - old data */
    // Int32 *newProperties, /* 1 - new properties, 0 - old properties */
    // UInt32 *indexInArchive /* -1 if there is no in archive, or if doesn't matter */
    // );

    // STDMETHOD(GetProperty)(UInt32 index, PROPID propID, PROPVARIANT *value);
    // STDMETHOD(GetStream)(UInt32 index, ISequentialInStream **inStream);
    // STDMETHOD(SetOperationResult)(Int32 operationResult);
    // STDMETHOD(CryptoGetTextPassword)(BSTR *password);
    // STDMETHOD(CryptoGetTextPassword2)(Int32 *passwordIsDefined, BSTR *password);

    // STDMETHOD(SetTotal)(UInt64 total) {
    //     TRACE_OBJECT_CALL("SetTotal");
    //     return CPPToJavaProgress::SetTotal(total);
    // }
    //
    // STDMETHOD(SetCompleted)(const UInt64 *completeValue) {
    //     TRACE_OBJECT_CALL("SetCompleted");
    //     return CPPToJavaProgress::SetCompleted(completeValue);
    // }

    // STDMETHOD(QueryInterface)(REFGUID refguid, void ** p) throw() {
    //     TRACE_OBJECT_CALL("QueryInterface");
    //
    //     if ((refguid == IID_ICryptoGetTextPassword) || (refguid == IID_ICryptoGetTextPassword2))
    //     {
    //         if (_isICryptoGetTextPasswordImplemented)
    //         {
    //         	if (refguid == IID_ICryptoGetTextPassword)
    //                 *p = (void *)(ICryptoGetTextPassword *)this;
    //         	else if (refguid == IID_ICryptoGetTextPassword2)
    //                 *p = (void *)(ICryptoGetTextPassword2 *)this;
    //         	else
    //                 return E_NOINTERFACE;
    //             AddRef();
    //             return S_OK;
    //         }
    //         return E_NOINTERFACE;
    //     }
    //
    //     return CPPToJavaProgress::QueryInterface(refguid, p);
    // }
    //
    // STDMETHOD_(ULONG, AddRef)() throw() {
    //     TRACE_OBJECT_CALL("AddRef");
    //     return CPPToJavaProgress::AddRef();
    // }
    //
    // STDMETHOD_(ULONG, Release)() {
    //     TRACE_OBJECT_CALL("Release");
    //     return CPPToJavaProgress::Release();
    // }
  STDMETHOD(SetTotal)(UInt64 total) noexcept Z7_override {
    TRACE_OBJECT_CALL("SetTotal");
    return _progress.SetTotal(total);
  }

  STDMETHOD(SetCompleted)(const UInt64 *completeValue) noexcept Z7_override {
    TRACE_OBJECT_CALL("SetCompleted");
    return _progress.SetCompleted(completeValue);
  }

    void freeOutItem(JNIEnvInstance & jniEnvInstance);


private:
    LONG getOrUpdateOutItem(JNIEnvInstance & jniEnvInstance, int index);

public:
    Z7_IFACE_COM7_IMP_NONFINAL(IArchiveUpdateCallback)
    Z7_IFACE_COM7_IMP_NONFINAL(ICryptoGetTextPassword)
    Z7_IFACE_COM7_IMP_NONFINAL(ICryptoGetTextPassword2)

private:
    Z7_COM_UNKNOWN_IMP_4(IArchiveUpdateCallback, ICryptoGetTextPassword2, ICryptoGetTextPassword, IProgress)
};

#endif /*CPPTOJAVAARCHIVEUPDATECALLBACK_H_*/
