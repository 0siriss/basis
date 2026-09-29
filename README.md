# Basis — локальный ИИ-ежедневник для Android

Фоновая запись → VAD → локальный ASR → локальная LLM → дневник дня + поиск/чат по прошлому.
Никаких облачных API и аналитики. Аудио хранится только во временном зашифрованном буфере до расшифровки.

> Статус: **этап 0** — окружение проверено, структура модулей предложена. Кода пока нет.

## Этап 0. Окружение

Сборка — **только в GitHub Actions** (локальная сборка в облачной сессии агента невозможна, см. ниже).
Установка на устройство и замеры — у владельца телефона через `adb` (скрипты будут в `tools/`).

| Компонент | В сессии агента | В GitHub Actions (`ubuntu-latest`) |
|---|---|---|
| JDK | 21 ✅ | `actions/setup-java` 21 ✅ |
| Gradle | 9.x (через wrapper) ✅ | wrapper ✅ |
| Android SDK / build-tools / platform | ❌ нет, `dl.google.com` заблокирован прокси | предустановлен ✅ |
| Google Maven (`maven.google.com`) | ❌ заблокирован (редирект на dl.google.com) | ✅ |
| NDK + CMake | ❌ | `sdkmanager "ndk;28.x" "cmake;3.31.x"` ✅ |
| Hugging Face (модели) | ❌ заблокирован | ✅ (но модели в CI не нужны) |
| adb / устройство | ❌ (облачный контейнер) | эмулятор через `android-emulator-runner` для инструментальных тестов |

Вывод: код пишу здесь, каждый push собирается в Actions (debug APK как artifact), проверку на реальном
телефоне делает владелец по чек-листу этапа (`tools/stageN-check.sh` — установка, логи, замеры RTF/батареи).
Поведение FGS на Android 14+ дополнительно проверяется инструментальными тестами на эмуляторе API 34/35/36.

### Актуальные версии (проверено 2026-09-29)

| Что | Версия | Комментарий |
|---|---|---|
| AGP | 9.4.0 | Gradle ≥ 9.6, JDK 17+, max API 37, встроенный Kotlin |
| Kotlin | 2.4.20 | KSP 2.3.x |
| compileSdk / targetSdk | 36 (Android 16) | 37 — поднимем, когда стабилизируется в CI-образе |
| minSdk | 31 | по ТЗ |
| NDK | 28.2.13676358 | по умолчанию для AGP 9.4 |
| sherpa-onnx | 1.13.8 (10.09.2026) | готовый AAR из GitHub Releases |
| ONNX Runtime Android | 1.30.0 | только если понадобится отдельно (см. риски) |
| llama.cpp | пин на конкретный тег `bNNNN` (git submodule) | собираем сами через CMake |

### Модели (скачиваются при первом запуске или импортируются через file picker)

| Роль | Модель | Размер (≈) |
|---|---|---|
| VAD | Silero VAD v5 (ONNX, из релизов sherpa-onnx) | 2 МБ |
| ASR ru (по умолчанию) | GigaAM **v3** RNNT/CTC, sherpa-onnx, int8 (`csukuangfj/sherpa-onnx-nemo-*-giga-am-v3-russian-2025-12-16`) | 220–250 МБ |
| ASR мультиязычный | Whisper small (int8) / base — для ru+en вперемешку | 250 / 80 МБ |
| LLM | **Qwen3.5-4B-Instruct Q4_K_M** (Apache-2.0, хороший русский); альтернатива Gemma-4 E4B / Qwen3.5-1.7B для скорости | ~2.7 ГБ |
| Эмбеддинги | multilingual-e5-small (ONNX int8) | 120 МБ |

## Ключевые решения

**Нативные библиотеки.**
- *sherpa-onnx*: готовый AAR из релиза, скачивается в CI скриптом `tools/fetch-native.sh` с проверкой SHA-256
  (в git не кладём — 48 МБ). Silero VAD берём **из sherpa-onnx** (`Vad` API) — один onnxruntime на всё.
- *llama.cpp*: git submodule, пин на тег, свой тонкий JNI-слой (~300 строк C++) + CMake через `externalNativeBuild`.
  Это надёжнее сторонних обёрток (они отстают от upstream и новых архитектур вроде Qwen3.5). Сборка arm64-v8a
  с `GGML_CPU_ARM_ARCH=armv8.2-a+dotprod+i8mm` (топовый телефон → i8mm ускоряет Q4). Vulkan — опционально позже.
- *Эмбеддинги*: sherpa-onnx AAR уже содержит `libonnxruntime.so`. Подключение второго ORT (`onnxruntime-android`)
  даст конфликт `.so`. Решение: e5-small через **llama.cpp в режиме embeddings (GGUF)** — тот же движок,
  без второго рантайма. Запасной вариант — ORT Java API с `pickFirst` и совпадающей версией ORT.
  *Отклонение от ТЗ (ONNX → GGUF для e5) — прошу подтвердить.*

**DI: Hilt.** Много Android-точек входа (FGS, WorkManager-воркеры, TileService, BroadcastReceiver) —
`@HiltWorker`/`@AndroidEntryPoint` закрывают их без ручной фабрики. Цена — kapt не нужен, KSP работает.

**Векторный поиск: Room + косинус в памяти + FTS4 (гибрид).** Оценка: ~150 чанков/день × 365 × 384 float ≈ 84 МБ/год
на диске; brute-force по 55k векторам на современном ARM — десятки мс. sqlite-vec требует своей сборки SQLite
(framework SQLite не грузит расширения) — сложность не окупается. FTS4 нужен для имён и точных слов («Саша»),
которые эмбеддинги ловят хуже. Ранжирование: RRF(вектор, BM25) + фильтр по дате, если в вопросе есть дата.

**Шифрование буфера.** Ключ AES-256-GCM в Android Keystore (не экспортируемый). Каждый сегмент — отдельный файл
в `noBackupFilesDir/segments/`, потоковое шифрование чанками по 64 КБ (свой IV на чанк). После успешного ASR —
удаление файла и записи. Проверка «аудио на диске не осталось» — инструментальный тест + отладочный экран.

**Батарея.** Днём только `AudioRecord` + VAD (Silero ~1 мс на 32 мс окно). ASR/LLM/индексация —
цепочка WorkManager `requiresCharging` (опционально «батарея > X%»), `setForeground` на время тяжёлой работы.

**Android 14+.** `foregroundServiceType="microphone"` + `FOREGROUND_SERVICE_MICROPHONE`; стартовать FGS с микрофоном
из фона нельзя → после перезагрузки/убийства процесса не стартуем запись молча, а показываем уведомление
«Продолжить запись» (тап = старт из foreground-контекста). Tile и уведомление — разрешённые точки входа.

## Предлагаемая структура модулей

```
basis/
├─ app/                     # Application, Hilt, навигация, MainActivity, TileService, BootReceiver
├─ core/
│  ├─ common/               # Result, dispatchers, время/таймзоны, логгер (только logcat, без аналитики)
│  ├─ model/                # доменные модели: Segment, Transcript, DaySummary, Chunk, SearchHit
│  ├─ database/             # Room: сегменты-метаданные, расшифровки, саммари, чанки, эмбеддинги, FTS
│  ├─ datastore/            # настройки (Proto/Preferences DataStore)
│  ├─ crypto/               # Keystore AES-GCM, потоковое шифрование файлов (+ unit-тесты)
│  └─ ui/                   # тема, общие Compose-компоненты
├─ audio/
│  ├─ capture/              # RecordingService (FGS microphone), AudioRecord 16k mono, состояние/пауза
│  └─ vad/                  # интерфейс Vad + SileroVad (sherpa-onnx), сегментатор (pre/post-roll, мин. длина)
├─ ml/
│  ├─ models/               # реестр моделей, загрузка (DownloadManager-подобно, с SHA-256), импорт через SAF
│  ├─ asr/                  # interface SpeechRecognizer; GigaAmRecognizer, WhisperRecognizer (sherpa-onnx)
│  ├─ llm/                  # interface LlmEngine; LlamaCppEngine (JNI) + native/ (CMake, llama.cpp submodule)
│  └─ embed/                # interface Embedder; E5Embedder (e5 prefix "query:"/"passage:")
├─ pipeline/                # WorkManager: TranscribeWorker, SummarizeWorker (map-reduce), IndexWorker, RetentionWorker
├─ search/                  # чанкинг, гибридный поиск (cosine+FTS, RRF), RAG-промпт для чата (+ unit-тесты)
├─ feature/
│  ├─ home/                 # статус, старт/пауза, «приватный режим N минут»
│  ├─ timeline/             # лента дней, саммари, раскрываемые расшифровки
│  ├─ chat/                 # чат с ежедневником, ссылки на дату/время
│  ├─ settings/             # модели, условия обработки, срок хранения, экспорт Markdown
│  └─ onboarding/           # разрешения, battery optimization (Xiaomi/Samsung/Huawei), юр. напоминание
├─ tools/                   # download-models.sh, adb-push-models.sh, fetch-native.sh, stageN-check.sh
└─ .github/workflows/       # build.yml (assembleDebug + unit tests + lint), emulator.yml (API 34/35/36)
```

Зависимости однонаправленные: `feature/*` → `pipeline`, `search`, `ml/*`, `audio/*` → `core/*`.
Нативный код изолирован в `ml/llm` (llama.cpp) и в AAR sherpa-onnx (`ml/asr`, `audio/vad`, при необходимости `ml/embed`).

## Известные ограничения (будут дополняться)
- Нет доступа к физическому устройству из среды разработки: замеры RTF/батареи делает владелец по скриптам.
- Android не даёт стартовать FGS с микрофоном из фона (14+) — после убийства процесса нужен тап пользователя.
- Прошивки Xiaomi/Huawei/Samsung могут убивать сервис несмотря на FGS — инструкции в onboarding.
