# Отчёт по аудиту функционала удаления фотографий

**Дата:** 2024  
**Ветка:** `audit-photo-deletion-checklist`  
**Версия БД:** 12 (MIGRATION_11_12 добавила таблицу `deletion_queue`)

---

## Executive Summary

**Общий статус:** ⚠️ **Частично реализовано (≈75%)**

Основная инфраструктура удаления фотографий реализована полностью:
- ✅ Room persistence layer (Entity, DAO, Repository)
- ✅ UI компоненты (ConfirmDeletionBar, ViewModel)
- ✅ UseCase для батч-удаления с поддержкой Android R+
- ✅ Unit-тесты для Repository и ViewModel
- ✅ Локализация

**Критические недостатки:**
- ❌ Отсутствует интеграция с Upload Flow (автоудаление после загрузки)
- ❌ Отсутствует настройка `autoDeleteAfterUpload` в DataStore
- ⚠️ Activity Result integration частично реализована, но не связана с `ConfirmDeletionUseCase`

---

## 1. Room + Persistence

### ✅ **Полностью реализовано**

#### Entity: `DeletionItem`
**Файл:** `core/data/src/main/java/com/kotopogoda/uploader/core/data/deletion/DeletionItem.kt`

```kotlin
@Entity(
    tableName = "deletion_queue",
    indices = [Index(value = ["status", "is_uploading"])]
)
data class DeletionItem(
    @PrimaryKey @ColumnInfo(name = "media_id") val mediaId: Long,
    @ColumnInfo(name = "content_uri") val contentUri: String,
    @ColumnInfo(name = "display_name") val displayName: String?,
    @ColumnInfo(name = "size_bytes") val sizeBytes: Long?,
    @ColumnInfo(name = "date_taken") val dateTaken: Long?,
    @ColumnInfo(name = "reason") val reason: String,
    @ColumnInfo(name = "status", defaultValue = "'pending'") val status: String = DeletionItemStatus.PENDING,
    @ColumnInfo(name = "is_uploading", defaultValue = "0") val isUploading: Boolean = false,
    @ColumnInfo(name = "created_at") val createdAt: Long = System.currentTimeMillis()
)
```

**Статус:** ✅ Все требуемые поля присутствуют, индекс создан для оптимизации запросов по `(status, is_uploading)`.

---

#### DAO: `DeletionItemDao`
**Файл:** `core/data/src/main/java/com/kotopogoda/uploader/core/data/deletion/DeletionItemDao.kt`

**Реализованные методы:**
- ✅ `enqueue(items: List<DeletionItem>)` — вставка с REPLACE стратегией
- ✅ `observePending(): Flow<List<DeletionItem>>` — реактивное наблюдение за `status=pending AND is_uploading=0`
- ✅ `getPending(): List<DeletionItem>` — синхронное получение pending
- ✅ `updateStatus(ids, status)` — обновление статуса + сброс `is_uploading`
- ✅ `updateUploading(ids, uploading, pendingStatus)` — установка флага `is_uploading` для pending элементов
- ✅ `purge(statuses, olderThan)` — очистка старых записей
- ✅ `getByIds(ids)` — получение по ID
- ✅ `getAll()` — полная выборка (для тестов)

**Статус:** ✅ Полный набор методов для управления очередью.

---

#### Repository: `DeletionQueueRepository`
**Файл:** `core/data/src/main/java/com/kotopogoda/uploader/core/data/deletion/DeletionQueueRepository.kt`

**Реализованные методы:**
- ✅ `observePending(): Flow<List<DeletionItem>>`
- ✅ `getPending(): List<DeletionItem>`
- ✅ `enqueue(requests: List<DeletionRequest>)`
- ✅ `markConfirmed(ids: List<Long>): Int`
- ✅ `markFailed(ids: List<Long>, cause: String?): Int`
- ✅ `markSkipped(ids: List<Long>): Int`
- ✅ `markUploading(ids: List<Long>, uploading: Boolean): Int`
- ✅ `purge(olderThan: Long): Int`

**Особенности:**
- Используется `Clock` для инъекции времени (тестируемо)
- Сортировка по `createdAt` с инкрементом для батчей
- Retention по умолчанию: 7 дней для terminal статусов (CONFIRMED, SKIPPED)
- Подробное логирование через Timber с тегом "DeletionQueue"

**Статус:** ✅ Полностью реализован со всеми требуемыми методами.

---

#### Миграция: `MIGRATION_11_12`
**Файл:** `core/data/src/main/java/com/kotopogoda/uploader/core/data/database/KotopogodaDatabase.kt:254-298`

```kotlin
val MIGRATION_11_12 = object : Migration(11, 12) {
    override fun migrate(db: SupportSQLiteDatabase) {
        val columns = getTableColumns(db, "deletion_queue")
        if (columns.isEmpty()) {
            // Создание новой таблицы
            db.execSQL("""CREATE TABLE IF NOT EXISTS `deletion_queue` (...)""")
        } else {
            // Добавление недостающих колонок (для будущих миграций)
            if ("status" !in columns) { ... }
            if ("is_uploading" !in columns) { ... }
            if ("created_at" !in columns) { 
                // Устанавливает текущее время для существующих записей
            }
        }
        db.execSQL("CREATE INDEX IF NOT EXISTS ...")
    }
}
```

**Статус:** ✅ Корректно обрабатывает как пустую БД, так и БД с существующей таблицей. Версия повышена до 12.

---

## 2. DataStore: настройки

### ❌ **Полностью отсутствует**

**Требуемый ключ:** `autoDeleteAfterUpload: Boolean` (default = true)

**Текущее состояние:**
- Файл `core/settings/src/main/kotlin/com/kotopogoda/uploader/core/settings/AppSettings.kt` содержит:
  ```kotlin
  data class AppSettings(
      val baseUrl: String,
      val appLogging: Boolean,
      val httpLogging: Boolean,
      val persistentQueueNotification: Boolean,
      val previewQuality: PreviewQuality,
  )
  ```
- ❌ Поле `autoDeleteAfterUpload` отсутствует
- ❌ Метод `setAutoDeleteAfterUpload(enabled: Boolean)` отсутствует в `SettingsRepository`

**Что нужно добавить:**
1. Поле `autoDeleteAfterUpload: Boolean = true` в `AppSettings`
2. Метод `suspend fun setAutoDeleteAfterUpload(enabled: Boolean)` в `SettingsRepository`
3. Реализация в `SettingsRepositoryImpl` с сохранением в DataStore
4. UI Toggle в Settings экране

**Файлы для изменения:**
- `core/settings/src/main/kotlin/com/kotopogoda/uploader/core/settings/AppSettings.kt`
- `core/settings/src/main/kotlin/com/kotopogoda/uploader/core/settings/SettingsRepository.kt`
- `core/settings/src/main/kotlin/com/kotopogoda/uploader/core/settings/SettingsRepositoryImpl.kt`

---

## 3. UI: верхняя панель

### ✅ **Полностью реализовано**

#### Компонент: `ConfirmDeletionBar`
**Файл:** `core/ui/src/main/java/com/kotopogoda/uploader/core/ui/ConfirmDeletionBar.kt`

```kotlin
@Composable
fun ConfirmDeletionBar(
    pendingCount: Int,
    inProgress: Boolean,
    onConfirm: () -> Unit,
    modifier: Modifier = Modifier,
)
```

**Функционал:**
- ✅ Отображает текст `"Подтвердить 🗑 XX"` где XX — `pendingCount`
- ✅ Кнопка неактивна при `pendingCount = 0` или `inProgress = true`
- ✅ Показывает `CircularProgressIndicator` при `inProgress = true`
- ✅ Используется `FilledTonalButton` с Material 3

**Интеграция:**
- ✅ Отображается в `ViewerScreen` (внутри `ViewerTopBar`)
- ✅ Также присутствует в `QueueScreen`

**ViewModel:** `DeletionConfirmationViewModel`
**Файл:** `core/data/src/main/java/com/kotopogoda/uploader/core/data/deletion/DeletionConfirmationViewModel.kt`

- ✅ Подписывается на `deletionQueueRepository.observePending()`
- ✅ Предоставляет `uiState: StateFlow<DeletionConfirmationUiState>` с полями:
  - `pendingCount: Int`
  - `pendingBytesApprox: Long`
  - `inProgress: Boolean`
  - `isConfirmEnabled: Boolean`
- ✅ Метод `confirmPending()` для подтверждения удаления
- ✅ События `DeletionConfirmationEvent` (ConfirmationSuccess, ConfirmationFailed)

**Статус:** ✅ Полностью реализовано, счётчик обновляется в реальном времени.

---

## 4. Activity Result + Системный диалог

### ⚠️ **Частично реализовано**

#### Launcher в ViewerScreen
**Файл:** `feature/viewer/src/main/java/com/kotopogoda/uploader/feature/viewer/ViewerScreen.kt:415-424`

```kotlin
val deleteLauncher = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.StartIntentSenderForResult()
) { result ->
    val outcome = when (result.resultCode) {
        Activity.RESULT_OK -> ViewerViewModel.DeleteResult.Success
        Activity.RESULT_CANCELED -> ViewerViewModel.DeleteResult.Cancelled
        else -> ViewerViewModel.DeleteResult.Failed
    }
    onDeleteResult(outcome)
}
```

**Статус:**
- ✅ Launcher создан и зарегистрирован
- ✅ Обрабатывает `RESULT_OK`, `RESULT_CANCELED`, `RESULT_FAILED`
- ⚠️ **НО:** используется для ViewerViewModel.onDelete (единичное удаление фото), а не для батч-удаления из `ConfirmDeletionUseCase`

#### Интеграция с ConfirmDeletionUseCase

**Проблема:**
- `ConfirmDeletionUseCase.prepare()` возвращает `PrepareResult.Ready(batches, initialOutcome)`
- `batches` содержат `DeleteBatch` с `intentSender: IntentSenderWrapper`
- ⚠️ **Отсутствует механизм запуска этих batches через Activity Result API**
- ✅ Метод `handleBatchResult(batch, resultCode, data)` реализован в UseCase, но НЕ вызывается из UI

**Что нужно доработать:**
1. Интегрировать `ConfirmDeletionUseCase` с `DeletionConfirmationViewModel`
2. При клике на `ConfirmDeletionBar` вызывать `useCase.prepare()`
3. Для каждого `DeleteBatch` запускать `deleteLauncher.launch(IntentSenderRequest.Builder(batch.intentSender).build())`
4. В callback лаунчера вызывать `useCase.handleBatchResult(batch, resultCode, data)`
5. Агрегировать результаты и показывать финальное уведомление

**Файлы для изменения:**
- `core/data/src/main/java/com/kotopogoda/uploader/core/data/deletion/DeletionConfirmationViewModel.kt`
- `feature/viewer/src/main/java/com/kotopogoda/uploader/feature/viewer/ViewerScreen.kt`

---

## 5. UseCase: батч-удаление

### ✅ **Полностью реализовано**

#### `ConfirmDeletionUseCase`
**Файл:** `core/data/src/main/java/com/kotopogoda/uploader/core/data/deletion/ConfirmDeletionUseCase.kt`

**Метод `prepare(chunkSize: Int = 200)`:**
- ✅ Проверяет пермишены (READ_MEDIA_IMAGES для API 33+, READ_EXTERNAL_STORAGE для более старых)
- ✅ Получает `pending && !isUploading` элементы из Repository
- ✅ Разбивает на chunks по 200 URI (DEFAULT_CHUNK_SIZE)
- ✅ Для Android R+ (API 30+):
  - Создаёт `MediaStore.createDeleteRequest()` через `MediaStoreDeleteRequestFactory`
  - Возвращает `PrepareResult.Ready` с batches
- ✅ Для Android Q и ниже:
  - Пытается удалить напрямую через `contentResolver.delete()`
  - Обрабатывает `RecoverableSecurityException` → создаёт batch с `intentSender`
  - Возвращает начальный outcome + pending batches

**Метод `handleBatchResult(batch, resultCode, data)`:**
- ✅ Обрабатывает `RESULT_OK` и `RESULT_CANCELED`
- ✅ Для `requiresRetryAfterApproval` повторяет `contentResolver.delete()`
- ✅ Для Android R+ проверяет отсутствие URI в MediaStore
- ✅ Вызывает `repository.markConfirmed/markFailed/markSkipped`
- ✅ Возвращает `BatchProcessingResult.Completed(outcome)`

**Дополнительные классы:**
- ✅ `MediaStoreDeleteRequestFactory` — обёртка для `MediaStore.createDeleteRequest`
- ✅ `IntentSenderWrapper` — wrapper для `android.content.IntentSender`
- ✅ `DeleteBatch`, `BatchItem`, `Outcome`, `PrepareResult`, `BatchProcessingResult` — data classes

**Статус:** ✅ Полная реализация с поддержкой всех Android версий, чанкированием и обработкой ошибок.

---

## 6. Upload Flow интеграция

### ❌ **Полностью отсутствует**

**Требуемый функционал:**
- При успешном завершении upload (`UploadTaskResult.Success`) проверять флаг `autoDeleteAfterUpload`
- Если флаг включён, добавлять фото в `DeletionQueueRepository.enqueue()` с `reason = "uploaded_cleanup"`
- Устанавливать `isUploading = true` перед началом загрузки
- Сбрасывать `isUploading = false` после завершения (успех или неудача)

**Текущее состояние:**
- ✅ `UploadTaskRunner.run()` возвращает `UploadTaskResult.Success` с `completionState`
- ✅ `UploadProcessorWorker` вызывает `repository.markSucceeded(item.id)` при успехе
- ❌ **НЕТ** вызова `deletionQueueRepository.enqueue()`
- ❌ **НЕТ** вызова `deletionQueueRepository.markUploading()`
- ❌ **НЕТ** проверки настройки `autoDeleteAfterUpload`

**Историческое решение:**
- В `UploadTaskRunner.deleteDocument()` (строка 220-245) есть старая логика удаления:
  - Для `file://` URI удаляет напрямую
  - Для MediaStore URI (Android Q и ниже) удаляет через `contentResolver.delete()`
  - Для Android R+ возвращает `AWAITING_MANUAL_DELETE`
- ⚠️ Эта логика работает **внутри worker'а** и **игнорирует** новую очередь удаления

**Что нужно добавить:**
1. В `UploadProcessorWorker.doWork()` после `repository.markSucceeded()`:
   ```kotlin
   val autoDelete = settingsRepository.flow.first().autoDeleteAfterUpload
   if (autoDelete) {
       deletionQueueRepository.enqueue(listOf(
           DeletionRequest(
               mediaId = extractMediaId(item.uri),
               contentUri = item.uri.toString(),
               displayName = item.displayName,
               sizeBytes = item.size,
               dateTaken = null,
               reason = "uploaded_cleanup"
           )
       ))
   }
   ```
2. Перед `taskRunner.run()` вызывать:
   ```kotlin
   deletionQueueRepository.markUploading(listOf(mediaId), uploading = true)
   ```
3. После завершения (success или failure):
   ```kotlin
   deletionQueueRepository.markUploading(listOf(mediaId), uploading = false)
   ```

**Файлы для изменения:**
- `core/network/src/main/java/com/kotopogoda/uploader/core/network/upload/UploadProcessorWorker.kt`
- Добавить зависимости: `DeletionQueueRepository`, `SettingsRepository`

---

## 7. Логирование и аналитика

### ✅ **Реализовано в Repository и UseCase**

#### Логи в `DeletionQueueRepository`:
```kotlin
Timber.tag("DeletionQueue").i("В очередь удаления добавлено %d элементов", prepared.size)
Timber.tag("DeletionQueue").i("Подтверждено удаление %d элементов", updated)
Timber.tag("DeletionQueue").w("Удаление %d элементов завершилось с ошибкой: %s", updated, cause)
Timber.tag("DeletionQueue").i("Пропущено удаление %d элементов", updated)
Timber.tag("DeletionQueue").i("Статус загрузки для %d элементов: %s", updated, if (uploading) "uploading" else "idle")
Timber.tag("DeletionQueue").i("Удалено %d записей из истории очереди", removed)
```

#### Логи в `ConfirmDeletionUseCase`:
```kotlin
Timber.tag("ConfirmDeletion").i("Требуется подтверждение пользователя для удаления %s", item.uri)
Timber.tag("ConfirmDeletion").w(security, "Отказано в доступе при удалении %s", item.uri)
Timber.tag("ConfirmDeletion").w(throwable, "Не удалось удалить %s", item.uri)
Timber.tag("ConfirmDeletion").i("Пользователь отменил подтверждение удаления для батча %s", batch.id)
```

**Статус:** ✅ Логирование присутствует, содержит:
- mediaCount (количество элементов)
- bytesFreed (в Outcome)
- причины (reason, cause)
- статусы операций

**Недостаёт:**
- ❌ События для аналитики (deletion_enqueued, deletion_confirm_dialog_shown и т.д.) не централизованы
- ⚠️ Рекомендуется добавить аналитику через отдельный слой (Firebase Analytics / custom tracker)

---

## 8. Тесты

### ✅ **Unit-тесты реализованы**

#### `DeletionQueueRepositoryTest`
**Файл:** `core/data/src/test/java/com/kotopogoda/uploader/core/data/deletion/DeletionQueueRepositoryTest.kt`

**Покрытие:**
- ✅ `observePendingFiltersUploadingAndStatus()` — фильтрация `pending && !isUploading`
- ✅ `enqueueResetsStatusAndSetsTimestamp()` — установка времени и статусов
- ✅ `statusTransitionsUpdateEntities()` — переходы между статусами
- ✅ `markUploadingAffectsPendingItemsOnly()` — обновление только pending элементов
- ✅ `purgeRemovesTerminalRecordsOlderThanThreshold()` — очистка старых записей

#### `DeletionConfirmationViewModelTest`
**Файл:** `core/data/src/test/java/com/kotopogoda/uploader/core/data/deletion/DeletionConfirmationViewModelTest.kt`

**Покрытие:** ✅ (файл найден, детали не проверялись)

#### `ViewerViewModelBatchDeleteTest`
**Файл:** `feature/viewer/src/test/java/com/kotopogoda/uploader/feature/viewer/ViewerViewModelBatchDeleteTest.kt`

**Покрытие:** ✅ (файл найден)

---

### ❌ **Instrumentation тесты отсутствуют**

**Требуемые тесты:**
- Эмуляция батча 3-5 URI → системный диалог → RESULT_OK
- Проверка очистки `deletion_queue` после подтверждения
- Тест на Android 11+ с `MediaStore.createDeleteRequest()`

**Файлы для создания:**
- `core/data/src/androidTest/java/com/kotopogoda/uploader/core/data/deletion/ConfirmDeletionUseCaseInstrumentationTest.kt`
- Требует эмулятор API 30+ и реальные MediaStore URI

---

## 9. Пермишены и Photo Picker

### ✅ **Реализовано**

#### Проверка пермишенов в `ConfirmDeletionUseCase`:
```kotlin
private fun requiredPermissionsFor(apiLevel: Int): Set<String> {
    val readPermission = if (apiLevel >= Build.VERSION_CODES.TIRAMISU) {
        Manifest.permission.READ_MEDIA_IMAGES
    } else {
        Manifest.permission.READ_EXTERNAL_STORAGE
    }
    return setOf(readPermission)
}
```

- ✅ Возвращает `PrepareResult.PermissionRequired(permissions)` если пермишены отсутствуют
- ✅ Поддержка READ_MEDIA_IMAGES (API 33+) и READ_EXTERNAL_STORAGE

#### Устойчивые identifiers:
- ✅ URI хранятся в виде `content://media/external/images/media/<_ID>`
- ✅ `mediaId` (тип `Long`) соответствует `MediaStore.MediaColumns._ID`

**Статус:** ✅ Корректная работа с MediaStore API.

---

## 10. Локализация

### ✅ **Реализовано на русском языке**

**Файл:** `core/ui/src/main/res/values/strings.xml`

```xml
<string name="confirm_deletion_button">Подтвердить 🗑 %1$d</string>
<string name="confirm_deletion_result">Удалено %1$d, освобождено ≈%2$s</string>
<string name="confirm_deletion_error">Не удалось подтвердить удаление</string>
```

**Статус:** ✅ Строки присутствуют на русском языке, форматирование с параметрами.

**Недостаёт:**
- ❌ Перевод на английский язык (`values-en/strings.xml`)
- ❌ Тексты дисклеймеров о локальном удалении (например, в диалоге настроек)

**Рекомендации:**
- Добавить файл `core/ui/src/main/res/values-en/strings.xml` с переводами
- Добавить строки:
  ```xml
  <string name="settings_auto_delete_title">Автоудаление после загрузки</string>
  <string name="settings_auto_delete_description">Фотографии будут удалены с устройства после успешной загрузки на сервер</string>
  <string name="deletion_disclaimer">Удаление локальное. Фото на сервере останутся.</string>
  ```

---

## Итоговая таблица по всем компонентам

| # | Компонент | Статус | Файлы | Примечания |
|---|-----------|--------|-------|------------|
| 1.1 | DeletionItem Entity | ✅ | `core/data/.../DeletionItem.kt` | Все поля присутствуют |
| 1.2 | DeletionItemDao | ✅ | `core/data/.../DeletionItemDao.kt` | Полный набор методов |
| 1.3 | DeletionQueueRepository | ✅ | `core/data/.../DeletionQueueRepository.kt` | Все методы реализованы |
| 1.4 | Миграция MIGRATION_11_12 | ✅ | `core/data/.../KotopogodaDatabase.kt:254` | Версия 12, корректная миграция |
| 1.5 | Логирование миграции | ✅ | — | Логи в Repository |
| 2.1 | autoDeleteAfterUpload в DataStore | ❌ | `core/settings/.../AppSettings.kt` | **Требует добавления** |
| 2.2 | Функции чтения/записи настройки | ❌ | `core/settings/.../SettingsRepository.kt` | **Требует добавления** |
| 3.1 | ConfirmDeletionBar компонент | ✅ | `core/ui/.../ConfirmDeletionBar.kt` | Полностью реализован |
| 3.2 | Счётчик с эмодзи | ✅ | — | `"Подтвердить 🗑 XX"` |
| 3.3 | Кнопка неактивна при count=0 | ✅ | — | `enabled = pendingCount > 0 && !inProgress` |
| 3.4 | Реальное время обновления | ✅ | — | Через Flow из Repository |
| 4.1 | ActivityResultLauncher создан | ✅ | `feature/viewer/.../ViewerScreen.kt:415` | Launcher для delete |
| 4.2 | Callback обрабатывает результаты | ⚠️ | — | **Не связан с ConfirmDeletionUseCase** |
| 4.3 | Показ уведомления после OK | ⚠️ | — | Через DeletionConfirmationEvent |
| 5.1 | ConfirmDeletionUseCase | ✅ | `core/data/.../ConfirmDeletionUseCase.kt` | Полная реализация |
| 5.2 | Чанкирование по 200 URI | ✅ | — | DEFAULT_CHUNK_SIZE = 200 |
| 5.3 | MediaStore.createDeleteRequest | ✅ | — | Через MediaStoreDeleteRequestFactory |
| 5.4 | Обработка через ActivityResult | ⚠️ | — | **Требует интеграции в UI** |
| 6.1 | Проверка autoDeleteAfterUpload | ❌ | `core/network/.../UploadProcessorWorker.kt` | **Требует добавления** |
| 6.2 | Enqueue при успешном upload | ❌ | — | **Требует добавления** |
| 6.3 | Исключение uploading из батча | ✅ | — | Фильтр в DAO/Repository |
| 7.1 | Логирование событий | ✅ | — | Timber с тегами |
| 7.2 | Параметры (count, bytes, причины) | ✅ | — | Присутствуют |
| 7.3 | Централизованная аналитика | ❌ | — | **Рекомендуется добавить** |
| 8.1 | Unit тесты Repository | ✅ | `core/data/src/test/.../DeletionQueueRepositoryTest.kt` | 5 тестов |
| 8.2 | Unit тесты UseCase | ⚠️ | — | **Требует добавления** |
| 8.3 | Instrumentation тесты | ❌ | — | **Требует создания** |
| 9.1 | Проверка пермишенов | ✅ | `core/data/.../ConfirmDeletionUseCase.kt:240` | READ_MEDIA_IMAGES / READ_EXTERNAL_STORAGE |
| 9.2 | Устойчивые identifiers | ✅ | — | content://media/.../<_ID> |
| 10.1 | Строки UI на русском | ✅ | `core/ui/src/main/res/values/strings.xml` | 3 строки |
| 10.2 | Перевод на английский | ❌ | — | **Требует добавления** |
| 10.3 | Тексты дисклеймеров | ❌ | — | **Требует добавления** |

---

## Список задач для завершения функционала

### Высокий приоритет (блокирует автоудаление):

1. **Добавить настройку `autoDeleteAfterUpload` в DataStore**
   - Изменить `AppSettings.kt`, `SettingsRepository.kt`, `SettingsRepositoryImpl.kt`
   - Добавить UI Toggle в Settings экране
   - Создать тесты для новой настройки

2. **Интегрировать DeletionQueue с Upload Flow**
   - Инъектировать `DeletionQueueRepository` и `SettingsRepository` в `UploadProcessorWorker`
   - Вызывать `enqueue()` с reason="uploaded_cleanup" при успешной загрузке
   - Устанавливать `markUploading()` при старте и завершении загрузки

3. **Связать ConfirmDeletionUseCase с Activity Result API**
   - Расширить `DeletionConfirmationViewModel` для вызова `useCase.prepare()`
   - Обработать `PrepareResult.Ready.batches` и запускать их через launcher
   - Агрегировать результаты через `useCase.handleBatchResult()`

### Средний приоритет (улучшает UX):

4. **Добавить Instrumentation тесты**
   - Создать `ConfirmDeletionUseCaseInstrumentationTest.kt`
   - Тесты для Android R+ с эмуляцией системного диалога

5. **Добавить локализацию на английский**
   - Создать `core/ui/src/main/res/values-en/strings.xml`
   - Перевести все строки удаления
   - Добавить дисклеймеры

6. **Централизовать аналитические события**
   - Создать `DeletionAnalytics` интерфейс
   - Логировать: `deletion_enqueued`, `deletion_confirm_dialog_shown`, `deletion_confirmed`, `deletion_canceled`, `autodelete_setting_changed`

### Низкий приоритет (оптимизации):

7. **Добавить Unit-тесты для ConfirmDeletionUseCase**
   - Тесты для чанкирования
   - Тесты для обработки пермишенов
   - Тесты для различных Android версий

8. **Оптимизировать логирование**
   - Унифицировать теги (например, использовать один тег "Deletion")
   - Добавить structured logging для парсинга

9. **Рефакторинг старой логики удаления**
   - Удалить `UploadTaskRunner.deleteDocument()` после полной миграции на DeletionQueue
   - Убедиться, что все пути используют новую очередь

---

## Заключение

**Основная инфраструктура удаления фотографий реализована качественно:**
- Persistence layer (Room) полностью готов
- UI компоненты работают
- UseCase поддерживает все Android версии с правильным чанкированием

**Критические недостатки, блокирующие автоудаление:**
- Отсутствует интеграция с Upload Flow
- Нет настройки `autoDeleteAfterUpload`
- Activity Result API не связан с ConfirmDeletionUseCase

**Рекомендации:**
1. Начать с задачи #1 (настройка) и #2 (интеграция с Upload) — это разблокирует автоудаление
2. Затем задачу #3 (Activity Result) — это даст батч-удаление через UI
3. Остальные задачи можно выполнять параллельно или после MVP

**Оценка готовности:** 75% (основа готова, требуется интеграция слоёв)
