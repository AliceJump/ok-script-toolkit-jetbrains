#!/usr/bin/env python3
"""Allow only the four legacy FileEditorManager calls required by IDE 2025.1 support.

2026.3 deprecates synchronous file opening in favor of the experimental
requestOpenFile API, which is unavailable on the oldest supported IDE.
Keep these four known calls visible in the verifier report; reject new API
deprecations rather than hiding all warnings.
"""

from collections import Counter
from pathlib import Path
import sys

PLUGIN_ID = "com.alicejump.okscripttoolkit"
LEGACY_METHOD = "Deprecated method com.intellij.openapi.fileEditor.FileEditorManager.openFile("
EXPECTED_CALLERS = Counter({
    "com.alicejump.okscripttoolkit.ui.TemplatesEditorKt.openTemplateGalleryEditor": 1,
    "com.alicejump.okscripttoolkit.ui.CharacterManagerEditorKt.openCharacterManager": 1,
    "com.alicejump.okscripttoolkit.ui.UnifiedAnnotationEditorKt.openUnifiedAnnotationEditor": 1,
    "com.alicejump.okscripttoolkit.ui.GettingStartedEditorKt.openGettingStarted": 1,
})


def check_report(root: Path) -> int:
    files = sorted(root.glob(f"PY-263.*/plugins/{PLUGIN_ID}/*/deprecated-usages.txt"))
    if len(files) != 1:
        print(f"Expected one 263 deprecation report, found {len(files)} in {root}", file=sys.stderr)
        return 1

    found = Counter()
    unexpected = []
    for line in files[0].read_text(encoding="utf-8").splitlines():
        if not line.strip():
            continue
        if line.startswith(LEGACY_METHOD) and " is invoked in " in line:
            caller = line.split(" is invoked in ", 1)[1].split("(", 1)[0]
            found[caller] += 1
        else:
            unexpected.append(line)

    if found != EXPECTED_CALLERS or unexpected:
        print("2026.3 deprecated API baseline changed:", file=sys.stderr)
        print(f"Expected: {dict(EXPECTED_CALLERS)}", file=sys.stderr)
        print(f"Observed: {dict(found)}", file=sys.stderr)
        for line in unexpected:
            print(f"Unexpected: {line}", file=sys.stderr)
        return 1

    print("PyCharm 263 deprecations: 4 known synchronous file-open calls; no new usages.")
    return 0


if __name__ == "__main__":
    if len(sys.argv) != 2:
        print("Usage: check-2026-3-deprecations.py <report-directory>", file=sys.stderr)
        sys.exit(2)
    sys.exit(check_report(Path(sys.argv[1])))
