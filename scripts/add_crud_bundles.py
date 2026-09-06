# -*- coding: utf-8 -*-
"""角色 CRUD 文案（6 语言）。"""
import io

blocks = {
'OkScriptToolkitBundle.properties': '''characterManager.addSkill=Add skill
characterManager.editSkill=Edit skill
characterManager.deleteSkill=Delete skill
characterManager.selectCharacterFirst=Select a character first.
characterManager.noSkillFile=No skill file found for {0}.
characterManager.deleteSkillConfirm=Delete skill {0} from {1}?
characterManager.mutationFailed=Mutation failed
''',
'OkScriptToolkitBundle_zh_CN.properties': '''characterManager.addSkill=添加技能
characterManager.editSkill=编辑技能
characterManager.deleteSkill=删除技能
characterManager.selectCharacterFirst=请先选择一个角色。
characterManager.noSkillFile=未找到 {0} 的技能文件。
characterManager.deleteSkillConfirm=确定从 {1} 删除技能 {0} 吗？
characterManager.mutationFailed=修改失败
''',
'OkScriptToolkitBundle_zh_TW.properties': '''characterManager.addSkill=新增技能
characterManager.editSkill=編輯技能
characterManager.deleteSkill=刪除技能
characterManager.selectCharacterFirst=請先選擇一個角色。
characterManager.noSkillFile=未找到 {0} 的技能檔案。
characterManager.deleteSkillConfirm=確定從 {1} 刪除技能 {0} 嗎？
characterManager.mutationFailed=修改失敗
''',
'OkScriptToolkitBundle_ja.properties': '''characterManager.addSkill=スキルを追加
characterManager.editSkill=スキルを編集
characterManager.deleteSkill=スキルを削除
characterManager.selectCharacterFirst=先にキャラクターを選択してください。
characterManager.noSkillFile={0} のスキルファイルが見つかりません。
characterManager.deleteSkillConfirm={1} からスキル {0} を削除しますか？
characterManager.mutationFailed=変更に失敗しました
''',
'OkScriptToolkitBundle_ko.properties': '''characterManager.addSkill=스킬 추가
characterManager.editSkill=스킬 편집
characterManager.deleteSkill=스킬 삭제
characterManager.selectCharacterFirst=먼저 캐릭터를 선택하세요.
characterManager.noSkillFile={0}의 스킬 파일을 찾을 수 없습니다.
characterManager.deleteSkillConfirm={1}에서 스킬 {0}을(를) 삭제할까요?
characterManager.mutationFailed=변경 실패
''',
'OkScriptToolkitBundle_es.properties': '''characterManager.addSkill=A\u00f1adir habilidad
characterManager.editSkill=Editar habilidad
characterManager.deleteSkill=Eliminar habilidad
characterManager.selectCharacterFirst=Selecciona primero un personaje.
characterManager.noSkillFile=No se encontr\u00f3 el archivo de habilidades de {0}.
characterManager.deleteSkillConfirm=\u00bfEliminar la habilidad {0} de {1}?
characterManager.mutationFailed=Error al modificar
''',
}

for name, block in blocks.items():
    p2 = f'src/main/resources/messages/{name}'
    with io.open(p2, encoding='utf-8', newline='\n') as f:
        c = f.read()
    if 'characterManager.addSkill' in c:
        print(name, 'skip'); continue
    with io.open(p2, 'a', encoding='utf-8', newline='\n') as f:
        f.write(block)
    print(name, 'added')
