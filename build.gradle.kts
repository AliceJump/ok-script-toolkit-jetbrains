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

// 用 Sync 而不是 Copy：`Copy` **不会删除源里已移除的文件**，于是从 python/ 删掉的脚本
// 会一直留在 build/resources/main/python/ 里继续被打进 jar。
// 实测（2026-09-20）：删掉 python/run_task.py 后重跑构建，产物里它仍在。
// Sync 会把目标目录同步成源的样子（多出来的删掉）。
// 目标目录专用于这些脚本，不影响 processResources 写进去的 messages/ 等。
val copyPython = tasks.register<Sync>("copyPythonScripts") {
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
        // 原子写的临时产物：`.tmp` 是写一半的中间文件，`.bak` 是旧版实现留在
        // python 目录旁边的备份。两者都是运行时垃圾，不该进 jar（AGENT.md 打包红线）。
        // 新版实现已把备份移到系统临时目录，这条是针对历史残留与手滑的兜底。
        exclude("**/*.ok-script-toolkit.tmp")
        exclude("**/*.bak")
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

// ---------------------------------------------------------------------------
// 测试临时文件统一落盘
// ---------------------------------------------------------------------------
// 背景：测试原先各自往系统临时目录**根部**写 `ok-xxx` 目录且从不清理，
// 三天堆了 2671 个目录 + 1194 个 `.bak`。现在三端（Kotlin / Python / Node）
// 统一落到系统临时目录下的固定子目录，跑完统一删除。
//
// 注意这里刻意**不用** `doFirst { }` / `doLast { }` 闭包：
// 本项目开了 org.gradle.configuration-cache，闭包捕获建脚本对象会序列化失败
// （见上文 copyPythonScripts 的注释，之前就被这个坑过一次）。
// 改成两个声明式的 Delete 任务，零闭包捕获，configuration cache 绝对安全。
val testTmpRoot = File(System.getProperty("java.io.tmpdir"), "ok-script-toolkit-tests")

// 开跑前清一次：上一次构建被强杀 / 崩掉时留下的残渣。
val cleanTestTmpBefore = tasks.register<Delete>("cleanTestTmpBefore") {
    description = "删除上一轮遗留的统一测试临时根"
    delete(testTmpRoot)
}

// 跑完统一删除。挂 finalizedBy 而不是 doLast —— doLast 在测试失败时不会执行，
// 而"失败之后残留"恰恰是最需要清干净的情况。
val cleanTestTmpAfter = tasks.register<Delete>("cleanTestTmpAfter") {
    description = "测试结束后统一删除测试临时根（无论测试成功或失败）"
    delete(testTmpRoot)
}

tasks {
    test {
        useJUnitPlatform()

        // Kotlin 侧（TestTmp）读系统属性；TestTmp 会在其下用 kt/ 子目录落盘
        systemProperty("ok.test.tmp.root", testTmpRoot.absolutePath)
        // 让生产代码 AtomicWritePaths 的 .bak 备份也落进同一个根，避免漏到系统临时目录
        systemProperty("ok-script-toolkit.backup.dir", File(testTmpRoot, "kt/backup").absolutePath)
        // Python / Node 测试脚本读环境变量，各自用 py/ 、js/ 子目录落盘
        environment("OK_TEST_TMP_ROOT", testTmpRoot.absolutePath)

        dependsOn(cleanTestTmpBefore)
        finalizedBy(cleanTestTmpAfter)
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
