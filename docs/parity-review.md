# 子仓库与主仓库功能差异审查（2026-09-06）

对照基准：主仓库 VSCode 扩展 v0.6.11（含 assetPack/saveToAssets 最新重构）、
子仓库 JetBrains 插件 v0.6.11（含本轮 UI 整治）。下次审查请覆盖本表并更新状态。

## 结论概览

编辑器语言功能（补全/hover/inlay）已完全对齐；三个工具面板主体对齐但存在
细节缺口与个别真 bug；完全缺失的大块是 saveToAssets 导出、截图采集、角色 CRUD。

## ✅ 已对齐

- 编辑器：4 类引用识别（lang/模板/效果/OCR）、5 场景补全（模板置顶、效果分类排序）、
  hover（全语言表格/缩略图预览/OCR 运行时说明）、行内提示 + tooltip、JSON 侧效果提示
- 模板画廊：响应式网格、插入/复制/红框标注原图（ok_templates 反查 30s TTL）
- 任务启动：AST 列任务、schema 探测与缓存、run_task.py 注入、timeout、taskkill、
  暂停/恢复、输出区、配置持久化
- 参数控件：bool/数字/下拉/多选/级联下拉/条件序列 JSON/configGroups 分组
- 角色面板（只读部分）、素材库基础操作、标注基础操作、数据源、6 语言 UI、设置项

## ⚠️ 部分实现（真 bug / 缺口）

| # | 问题 | 严重度 | 状态 |
|---|---|---|---|
| 1 | list 类型参数落入 else 分支变 JTextField，数组被存成字符串（buildTaskConfig） | 高 | ✅ 已修 |
| 2 | inferEffectIds/parseEffectTermMap 已定义但 load() 从未调用，inferred 恒 false | 中 | 待修 |
| 3 | 大部分 issue 构造未填 source，跳转 UI 有、数据没有 | 中 | ✅ 已修 |
| 4 | schema 缓存命中时不跑 parse_config_tasks，新增任务刷新后不出现；probeTaskSchemas 未传设置的 poDirectory（写死 i18n） | 中 | ✅ 已修 |
| 5 | 任务配置 extraArgs/env 能读不应用 | 低 | 待办 |
| 6 | 角色面板状态栏硬编码英文 | 低 | 待办 |
| 7 | 无文件 watcher（数据变化需手动刷新；主仓库 watcher+300ms 防抖） | 低 | 待办 |
| 8 | 工具窗关闭即杀任务进程（主仓库任务后台存续） | 低 | 待办 |
| 9 | 标注编辑器缺 undo/redo、copy/paste、8 向 resize、拖动移框、缩放平移（仅 fit 不放大）、跨图导航、双击数值编辑、改动即存 | 中 | 待办 |
| 10 | 素材导入仅单文件，无 nextImageName 自动编号；drop_down/级联本地化标签（option_labels）未用；字段描述未渲染 | 低 | 待办 |

## ❌ 完全缺失（主仓库独有，按建议优先级）

1. ✅ saveToAssets 打包导出：素材面板导出按钮（目标 assets/ok_tasks/assets 二选一、
   可选 LabelEnum.py），Kotlin 实现 bin-packing 多页合成（尺寸分组+重叠检测+白底
   画布原坐标粘贴）+ COCO 重写（分类清理）+ Task.Backgroundable 进度
2. ✅ 游戏窗口截图采集：素材面板新增截图按钮（probe 自动探测窗口配置，
   失败回退手输正则；capture_game_window.py 截图并自动注册进 COCO；
   PythonScriptLocator 解压白名单扩到 5 个脚本）
3. ◐ 角色技能 CRUD：已交付（添加/编辑/删除技能，原子写入+备份，同步技能锁定；
   effects.py 加分类/效果、强化组编辑待后续）
4. ✅ 角色头像：表格头像列（avatarTemplateRegex 匹配模板 -> bbox 裁剪 24px，居中适配）
5. sub_configs 子配置树（折叠树 + boolean 条件显隐 + groupSelector；schema 已返回，纯 UI 缺失）
6. 标注快捷键配置 annotationKeybindings（依赖标注编辑器进阶交互）
7. 次要：编辑器大画廊双入口、任务卡片式 UI、lastPythonEditor 跟踪、注释面板命令

## 行动顺序

1. 修 4 个真 bug（上表 1-4）
2. 截图采集 UI（脚本已打包）
3. saveToAssets 导出
4. 角色 CRUD + 头像 + 术语推断
5. 标注编辑器进阶交互 + sub_configs 树
