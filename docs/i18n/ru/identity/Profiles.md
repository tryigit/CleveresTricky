# Profiles

**Язык:** [English](../../../identity/Profiles.md) | [Türkçe](../../tr/identity/Profiles.md) | [简体中文](../../zh-CN/identity/Profiles.md) | [Español](../../es/identity/Profiles.md) | [Deutsch](../../de/identity/Profiles.md) | **Русский** | [Bahasa Indonesia](../../id/identity/Profiles.md) | [हिन्दी](../../hi/identity/Profiles.md) | [العربية](../../ar/identity/Profiles.md)

Профили применяют наборы необязательных настроек одной проверенной транзакцией; базовая защита boot, Keystore и инфраструктуры RKP остаётся активной независимо.

Daily Compatibility использует targeted scope и мониторинг keybox; Default: консервативный режим (применение профиля Default или сброс окружения в WebUI восстанавливает `target.txt`, `identity_target.txt`, `drm_packages.txt`, `boot_props_mode` и `security_patch.txt` к чистым исходным значениям по умолчанию и удаляет все настроенные удалённые серверы вместе с кэшированным содержимым keybox); Maximum Compatibility включает Global Mode, build identity, identity refresh и telephony и выключает DRM passthrough; Minimal отключает необязательную identity-логику и плановые проверки keybox. Ни один preset не меняет защиту инфраструктуры RKP.

Старые конфигурации могут содержать выведенный из эксплуатации маркер `rkp_passthrough`, но generated-key поведение больше от него не зависит. Профили version two могут хранить назначения приложений, template, проверенный keybox, privacy, patch и параметры identity/DRM; старое поле RKP сохраняется только для миграционной совместимости и не является активной настройкой WebUI.
