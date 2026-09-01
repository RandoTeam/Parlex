# Parlex Roadmap

Рабочее правило проекта: не больше одной фазы за раз. Каждая фаза заканчивается
проверкой, коротким отчетом и отдельным коммитом перед переходом дальше.

## Фаза R1: Восстановить Подписанный Beta Release

Приоритет: критический.

Цель: выпустить следующую beta только как подписанный release APK, совместимый с
уже опубликованной подписью приложения.

Шаги:

1. Восстановить `keystore.properties` и оригинальный release keystore.
2. Поднять `versionCode` и `versionName` на следующую beta.
3. Обновить README/PROJECT_STAGE с новым номером.
4. Собрать `assembleRelease`.
5. Проверить APK через `apksigner`.
6. Проверить фактический `versionCode` и `versionName` внутри APK.
7. Запушить `main`.
8. Создать GitHub prerelease и загрузить подписанный APK.

Не делать: не публиковать debug APK и не генерировать новый release key без
явного решения владельца, потому что это сломает обновление существующим
пользователям.

## Фаза R2: Передача И Контроль Нового Разработчика

Цель: новый разработчик может поднять проект без истории чата.

Шаги:

1. Проверить clean clone по `HANDOFF.md`.
2. Проверить восстановление `llama.cpp` по `llama.cpp.version`.
3. Запустить `assembleDebug`.
4. Установить debug APK на устройство.
5. Пройти smoke-test: Text, Dialogue, Camera capture, Gallery, History, Models,
   Settings language switch.
6. Убедиться, что branch protection и CI работают для новых PR.

## Фаза C1: Camera Document Polish [COMPLETED]

Цель: переведенная страница должна выглядеть как читаемый документ, а не как
набор случайных overlay-строк.

Шаги:

1. [x] Улучшить группировку OCR строк в блоки страницы (`BoundingBoxClustering.kt`).
2. [x] Для книг и документов выравнивать размеры строк, отступы и межстрочные
   расстояния (статическая бинарная подгонка `StaticLayout`).
3. [x] Мягко затемнять или блюрить оригинальный текст под переводом (`ColorSamplingAndLuminance.kt`, градиентные плашки с alpha-блендингом).
4. [x] Сохранять читаемый масштаб перевода на всей странице (`CameraTextInpainter.kt`).
5. [x] Добавить ручной fallback: показать оригинал и перевод блоком, если overlay
   не помещается (`BilingualInspectBottomSheet.kt`, боковой просмотрщик с TTS и копированием).
6. [x] Проверить на русской/английской книге, меню и скриншоте (unit-тесты `BoundingBoxClusteringTest.kt`).

## Фаза C2: Camera Travel Mode [COMPLETED]

Статус: выполнена (2026-08-31).

Цель: быстрый перевод вывесок, меню, билетов, экранов и короткого публичного
текста, интерактивные карточки путешественника (копирование, TTS-озвучивание, офлайн-словарь по тапу на слово, конвертер валют и детекция пищевых аллергенов).

Шаги:

1. [x] Создать `CameraEnvironmentQualityAnalyzer.kt` (анализ Luma SAD по Y-plane для детекции тряски камеры, низкого освещения и смаза).
2. [x] Создать `AllergenClassifier.kt` (определение 7 групп аллергенов и диетических меток в блюдах и меню на 33 языках).
3. [x] Создать `CameraTravelModels.kt` (`TravelCardUiState`, `TravelActionType`, `TravelCardAction`).
4. [x] Доработать `BilingualInspectBottomSheet.kt` (интерактивные слова-токены для перехода в `DictionaryPopup`, плашка валютного эквивалента `≈ 1 450 ₽`, аллерген-бейджы).
5. [x] Доработать `CameraViewModel.kt` и `CameraScreen.kt` (интеграция `DictionaryRepository`, `CurrencyAugmentor`, интерактивный просмотрщик).
6. [x] Покрыть модульными тестами (`CameraTravelModeTest.kt`).

## Фаза C3: Live Subtitle Camera Mode [COMPLETED]

Статус: выполнена (2026-08-31).

Цель: переводить динамичный текст на внешних экранах, презентациях, онлайн-лекциях, мониторах и табличках как стабильные, немерцающие плавающие субтитры (Floating Teleprompter HUD).

Шаги:

1. [x] Создать `CameraSubtitleModels.kt` (`SubtitleLine`, `SubtitleStyle`, `LiveSubtitleUiState`, `SubtitleAction`).
2. [x] Создать `SpatioTemporalSubtitleTracker.kt` (3-стадийный трекер: IoU-сопоставление $\ge 0.38$, фильтрация OCR-джиттера через расстояние Левенштейна и EMA-сглаживание координат).
3. [x] Создать `LiveSubtitleBanner.kt` (плавающий телесуфлер субтитров с кнопками паузы, TTS-озвучивания, копирования и переключения размера шрифта).
4. [x] Интегрировать в `CameraViewModel.kt` и `CameraScreen.kt` (кнопка переключения режима субтитров, потоковая дедупликация и обновление UI).
5. [x] Покрыть модульными тестами (`CameraSubtitleTrackerTest.kt`).

## Фаза G1: Snapdragon Multi-Generation Hardware Profiles & Dynamic GPU Tuning [COMPLETED]

Статус: выполнена (2026-08-31).

Цель: обеспечить максимальную производительность GPU на Snapdragon 8 Gen 3 (Adreno 750), Snapdragon 845 (Adreno 630) и всей линейке Adreno 6xx/7xx/8xx в рамках единого универсального APK без регрессии эталонного профиля Snapdragon 8 Elite (Adreno 830 / OnePlus 13).

Реализованные шаги:

1. [x] Создана подсистема классификации поколений `AdrenoHardwareProfile.kt` (`AdrenoGeneration.ADRENO_6XX`, `ADRENO_7XX`, `ADRENO_8XX`, `UNKNOWN_GPU`) с автоматическим расчетом динамического оффлоада слоев `calculateGpuLayers()` с защитным резервом RAM 600 МБ для предотвращения OOM на 4GB/6GB RAM.
2. [x] Создан реестр аппаратных профилей `AdrenoProfileRegistry.kt`, охватывающий Snapdragon 845 (Adreno 630), 855 (640), 865 (650), 888 (660), 8 Gen 1 (730), 7+ Gen 2 (725), 8 Gen 2 (740), 7+ Gen 3 (732), 8s Gen 3 (735), 8 Gen 3 (750), 8s Gen 4 (825) и эталонный замороженный профиль Snapdragon 8 Elite (Adreno 830 / OnePlus 13).
3. [x] Расширен нативный JNI-мост `translive_jni.cpp` и `translive_jni_stub.cpp` с параметризованной загрузкой `nativeLoadModel` (`nGpuLayers`, `nBatch`, `nUbatch`, `nCtx`, `nThreads`), сохранением Flash Attention и mmap-загрузки, а также расширенным OpenCL hardware probe в `nativeRuntimeDiagnostics()`.
4. [x] Интегрирован `TranslationEngine.kt` с автоопределением профиля и клампингом потоков host CPU на GPU (2 для A6x, 4 для A7x/A8x).
5. [x] Создан модульный тест `AdrenoHardwareProfileTest.kt` (100% pass).

## Фаза L1: TranslateGemma LiteRT Beta Benchmark [COMPLETED]

Статус: выполнена (2026-08-31).

Цель: решить по фактам, нужен ли LiteRT как третий beta runtime рядом с GGUF.

Реализованные шаги и результаты бенчмарка:

1. [x] Создана модель метрик и телеметрии `BenchmarkMetrics.kt` (`BenchmarkTarget`, `BenchmarkSample`, `SampleCategory`, `IterationMetrics`, `QualityMetrics`, `BenchmarkSummaryReport`).
2. [x] Создан чистый Kotlin-оценщик качества перевода `MultiLanguageEvaluator.kt` (Sentence BLEU-4 с brevity penalty, Length Ratio, OCR Line-ID Retention, обнаружение повторов/галлюцинаций, валидация утечки тегов `<src>`, `<dst>`, `<ctrl...>`).
3. [x] Создана тестовая батарея `LanguageEvaluationSuite.kt` (22 стандартных образца, охватывающих 33 языка, 5 диалектов, короткие фразы, диалоговые реплики и многострочные структурированные OCR-меню/таблички).
4. [x] Создан профилировщик `TranslationBenchmarkEngine.kt` (измерение Cold/Warm Load, TTFT, токенов/сек, пикового Native Heap / PSS и фиксация статуса аппаратных делегатов CPU/GPU).
5. [x] Обновлена документация и матрица результатов `docs/LITERT_BETA.md` с фиксацией профиля Snapdragon 8 Elite (OnePlus 13).
6. [x] Покрыто модульными тестами `LiteRtBenchmarkTest.kt` (100% pass).

## Фаза M1: Model Catalog Audit [COMPLETED]

Статус: выполнена (2026-08-31).

Цель: держать каталог моделей современным, но не превращать приложение в
непроверенный список файлов.

Реализованные шаги и результаты аудита:

1. [x] Проведен аудит всех семейств моделей по официальным upstream-репозиториям (Tencent HuggingFace, Google AI Edge LiteRT, Google TranslateGemma GGUF).
2. [x] Введены категории производительности `ModelPerformanceTier` (`⚡ Fast / Budget`, `⭐ Balanced`, `💎 Max Quality`, `🚀 GPU-Accelerated`).
3. [x] Добавлено отображение бейджей категорий производительности в карточках моделей (`ModelManagerScreen.kt`).
4. [x] Зафиксированы точные SHA-256 хеши, размеры и требования к RAM для всех вариантов GGUF и LiteRT-LM.
5. [x] Создан документ аудита `docs/DEPENDENCY_MODEL_AUDIT_2026-08-31.md`.
6. [x] Написан набор модульных тестов целостности каталога `ModelCatalogTest.kt` (100% pass).

## Фаза P1: Offline Language Packs [COMPLETED]

Статус: выполнена (2026-08-31).

Цель: пользователь видит не отдельные разрозненные файлы, а целостные офлайн-сценарии с атомарной загрузкой и прозрачным учетом занятого пространства.

Реализованные пакеты и функциональность:

1. [x] Создана модель данных `LanguagePackModels.kt` (`PackComponentType`, `ComponentInstallStatus`, `PackOverallStatus`, `LanguagePack`, `TravelPacksCatalog`).
2. [x] Каталог готовых Travel Packs: 🇷🇺 ↔ 🇬🇧 RU-EN, 🇻🇳 ↔ 🇬🇧 VI-EN, 🇨🇳 ↔ 🇬🇧 ZH-EN, 🇪🇸 ↔ 🇬🇧 ES-EN, 🇯🇵 ↔ 🇬🇧 JA-EN, 🇩🇪 ↔ 🇬🇧 DE-EN, 🇫🇷 ↔ 🇬🇧 FR-EN.
3. [x] Создан координатор `LanguagePackRepository.kt` (мульти-компонентная атомарная верификация и загрузка NMT через `FastTranslateEngine`, словаря через `DictionaryRepository`, STT через `SpeechEngine`, OCR-скриптов и системного TTS).
4. [x] Создан хаб `LanguagePacksHubSection` и карточки `TravelPackCard` в `LanguagePackComponents.kt` с многосегментным прогресс-баром и детальным списком модулей.
5. [x] Интеграция в `ModelManagerViewModel.kt` и `ModelManagerScreen.kt`.
6. [x] Покрыто модульными тестами (`LanguagePackRepositoryTest.kt`).

## Фаза F1: Fast NMT На Всех Поверхностях

Статус: выполнена (2026-08-30).

Цель: пользователь выбирает «Быстрый» и получает мгновенный перевод через ML Kit
NMT без загрузки LLM в RAM. Три режима (Fast, Fast + LLM Improve, LLM Only)
работают на текстовом экране, в диалоге и синхронизированы с камерой.

Шаги:

1. [x] Починить `SettingsScreen.kt`: определить отсутствующий composable
   `TranslationPolicyOption`.
2. [x] Переименовать `CameraTranslateEngine` в `FastTranslateEngine`.
3. [x] Инжектить `FastTranslateEngine` в `TranslationViewModel`, читать
   `TranslationPolicy`, реализовать ветвление Fast/Improve/LLM.
4. [x] Добавить UI Magic Wand (`AutoFixHigh`) и бейдж режима на текстовом экране.
5. [x] Подключить Fast NMT в `DialogueViewModel`.
6. [x] Синхронизировать `CameraViewModel` с глобальной политикой.
7. [x] Собрать debug APK, пройти smoke-test всех трёх режимов.

## Фаза S1: Перевод Экрана — Продуктовое Состояние

Статус: выполнена (2026-08-30).

Цель: перевод появляется поверх текущего приложения за ~700ms без перехода в
Parlex.

Шаги:

1. [x] Плавающая кнопка: drag, DP-размеры, иконка Parlex, сохранение позиции.
2. [x] Long-press меню: перевести экран, живой режим, смена языков, закрыть.
3. [x] Оверлейный результат: скриншот → OCR → Fast NMT → блоки перевода поверх
   замороженного кадра без перехода в Parlex.
4. [x] Кнопки на оверлее: закрыть, открыть в Parlex, копировать всё.
5. [x] Проверить на OnePlus 13: latency, визуал, взаимодействие с другими приложениями.

## Фаза N1: Транслитерация

Статус: выполнена (2026-08-30).

Цель: нелатинский текст (кириллица, CJK, деванагари, арабский, тайский, хангыль)
сопровождается латинской транслитерацией для чтения вслух.

Шаги:

1. [x] Создать `TransliterationEngine.kt` на базе `android.icu.text.Transliterator`.
2. [x] Добавить строку транслитерации под исходным и переведённым текстом.
3. [x] Добавить toggle в настройках.
4. [x] Проверить на русском, китайском, арабском и хинди.

## Фаза S2: Живой Перевод Экрана

Статус: выполнена (2026-08-30).

Цель: непрерывный перевод текста поверх работающего приложения с keyframe OCR.

Шаги:

1. [x] Создать `KeyframeMotionDetector.kt` с быстрым Luma SAD и дебаунсом 300мс.
2. [x] Создать `LiveTranslationPipeline.kt` с spatial diffing, scroll tracking и LRU кэшем.
3. [x] Создать `LiveScreenTranslateService.kt` в continuous mode с `ImageReader` и `DisplayListener`.
4. [x] Создать `LiveOverlayRenderer.kt` с полноэкранным AR Canvas (`FLAG_NOT_TOUCHABLE`) и HUD контроллером.
5. [x] Добавить пункт «⚡ Живой перевод экрана» в контекстное меню плавающей кнопки.
6. [x] Покрыть модульными тестами (`KeyframeMotionDetectorTest`, `LiveTranslationPipelineTest`).

## Фаза N2: Встроенный Офлайн-Словарь

Статус: выполнена (2026-08-31).

Цель: быстрый просмотр словарной статьи, части речи, транскрипции и примеров из локальной базы Room без сетевого запроса.

Шаги:

1. [x] Создать `DictionaryEntry.kt` и `DictionaryDao.kt` в Room с индексами поиска.
2. [x] Создать `CoreDictionarySeeder.kt` с офлайн-базой RU↔EN и `DictionaryRepository.kt` с кэшем и поддержкой импорта TSV.
3. [x] Создать Composable `DictionaryPopup.kt` и `DictionaryViewModel.kt`.
4. [x] Интегрировать карточку словаря в `TranslationScreen.kt` и раздел в `ModelManagerScreen.kt`.
5. [x] Проверить модульными тестами (`DictionaryRepositoryTest.kt`).

## Фаза N3: Перевод Документов (PDF)

Статус: выполнена (2026-08-31).

Цель: импорт PDF, постраничный OCR, перевод блоков, side-by-side просмотр
оригинала и перевода.

Шаги:

1. [x] Создать `PdfModels.kt` и `PdfRendererManager.kt` с 200 DPI рендерингом и LRU кэшем.
2. [x] Создать `PdfDocumentProcessor.kt` (семантическая группировка параграфов, удаление дефисов) и `PdfExportManager.kt`.
3. [x] Создать `DocumentTranslateViewModel.kt` с постраничной конвейерной обработкой.
4. [x] Создать `DocumentTranslateScreen.kt` с поддержкой Zoom/Pan, переключением режимов (Оверлей/Сплит/Оригинал) и экспортом.
5. [x] Проверить модульными тестами (`PdfDocumentProcessorTest.kt`).

## Фаза S3: Мультиязычный Автоперевод Экрана и Живой Конвертер Валют

Статус: выполнена (2026-08-31).

Цель: автоопределение языка для каждого текстового блока на экране с переводом на язык пользователя + автоматическое определение цен/валют в тексте с выводом эквивалента в домашней валюте пользователя без API-ключей.

Шаги:

1. [x] Создать `ExchangeRateEntity`, `ExchangeRateDao`, `ExchangeRateBaseline` (35+ валют) и `ExchangeRateRepository` с каскадом FloatRates / OpenER / Frankfurter.
2. [x] Создать `CurrencyParser.kt` and `CurrencyAugmentor.kt` для распознавания и вычисления эквивалентов в скобках `(≈ 4 580 ₽)`.
3. [x] Добавить выбор домашней валюты и тумблер конвертации в `SettingsRepository` и `SettingsScreen`.
4. [x] Интегрировать автоопределение языков и конвертер валют в `LiveTranslationPipeline.kt`, `LiveOverlayRenderer.kt` и `TranslationViewModel.kt`.
5. [x] Покрыть модульными тестами (`CurrencyParserTest.kt`, `CurrencyAugmentorTest.kt`).

## Фаза S4: Непрерывный Автоперевод Экрана и Плавающий HUD-Контроллер

Статус: выполнена (2026-08-31).

Цель: переключение между одиночными снимками (`SINGLE_SHOT`) и непрерывным AR-автопереводом (`AUTO_LIVE`), плавающий компактный HUD с пульсирующим статус-кольцом, мини-пикером языков прямо на экране (`Auto` / выбор языка / `⇄`), интерактивным режимом касания (копирование + TTS) и кнопкой одиночного снимка.

Шаги:

1. [x] Создать `ScreenTranslateModels.kt` (`ScreenTranslateMode`, `HudStatus`, `HudUiState`, `HudAction`).
2. [x] Доработать `LiveOverlayRenderer.kt`:
   - Анимированное пульсирующее кольцо (`ValueAnimator`) с цветовой индикацией статуса (Зеленый = Live/Мониторинг, Янтарный = Стабилизация, Голубой = Перевод, Оранжевый = Пауза).
   - Переключатель режимов `⚡ Auto` vs `📸 Single`.
   - Плавающий мини-диалог выбора языков со свопом `⇄` и режимом `Auto-Detect`.
   - Интерактивный режим с поддержкой TTS-озвучивания и копирования в буфер обмена.
3. [x] Доработать `LiveScreenTranslateService.kt`:
   - Состояние `HudUiState` с реакцией на действия пользователя (`HudAction`).
   - Уменьшение окна детектора стабилизации до 180 мс для быстрого отклика.
   - Поддержка одиночного снимка по требованию (`TriggerSingleShot`).
4. [x] Покрыть модульными тестами (`ScreenTranslateModelsTest.kt`).

## Фаза D1: Continuous Bi-Directional Hands-Free Voice Dialogue [COMPLETED]

Статус: выполнена (2026-08-31).

Цель: непрерывный голосовой перевод диалога без удержания кнопок с VAD и защитой от акустического эха (AEC Guard), автоматическим арбитром языков реплик (Language Arbiter) и двухуровневым переводом (мгновенный Fast NMT ~20мс + опциональный LLM Refine по требованию) с Material Design 3 zero-emoji стандартами.

Шаги:

1. [x] D1.1: Детерминированный `DialogueLanguageArbiter.kt` (анализ Unicode-скриптов, битовая маска диакритик, лексикон и марковское априорное чередование).
2. [x] D1.2: Непрерывный фоновый цикл захвата аудио с Silero VAD и трехуровневым AEC Guard (`SpeechEngine.kt` - подавление фреймов микрофона при TTS + 300мс reverb cooldown).
3. [x] D1.3: Двухуровневый перевод: Fast NMT (~20мс) + кнопка глубокого улучшения контекста LLM (`improveMessageWithLlm`) в пузыре сообщения без сдвига скролла. Полный редизайн на Material 3 Zero-Emoji (плашки `[EN]`, `[RU]`).
4. [x] Покрыть модульными тестами (`DialogueLanguageArbiterTest.kt`, `ContinuousDialogueVadTest.kt`, `DialogueLlmRefinementTest.kt`).

## Фаза P1: Fast Translation Package Download Management & Bulk Download [COMPLETED]

Статус: выполнена (2026-08-31).

Цель: прозрачное управление пакетами быстрого офлайн-перевода (Google ML Kit NMT) по аналогии со скачиванием LLM моделей: отображение точных размеров пакетов (~30 МБ на язык), объема занятого и свободного дискового пространства, прозрачность источника (`Google ML Kit CDN • Офлайн-модели NMT`), кнопка пакетного скачивания всех языков («Скачать все пакеты») с отображением прогресса и удаление установленных пакетов.

Шаги:

1. [x] Расширить `FastTranslateEngine.kt`: константа `PACKAGE_SIZE_BYTES = 30_000_000L`, происхождение `DOWNLOAD_SOURCE_NAME`, конвейер `downloadAllPackages` с колбэками прогресса и автозакрытие транслятора при удалении.
2. [x] Расширить `ModelManagerViewModel.kt`: поля размера, источника и состояния удаления в `CameraLanguagePackUiState`, методы `downloadAllFastLanguagePackages()` и `deleteCameraLanguagePack()`.
3. [x] Редизайн карточки `CameraLanguagePacksGroup` в `ModelManagerScreen.kt`: сводка хранилища (`X / 30 пакетов`, `размер на диске`), бейдж источника Google CDN, линейный индикатор загрузки, кнопка массового скачивания, плашки ISO кодов языков и корзина для удаления пакетов.
4. [x] Полное соответствие Material 3 Zero-Emoji (включая очистку карточки словаря).
5. [x] Покрыть модульными тестами (`FastTranslatePackageManagementTest.kt` - 12/12 тестов pass).


## Фаза H: Dialogue Voice Recording, Timeline History & Local LLM Session Summary [COMPLETED]

Статус: выполнена (2026-08-31).

Цель: непрерывная фоновая запись аудио диалогов в настраиваемом формате (AAC .m4a 48 кбит/с или несжатый WAV .wav) без конфликтов с микрофоном SpeechEngine/Silero VAD, сохранение метаданных и временных меток реплик в Room DB v5, хронологический таймлайн с аудиоплеером (скраббинг, скорость 1.0x–2.0x, синхронизированный переход к репликам), карточка мультиязычной статистики сессии (слова, символы, CJK, темп чередования спикеров) и локальная генерация структурированного резюме диалога нейросетью LiteRT (Gemma 2 2B).

Шаги:

1. [x] H1: Поддержка форматов записи (`AudioRecordingFormat.kt` - AAC/WAV) и потоковый кодировщик `DialogueAudioRecorder.kt`, перехватывающий 16 кГц PCM из `SpeechEngine.onPcmSamplesRead` без монопольного захвата `AudioRecord`.
2. [x] H2: Миграция Room DB до версии 5 (`TransLiveDatabase.kt`, `Entities.kt`) с расширенными метаданными `DialogueSession` и `DialogueMessage` (аудио, статистика, резюме).
3. [x] H3: Настройки записи звука в `SettingsScreen.kt` (тумблер включения и плашки форматов AAC/WAV).
4. [x] H4: Синхронизированный плеер `DialogueAudioPlayer.kt` и локальный генератор резюме `DialogueSummaryEngine.kt` на базе LiteRT Gemma 2 2B.
5. [x] H5: Компоненты UI (`DialogueSessionStatsCard.kt`, `DialogueAudioPlayerBar.kt`, `DialogueLlmSummaryCard.kt`) и обновленный `HistoryScreen.kt` с хронологическим таймлайном.
## Фаза O: Floating Screen Translation UX Overhaul & AR Back Interception [COMPLETED]

Статус: выполнена (2026-08-31).

Цель: полная модернизация плавающей кнопки перевода экрана и AR-оверлея: магнитная корзина удаления (Drag-to-Dismiss) с физикой притяжения и тактильным откликом, перехват аппаратного и жестового «Назад» (Predictive Back Gesture) без блокировки касаний хост-приложения, Liquid Glass редизайн с верхним бликом и векторной пиктограммой `A ⇄ 文` вместо текстовой «Т», адаптивное радиально-линейное меню Material 3 Expressive FAB Menu, полированный AR-оверлей с микро-панелью действий и композитный экспорт скриншотов.

Шаги:

1. [x] O1: Создать `DismissTrashView.kt` и `DragToDismissCalculator.kt` (магнитное притяжение, расчет границ экрана, тактильный тик и удаление сервиса).
2. [x] O2: Реализовать `ArOverlayBackController.kt` и `ArOverlayWindowFlags.kt`, динамическое переключение `FLAG_NOT_TOUCH_MODAL` / `FLAG_WATCH_OUTSIDE_TOUCH` для перехвата жеста «Назад» без `FLAG_NOT_FOCUSABLE`.
3. [x] O3: Редизайн `FloatingBubbleView.kt` (Liquid Glass дуга отражения, векторные стрелки перевода, состояние IDLE/SCAN/TRANSLATING).
4. [x] O4: Интеграция `FabMenuLayoutCalculator.kt` и Material 3 Expressive FAB Menu.
5. [x] O5 & O6: Полировка AR-оверлея и композитный экспорт (`ScreenTranslationExporter.kt`).
6. [x] Модульные тесты pure JVM (`DragToDismissTest.kt`, `BackGestureInterceptionTest.kt`, `BubbleAndFabMenuTest.kt` - 100% pass) и верификация на устройстве.

## Фаза V: Multi-Engine STT Architecture & Streaming Transducers [COMPLETED]

Статус: выполнена (2026-08-31).

Цель: переход от моно-модели к мультидвижковой архитектуре распознавания речи: каталог моделей 2026 года (Zipformer streaming per-language, SenseVoice Small INT8 для ZH/EN/JA/KO, Whisper Tiny, Qwen3-ASR 0.6B), динамический селектор движка по активному языку и двухуровневый конвейер распознавания (потоковый 1-й проход + финальное уточнение 2-м проходом).

Шаги:

1. [x] V1-V3: Создать `SttEngineComponents.kt` (`SttEngineType`, `SttEngineDescriptor`, `SttEngineRegistry`, `SttEngineSelector`).
2. [x] V4: Валидация языкового покрытия (SenseVoice ZH/EN/JA/KO/YUE без RU, Zipformer RU/EN/ZH/VI).
3. [x] V5: `TwoPassSttPipeline.kt` с накоплением потоковых токенов и финальным уточнением.
4. [x] Модульные тесты pure JVM (`MultiEngineSttTest.kt` - 100% pass).

## Фаза AI: On-Device Vision LLM & Screen Analysis Pipeline [COMPLETED]

Статус: выполнена (2026-08-31).

Цель: локальный анализ и объяснение происходящего на экране с помощью компактных Vision-Language Models (MiniCPM-V 4.6, SmolVLM-2, Gemma 4 Edge E2B), алгоритм масштабирования изображений под лимиты памяти мобильных чипсетов и генерация структурированных промптов.

Шаги:

1. [x] AI1 & AI4: Создать `VisionLlmComponents.kt` (`VisionLlmModelType`, `VisionLlmModelDescriptor`, `VisionLlmCatalog`).
2. [x] AI2: Алгоритм `ImageDimensionScaler` для безопасного масштабирования скриншотов с сохранением соотношения сторон.
3. [x] AI3: `VisionPromptBuilder` для сценариев перевода, детального описания и суммаризации экрана.
4. [x] AI5: `StreamingTextAccumulator` для потокового вывода ответа с поддержкой отмены.
## Фаза M: Model Manager 5-Step Overhaul & Fast Translation Offline Sync [COMPLETED]

Статус: выполнена (2026-09-01).

Цель: 5-шаговая иерархическая структура раздела загрузок с единым стандартом прогресса скачивания (EMA сглаживание скорости, байты/сек, ETA), автономным экспортом/импортом моделей быстрого перевода (.parlex-fast), расширением каталога Fast NMT до 59 языков и строгим соответствием Material Design 3 Zero-Emoji.

Шаги:

1. [x] M1: `FastModelSyncComponents.kt` (`ParlexFastManifest`, `FastModelSyncPacker`, `FastModelArchiveValidator` с автономным JSON-парсером).
2. [x] M2: Расширение `toMlKitLang` в `FastTranslateEngine.kt` до всех 59 языковых констант Google ML Kit Translation.
3. [x] M3: `DownloadMetricsComponents.kt` (`DownloadMetricsTracker` с EMA альфа=0.25, `UniversalDownloadState`, `DownloadFormatter`).
4. [x] M4: `UniversalDownloadComponents.kt` (`CircularDownloadButton`, `DownloadProgressRow`, `UniversalDownloadCard`).
5. [x] M5: Реорганизация `ModelManagerScreen.kt` в 5 последовательных нумерованных разделов:
   - Шаг 1: `1. Основной ИИ-переводчик (LLM)` (Hy-MT2 1.8B/7B, Gemma 4 LiteRT, TranslateGemma 4B, HY-MT 1.5, управление RAM).
   - Шаг 2: `2. Быстрый перевод (Fast NMT)` (полный каталог 59 языков, поиск/фильтрация, «Скачать все», экспорт/импорт `.parlex-fast`).
   - Шаг 3: `3. Голосовой ввод (STT)` (Silero VAD + Whisper Tiny, Qwen3-ASR 0.6B INT8).
   - Шаг 4: `4. Зрительные модели (Vision & OCR)` (PP-OCRv6 tiny MNN).
   - Шаг 5: `5. Офлайн-словари и данные` (Travel Packs Hub, встроенный словарь RU ↔ EN).
## Фаза W: Zero-Latency Screen Translation, Volume Key Decoupling & Offline Pack Hardening [COMPLETED]

Статус: выполнена (2026-09-01).

Цель:
1. Моментальное извлечение текста с экрана через `AccessibilityNodeInfo` (BFS обход, фильтрация геометрии, дедупликация контейнеров) без задержки и без вызова диалогов записи экрана, с 1-проходным пакетным переводом `FastBatchTranslator` (sentinel разделитель `\n\n<<<§>>>\n\n`) и авто-подгонкой плашек `ArBoundingBoxClusterer`.
2. Архитектурное разделение кнопок громкости / шортката доступности и плавающей кнопки (`ScreenA11yShortcutBehavior`: `SINGLE_SHOT_NO_BUBBLE` — мгновенный оверлейный перевод без оставления плавающей кнопки vs `TOGGLE_FLOATING_BUBBLE` — переключение плавающей кнопки).
3. Независимые настройки экранного перевода (синхронизация целевого языка с основным или независимый выбор целевого языка).
4. Исправление загрузчика офлайн-словарей и Travel Packs (автоматическое скачивание Whisper/VAD STT компонентов вместе с пакетами и отображение спиннера загрузки для индивидуальных пакетов в Шаге 2).

Шаги:
1. [x] W1: `FastBatchTranslator.kt` с разделителем `\n\n<<<§>>>\n\n` и unit-тестами `FastBatchTranslatorTest.kt`.
2. [x] W1: `ArBoundingBoxClusterer.kt` с обновленными порогами (`maxVerticalGapFactor = 1.25f`, `minHorizontalOverlapRatio = 0.45f`), JVM test-совместимой геометрией и unit-тестами `ArBoundingBoxClustererTest.kt`.
3. [x] W1: Внедрение `extractVisibleTextNodesFast(maxNodes = 600)` в `ScreenAccessibilityService.kt` и гибридного 0ms AR-конвейера в `ScreenTranslateOverlayService.kt`.
4. [x] W2: Настройки поведения шортката `ScreenA11yShortcutBehavior` и языковые маршруты в `SettingsRepository.kt` с unit-тестами `ScreenSettingsTest.kt`.
5. [x] W2: UI карточка экранного перевода и действий кнопок громкости в `SettingsScreen.kt`.
6. [x] W3: Исправление индикатора загрузки `isThisDownloading` в `ModelManagerScreen.kt` и автодокачивание STT в `ModelManagerViewModel.kt` / `LanguagePackRepository.kt`.
7. [x] W-VERIFY: Полная компиляция `:app:compileDebugKotlin`, сборка `:app:assembleDebug`, 100% прохождение unit-тестов.

## Governance

- Один PR должен решать одну тему.
- i18n, CI, Gradle wrapper, release flow и модельный каталог не смешиваются в
  одном PR.
- Debug CI проверяет качество, но не выпускает APK.
- Подписанные beta/release APK остаются ручным контролируемым процессом.




