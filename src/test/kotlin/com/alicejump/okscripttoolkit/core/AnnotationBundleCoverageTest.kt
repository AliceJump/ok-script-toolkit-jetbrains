package com.alicejump.okscripttoolkit.core

import com.alicejump.okscripttoolkit.LocaleBundles
import java.util.Locale
import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AnnotationBundleCoverageTest {
    @Test fun `unified UI references and formatting are covered in every annotation locale`() {
        val files = TestMessages.dir.listFiles()!!.filter {
            it.name.startsWith("OkScriptToolkitAnnotationBundle") && it.name.endsWith(".properties")
        }
        assertEquals(6, files.size)
        fun read(file: java.io.File) = Properties().also { properties -> file.reader(Charsets.UTF_8).use(properties::load) }
        val base = read(files.single { it.name == "OkScriptToolkitAnnotationBundle.properties" }).stringPropertyNames()
        for (file in files) assertEquals(base, read(file).stringPropertyNames(), file.name)
        val source = TestRepoLayout.locate("src/main/kotlin")
        var references = 0
        val markers = Regex("(?:AnnotationUiBundle\\.message|publishingMessage)\\(\\s*\"([^\"]+)\"")
        for (file in source.walkTopDown().filter { it.isFile && it.extension == "kt" }) {
            val text = file.readText()
            for (match in markers.findAll(text)) {
                references++
                assertTrue(match.groupValues[1] in base, "${file.name}: ${match.groupValues[1]}")
            }
            if (file.name == "PositionPublishFlow.kt") {
                for (match in Regex("\"((?:position|publish|source)\\.[A-Za-z]+)\"").findAll(text)) {
                    assertTrue(match.groupValues[1] in base, match.groupValues[1])
                }
            }
        }
        assertTrue(references > 20)
        for (locale in listOf(Locale.ROOT, Locale.SIMPLIFIED_CHINESE, Locale.TRADITIONAL_CHINESE, Locale.JAPANESE, Locale.KOREAN, Locale.of("es"))) {
            val bundle = LocaleBundles.bundle("messages.OkScriptToolkitAnnotationBundle", locale)
            val formatted = java.text.MessageFormat.format(bundle.getString("position.overwrite"), "example.py")
            assertTrue(formatted.contains("example.py"), locale.toString())
            assertTrue(formatted.contains('\n'), "${locale}: 换行必须按实际字符解码")
            val result = java.text.MessageFormat.format(bundle.getString("publish.packing"), 2, 3)
            assertTrue(result.contains("2/3"), locale.toString())
        }
    }
}
