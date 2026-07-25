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
        // JitPack (LaMa-ONNX / 其他第三方开源库)
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "WatermarkRemover"
include(":app")
