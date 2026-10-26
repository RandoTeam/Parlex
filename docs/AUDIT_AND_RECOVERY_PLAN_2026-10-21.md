# Parlex — технический аудит и план восстановления

| | |
|---|---|
| Дата снимка | 2026-10-21 |
| Репозиторий | `https://github.com/RandoTeam/Parlex.git` |
| Ветка | `main` |
| HEAD | `607638f4d1828f1d36ba8a142a6c227a17b5b00d` (`feat(release): Parlex v1.5.1-beta.5 …`, 2026-09-14) |
| Артефакты проверки | `:app:compileDebugKotlin` — OK; `:app:assembleDebug` — OK (native stub); `:app:testDebugUnitTest` — 465/465 |
| CI | Красный: 10 из 10 последних запусков `Android Check` завершились ошибкой |
| Устройство | Не подключено (`adb devices` пуст). Всё, что касается реального поведения оверлея/камеры/микрофона, ниже помечено как **гипотеза, требующая проверки на телефоне** |

Документ задуман как «живой» план: по нему можно идти фаза за фазой, отмечать пункты и сверять acceptance-критерии. Он не заменяет `ROADMAP.md`, а фиксирует фактическое состояние кода и порядок восстановления.

---

## 0.1 Статус первого прохода (2026-10-21)

Recovery commits: `2101c79..fe7d4b4` (main). Validation: `:app:testDebugUnitTest`
— **470/470**, `:app:lintDebug` — **BUILD SUCCESSFUL**, `:app:assembleDebug` — OK.
Независимая проверка ролей: `docs/VERIFICATION_2026-10-22.md`.

- [x] Официальный `gradlew.bat` с поддержкой `JAVA_HOME`.
- [x] `scripts/setup-native.ps1/.sh`: OpenCL submodule, link-stub, pinned llama.cpp.
- [x] CMake больше не падает без OpenCL: CPU-only сборка по умолчанию, GPU — при наличии prerequisite.
- [x] CI переписан: SDK-пакеты, Gradle cache, unit tests, artifact; отдельная native-джоба.
- [x] Удалены два неиспользуемых 55 MB AAR.
- [x] `allowBackup=false`; явные Room-миграции 1→5 вместо destructive.
- [x] Экранный layout: переводы вписываются в исходный блок, без сдвига поверх соседей; line-маски больше не двигаются; clip.
- [x] Смешанные языки: группы по source language, отдельная пара на группу; `TranslatorPool` вместо одного глобального переводчика.
- [x] Сканер/пузырь скрываются перед screenshot; rotation/window change закрывают stale overlay; `ACTION_SHOW_TRANSLATION` обрабатывается.
- [x] Диагностика accessibility shortcut + тест в Settings; системный shortcut сохранён; прямой `onKeyEvent`/key filtering удалён после security-review (Play-риск, фильтрация всех клавиш).
- [x] OCR mode вынесен в настройки; default — FAST_ONLY.
- [x] Pixel/M3 Expressive-подобное появление: fade/scale + stagger + haptic.
- [x] Camera: crop-aware live OCR, 1280×720 analysis, один OCR-проход на кадр, без `runBlocking`, LiteRT-aware Quality, per-region document painting.
- [x] Voice: TTS timeout/watchdog, per-utterance callbacks, ASR decode вне audio read loop, AEC/NS/audio focus, измеряемая длительность реплики, финализация записи.
- [ ] Проверка на реальном устройстве (Pixel/OnePlus/ColorOS), screenshot golden-тесты, TQI v2 на production layout.
- [ ] Semantic memory (EmbeddingGemma 2) — отдельный план `docs/SEMANTIC_MEMORY_PLAN_2026-10-21.md`.

---

## 0. Краткое резюме (TL;DR)

Проект содержит много сильных идей и большой объём кода (145 Kotlin-файлов, ~35 000 строк), но три ключевых сценария — **экранный перевод по кнопкам громкости**, **камера** и **голосовой диалог** — находятся в состоянии, где UI и документация обещают больше, чем реально работает. Главные причины — не «мелкие баги», а архитектурные:

| # | Приоритет | Проблема | Суть |
|---|---|---|---|
| 1 | **P0** | Кнопки громкости | Прямого перехвата томов нет вообще: нет `onKeyEvent`, нет `canRequestFilterKeyEvents`/`flagRequestFilterKeyEvents`. Приложение полагается на системный accessibility shortcut, который пользователь должен настроить в OEM-настройках, и никак это не проверяет. |
| 2 | **P0** | «Каша» на экране | Движок раскладки **сдвигает** блоки вниз при коллизиях, а «ластики» (`linePills`) сдвигаются вместе с текстом и закрашивают уже другие участки экрана. Текст уезжает поверх чужого UI, исходные области остаются залитыми цветом. |
| 3 | **P0** | Смешанные языки | Блоки с разными языками переводятся одним активным ML Kit-переводчиком, направление которого задаётся самым длинным образцом. Остальные блоки получают перевод «не в ту сторону». |
| 4 | **P0** | Камера | `recognizeLive()` передаёт в ML Kit полный кадр без учёта `cropRect`; разрешение анализа не задано; в auto-режиме на каждый кадр запускается до трёх распознавателей. Плюс оверлей рисует чёрные плашки и обрезает текст. |
| 5 | **P0** | Камера, снимок | Перевод «запекается» в bitmap по строкам: фон закрашивается сплошным цветом, длинный текст обрезается, в документном режиме закрашивается весь блок страницы (вместе с картинками/таблицами). |
| 6 | **P0** | Голос | `SystemTtsEngine.speakAndWait()` не имеет таймаута. Если `onDone`/`onError` не пришёл, корутина висит вечно, `turnMutex` не отпускается — диалог навсегда застревает в `SPEAKING`. |
| 7 | **P0** | Голос | Распознавание Whisper выполняется **в том же цикле**, который читает `AudioRecord`. Пока идёт decode, микрофон не читается → потерянные слова, обрезанные фразы, «рваная» речь. |
| 8 | **P0** | Общий NMT | `FastTranslateEngine` — синглтон с **одной** активной парой. Диалог, экран и камера переключают её друг другу, при этом `activateDownloadedPair()` закрывает предыдущий `Translator`; перевод в полёте может вернуть исходный текст или неверное направление. |
| 9 | **P1** | Тесты | 465 зелёных тестов не покрывают ни одного реального рендера оверлея: `ArOverlayRenderingTest`, `ArPerLineLayoutTest`, `ArElasticRelaxationTest`, `ArOverlaySafeGeometryTest` определяют **свои копии** структур и проверяют сами себя. TQI показывает 100/100, потому что меряет исходные прямоугольники, а не то, что рисуется. |
| 10 | **P1** | Воспроизводимость | CI красный из-за отсутствующих OpenCL-заголовков/заглушки; `gradlew.bat` игнорирует `JAVA_HOME`; документация ссылается на несуществующий setup-скрипт и на другой commit llama.cpp, чем `llama.cpp.version`. |
| 11 | **P1** | «Призрачные» фичи | Целые каталоги (`VisionLlmComponents`, `SttEngineComponents`, `ScreenOverlayLifecycleComponents`, `DownloadMetricsTracker`) **нигде не используются**, но заявлены в README/PROJECT_STAGE как готовые. |
| 12 | **P1** | Данные/приватность | `allowBackup=true` без правил → история, настройки и записи диалогов могут уходить в облачный backup; Room использует `fallbackToDestructiveMigration()`; 25 из 27 моделей каталога без SHA-256. |

---

## 1. Проверка репозитория и сборки

### 1.1 Клон и remote

```
origin  https://github.com/RandoTeam/Parlex.git (fetch)
origin  https://github.com/RandoTeam/Parlex.git (push)
branch  main -> origin/main
working tree clean
git count-objects -vH: size-pack 132.99 MiB, in-pack 2955
```

Remote настроен правильно; все дальнейшие коммиты нужно вести в `origin/main` этого репозитория. История оформлена в стиле Conventional Commits, но крупными смешанными коммитами (фича + релиз + документация сразу).

### 1.2 Что реально собирается на чистом клоне

Выполнено локально (Windows, JDK 21 Temurin):

| Команда | Результат |
|---|---|
| `.\gradlew.bat :app:compileDebugKotlin` | **BUILD SUCCESSFUL** (3 мин 06 с) |
| `.\gradlew.bat :app:testDebugUnitTest` | **BUILD SUCCESSFUL**, 69 suites / **465 tests / 0 failures / 0 errors** (1 мин 33 с) |
| `.\gradlew.bat :app:assembleDebug` | **BUILD SUCCESSFUL** (1 мин 43 с), `app-debug.apk` = **134.9 MB** |

Важные оговорки:

- **`gradlew.bat` в репозитории — не официальный wrapper.** Он делает `set JAVA_EXE=java.exe` и проверяет только `java` из `PATH`; `JAVA_HOME` игнорируется. На этой машине `PATH` указывал на Oracle JRE 8 → Gradle падал с `Gradle requires JVM 17 or later`. Сборка прошла только после принудительной подстановки JDK 21 в `PATH`. Это ломает инструкции README/HANDOFF у любого, у кого Java 8/11 стоит по умолчанию.
- APK собран с **native stub**: `CMakeLists.txt` не нашёл `app/src/main/cpp/llama.cpp` и собрал `translive_jni_stub.cpp`. То есть LLM-перевод в этом APK недоступен; это ожидаемо для чистого клона, но должно быть явно написано в инструкции.
- MNN checkout также отсутствует; `ocr_mnn_jni.cpp` возвращает строку «MNN OCR bridge is a capability stub in this APK». Гибридный PP-OCR, заявленный в PROJECT_STAGE, на чистом клоне не работает.

### 1.3 CI сломан (подтверждено GitHub Actions)

Все последние запуски `Android Check` — failure:

- `34896003013` (2026-09-14, HEAD) — падение на шаге `android-actions/setup-android@v4`: `Warning: Failed to find package 'tools'`, `sdkmanager … failed with exit code 1` (25 с).
- `34791014539` (2026-09-13) — сборка доходит до CMake и падает:

```
CMake Error at CMakeLists.txt:32 (message):
  OpenCL build prerequisites are missing. Initialise third_party/OpenCL-Headers
  and generate opencl-linker/arm64-v8a/libOpenCL.so with the documented setup script.
```

Причины:

1. `actions/checkout@v6` не инициализирует сабмодуль `third_party/OpenCL-Headers`.
2. В репозитории **нет** `opencl-linker/**/libOpenCL.so` (и он попадает под `*.so` в `.gitignore`).
3. «Documented setup script», на который ссылается CMake, **отсутствует**: в `scripts/` и `tools/` только `verify_on_device_tqi.ps1`, `litert-device-probe.ps1`, `litert-download-translategemma.ps1`.
4. Workflow ставит `build-tools;37.0.0` и не ставит пакет `tools`; набор SDK-пакетов не совпадает с ожиданиями setup-android.

Дополнительно CI:

- не запускает unit-тесты (`:app:testDebugUnitTest`);
- не запускает lint;
- не собирает release/подпись;
- не имеет `androidTest`;
- не кэширует Gradle/NDK;
- не инициализирует сабмодули.

### 1.4 Документация рассогласована

| Документ | Что заявлено | Реальность |
|---|---|---|
| `PROJECT_STAGE.md:9` | HEAD `487f0af` | фактический HEAD `607638f` |
| `PROJECT_STAGE.md:3` | snapshot 2026-09-14 | сегодня 2026-10-21; снимок не обновлялся |
| `README.md:209`, `PROJECT_STAGE.md:95`, `HANDOFF.md:75` | llama.cpp commit `5dcb711…` | `app/src/main/cpp/llama.cpp.version` = `b94041a98ec86e29e63b02decd29175d6820b2a1`; CI берёт именно его |
| `PROJECT_STAGE.md:26` | гибридный MNN PP-OCRv6 работает | MNN не восстанавливается по инструкции; в APK stub |
| `PROJECT_STAGE.md:40-42` | Zipformer, SenseVoice, Whisper, Qwen3, Vision LLM | реально используется Whisper/Qwen3; реестры Zipformer/SenseVoice и весь Vision LLM — мёртвый код |
| `README.md` (Feature Matrix) | Screen Translation «Prototype», но Phase Z «COMPLETED» | оба утверждения одновременно; фактическое качество ближе к prototype |
| `ROADMAP.md` Governance | «CI проверяет сборку» | CI красный на каждом коммите |
| `docs/superpowers/plans/2026-09-14-ocr-hybrid-merge.md` | IoU > 0.40 / 60% покрытия | в `CandidateMerger.kt:30` — `intersectArea / ocrArea > 0.50`, IoU не считается |

### 1.5 Мусор и размер репозитория

- В `app/libs/` лежат **три** AAR Sherpa-ONNX: `1.13.0` (55.2 MB), `1.13.1` (55.2 MB), `1.13.4` (47.7 MB). Используется только `1.13.4` (`app/build.gradle.kts`). Пакет репозитория — 132.99 MiB; две ненужные версии — ~105 MB «мёртвого» веса в каждой копии.
- 13 файлов `assets/tessdata/*.traineddata` (~40 MB) бандлятся в APK, хотя Tesseract — fallback-путь; стоит проверить, нужны ли все языки в базовом APK, или вынести в загрузку.
- `proguard-rules.pro` содержит keep-правила для несуществующих `CameraTranslateEngine` и `TtsEngine`.

---

## 2. Экранный перевод и кнопки громкости (P0)

### 2.1 Что обещано пользователю

В строках настроек прямо написано:

- `values/strings.xml:285-286`: «Volume Keys & A11y Shortcut Action», «pressing the volume shortcut (Volume Up + Down hold)»;
- `values/strings.xml:301`: «instant screen translation via hardware volume buttons»;
- `values/strings.xml:310`: «Under Shortcut, enable Hold volume keys»;
- `ScreenAccessibilityService.kt:42-43, 71-72`: комментарии «Volume Up + Down hold (3s)».

### 2.2 Что реально в коде

- В `ScreenAccessibilityService` **нет** `override fun onKeyEvent(...)`.
- В `app/src/main/res/xml/accessibility_service_config.xml` **нет** `android:canRequestFilterKeyEvents="true"` и **нет** `flagRequestFilterKeyEvents` в `accessibilityFlags`.
- Единственный вход — `AccessibilityButtonController.Callback.onClicked` (`ScreenAccessibilityService.kt:76-105`). Это системная «кнопка доступности» / accessibility shortcut. Чтобы она работала, пользователь должен:
  1) включить сервис в Accessibility;
  2) отдельно настроить Shortcut → «Hold volume keys» (на разных OEM по-разному, на ColorOS часто отсутствует/заблокирован);
  3) чтобы OEM маршрутизировал shortcut в `onClicked` именно этого сервиса.
- Приложение не проверяет `AccessibilityButtonController.isAccessibilityButtonAvailable`, не показывает, настроен ли shortcut, не логирует источник триггера и не имеет альтернативы, если shortcut недоступен.
- `onAccessibilityEvent` пуст, поэтому приложение не узнаёт, что экран/окно изменились, и не может снять/пересчитать устаревший оверлей.

**Вывод:** «перехват кнопок громкости» как функция приложения не реализован. Реализован только необязательный системный shortcut, который пользователь настраивает вручную, без диагностики и без fallback. Это объясняет, почему «очень пытался, но не смог».

### 2.3 Почему получается «каша»: причины по убыванию вклада

#### (1) «Ластики» едут вместе с текстом и закрашивают чужой UI — прямая причина артефактов

`ArRenderBox.offset()` (`ArTranslateOverlayView.kt:73-82`) сдвигает `pillRect`, `touchRect`, `textOffset` **и `linePills`**. Далее `onDraw` (`:866-881`) использует `linePills` как маски-«ластики»:

```kotlin
for (lp in item.linePills) {
    canvas.drawRoundRect(lp.lineRect, cornerRadius, cornerRadius, erasePaint)
}
```

То есть после `resolveOverlaps()` блок уехал вниз, а его line-маски закрасили уже **другой** участок экрана. В результате:

- исходная область остаётся закрашенной `backgroundColor` (потому что `eraseRect` не сдвигается);
- новая область под переводом тоже закрашивается;
- на плотных экранах появляются прямоугольные пятна поверх чужих карточек/иконок.

Это и есть визуальная «каша» в чистом виде. Комментарий в коде («eraseRect remains strictly pinned») верен только для `eraseRect`; `linePills` по-прежнему сдвигаются.

#### (2) Коллизии решаются сдвигом текста, а не подгонкой в исходный блок

`resolveOverlaps()` (`:455-530`):

- глобально сдвигает блоки вниз при пересечении по колонкам (порог 20%);
- затем может сдвинуть всё вверх, если вылезло за нижнюю границу;
- затем «pull-up» сдвигает блоки вверх от низа экрана, что может снова создать пересечения;
- всего 4 прохода, после которых пересечения не гарантированно устранены.

При этом исходное место текста уже закрашено, а сам текст живёт в другом месте. В отличие от Google Lens/Circle to Search, где перевод **остаётся в границах исходного блока** и подгоняется по размеру, здесь страница «плывёт».

#### (3) Перевод почти всегда длиннее оригинала, а авто-fit слишком слабый

- `candidateTextSize` выбирается из высоты блока (`:316-330`), стартует 11–20sp.
- Уменьшение шрифта включается только если высота выросла больше чем в **1.5×** (многострочный) или **2×** (однострочный) (`:361-368`).
- Ширина `targetWidth` = `max(srcWidth, 48dp)`, но не ограничена соседями; высота увеличивается вниз.
- Обрезки/эллипсиса нет: `StaticLayout` рисуется целиком, `clipRect` отсутствует. Всё, что вылезло, либо перекрывает соседей, либо уходит за экран.

#### (4) Нет реального inpainting — только сплошная заливка по цвету краёв

`InpaintingHelper.getDominantEdgeColor()` (`:106-187`) читает 48 пикселей по периметру и возвращает средний цвет; `onDraw` рисует им `drawRoundRect`. На градиентах, фото, иконках, обоях и тенях это выглядит как цветные заплатки. Пиксели антиалиасинга самого текста по периметру могут попасть в выборку и сделать цвет грязным. Настоящего восстановления фона (blur/clone/edge-aware) нет.

#### (5) Смешанные языки переводятся одним направлением

В `processAndShowArTranslation()`:

- для каждого блока определяется язык (`:783-805`);
- из блоков фильтруются только те, чей язык равен target;
- но модель активируется **одна** — по самому длинному образцу (`:812-824`), и весь батч уходит в `fastBatchTranslator.translateBatch()` (`:846`), который использует текущий `Translator` синглтона.

Если на экране, например, английский UI и русский чат, русские блоки будут переведены переводчиком EN→RU (или наоборот) — получится грамматическая каша. Та же проблема в камере/диалоге из-за общей синглтон-пары (см. §6.1).

#### (6) A11y-дерево и скриншот снимаются не одновременно

`captureViaAccessibility()` (`ScreenTranslateOverlayService.kt:568-625`) сначала вызывает `extractVisibleTextNodesFast()`, затем асинхронно `captureSilentScreenshot()`. Между этими событиями пользователь может прокрутить список, открыться клавиатура, измениться layout. Координаты узлов и «замороженный» bitmap расходятся. Никакой проверки timestamp/окна/scroll-позиции нет.

#### (7) `FULL_SCAN` по умолчанию: каждый вызов — это OCR, а не «0 ms»

`SettingsRepository.OcrMode.fromString(null)` возвращает `FULL_SCAN` (`SettingsRepository.kt:276-279`), а `processAndShowArTranslation` при `FULL_SCAN` всегда запускает `ocrEngine.recognize(bitmap, "auto")` (`:713-719`). В свою очередь `OcrEngine.recognize("auto")` — это минимум ML Kit Latin + Tesseract RU, а при CJK ещё несколько проходов (`OcrEngine.kt:183-274`). При этом настройка `OcrMode` **не выведена в UI** (сеттер `ocrMode` нигде не вызывается). Итог: обещанный «0 ms zero-dialogue» на практике — секунды, особенно на первом вызове Tesseract (копирование tessdata).

#### (8) Шумовой фильтр выбрасывает значимый контент

`ScreenTextNoiseFilter` (`ScreenTextNoiseFilter.kt:48-75`) удаляет все числа, цены, даты, проценты, URL. В меню, магазине, расписании, банковском приложении это ключевая информация; в результате рядом с переведённым текстом остаются пустые места и «дыры». Google Lens такие элементы сохраняет и переводит контекстно (единицы, валюты).

#### (9) Слияние A11y + OCR может терять текст

`CandidateMerger.merge()` (`CandidateMerger.kt:20-34`) выбрасывает OCR-блок, если он на >50% площади покрыт **любым** A11y-узлом, даже если A11y-текст — мусорный контейнер. Плюс `NativeScreenTextExtractor.filterAndDeduplicate()` при покрытии <70% удаляет дочерние узлы и продвигает родителя (`:101-112`), из-за чего несколько визуально разных строк становятся одним большим блоком — а дальше его перевод раскладывается в один `StaticLayout` и наезжает на соседей.

#### (10) Пересчёт языка/перевод при смене пары не сериализован

`retranslateCurrentScreen()` (`:974-1040`) запускает новый `serviceScope.launch` на каждое переключение языка. Отмена предыдущей задачи и проверка session id отсутствуют. Быстрое переключение «RU → EN → DE» может дать результат от старой задачи, который перезапишет новый. В LLM-ветке нет `BrandEntityShield` и `NmtTechGlossaryPostProcessor`, то есть качество и защита сущностей отличаются от основного пути.

#### (11) Ориентация/размеры/многооконность не обрабатываются

`onConfigurationChanged()` (`:220-257`) двигает только пузырь и закрывает HUD. AR-оверлей остаётся с прежними `screenWidth/screenHeight`, прежним bitmap и прежними координатами. При повороте получается полностью неверная картинка. `screenWidth/Height` берутся из `resources.displayMetrics` (`:725-726, 907-909`), а не из `WindowManager.getCurrentWindowMetrics()`; на cutout/навигации/сплит-скрине это может не совпадать с реальным кадром. Сам `frozenBitmap` рисуется в `onDraw` без масштабирования (`:853-858`), поэтому любое расхождение размеров приводит к обрезке/сдвигу.

#### (12) UX-решения, которые усиливают ощущение «мусора»

- Любой тап по пустому месту **закрывает** оверлей (`:945-958`), а окно `isFocusable=true` — случайные нажатия и потеря фокуса под приложением.
- Нет никакого индикатора прогресса по блокам: либо пусто, либо всё сразу.
- Верхняя панель жёстко на английском («Auto ▼», «Translate», «Original», «Save», «Copy», «Speak», «Hide») и содержит только 9 языков (`:729-743`), хотя в проекте 33/59 языков.
- Размеры текста в Canvas считаются в `dp`, а не `sp` → системный размер шрифта игнорируется; для accessibility-сервиса это критично.
- Нет `contentDescription` у самодельных кнопок; TalkBack не сможет ими пользоваться.
- Сканирующий эффект и плавающий пузырь попадают в скриншот: `showLensScanEffect()` вызывается **до** `captureSilentScreenshot()` (`:574-578`), а `accessibility takeScreenshot` снимает весь экран, включая окна Parlex. На «замороженном» кадре остаются артефакты сканера/пузыря.

### 2.4 Проблемы жизненного цикла и состояния

| Проблема | Код | Последствие |
|---|---|---|
| `pendingDirectBitmap` / `pendingDirectNodes` — статические поля | `ScreenTranslateOverlayService.kt:1194-1244` | гонка двух триггеров, утечка bitmap, потеря запроса при перезапуске сервиса |
| «Лечение» зависшего мьютекса | `:507-528` | `unlock()` чужого лока/повторный unlock может разблокировать **новую** сессию; `currentSessionToken` увеличивается, но нигде не проверяется |
| Watchdog | `:500-505, 892-895` | по таймауту делает `recoverToIdle`, но не отменяет `activeTranslationJob`; фоновая задача может позже показать оверлей |
| `dismissArOverlay()` вызывает `releaseTranslationLock()` | `:1099-1107` | закрытие оверлея может снять лок у активной новой сессии |
| `lastCapturedBitmap` / `frozenBitmap` не recycle | `:98, 863`; `ArTranslateOverlayView.kt:194` | 10–30 MB на кадр, давление на native heap, GC-паузы |
| `ACTION_SHOW_TRANSLATION` | объявлен `:1179`, отправляется `ScreenCaptureService.kt:104-107`, **не обрабатывается** в `onStartCommand` | путь «снимок → оверлей» мёртв |
| `LiveScreenTranslateService` | отдельный сервис + `LiveOverlayRenderer` | вторая, неиспользуемая реализация экранного перевода; никто её не запускает (кроме неиспользуемого `ACTION_REQUEST_LIVE_TRANSLATE`) |
| `onStartCommand` всегда `START_STICKY` | `:217` | one-shot сервис может быть перезапущен системой без интента и показать пузырь |
| нет `onTrimMemory`/освобождения bitmap | сервис/оверлей | OOM на слабых устройствах |
| уведомление `setSmallIcon(R.mipmap.ic_launcher)` | `:1134-1135` | на части прошивок пустая/белая иконка; нужен monochrome vector |

### 2.5 «Тесты» и TQI: ложная уверенность

- `ArOverlayRenderingTest.kt` (17 тестов) объявляет **собственные** `SpatialRect`, `ArOcrFragment`, `ArRenderBox`, `SpatioTemporalArClusterer` и тестирует их. Production-класс `ArTranslateOverlayView` не импортируется и не вызывается.
- `ArPerLineLayoutTest.kt`, `ArElasticRelaxationTest.kt`, `ArOverlaySafeGeometryTest.kt` устроены так же: локальные `GeoRect`/`ArLinePillModel` и арифметика, продублированная в тесте. Если завтра сломать `precomputeLayouts()` или `resolveOverlaps()`, эти тесты останутся зелёными.
- `ScreenOverlayLifecycleTest.kt` импортирует production-классы, но это `FloatingBubbleStateMachine`, `CaptureDebounceMutex`, `OverlayLayoutCalculator` и т.п., которые **не используются** в приложении (см. §6.3).
- `TqiEvaluator` оценивает геометрию **исходных** A11y-узлов: `node.bounds` берутся из фикстуры, «left-edge drift» по определению 0, коллизии считаются по исходным прямоугольникам. Рендер, сдвиги, шрифт, закраска, переносы не участвуют. Отсюда 100/100 за 34 мс и при этом «каша» на реальном экране.
- `testOptions { unitTests.isReturnDefaultValues = true }` означает, что Android-методы в JVM-тестах возвращают 0/false; например, `Rect.intersects()` в тестах может молча не работать.

**Правило на будущее:** тест обязан вызывать production-код и падать при регрессии. Для оверлея нужны Robolectric/Paparazzi/инструментальные golden-тесты, а не копия формул.

### 2.6 Целевая архитектура экранного перевода

```
Trigger (a11y shortcut / volume chord / QS tile / bubble / assistant)
        │
        ▼
ScreenTranslationService  (один foreground service)
        │  sessionId, Mutex, cancel()
        ▼
ScreenTranslationSession (state machine: Idle → Capturing → Extracting
        │                                  → Translating → Rendering → Showing
        │                                  → Dismissed/Error)
        ├── CaptureSource (A11y takeScreenshot / MediaProjection fallback)
        ├── Extractor (A11y nodes first; raster OCR only if budget allows)
        ├── BlockBuilder (paragraph grouping, language per group, filters)
        ├── TranslatorRouter (per-pair pool; ML Kit / LLM; entity shield)
        ├── ArLayoutEngine (pure Kotlin, fit-in-box, no cross-block movement)
        └── ArOverlayView (TYPE_ACCESSIBILITY_OVERLAY, animations, actions)
```

Ключевые принципы:

1. **Один сервис и одна сессия.** `LiveScreenTranslateService` и `ScreenCaptureService` либо удаляются, либо становятся тонкими адаптерами. Все результаты помечаются `sessionId`; результат старой сессии отбрасывается.
2. **Никаких сдвигов через чужие блоки.** Каждый перевод обязан поместиться в исходный блок; если не помещается — уменьшить шрифт, затем ellipsis + раскрытие по тапу. Расширение вниз разрешено только в измеренный «пустой» зазор до ближайшего соседа.
3. **Маски — только исходные.** `eraseRect` и line-маски привязаны к `srcRect`; при переносе текста внутри блока маски не двигаются за пределы `srcRect + bleed`. Это одной правкой убирает большинство «пятен».
4. **Реальный фон.** Вместо среднего цвета — blur/clone patch из окрестностей + blend; опционально edge-aware inpaint. Как минимум: downscale-размытие + сохранение градиента, без резких прямоугольников.
5. **Снимок и дерево — атомарно.** Сначала скриншот, затем дерево, с проверкой, что окно/scroll не изменились; иначе один retry. Сканер/пузырь скрывать перед capture (`View.INVISIBLE` на время кадра).
6. **Смешанные языки — группы.** Группировать блоки по определённому языку; для каждой группы — свой Translator; LLM только там, где нужен.
7. **Ориентация/размеры — из `WindowMetrics`.** На `onConfigurationChanged`/`onDisplayChanged` — либо пересчёт, либо корректное закрытие с подсказкой «повторите».
8. **Доступность.** `sp` вместо `dp` для текста, `contentDescription`, локализованные строки, полный список языков, RTL.
9. **Триггер — явный и диагностируемый.** См. §2.7.

### 2.7 Как сделать кнопки громкости по-настоящему

Android не даёт обычному приложению глобально перехватывать томы. Есть два легальных пути, и оба нужно оформить как настройку с диагностикой:

**A. Системный accessibility shortcut (основной, поддерживаемый):**

- сервис уже объявляет `flagRequestAccessibilityButton`;
- добавить в настройки экран «Триггер»:
  - статус сервиса (`isConnected`);
  - `accessibilityButtonController.isAccessibilityButtonAvailable`;
  - кнопку «Открыть настройки Accessibility» (уже есть);
  - пошаговую инструкцию под OEM (ColorOS/Xiaomi/Samsung/Pixel) с картинками;
  - кнопку «Проверить» → вызывает тот же путь, что и shortcut, и показывает toast/лог;
  - отображение «последний триггер: 12:34, источник: accessibility shortcut»;
- `onClicked` должен работать даже без пузыря и без projection (one-shot);
- если `isAccessibilityButtonAvailable == false` — предлагать QS tile/пузырь/Assistant.

**B. Прямой filter key events (опционально, «экспериментально»):**

- `android:canRequestFilterKeyEvents="true"` + `flagRequestFilterKeyEvents`;
- `override fun onKeyEvent(event: KeyEvent): Boolean`;
- детект chord VolumeUp+VolumeDown с таймаутом (например, 350 мс) и защитой от автоповтора;
- проглатывать **только** события chord, обычные томы пропускать;
- обязательно настройка «перехватывать кнопки громкости» с предупреждением, что на части прошивок/в Play-политике это может быть ограничено;
- не пытаться делать это через `TYPE_APPLICATION_OVERLAY` с фокусом — это ломает управление приложением под оверлеем.

**C. Резервные триггеры (обязательны):** QS tile (есть), плавающий пузырь (есть), Assistant (`AssistTriggerActivity`), action в уведомлении, шорткат launcher, `PROCESS_TEXT`.

Acceptance для триггера:

- на чистом Pixel/OnePlus после настройки shortcut: удержание VolumeUp+VolumeDown → оверлей ≤ 400 мс;
- кнопка «Проверить» в настройках воспроизводит тот же результат;
- если shortcut недоступен — UI явно объясняет альтернативу, а не молчит;
- при выключенной настройке обычная регулировка громкости не меняется;
- 20 последовательных срабатываний — без утечек и зависаний.

### 2.8 План фаз по экрану

| Фаза | Приоритет | Содержание | Acceptance |
|---|---|---|---|
| **Z.2 Screen Session Core** | P0 | Один сервис/сессия; state machine; sessionId; отмена; убрать static pending; скрывать пузырь/сканер перед capture; `WindowMetrics`; rotation; удалить/адаптировать LiveScreenTranslateService; починить `ACTION_SHOW_TRANSLATION` | Нет устаревших оверлеев после rotation/смены окна; 20 триггеров без утечек; one-shot корректен; нет `ACTION_SHOW_TRANSLATION` в никуда |
| **Z.3 Layout & Inpainting** | P0 | Fit-in-box без сдвигов; маски только `srcRect`; реальный фон; ellipsis; line-height/типографика; убрать `linePills` из erase; TQI v2 на production layout | 0 закрасок вне `srcRect ± bleed`; 0 пересечений; текст не выходит за блок без ellipsis; визуальная приёмка на 6 реальных экранах |
| **Z.4 Trigger & UX** | P0 | Volume chord/shortcut, диагностика, onboarding, QS/assistant fallback; Pixel-подобные анимации (fade/scale/stagger/haptic); полный список языков; локализация; a11y; действия на блоке | Volume shortcut ≤400 мс; кнопка «Проверить»; TalkBack; RU/EN/ZH; нет hardcoded English в оверлее |
| **Z.5 Live/Scroll (опционально)** | P1 | Отслеживание scroll/window events; «экран изменился — обновить»; авто-пересчёт по debounce; live-режим только если реально нужен | Оверлей не висит над изменившимся экраном; есть chip «Обновить» / авто-refresh |

---

## 3. Камера (P0)

### 3.1 Наблюдаемые причины «камера — ужас»

| # | Проблема | Код | Эффект |
|---|---|---|---|
| 1 | **`cropRect` игнорируется** при live-OCR | `OcrEngine.recognizeLive()` `:298-336`: `InputImage.fromMediaImage(mediaImage, rotation)` без crop; координаты и `imageWidth/Height` — полного кадра | CameraX `UseCaseGroup` + `ViewPort` отдаёт обрезанный кадр, а оверлей считает координаты по полному → блоки смещены, особенно на 4:3-сенсорах и при FILL_CENTER |
| 2 | **Не задано разрешение анализа** | `CameraScreen.kt:1428-1436`: `ImageAnalysis.Builder()` без `setTargetResolution`/`ResolutionSelector` | анализ часто 640×480 → мелкий текст не читается, боксы дрожат |
| 3 | **До 3 OCR-проходов на кадр** | `CameraViewModel.kt:788-802`: auto-режим берёт по одному backend из Latin/Chinese/Devanagari и делает `map { recognizeCaptureAttempt }` | 3× задержка, нагрев, батарея, 1–5 FPS вместо «live» |
| 4 | **`runBlocking` в кадровом цикле** | `CameraViewModel.kt:719-731`: субтитры переводятся через `runBlocking { fastTranslateEngine.translateLines() }` | блокирует IO-поток, кадры копятся/дропаются |
| 5 | **Чёрные плашки 64% + fake bold** | `CameraScreen.kt:1555-1605` | перекрывает исходный текст и изображение, выглядит как «мусор»; текст обрезается 1–2 строками с ellipsis |
| 6 | **Перевод по строкам, а не по абзацам** | `CameraViewModel.paintOnBitmap()` `:2766-2860` | каждая OCR-строка переводится отдельно → разрушенный порядок слов, обрезанные фразы, «каша» |
| 7 | **Сплошная заливка фона** | `paintOnBitmap()`: `drawRoundRect` цветом `sampleBackgroundColor`, alpha 232 | на фото/градиенте — цветные прямоугольники; исходник уничтожается безвозвратно |
| 8 | **Документный режим закрашивает всю страницу** | `paintDocumentRegionsOnBitmap()` `:2692-2759`: `drawRect(pageBox, pageColor)` | исчезают картинки, таблицы, разделители, номера страниц; остаётся один текст |
| 9 | **Даунскейл до 1920 перед OCR** | `prepareCaptureBitmap()` `:1031-1043` | теряется мелкий шрифт; OCR ошибается; качество снимка хуже, чем позволяла камера |
| 10 | **Глобальный `release()` из ViewModel** | `CameraViewModel.onCleared()` `:3078-3081`: `fastTranslateEngine.release()` | выход с камеры убивает переводчик для экрана/диалога; при активном использовании — гонка/пустой перевод |
| 11 | **LiteRT не поддерживается в камере** | `ensureLlmModelLoaded()` `:518-533` всегда грузит GGUF `TranslationEngine`, не смотрит `ModelRuntime.LITERT_LM` | при выбранной LiteRT-модели Quality-режим не работает/падает |
| 12 | **Смешанные языки теряются** | `BilingualParagraph` в `:1268-1285` берёт `effectiveSourceLanguage` для всех блоков | словарь/поиск/перевод по неверному языку для иноязычных строк |
| 13 | **TTS не инициализирован** | `CameraScreen`/`CameraViewModel` не вызывают `systemTts.initialize()` | кнопка «Слушать» может молчать, пока пользователь не откроет Text/Dialogue |
| 14 | **Нет таймаутов LLM** | `translateCaptureLinesWithMainModel()` `:2351-2362` | зависший/медленный GGUF = вечный `PROCESSING` без отмены |
| 15 | **Нет инструментальных тестов** | нет `androidTest` | качество картинки/координат не проверяется автоматически |
| 16 | **Нет стабилизации live-картинки** | `TranslationOverlay` каждый кадр перерисовывает боксы | мигание, дрожание, «прыгающие» переводы |
| 17 | **Нет нормального zoom/exposure/torch UX** | в `LiveCameraView` есть только tap-to-focus | на реальных сценах (меню, вывески) сложно навести/поймать свет |
| 18 | **Галерея без persistable URI** | `GetContent` + `decodeGalleryBitmap` | после перезапуска процесса изображение может стать недоступно |

### 3.2 Что должна делать камера (целевой дизайн)

**Режим Live (видоискатель)** — это не «перевод поверх видео», а стабильный вспомогательный слой:

- `ImageAnalysis` 1280×720 или 1920×1080, `ResolutionSelector`, `OUTPUT_IMAGE_FORMAT_RGBA_8888` (или корректный YUV с crop), `STRATEGY_KEEP_ONLY_LATEST`, один inflight-запрос.
- Один проход OCR на кадр; auto-язык определяется по миниатюре раз в ~1 с, а не тремя проходами каждый кадр.
- Наложение: либо компактная нижняя «плашка субтитров» (как Google Translate live), либо in-place только для стабильных треков (EMA), без чёрных прямоугольников и обрезки.
- Адаптивный throttle по фактическому времени OCR (цель ≤ 150 мс на кадр), пропуск кадров, отключение тяжёлых эффектов при нагреве.
- Никаких `runBlocking`; перевод — suspend/Flow, результаты приходят с меткой кадра.

**Режим Capture/Scan (снимок/документ)**:

- Полное разрешение сенсора → OCR **до** даунскейла; ориентация/EXIF.
- Детект документа/перспективы (ML Kit Document Scanner или собственный контур), кроп.
- Группировка строк в абзацы/колонки/таблицы; перевод абзацами; сохранение порядка.
- Рендер in-place с переносом абзаца, подгонкой шрифта, реальной зачисткой фона; изображения/таблицы не закрашивать (или явный режим «только текст»).
- Экран результата: переключатель «Оригинал / Перевод / Бок-о-бок», зум, тап по абзацу, словарь, TTS.
- Ограничение памяти и отмена при уходе с экрана; освобождение bitmap.

### 3.3 План фаз по камере

| Фаза | Приоритет | Содержание | Acceptance |
|---|---|---|---|
| **C4 Camera Live Reliability** | P0 | cropRect, resolution selector, один OCR/кадр, убрать `runBlocking`, throttling, стабильный оверлей, убрать глобальный `release()`, LiteRT, TTS init, таймауты | Live ≥ 24 FPS preview; OCR p50 ≤ 180 мс; ошибка координат ≤ 1% ширины; 10 минут без перегрева/утечек |
| **C5 Camera Capture Quality** | P1 | full-res OCR, абзацы, reflow, документный кроп, замена baked bitmap на overlay/toggle, качество фона | Текст документа не обрезан; картинки/таблицы сохранены; перевод читаем на A4 при 100% зуме |
| **C6 Camera UX/Docs** | P2 | зум/экспозиция/фонарик, подсказки качества, onboarding, тесты на устройстве | Пользовательский сценарий «навёл → снял → прочитал» без тупиков |

---

## 4. Голосовой перевод (P0/P1)

### 4.1 Критические дефекты

| # | Проблема | Код | Эффект |
|---|---|---|---|
| 1 | **TTS без таймаута** | `SystemTtsEngine.speakAndWait()` `:53-97` | если `onDone/onError` не пришёл (нет voice data, ошибка движка, `QUEUE_FLUSH`, kill), корутина висит вечно; `turnMutex` (`DialogueViewModel.kt:390`) не отпускается → диалог мёртв до перезапуска |
| 2 | **Слушатель TTS перезаписывается** | `:67-88`, `speak()` `:100-107` | глобальный `setOnUtteranceProgressListener` без фильтра по `utteranceId`; параллельный `speak()`/`speakAndWait()` может завершить не ту корутину |
| 3 | **`engine.speak()` результат игнорируется** | `:90` | `TextToSpeech.ERROR` не приводит к `onError`; продолжение висит |
| 4 | **TTS инициализируется только из UI** | `DialogueViewModel.kt:121`, `TranslationScreen.kt:66` | камера/словарь/история могут молчать |
| 5 | **Распознавание блокирует чтение аудио** | `SpeechEngine.startListening()` `:243-284`: `recognizeSegment()` вызывается прямо в цикле `AudioRecord.read` | пока Whisper декодирует 0.5–3 с, микрофон не читается; фразы теряются/обрезаются |
| 6 | **Нет backpressure/очереди ходов** | `onResult` → `viewModelScope.launch` на каждый VAD-сегмент; `turnMutex` только сериализует | при длинном TTS/переводе сегменты копятся; пользователь говорит уже другое, а слышит старое |
| 7 | **Нет audio focus / режима связи** | нигде нет `AudioManager`, `requestAudioFocus`, `MODE_IN_COMMUNICATION`, speakerphone | TTS душится музыкой/другими приложениями; эхо и «само-перевод» |
| 8 | **AEC — только software gate** | `notifyTtsPlaybackStarted/Finished`, 300 мс cooldown | `AudioRecord` c `VOICE_RECOGNITION` не гарантирует подавление громкого TTS; после 300 мс эхо возвращается |
| 9 | **Захардкоженная длительность реплики** | `DialogueViewModel.kt:447`: `audioDurationMs = 2500L` | неверные позиции в аудио-таймлайне/статистике, скраб не совпадает |
| 10 | **`writePcmSamples` игнорирует `readCount`** | `DialogueAudioRecorder.kt:78-86` | в файл попадает «хвост» старого буфера; шум/щелчки |
| 11 | **Гонка stop/encode** | `:88-107`, `AacMediaCodecEncoder` | `stopSession()` может освобождать muxer, пока worker пишет; битый m4a |
| 12 | **`onCleared` не закрывает запись** | `DialogueViewModel.kt:609-613` | при уходе с экрана сессия/файл не финализируются |
| 13 | **Глобальный `SpeechEngine.release()`** | `ModelManagerViewModel.kt:688,700,709` | удаление/смена STT-модели во время диалога рвёт `AudioRecord`/recognizer |
| 14 | **Нет streaming partials** | `SpeechEngine` отдаёт только финальный текст после VAD | пользователь не видит, что его слышат; нет barge-in |
| 15 | **«Мульти-движок STT» — только каталог** | `SttEngineComponents.kt` не используется | Zipformer/SenseVoice не реализованы, хотя заявлены |
| 16 | **Логи содержат текст реплики** | `DialogueViewModel.kt:413` | противоречит «no logging of translated phrases» в README |
| 17 | **Нет таймаута/отмены LLM** | `translateWithActiveRuntime()` | модель может «зациклиться», ход не завершится |
| 18 | **Нет внешнего AEC/NS** | — | шумные улицы/кафе → WER резко растёт |

### 4.2 Целевая архитектура голоса

```
AudioRecord (VOICE_COMMUNICATION) ── ring buffer ──► VAD thread
                                              │
                                              └──► Recognizer worker (streaming + final)
                                                      │ partial/final events
                                                      ▼
                                              DialogueTurnQueue (cancelable, bounded)
                                                      │
                                              TranslatorRouter (pair pool)
                                                      ▼
                                              TTS (utteranceId, timeout, watchdog, audio focus)
                                                      │
                                              AEC gate + cooldown + barge-in
```

Обязательные свойства:

- `AudioRecord` читается отдельным потоком; обработка (VAD/ASR/перевод) никогда его не блокирует.
- Очередь ходов ограничена (например, 1 активный + 1 ожидающий); новый ход отменяет старый, если пользователь заговорил.
- TTS: уникальный `utteranceId`, таймаут 10–15 с, watchdog на `onDone`, отмена по стоп-кнопке, отдельный listener на utterance.
- `AudioManager`: audio focus, `MODE_IN_COMMUNICATION`, speakerphone для диалога; `AcousticEchoCanceler`/`NoiseSuppressor` при поддержке.
- Точные `audioStartTimeMs`/`audioDurationMs` из recorder, а не 2500.
- Частичные гипотезы в UI (если выбран streaming-движок); финальный текст — Whisper/Qwen.
- `release()` только из владельца жизненного цикла; централизованный `SpeechSession`.
- Никакого текста реплик в logcat (только метрики/коды).

### 4.3 План фаз по голосу

| Фаза | Приоритет | Содержание | Acceptance |
|---|---|---|---|
| **D4 Voice Core Reliability** | P0 | Отдельный аудио-поток + очередь; TTS timeout/watchdog/utteranceId; audio focus/AEC; точные длительности; финализация записи; убрать глобальные release; таймауты LLM | 15-минутный диалог без зависаний; TTS-сбой восстанавливается ≤3 с; 0 само-эха в 10 сценариях; запись валидна |
| **D5 Voice UX/Quality** | P1 | Partial-текст, barge-in, прерывание, индикация фаз, повтор/стоп, language lock; реальные Zipformer/SenseVoice или удаление заявлений | Первый partial ≤500 мс после начала речи; перебивание работает; понятная индикация «слушаю/перевожу/говорю» |
| **D6 Voice Accuracy** | P2 | Шумоподавление, калибровка VAD, WER-бенчмарк, выбор модели под устройство | WER на русском/английском в шумном окружении не хуже baseline; замеры в отчёте |

---

## 5. Сквозные архитектурные проблемы

### 5.1 Один ML Kit-переводчик на все сценарии (P0)

`FastTranslateEngine` (`@Singleton`) хранит **один** `currentTranslator`, `currentSourceLang`, `currentTargetLang`. `activateDownloadedPair()` (`:396-437`) закрывает предыдущий переводчик и создаёт новый. Любой экран/сервис, вызвавший `activateDownloadedPair()` или `translate()`:

- меняет пару для всех остальных;
- может закрыть `Translator`, пока другой сценарий выполняет перевод;
- при неудачной активации (`_isReady=false`) `translate()` возвращает **исходный текст** — выглядит как «перевод не работает».

`isReadyFor()` проверяет только текущую пару, поэтому экран/камера считают, что модели нет, и молча пропускают перевод.

**Решение:** `TranslatorPool` с ключом `(src,dst)`, LRU (например, 4 пары), счётчиком inflight; `translate(text, src, dst)` выбирает пару; `release()` — только при выгрузке моделей/выходе, не из ViewModel. Никогда не закрывать переводчик с активными запросами.

### 5.2 Жизненный цикл LLM-модели (P1)

- Загрузка/выгрузка размазана по `TranslationViewModel`, `DialogueViewModel`, `CameraViewModel`, `SettingsViewModel`, `ModelManagerViewModel`, `TranslationBenchmarkEngine`.
- `CameraViewModel.ensureLlmModelLoaded()` не знает про LiteRT.
- `idleTimeoutMinutes` работает только пока жив `TranslationViewModel`; при уходе с экрана таймер отменяется (`TranslationViewModel.kt:829-835`), модель остаётся в RAM навсегда.
- Таймер может выгрузить модель во время активного диалога → `IllegalStateException("Модель перевода не загружена")`.

**Решение:** `ModelSessionManager` (singleton): refcount по владельцам (`Text`, `Dialogue`, `Camera`, `Screen`, `Document`), единая загрузка с учётом runtime (GGUF/LiteRT), idle unload только при refcount=0 и отсутствии inflight, событие «модель выгружена» для UI.

### 5.3 Мёртвый код и «призрачные» функции (P1)

Поиск использований показывает, что следующие классы/файлы **не используются в production** (только определение/тесты):

- `VisionLlmComponents.kt` — весь файл: `VisionLlmCatalog`, `ImageDimensionScaler`, `VisionPromptBuilder`, `StreamingTextAccumulator` и др. При этом README/PROJECT_STAGE заявляют «On-Device Vision LLM & Screen Analysis».
- `SttEngineComponents.kt` — весь файл: `SttEngineRegistry`, `SttEngineSelector`, `TwoPassSttPipeline`, Zipformer/SenseVoice-дескрипторы. Заявлено 4 движка, реально 2.
- `ScreenOverlayLifecycleComponents.kt` — `FloatingBubbleStateMachine`, `ScreenCaptureSessionManager`, `CaptureDebounceMutex`, `OverlayLayoutCalculator`, `ArOverlayWindowFlags`, `ArOverlayBackController`, `FabMenuLayoutCalculator` и др. Ни один не подключён к сервису/вью.
- `DownloadMetricsComponents.kt` — `DownloadMetricsTracker`, `CompositeDownloadTracker`, `ComponentDownloadItem`.
- `FastModelSyncComponents.kt` — `ArchiveValidationResult` (частично).
- `proguard-rules.pro` — keep для несуществующих `CameraTranslateEngine`, `TtsEngine`.

Тесты на эти классы создают ложное покрытие (`ScreenOverlayLifecycleTest` и др.).

**Решение:** либо интегрировать, либо удалить и убрать из документации. Не оставлять «каталоги ради каталогов».

### 5.4 Тестовая стратегия (P0 для доверия)

- JVM-тесты должны вызывать production-код. Для геометрии вынести `ArLayoutEngine` в чистый Kotlin (без `android.graphics`), тестировать его.
- Для `ArTranslateOverlayView` — Robolectric `@GraphicsMode(NATIVE)` или Paparazzi screenshot-тесты; сравнивать golden PNG.
- `androidTest` на устройстве/эмуляторе: capture → overlay → dismiss, rotation, permission denial, low memory.
- Макробенчмарки: время от триггера до первого блока, полного рендера; FPS камеры; OCR latency; voice round-trip.
- Фикстуры — реальные скриншоты 6–8 архетипов (WebView, мессенджер, карточки, документ, таблица, RTL/CJK) с ожидаемыми боксами; тест проверяет **финальные** прямоугольники, маски, переносы.
- CI: unit + lint на каждый PR; instrumentation — nightly/на устройстве.

### 5.5 i18n (P1)

- Базовый `values/strings.xml` — 392 строки, `values-ru` — 392, `values-zh-rCN`/`zh-rTW` — только **205** (≈52%). Китайский UI наполовину на английском.
- В Kotlin много захардкоженных русских строк: `ModelCatalog.kt` (38), `SettingsScreen.kt` (36), `ModelManagerScreen.kt` (21), `DocumentTranslateScreen.kt` (21), `ModelManagerViewModel.kt` (14) и др. Для EN-пользователя это выглядит как баг.
- Оверлей экранного перевода целиком на английском, включая названия языков.
- `AppLocale` поддерживает system/en/ru/zh-CN/zh-TW; RTL-языки (ar/he/fa/ur) есть в переводе, но UI оверлея не проверялся на RTL.
- `LocalizedTextProvider` использует application context; на Android <13 смена языка в рантайме может не дойти до сервисов/уведомлений.

**Решение:** вынести все строки в ресурсы, добить zh до 100%, добавить `values-ar`/RTL smoke, использовать `plurals`, проверить `pseudo-locales`, линтер на hardcoded strings.

### 5.6 Приватность, backup и данные (P1)

- `AndroidManifest.xml:18`: `android:allowBackup="true"` без `android:dataExtractionRules`/`fullBackupContent`. Auto Backup может отправлять в Google Drive:
  - историю переводов и диалогов (Room);
  - настройки;
  - аудиозаписи диалогов в `getExternalFilesDir("dialogues")`;
  - потенциально крупные модели (лимит 25 МБ, скорее всего backup будет падать, но пытаться будет).
  Для приложения, которое продаётся как «sovereign/offline/zero telemetry», это прямое противоречие.
- `AppModule.kt:24-29`: `fallbackToDestructiveMigration()` — при любом бампе версии Room **уничтожает** историю/словарь без предупреждения. Нужны явные миграции + тест миграций.
- `ModelCatalog.kt`: 27 вариантов, **только 2** с `sha256`. `ModelDownloadManager.verifyDownload()` при отсутствии хеша молча пропускает проверку (`:363-366`). README заявляет «pinned SHA-256 checksums». Нужно либо заполнить хеши для всех, либо явно помечать «unverified» и не обещать проверку.
- `DialogueViewModel.kt:413` логирует текст реплики в logcat.
- `AssistTriggerActivity` экспортирован и стартует overlay из любого приложения; нужен guard/разрешение или осознанное решение.
- `FileProvider` пути — только cache; ок.
- Сетевые запросы: HTTPS, но без certificate pinning; для модели/курсов это приемлемо, но стоит зафиксировать политику.

### 5.7 Сборка, CI и git-гигиена (P0/P1)

- Восстановить официальный `gradlew`/`gradlew.bat` (генерация `gradle wrapper --gradle-version 9.4.1` под JDK 21) или как минимум добавить `JAVA_HOME`-детект. Сейчас README-команды не работают на машине с Java 8 по умолчанию.
- Добавить `scripts/setup-native.ps1` и `.sh`:
  - `git submodule update --init --recursive`;
  - скачать/собрать stub `libOpenCL.so` под NDK 27.3 или собрать `OpenCL-Headers`;
  - checkout llama.cpp по SHA из `llama.cpp.version`;
  - опционально MNN по `MNN.version`;
  - проверить `CL/cl.h`, `libOpenCL.so`, `llama.cpp/CMakeLists.txt`.
- CI:
  - `actions/checkout` с `submodules: recursive`;
  - валидный набор `sdkmanager` (platform 36, build-tools 36.x, ndk 27.3, cmake 3.22.1);
  - шаг `:app:testDebugUnitTest` и `:app:lintDebug`;
  - кэш Gradle;
  - отдельная matrix-джоба `assembleDebug` со stub и с native;
  - публикация APK как artifact (не release).
- Git:
  - не хранить 3 версии AAR; оставить одну (или перейти на Maven-артефакт);
  - добавить `.gitattributes` для line endings? Желательно;
  - включить branch protection: PR + зелёный CI;
  - релизные коммиты отделять от фич (сейчас `feat(release)` смешивает код, версию и документацию);
  - обновлять `PROJECT_STAGE.md` (HEAD/date/version/SHA APK) в релизном коммите автоматически/вручную.
- Документация:
  - привести `README.md`, `HANDOFF.md`, `PROJECT_STAGE.md` к фактическому состоянию;
  - отметить экспериментальные/нереализованные фичи явно;
  - синхронизировать SHA llama.cpp;
  - добавить ссылку на этот аудит.

### 5.8 Крупные классы и дублирование (P2, но влияет на скорость修复)

- `CameraViewModel.kt` 2855 строк, `CameraScreen.kt` 1826, `ModelManagerScreen.kt` 1760, `SettingsScreen.kt` 1434, `ScreenTranslateOverlayService.kt` 1133, `ArTranslateOverlayView.kt` 1045, `ModelManagerViewModel.kt` 1010.
- Логика загрузки модели продублирована 3 раза в `TranslationViewModel` (FAST_IMPROVE, LLM_DIRECT, reload), плюс в `DialogueViewModel`, `CameraViewModel`, `SettingsViewModel`.
- Два экранных пайплайна (`LiveScreenTranslateService` и `ScreenTranslateOverlayService`) и один полумёртвый (`ScreenCaptureService`).
- Дублирование форматирования/языковых каталогов между камерой и экраном.

Рекомендация: сначала стабилизировать поведение (фазы Z/C/D), затем извлекать `ScreenTranslationSession`, `ArLayoutEngine`, `ModelSessionManager`, `TranslatorPool`, `AudioSession` в отдельные классы с тестами.

---

## 6. Приоритетный план восстановления

Порядок выбран так, чтобы сначала получить **зелёный CI и честную картину**, затем починить экран (главная ценность), потом голос (самый хрупкий), затем камеру (самый объёмный), затем качество/полировку. Одна фаза — один осмысленный коммит (по правилам проекта).

### Stage 0 — Engineering Baseline (1–2 дня)

| ID | Задача | Файлы | DoD |
|---|---|---|---|
| A0.1 | Официальный Gradle wrapper, JDK 21 | `gradlew`, `gradlew.bat` | `.\gradlew.bat --version` работает при JDK 21; README не врёт |
| A0.2 | Setup-скрипт native + OpenCL stub | `scripts/setup-native.*`, `CMakeLists.txt` (опция `PARLEX_OPENCL=OFF`) | чистый клон + скрипт → `assembleDebug` с native; CI зелёный |
| A0.3 | CI: submodules, SDK, tests, lint, artifacts | `.github/workflows/android-check.yml` | 3 последовательных зелёных run; тесты и lint в PR |
| A0.4 | Убрать 2 лишних AAR | `app/libs/` | размер pack уменьшен ~105 MB; сборка не меняется |
| A0.5 | Backup/миграции | `AndroidManifest.xml`, `res/xml/*backup*`, `AppModule.kt` | backup исключает аудио/историю/модели; миграции покрыты тестом |
| A0.6 | Доки: HEAD/date/SHA/фичи | `PROJECT_STAGE.md`, `README.md`, `HANDOFF.md`, `ROADMAP.md` | нет расхождений с кодом; ссылка на аудит |
| A0.7 | Q1: каркас тестов | `app/src/androidTest`, `app/src/test` | smoke-тест запускается на эмуляторе; CI/nightly job |

### Stage 1 — Screen Translation (P0, основная ценность)

| ID | Задача | DoD |
|---|---|---|
| **Z.2.1** | `ScreenTranslationSession` + service refactor: один сервис, sessionId, cancel, no static pending, no stale results | 20 триггеров/10 отмен без утечек; повторный триггер отменяет предыдущий |
| **Z.2.2** | Capture: скрыть пузырь/сканер, screenshot+tree атомарно, retry, отказ от `START_STICKY` для one-shot | На скриншоте нет окон Parlex; координаты совпадают после 100 попыток на 3 устройствах |
| **Z.2.3** | Rotation/window/metrics: `WindowMetrics`, `onDisplayChanged`, dismiss/rescan | Поворот не оставляет «старый» оверлей; сплит-скрин корректен |
| **Z.2.4** | Удалить/адаптировать `LiveScreenTranslateService`; починить `ACTION_SHOW_TRANSLATION` | В коде один путь; мёртвых action нет |
| **Z.3.1** | `ArLayoutEngine`: fit-in-box, max height, ellipsis, no cross-block shift | 0 выходов за блок; 0 пересечений; тесты на pure Kotlin |
| **Z.3.2** | Маски: erase только `srcRect`/line pills без сдвига; bleed clamp | 0 закрасок вне `srcRect±3dp` на 6 фикстурах |
| **Z.3.3** | Фон: blur/clone patch, цвет, контраст; без «заплаток» | Визуальная приёмка на градиентах/фото/тёмной теме |
| **Z.3.4** | Типографика: sp, baseline, line-height, шрифт по роли, переносы | Читаемо на 320–1440 dp; системный масштаб шрифта работает |
| **Z.3.5** | TQI v2: меряет production layout; golden-тесты | TQI падает при регрессии раскладки; CI screenshot diff |
| **Z.4.1** | Триггер: diagnostic card, `isAccessibilityButtonAvailable`, инструкции OEM, «Проверить» | Volume shortcut ≤400 мс на OnePlus/Pixel; понятная ошибка на ColorOS |
| **Z.4.2** | Volume chord через `onKeyEvent` (opt-in) | Обычная громкость не ломается; chord срабатывает; выключено по умолчанию |
| **Z.4.3** | Pixel-like анимация: freeze zoom/fade, stagger блоков, haptic, scan | 60 fps; нет резких появлений; длительность 150–250 мс |
| **Z.4.4** | Actions/a11y/i18n: Copy/Speak/Original/Hide, полный список языков, RU/EN/ZH, TalkBack | TalkBack проходит сценарий; нет hardcoded English |
| **Z.5** | Scroll/window changes: chip «Обновить» / auto-rescan | Оверлей не врёт после скролла |

### Stage 2 — Voice (P0)

| ID | Задача | DoD |
|---|---|---|
| D4.1 | Audio thread + ring buffer; recognition вне read-loop | 0 дропов на 30-секундной фразе; WER не хуже |
| D4.2 | TTS timeout/watchdog/utteranceId + обработка `ERROR` | 100 циклов TTS без зависаний; принудительный сбой восстанавливается ≤3 с |
| D4.3 | Audio focus + MODE_IN_COMMUNICATION + AEC/NS | 10 минут диалога без self-echo; музыка приглушается корректно |
| D4.4 | Очередь ходов/barge-in/cancel | Новый ход отменяет старый; UI не отстаёт |
| D4.5 | Recording: readCount, точные offset/duration, финализация | m4a/wav валидны; скраб ±100 мс; onCleared закрывает файл |
| D4.6 | Shared engine lifecycle | `release()` не из ViewModel; смена STT-модели не рвёт диалог |

### Stage 3 — Camera (P0/P1)

| ID | Задача | DoD |
|---|---|---|
| C4.1 | cropRect + ResolutionSelector + RGBA/YUV корректно | Ошибка координат ≤1% на 4:3/16:9/повороте |
| C4.2 | Один OCR/кадр; auto-language probe 1 Гц; adaptive throttle | ≥24 FPS preview; OCR p50 ≤180 мс; CPU/темп в норме |
| C4.3 | Overlay: без чёрных плашек, стабильные боксы, читаемый текст | Нет мигания; текст не обрезан без ellipsis |
| C4.4 | Убрать `runBlocking`, глобальный `release`, добавить LiteRT/таймауты | Смена экрана не ломает перевод; Quality работает на LiteRT |
| C5.1 | Full-res OCR + абзацы + reflow | Документ читаем; порядок слов сохранён |
| C5.2 | Документный режим: не закрашивать изображения/таблицы | Картинки/таблицы на месте; есть режим «только текст» |
| C5.3 | Экран результата: Original/Translated/Side-by-side, зум, словарь | Пользователь может сравнить и скопировать |

### Stage 4 — Hygiene/quality (P1/P2)

- **H1:** SHA-256 для всех моделей или явная маркировка; лог-гигиена; экспортированные компоненты; `allowBackup`.
- **H2:** i18n 100% zh + вынос хардкода; RTL smoke.
- **M2:** `TranslatorPool` + `ModelSessionManager` + idle unload.
- **Q2:** макробенчмарки и nightly instrumentation.
- **D5:** streaming partials + реальные STT-движки или удаление из доков.
- **D6/C6:** точность/UX.

### Что делать в первые 48 часов (quick wins)

1. Починить `gradlew.bat`/CI и получить зелёный run — иначе любая работа не проверяема.
2. Убрать сдвиг `linePills` из erase-пасса (Z.3.2) — это самая заметная «каша».
3. Заменить `resolveOverlaps` на fit-in-box для блоков (Z.3.1) — без сдвигов через чужие блоки.
4. Отключить `FULL_SCAN` по умолчанию / вынести `OcrMode` в настройки (Z.2/настройки).
5. В `SystemTtsEngine.speakAndWait` добавить `withTimeout` + `utteranceId`-фильтр (D4.2) — убирает «намертво зависший» диалог.
6. В камере передать `cropRect` в OCR и поставить resolution selector (C4.1–C4.2).
7. Убрать `fastTranslateEngine.release()` из `CameraViewModel.onCleared()` (C4.4).
8. Обновить `PROJECT_STAGE.md`/README, чтобы не обещать неработающее.

---

## 7. Definition of Done и приёмка

**Для каждой фазы:**

- [ ] Код собирается (`compileDebugKotlin`) и проходит unit-тесты.
- [ ] Новые/изменённые тесты вызывают production-код и падают при регрессии.
- [ ] Есть ручной чек-лист на устройстве (см. матрицу).
- [ ] Нет новых ошибок lint; нет `TODO/FIXME` в затронутых файлах.
- [ ] Обновлены `PROJECT_STAGE.md`/`HANDOFF.md`/`ROADMAP.md`, если фаза завершена.
- [ ] Для релиза: `versionCode`/`versionName`, подпись, `apksigner verify`, SHA-256 APK, release notes.
- [ ] В логах нет пользовательского текста/аудио.
- [ ] Замеры latency/памяти приложены к отчёту.

**Матрица устройств (минимум):**

- Android 11 (проверка `takeScreenshot` fallback), Android 13, 14, 15/16.
- OnePlus 13 (Snapdragon 8 Elite, ColorOS), Pixel 8/9 (stock), Samsung (One UI), Xiaomi/HyperOS, устройство 6 GB RAM.
- Планшет/складной, сплит-скрин, поворот, cutout, жестовая навигация.
- RU/EN/ZH, RTL (ar/he), CJK, длинные строки, крупный системный шрифт.
- Тёмная/светлая тема, обои/фото/градиент под текстом.

**Метрики приёмки (целевые):**

| Метрика | Цель |
|---|---|
| Триггер → первый переведённый блок | p50 ≤ 400 мс, p90 ≤ 900 мс |
| Полный рендер экрана (A11y-only) | p90 ≤ 1.2 с |
| Полный рендер с OCR | p90 ≤ 2.5 с (и только по запросу) |
| Закраски вне `srcRect±3dp` | 0 |
| Пересечения блоков | 0 |
| Выход текста за блок без ellipsis | 0 |
| Утечки bitmap/окон за 20 циклов | 0 |
| Camera preview FPS | ≥ 24 |
| Camera OCR p50 (1280×720) | ≤ 180 мс |
| Voice: зависания за 15 мин | 0 |
| Voice: конец речи → старт TTS (Fast) | p50 ≤ 1.5 с |
| Voice: self-echo | 0 в 10 сценариях |
| Crash-free (внутренняя бета) | ≥ 99.5% |

---

## 8. Что проверено, а что требует телефона

**Проверено фактически:** клон, remote, HEAD, структура, сборка Kotlin, unit-тесты, debug APK, CI-логи GitHub, содержимое кода, наличие/отсутствие символов, размеры, i18n-ресурсы, каталог моделей, мёртвый код.

**Требует устройства (не проверено):**

- реальное срабатывание accessibility shortcut на ColorOS/OnePlus/Pixel;
- точность координат overlay на cutout/жестовой навигации/сплит-скрине;
- реальная задержка `takeScreenshot` и ML Kit/Tesseract на устройстве;
- поведение `MediaProjection`/FGS на Android 14–16;
- AEC/эхо, качество Whisper/Qwen3 в шуме;
- нагрев/троттлинг камеры и NPU;
- поведение TTS при отсутствии voice data.

Эти пункты нужно закрыть в Stage 0 (тестовая инфраструктура) и в ручной приёмке.

---

## 9. Политика коммитов и remote

- Remote подтверждён: `origin` = `https://github.com/RandoTeam/Parlex.git`, fetch/push.
- Ветка: `main`. Все коммиты этой работы ведутся в `origin/main`.
- Формат: Conventional Commits, scope по модулю: `fix(screen):`, `feat(camera):`, `refactor(voice):`, `chore(ci):`, `docs(audit):`.
- Один коммит = одна фаза/логическая единица; не смешивать релиз, фичу и документацию.
- Перед push: `:app:testDebugUnitTest` (+ `:app:lintDebug` после A0.3), `git status` чистый, CI зелёный.
- Релиз: только после зелёного CI, с проверкой подписи и `versionCode`/`versionName`; тег и APK — отдельным шагом.
- Force-push в `main` не использовать; для крупных изменений — ветка + PR.

---

## 10. Приложение A. Команды и результаты

```powershell
# Clone
git clone https://github.com/RandoTeam/Parlex.git C:\VietnAi\Parlex

# Build (JDK 21 forced into PATH because of broken gradlew.bat)
$env:JAVA_HOME = 'C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot'
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
.\gradlew.bat :app:compileDebugKotlin --no-daemon --console=plain
# BUILD SUCCESSFUL in 3m 6s

.\gradlew.bat :app:testDebugUnitTest --no-daemon --console=plain
# BUILD SUCCESSFUL in 1m 33s
# 69 suites, 465 tests, 0 failures, 0 errors

.\gradlew.bat :app:assembleDebug --no-daemon --console=plain
# BUILD SUCCESSFUL in 1m 43s
# app-debug.apk 134.9 MB
# CMake warning: llama.cpp not found ... Building stub.
```

```text
adb devices
# List of devices attached
# (пусто)
```

CI (GitHub):

```text
gh run list --limit 10
# все 10 последних — failure
gh run view 34791014539 --log-failed
# CMake Error at CMakeLists.txt:32: OpenCL build prerequisites are missing
gh run view 34896003013 --log-failed
# sdkmanager failed: Failed to find package 'tools'
```

---

## 11. Приложение B. Индекс ключевых мест в коде

### Экран

- `app/src/main/kotlin/com/translive/app/service/accessibility/ScreenAccessibilityService.kt:76-105` — единственный триггер (a11y button callback); нет `onKeyEvent`.
- `app/src/main/res/xml/accessibility_service_config.xml:6` — flags без `flagRequestFilterKeyEvents`.
- `app/src/main/res/values/strings.xml:285-310` — обещания про volume keys.
- `service/ScreenTranslateOverlayService.kt:698-896` — пайплайн; `:707-721` merge; `:783-861` перевод; `:813-824` одна пара; `:846` batch; `:974-1040` retranslate; `:1042-1056` save; `:507-528` mutex; `:1194-1244` static pending; `:220-257` rotation.
- `ui/overlay/ArTranslateOverlayView.kt:73-82` — offset; `:391-407` linePills; `:455-530` resolveOverlaps; `:866-881` erase; `:834-846` updateBoxes; `:996-1009` per-card toggle; `:729-743` языки; `:850-899` draw; `:945-958` tap-to-dismiss.
- `service/capture/ScreenCaptureService.kt:104-107` — `ACTION_SHOW_TRANSLATION`, который не обрабатывается.
- `service/LiveScreenTranslateService.kt` — неиспользуемый второй пайплайн.

### Камера

- `engine/OcrEngine.kt:298-336` — live OCR без cropRect.
- `ui/screens/CameraScreen.kt:1428-1436` — ImageAnalysis без resolution.
- `ui/viewmodel/CameraViewModel.kt:637-760` — кадровый цикл; `:788-802` multi-OCR; `:719-731` runBlocking; `:2766-2860` paintOnBitmap; `:2692-2759` document paint; `:1268-1285` paragraph source; `:3078-3081` release; `:518-533` LiteRT; `:1031-1043` downscale.
- `ui/screens/CameraScreen.kt:1528-1607` — overlay.

### Голос

- `engine/SystemTtsEngine.kt:53-97` — TTS без таймаута; `:100-107` общий listener.
- `engine/SpeechEngine.kt:243-284` — блокирующее распознавание; `:321-327` гонка stop.
- `ui/viewmodel/DialogueViewModel.kt:386-515` — turn loop; `:434-450` длительность; `:484-492` TTS; `:609-613` onCleared.
- `engine/audio/DialogueAudioRecorder.kt:78-86`, `:88-107`.

### Сквозное

- `engine/FastTranslateEngine.kt:396-437` — единственная пара.
- `di/AppModule.kt:24-29` — destructive migration.
- `app/src/main/AndroidManifest.xml:18` — allowBackup.
- `app/src/main/kotlin/com/translive/app/data/model/ModelCatalog.kt` — 27 вариантов, 2 sha256.
- `app/src/main/cpp/CMakeLists.txt:27-37` — OpenCL prereq.
- `gradlew.bat` — игнорирует JAVA_HOME.
- `.github/workflows/android-check.yml` — CI.
- `<Component>Components.kt` — мёртвые каталоги (см. §5.3).
- `app/src/test/.../ArOverlayRenderingTest.kt:17-80` — тест собственных копий.
- `app/src/test/.../ArPerLineLayoutTest.kt:19-45` — то же.
- `app/src/test/.../tqi/TqiEvaluator.kt` — оценка исходных боксов.

---

## 12. Приложение C. Заявленные функции vs фактическая реализация

| Заявлено в README/PROJECT_STAGE | Факт в коде | Действие |
|---|---|---|
| Кнопки громкости / «Volume Key Decoupling» | Только системный a11y shortcut без проверки/инструкций | Реализовать Z.4, честно описать |
| Multi-engine STT: Zipformer, SenseVoice, Whisper, Qwen3 | Whisper и Qwen3 в `SpeechEngine`; Zipformer/SenseVoice — только дескрипторы | D5: реализовать или убрать из доков |
| Vision LLM (MiniCPM-V, SmolVLM-2, Gemma 4 Edge) | `VisionLlmComponents.kt` не используется | Убрать или реализовать отдельной фазой |
| Гибридный MNN PP-OCRv6 | MNN checkout отсутствует; native stub | A0.2: скрипт/артефакт или убрать заявление |
| TQI 100/100 | Синтетическая геометрия, не рендер | Z.3.5: TQI v2 на production |
| CI проверяет сборку | Красный 10/10 | A0.3 |
| Camera «partial, works well in clear light» | crop/resolution/3 OCR/чёрные плашки/обрезка | C4–C5 |
| Voice «beta, functional» | возможен вечный TTS-lock и потеря аудио | D4 |
| «Zero telemetry, no logging of phrases» | лог реплики в `DialogueViewModel.kt:413` | H1 |
| «Pinned SHA-256 checksums» | 2 из 27 | H1 |

---

## 13. Приложение D. Предлагаемая структура после рефакторинга

```
service/screen/
  ScreenTranslationService.kt        // один FGS, тонкий
  ScreenTranslationSession.kt        // state machine + sessionId
  ScreenCaptureSource.kt             // a11y screenshot / MediaProjection
  ScreenOverlayHost.kt               // window, TYPE_ACCESSIBILITY_OVERLAY, rotation
  ScreenBlockBuilder.kt              // a11y/OCR merge, grouping, language
  ScreenTranslationRouter.kt         // per-language pair, Fast/LLM
engine/layout/
  ArLayoutEngine.kt                  // pure Kotlin geometry, fit-in-box
  ArBackgroundPainter.kt             // blur/clone patch
  ArTypography.kt
engine/translate/
  TranslatorPool.kt                  // per-pair ML Kit, LRU
  ModelSessionManager.kt             // refcount, idle unload, GGUF/LiteRT
engine/audio/
  AudioCapturePipeline.kt            // thread + ring buffer
  DialogueTurnQueue.kt
  TtsController.kt                   // timeout, utteranceId, focus
ui/camera/
  CameraLiveOverlay.kt               // stabilized, no black boxes
  CameraCaptureController.kt         // full-res OCR, reflow
```

Эта структура не обязательна к дословному исполнению, но она задаёт границы, которые нужно получить, чтобы код снова стал тестируемым.

---

> **Главный вывод:** проблема не в том, что «не хватает полировки». Три ключевых сценария сломаны на уровне модели данных и жизненного цикла: экран двигает блоки и закрашивает не то; камера не знает про crop и рисует поверх изображения; голос блокирует сам себя и может зависнуть навсегда. Пока эти три вещи не переписаны по фазам Z.2–Z.4 / C4–C5 / D4–D5, добавление новых функций будет только увеличивать «кашу». Начинать нужно с A0 (зелёный CI и воспроизводимая сборка), затем Z.2–Z.4, затем D4, затем C4–C5.
