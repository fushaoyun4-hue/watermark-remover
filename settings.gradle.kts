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
        // JitPack (LaMa-ONNX / 其他第三方开源库)
        maven { url = uri("https://jitpack.io") }
        // 阿里云镜像公共仓库（OpenCV / ffmpeg-kit 等）
        maven { url = uri("https://maven.aliyun.com/repository/public") }
    }
}

rootProject.name = "WatermarkRemover"
include(":app")
