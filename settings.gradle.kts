plugins {
    // 允许 Gradle 在本机没有匹配 toolchain JDK 时自动下载（CI 上是 JDK 21）
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "ok-script-toolkit-jetbrains"
