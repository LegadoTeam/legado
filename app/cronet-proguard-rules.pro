#############################################
# Cronet ProGuard / R8 rules (方案A: Maven cronet-embedded)
# 说明：
# - 仅用于 app 侧打包 cronet-embedded
# - 不包含任何“文件树/DocumentFile/TreeDocumentFile”相关 keep
#############################################

############### [1] AndroidX Keep 注解支持 ###############
# third_party/androidx/androidx_annotations.flags
-keep @androidx.annotation.Keep class *
-keepclasseswithmembers,allowaccessmodification class * {
  @androidx.annotation.Keep <fields>;
}
-keepclasseswithmembers,allowaccessmodification class * {
  @androidx.annotation.Keep <methods>;
}

############### [2] Chromium 注解相关（Cronet 会用到） ###############
# build/android/chromium_annotations.flags

# Keep all annotation related attributes that can affect runtime
-keepattributes RuntimeVisible*Annotations
-keepattributes AnnotationDefault

# Keeps for class level annotations.
-keep,allowaccessmodification @org.chromium.build.annotations.UsedByReflection class ** {}

# Keeps for method/field level annotations.
-keepclasseswithmembers,allowaccessmodification class ** {
  @org.chromium.build.annotations.UsedByReflection <methods>;
}
-keepclasseswithmembers,allowaccessmodification class ** {
  @org.chromium.build.annotations.UsedByReflection <fields>;
}

# DoNotInline：不内联，但允许 shrink/obfuscation
-if @org.chromium.build.annotations.DoNotInline class * {
    *** *(...);
}
-keep,allowobfuscation,allowaccessmodification class <1> {
    *** <2>(...);
}
-keepclassmembers,allowobfuscation,allowaccessmodification class * {
   @org.chromium.build.annotations.DoNotInline <methods>;
}
-keepclassmembers,allowobfuscation,allowaccessmodification class * {
   @org.chromium.build.annotations.DoNotInline <fields>;
}

# AlwaysInline
-alwaysinline class * {
    @org.chromium.build.annotations.AlwaysInline *;
}

# DoNotStripLogs：保留日志（R8 不允许设为 0，所以用 1）
-maximumremovedandroidloglevel 1 class ** {
   @org.chromium.build.annotations.DoNotStripLogs <methods>;
}
-maximumremovedandroidloglevel 1 @org.chromium.build.annotations.DoNotStripLogs class ** {
   <methods>;
}

# DoNotClassMerge：禁止类合并
-keep,allowaccessmodification,allowobfuscation,allowshrinking @org.chromium.build.annotations.DoNotClassMerge class *

# IdentifierNameString
-identifiernamestring class * {
    @org.chromium.build.annotations.IdentifierNameString *;
}

# OptimizeAsNonNull：帮助 R8 判定非空
-assumevalues class ** {
  @org.chromium.build.annotations.OptimizeAsNonNull *** *(...) return _NONNULL_;
}
-assumenosideeffects class ** {
  @org.chromium.build.annotations.OptimizeAsNonNull *** *(...);
}
-assumevalues class ** {
  @org.chromium.build.annotations.OptimizeAsNonNull *** * return _NONNULL_;
}
-assumenosideeffects class ** {
  @org.chromium.build.annotations.OptimizeAsNonNull *** *;
}

############### [3] shared_with_cronet.flags ###############
# Keep Parcelable CREATOR（仅限 org.chromium 包）
-keepclassmembers class !cr_allowunused,org.chromium.** implements android.os.Parcelable {
  public static *** CREATOR;
}
# Don't obfuscate Parcelables in org.chromium
-keepnames,allowaccessmodification class !cr_allowunused,org.chromium.** implements android.os.Parcelable {}

# Keep enum values() / valueOf（仅限 org.chromium）
-keepclassmembers enum !cr_allowunused,org.chromium.** {
    public static **[] values();
}

# Required to remove fields until b/274802355 is resolved.
-assumevalues class !cr_allowunused,** {
  final org.chromium.base.ThreadUtils$ThreadChecker * return _NONNULL_;
}

############### [4] cronet_impl_common_proguard.cfg ###############
# Cronet API 通过反射读取 ImplVersion
-keep public class org.chromium.net.impl.ImplVersion {
  public *;
}

-dontwarn com.google.errorprone.annotations.DoNotMock

############### [5] cronet_impl_native_proguard.cfg ###############
# Cronet API 通过反射调用 NativeCronetProvider(Context)
-keep class org.chromium.net.impl.NativeCronetProvider {
    public <init>(android.content.Context);
}

# 有些构建链要求 keep 注解本身，否则 keep rule 可能不生效
-keep @interface org.chromium.build.annotations.DoNotInline
-keep @interface org.chromium.build.annotations.UsedByReflection
-keep @interface org.chromium.build.annotations.IdentifierNameString

# JNI Zero 注解（Cronet jar 内部可能 jarjar 过，所以用 ** 前缀）
-keep @interface **org.jni_zero.AccessedByNative
-keep @interface **org.jni_zero.CalledByNative
-keep @interface **org.jni_zero.CalledByNativeUnchecked

# Suppress unnecessary warnings.
-dontnote org.chromium.net.ProxyChangeListener$ProxyReceiver
-dontnote org.chromium.net.AndroidKeyStore
-dontwarn org.chromium.base.WindowCallbackWrapper

# Generated for chrome apk and not included into cronet.
-dontwarn org.chromium.base.library_loader.LibraryLoader
-dontwarn org.chromium.base.SysUtils
-dontwarn org.chromium.build.NativeLibraries

# Not required to keep (not loaded as entry point), but suppress note.
-dontnote org.chromium.net.UrlRequest$ResponseHeadersMap

# legacy support lib warnings (有些环境会扫到)
-dontwarn android.support.**

# Skip protobuf runtime check for isOnAndroidDevice()
-assumevalues class com.google.protobuf.Android {
    static boolean ASSUME_ANDROID return true;
}

# 保留所有手动注册的 native 方法（Cronet 必须）
-keepclasseswithmembers,includedescriptorclasses,allowaccessmodification class org.chromium.**,**J.N {
  native <methods>;
}

# Chromium protos：builder 反射依赖，保留字段
-keepclassmembers class org.chromium.** extends com.google.protobuf.GeneratedMessageLite {
  <fields>;
}

# Android System SDK false positive
-dontwarn android.os.SystemProperties

############### [6] cronet_shared_proguard.cfg ###############
-dontwarn android.util.StatsEvent
-dontwarn android.util.StatsEvent$*
-dontwarn android.util.StatsLog

############### [7] jni_zero/proguard.flags ###############
-keepclasseswithmembers,allowaccessmodification class ** {
  @**org.jni_zero.AccessedByNative <fields>;
}
-keepclasseswithmembers,includedescriptorclasses,allowaccessmodification,allowoptimization class ** {
  @**org.jni_zero.CalledByNative <methods>;
}
-keepclasseswithmembers,includedescriptorclasses,allowaccessmodification,allowoptimization class ** {
  @**org.jni_zero.CalledByNativeUnchecked <methods>;
}

# 允许删除未使用 native，但保留的不要重命名（较宽；按官方保持）
-keepclasseswithmembernames,includedescriptorclasses,allowaccessmodification class ** {
  native <methods>;
}

# multiplexing: 保留 hash 字段（官方说明）
-keepclasseswithmembers class !cr_allowunused,**J.N {
  public long *_HASH;
}

############### [8] Cronet 额外稳妥 keep（建议保留） ###############
# 防止 R8 过度裁剪 Cronet API / Provider / Engine
-keep class org.chromium.net.** { *; }
-keep class org.chromium.net.impl.** { *; }

# 你原来已有的 X509Util（保留）
-keepclassmembers class org.chromium.net.X509Util {
    *** sDefaultTrustManager;
    *** sTestTrustManager;
}

############### [9] 常见 warn 抑制（不影响功能） ###############
-dontwarn internal.org.chromium.**
-dontwarn org.chromium.**

#############################################
# ✅ 注意：
# 1) 这份文件不解决 Duplicate class，Duplicate 必须通过依赖层面解决：
#    - 只能保留 Maven cronet-embedded
#    - 删除 fileTree(cronetlib) 以及任何本地 cronet jar/aar
#    - 不要 exclude org.chromium.net（否则会 R8 missing class）
# 2) 这份文件已按你要求：没有任何“文件树/TreeDocumentFile”相关 keep
#############################################
