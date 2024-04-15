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
