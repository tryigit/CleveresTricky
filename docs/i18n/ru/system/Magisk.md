# Поддержка Magisk

**Язык:** [English](../../../system/Magisk.md) | [Türkçe](../../tr/system/Magisk.md) | [简体中文](../../zh-CN/system/Magisk.md) | [Español](../../es/system/Magisk.md) | [Deutsch](../../de/system/Magisk.md) | **Русский** | [Bahasa Indonesia](../../id/system/Magisk.md) | [हिन्दी](../../hi/system/Magisk.md) | [العربية](../../ar/system/Magisk.md)

## Важное предупреждение

> [!CAUTION]
> **Magisk НЕ рекомендуется для использования с CleveresTricky.**
>
> Современные системы обнаружения и Google Play Integrity активно проверяют пространства имен монтирования в пространстве пользователя, бинарные файлы root и внедрение в Zygote. Архитектура Magisk оставляет заметные следы, что существенно затрудняет долговременную маскировку root.
>
> Подробное сравнение архитектур root на уровне ядра и в пространстве пользователя доступно здесь:
> 👉 **[Advanced Android Root Guide: KernelSU, APatch & Concealment | Yiğit - tryigit.dev](https://tryigit.dev/advanced-android-root-architecture-concealment/)**
>
> **По возможности выбирайте [KernelSU](https://kernelsu.org) или [APatch](https://apatch.dev).**

---

## Архитектура без WebUI (Headless)

KernelSU и APatch предоставляют встроенную среду для WebUI-расширений модулей внутри своих приложений-менеджеров. Magisk не поддерживает этот интерфейс.

Поэтому при установке в Magisk **CleveresTricky работает в фоновом режиме демона без графического интерфейса WebUI**. Основной нативный демон (`cleverestrickyd`), бэкенд и перехватчики Keystore функционируют в полном объеме, однако настройка производится вручную путем редактирования файлов в `/data/adb/cleverestricky/`.

---

## Руководство по ручной настройке

Все файлы настроек и политик располагаются в каталоге `/data/adb/cleverestricky/`.

### 1. Область действия и цели

* **`target.txt`**: Список имен пакетов (по одному на строку), для которых подменяется аттестация Keystore:
  ```text
  com.google.android.gms
  com.google.android.gms.unstable
  com.android.vending
  ```
* **`global_mode`**: Пустой файл-маркер.
  * **Присутствует**: Глобальный режим активен (перехватываются все несистемные приложения).
  * **Отсутствует**: Перехватываются только пакеты из `target.txt`.
  * *Команда:* `touch /data/adb/cleverestricky/global_mode` (включить) или `rm -f /data/adb/cleverestricky/global_mode` (отключить).

* **`identity_target.txt`**: Целевые пакеты для подмены идентификаторов устройства.
* **`global_identity_mode`**: Пустой файл-маркер для глобального применения подмены идентификаторов.

---

### 2. Идентификация устройства и свойства сборки

* **`spoof_build_vars`**: Подменяемые параметры в формате `КЛЮЧ=ЗНАЧЕНИЕ`:
  ```properties
  MANUFACTURER=Google
  MODEL=Pixel 8 Pro
  FINGERPRINT=google/husky/husky:14/UQ1A.240105.004/11269998:user/release-keys
  BRAND=google
  PRODUCT=husky
  DEVICE=husky
  RELEASE=14
  ID=UQ1A.240105.004
  INCREMENTAL=11269998
  TYPE=user
  TAGS=release-keys
  ```
* **`security_patch.txt`**: Дата патча безопасности (например, `2026-03-05`). Оставьте пустым для автосогласования.
* **`boot_props_mode`**: Режим свойств загрузчика (`auto`, `manual` или `disabled`).

---

### 3. Аппаратный Keybox

* **`keybox.xml`**: Разместите ваш XML-файл аппаратной аттестации напрямую в `/data/adb/cleverestricky/keybox.xml`. Убедитесь в ограничении прав доступа (`chmod 600`).
* **`keyboxes/`**: Каталог для хранения нескольких файлов keybox.

---

### 4. DRM и конфиденциальность

* **`drm_packages.txt`**: Мультимедийные приложения, исключаемые из перехвата Keystore для сохранения Widevine L1:
  ```text
  com.netflix.mediaclient
  com.amazon.avod.thirdpartyclient
  com.disney.disneyplus
  ```

---

### 5. Флаги функций (Файлы-маркеры)

Создайте файл для включения (`touch <файл>`) или удалите для выключения (`rm -f <файл>`):

| Файл-маркер | Назначение при наличии |
| :--- | :--- |
| `spoof_enabled` | Включает движок подмены идентификаторов. |
| `spoof_build_identity` | Включает подмену свойств сборки. |
| `auto_keybox_check` | Автоматически проверяет валидность и статус отзыва keybox. |
| `drm_passthrough` | Активирует исключение DRM для приложений из `drm_packages.txt`. |
| `hide_sensitive_props` | Скрывает признаки root, отладки и статуса загрузчика. |
| `tee_broken_mode` | Включает программную эмуляцию аттестации при поврежденном аппаратном TEE. |
| `debug_logging` | Включает подробное логирование в `native_runtime.log`. |

---

## Применение изменений и проверка

### Применение конфигурации
Так как в Magisk нет WebUI для динамического перезапуска, рекомендуется перезагрузить устройство:
```sh
su -c "reboot"
```

### Просмотр логов
```sh
su -c "cat /data/adb/cleverestricky/native_runtime.log"
```

### Проверка запущенных процессов
```sh
su -c "ps -A | grep -E 'cleverestrickyd|cleverestricky_backend'"
```

### Сбор диагностического отчета
```sh
su -c "/data/adb/modules/cleverestricky/action.sh"
```
Архив будет сохранен в каталоге `/data/adb/cleverestricky/bugreports/`.
