# JetBrains 侧设计对齐规格：#9/#10 Swing 移植 + #7 配置接管

> 对应主仓库 PR：#8/#9（运行中心 + 健康度条 + 悬停弹层）、#10（sidebar 规范 + token 化）、
> #7（配置接管 Phase 1-6）。本文件是 Kotlin/Swing 侧的**等价物规格**：
> VS Code 侧靠 CSS token 与 webview，Swing 没有 CSS，逐项给出 IntelliJ 平台等价物。
> 审计结论原稿在父仓本地工作目录 `.workbuddy/design/jetbrains-parity-audit.md`（2026-09-25；
> `.workbuddy/` 不入库、仅本机可见，结论已吸收进本文与代码）。

## 0. 范围

| 主仓库成果 | Kotlin 侧现状 | 本 PR 动作 |
|---|---|---|
| #7 执行器注入（OK_TOOLKIT_GCONFIG + gparams） | **缺失**（唯一真功能差距） | 补齐：probe 解析 → store 持久化 → merge 规则 → env 注入 → gparams 推送 |
| #9 健康度条 + 运行中心 + 任务卡悬停弹出 | statusBar 只有文字状态 | 升级 statusBar 为健康条；详情面板空闲态渲染运行中心；任务行悬停弹出概览 |
| #9 全局配置卡悬停弹窗（gpop） | 无全局配置呈现 | 配置组摘要弹层（只读，遵循「弹出层不带交互按钮」规范） |
| #10 两层可点击语义 + token 化 | 硬编码 JBColor 散落 | 收敛到单点主题对象；chip 已有 styleChip 范式可推广 |

不移植：12 套皮肤/3 种布局切换（ok-ui-lab 专属，Swing 侧遵循 IDE 主题即可）。

## 1. 语义 token → Swing 等价物映射

新建 `tasklauncher/TaskLauncherTheme.kt`（单点收敛，禁止再散落硬编码色）：

| VS Code token | Swing 等价物 | 说明 |
|---|---|---|
| `--ok` | `JBColor(0x369B47, 0x5FAD65)` | 收编现有 COLOR_GOOD |
| `--run` | `JBColor(0x2E7DD1, 0x6CA6E8)` | 运行中（新增；蓝=连接/轮询中） |
| `--warn` | `JBColor(0xB87700, 0xE8A33D)` | 收编 COLOR_WARN |
| `--pause` | `JBColor(0x8A6D00, 0xC9A227)` | 暂停态（与 warn 区分：偏黄褐） |
| `--err` | `JBColor(0xDB3B4B, 0xF26D6D)` | 收编 COLOR_BAD |
| `--trigger`（触发任务） | `JBColor(0x7A5AF8, 0x9B8AFB)` | 收编 COLOR_TRIGGER |
| `--text-primary` | `UIUtil.getLabelForeground()` | 永远走 LAF，不硬编码 |
| `--text-muted` | `JBColor.foreground().darker()` 不用；用 `UIUtil.getLabelDisabledForeground()` | 次要文字 |
| `--bg-control` | 按钮走平台 LAF（JButton 默认） | 真按钮不自绘背景 |
| `--bg-row` | `UIUtil.getPanelBackground()` + hover 切 `UIUtil.getListSelectionBackground(false)` 的淡化版 | 行级可点击区 |
| `--border` | `JBUI.CurrentTheme.CustomFrameDecorations.separatorForeground()` 退化用 `BorderFactory.createLineBorder(JBColor.border())` | |
| `--radius-*` | `JBUI.CurrentTheme.Dialog.interiorWidth()` 或固定 8px 圆角 chip | styleChip 现范式保留 |
| `--font-xs/sm/md/lg` | `JBFont.create(Label.font).deriveFont(...)` 小一号/标准 | 不引入 px 字号 |

**验收**：`grep -n "Color(0x" tasklauncher/*.kt` 只允许出现在 TaskLauncherTheme.kt。

## 2. 两层可点击语义（#10 核心约定）

| 类别 | Swing 落法 | 用于 |
|---|---|---|
| 真按钮 | `JButton`（平台 LAF，自带控件面 + 描边语义） | 工具栏、启动/停止、查看日志、详情动作 |
| 行级可点击区 | `JPanel` 无描边 + 浅底（panelBackground），hover 换 rowHover | 运行中心里的任务行、配置组摘要行 |

规则同 VS Code 侧：**hover 不承担「让用户发现可点击」的职责**——行级区默认就有浅底；
真按钮与行头不可同色同框。

## 3. #9 概念移植

### 3.1 健康度条（rc-health → statusBar 升级）

数据源 `ExecutorState` 字段已齐（status/paused/current/currentIsTrigger/onetimeQueue/controlError）。

```
[● 健康点] [状态文字] [当前任务 chip（current 非空时）] …… [进度条] [查看日志]
```

- 健康点：8px 圆点，颜色 = `--run`（connecting/running 未暂停）、`--pause`（paused）、
  `--err`（controlError 非空）、`--ok`（idle 且 finishMessage 正常）、`--text-muted`（idle 无消息）
- 状态文字：沿用现有 syncRunnerState 的 statusText 组装逻辑
- 当前任务 chip：复用 styleChip 范式（圆角描边小标签），trigger/onetime 用不同描边色

### 3.2 运行中心（rc-queue → 详情面板空闲态）

任务表未选中任何行时，右侧详情不再显示占位文字，改为**运行中心视图**（只读）：

- 「当前任务」区：current 非空显示任务 key + 触发/一次性类别，否则显示「空闲」
- 「执行队列」区：onetimeQueue 逐行列出（行级可点击区样式，点击即选中左侧对应任务行）
- 「触发轮询」区：enabledTriggers 逐行列出 + 勾选态
- 顶部一行小标题 + 健康点，与 statusBar 同源同色

实现：`RunCenterPanel`（内部类或独立文件），`showDetailPlaceholder()` 改为 `showRunCenter()`。

### 3.3 悬停弹层（任务卡 hover → 概览）

- 触发行/一次性行 hover 800ms 后弹 `JBPopup`（只读摘要：显示名、kind、参数已改键数、schema 错误）
- **弹出层只读、不带交互按钮**（用户硬规则）；半透明底 + 圆角（Swing 无 backdrop-filter，
  用 `JBPopup` 默认半透明 + `JBUI.insets` 即可，不追求毛玻璃）
- 点 Tab/切行 1.2s 内抑制弹出（对齐主仓库约定）

### 3.4 全局配置卡悬停弹窗（gpop）

运行中心的配置区分组列出 `globalConfigGroups`（name/displayName/source），
hover 弹出组内字段摘要（displayKey: value 截断），样式同 3.3。

## 4. #7 配置接管 Kotlin 侧实现清单

### 4.1 数据模型（TaskLauncherService.kt）

```kotlin
data class GlobalConfigGroup(
    val name: String,
    val displayName: String? = null,
    val description: String? = null,
    val fields: List<TaskParamField> = emptyList(),   // 与任务字段同构，直接复用
    val source: String? = null,                        // framework | project_store
)
// SchemaProbeResult 增：
val globalConfigGroups: List<GlobalConfigGroup> = emptyList(),
```

- `parseSchemas` 平级新增 `parseGlobalConfigGroups(node)`；probe 与 schema 缓存两条读路径都接上
- schema 缓存写路径无需改（data class 整体序列化自动带上）

### 4.2 持久化（tasks.json ProjectConfig）

```kotlin
data class ProjectConfig(
    val tasks: Map<String, TaskConfig> = emptyMap(),
    val enabledTriggers: List<String> = emptyList(),
    val globalConfigs: Map<String, Map<String, Any?>> = emptyMap(),  // {组名: {键: 值}}
)
```

- `parseTaskConfigStore` 解析 `globalConfigs`
- `TaskConfigMerge.withGlobalConfigs(store, root, snapshots)` —— 照 withTask/withEnabledTriggers
  范式：**只换 globalConfigs，不碰 tasks 与 enabledTriggers**
- `TaskLauncherService.saveGlobalConfigs(snapshots)`：storeLock 整序列上锁 + 回填缓存

### 4.3 物化规则（纯对象，可直测）——`GlobalSnapshotRules.kt`

对齐 consolePanel.ts materializeTaskSnapshot/materializeGlobalSnapshot 语义：

```kotlin
object GlobalSnapshotRules {
    /** 物化一组字段进快照。返回 (新快照, 新增键数)。
     *  existing 为空（首建）→ 全部继承 f.value；
     *  否则（重探针）→ 已有键保留（孤儿键不删），新键取 f.default ?: f.value。 */
    fun materialize(existing: Map<String, Any?>, fields: List<TaskParamField>): Pair<Map<String, Any?>, Int>

    /** 「恢复默认」：全部键取 f.default ?: f.value（不管 existing）。 */
    fun resetToDefaults(existing: Map<String, Any?>, fields: List<TaskParamField>): Map<String, Any?>
}
```

### 4.4 启动注入（ensureExecutor）

```kotlin
val gconfig = taskService.loadTaskConfigs().projects[...]?.globalConfigs.orEmpty()
if (gconfig.isNotEmpty()) {
    env["OK_TOOLKIT_GCONFIG"] = objectMapper.writeValueAsString(gconfig)
}
```

### 4.5 运行中推送（TaskRunnerService）

```kotlin
/** 全局配置快照即时推送（对齐 VS Code 侧 gparams 命令） */
fun pushGlobalParams(json: String): Boolean = sendCommand("gparams $json")
```

调用点：全局配置修改保存后（flush 时）防抖 400ms 推送，`isRunning()` 才推。

### 4.6 UI 呈现（本轮最小可用）

- 运行中心（3.2）列出全局配置组（组名 + source 标签 + 字段数），hover 弹只读摘要（3.4）
- 组级「重置默认」动作延后到下一 PR（本轮先把接管链路打通）

## 5. 测试

- `GlobalSnapshotRulesTest`：首建继承 value / 重探针新键 default / 孤儿键保留 / reset 全 default
- `TaskConfigMergeTest`：withGlobalConfigs 不碰 tasks 与 enabledTriggers；空项目根自动新建
- 纯对象，不依赖 IDE 平台（现有 test 源集已验证可行）
- Bundle：新增键六语言对等（BundleParityTest 约束）——健康点 tooltip、运行中心标题/空态、
  配置区标题、推送失败日志等

## 6. 显式不做

- 12 皮肤/3 布局切换（ok-ui-lab 专属）
- 全局配置的完整编辑表单（与任务参数面板同级复杂度，下一 PR）
- 多账户覆盖（multiAccount）——probe 已透传 JSON，Kotlin 侧本轮只透传不消费
