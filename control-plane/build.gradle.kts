import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
  alias(deps.plugins.kotlin.multiplatform)
  alias(deps.plugins.android.kmp.library)
  alias(deps.plugins.kotlin.serialization)
  // Same as :presentation — the macOS framework below embeds :klardrop-common's Sentry cinterop,
  // so this module needs the plugin's sentry-cocoa link configuration too.
  alias(deps.plugins.sentry.kmp)
}

kotlin {
  android {
    namespace = "com.carlom.klardrop.control"
    compileSdk = 37
    minSdk = 24
  }
  jvm("desktopJvm") {
    compilerOptions {
      jvmTarget = JvmTarget.JVM_21
    }
  }
  linuxX64().binaries.all {
    // See the note in common/src/nativeInterop/cinterop/avahi.def: ld.lld's built-in
    // search list does not include Debian/Ubuntu's multiarch /usr/lib/<triplet>, so
    // without these this module's own test binary fails to link its sqlite3.
    linkerOpts(
      "-L/usr/lib/x86_64-linux-gnu",
    )
  }
  // Declared only when this build can produce linuxArm64 — see `hostCanBuildLinuxArm64`.
  if (rootProject.extra["hostCanBuildLinuxArm64"] as Boolean) {
    linuxArm64().binaries.all {
      linkerOpts(
        "-L/usr/lib/aarch64-linux-gnu",
      )
    }
  }
  // The native macOS app (iosApp/KlardropMac, SwiftUI) is not a JVM app: it links a Kotlin
  // framework. :presentation's KlardropBootstrap cannot reference ControlPlane because
  // :control-plane already depends on :presentation, so wiring the host from :presentation
  // would be a dependency cycle. This target is the way out — :control-plane sits downstream
  // of both, so it is the module that produces the one framework the Xcode target links, and
  // MacApp.swift starts/binds the shared server through it. It is not dead weight: it is the
  // exact code the shipped macOS app runs.
  macosArm64 {
    binaries.framework {
      // Named `presentation` because this is the ONLY Kotlin framework KlardropMac
      // links. It embeds :presentation and :klardrop-common, so the ~20 Swift files
      // shared with iOS keep resolving `import presentation` unchanged, and
      // `ControlPlane` simply appears in that module.
      //
      // Naming it anything else would mean linking two static Kotlin/Native
      // frameworks that share Kotlin types, which is unsound: the same ObjC class
      // becomes visible through two Swift modules, which is what made MacApp.swift
      // fail with "'KlardropBootstrap' is ambiguous for type lookup". ld64's lazy
      // archive-member extraction and `-undefined dynamic_lookup` had been hiding
      // that at link time rather than removing it.
      baseName = "presentation"
      isStatic = true
      // Both exports are required by the framework's public API: `start(Klardrop)`
      // and `bind(DiscoveryController, Klardrop)` name types this module does not
      // own. Without `export` the generated header cannot name them and MacApp.swift
      // fails to compile.
      export(project(":presentation"))
      export(project(":klardrop-common"))
    }
  }
  applyDefaultHierarchyTemplate()

  // Tells the sentry-kmp plugin where the Xcode project lives so it finds Sentry.xcframework
  // in DerivedData (SwiftPM, see iosApp/Package.swift). Same as :presentation.
  sentryKmp {
    linker {
      xcodeprojPath.set(rootProject.file("iosApp/iosApp.xcodeproj").absolutePath)
    }
  }

  // Swift export for the macOS app, mirroring :presentation's swiftExport. KlardropMac
  // links this module's output (Run Script: `:control-plane:embedSwiftExportForXcode`), so
  // it must produce the same `presentation` Swift module the shared iOS/macOS Swift imports.
  @OptIn(org.jetbrains.kotlin.gradle.swiftexport.ExperimentalSwiftExportDsl::class)
  swiftExport {
    moduleName = "presentation"
    flattenPackage = "com.carlom.klardrop"
    export(project(":presentation"))
    export(project(":klardrop-common")) {
      moduleName = "KlardropCommon"
      flattenPackage = "com.carlom.klardrop.common"
    }
    export(deps.filekit.core) {
      moduleName = "FilekitCore"
      flattenPackage = "io.github.vinceglb.filekit.core"
    }
  }

  sourceSets {
    commonMain {
      dependencies {
        // `api`, not `implementation`: the macosArm64 framework above exports both,
        // and an exported dependency must be an API dependency of its source set.
        // `implementation` links the tests fine but fails
        // :control-plane:linkDebugFrameworkMacosArm64 with "not specified as
        // API-dependencies of a corresponding source set".
        api(project(":klardrop-common"))
        api(project(":presentation"))
        implementation(deps.kotlinx.coroutines.core)
        implementation(deps.kotlinx.serialization.json)
        implementation(deps.ktor.network)
      }
    }
    val desktopJvmMain by getting {
      dependencies {
        // QrMatrixRenderer.desktopJvm.kt — same QR encoder compose-ui already uses; there is
        // no native macOS or linux artifact of qrcode-kotlin (see QrMatrixRenderer.linux.kt),
        // so it is JVM/Android-only.
        implementation(deps.qrcode.kotlin)
      }
    }
    commonTest {
      dependencies {
        implementation(kotlin("test"))
        implementation(deps.kotlinx.coroutines.test)
        implementation(deps.ktor.network)
      }
    }
  }

  // `posixMain` holds the control-file implementation the Linux and macOS engines share
  // verbatim. It has to be declared by hand: the default hierarchy template's groups are
  // native / apple / linux / macos, and there is no group covering {linux, macos}, so a
  // `unixMain` never exists. Without this line `src/posixMain` would simply not be compiled —
  // Gradle would build a different set of files and report no error at all, which is exactly
  // the kind of silently-dead code this module must not ship. `linuxMain` and `macosMain`
  // both come from the template; only the shared one is ours.
  sourceSets.apply {
    val posix = create("posixMain") { dependsOn(getByName("commonMain")) }
    getByName("linuxMain").dependsOn(posix)
    getByName("macosMain").dependsOn(posix)
  }
}

// resolveControlFilePath() (JVM) writes to $XDG_RUNTIME_DIR/klardrop/control.json. Without
// this, tests that exercise it (LoopbackHttpServerTest, ControlPlaneUpdateTest) write to and
// delete the REAL control.json of whatever klardrop daemon happens to be running on the
// machine under the developer's own XDG_RUNTIME_DIR — this has actually happened. Point every
// test JVM at an isolated directory instead.
tasks.withType<Test>().configureEach {
  environment("XDG_RUNTIME_DIR", temporaryDir.absolutePath)
}
