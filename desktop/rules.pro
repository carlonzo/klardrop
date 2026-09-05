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

# Disable ProGuard's OPTIMIZATION pass (shrinking still runs; obfuscation is
# turned off separately just below).
# Its optimizer emits invalid bytecode for this codebase in at least two
# unrelated places — kotlinx.coroutines JobSupport ("VerifyError: Bad
# invokespecial ... indirect superinterface") and Compose's Skia text
# ("VerifyError: Bad return type" in ActualParagraph) — and the breakage is
# non-deterministic across builds. Optimization buys little for a desktop app
# that already bundles a JRE, so turning it off trades a slightly larger bundle
# for a release that actually runs.
-dontoptimize

# Disable OBFUSCATION too, so desktop crash reports are readable in Sentry. Shrinking
# still runs — this only stops name mangling, and it also stops ProGuard stripping the
# SourceFile/LineNumberTable attributes (attribute removal is part of the obfuscation
# step, and the -keepattributes above does not ask for them). So release frames arrive
# as real class/method names WITH file and line, instead of a.b.c.d(Unknown Source).
#
# Sentry can deobfuscate a plain JVM app — this is not the Android-only path it looks
# like. io.sentry.SentryOptions.setProguardUuid lives in the core `sentry` artifact
# (also settable as `sentry.proguard-uuid` or SENTRY_PROGUARD_UUID, or via an
# io.sentry.ProguardUuids entry in a sentry-debug-meta.properties resource), core's
# MainEventProcessor emits the matching debug_meta image of type "proguard" on every
# event, and the server keys symbolication off platform "java" — which is exactly what
# sentry-java stamps here. The reason we don't use it is cost, not capability: it would
# mean teaching the release pipeline to run `sentry-cli proguard uuid` +
# `upload-proguard` for the mapping of each distribution and bake that UUID into the
# packaged app — and the desktop distributions are host-locked, so that is three
# artifacts on three runners to keep in sync for every release.
#
# Not obfuscating buys the same result for free. The trade is cheap here for the same
# reasons as -dontoptimize above: this repo is public, so obfuscation hides nothing
# that isn't already on GitHub, and the distribution bundles its own JRE, so real names
# are a rounding error next to the runtime we already ship.
-dontobfuscate

# Keep the synthetic enum members. Without this ProGuard strips values()/$VALUES,
# so Class.getEnumConstants() returns null at runtime — which crashes anything
# that reflects over enums (e.g. Sentry's serializer init, kotlinx.serialization
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
# Sentry crash reporter (replaced Bugsnag + its bundled Jackson serializer).
#
# sentry-java resolves its integrations, transport and JSON (de)serializers by name at
# init, so shrinking them produces a ClassNotFoundException on the first Sentry.init.
# Unlike Android — where AGP applies the Sentry AAR's own consumer rules automatically —
# the Compose Desktop proguard task reads only this file, so the keeps are manual.
#
# The -dontwarn list covers sentry-java's optional integrations (Spring, logback, JUL,
# OpenTelemetry, GraphQL). They are compile-time references to artifacts we do not
# bundle, and without this the whole minified build aborts on unresolved references —
# the same failure mode as the BouncyCastle block above.
# -------------------------------------------------------------------------
-keep class io.sentry.** { *; }
-keepclassmembers enum io.sentry.** { *; }
-dontwarn io.sentry.**
-dontwarn org.slf4j.**

# -------------------------------------------------------------------------
# FileKit — `filekit-dialogs-compose` ships an ImageBitmap→ByteArray helper
# (ImageBitmapExt_nonAndroid) that calls
# org.jetbrains.skia.Image.encodeToData(EncodedImageFormat, int). Skiko dropped
# that overload, so against the Skiko that Compose 1.12 bundles the call is an
# unresolved *program* class member and ProGuard aborts the whole minified
# build ("there were 1 unresolved references to program class members. Please
# correct the above warnings first.") — the same failure mode as the
# BouncyCastle and Sentry-integration blocks above, and what broke the nightly
# Linux app-image build.
#
# The dead reference is harmless: that helper backs FileKit's camera-picker /
# save-image-to-gallery path, which is Android-only in practice. Desktop only
# uses the file and media *pickers* (rememberFilePickerLauncher and friends),
# so nothing on the desktop runtime ever reaches encodeToByteArray. Scoped to
# the one package so a genuinely broken FileKit reference elsewhere still fails
# the build.
# -------------------------------------------------------------------------
-dontwarn io.github.vinceglb.filekit.dialogs.compose.util.**

# -------------------------------------------------------------------------
# Compose Native Tray — Linux StatusNotifierItem via JNI. The library loads a
# bundled .so reflectively; shrinking it (or JNA, if a transitive still pulls
# it) produces a missing/untinted tray icon and dead clicks in release builds.
# -------------------------------------------------------------------------
-keep class dev.nucleusframework.composenativetray.** { *; }
-keep class com.sun.jna.** { *; }
-dontwarn com.sun.jna.**
-dontwarn dev.nucleusframework.composenativetray.**

# -------------------------------------------------------------------------
# Debug Control — strictly development/test infrastructure driven via klardrop-ctl.
# Never keep com.carlom.klardrop.debug.** in release builds. ProGuard strips it
# completely because DesktopDebugLoader accesses it only reflectively in debug runs.
# -------------------------------------------------------------------------
-dontwarn com.carlom.klardrop.debug.**

