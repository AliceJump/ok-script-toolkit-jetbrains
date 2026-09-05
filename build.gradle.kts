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
val copyPython = tasks.register<Copy>("copyPythonScripts") {
    from(pythonDir) {
        include("**/*.py")
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
    jvmToolchain(23)
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
