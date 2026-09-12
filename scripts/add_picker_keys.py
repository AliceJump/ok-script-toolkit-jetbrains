"""为效果多选器补齐六语文案（幂等：已存在的键不会重复追加）。"""

import io

KEYS = {
    "en": {
        "characterManager.picker.allCategories": "All categories",
        "characterManager.picker.selected": "{0} selected",
        "characterManager.picker.idsHint": "Tick to select - multiple selection is allowed.",
        "characterManager.picker.selectedIds": "Selected: {0}",
        "characterManager.picker.empty": "No effect selected yet.",
        "characterManager.picker.noMatch": "No effect matches the filter.",
        "characterManager.picker.hint": "Tick the effects you need, then tweak their parameters on the right.",
        "characterManager.picker.value": "Value",
        "characterManager.picker.duration": "Duration",
        "characterManager.picker.target": "Target",
        "characterManager.picker.count": "Count",
        "characterManager.fieldBaseEffects": "Base effects",
    },
    "zh_CN": {
        "characterManager.picker.allCategories": "全部分类",
        "characterManager.picker.selected": "已选 {0} 个",
        "characterManager.picker.idsHint": "勾选即生效，支持多选。",
        "characterManager.picker.selectedIds": "已选：{0}",
        "characterManager.picker.empty": "还没有选中任何效果。",
        "characterManager.picker.noMatch": "没有符合筛选条件的效果。",
        "characterManager.picker.hint": "左侧勾选需要的效果，右侧调整各自参数。",
        "characterManager.picker.value": "数值",
        "characterManager.picker.duration": "持续",
        "characterManager.picker.target": "目标",
        "characterManager.picker.count": "层数",
        "characterManager.fieldBaseEffects": "基础效果",
    },
    "zh_TW": {
        "characterManager.picker.allCategories": "全部分類",
        "characterManager.picker.selected": "已選 {0} 個",
        "characterManager.picker.idsHint": "勾選即生效，支援多選。",
        "characterManager.picker.selectedIds": "已選：{0}",
        "characterManager.picker.empty": "尚未選取任何效果。",
        "characterManager.picker.noMatch": "沒有符合篩選條件的效果。",
        "characterManager.picker.hint": "左側勾選需要的效果，右側調整各自參數。",
        "characterManager.picker.value": "數值",
        "characterManager.picker.duration": "持續",
        "characterManager.picker.target": "目標",
        "characterManager.picker.count": "層數",
        "characterManager.fieldBaseEffects": "基礎效果",
    },
    "ja": {
        "characterManager.picker.allCategories": "すべてのカテゴリ",
        "characterManager.picker.selected": "{0} 件選択中",
        "characterManager.picker.idsHint": "チェックで選択できます。複数選択可。",
        "characterManager.picker.selectedIds": "選択中：{0}",
        "characterManager.picker.empty": "効果が選択されていません。",
        "characterManager.picker.noMatch": "条件に一致する効果がありません。",
        "characterManager.picker.hint": "左で効果を選び、右でパラメーターを調整します。",
        "characterManager.picker.value": "値",
        "characterManager.picker.duration": "持続",
        "characterManager.picker.target": "対象",
        "characterManager.picker.count": "回数",
        "characterManager.fieldBaseEffects": "基本効果",
    },
    "ko": {
        "characterManager.picker.allCategories": "모든 분류",
        "characterManager.picker.selected": "{0}개 선택됨",
        "characterManager.picker.idsHint": "체크하면 선택됩니다. 여러 개 선택할 수 있습니다.",
        "characterManager.picker.selectedIds": "선택됨: {0}",
        "characterManager.picker.empty": "선택한 효과가 없습니다.",
        "characterManager.picker.noMatch": "조건에 맞는 효과가 없습니다.",
        "characterManager.picker.hint": "왼쪽에서 효과를 고르고 오른쪽에서 매개변수를 조정하세요.",
        "characterManager.picker.value": "값",
        "characterManager.picker.duration": "지속",
        "characterManager.picker.target": "대상",
        "characterManager.picker.count": "횟수",
        "characterManager.fieldBaseEffects": "기본 효과",
    },
    "es": {
        "characterManager.picker.allCategories": "Todas las categorías",
        "characterManager.picker.selected": "{0} seleccionados",
        "characterManager.picker.idsHint": "Marca para seleccionar. Se permite selección múltiple.",
        "characterManager.picker.selectedIds": "Seleccionados: {0}",
        "characterManager.picker.empty": "Todavía no hay efectos seleccionados.",
        "characterManager.picker.noMatch": "Ningún efecto coincide con el filtro.",
        "characterManager.picker.hint": "Marca los efectos a la izquierda y ajusta sus parámetros a la derecha.",
        "characterManager.picker.value": "Valor",
        "characterManager.picker.duration": "Duración",
        "characterManager.picker.target": "Objetivo",
        "characterManager.picker.count": "Acumulaciones",
        "characterManager.fieldBaseEffects": "Efectos base",
    },
}

FILES = {
    "en": "src/main/resources/messages/OkScriptToolkitBundle.properties",
    "zh_CN": "src/main/resources/messages/OkScriptToolkitBundle_zh_CN.properties",
    "zh_TW": "src/main/resources/messages/OkScriptToolkitBundle_zh_TW.properties",
    "ja": "src/main/resources/messages/OkScriptToolkitBundle_ja.properties",
    "ko": "src/main/resources/messages/OkScriptToolkitBundle_ko.properties",
    "es": "src/main/resources/messages/OkScriptToolkitBundle_es.properties",
}

for lang, path in FILES.items():
    with io.open(path, encoding="utf-8") as f:
        lines = f.read().splitlines()
    existing = {l.split("=", 1)[0] for l in lines if "=" in l and not l.lstrip().startswith("#")}
    added = 0
    out = list(lines)
    for key, value in KEYS[lang].items():
        if key in existing:
            continue
        out.append("%s=%s" % (key, value))
        added += 1
    with io.open(path, "w", encoding="utf-8", newline="\n") as f:
        f.write("\n".join(out) + "\n")
    print("%s +%d" % (lang, added))
