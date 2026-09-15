# CleveresTricky

**Язык:** [English](README.md) | [Türkçe](README.tr.md) | [简体中文](README.zh-CN.md) | [Español](README.es.md) | [Deutsch](README.de.md) | **Русский** | [Bahasa Indonesia](README.id.md) | [हिन्दी](README.hi.md) | [العربية](README.ar.md)

[![Release](https://img.shields.io/github/v/release/tryigit/CleveresTricky?display_name=tag&sort=semver&label=Release)](https://github.com/tryigit/CleveresTricky/releases/latest)
[![Загрузки](https://img.shields.io/github/downloads/tryigit/CleveresTricky/total?color=0A84FF&label=%D0%97%D0%B0%D0%B3%D1%80%D1%83%D0%B7%D0%BA%D0%B8)](https://github.com/tryigit/CleveresTricky/releases)
![Android](https://img.shields.io/badge/Android-12--17-3DDC84?logo=android&logoColor=white)
![Module](https://img.shields.io/badge/Module-KernelSU%20%7C%20APatch-6f42c1)

CleveresTricky - модуль для KernelSU и APatch на Android 12-17. Он объединяет совместимость Android Keystore и attestation, управление Keybox/CBOX, выбор приложений, дополнительные настройки идентичности, уровни патчей и инструменты приватности в одном мобильном WebUI.

Начните со стандартных настроек и включайте только те функции, которые действительно нужны.

## Что можно делать

- Управлять, проверять, выбирать и менять файлы **Keybox/CBOX**.
- Использовать Global Mode или применять отдельные правила к выбранным приложениям.
- При необходимости настраивать представление device/build, attestation, телефонии, региона и security patch.
- Защищать потоки Remote Key Provisioning и уменьшать раскрытие поддерживаемых DRM-идентификаторов без заявления о DRM bypass.
- Создавать резервные копии, смотреть эффективное состояние и собирать диагностику через WebUI или Action модуля.

## Быстрый старт

1. Скачайте последнюю ZIP-сборку с официальной страницы [Releases](https://github.com/tryigit/CleveresTricky/releases/latest).
2. Установите ZIP через KernelSU или APatch при запущенном Android.
3. Откройте WebUI CleveresTricky из менеджера модулей.
4. Добавляйте только **Keybox или CBOX**, которыми вы владеете или которые вам разрешено тестировать.
5. Сначала оставьте стандартную конфигурацию и включайте идентичность, правила приложений или приватность только при необходимости.

Проект не содержит готового к использованию Keybox или приватного attestation-ключа.

## Поддерживаемая среда

- Android **12-17** / API **31-37**
- **ARM64** и **x86-64**
- **KernelSU** и **APatch** (рекомендуется, полная поддержка WebUI)
- **Magisk** (режим headless / ручная настройка через `/data/adb/cleverestricky/`, [не рекомендуется](https://tryigit.dev/advanced-android-root-architecture-concealment/))

Установка из recovery не поддерживается.

## Важно знать

CleveresTricky улучшает локальный путь совместимости, но удалённый результат всё равно зависит от реального устройства, прошивки, статуса сертификации, Google Play services, политики сервера и ваших настроек. Модуль не может гарантировать конкретный результат Play Integrity или attestation.

Он не блокирует bootloader физически заново, не переписывает измерения Verified Boot, не меняет аппаратный Root of Trust, модем или baseband и не превращает функции DRM-приватности в DRM bypass.

Используйте только те конфигурации и учётные данные, на которые у вас есть разрешение.

## Подробнее

- [Руководство по Strong Integrity](docs/i18n/ru/security/StrongIntegrityGuide.md) - быстрое руководство по прохождению Google Play Integrity (MEETS_STRONG_INTEGRITY) на официальных, AOSP и кастомных прошивках.
- [Руководство по поддержке Magisk](docs/i18n/ru/system/Magisk.md) - руководство по ручной настройке в режиме headless и маскировке root для пользователей Magisk.
- [Keybox Manager](docs/i18n/ru/security/KeyboxManager.md) - загрузка, проверка, выбор и проверка отзыва Keybox/CBOX.
- [Application Scope](docs/i18n/ru/identity/ApplicationScope.md) и [Application Rules](docs/i18n/ru/identity/ApplicationRules.md) - выбор приложений, к которым применяются функции.
- [Build Identity](docs/i18n/ru/identity/BuildIdentity.md), [Telephony Identity](docs/i18n/ru/identity/TelephonyIdentity.md) и [Patch Levels](docs/i18n/ru/identity/PatchLevels.md) - дополнительные настройки идентичности.
- [RKP Protection](docs/i18n/ru/security/RkpProtection.md) и [DRM Privacy](docs/i18n/ru/system/DrmPassthrough.md) - совместимость платформы и приватность.
- [Backup and Restore](docs/i18n/ru/system/BackupRestore.md) - зашифрованное резервное копирование и восстановление конфигурации.
- [Security Model](docs/i18n/ru/security/SecurityModel.md) и [Installer](docs/i18n/ru/system/Installer.md) - границы доверия и детали установки.

## Нужна помощь?

Используйте страницу **Logs** в WebUI или **Action** модуля, чтобы создать аварийный диагностический отчёт. Перед отправкой проверьте архив: он может содержать сведения об устройстве и системе.

Типовые проблемы и шаги диагностики описаны в [Diagnostics](docs/i18n/ru/system/Diagnostics.md).

## Проект

[История изменений](CHANGELOG.md) · [Участие](docs/i18n/ru/CONTRIBUTING.md) · [Языки](docs/i18n/ru/LANGUAGES.md) · [Лицензия](LICENSING.md) · [Пожертвовать](docs/i18n/ru/DONATE.md) · [Telegram](https://t.me/cleverestech)
