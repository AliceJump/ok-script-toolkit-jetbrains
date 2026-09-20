package com.alicejump.okscripttoolkit.core

/**
 * 缩略图磁盘缓存的**键**（纯函数，不碰文件系统）。
 *
 * 键 = **原图内容 hash + bbox + 目标高度**。三项都不能少：
 *
 * | 组成 | 少了会怎样 |
 * |---|---|
 * | 内容 hash | 同一路径换了图，键不变 → **复用旧缩略图**（面板显示的图与点开的图不一致） |
 * | bbox | 同一张原图上不同模板的裁剪框不同 → 互相串图 |
 * | 目标高度 | 改了预览尺寸后仍读到旧尺寸的图 → 模糊或裁切 |
 *
 * ⚠️ **用内容 hash 而不是文件路径**：父仓踩过这个坑（`src/pngCrop.ts` 的注释记着），
 * 表现是"面板里看到的图和点开的不一致"—— 因为路径没变、内容变了，缓存却命中了。
 *
 * ⚠️ **目标高度进键是刻意的**：父仓的 `THUMB_HEIGHT` 从 96 改成别的值时，
 * 旧缓存必须自然失效，而不是读到按旧高度渲染的图。
 *
 * 与 VS Code 侧 `pngCrop.thumbFileName()` 对应（那里是 `hash|bbox|height` 直接拼成文件名）。
 */
object TemplateThumbCacheKey {

    /** bbox 参与键的写法：`x,y,w,h`（顺序固定，不能改成集合）。 */
    fun bboxToken(bbox: IntArray): String = bbox.joinToString(",")

    /**
     * 缓存键。**用 `|` 分隔**（不是 `-` 或 `_`）：hash 是 hex、bbox 与高度是数字，
     * 而 `|` 不会出现在任何一项里，所以拼接不会产生歧义（`1|23` 与 `12|3` 不会撞）。
     */
    fun keyOf(contentHash: String, bbox: IntArray, targetHeight: Int): String =
        "$contentHash|${bboxToken(bbox)}|$targetHeight"

    /**
     * 磁盘文件名。键里只有 hex / 数字 / `,` / `|`，`|` 在多数文件系统上合法但难看，
     * 所以换成 `_` —— **此时已无歧义**（hex 与数字都不含 `_`）。
     */
    fun fileNameOf(contentHash: String, bbox: IntArray, targetHeight: Int): String =
        "${keyOf(contentHash, bbox, targetHeight).replace('|', '_')}.png"
}
