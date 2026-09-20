package com.alicejump.okscripttoolkit.core

import java.io.File
import java.util.Properties

/**
 * 测试用的 `messages/` 目录定位与语言包读取。
 *
 * 抽出来是因为有两处测试要读它（[BundleParityTest] 与 [BundleCoverageTest]）。
 * 路径定位本身在 [TestRepoLayout] —— 那个"逐级上溯"的逻辑现在有三个测试要用
 * （多了一个扫 `src/main/kotlin` 源码的），所以挪出去共用。
 */
internal object TestMessages {

    /** 英文基础包 = 权威键集（其它语言只是它的翻译）。 */
    const val BASE_NAME = "OkScriptToolkitBundle.properties"

    /** 定位 messages 目录。完全找不到时**显式失败**，而不是返回空列表把断言变成恒真。 */
    val dir: File by lazy { TestRepoLayout.locate("src/main/resources/messages") }

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
