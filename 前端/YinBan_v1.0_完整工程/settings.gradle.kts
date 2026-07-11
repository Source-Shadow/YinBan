// ============================================================
// 路径: settings.gradle.kts
// 用途: AI 影伴系统 (YinBan) — 项目模块配置
// ============================================================

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
    }
}

rootProject.name = "YinBan"
include(":app")
