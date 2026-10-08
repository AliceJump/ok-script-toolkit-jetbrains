# 子仓库与主仓库功能对齐记录

[简体中文](parity-review.md) | [English](parity-review.en.md)

复核日期：2026-10-08。主仓基线 `aea8b14`、子仓 `main` `9eb4992`，均为 `1.23.0`。本轮从 [PR #28](https://github.com/AliceJump/ok-script-toolkit-jetbrains/pull/28) 的 `0e846f0` 继续补齐差异，同时调整主仓。

完整功能矩阵、操作契约、源码入口和验证状态统一维护在配套主仓 PR 分支的 [功能与文档对齐表](https://github.com/AliceJump/ok-script-toolkit/blob/codex/complete-feature-parity/docs/feature-parity.md)，合并后可从主仓 `main` 查看。

## 已补齐的差异

- 运行时 Template 卡片保留真实图片、bbox 和模板别名；恢复插入、复制、来源查看、搜索和自动刷新。宽屏与侧栏共用三模式预览。
- 统一标注管理接入原图查看、Template 交换、删除、临时截图拖入、搜索及外部变更刷新。窄窗口工具栏换行，不裁掉操作入口；旧异步回调不会污染新模型。
- 发布恢复枚举路径、类名、框架后备路径和引用检查。先配置后写入；位置拒绝覆盖或失败时不继续模板写入。Position 路径来源和覆盖重置与主仓一致。
- 图片编号同时预留三份工作文件的记录；临时截图发送使用实际项目根。原图删除联动清理 Template / Rect / Point，失败时恢复已修改文件；模板读取失败时在清理 Rect / Point 前直接退出，避免多余写入和通知。
- 发布文案和资源预览标题使用外部六语言资源，快捷键与主仓一致；清理未注册的旧文本窗口及发布路径。
- 独立 CI 固定主仓 `aea8b14`，包含当前 Position Schema；共享 Python 和 Schema 与主仓产物一致。

VS Code 标注改动即保存；子仓对话框点击保存时写回，取消放弃当前编辑。宿主原生布局可不同，资源来源、表达式、写入范围和发布含义一致。业务项目负责加载发布文件；插件不建立业务迁移或应用回执机制。

## 验证与交付状态

`gradlew test buildPlugin verifyPluginStructure verifyPluginConfiguration` 通过，485 个测试，零失败、错误、跳过。主仓完整测试和 VSIX 打包通过。两端产物的 13 个 Python 脚本与 Schema 逐字节相同，语言资源齐全，不含 Agent、测试或开发说明文件。

本轮改动通过 PR 交付，合并前不计入已发布版本。历史 `0e846f0` CI 成功；旧审阅覆盖为 `a9fd35f`，此前主动复审被限流，不代表新提交已审阅。主仓 PR 的 gitlink 指向本轮子仓提交；先合并子仓 PR #28，再确认或更新 gitlink 到子仓 `main` 可达的提交，并验证主仓 CI 后合并主仓。

没有执行真实 IDE、游戏截图、业务项目运行验收或完整 Plugin Verifier API 兼容性检查。历史 [设计对齐参考](design-parity.md) 的旧任务清单不作为当前验收依据。
