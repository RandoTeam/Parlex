# Камера, языковые пакеты и перевод экрана

## Факты, подтверждённые в текущем коде и официальной документации

- Быстрый путь камеры — локальный ML Kit Translate. Модель скачивается **на язык**, а не на пару: два подготовленных языковых пакета работают в обоих направлениях.
- Из 38 вариантов `Language` быстрый NMT поддерживает 33: 31 основной язык и кантонский/хоккиен через общий китайский пакет. Это 30 уникальных загрузок, потому что упрощённый/традиционный китайский, кантонский и хоккиен используют одну модель `zh`.
- Быстрый ML Kit путь не поддерживает `my`, `km`, `bo`, `mn`, `ug`. Для них камера должна предлагать режим фото/документа с установленной HY-MT/TranslateGemma, а не обещать live-перевод.
- OCR уже маршрутизирует все 38 вариантов между ML Kit и Tesseract, но Tesseract является причиной медленного и слабого распознавания на сложных изображениях.
- `DavidVentura/offline-translator` использует Firefox/Bergamot NMT, PaddleOCR и MNN. Его исходный код GPL-3.0: код не переносится в Parlex; воспроизводятся только независимые технические решения.

## Целевое поведение пакетов

1. В разделе «Модели» есть одна свёрнутая группа «Языковые пакеты быстрой камеры».
2. Внутри — 30 пакетов, каждый с понятным перечнем языков-алиасов и статусом. Не создаются сотни карточек пар.
3. Пользователь скачивает один язык или текущую пару. После загрузки, например, `ru` и `en`, доступны `ru → en` и `en → ru`.
4. При смене пары в камере отображается точная причина и действие:
   - пакеты есть — перевод сразу активен;
   - не хватает языков — кнопка «Скачать пакеты текущей пары» прямо в камере;
   - быстрая модель не существует — честная подсказка «Используйте фото / качество».
5. Автоисточник допускает только установленные быстрые языки и никогда не инициирует скачивание по кадру.

## Реализация

- [x] Представить ML Kit-модель как переиспользуемый пакет языка, включая общий пакет китайских вариантов.
- [x] Добавить свёрнутую группу языковых пакетов и индивидуальную загрузку в «Модели».
- [x] Добавить выбор и загрузку любой поддерживаемой пары внутри этой свёрнутой группы; скачиваются только два переиспользуемых языковых пакета.
- [x] Добавить загрузку текущей пары непосредственно из камеры.
- [x] Исправить CJK-маршрутизацию: отдельные офлайн-recognizer'ы ML Kit для японского и корейского вместо китайского.
- [ ] Список отсутствующих пакетов и точные имена языков в карточке текущей пары.
- [ ] Автоисточник: ограничение кандидатов установленными пакетами уже добавлено для фото/скриншота. Осталось заменить эвристический выбор письменности на PP-OCR detector/classifier и распространить его на live-режим.
- [x] Явные режимы камеры: `Быстро` (ML Kit) и `Качество` (фото/документ + HY-MT/TranslateGemma).
- [ ] Заменить тяжёлый OCR фото/документов на проверенный PP-OCR mobile pipeline: detector + recognizer по письменности + ориентация + reading order. В live-режиме оставить keyframe OCR, трекинг блоков и кэш перевода. Технический выбор: PP-OCRv6 models + MNN с проверяемыми CPU/OpenCL/Vulkan бэкендами; официальный ONNX Android demo использовать как эталон формата моделей и метрик, но не как GPU runtime.
- [ ] MNN 3.6.1 уже проверен отдельной arm64-сборкой с NDK 27.3: OpenCL и Vulkan включены, KleidiAI отключён из-за риска SIGILL на новых ARM-устройствах. Полная библиотека получилась около 52 МБ; перед интеграцией нужен mini/OCR-only профиль и измерение на OnePlus 13.
- [ ] Корпус OnePlus 13: русский, английский, китайский, арабский, тайский, постеры, страницы книг и смешанные надписи; сохранить метрики OCR, перевода и end-to-end latency.

### OCR runtime decision (2026-08-17)

- The official PaddleOCR Android SDK was audited as a correctness reference:
  detection, quadrilateral sorting/unclip, perspective crops, batched
  recognition, CTC decoding, and timing breakdowns are all present.
- Its shipped executor is ONNX Runtime. It is suitable as a CPU correctness
  fallback and benchmark reference, but it is not the primary Snapdragon GPU
  path for Parlex.
- PP-OCRv6 tiny detector/recognizer ONNX graphs were converted to MNN and open
  successfully. A standalone MNN 3.6.1 arm64 build was produced with OpenCL
  and Vulkan enabled; the mini build is about 27 MB and the full build about
  52 MB. These are research artifacts until Android inference is proven.
- Production direction: reproduce the audited behavior independently, run
  PP-OCR through MNN on supported GPU backends, retain a controlled CPU
  fallback, and expose the actual backend in diagnostics. Do not copy the
  GPL-3.0 `offline-translator` source.
- Before shipping OCR packages, require a real Android detector and
  recognizer inference test, checksum/model metadata validation, cold/warm
  timings, and graceful fallback when GPU initialization fails.

### OCR package catalog and pair behavior

- The camera language group remains the single user-facing entry point. It
  contains the reusable fast translation packages, while OCR runtime assets
  are shown as a separate compact subsection rather than one row per language.
- The minimum OCR package set is:
  - `ppocrv6_tiny_det`: text detector and geometry;
  - `ppocrv6_tiny_rec_latin`: Latin/Cyrillic-compatible recognition path;
  - `ppocrv6_tiny_rec_cjk`: Chinese/Japanese/Korean recognition path;
  - script dictionaries/configuration and version metadata.
- OCR packages are not language-pair packages. One detector/recognizer asset
  can serve many translation pairs; the selected source and target only choose
  OCR routing and the fast translation packages.
- For `ru ↔ en` and any other ML Kit pair, the camera offers exactly the two
  missing reusable language packages. Selecting another pair (for example
  `zh → fr`) recomputes the missing set immediately and offers one action.
- Languages without a fast ML Kit package remain selectable and use the local
  LLM fallback. They must never produce a fake download entry or be counted as
  an installed fast package.
- The existing `ModelRuntime` enum currently represents only GGUF and LiteRT.
  OCR assets need a dedicated runtime/package type before they can be wired to
  the common downloader; this is an implementation prerequisite, not a UI-only
  catalog addition.

## Перевод экрана

1. [x] Первый релиз: `ACTION_PROCESS_TEXT` и `ACTION_SEND` — перевод выделенного или переданного текста из других приложений без захвата экрана.
   Переданное изображение или скриншот также сразу открывается в обработке камеры.
2. [x] Кнопка «Перевести экран» запускает отдельную foreground-службу с `MediaProjection`; Android показывает системное согласие на **каждую** сессию. Базовый маршрут делает один снимок экрана и передаёт его в существующий photo/OCR pipeline.
3. Пользователь выбирает экран или отдельное приложение; сервис делает keyframe OCR только при стабильном кадре, переводит кэшированные блоки быстрым NMT и рисует неинтерактивный overlay.
4. Длинное нажатие на плавающей кнопке: пауза, выбор области, выбор языков, «Открыть в качестве».
5. В Android 14+ обязательны `FOREGROUND_SERVICE_MEDIA_PROJECTION`, тип службы `mediaProjection`, notification и корректная остановка по `MediaProjection.Callback`.

## Полный функциональный паритет: что переносим и в каком порядке

| Сценарий | Статус Parlex | Реализация |
|---|---|---|
| Обычный текст | Есть | LLM: HY-MT / TranslateGemma / Gemma; явная пара языков и ручной запуск |
| Быстрый текст камеры | Есть, расширяется | ML Kit NMT с 30 скачиваемыми языковыми пакетами |
| Фото, постер, книга | Есть, OCR нужно заменить | PP-OCRv5 mobile для текста и геометрии; основная LLM только в режиме «Качество» |
| Скриншот из приложения | Есть | Android Share image → Camera photo pipeline |
| Выделенный текст в другом приложении | Есть | `ACTION_PROCESS_TEXT` / `ACTION_SEND` → основной переводчик |
| Постоянная кнопка и live overlay | План | `MediaProjection`, foreground service, явное согласие на каждую сессию, keyframe OCR, overlay без перехвата касаний |
| PDF / документы | План | Импорт PDF, постраничный OCR, чтение по блокам, экспорт текста; визуальная перерисовка PDF — только после измерения качества |
| URL / статья | План | Приём ссылки из Share, локальная загрузка по явному действию, reader-mode извлечение, затем тот же перевод текста |
| Словарь и перевод слова | План | Долгое нажатие по слову, локальные словарные пакеты по языку, без сетевого запроса |
| Озвучивание | Частично есть | Системный TTS сейчас; нейронные голосовые пакеты только после стабилизации STT и камеры |
| Голосовой диалог | Есть, развивается отдельно | ASR с фиксированной стороной A/B, VAD и запрет перевода речи третьего языка |

### Правила, общие для всех новых функций

1. Быстрый маршрут никогда не ждёт LLM: OCR → локальный NMT → кэш по тексту и языковой паре.
2. Качественный маршрут пользователь выбирает сам: крупное фото, страница книги или длинный текст → OCR блоками → LLM.
3. Каждый внешний пакет отображается в «Моделях», имеет размер, источник, контрольную сумму, статус и явную загрузку.
4. Ни камера, ни overlay не скачивают модели во время захвата кадра.
5. Никакие текст, изображение, голос или экран не отправляются в сеть после того, как пакеты загружены.

## Источники

- [PaddleOCR: официальный Android SDK](https://github.com/PaddlePaddle/PaddleOCR/blob/main/docs/version3.x/inference_deployment/cross_platform/android_deployment.en.md) — PP-OCRv6, разделение SDK/demo, API и измерение времени detector/recognizer.
- [MNN](https://github.com/alibaba/MNN) — Android GPU inference через OpenCL/Vulkan; используется как runtime OCR в исследованном приложении.
- [offline-translator](https://github.com/DavidVentura/offline-translator) — источник функциональных идей. Лицензия GPL-3.0, поэтому код не переносится.

- https://developers.google.com/ml-kit/language/translation/android
- https://developers.google.com/ml-kit/language/translation/translation-language-support
- https://developer.android.com/media/grow/media-projection
- https://github.com/DavidVentura/offline-translator

### PP-OCRv6 tiny tensor validation

- Host MNN inference was run against the converted artifacts before Android
  activation: detector `(1,3,640,640)` → `(1,1,640,640)` and recognizer
  `(1,3,48,320)` → `(1,40,6906)`.
- The recognizer decoder uses `time=40` and `classes=6906`; the dictionary
  and exact normalization metadata remain part of the package manifest.
- Both models complete a zero-filled Float32 MNN CPU session. Android GPU
  validation is still required on the target device.

The PP-OCRv6 tiny recognizer dictionary contract is fixed at 6,904 UTF-8
characters, 27,156 bytes, SHA-256
`c5cbe34ef40c29c4df07ed012bf96569cb69a2d2a01a07027e9f13cb832bd9cd`, one
character per line with a final newline. The runtime rejects any mismatch.
