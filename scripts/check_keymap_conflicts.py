#!/usr/bin/env python3
"""核对插件快捷键与 IDE 自带 keymap / 内置插件快捷键是否冲突。

用法：python3 scripts/check_keymap_conflicts.py <IDE 安装目录> ["control alt shift O" ...]
IDE 目录可用 Gradle 缓存里的 pycharm-professional-2025.1.x（buildPlugin 时下载）。
不带快捷键参数时，读 plugin.xml 里本插件声明的全部 keyboard-shortcut。
"""
import re, sys, zipfile
from pathlib import Path

def norm(k: str) -> str:
    parts = k.lower().split()
    mods = sorted({p.replace('control', 'ctrl') for p in parts[:-1]})
    return ' '.join(mods + [parts[-1]])

def scan(ide: Path):
    used = {}
    for jar in list(ide.glob('lib/**/*.jar')) + list(ide.glob('plugins/**/*.jar')):
        try:
            z = zipfile.ZipFile(jar)
        except Exception:
            continue
        for name in z.namelist():
            if not name.endswith('.xml'):
                continue
            text = z.read(name).decode('utf-8', 'ignore')
            if 'keyboard-shortcut' not in text:
                continue
            for m in re.finditer(r'<keyboard-shortcut[^>]*>', text):
                tag = m.group(0)
                first = re.search(r'first-keystroke="([^"]+)"', tag)
                if not first or 'second-keystroke' in tag:
                    continue
                keymap = re.search(r'keymap="([^"]+)"', tag)
                where = keymap.group(1) if keymap else Path(name).stem
                used.setdefault(norm(first.group(1)), set()).add(where)
    return used

def main():
    ide = Path(sys.argv[1])
    keys = sys.argv[2:]
    if not keys:
        xml = Path(__file__).resolve().parent.parent / 'src/main/resources/META-INF/plugin.xml'
        keys = re.findall(r'first-keystroke="([^"]+)"', xml.read_text(encoding='utf-8'))
    used = scan(ide)
    bad = 0
    for k in keys:
        hits = sorted(used.get(norm(k), []))
        print(f'{k:28} -> {", ".join(hits) if hits else "FREE"}')
        bad += bool(hits)
    sys.exit(1 if bad else 0)

if __name__ == '__main__':
    main()
