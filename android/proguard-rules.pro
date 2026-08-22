# R8 keep rules for the minified Android release.
#
# Mirrors the shared-library keeps from desktop/rules.pro for the code that runs
# on both platforms (kotlinx.serialization DTOs, Wire/protobuf, whyoleg crypto,
# Ktor). Desktop-only deps (jmDNS, SQLite-JDBC, the JVM Main dispatcher loader)
# are omitted — Android uses NSD for discovery and a different runtime. R8's
# optimizer is kept on (unlike desktop's ProGuard, which needed -dontoptimize).
#
# Treat changes here as release-gating: debug builds aren't minified, so only the
# release APK exercises these rules.

# Keep annotations + generic signatures used reflectively at runtime.
-keepattributes RuntimeVisibleAnnotations,AnnotationDefault,InnerClasses,Signature,*Annotation*

# Synthetic enum members — Class.getEnumConstants() returns null without these,
# which breaks kotlinx.serialization enum descriptors and Sentry's serializer init.
-keepclassmembers,allowoptimization enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# -------------------------------------------------------------------------
# kotlinx.serialization — keep every @Serializable type's generated $$serializer,
# its Companion, and the synthetic serializer() accessor. Covers the trust/socket
# wire models AND the update DTOs (com.carlom.klardrop.common.update.**) parsed
# from latest.json.
# -------------------------------------------------------------------------
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.carlom.klardrop.**$$serializer { *; }
-keepclassmembers class com.carlom.klardrop.** {
    *** Companion;
}
-keepclasseswithmembers class com.carlom.klardrop.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keepclassmembers @kotlinx.serialization.Serializable class com.carlom.klardrop.** {
    *** Companion;
    *** INSTANCE;
    kotlinx.serialization.KSerializer serializer(...);
    <fields>;
}

# -------------------------------------------------------------------------
# Wire / protobuf — generated Nearby Share message classes + runtime adapters,
# constructed and (de)serialized via their generated ADAPTERs.
# -------------------------------------------------------------------------
-keep class com.squareup.wire.** { *; }
-keep class com.google.location.nearby.connections.proto.** { *; }
-keep class sharing.nearby.** { *; }
-dontwarn com.squareup.wire.**
-dontwarn okio.**

# -------------------------------------------------------------------------
# Ktor (raw socket transport).
# -------------------------------------------------------------------------
-keep class io.ktor.** { *; }
-dontwarn io.ktor.**

# -------------------------------------------------------------------------
# whyoleg cryptography — provider found via ServiceLoader (META-INF/services);
# must not be stripped/renamed or TrustCrypto init throws on startup. The
# -provider-optimal artifact has optional BouncyCastle paths.
# -------------------------------------------------------------------------
-keep class dev.whyoleg.cryptography.** { *; }
-dontwarn dev.whyoleg.cryptography.**
-dontwarn org.bouncycastle.**

# -------------------------------------------------------------------------
# Sentry crash reporter (replaced Bugsnag + its bundled Jackson serializer).
# The sentry-android AAR ships consumer rules that AGP applies automatically, so the
# keeps live there rather than here. These only silence unresolved references to
# sentry-java's optional integrations, which we do not bundle.
# -------------------------------------------------------------------------
-dontwarn io.sentry.**
-dontwarn org.slf4j.**

# Strip debug/verbose/info logging in release (R8 optimize removes these calls).
# Log.e / Log.w are kept so error reporting and Sentry stay intact.
-assumenosideeffects class android.util.Log {
    public static int d(...);
    public static int v(...);
    public static int i(...);
    public static boolean isLoggable(...);
}
