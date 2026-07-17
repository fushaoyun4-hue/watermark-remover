pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // OpenCV Maven repository
        maven { url = uri("https://artifacts.aitorafla.com/releases") }
    }
}

rootProject.name = "WatermarkRemover"
include(":app")
