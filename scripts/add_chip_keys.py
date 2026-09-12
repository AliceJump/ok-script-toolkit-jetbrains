"""效果胶囊 / 触发模式徽标用到的文案（幂等）。"""

import io

KEYS = {
    "en": {
        "characterManager.detailNone": "none",
        "characterManager.detailInferred": "Inferred from the trigger text",
        "characterManager.detailPulse": "[pulse]",
        "characterManager.detailTriggerEffects": "Trigger:",
        "characterManager.detailOutputEffects": "Output:",
        "characterManager.triggerModeAllHint": "ALL - every listed effect is required",
        "characterManager.triggerModeAnyHint": "ANY - any one of the listed effects is enough",
    },
    "zh_CN": {
        "characterManager.detailNone": "无",
        "characterManager.detailInferred": "由触发文本推测而来",
        "characterManager.detailPulse": "［脉冲］",
        "characterManager.detailTriggerEffects": "触发依赖：",
        "characterManager.detailOutputEffects": "产出效果：",
        "characterManager.triggerModeAllHint": "ALL —— 列出的效果需全部满足",
        "characterManager.triggerModeAnyHint": "ANY —— 列出的效果满足任一即可",
    },
    "zh_TW": {
        "characterManager.detailNone": "無",
        "characterManager.detailInferred": "由觸發文本推測而來",
        "characterManager.detailPulse": "［脈衝］",
        "characterManager.detailTriggerEffects": "觸發依賴：",
        "characterManager.detailOutputEffects": "產出效果：",
        "characterManager.triggerModeAllHint": "ALL —— 列出的效果需全部滿足",
        "characterManager.triggerModeAnyHint": "ANY —— 列出的效果滿足任一即可",
    },
    "ja": {
        "characterManager.detailNone": "なし",
        "characterManager.detailInferred": "トリガー文から推測",
        "characterManager.detailPulse": "［パルス］",
        "characterManager.detailTriggerEffects": "トリガー依存：",
        "characterManager.detailOutputEffects": "産出効果：",
        "characterManager.triggerModeAllHint": "ALL —— すべての効果が必要",
        "characterManager.triggerModeAnyHint": "ANY —— いずれか 1 つで十分",
    },
    "ko": {
        "characterManager.detailNone": "없음",
        "characterManager.detailInferred": "트리거 텍스트에서 추론됨",
        "characterManager.detailPulse": "[펄스]",
        "characterManager.detailTriggerEffects": "트리거 의존:",
        "characterManager.detailOutputEffects": "산출 효과:",
        "characterManager.triggerModeAllHint": "ALL - 나열된 효과가 모두 필요",
        "characterManager.triggerModeAnyHint": "ANY - 나열된 효과 중 하나면 충분",
    },
    "es": {
        "characterManager.detailNone": "ninguno",
        "characterManager.detailInferred": "Inferido del texto de activación",
        "characterManager.detailPulse": "[pulso]",
        "characterManager.detailTriggerEffects": "Dependencias:",
        "characterManager.detailOutputEffects": "Efectos de salida:",
        "characterManager.triggerModeAllHint": "ALL - se requieren todos los efectos listados",
        "characterManager.triggerModeAnyHint": "ANY - basta con uno de los efectos listados",
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
