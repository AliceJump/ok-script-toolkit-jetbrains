# -*- coding: utf-8 -*-
"""为 CharacterDataService 的全部 issue 构造补 source（对齐 VSCode 版跳转能力）。"""
import io

p = 'src/main/kotlin/com/alicejump/okscripttoolkit/core/CharacterDataService.kt'
with io.open(p, encoding='utf-8', newline='') as f:
    src = f.read()

pairs = [
    ('''                issues.add(CharacterIssue(nextIssueId(), IssueSeverity.ERROR, "missing-file",
                    "Master file not found: ${paths.masterFile}"))''',
     '''                issues.add(CharacterIssue(nextIssueId(), IssueSeverity.ERROR, "missing-file",
                    "Master file not found: ${paths.masterFile}",
                    CharacterIssueSource(SourceKind.MASTER)))'''),
    ('''                issues.add(CharacterIssue(nextIssueId(), IssueSeverity.ERROR, "missing-effects-file",
                    "Effects file not found: ${paths.effectsFile}"))''',
     '''                issues.add(CharacterIssue(nextIssueId(), IssueSeverity.ERROR, "missing-effects-file",
                    "Effects file not found: ${paths.effectsFile}",
                    CharacterIssueSource(SourceKind.EFFECTS)))'''),
    ('''                issues.add(CharacterIssue(nextIssueId(), IssueSeverity.ERROR, "missing-skills-directory",
                    "Skills directory not found: ${paths.skillsDir}"))''',
     '''                issues.add(CharacterIssue(nextIssueId(), IssueSeverity.ERROR, "missing-skills-directory",
                    "Skills directory not found: ${paths.skillsDir}",
                    CharacterIssueSource(SourceKind.CHARACTER)))'''),
    ('''                    issues.add(CharacterIssue(nextIssueId(), IssueSeverity.ERROR, "invalid-json",
                        "Failed to parse ${file.name}"))''',
     '''                    issues.add(CharacterIssue(nextIssueId(), IssueSeverity.ERROR, "invalid-json",
                        "Failed to parse ${file.name}",
                        CharacterIssueSource(SourceKind.CHARACTER, fileName = file.name)))'''),
    ('''                    issues.add(CharacterIssue(nextIssueId(), IssueSeverity.ERROR, "missing-character-id",
                        "Skill file ${file.name} missing character_id, using filename"))''',
     '''                    issues.add(CharacterIssue(nextIssueId(), IssueSeverity.ERROR, "missing-character-id",
                        "Skill file ${file.name} missing character_id, using filename",
                        CharacterIssueSource(SourceKind.CHARACTER, fileName = file.name)))'''),
    ('''                    issues.add(CharacterIssue(nextIssueId(), IssueSeverity.ERROR, "duplicate-character-id",
                        "Duplicate character_id '$characterId' in ${file.name}"))''',
     '''                    issues.add(CharacterIssue(nextIssueId(), IssueSeverity.ERROR, "duplicate-character-id",
                        "Duplicate character_id '$characterId' in ${file.name}",
                        CharacterIssueSource(SourceKind.CHARACTER, characterId = characterId, fileName = file.name)))'''),
    ('''                    issues.add(CharacterIssue(nextIssueId(), IssueSeverity.WARNING, "missing-skills-array",
                        "Skill file ${file.name} has no skills array"))''',
     '''                    issues.add(CharacterIssue(nextIssueId(), IssueSeverity.WARNING, "missing-skills-array",
                        "Skill file ${file.name} has no skills array",
                        CharacterIssueSource(SourceKind.CHARACTER, fileName = file.name)))'''),
    ('''                        issues.add(CharacterIssue(nextIssueId(), IssueSeverity.WARNING, "invalid-skill-entry",
                            "Invalid skill entry at index $idx in ${file.name}"))''',
     '''                        issues.add(CharacterIssue(nextIssueId(), IssueSeverity.WARNING, "invalid-skill-entry",
                            "Invalid skill entry at index $idx in ${file.name}",
                            CharacterIssueSource(SourceKind.CHARACTER, fileName = file.name)))'''),
    ('''                        issues.add(CharacterIssue(nextIssueId(), IssueSeverity.ERROR, "missing-skill-id",
                            "Skill at index $idx in ${file.name} missing skill_id"))''',
     '''                        issues.add(CharacterIssue(nextIssueId(), IssueSeverity.ERROR, "missing-skill-id",
                            "Skill at index $idx in ${file.name} missing skill_id",
                            CharacterIssueSource(SourceKind.CHARACTER, fileName = file.name)))'''),
    ('''                        issues.add(CharacterIssue(nextIssueId(), IssueSeverity.ERROR, "duplicate-skill-id",
                            "Duplicate skill_id '$skillId' in ${file.name}"))''',
     '''                        issues.add(CharacterIssue(nextIssueId(), IssueSeverity.ERROR, "duplicate-skill-id",
                            "Duplicate skill_id '$skillId' in ${file.name}",
                            CharacterIssueSource(SourceKind.CHARACTER, skillId = skillId, fileName = file.name)))'''),
    ('''                    issues.add(CharacterIssue(nextIssueId(), IssueSeverity.WARNING, "missing-skill-file",
                        "Master entry '$masterId' ($zhName) has no corresponding skill file"))''',
     '''                    issues.add(CharacterIssue(nextIssueId(), IssueSeverity.WARNING, "missing-skill-file",
                        "Master entry '$masterId' ($zhName) has no corresponding skill file",
                        CharacterIssueSource(SourceKind.MASTER, characterId = masterId)))'''),
    ('''                        issues.add(CharacterIssue(nextIssueId(), IssueSeverity.WARNING, "character-name-mismatch",
                            "Character '${parsed.characterId}': master name '$masterZh' != skill name '${parsed.name}'"))''',
     '''                        issues.add(CharacterIssue(nextIssueId(), IssueSeverity.WARNING, "character-name-mismatch",
                            "Character '${parsed.characterId}': master name '$masterZh' != skill name '${parsed.name}'",
                            CharacterIssueSource(SourceKind.CHARACTER, characterId = parsed.characterId)))'''),
    ('''                        issues.add(CharacterIssue(nextIssueId(), IssueSeverity.WARNING, "character-star-mismatch",
                            "Character '${parsed.characterId}': master stars $masterStars != skill star ${parsed.star}"))''',
     '''                        issues.add(CharacterIssue(nextIssueId(), IssueSeverity.WARNING, "character-star-mismatch",
                            "Character '${parsed.characterId}': master stars $masterStars != skill star ${parsed.star}",
                            CharacterIssueSource(SourceKind.CHARACTER, characterId = parsed.characterId)))'''),
    ('''                    issues.add(CharacterIssue(nextIssueId(), IssueSeverity.WARNING, "missing-master-entry",
                        "Skill file for '${parsed.characterId}' has no master table entry"))''',
     '''                    issues.add(CharacterIssue(nextIssueId(), IssueSeverity.WARNING, "missing-master-entry",
                        "Skill file for '${parsed.characterId}' has no master table entry",
                        CharacterIssueSource(SourceKind.CHARACTER, characterId = parsed.characterId)))'''),
    ('''                    issues.add(CharacterIssue(nextIssueId(), IssueSeverity.WARNING, "missing-character-locales",
                        "Character '${parsed.characterId}' has no locale entries"))''',
     '''                    issues.add(CharacterIssue(nextIssueId(), IssueSeverity.WARNING, "missing-character-locales",
                        "Character '${parsed.characterId}' has no locale entries",
                        CharacterIssueSource(SourceKind.LOCALE, characterId = parsed.characterId)))'''),
    ('''                    issues.add(CharacterIssue(nextIssueId(), IssueSeverity.INFO, "orphan-locale-entry",
                        "Locale entry for '$charId' has no corresponding character"))''',
     '''                    issues.add(CharacterIssue(nextIssueId(), IssueSeverity.INFO, "orphan-locale-entry",
                        "Locale entry for '$charId' has no corresponding character",
                        CharacterIssueSource(SourceKind.LOCALE, characterId = charId)))'''),
]

count = 0
for old, new in pairs:
    if old in src:
        src = src.replace(old, new, 1)
        count += 1
    else:
        print('NOT FOUND:', old[:70].replace('\n', ' | '))

with io.open(p, 'w', encoding='utf-8', newline='\n') as f:
    f.write(src)
print('added source to %d/%d issues' % (count, len(pairs)))
