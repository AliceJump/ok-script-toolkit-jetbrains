package com.alicejump.okscripttoolkit.core

import java.io.File
import java.util.Properties

/**
 * 测试用的 `messages/` 目录定位与语言包读取。
 *
 * 抽出来是因为有两处测试要读它（[BundleParityTest] 与 [BundleCoverageTest]），
 * 而这段路径解析**不是**显然的一行：Gradle 跑测试时工作目录是项目根（`jetbrains/`），
 * 但从 IDE 里单独跑测试类时工作目录可能是仓库根或模块根 —— 写死
 * `File("src/main/resources/messages")` 会变成"本地能过、CI 挂了"的经典陷阱。
 */
internal object TestMessages {

    /** 英文基础包 = 权威键集（其它语言只是它的翻译）。 */
    const val BASE_NAME = "OkScriptToolkitBundle.properties"

    /**
     * 定位 messages 目录：从当前工作目录逐级往上找。
     *
     * 完全找不到时**显式失败**，而不是返回空列表把断言变成恒真。
     */
    val dir: File by lazy {
        val relative = "src/main/resources/messages"
        var current: File? = File("").absoluteFile
        val candidates = mutableListOf<File>()
        while (current != null) {
            candidates += File(current, relative)
            candidates += File(current, "jetbrains/$relative")
            current = current.parentFile
        }
        candidates.firstOrNull { it.isDirectory }
            ?: throw AssertionError(
                "找不到 messages 目录。已尝试：\n" + candidates.joinToString("\n") { "  ${it.path}" },
            )
    }

    fun bundleNames(): List<String> = dir
        .listFiles { f -> f.name.startsWith("OkScriptToolkitBundle") && f.name.endsWith(".properties") }
        ?.map { it.name }
        ?.sorted()
        .orEmpty()

    fun loadProperties(name: String): Properties {
        val properties = Properties()
        File(dir, name).inputStream().use { properties.load(it) }
        return properties
    }

    fun loadKeys(name: String): Set<String> = loadProperties(name).stringPropertyNames()
}
