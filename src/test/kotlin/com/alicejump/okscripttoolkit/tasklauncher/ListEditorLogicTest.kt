package com.alicejump.okscripttoolkit.tasklauncher

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ListEditorLogicTest {

    @Test
    fun `labelFor prefers localized label and falls back to raw value`() {
        val available = listOf("ATTACK", "DEFEND", "100")
        val labels = listOf("攻击", "防御")

        assertEquals("攻击", ListEditorComponent.labelFor("ATTACK", available, labels))
        // 标签按索引对应（labels[index] ?? value）；末尾选项无标签时回退原值
        assertEquals("防御", ListEditorComponent.labelFor("DEFEND", available, labels))
        assertEquals("100", ListEditorComponent.labelFor("100", available, labels))
        // 不在可用集中的既有项原样显示
        assertEquals("custom", ListEditorComponent.labelFor("custom", available, labels))
        // 无 options_available：原样显示
        assertEquals("ATTACK", ListEditorComponent.labelFor("ATTACK", null, labels))
    }

    @Test
    fun `labelFor compares by string form so numeric options match`() {
        val available = listOf(100, 200)
        assertEquals("100", ListEditorComponent.labelFor("100", available, emptyList<Any>()))
        assertEquals("200", ListEditorComponent.labelFor(200, available, emptyList<Any>()))
    }

    @Test
    fun `availableOptions parses type meta strictly`() {
        val meta = mapOf<String, Any>(
            "options_available" to listOf("a", "b"),
            "options_available_labels" to listOf("甲", "乙"),
            "allow_duplication" to true,
        )
        val (available, labels, allowDup) = ListEditorComponent.availableOptions(meta)
        assertEquals(listOf("a", "b"), available)
        assertEquals(listOf("甲", "乙"), labels)
        assertTrue(allowDup)

        // allow_duplication 非布尔 true 时不允许重复（对齐 JS === true）
        val stringDup = mapOf<String, Any>(
            "options_available" to listOf("a"),
            "allow_duplication" to "true",
        )
        assertFalse(ListEditorComponent.availableOptions(stringDup).third)

        assertNull(ListEditorComponent.availableOptions(null).first)
    }

    @Test
    fun `availableOptions treats empty options_available as present`() {
        // 空 [] 也是"存在可用集"：双栏模式（对齐 JS Array.isArray 语义）
        val meta = mapOf<String, Any>("options_available" to emptyList<Any>())
        val (available, labels, _) = ListEditorComponent.availableOptions(meta)
        assertTrue(available != null && available.isEmpty())
        assertTrue(labels.isEmpty())
    }
}
