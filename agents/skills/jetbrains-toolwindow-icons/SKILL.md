---
name: jetbrains-toolwindow-icons
description: JetBrains 插件工具窗（toolWindow）图标的 New UI 官方规范：四件套命名、尺寸、描边、色板值与变色机制。新增/修改工具窗图标时必须遵循本规范。
---

# JetBrains 工具窗图标规范

适用于本仓库 `src/main/resources/icons/` 下所有工具窗图标，以及 plugin.xml 中
`<toolWindow icon="...">` 的引用方式。依据：IntelliJ Platform SDK 文档
（plugins.jetbrains.com/docs/intellij/icons.html）、intellij-community 源码
（`platform/icons/src/expui/toolwindows/`、`platform/util/ui/src/com/intellij/ui/icons/stroke.kt`、
`SquareStripeButtonLook.kt`）与 CheckStyle-IDEA、SonarLint 插件先例。

## 四件套命名（缺一不可）

一个名为 `x` 的图标必须提供 4 个变体，放在同一目录：

| 文件 | 尺寸 | 主题 | 用途 |
|---|---|---|---|
| `x@20x20.svg` | 20×20 | light | New UI 默认 |
| `x@20x20_dark.svg` | 20×20 | dark | New UI 默认（深色） |
| `x.svg` | 16×16 | light | Compact 紧凑模式 |
| `x_dark.svg` | 16×16 | dark | Compact 紧凑模式（深色） |

`plugin.xml` 中只写基础名 `icon="/icons/x.svg"`，平台自动挑选正确变体。

## 尺寸与描边

- 20×20：内容留约 2px 边距（有效绘图区 ≈16×16），`stroke-width="1.5"`。
- 16×16：`stroke-width="1"`。
- 圆角收尾：`stroke-linecap/linejoin="round"` 或矩形 `rx`（0.5/0.75/1）。

## 色值（只允许平台调色板内的色）

| 用途 | light | dark |
|---|---|---|
| 默认描边/填充 | `#6C707E` | `#CED0D6` |
| 语义红（如 badge） | `#DB5860` | `#C75450` |
| 语义蓝 | `#3574F0` | `#3574F0` |
| 语义绿 | `#5FB865` | `#5FB865` |
| 语义黄 | `#F0A732`（light 用 #EDA200） | `#FCC75B` |

写法：直接硬编码在 `fill=` / `stroke=` 属性里。

## 变色机制（为什么不能乱用色）

- **调色板色 → 选中态变色**：工具窗按钮选中时，平台 `SquareStripeButtonLook`
  调用 `toStrokeIcon(icon, selectedForeground)` 把调色板内的 fill/stroke 值
  重着色为选中前景色（New UI 下为白色）。**调色板之外的色值"故意不动"**——
  用 `currentColor`、`#000000` 或任意自定义色，选中时图标不会变白，深色下对比度差。
- **`_dark` 变体 → 深色主题默认色**：深色主题的常规显示色来自 `_dark` 文件，
  与选中态变色是两个独立机制，两者都要有。
- `currentColor` 平台 SVG 加载器不处理，禁止使用。

## 反面案例（本仓库曾踩过的坑）

1. 24×24 + stroke 1.6 的图标：不在规范尺寸体系，平台缩放后笔画粗细失真。
2. 只有单个 svg 无变体：深色主题下发黑、Compact 模式下发虚。
3. `stroke="#000000"`：深色主题不可见。

## 参考实现

- 官方内置：intellij-community `platform/icons/src/expui/toolwindows/`
  （messages/commit/notifications 四件套原文）
- 第三方正例：CheckStyle-IDEA（`checkstyle@20x20.svg` stroke-width 1.171875 按
  1.25 倍画布等比）、SonarLint（工具窗图标 + 状态 badge，红 badge 双主题换色）
