rootProject.name = "device-registry"

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
    versionCatalogs {
        create("backendLibs") {
            from(files("../gradle/backend.versions.toml"))
        }
    }
}
