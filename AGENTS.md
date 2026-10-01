# 仓库协作指引

- 处理本仓或配套主仓的 PR review 时，先读
  [.agents/skills/ok-script-pr-review/SKILL.md](.agents/skills/ok-script-pr-review/SKILL.md)。
- 本仓是独立 Git 仓库；在主仓中作为 `jetbrains/` 子模块检出。GitHub API、提交和
  CI 检查须使用各自仓库，不能依赖当前工作目录猜测目标。

## 文档语言

- 面向大模型的 Agent 指令、技能与技能配套说明只维护中文单文件，不另建翻译副本。
- 其他文档必须同时维护中文 `名称.md` 与英文 `名称.en.md`，正文按语言分开；修改时同步内容与链接。
- 文件名、代码、API 名称和必要的原始界面文字保留原文。
