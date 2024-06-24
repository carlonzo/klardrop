import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.dsl.CommonExtension
import com.android.build.api.dsl.LibraryExtension
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile

group = "com.carlom.klardrop"
version = "1.0-SNAPSHOT"

plugins {
  alias(deps.plugins.kotlin.multiplatform) apply false
  alias(deps.plugins.kotlin.android) apply false
  alias(deps.plugins.android.application) apply false
  alias(deps.plugins.android.library) apply false
  alias(deps.plugins.jetbrains.compose) apply false
  alias(deps.plugins.compose.compiler) apply false
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

  withAndroidPlugin {
    compileOptions {
      sourceCompatibility = javaVersion
      targetCompatibility = javaVersion
    }

    compileSdk = 33
    defaultConfig {
      minSdk = 23
    }
  }

//  pluginManager.withPlugin("org.jetbrains.compose") {
//    configure<ComposeExtension> {
//      kotlinCompilerPlugin.set(deps.versions.compose.compiler)
//    }
//  }

  tasks.withType<KotlinJvmCompile>().configureEach {
    kotlinOptions {
      jvmTarget = javaVersion.toString()
    }
  }

  tasks.withType<JavaCompile>().configureEach {
    sourceCompatibility = javaVersion.toString()
    targetCompatibility = javaVersion.toString()
  }

}

fun Project.withAndroidPlugin(configureBlock: CommonExtension<*, *, *, *, *>.() -> Unit) {
  pluginManager.withPlugin("com.android.application") {
    configure<ApplicationExtension> { configureBlock() }
  }

  pluginManager.withPlugin("com.android.library") {
    configure<LibraryExtension> { configureBlock() }
  }
}

