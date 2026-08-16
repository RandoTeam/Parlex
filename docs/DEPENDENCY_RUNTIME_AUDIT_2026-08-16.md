# Аудит зависимостей и рантаймов — 2026-08-16

Проверка выполнена по Gradle-конфигурации проекта, Maven metadata, официальным
релизам Android/Google и репозиториям владельцев нативных рантаймов.

## Обновить в этой работе

| Компонент | Сейчас | Целевая версия | Почему |
|---|---:|---:|---|
| Sherpa-ONNX Android AAR | 1.13.1 | 1.13.4 | В 1.13.1 нет Kotlin API `OfflineQwen3AsrModelConfig`; 1.13.4 содержит актуальный ONNX Runtime и Qwen3-ASR API. |
| Qwen3-ASR | отсутствует | 0.6B INT8, пакет 2026-03-25 | Качественное офлайн-распознавание речи для 30 языков / 52 языков и диалектов. Пакет 878 702 423 B, SHA-256 закреплён в GitHub Release. |
| Compose BOM | 2026.05.01 | 2026.06.00 | Исправления Compose; это последняя стабильная BOM, указанная в официальной документации Compose. |
| AndroidX Core KTX | 1.18.0 | 1.19.0 | Текущий стабильный AndroidX Core. |
| AndroidX Hilt Navigation Compose | 1.3.0 | 1.4.0 | Текущий стабильный адаптер навигации Hilt. |
| OkHttp | 5.3.2 | 5.4.0 | Актуальные исправления HTTP-загрузчика моделей. |
| llama.cpp | b10442 | b10452 | Свежий upstream на момент проверки; необходима пересборка OpenCL Android-пути и smoke-test GPU. |
| AGP / Gradle | 8.9.1 / 8.11.1 | 9.2.0 / 9.4.1 | Современный сборочный стек Android, поддержка API 37, новые D8/R8 и требование для актуального Hilt/KSP. |
| Kotlin Android plugin | 2.2.21 | убрать при AGP 9 | AGP 9 использует встроенный Kotlin; старый plugin несовместим с новым DSL. |
| KSP | 2.2.21-2.0.5 | 2.3.10, KSP2 | KSP1 не поддерживает AGP 9/Kotlin 2.3; Room и Hilt уже используют KSP. |
| Hilt | 2.57.2 | 2.60.1 | Современный KSP/AGP 9-совместимый процессор DI. |
| targetSdk | 35 | 36 | Соответствие Android 16 на целевом OnePlus 13; требует отдельного ручного smoke-test интерфейса. |

## Уже актуально — не обновлять без причины

| Компонент | Версия | Вывод |
|---|---:|---|
| Room | 2.8.4 | Текущий стабильный выпуск. |
| Activity Compose | 1.13.0 | Текущий стабильный выпуск. |
| Lifecycle runtime / ViewModel Compose | 2.10.0 | Текущая стабильная ветка; 2.12.x пока alpha. |
| Navigation Compose | 2.9.8 | Текущая стабильная ветка; 2.10.x пока RC. |
| Coroutines Android | 1.11.0 | Текущий стабильный выпуск. |
| Lottie Compose | 6.7.1 | Текущий стабильный выпуск. |
| CameraX | 1.6.1 | Текущий стабильный выпуск. 1.7.x пока alpha и не должен попасть в пользовательскую сборку. |
| ML Kit Text Recognition / Chinese / Devanagari | 16.0.1 | Текущий выпуск. |
| ML Kit Translate | 17.0.3 | Текущий выпуск. |
| ML Kit Language ID | 17.0.6 | Текущий выпуск. |
| LiteRT-LM Android | 0.16.0 | Текущий выпуск; обновление не даст Qwen ASR, так как это другой ONNX-рантайм. |
| Tesseract4Android | 4.9.0 | Текущий выпуск на Tesseract 5.5.1. |

## Не является библиотекой приложения

| Компонент | Статус | Действие |
|---|---|---|
| Adreno GPU-драйвер | Поставляется ColorOS/OTA | APK не должен заменять драйвер. Проверяем только доступность OpenCL/Vulkan и фактический backend. |
| NDK | 27.3.13750724 установлен локально | AGP 9.2 рекомендует NDK 28.2.13676358, но он не установлен в SDK. Не фиксировать отсутствующую версию в Gradle до установки через Android Studio/SDK Manager. |
| CMake | 3.22.1 | Совместим с текущим JNI/CMake-проектом; обновление не ускоряет inference само по себе. |

## Решение по Qwen3-ASR

- Добавляется только `Qwen3-ASR 0.6B INT8`, не 1.7B: он предназначен для телефона и занимает около 0.94 GB после скачивания архива.
- Это отдельный режим **«Качество»** в голосовом переводе. Whisper Tiny остаётся режимом **«Быстро»**.
- Оба режима используют общий Silero VAD. Языковая сторона выбирается явной кнопкой A/B; автоматическое распознавание третьего языка не меняет направление перевода.
- Qwen-пакет загружается через существующий resumable downloader, с SHA-256 до распаковки. Он не встроен в APK и не загружается при входе в диалог.
- Qwen3-ASR в Sherpa-ONNX использует ONNX CPU provider. GPU/NPU не будет показан как активный, пока не появится проверенный Adreno backend для этого точного ONNX пакета.

## Источники

- AGP 9.2 / Gradle / NDK compatibility: https://developer.android.com/build/releases/agp-9-2-0-release-notes
- Built-in Kotlin migration: https://developer.android.com/build/migrate-to-built-in-kotlin
- KSP2 compatibility: https://github.com/google/ksp
- CameraX stable channel: https://developer.android.com/jetpack/androidx/releases/camera
- ML Kit Translation model management: https://developers.google.com/ml-kit/language/translation/android
- LiteRT-LM Maven metadata: https://dl.google.com/dl/android/maven2/com/google/ai/edge/litertlm/litertlm-android/maven-metadata.xml
- Sherpa-ONNX 1.13.4 source declaration: https://github.com/k2-fsa/sherpa-onnx/blob/master/jitpack.yml
- Qwen3-ASR Android/ONNX documentation: https://k2-fsa.github.io/sherpa/onnx/qwen3-asr/index.html
- Qwen3-ASR official model family: https://github.com/QwenLM/Qwen3-ASR
