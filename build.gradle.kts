import com.android.build.gradle.internal.dsl.BaseAppModuleExtension
import org.jetbrains.compose.ComposeExtension

group "com.carlom.klardrop"
version "1.0-SNAPSHOT"

plugins {
  alias(deps.plugins.kotlin.multiplatform) apply false
  alias(deps.plugins.kotlin.android) apply false
  alias(deps.plugins.android.application) apply false
  alias(deps.plugins.android.library) apply false
  alias(deps.plugins.jetbrains.compose) apply false
  alias(deps.plugins.kotlin.serialization) apply false
}

allprojects {
  repositories {
    google()
    mavenCentral()
    maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
    maven("https://s01.oss.sonatype.org/content/repositories/snapshots/") // sonatype snapshots
    mavenLocal()
  }
}

subprojects {

  val javaVersion = JavaVersion.VERSION_17

  pluginManager.withPlugin("com.android.library") {
    configure<com.android.build.gradle.BaseExtension> {
      compileOptions {
        sourceCompatibility = javaVersion
        targetCompatibility = javaVersion
      }
    }
  }

  pluginManager.withPlugin("com.android.application") {
    configure<BaseAppModuleExtension> {
      compileOptions {
        sourceCompatibility = javaVersion
        targetCompatibility = javaVersion
      }
    }
  }

  pluginManager.withPlugin("org.jetbrains.compose") {
    configure<ComposeExtension> {
      kotlinCompilerPlugin.set(deps.versions.compose.compiler)
    }
  }

  tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile>().configureEach {
    kotlinOptions {
      jvmTarget = javaVersion.toString()
    }
  }

  tasks.withType<JavaCompile>().configureEach {
    sourceCompatibility = javaVersion.toString()
    targetCompatibility = javaVersion.toString()
  }

}

