# Сбор журналов и диагностика (Logging and Diagnostics)

**Язык:** [English](../../../LOG.md) | [Türkçe](../tr/LOG.md) | [简体中文](../zh-CN/LOG.md) | [Español](../es/LOG.md) | [Deutsch](../de/LOG.md) | **Русский** | [Bahasa Indonesia](../id/LOG.md) | [हिन्दी](../hi/LOG.md) | [العربية](../ar/LOG.md)

CleveresTricky предоставляет несколько встроенных инструментов для формирования диагностических отчётов, журналов выполнения и аварийных архивов. При создании Issue или сообщении об ошибке предоставление диагностики и журналов обязательно для выявления первопричины проблемы.

---

## Способ 1: Панель управления WebUI (Рекомендуется)

WebUI обеспечивает самый быстрый и удобный способ сбора данных непосредственно с устройства без использования ADB или ПК.

### A. Снимок поддержки и диагностики (Support Diagnostics Snapshot)
1. Откройте WebUI CleveresTricky в браузере или через менеджер root.
2. Перейдите на вкладку **Информация и ресурсы (Info & Resources)**.
3. Прокрутите страницу до раздела **Диагностика поддержки (Support Diagnostics)**.
4. Нажмите кнопку **Копировать диагностику (Copy Diagnostics)**.
5. Вставьте скопированный текст прямо в соответствующий раздел шаблона ошибки на GitHub (**Support Diagnostics Snapshot**).

> [!NOTE]
> Снимок диагностики строго конфиденциален. Он содержит только состояние модуля, версию среды, тип root, загрузку памяти/ЦП и флаги функций. Закрытые ключи, пароли, сертификаты keybox, имена пакетов и идентификаторы никогда не включаются в отчёт.

### B. Живые логи WebUI и отладка (Debug Logging)
1. В WebUI откройте вкладку **Журналы (Logs)**.
2. Переключите тумблер **Debug Logging** во включённое состояние (тумблер станет зелёным). Это активирует детальную трассировку без установки отладочной сборки.
3. Воспроизведите ошибку или проверку Play Integrity на устройстве.
4. Вернитесь на вкладку **Logs** и нажмите **Копировать (Copy)**.
5. Вставьте полученный текст в отчёт об ошибке.
6. После завершения выключите **Debug Logging**, чтобы не расходовать системные ресурсы.

---

## Способ 2: Создание аварийного архива в один клик (`action.sh`)

Модуль содержит встроенный автоматический инструмент, который упаковывает системные логи, состояние модуля и информацию о root-окружении в сжатый архив (`.tar.gz`).

### Через менеджер Root:
- В **KernelSU** или **APatch** нажмите кнопку **Действие (Action)** рядом с карточкой модуля CleveresTricky.

### Через терминал (Termux / Root Shell):
```bash
su -c /data/adb/modules/cleverestricky/action.sh
```

### Где найти архив:
- Сформированный архив сохраняется по адресу:
  ```
  /data/adb/cleverestricky/bugreports/CleveresTricky_Bugreport_<метка_времени>.tar.gz
  ```
  (и автоматически дублируется в каталог `Download/` устройства, если есть доступ).
- Вы можете прикрепить этот архив к вашему Issue на GitHub.

> [!TIP]
> Файлы XML/CBOX keybox и персональные сертификаты намеренно исключены из архива для защиты безопасности.

---

## Способ 3: Журнал выполнения нативного демона (`native_runtime.log`)

Фоновый нативный процесс непрерывно фиксирует статус инициализации, перехваты (hooks) и состояние выполнения в:
```
/data/adb/cleverestricky/native_runtime.log
```

Просмотр последних строк через терминал:
```bash
su -c "tail -n 100 /data/adb/cleverestricky/native_runtime.log"
```

---

## Способ 4: Android Logcat (ADB или Терминал)

Если интерфейс WebUI не открывается или модуль не стартует, logcat предоставляет прямые сведения о процессе запуска и транзакциях Binder.

### Захват в реальном времени через ADB:
```bash
adb logcat -s cleverestricky CleveresTricky
```

### В Termux или root-оболочке:
```bash
su -c "logcat -d -s cleverestricky CleveresTricky"
```

### Запись чистого запуска (с перезапуском Keystore):
```bash
adb logcat -c
adb shell su -c 'setprop ctl.restart keystore2'
adb logcat -d -s cleverestricky CleveresTricky
```

### Ключевые маркеры успешного запуска:
- `Welcome to Service!`
- `Web server on port ...`
- `libbinder ioctl hook installed successfully`
- `Keystore Binder interceptor registered`
- `TEE SecurityLevel interceptor registered`

### Маркер аппаратного сбоя KeyMint / TEE:
Если в логах появляется:
```text
[WARN] Platform KeyMint HAL unreachable or TEE broken (SECURE_HW_COMMUNICATION_FAILED).
[INFO] CleveresTricky requires a functional hardware KeyMint; aborting injection to prevent framework deadlock.
[INFO] Please consult documentation (docs/security/Attestation.md or docs/LOG.md) for TEE recovery guidance.
```
Это указывает на сбой связи с аппаратным TEE или KeyMint HAL производителя (код ошибки -49 / 10). CleveresTricky намеренно прерывает перехват для предотвращения взаимных блокировок во фреймворке. Варианты восстановления TEE на стороне устройства (например, восстановление TEE RKP на разблокированных OnePlus 13/15) описаны в [Attestation.md](security/Attestation.md#аппаратный-tee-provisioning-и-примечание-по-восстановлению-oneplus).

> [!WARNING]
> Проверьте логи перед отправкой. Данные учётных записей и токены WebUI не записываются, но имена моделей устройств, PID и имена установленных приложений могут присутствовать.

---

## Что необходимо прикрепить к Issue

При создании отчёта об ошибке на GitHub укажите:
1. **Снимок диагностики поддержки** (со вкладки Информация в WebUI)
2. **Журналы** (со вкладки Logs с включённым Debug Logging, архив `action.sh` или вывод logcat)
3. Модель устройства, версию Android и используемый Root (KernelSU / APatch / Magisk)
