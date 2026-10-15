#ifndef CPPTOJAVAARCHIVEOPENCALLBACK_H_
#define CPPTOJAVAARCHIVEOPENCALLBACK_H_

#include "CPPToJavaAbstract.h"

#include "JavaStatInfos/JavaPackageSevenZip.h"

#include "7zip/IDecl.h"
#include "Common/MyCom.h"
#include "7zip/Archive/IArchive.h"


class CPPToJavaArchiveOpenCallback:
    public CPPToJavaAbstract,
    public IArchiveOpenCallback, // virtual 
    public CMyUnknownImp
{
    jni::IArchiveOpenCallback * _iArchiveOpenCallback;

public:

    CPPToJavaArchiveOpenCallback(JBindingSession & jbindingSession, JNIEnv * initEnv, jobject archiveOpenCallback) :
        CPPToJavaAbstract(jbindingSession, initEnv, archiveOpenCallback),
                _iArchiveOpenCallback(jni::IArchiveOpenCallback::_getInstanceFromObject(initEnv, archiveOpenCallback))
    {
        TRACE_OBJECT_CREATION("CPPToJavaArchiveOpenCallback")
    }

    Z7_IFACE_COM7_IMP_NONFINAL(IArchiveOpenCallback)

private:
    Z7_COM_UNKNOWN_IMP_1(IArchiveOpenCallback)
};


#endif /*CPPTOJAVAARCHIVEOPENCALLBACK_H_*/
