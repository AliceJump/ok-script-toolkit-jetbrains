plugins {
    id("org.jetbrains.kotlin.jvm") version "2.1.10"
    id("org.jetbrains.intellij.platform") version "2.18.1"
}

group = providers.gradleProperty("pluginGroup").get()
version = providers.gradleProperty("pluginVersion").get()

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

// 把 Python 脚本打包进插件 resources（python/ 在 ok-script-toolkit/python/，jetbrains 的上一层）
val pythonDir = project.rootDir.resolve("../python").normalize()
val distributableTasks = setOf("verifyPlugin", "runPluginVerifier", "publishPlugin", "signPlugin")

// ../python 不存在 = 只检出了 jetbrains 子仓库（子仓库自己的 CI 就是这样）。
// 这时普通构建照常走（Copy 空跑，产物里没有 python/，CI 只当冒烟产物用）；
// 但**发布/验证流水线**（verifyPlugin / runPluginVerifier / publishPlugin / signPlugin）
// 必须带 Python 脚本，否则打出来的插件跑不了任务，直接报错中止。
// 注意不要把 buildPlugin 放进这个集合：子仓库独立 CI 会请求 buildPlugin，
// 放进去会让它必然失败。
//
// 检查必须放在**配置阶段**，不要在任务上挂 onlyIf：
// 本项目的 Gradle 版本下，copyPythonScripts 上的 onlyIf 会让 configuration cache
// 无法序列化（"cannot serialize Gradle script object references"），而
// gradle.properties 开了 org.gradle.configuration-cache=true、CI 又不带
// --no-configuration-cache，于是整个构建 BUILD FAILED，CI 与发布流水线全红。
// 缺脚本时 Copy 任务本来就只是空跑，不需要 onlyIf 兜底。
if (!pythonDir.isDirectory) {
    val isDistributable = gradle.startParameter.taskNames.any { requested ->
        distributableTasks.any { name -> name.contains(requested, ignoreCase = true) }
    }
    if (isDistributable) {
        throw GradleException("Python scripts not found at ${pythonDir.absolutePath}. Required for distributable builds.")
    }
    logger.warn("Warning: ../python directory not found, skipping Python script packaging")
}

val copyPython = tasks.register<Copy>("copyPythonScripts") {
    from(pythonDir) {
        include("**/*.py")
        // __pycache__ 里只有 .pyc，本来就不会被 include 命中；但 Gradle 遍历 `**`
        // 时仍会在目标目录建出空目录，最终以空目录形式进 jar。显式排除掉。
        exclude("**/__pycache__/**")
        // 测试脚本是开发用途，按 AGENT.md「插件打包」不得进产物。
        // 它们现在在 python/tests/ 下，这里再设一道防线，避免日后有人把
        // test_*.py 放回 python/ 就被 `**/*.py` 静默收进 jar。
        exclude("tests/**")
        exclude("test_*.py")
    }
    into(project.layout.buildDirectory.dir("resources/main/python"))
}
tasks.processResources { dependsOn(copyPython) }

dependencies {
    intellijPlatform {
        val localPath = providers.gradleProperty("platformLocalPath").orNull
        if (!localPath.isNullOrBlank() && file(localPath).resolve("product-info.json").exists()) {
            local(localPath)
        } else {
            pycharm(providers.gradleProperty("platformVersion").get())
            bundledPlugin("PythonCore")
        }
        testFramework(org.jetbrains.intellij.platform.gradle.TestFrameworkType.Platform)
    }
    testImplementation(kotlin("test"))
    testImplementation("junit:junit:4.13.2")
}

kotlin {
    // CI（release.yml 的 setup-java）只安装 JDK 21；IntelliJ 2025.1 的 JBR 也是 21，
    // 用 23 编译会同时造成 CI toolchain 缺失和产物流水线类文件版本不兼容。
    jvmToolchain(21)
}

tasks {
    test {
        useJUnitPlatform()
    }
}

intellijPlatform {
    autoReload = true
    buildSearchableOptions = false

    pluginConfiguration {
        id = providers.gradleProperty("pluginGroup")
        name = providers.gradleProperty("pluginName")
        version = providers.gradleProperty("pluginVersion")
        description = """
            JetBrains Platform support for ok-script projects. Provides language-key,
            OCR pattern, template and effect-ID completion, documentation, inline hints,
            and a searchable template gallery for Python projects.
        """.trimIndent()
        ideaVersion {
            sinceBuild = "251"
            untilBuild = provider { null }
        }
        vendor {
            name = "AliceJump"
            url = "https://github.com/AliceJump/ok-script-toolkit-jetbrains"
        }
    }

    signing {
        privateKey = providers.environmentVariable("PRIVATE_KEY")
        password = providers.environmentVariable("PRIVATE_KEY_PASSWORD")
        certificateChain = providers.environmentVariable("CERTIFICATE_CHAIN")
    }

    pluginVerification {
        ides {
            current()
        }
    }
}
