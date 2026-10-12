# 快捷键

| 动作 | Windows / Linux（`$default`） | macOS（`Mac OS X`） | 说明 |
| --- | --- | --- | --- |
| 截到收件箱（`CaptureToInbox`） | `Ctrl+Alt+Shift+O` | `⌘⌥⇧O` | 截一张游戏截图到「资源 · 收件箱」 |
| 资源 · 已发布（`ShowUnifiedResources`） | `Ctrl+Alt+T` | `⌘⌥T` | 既有快捷键，未改动（见下方已知冲突） |

## 为什么不是 `Ctrl+Alt+S`

`Ctrl+Alt+S` 在 IntelliJ 默认 keymap 里是 **Settings**（Emacs keymap 里也被占用）。
VS Code 版用 `Ctrl+Alt+S` 截图，JetBrains 版改为 `Ctrl+Alt+Shift+O`（O = ok-script），
旧的「截图到草稿」动作保留但不再绑定快捷键。

## 核对方法

`scripts/check_keymap_conflicts.py` 扫描 IDE 安装目录里**所有** jar 中的 keymap 文件与
插件 `keyboard-shortcut` 声明（单击键，不含双击键序列）：

```bash
python3 scripts/check_keymap_conflicts.py <PyCharm 2025.1 目录> "control alt shift O" "meta alt shift O"
```

2025.1.1.1 的结果：`control alt shift O`、`meta alt shift O` 均为 **FREE**

Windows 系统级 `Ctrl+Alt+S`（游戏前台也能触发）同样写入**资源收件箱**，与 VS Code #41 一致；不打开工具窗口。
（`$default`、Mac OS X、Mac OS X 10.5+、GNOME、KDE、XWin、Emacs、Sublime Text、macOS System Shortcuts 与全部内置插件）。
Eclipse / VS Code / NetBeans 等 keymap 是单独下载的插件，未在本地核对。

## 已知冲突（既有，未在本次改动）

- `Ctrl+Alt+T`（资源预览）与 `$default` / XWin 的 **Surround With** 冲突。建议后续换键，需另行确认。
