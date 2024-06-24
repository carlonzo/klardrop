dependencyResolutionManagement {
  versionCatalogs {
    register("deps") {
      from(fileTree("gradle/dependencies.toml"))
    }
  }
}

pluginManagement {
  repositories {
    google()
    gradlePluginPortal()
    mavenCentral()
    maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
  }

}



rootProject.name = "klardrop"

include(":common")
include(":protos")
include("common-ui")
include(":android", ":desktop", ":macos")

// changing names due to clashes with other "common" libs https://youtrack.jetbrains.com/issue/KT-66568/w-KLIB-resolver-The-same-uniquename...-found-in-more-than-one-library
project(":common").name="klardrop-common"