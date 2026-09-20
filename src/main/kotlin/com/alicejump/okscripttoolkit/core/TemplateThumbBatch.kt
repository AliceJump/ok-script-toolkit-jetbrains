package com.alicejump.okscripttoolkit.core

/**
 * 缩略图请求的**分组**：同一张原图只解码一次。
 *
 * 为什么需要它：缩略图是"从原图上裁 bbox 再缩放"，而**一次裁剪必须先解码整张原图**。
 * 实测真实项目里"每张原图的模板数"差别很大 ——
 * ok-wuthering-waves 281 模板 / 156 图（1.8:1），而 ok-end-field **276 模板 / 16 图（17:1）**。
 * 后者若按模板逐个处理，会把同一张 2560×1440 的原图**解码 17 次**。
 *
 * **为什么是"分组"而不是"缓存解码结果"**：一张解码后的原图约 15MB
 * （2560×1440 ARGB），缓存几张就把堆吃掉了。按图分组是"解一张 → 立刻裁完这一组的全部
 * → 释放"，任意时刻只持有一张。
 *
 * 与 VS Code 侧 `warmCropCache` 的"按图分组 + 一次解码多张裁剪"对应。
 */
object TemplateThumbBatch {

    /** 同一张原图上的一组请求。 */
    data class Group<T, K>(
        /** 原图的**标识**（`Path` / `String` 都行 —— 只用来判等，所以不必转字符串） */
        val imagePath: K,
        /** 该原图上要裁的项 */
        val items: List<T>,
    )

    /**
     * 按源图分组。
     *
     * **保持首次出现的顺序**（组与组之间按组内**首个**项的出现顺序排，组内也保持原顺序）。
     *
     * ⚠️ 分组**会改变填充顺序**：某张图上的所有缩略图会在轮到它那组时一次性全部出现，
     * 于是"排在第 100 位、但与第 5 位共用一张图"的那张会**提前**填上。
     * 这是分组的代价，换来的是同一张原图只解码一次（实测最高省 17×）。
     * 要严格按卡片顺序出现，就只能缓存解码后的原图 —— 而一张 2560×1440 的 ARGB 是 ~15MB，
     * 那个代价更大（见本文件的类注释）。
     *
     * @param imagePathOf 取某一项的源图标识。**对键类型泛型**是刻意的：
     *   `FeatureTemplate.imagePath` 是 `Path`，转成字符串再判等既多余又容易踩
     *   分隔符/大小写不一致的坑。
     */
    fun <T, K> groupByImage(items: List<T>, imagePathOf: (T) -> K): List<Group<T, K>> {
        val order = LinkedHashMap<K, MutableList<T>>()
        for (item in items) {
            order.getOrPut(imagePathOf(item)) { mutableListOf() }.add(item)
        }
        return order.map { (key, group) -> Group(key, group) }
    }
}
