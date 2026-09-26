package com.alicejump.okscripttoolkit.editor

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.jetbrains.jsonSchema.extension.JsonSchemaFileProvider
import com.jetbrains.jsonSchema.extension.JsonSchemaProviderFactory
import com.jetbrains.jsonSchema.extension.SchemaType

/**
 * 项目约定文件 `ok-script-toolkit.json` 的 JSON Schema 支持。
 *
 * 对齐 VS Code 侧 package.json 的 `jsonValidation`（schemas/ok-script-toolkit.schema.json）：
 * 编辑约定文件时提供补全、悬浮文档与校验 —— 约定文件是手写的，字段拼错会被
 * ProjectConvention.parseFile 的容错静默吞掉、项目约定整体失效，schema 是唯一能
 * 在编辑现场拦住它的手段。schema 与 VS Code 用**同一份**（父仓 schemas/，
 * 由 copyConventionSchema 任务同步进 JAR），保证两端校验口径一致。
 */
class OkConventionSchemaProviderFactory : JsonSchemaProviderFactory {

    override fun getProviders(project: Project): List<JsonSchemaFileProvider> = listOf(PROVIDER)

    private companion object {
        // getResourceFile 走 classloader 定位 JAR 内资源；找不到（未打包 schema 的构建）返回 null，
        // provider 让 isAvailable 继续命中、getSchemaFile 返回 null —— 平台对 null schema 的
        // provider 是跳过而不是抛错，正好实现"没有 schema 就当没有这个功能"。
        private val SCHEMA_FILE: VirtualFile? =
            JsonSchemaProviderFactory.getResourceFile(
                OkConventionSchemaProviderFactory::class.java,
                "/schemas/ok-script-toolkit.schema.json",
            )

        private val PROVIDER = object : JsonSchemaFileProvider {
            // 与 VS Code jsonValidation 的 fileMatch 一致：任意目录下的约定文件都挂 schema
            override fun isAvailable(file: VirtualFile): Boolean = file.name == "ok-script-toolkit.json"

            override fun getName(): String = "ok-script Toolkit"

            override fun getSchemaFile(): VirtualFile? = SCHEMA_FILE

            override fun getSchemaType(): SchemaType = SchemaType.embeddedSchema
        }
    }
}
