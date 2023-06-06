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

include(":android", ":desktop", ":common", ":macos")
include("common-ui")
