# Profiles

**Sprache:** [English](../../../identity/Profiles.md) | [Türkçe](../../tr/identity/Profiles.md) | [简体中文](../../zh-CN/identity/Profiles.md) | [Español](../../es/identity/Profiles.md) | **Deutsch** | [Русский](../../ru/identity/Profiles.md) | [Bahasa Indonesia](../../id/identity/Profiles.md) | [हिन्दी](../../hi/identity/Profiles.md) | [العربية](../../ar/identity/Profiles.md)

Profiles wenden optionale Einstellungen in einer validierten Transaktion an; Core-Boot-, Keystore- und RKP-Infrastrukturschutz bleiben unabhängig aktiv.

Daily Compatibility nutzt gezielten Scope und Keybox-Monitoring; Default ist konservativ (das Anwenden des Default-Profils oder Zurücksetzen über das WebUI stellt `target.txt`, `identity_target.txt`, `drm_packages.txt`, `boot_props_mode` und `security_patch.txt` auf die sauberen Paket-Standardwerte zurück und entfernt jeden konfigurierten Remote-Server samt zwischengespeichertem Keybox-Inhalt); Maximum Compatibility aktiviert Global Mode, Build Identity, Identity Refresh und Telephony und deaktiviert DRM Passthrough; Minimal deaktiviert optionale Identity- und geplante Keybox-Arbeit. Keines dieser Presets ändert den RKP-Infrastrukturschutz.

Alte Konfigurationen können den stillgelegten Marker `rkp_passthrough` enthalten, aber Generated-Key-Verhalten hängt nicht mehr davon ab. Version-two-Profile können App-Zuordnung, Template, validierte Keybox, Privacy, Patch und optionale Identity/DRM-Wahlen speichern; das alte RKP-Feld bleibt nur für Migration kompatibel und ist keine Live-WebUI-Option.
