pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // NewPipeExtractor（YouTube 取流）只发布在 JitPack，与 NeriPlayer fork 一致
        maven { url = uri("https://jitpack.io") }
        // Xposed API（compileOnly）：LSPosed 认本应用是模块要用，运行时由框架提供，不打进 APK
        maven { url = uri("https://api.xposed.info/") }
    }
}

rootProject.name = "YZLiYin"
include(":app")
