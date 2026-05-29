# ProGuard configuration for the minified desktop release.
#
# WHY THIS MATTERS: `buildTypes.release` runs ProGuard over the whole runtime
# classpath. kotlinx.serialization, Wire/protobuf, jmDNS, SQLite-JDBC and Ktor
# all rely on reflection, generated members, or ServiceLoader — without keep
# rules ProGuard renames/strips them and the *release* build breaks at runtime
# (deserialization, mDNS, DB, networking) while debug works fine. The in-app
# update checker parses latest.json with kotlinx.serialization, so the same risk
# applies there. Treat any change here as release-gating.

# Keep annotations + generic signatures used reflectively at runtime.
-keepattributes RuntimeVisibleAnnotations,AnnotationDefault,InnerClasses,Signature,*Annotation*

# Disable ProGuard's OPTIMIZATION pass (shrinking + obfuscation still run).
# Its optimizer emits invalid bytecode for this codebase in at least two
# unrelated places — kotlinx.coroutines JobSupport ("VerifyError: Bad
# invokespecial ... indirect superinterface") and Compose's Skia text
# ("VerifyError: Bad return type" in ActualParagraph) — and the breakage is
# non-deterministic across builds. Optimization buys little for a desktop app
# that already bundles a JRE, so turning it off trades a slightly larger bundle
# for a release that actually runs.
-dontoptimize

# Keep the synthetic enum members. Without this ProGuard strips values()/$VALUES,
# so Class.getEnumConstants() returns null at runtime — which crashes anything
# that reflects over enums (e.g. Bugsnag's serializer init, kotlinx.serialization
# enum descriptors). Canonical ProGuard enum rule.
-keepclassmembers,allowoptimization enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# -------------------------------------------------------------------------
# kotlinx.serialization — canonical upstream ruleset.
# -------------------------------------------------------------------------
-dontnote kotlinx.serialization.**
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Every @Serializable type in the app: keep its generated $$serializer, the
# Companion, and the synthetic serializer() accessor. Covers the trust/socket
# wire models exercised by the live protocol AND the update DTOs
# (com.carlom.klardrop.common.update.**) parsed from latest.json.
-keep,includedescriptorclasses class com.carlom.klardrop.**$$serializer { *; }
-keepclassmembers class com.carlom.klardrop.** {
    *** Companion;
}
-keepclasseswithmembers class com.carlom.klardrop.** {
    kotlinx.serialization.KSerializer serializer(...);
}
# Extra belt-and-braces: keep members of anything annotated @Serializable.
-keepclassmembers @kotlinx.serialization.Serializable class com.carlom.klardrop.** {
    *** Companion;
    *** INSTANCE;
    kotlinx.serialization.KSerializer serializer(...);
    <fields>;
}

# -------------------------------------------------------------------------
# Wire / protobuf — generated Nearby Share message classes + runtime adapters.
# These are constructed and (de)serialized via their generated ADAPTERs.
# -------------------------------------------------------------------------
-keep class com.squareup.wire.** { *; }
-keep class com.google.location.nearby.connections.proto.** { *; }
-keep class sharing.nearby.** { *; }
-dontwarn com.squareup.wire.**
-dontwarn okio.**

# -------------------------------------------------------------------------
# jmDNS — Linux/Windows mDNS discovery. Uses reflection over service types.
# -------------------------------------------------------------------------
-keep class javax.jmdns.** { *; }
-keep class org.jmdns.** { *; }
-dontwarn javax.jmdns.**
-dontwarn org.jmdns.**

# -------------------------------------------------------------------------
# SQLite-JDBC — loads its native lib and registers via the JDBC ServiceLoader.
# -------------------------------------------------------------------------
-keep class org.sqlite.** { *; }
-dontwarn org.sqlite.**

# -------------------------------------------------------------------------
# Ktor (raw socket transport) + kotlinx.coroutines (Main dispatcher is loaded
# via ServiceLoader; coroutines uses volatile fields updated atomically).
# -------------------------------------------------------------------------
-keep class io.ktor.** { *; }
-dontwarn io.ktor.events.**
-dontwarn io.ktor.**
# Keep coroutines wholesale (it's reflection/ServiceLoader-heavy); combined with
# -dontoptimize at the top this keeps its bytecode intact.
-keep class kotlinx.coroutines.** { *; }
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembers class kotlinx.coroutines.** {
    volatile <fields>;
}
-dontwarn kotlinx.coroutines.**

# -------------------------------------------------------------------------
# whyoleg cryptography — the provider (JdkCryptographyProviderContainer) is found
# via a ServiceLoader (META-INF/services). ProGuard must not strip/rename the
# provider classes or that lookup throws ServiceConfigurationError at TrustCrypto
# init and the app dies on startup. The `-provider-optimal` artifact also has
# optional BouncyCastle code paths; desktop uses the JDK provider and does NOT
# bundle BouncyCastle, so those references are unresolved at proguard time
# (without the dontwarn the whole minified build aborts on "unresolved references").
# -------------------------------------------------------------------------
-keep class dev.whyoleg.cryptography.** { *; }
-dontwarn dev.whyoleg.cryptography.**
-dontwarn org.bouncycastle.**

# -------------------------------------------------------------------------
# Bugsnag crash reporter + its bundled Jackson serializer + SLF4J.
# Jackson's config enums are read via getEnumConstants() at <clinit>; if shrinking
# drops their constants that returns null and Bugsnag init throws on app startup.
# Keep Jackson (and its enums) wholesale — it's only the crash reporter's dep.
# -------------------------------------------------------------------------
-keep class com.bugsnag.** { *; }
-keep class com.fasterxml.jackson.** { *; }
-keepclassmembers enum com.fasterxml.jackson.** { *; }
-dontwarn com.bugsnag.**
-dontwarn com.fasterxml.jackson.**
-dontwarn org.slf4j.**
