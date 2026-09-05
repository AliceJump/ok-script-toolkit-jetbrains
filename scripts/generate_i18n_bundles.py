# -*- coding: utf-8 -*-
"""一次性脚本：生成 zh_TW/ja/ko/es 语言包并校验键与 en bundle 一致。"""
import io
import os

os.chdir(os.path.join(os.path.dirname(__file__), "..", "src", "main", "resources", "messages"))

zh_tw = """plugin.name=ok-script Toolkit
hints.python.name=ok-script Toolkit
hints.python.description=在 Python 檔案中內聯顯示 self.lang 鍵值、OCR match 譯文與效果 ID
hints.json.name=ok-script 效果提示
hints.json.description=在 JSON 檔案中內聯顯示效果 ID 的描述
toolwindow.templates=ok-script 模板
toolwindow.tasks=ok-script 任務
settings.displayName=ok-script Toolkit
settings.paths=資料來源
settings.editor=編輯器提示
settings.taskLauncher=任務啟動器
settings.langDirectory=語言 JSON 目錄：
settings.poDirectory=gettext PO 目錄：
settings.poDomains=PO 網域（逗號分隔）：
settings.displayLocale=顯示語言：
settings.featureAliases=模板別名（逗號分隔）：
settings.effectsFile=效果定義檔案：
settings.enablePoData=啟用 gettext PO 資料
settings.enableInlayHints=啟用行內提示
settings.templateGallery=啟用模板畫廊
settings.autoLocale=自動（跟隨 IDE）
gallery.search=搜尋模板
gallery.count=共 {0} 個模板
gallery.insert=插入
gallery.copy=複製表達式
gallery.open=開啟來源圖片
gallery.refresh=重新整理
gallery.empty=未找到模板，請檢查 assets/coco_annotations.json。
gallery.copied=已複製：{0}
gallery.noEditor=沒有可用的 Python 編輯器，已改為複製表達式。
documentation.language=語言
documentation.type=類型
documentation.value=值
documentation.current=目前
documentation.category=分類
documentation.description=描述
settings.okScriptProjectPath=ok-script 專案路徑（留空自動偵測）：
settings.okScriptPython=Python 直譯器路徑（留空自動偵測）：
settings.character=角色管理
settings.characterProjectPath=角色專案路徑（留空自動偵測）：
settings.characterMasterFile=角色主表檔案：
settings.characterSkillsDirectory=角色技能目錄：
settings.characterLocaleFile=角色語言檔案：
settings.characterAvatarTemplateRegex=頭像模板正規表示式：
settings.templateAssets=模板素材
settings.okTemplatesDirectory=模板目錄：
taskLauncher.refresh=重新整理任務清單
taskLauncher.run=執行任務
taskLauncher.stop=停止任務
taskLauncher.pause=暫停任務
taskLauncher.resume=恢復任務
taskLauncher.noTaskSelected=請選擇要執行的任務
taskLauncher.taskRunning=任務正在執行中
taskLauncher.taskCompleted=任務完成
taskLauncher.taskFailed=任務失敗
taskLauncher.taskStopped=任務已停止
taskLauncher.taskTimeout=任務逾時
taskLauncher.noTaskRunning=目前沒有正在執行的任務
taskLauncher.loading=載入任務中...
taskLauncher.noProject=未找到 ok-script 專案，請在設定中設定專案路徑
taskLauncher.noPython=未找到 Python 直譯器
taskLauncher.console=輸出
taskLauncher.clearConsole=清空輸出
taskLauncher.timeout=逾時（秒，0=不限時）
documentation.templateName=模板名稱
documentation.size=尺寸
documentation.source=來源
documentation.ocrRuntime=執行時 fix_match_regex 會先透過 ocr.po 翻譯此 pattern，再進行編譯。
completion.keys={0} 個鍵
notification.group=ok-script 語言提示
characterManager.search=搜尋角色
characterManager.refresh=重新整理
characterManager.loading=載入角色資料中...
characterManager.detail=詳情
characterManager.issues=問題
characterManager.effects=效果
templateAsset.search=搜尋模板
templateAsset.import=匯入圖片
templateAsset.refresh=重新整理
templateAsset.loading=載入模板中...
templateAsset.empty=未找到模板圖片。
templateAsset.open=開啟來源圖片
templateAsset.delete=刪除
templateAsset.deleteConfirm=確定刪除模板 {0} 嗎？
templateAsset.count=共 {0} 個模板
"""

ja = """plugin.name=ok-script Toolkit
hints.python.name=ok-script Toolkit
hints.python.description=Python ファイル内に self.lang の値・OCR match の訳語・効果 ID をインライン表示します
hints.json.name=ok-script 効果ヒント
hints.json.description=JSON ファイル内に効果 ID の説明をインライン表示します
toolwindow.templates=ok-script テンプレート
toolwindow.tasks=ok-script タスク
settings.displayName=ok-script Toolkit
settings.paths=データソース
settings.editor=エディターヒント
settings.taskLauncher=タスクランチャー
settings.langDirectory=言語 JSON ディレクトリ：
settings.poDirectory=gettext PO ディレクトリ：
settings.poDomains=PO ドメイン（カンマ区切り）：
settings.displayLocale=表示言語：
settings.featureAliases=テンプレートエイリアス（カンマ区切り）：
settings.effectsFile=効果定義ファイル：
settings.enablePoData=gettext PO データを有効化
settings.enableInlayHints=インラインヒントを有効化
settings.templateGallery=テンプレートギャラリーを有効化
settings.autoLocale=自動（IDE に従う）
gallery.search=テンプレートを検索
gallery.count={0} 個のテンプレート
gallery.insert=挿入
gallery.copy=式をコピー
gallery.open=ソース画像を開く
gallery.refresh=更新
gallery.empty=テンプレートが見つかりません。assets/coco_annotations.json を確認してください。
gallery.copied=コピーしました：{0}
gallery.noEditor=利用可能な Python エディターがないため、式をコピーしました。
documentation.language=言語
documentation.type=種類
documentation.value=値
documentation.current=現在
documentation.category=カテゴリ
documentation.description=説明
settings.okScriptProjectPath=ok-script プロジェクトパス（空欄で自動検出）：
settings.okScriptPython=Python インタープリターパス（空欄で自動検出）：
settings.character=キャラクター管理
settings.characterProjectPath=キャラクタープロジェクトパス（空欄で自動検出）：
settings.characterMasterFile=キャラクター master ファイル：
settings.characterSkillsDirectory=キャラクタースキルディレクトリ：
settings.characterLocaleFile=キャラクター言語ファイル：
settings.characterAvatarTemplateRegex=アバターテンプレート正規表現：
settings.templateAssets=テンプレートアセット
settings.okTemplatesDirectory=テンプレートディレクトリ：
taskLauncher.refresh=タスクリストを更新
taskLauncher.run=タスクを実行
taskLauncher.stop=タスクを停止
taskLauncher.pause=タスクを一時停止
taskLauncher.resume=タスクを再開
taskLauncher.noTaskSelected=実行するタスクを選択してください
taskLauncher.taskRunning=タスクが実行中です
taskLauncher.taskCompleted=タスクが完了しました
taskLauncher.taskFailed=タスクが失敗しました
taskLauncher.taskStopped=タスクを停止しました
taskLauncher.taskTimeout=タスクがタイムアウトしました
taskLauncher.noTaskRunning=現在実行中のタスクはありません
taskLauncher.loading=タスクを読み込み中...
taskLauncher.noProject=ok-script プロジェクトが見つかりません。設定でプロジェクトパスを指定してください
taskLauncher.noPython=Python インタープリターが見つかりません
taskLauncher.console=出力
taskLauncher.clearConsole=出力をクリア
taskLauncher.timeout=タイムアウト（秒、0=無制限）
documentation.templateName=テンプレート名
documentation.size=サイズ
documentation.source=ソース
documentation.ocrRuntime=実行時、fix_match_regex はこのパターンを ocr.po で翻訳してからコンパイルします。
completion.keys={0} 個のキー
notification.group=ok-script 言語ヒント
characterManager.search=キャラクターを検索
characterManager.refresh=更新
characterManager.loading=キャラクターデータを読み込み中...
characterManager.detail=詳細
characterManager.issues=問題
characterManager.effects=効果
templateAsset.search=テンプレートを検索
templateAsset.import=画像をインポート
templateAsset.refresh=更新
templateAsset.loading=テンプレートを読み込み中...
templateAsset.empty=テンプレート画像が見つかりません。
templateAsset.open=ソース画像を開く
templateAsset.delete=削除
templateAsset.deleteConfirm=テンプレート {0} を削除しますか？
templateAsset.count={0} 個のテンプレート
"""

ko = """plugin.name=ok-script Toolkit
hints.python.name=ok-script Toolkit
hints.python.description=Python 파일에 self.lang 키 값, OCR match 번역, 효과 ID를 인라인으로 표시합니다
hints.json.name=ok-script 효과 힌트
hints.json.description=JSON 파일에 효과 ID 설명을 인라인으로 표시합니다
toolwindow.templates=ok-script 템플릿
toolwindow.tasks=ok-script 작업
settings.displayName=ok-script Toolkit
settings.paths=데이터 소스
settings.editor=에디터 힌트
settings.taskLauncher=작업 런처
settings.langDirectory=언어 JSON 디렉터리:
settings.poDirectory=gettext PO 디렉터리:
settings.poDomains=PO 도메인(쉼표로 구분):
settings.displayLocale=표시 언어:
settings.featureAliases=템플릿 별칭(쉼표로 구분):
settings.effectsFile=효과 정의 파일:
settings.enablePoData=gettext PO 데이터 사용
settings.enableInlayHints=인라인 힌트 사용
settings.templateGallery=템플릿 갤러리 사용
settings.autoLocale=자동(IDE 따름)
gallery.search=템플릿 검색
gallery.count=템플릿 {0}개
gallery.insert=삽입
gallery.copy=표현식 복사
gallery.open=원본 이미지 열기
gallery.refresh=새로 고침
gallery.empty=템플릿을 찾을 수 없습니다. assets/coco_annotations.json을 확인하세요.
gallery.copied=복사됨: {0}
gallery.noEditor=사용 가능한 Python 에디터가 없어 표현식을 복사했습니다.
documentation.language=언어
documentation.type=유형
documentation.value=값
documentation.current=현재
documentation.category=분류
documentation.description=설명
settings.okScriptProjectPath=ok-script 프로젝트 경로(비우면 자동 감지):
settings.okScriptPython=Python 인터프리터 경로(비우면 자동 감지):
settings.character=캐릭터 관리
settings.characterProjectPath=캐릭터 프로젝트 경로(비우면 자동 감지):
settings.characterMasterFile=캐릭터 마스터 파일:
settings.characterSkillsDirectory=캐릭터 스킬 디렉터리:
settings.characterLocaleFile=캐릭터 언어 파일:
settings.characterAvatarTemplateRegex=아바타 템플릿 정규식:
settings.templateAssets=템플릿 애셋
settings.okTemplatesDirectory=템플릿 디렉터리:
taskLauncher.refresh=작업 목록 새로 고침
taskLauncher.run=작업 실행
taskLauncher.stop=작업 중지
taskLauncher.pause=작업 일시 중지
taskLauncher.resume=작업 재개
taskLauncher.noTaskSelected=실행할 작업을 선택하세요
taskLauncher.taskRunning=작업이 실행 중입니다
taskLauncher.taskCompleted=작업 완료
taskLauncher.taskFailed=작업 실패
taskLauncher.taskStopped=작업이 중지되었습니다
taskLauncher.taskTimeout=작업 시간 초과
taskLauncher.noTaskRunning=현재 실행 중인 작업이 없습니다
taskLauncher.loading=작업 불러오는 중...
taskLauncher.noProject=ok-script 프로젝트를 찾을 수 없습니다. 설정에서 프로젝트 경로를 지정하세요
taskLauncher.noPython=Python 인터프리터를 찾을 수 없습니다
taskLauncher.console=출력
taskLauncher.clearConsole=출력 지우기
taskLauncher.timeout=시간 초과(초, 0=제한 없음)
documentation.templateName=템플릿 이름
documentation.size=크기
documentation.source=소스
documentation.ocrRuntime=런타임에 fix_match_regex가 이 패턴을 ocr.po로 번역한 후 컴파일합니다.
completion.keys={0}개 키
notification.group=ok-script 언어 힌트
characterManager.search=캐릭터 검색
characterManager.refresh=새로 고침
characterManager.loading=캐릭터 데이터 불러오는 중...
characterManager.detail=상세
characterManager.issues=문제
characterManager.effects=효과
templateAsset.search=템플릿 검색
templateAsset.import=이미지 가져오기
templateAsset.refresh=새로 고침
templateAsset.loading=템플릿 불러오는 중...
templateAsset.empty=템플릿 이미지를 찾을 수 없습니다.
templateAsset.open=원본 이미지 열기
templateAsset.delete=삭제
templateAsset.deleteConfirm=템플릿 {0}을(를) 삭제하시겠습니까?
templateAsset.count=템플릿 {0}개
"""

es = """plugin.name=ok-script Toolkit
hints.python.name=ok-script Toolkit
hints.python.description=Muestra en línea los valores de self.lang, las traducciones de match de OCR y los IDs de efecto en archivos Python
hints.json.name=ok-script Sugerencias de efectos
hints.json.description=Muestra en línea las descripciones de los IDs de efecto en archivos JSON
toolwindow.templates=Plantillas ok-script
toolwindow.tasks=Tareas ok-script
settings.displayName=ok-script Toolkit
settings.paths=Fuentes de datos
settings.editor=Sugerencias del editor
settings.taskLauncher=Lanzador de tareas
settings.langDirectory=Directorio JSON de idiomas:
settings.poDirectory=Directorio PO de gettext:
settings.poDomains=Dominios PO (separados por comas):
settings.displayLocale=Idioma de visualización:
settings.featureAliases=Alias de plantilla (separados por comas):
settings.effectsFile=Archivo de definición de efectos:
settings.enablePoData=Habilitar datos PO de gettext
settings.enableInlayHints=Habilitar sugerencias en línea
settings.templateGallery=Habilitar galería de plantillas
settings.autoLocale=Automático (según el IDE)
gallery.search=Buscar plantillas
gallery.count={0} plantillas
gallery.insert=Insertar
gallery.copy=Copiar expresión
gallery.open=Abrir imagen original
gallery.refresh=Actualizar
gallery.empty=No se encontraron plantillas. Comprueba assets/coco_annotations.json.
gallery.copied=Copiado: {0}
gallery.noEditor=No hay un editor de Python disponible; la expresión se copió en su lugar.
documentation.language=Idioma
documentation.type=Tipo
documentation.value=Valor
documentation.current=Actual
documentation.category=Categoría
documentation.description=Descripción
settings.okScriptProjectPath=Ruta del proyecto ok-script (vacío para autodetectar):
settings.okScriptPython=Ruta del intérprete de Python (vacío para autodetectar):
settings.character=Gestión de personajes
settings.characterProjectPath=Ruta del proyecto de personajes (vacío para autodetectar):
settings.characterMasterFile=Archivo maestro de personajes:
settings.characterSkillsDirectory=Directorio de habilidades de personajes:
settings.characterLocaleFile=Archivo de idiomas de personajes:
settings.characterAvatarTemplateRegex=Regex de plantilla de avatar:
settings.templateAssets=Recursos de plantillas
settings.okTemplatesDirectory=Directorio de plantillas:
taskLauncher.refresh=Actualizar lista de tareas
taskLauncher.run=Ejecutar tarea
taskLauncher.stop=Detener tarea
taskLauncher.pause=Pausar tarea
taskLauncher.resume=Reanudar tarea
taskLauncher.noTaskSelected=Selecciona una tarea para ejecutar
taskLauncher.taskRunning=Ya hay una tarea en ejecución
taskLauncher.taskCompleted=Tarea completada
taskLauncher.taskFailed=Tarea fallida
taskLauncher.taskStopped=Tarea detenida
taskLauncher.taskTimeout=Tarea agotó el tiempo de espera
taskLauncher.noTaskRunning=No hay ninguna tarea en ejecución
taskLauncher.loading=Cargando tareas...
taskLauncher.noProject=No se encontró un proyecto ok-script. Configura la ruta del proyecto en Ajustes
taskLauncher.noPython=No se encontró el intérprete de Python
taskLauncher.console=Salida
taskLauncher.clearConsole=Limpiar salida
taskLauncher.timeout=Tiempo de espera (segundos, 0=sin límite)
documentation.templateName=Nombre de plantilla
documentation.size=Tamaño
documentation.source=Origen
documentation.ocrRuntime=En tiempo de ejecución, fix_match_regex traduce este patrón mediante ocr.po antes de compilarlo.
completion.keys={0} claves
notification.group=ok-script Sugerencias de idioma
characterManager.search=Buscar personajes
characterManager.refresh=Actualizar
characterManager.loading=Cargando datos de personajes...
characterManager.detail=Detalle
characterManager.issues=Problemas
characterManager.effects=Efectos
templateAsset.search=Buscar plantillas
templateAsset.import=Importar imagen
templateAsset.refresh=Actualizar
templateAsset.loading=Cargando plantillas...
templateAsset.empty=No se encontraron imágenes de plantilla.
templateAsset.open=Abrir imagen original
templateAsset.delete=Eliminar
templateAsset.deleteConfirm=\u00bfEliminar la plantilla {0}?
templateAsset.count={0} plantillas
"""

bundles = {
    "OkScriptToolkitBundle_zh_TW.properties": zh_tw,
    "OkScriptToolkitBundle_ja.properties": ja,
    "OkScriptToolkitBundle_ko.properties": ko,
    "OkScriptToolkitBundle_es.properties": es,
}

with io.open("OkScriptToolkitBundle.properties", encoding="utf-8") as f:
    en_keys = [line.split("=")[0] for line in f if "=" in line and not line.startswith("#")]

for name, content in bundles.items():
    my_keys = [line.split("=")[0] for line in content.splitlines() if "=" in line]
    missing = [k for k in en_keys if k not in my_keys]
    extra = [k for k in my_keys if k not in en_keys]
    if missing or extra:
        print(name, "MISSING:", missing, "EXTRA:", extra)
    else:
        with io.open(name, "w", encoding="utf-8", newline="\n") as f:
            f.write(content)
        print(name, "written,", len(my_keys), "keys OK")
