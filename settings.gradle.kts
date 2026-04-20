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
  }

}



rootProject.name = "klardrop"

include(":common")
include(":protos")
include("common-ui")
include(":android", ":desktop", ":macos", ":cli")

// to workaround https://youtrack.jetbrains.com/issue/KT-66568/w-KLIB-resolver-The-same-uniquename...-found-in-more-than-one-library
project(":common").name = "klardrop-common"