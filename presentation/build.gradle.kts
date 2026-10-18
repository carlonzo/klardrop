import io.sentry.kotlin.multiplatform.gradle.AppleDependencyProvider
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
  alias(deps.plugins.kotlin.multiplatform)
  alias(deps.plugins.android.kmp.library)
  alias(deps.plugins.kotlin.serialization)
  // Also applied here, not just to :klardrop-common. :presentation's Apple binaries —
  // including the Gradle-run test executables — inherit `-framework Sentry` from the
  // klardrop-common cinterop klib, so they need the plugin's sentry-cocoa link
  // configuration too. Without it the link fails with "framework 'Sentry' not found".
  alias(deps.plugins.sentry.kmp)
}

kotlin {
  explicitApi()

  @OptIn(org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation::class)
  abiValidation {
  }

  android {
    namespace = "com.klardrop.presentation"
    compileSdk = 37
    minSdk = 23
  }
  jvm("desktopJvm") {
    compilerOptions {
      jvmTarget = JvmTarget.JVM_21
    }
  }
  iosArm64 {
    binaries.framework {
      baseName = "presentation"
      isStatic = true
      export(project(":klardrop-common"))
    }
  }
  iosSimulatorArm64 {
    binaries.framework {
      baseName = "presentation"
      isStatic = true
      export(project(":klardrop-common"))
    }
  }
  macosArm64 {
    binaries.framework {
      baseName = "presentation"
      isStatic = true
      export(project(":klardrop-common"))
    }
  }
  linuxX64()
  // Declared only when this build can produce linuxArm64 — see `hostCanBuildLinuxArm64`.
  if (rootProject.extra["hostCanBuildLinuxArm64"] as Boolean) linuxArm64()
  applyDefaultHierarchyTemplate()

  // Swift export (replaces both the Obj-C framework above and SKIE): Kotlin 2.4.20+
  // generates real Swift modules instead of an Obj-C header, so sealed hierarchies
  // become exhaustive Swift enums (`.sealedType()` instead of SKIE's `onEnum(of:)`),
  // Flows become AsyncSequence (`.asAsyncSequence()`), suspend funs become
  // `async throws`, and Float?/Int? map to Swift optionals directly. Xcode calls
  // `embedSwiftExportForXcode` (replacing `embedAndSignAppleFrameworkForXcode` in
  // the Run Script phase); Gradle-run Apple tests link the same Swift modules.
  //
  // Module layout mirrors the old Obj-C export: `presentation` is the root module
  // Swift imports, `KlardropCommon` carries the :klardrop-common API the framework
  // re-exports, and `FilekitCore` carries filekit-core types (PlatformFile) that
  // cross the boundary via PlatformFileBridge.
  @OptIn(org.jetbrains.kotlin.gradle.swiftexport.ExperimentalSwiftExportDsl::class)
  swiftExport {
    moduleName = "presentation"
    flattenPackage = "com.carlom.klardrop"
    export(project(":klardrop-common")) {
      moduleName = "KlardropCommon"
      flattenPackage = "com.carlom.klardrop.common"
    }
    export(deps.filekit.core) {
      moduleName = "FilekitCore"
      flattenPackage = "io.github.vinceglb.filekit.core"
    }
  }

  // Direct Xcode integration: the "Embed Kotlin presentation.framework" Run Script
  // phase builds the framework via Gradle during the Xcode build itself — no
  // CocoaPods, no podspec, no `syncFramework`.
  //
  // Sentry: with no `kotlin("native.cocoapods")` plugin applied, the sentry-kmp
  // plugin links sentry-cocoa from Xcode's SwiftPM integration instead (exact
  // 9.29.2 in the iosApp.xcodeproj package reference, to match the kmp 0.28.0
  // cinterop). It finds
  // Sentry.xcframework in DerivedData via `sentryKmp.linker.xcodeprojPath`
  // below, and adds `-F`/`-rpath` itself for both framework and
  // test-executable links — so no manual `-F`/`-rpath` forwarding is needed.

  // Tells the sentry-kmp plugin where the Xcode project lives so its
  // DerivedData strategy can find Sentry.xcframework (the sentry-cocoa package
  // reference lives in iosApp.xcodeproj, pinned to the version the kmp cinterop
  // was built against). Gradle-run
  // Apple test executables link against that same framework copy.
  sentryKmp {
    autoInstall.apple.provider.set(AppleDependencyProvider.NONE)
    linker {
      xcodeprojPath.set(rootProject.file("iosApp/iosApp.xcodeproj").absolutePath)
    }
  }

  sourceSets {

    commonMain {
      dependencies {
        api(project(":klardrop-common"))
        api(deps.kotlinx.coroutines.core)
        api(deps.filekit.core)
        api(deps.kotlinx.io.core)
        implementation(deps.kotlinx.serialization.json)
      }
    }

    commonTest {
      dependencies {
        implementation(kotlin("test"))
        implementation(deps.turbine)
        implementation(deps.kotlinx.coroutines.test)
        // ktor-network types (InetSocketAddress) appear in the VisibleDevices interface
        // faked by DiscoveryControllerPairingQueueTest.
        implementation(deps.ktor.network)
      }
    }

    all {
      languageSettings.optIn("kotlinx.coroutines.ExperimentalCoroutinesApi")
    }

    targets.all {
      compilations.all {
        compileTaskProvider.configure {
          compilerOptions {
            freeCompilerArgs.add("-Xexpect-actual-classes")
          }
        }
      }
    }
  }
}

val apiDumpDir = layout.projectDirectory.dir("api")
val checkNoLeakedImplementationTypes = tasks.register("checkNoLeakedImplementationTypes") {
  group = "verification"
  description = "Verifies that no forbidden implementation types are exposed in the public ABI dump."
  inputs.dir(apiDumpDir)
  val dumpDir = apiDumpDir.asFile
  val forbidden = listOf(
    "com.squareup.wire",
    "app.cash.sqldelight",
    "com.carlonzo.ukey2",
    "com.google.security",
    "com.google.location.nearby",
    "sharing.nearby",
    "location.nearby",
  )
  doLast {
    if (!dumpDir.exists()) return@doLast
    val offending = mutableListOf<String>()
    dumpDir.walkTopDown().filter { it.isFile }.forEach { file ->
      file.useLines { lines ->
        lines.forEachIndexed { index, line ->
          val normalized = line.replace('/', '.')
          for (pkg in forbidden) {
            if (pkg in normalized) {
              offending.add("${file.relativeTo(dumpDir.parentFile)}:${index + 1} [$pkg]: $line")
              break
            }
          }
        }
      }
    }
    if (offending.isNotEmpty()) {
      throw GradleException("Forbidden package leaks detected in API dumps:\n" + offending.joinToString("\n"))
    }
  }
}

tasks.named("check") {
  dependsOn("checkKotlinAbi")
  dependsOn(checkNoLeakedImplementationTypes)
}

