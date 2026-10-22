# Parlex — независимая проверка recovery-прохода

| | |
|---|---|
| Дата | 2026-10-22 |
| HEAD | `88b8d2c` + локальные исправления этой проверки |
| Роли | Senior Android engineer · Security/Privacy · QA/Accessibility/Performance · Release/CI · Docs |
| Метод | статический review диффа `607638f..HEAD`, чтение production-кода, прогон Gradle, разбор отчётов lint/test, анализ манифеста и APK |
| Ограничение | физическое устройство не подключено; всё, что требует реального экрана/микрофона/камеры, помечено как **device-only** |

## 1. Фактическая валидация

| Проверка | Результат |
|---|---|
| `git status` | clean перед проверкой; remote `origin/main` = `https://github.com/RandoTeam/Parlex.git` |
| `:app:compileDebugKotlin` | **BUILD SUCCESSFUL** |
| `:app:testDebugUnitTest` | **70 suites / 470 tests / 0 failures / 0 errors** |
| `:app:lintDebug` | **0 errors**, 385 warnings, 1 hint; `abortOnError=true` проходит |
| `:app:assembleDebug` | **BUILD SUCCESSFUL**, `app-debug.apk` 135.9 MB |
| APK manifest | `allowBackup=false`, `versionCode=15105`, `versionName=1.5.1-beta.5`, `minSdk=26`, `targetSdk=36` |
| Native | `libtranslive.so` собран (stub без llama.cpp); `libOpenCL.so` в APK отсутствует (правильно) |
| CI workflow | синтаксис и команды валидны; GitHub Actions **не запускается из-за биллинга аккаунта** (`recent account payments failed`), не из-за кода |
| Секреты | ключи/keystore/APK в git не найдены |

## 2. Роли и выводы

### 2.1 Senior Android engineer

**Подтверждённые дефекты, исправленные в этой проверке**

- **MediaProjection / FGS на Android 14+**: `ScreenTranslateOverlayService` вызывал `ensureForeground()` до `getMediaProjection()` и оставался с `dataSync`; на API 34+ это могло давать `SecurityException` и тихо ломать projection. Теперь тип `mediaProjection|dataSync` запрашивается до `getMediaProjection`, с откатом на `dataSync` при ошибке.
- **Camera crop regression**: после первой итерации `recognizeLive()` при `cropRect != fullFrame` уходил в YUV→JPEG→Bitmap→crop (дорого и с аллокациями). Заменено на zero-copy `InputImage` + арифметический перенос боксов из capture-space в viewport-space (`cropOcrResultToViewport`), включая rotation 90/180/270.
- **Volume chord**: прямой `onKeyEvent`/`canRequestFilterKeyEvents` удалён из конфигурации accessibility. Причина — security/Play-риск и необходимость фильтрации всех клавиш. Остаётся системный accessibility shortcut (тот путь, который у вас работал) + диагностика/кнопка «Проверить».
- **`isAccessibilityTool="true"`** заменён на `false`: сервис не является средством доступности в смысле Play; это снижает риск отклонения.
- **FAST-режим** больше не вызывает LLM, если `isLlm=false`; раньше загруженная LLM от камеры/диалога могла внезапно обрабатывать экран в FAST-режиме.
- **`TextPrefixProtector`** возвращён в экранный batch: списки/маркеры/эмодзи больше не теряются.
- **Retranslate**: старый `retranslateJob` отменяется при dismiss и перед новым пересчётом; передаётся `screenLanguage` для auto-режима.
- **One-shot `ACTION_SHOW_TRANSLATION`**: при ошибке декодирования сервис останавливается, если пузыря нет.
- **Camera LiteRT**: ветка unsupported-pair теперь использует `isMainModelLoaded()` (LiteRT или GGUF), а не только GGUF.
- **TTS**: listener ставится сразу после конструктора, а не только внутри `OnInitListener` — если callback приходит раньше, таймаут больше не срабатывает всегда.
- **SpeechEngine**: очередь VAD-сегментов ограничена (`capacity=4`, `DROP_OLDEST`), read-loop не крутится на 100% при `read <= 0`, длительность реплики берётся из VAD-сегмента.
- **TranslatorPool**: ограничен 4 парами (LRU-подобное вытеснение), `release()` больше не закрывает `currentTranslator` дважды, `ensureTranslator` не сбрасывает чужой `_isDownloading`.
- **DB**: `exportSchema=true`, схема v5 генерируется в `app/schemas/`; миграции 1→5 переписаны через пересоздание таблиц без `DEFAULT`, чтобы не было расхождений с Room-валидацией; добавлен downgrade fallback.

**Остаётся (P1/P2, не блокирует сборку)**

1. Статический `pendingDirectBitmap/пendingDirectNodes` всё ещё может быть перезаписан двумя вызовами до `onStartCommand` (записи теперь под `pendingLock`, но канал один). Полное решение — payload в `Intent`/`Uri` или очередь сессий.
2. `cancelActiveTranslation()` во время полёта screenshot может не отменить callback; нужен session token на каждом callback.
3. `precomputeLayouts()` и `createBackgroundPatch()` работают на UI-потоке; для 40+ блоков возможен hitch.
4. `translatorPool` не инвалидирует кэш при удалении пакета (stale `Translator` для старых пар).
5. `SpeechEngine.release()` откладывает освобождение native recognizer, но `initialize()` при смене модели всё ещё может освободить его во время decode; нужен единый native-session lock.
6. `ArTranslateOverlayView`/`FloatingBubbleView` TalkBack: пузырь получил `contentDescription`/`performClick`, но у текстовых блоков оверлея нет `AccessibilityNodeProvider`/виртуальных узлов.
7. Экспорт: `renderComposite()` теперь вызывается на `Dispatchers.Default`, bitmap освобождается; нужен bitmap-diff тест.

### 2.2 Security / Privacy

**Исправлено**

- **Zip-slip** в `FastModelSyncComponents.unpackToDirectory`: канонический путь + запрет `..`/абсолютных имён. Раньше hostile `.parlex-fast` мог писать в `shared_prefs`/`filesDir`.
- **MediaProjection FGS order** (см. выше) — устранён тихий обход projection-политики.
- **Accessibility**: `isAccessibilityTool=false`; direct key filtering удалён; доступ к экрану остаётся только через явно включённый пользователем сервис. В Settings есть инструкция и кнопка проверки.
- **AssistTriggerActivity**: экспортированная activity теперь принимает только `ACTION_ASSIST`, `ACTION_VOICE_COMMAND`, `heytap...`, `coloros...`; сторонний intent больше не запускает overlay.
- **`allowBackup=false`** — история, диалоги, записи и настройки не уходят в Auto Backup.
- **Room**: вместо destructive migration — явные миграции; downgrade fallback только на downgrade.

**Остаётся**

1. **SHA-256**: 25 из 27 моделей каталога без checksum; `verifyDownload` пропускает проверку при `sha256 == null`. Multi-GB GGUF/STT принимаются без целостности. Нужно либо заполнить хеши, либо fail-closed + UI-предупреждение.
2. **Screenshot cache**: `ScreenCaptureService` пишет PNG в `cacheDir/screen_captures` и не удаляет; накопление чувствительных кадров. Нужна очистка после декодирования/при старте.
3. **Dialogue audio files** не удаляются при удалении сессии (`HistoryViewModel`).
4. **Export filename** `DISPLAY_NAME` не санитизируется в `DocumentTranslateViewModel` — возможен выход за `exports/`.
5. **CI supply chain**: actions pinned by major tags, JitPack без content filter, нет dependency verification. P2.
6. **`MNN.version`** — 7-символьный SHA, а AGENTS требует 40; исправить при следующем native-обновлении.

### 2.3 QA / Accessibility / Performance

**Подтверждено**

- 470 тестов зелёные, но **большая часть overlay/camera/voice тестов — «теневые»**: `ArOverlayRenderingTest`, `ArPerLineLayoutTest`, `ArElasticRelaxationTest`, `ArOverlaySafeGeometryTest`, `FloatingBubbleTest`, `LensScanEffectTest`, `MiniOverlayHudTest` объявляют свои копии типов/алгоритмов и не вызывают production. `HandsFreeDialogueEngine` в production отсутствует. `CameraViewModel` не конструируется ни в одном тесте. `TqiHostBenchmarkTest` меряет исходные боксы, а не рендер.
- `app/src/androidTest` отсутствует; миграции и overlay не проверяются на устройстве.
- `ArTextFitTest` теперь вызывает production `ArTextFit`, но часть assert'ов тавтологична; нужен тест полного fit-цикла.
- `FloatingBubbleView` получил `contentDescription` и `performClick`; `ArTranslateOverlayView` вызывает `performClick` на tap. Виртуальных a11y-узлов для переведённых блоков нет.
- Touch targets языка/тумблера/close меньше 48 dp (26–36 dp).
- `CameraScreen` overlay перешёл на `sp`/fontScale, но `TextPaint`/`StaticLayout` всё ещё создаются на каждый draw.
- `precomputeLayouts` на UI-потоке (см. выше).
- `SettingsScreen`: switch удалён вместе с chord; кнопка «Проверить» теперь показывает success-строку, а не собственную подпись. Оставшиеся hardcoded строки: `Auto`, `Saved`, `Save failed`, `⇄`, `✕` и toast-сообщения.
- `README`/`PROJECT_STAGE` всё ещё содержат устаревшие числа/формулировки (465 vs 470, probe 1 s vs 30 frames ≈6 s, «11–16 sp» vs текущие 9–22 sp, «collision avoidance» vs отсутствие). Нужна синхронизация.

**Остаётся P0 для доверия к тестам**

- Добавить `androidTest` с `MigrationTestHelper` для 1→2, 2→3, 3→4, 4→5.
- Заменить shadow-тесты на вызовы production или удалить их.
- Добавить Robolectric/Paparazzi golden-тест `ArTranslateOverlayView.precomputeLayouts` и `renderComposite`.
- Добавить bitmap-diff тест `ScreenTranslationExporter.exportRendered`.
- Добавить instrumented smoke: capture → overlay → rotate → dismiss, camera crop mapping, TTS timeout.

### 2.4 Release / CI

- Workflow исправлен: корректные SDK-пакеты, unit tests, lint, artifact, отдельная native-джоба, `timeout` отсутствует (P2).
- Локально всё зелёное. **GitHub Actions не стартует из-за billing failure аккаунта** — это не дефект кода; нужно пополнить/поднять лимит Actions.
- APK 135.9 MB; версия/подпись не менялись (это не релиз).
- `app/schemas/5.json` нужно закоммитить (сделано в этой проверке) — иначе миграции нечем проверять.

### 2.5 Docs

- `docs/AUDIT_AND_RECOVERY_PLAN_2026-10-21.md` обновлён статусом recovery и ссылкой на эту проверку.
- `PROJECT_STAGE` обновлён по HEAD/датам, но раздел Phase Z всё ещё описывает старую collision-модель; пометить как historical.
- README: заменить «probe once per second» на «one probe per 30 analysed frames (≈6 s at 5 fps)», «1280×720 requested» на «requested with closest-higher fallback», убрать «collision avoidance» из текущего описания overlay.

## 3. Что проверено и признано корректным

- `allowBackup=false`, FileProvider ограничен `cache-path screen_captures`, `ScreenCaptureService` стартует FGS с `mediaProjection` до `getMediaProjection`.
- Tar-распаковка STT защищена каноническим путём; zip-slip в fast-pack исправлен.
- Нет `HttpLoggingInterceptor` и пользовательского текста в logcat (остались только utteranceId/errorCode).
- Нет секретов/ключей/APK в git; удалены два лишних AAR по ~55 MB.
- `FastBatchTranslator` fallback-тест исправлен на `AtomicInteger`; flaky-тестов не обнаружено.
- `ArTextFit` вынесен в production и покрыт тестом (базовая проверка clamp/truncation).
- `ScreenTranslationExporter.exportRendered` теперь сохраняет ровно тот in-place рендер; bitmap освобождается.

## 4. Следующий минимальный порядок работ

1. **P0**: `androidTest` migration tests + коммит `app/schemas/`; до этого не выпускать обновление поверх v1–v4 без ручной проверки.
2. **P1**: session token для screenshot-callback и `pending` payload; TalkBack virtual nodes; 48 dp targets.
3. **P1**: SHA-256 для моделей/STT; fail-closed.
4. **P1**: убрать/пометить shadow-тесты, добавить Robolectric/golden overlay-тест.
5. **P2**: i18n zh для 22 новых строк, вынести `Auto/Saved/⇄/✕`; обновить README/PROJECT_STAGE.
6. **Device-only**: проверить FGS/projection на Android 14+, camera crop на 4:3/16:9, rotation, TalkBack, TTS CJK, volume shortcut на ColorOS/Pixel.

---

**Итог:** сборка, unit-тесты (470), lint и APK проходят; критических compile/runtime-дефектов в новом коде не найдено. Основной риск теперь не в «красной сборке», а в отсутствии device/instrumentation-проверки миграций, overlay и камеры. CI запустится только после устранения billing-проблемы GitHub Actions.
