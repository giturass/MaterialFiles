# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile

# Native methods
# BASS uses JNI lookups for its nested callback and channel-info classes.
-keep class com.un4seen.bass.** { *; }
-keep class me.zhanghai.android.files.viewer.media.AspectPlayerView { public <init>(...); }
# https://www.guardsquare.com/en/products/proguard/manual/examples#native
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}

# App
-keep class me.zhanghai.android.files.** implements androidx.appcompat.view.CollapsibleActionView { *; }
-keep class me.zhanghai.android.files.provider.common.ByteString { *; }
-keep class me.zhanghai.android.files.provider.linux.syscall.** { *; }
-keepnames class * extends java.lang.Exception
# For Class.getEnumConstants()
-keepclassmembers enum * {
    public static **[] values();
}
-keepnames class me.zhanghai.android.files.** implements android.os.Parcelable

# Apache FtpServer
-keepclassmembers class * implements org.apache.mina.core.service.IoProcessor {
    public <init>(java.util.concurrent.ExecutorService);
    public <init>(java.util.concurrent.Executor);
    public <init>();
}

# Bouncy Castle
-keep class org.bouncycastle.jcajce.provider.** { *; }
-keep class org.bouncycastle.jce.provider.** { *; }

# apksig parses and encodes ASN.1 through runtime annotations, public no-arg constructors,
# and annotated fields (including their generic types). R8 cannot see these reflective uses.
-keepattributes RuntimeVisibleAnnotations,AnnotationDefault,Signature
-keep,allowobfuscation @interface com.android.apksig.internal.asn1.Asn1Class { *; }
-keep,allowobfuscation @interface com.android.apksig.internal.asn1.Asn1Field { *; }
-keep,allowobfuscation @com.android.apksig.internal.asn1.Asn1Class class com.android.apksig.** {
    public <init>();
    @com.android.apksig.internal.asn1.Asn1Field <fields>;
}

# SMBJ
-dontwarn javax.el.**
-dontwarn org.ietf.jgss.**
-dontwarn sun.security.x509.X509Key

# SMBJ-RPC
-dontwarn java.rmi.UnmarshalException

# 7-Zip-JBinding resolves its Java classes, properties and callbacks from JNI.
-keep class net.sf.sevenzipjbinding.** { *; }
-keep class me.zhanghai.android.files.provider.archive.archiver.SevenZipArchiveProperties { *; }
-keep class * implements net.sf.sevenzipjbinding.IArchiveOpenCallback { *; }
-keep class * implements net.sf.sevenzipjbinding.IArchiveExtractCallback { *; }
-keep class * implements net.sf.sevenzipjbinding.IOutCreateCallback { *; }
-keep class * implements net.sf.sevenzipjbinding.ISequentialInStream { *; }
-keep class * implements net.sf.sevenzipjbinding.ISequentialOutStream { *; }

# APKEditor: retain paths used by Class.getResourceAsStream().
-keepnames class com.reandroid.arsc.ARSCLib
-keepnames class com.reandroid.apk.AndroidFrameworks
-keepnames class com.reandroid.apkeditor.APKEditor
-keepnames class com.reandroid.apkeditor.refactor.HtmlColor
# The optional upstream JF smali engine includes desktop-only ANTLR debugging tools.
-dontwarn java.awt.**
-dontwarn javax.swing.**
