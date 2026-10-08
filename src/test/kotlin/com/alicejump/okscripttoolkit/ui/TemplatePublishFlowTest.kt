package com.alicejump.okscripttoolkit.ui

import com.alicejump.okscripttoolkit.TestTmp
import kotlin.test.Test
import kotlin.test.assertEquals

class TemplatePublishFlowTest {
    @Test fun `rename checks scan project imports and exclude dependency copies`() {
        val root = TestTmp.create("ok-enum-refs").toPath()
        fun write(file: String, text: String) {
            val target = root.resolve(file)
            java.nio.file.Files.createDirectories(target.parent)
            java.nio.file.Files.writeString(target, text)
        }
        write("src/task.py", "from src.labels import OldLabels as fL")
        write("src/unrelated.py", "from src.labels import OtherLabels")
        write(".venv/lib/dependency.py", "from src.labels import OldLabels")
        write("node_modules/sample.py", "from src.labels import OldLabels")
        assertEquals(listOf("src/task.py"), scanEnumReferences(root, "OldLabels"))
        assertEquals(emptyList(), scanEnumReferences(root, ""))
    }
}
