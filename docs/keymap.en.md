# Keyboard shortcuts

| Action | Windows / Linux (`$default`) | macOS (`Mac OS X`) | Notes |
| --- | --- | --- | --- |
| Capture to Inbox (`CaptureToInbox`) | `Ctrl+Alt+Shift+O` | `⌘⌥⇧O` | Take a game screenshot into Resources · Inbox |
| Resources · Published (`ShowUnifiedResources`) | `Ctrl+Alt+T` | `⌘⌥T` | Existing shortcut, unchanged (see known conflict below) |

## Why not `Ctrl+Alt+S`

In the default IntelliJ keymap `Ctrl+Alt+S` is **Settings** (it is also taken in the Emacs keymap).
The VS Code extension captures with `Ctrl+Alt+S`; the JetBrains plugin uses `Ctrl+Alt+Shift+O` (O for ok-script).
The old "Screenshot into Drafts" action is kept but no longer has a shortcut.

## How it was checked

`scripts/check_keymap_conflicts.py` scans keymap files and plugin `keyboard-shortcut` declarations in **every** jar
of an IDE installation (single keystrokes; two-stroke sequences are ignored):

```bash
python3 scripts/check_keymap_conflicts.py <PyCharm 2025.1 dir> "control alt shift O" "meta alt shift O"
```

Result on 2025.1.1.1: both `control alt shift O` and `meta alt shift O` are **FREE**

The Windows system-wide `Ctrl+Alt+S` (works while a game holds focus) also lands in the **Resources inbox**, matching VS Code #41, without opening a tool window.
(`$default`, Mac OS X, Mac OS X 10.5+, GNOME, KDE, XWin, Emacs, Sublime Text, macOS System Shortcuts and all bundled plugins).
Eclipse / VS Code / NetBeans keymaps are separate downloadable plugins and were not checked locally.

## Known conflict (existing, not changed here)

- `Ctrl+Alt+T` (resource preview) conflicts with **Surround With** in `$default` / XWin. Worth rebinding later, after confirmation.
