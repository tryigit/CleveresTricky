# CleveresTricky Dokumentation

**Sprache:** [English](../../README.md) | [Türkçe](tr.md) | [简体中文](zh-CN.md) | [Español](es.md) | **Deutsch** | [Русский](ru.md) | [Bahasa Indonesia](id.md) | [हिन्दी](hi.md) | [العربية](ar.md)

[Deutsche README](../../README.de.md)

> Diese Referenz lokalisiert die benutzerorientierte Markdown-Dokumentation. Bei technischen Abweichungen gelten die englische Dokumentation und der Quellcode.

<a id="application-rules"></a>
## Application Rules

Application Rules weist einer geeigneten App ein Gerätetemplate, eine verifizierte lokale Keybox oder eine Datenschutzrichtlinie zu. Eine gültige Regel ist bereits ein explizites Target. `inherit` behält die globale Policy, `isolate` erzeugt stabile app-spezifische IMEI/IMSI/ICCID/MEID/Telefon/Serial/Attestation-Identifier und ein DRM-`deviceUniqueId`-Pseudonym, `redact` leert unterstützte Werte unter Erhalt von Android-Berechtigungsfehlern.

Attestation-Identity benötigt eine aktive verifizierte Keybox. DRM Identifier Isolation ist unabhängig von DRM Keystore Passthrough. Shared-UID-Pakete werden deterministisch über Package Manager aufgelöst; einem Paketnamen aus der Anfrage wird nicht vertraut. Regeln werden als begrenzter, atomar ersetzter Zustand mit Cache-Invalidierung gehalten.

<a id="application-scope"></a>
## Application Scope

Bestimmt, welche Android-App-UIDs Zertifikat- oder Identitätskompatibilität erhalten. Targeted Mode nutzt exakte Pakete oder begrenzte Wildcards aus `target.txt` und löst sie über Package Manager zum realen Caller auf. Shared UIDs teilen Binder-Identität.

Global Mode benötigt keinen Target-Eintrag, schließt Systemidentitäten und geschützte Infrastruktur aber weiter aus. Unbekannte Paketauflösung schlägt geschlossen fehl. Regeln und kurzer Decision-Cache werden gemeinsam ersetzt.

<a id="attestation"></a>
## Attestation

Die Attestation-Schicht bietet ausgewählten Apps kontrollierte Zertifikatsketten-Kompatibilität, während echte Android-Schlüsselerzeugung und spätere kryptografische Operationen erhalten bleiben.

RKP-Infrastruktur-Caller bleiben immer auf Androids echtem Provisioning-Pfad. Für ausgewählte App-UIDs verwenden erfolgreiche `generateKey`-Antworten und spätere `getKeyEntry`-Zertifikatlesungen denselben Kompatibilitätspfad, damit ein Alias nicht unterschiedliche Attestation-Leaf-Zertifikate zeigt.

Private-Key-Operationen werden weiterhin von Android KeyMint oder StrongBox ausgeführt. Vor Aktivierung werden Schlüssel/Zertifikat-Zuordnung, Algorithmus, Chain, Gültigkeit, Mehrdeutigkeit und Revocation geprüft. Zertifikatsersetzung erzeugt keinen Hardware-Root-of-Trust, sperrt keinen Bootloader physisch und garantiert kein Remote-Verdict.

<a id="automatic-keybox-check"></a>
## Automatic Keybox Check

Hält Keybox- und Revocation-Zustand aktuell, ohne Storage dauerhaft zu scannen. File Observer übernimmt normale Änderungen; ein niedriger Fallback-Takt deckt ungeeignete Dateisysteme ab.

Jeder Refresh validiert Key, Chain, Algorithmus, Datum, Ambiguität und Revocation neu. Wenn Revocation nicht feststellbar ist, wird neues Material nicht aktiviert. Caches sind nach Dateianzahl und Größe begrenzt.

<a id="backup-restore"></a>
## Backup and Restore

Verschiebt Konfiguration und autorisiertes Schlüsselmaterial in einem authentifiziert verschlüsselten Archiv. Export verlangt mindestens 12 Zeichen Passwort und verwendet eine Allowlist; Symlinks, unbekannte Pfade und Größenüberschreitungen werden verworfen.

Import akzeptiert nur CTSB und begrenzt Upload, Entries, Keyboxes sowie Einzel- und Gesamtgröße. Traversal, Duplikate, Verzeichnisse, Symlink-Ziele, fehlerhafte Settings und Keyboxes werden vor dem Schreiben abgewiesen. Policy v2 wird als vollständiger Snapshot validiert.

<a id="boot-properties"></a>
## Boot Properties

Core Userspace-Property-Ansicht zur Reduktion typischer unlocked/debug/warranty/verified-boot/recovery-Indikatoren. Der feste Satz wird vor Zygote angewendet und bleibt unabhängig von optionaler Identität aktiv.

`boot_props_mode` steuert nur optionale Build-Identity-Kompatibilität (`auto`, `force`, `disable`) und deaktiviert nicht den Core-Schutz. Es sperrt keinen Bootloader physisch und repariert Verified Boot nicht.

<a id="build-identity"></a>
## Build Identity

Wendet ein vollständiges Gerätetemplate auf Fingerprint und unterstützte app-sichtbare Build-Felder an. Es ist optional, benötigt Spoof Engine und Reboot. Arbiträre Android-Properties werden abgelehnt.

Auto Identity kann Pixel-beta/canary-Metadaten von Google auflösen und lokal speichern, schaltet den Engine aber nicht automatisch ein. Build Identity, Security Patch, Region, Telephony und Attestation Identity sind voneinander getrennt.

<a id="building"></a>
## Building

Benötigt Java 21, SDK API 36, NDK 27.3.13750724, CMake 3.22.1, stable Rust, ARM64/x86-64 Android targets, Cargo NDK und Submodules. Kotlin/Android-Checks, Rust fmt/clippy/tests und Unit Tests müssen erfolgreich sein.

CI prüft Shell, SELinux, Template, Kotlin/Java/Rust, beide Architekturen, Release/Debug ZIP und Encryptor. First-party C ist verboten; `binder_interceptor.cpp` ist die einzige erlaubte first-party C++ ABI-Grenze. Release: `./gradlew zipRelease`.

<a id="certificate-safe-mode"></a>
## Certificate Safe Mode

Legacy-Konzept. Die aktuelle WebUI bietet keinen Schalter zum Abschalten der Core-Keystore/TEE-Kompatibilität. Scope kommt aus Global Mode und Application Rules; Spoof Engine steuert nur Identitätswerte.

`tee_broken_mode` kann für Migration gelesen werden, ist aber nicht mehr Teil des Core-Targeting. Diagnose erfolgt durch engeren Scope, geeigneten Passthrough oder kontrolliertes Entfernen von Key Material.

<a id="diagnostics"></a>
## Diagnostics

Zuerst Dashboard für Version, Engine, Profile, Keybox Count, Target Size, RKP, DRM und native Features prüfen und den ersten Fehler in Logs suchen. Wenn WebUI nicht startet: logcat, daemon, `webroot`, architecture-specific `webui_bridge` und Module-Manager-Status prüfen.

Zur Isolation Minimal + Reboot, genuine Verhalten bestätigen und Funktionen Schritt für Schritt aktivieren. Effective State zeigt Rule/Profile, Scope, Template, Keybox Ref, Privacy, Features, Patches, RKP/DRM, KeyMint/StrongBox, Provider Coexistence und Reboot Requirement ohne private Keys.

<a id="drm-passthrough"></a>
## DRM Keystore Passthrough and Identifier Privacy

Keystore Passthrough hält ausgewählte Medien-Apps auf Androids echtem Zertifikatspfad. Identifier Privacy ersetzt nur das unterstützte stable-AIDL-`deviceUniqueId` für `privacy=isolate` durch ein stabiles app-spezifisches Pseudonym, das nicht aus dem echten DRM-ID abgeleitet wird.

`drm_packages.txt` unterstützt exakte Pakete und begrenzte Wildcards. Der Privacy Hook ist auf `IDrmFactory` / `IDrmPlugin.getPropertyByteArray("deviceUniqueId")` begrenzt und ändert keine HIDL-Pfade, Security Level, Lizenzen, Provisioning, Keys, Sessions, HDCP oder String Properties. Bei unerwartetem ABI bleibt die Originalantwort erhalten.

<a id="encrypted-storage"></a>
## Encrypted Storage

CBOX verwendet authentifiziertes AES-256-GCM für Keybox-Speicherung/Transfer und bindet Metadata an Ciphertext. Passwortcontainer nutzen begrenzte Key Derivation, lokale Cache-Schlüssel liegen im privaten Konfigurationsbereich.

Unlock wird nur über native WebUI akzeptiert und ersetzt nicht die Keybox-Validierung. Key/Certificate/Chain/Date/Algorithm/Revocation werden erneut geprüft. Ein feindlicher Root-Prozess kann entsperrte Daten weiterhin lesen.

<a id="identity-refresh"></a>
## Identity Refresh

Bereitet eine validierte Identität für den nächsten Boot vor, ohne den aktuellen Snapshot zu ändern. Early Boot validiert Stage-Datei und promoted sie atomar, sodass Build Properties und Service denselben Zustand nutzen.

IMEI/ICCID-Checksums und Längen sind begrenzt. Manuelle Änderungen entfernen alte Stage-Daten; deaktivierter Engine/Refresh verhindert unerwünschte Promotion.

<a id="installer"></a>
## Installer

Installiert den vollständigen KernelSU/APatch-Payload für Android 12-17 auf ARM64/x86 64. Magisk und Recovery werden vor einer Teilinstallation abgewiesen.

Jeder Payload hat SHA 256; Runtime lehnt Symlinks, nicht reguläre und unerwartete Dateien ab. Interne Hashes beweisen nicht den Ersteller, daher veröffentlichen offizielle Releases zusätzlich `SHA256SUMS` und GitHub signed build provenance.

<a id="keybox-manager"></a>
## Keybox Manager

Lädt, verifiziert, wählt und überwacht autorisiertes Attestation-Key-Material in Legacy-, XML- und CBOX-Form. App-Regeln können spezifische Dateien referenzieren; Remote-Daten bleiben bis zur lokalen Prüfung untrusted.

Private Key muss Leaf Certificate entsprechen; Algorithmus, Chain, Datum, Duplikate/Ambiguität und Revocation werden geprüft. Unklare Revocation aktiviert kein neues Material und ein fehlerhafter Pool wird komplett verworfen.

<a id="native-architecture"></a>
## Native Architecture

Portable native Logik ist Rust. First-party C existiert nicht; `binder_interceptor.cpp` ist wegen des privaten libbinder Object ABI die einzige C++-Ausnahme. Rust Core validiert Binder Layout/Streams, FDs und kernel-validierte Copies.

Der Rust Injector verwaltet Files, SELinux Socket, FD Transfer, Maps/Symbols, ptrace, Register, Remote Memory, Loader und Cleanup. Temporäre Stack-Änderungen werden aus einem begrenzten Journal wiederhergestellt. Die C++-Ausnahme darf nicht wachsen.

<a id="patch-levels"></a>
## Patch Levels

`security_patch.txt` bietet System/Vendor/Boot-Regeln global und per App. Unterstützt werden Kalenderwerte, `today`, `device_default`, `prop`, `no`; Policy v2 hat Device, Property, Manual, Automatic, Omit getrennt je Komponente.

Parsing ist streng begrenzt und invalides Input ersetzt den Zustand nicht teilweise. Automatic nutzt Kalenderarithmetik. Die Funktion installiert keine realen Sicherheitsupdates und garantiert keinen Remote-Verdict.

<a id="performance"></a>
## Performance and Memory

Core Keystore Interception bleibt aktiv; bei deaktiviertem Spoof Engine werden optionale Identity/DRM/Build/Region/Telephony-Arbeiten geparkt. Automatic Keybox Check hat einen eigenen Schalter.

Binder Parser nutzt fixe Arrays und einen 64-Slot Descriptor Cache. Controller und Caches sind begrenzt und vermeiden Busy Polling. Rust Release nutzt LTO, Größenoptimierung und gehärtetes Linking.

<a id="profiles"></a>
## Profiles

Profiles wenden optionale Einstellungen in einer validierten Transaktion an; Core-Boot-, Keystore- und RKP-Infrastrukturschutz bleiben unabhängig aktiv.

Daily Compatibility nutzt gezielten Scope und Keybox-Monitoring; Default ist konservativ; Maximum Compatibility aktiviert Global Mode, Build Identity, Identity Refresh und Telephony und deaktiviert DRM Passthrough; Minimal deaktiviert optionale Identity- und geplante Keybox-Arbeit. Keines dieser Presets ändert den RKP-Infrastrukturschutz.

Alte Konfigurationen können den stillgelegten Marker `rkp_passthrough` enthalten, aber Generated-Key-Verhalten hängt nicht mehr davon ab. Version-two-Profile können App-Zuordnung, Template, validierte Keybox, Privacy, Patch und optionale Identity/DRM-Wahlen speichern; das alte RKP-Feld bleibt nur für Migration kompatibel und ist keine Live-WebUI-Option.

<a id="provider-coexistence"></a>
## Provider Coexistence

Automatic Build Identity erkennt andere Fingerprint/Property-Provider wie PIF, `autopif`/`auto_pif` und PlayCurl und überschreibt sie nicht.

Bei Konflikt bleiben optionale Build Properties untouched, andere CleveresTricky-Funktionen können weiterarbeiten. Force umgeht bewusst die Erkennung; Automatic ist empfohlen.

<a id="region-properties"></a>
## Region Properties

Stellt eine kleine feste China-Region-Ansicht über Hardware/SIM/Operator Country, Hardware Level und Radio Marker bereit. Arbiträre Properties werden nicht akzeptiert.

Mit Spoof Engine erfolgt Anwendung vor Zygote. Reale SIM-Region, Radio Registration, Modem Firmware, sichere Verkaufsregion und Carrier Account ändern sich nicht.

<a id="remote-sources"></a>
## Remote Sources

Ruft autorisiertes Keybox-Material nur von explizitem HTTPS ab. Host/Port/Path/Timeout/Refresh/Auth/Header/Response Size sind begrenzt und Secrets fehlen in Statusantworten.

Signaturen können verlangt werden. Vor Signature-, XML/CBOX-, Size-, Keybox-, Certificate- und Revocation-Prüfung wird nichts aktiviert. Fehlgeschlagener Refresh ersetzt verifiziertes Material nicht.

<a id="rkp-protection"></a>
## RKP Protection

Remote-Key-Provisioning-Schutz hält Androids Provisioning-Infrastruktur auf dem echten Plattformpfad. Android/Google-RKP- und alte Remote-Provisioner-Pakete liegen immer außerhalb der Zertifikatsersetzung; System-UIDs und unbekannte Paketauflösung verhalten sich fail closed.

RKP-Infrastruktur-Caller werden nie verändert. Für Ziel-App-UIDs verwenden `generateKey` und spätere `getKeyEntry`-Zertifikatantworten einen einheitlichen Kompatibilitätspfad, damit ein Alias nicht zwei verschiedene Attestation-Leafs zeigt.

Der alte Schalter `rkp_passthrough` ist stillgelegt. Der Marker darf in alten Konfigurationen oder Backups verbleiben, steuert aber Generated-Key-Verhalten nicht mehr und wird nicht als WebUI-Runtime-Toggle angeboten. Eingebaute Profiles ändern RKP nicht; der Infrastrukturschutz ist immer aktiv.

CleveresTricky simuliert keinen RKP-Server, erzeugt keine Provisioning-Credentials und ändert keinen Hardware-Provisioning-Root.

<a id="security-model"></a>
## Security Model

Root Service, OS, KernelSU/APatch, installierte Moduldateien und autorisiertes Key Material sind trusted. Apps, Binder Input, Uploads, Remote Responses, Config, Archive, Regeln, Templates, Paths und Network Metadata sind untrusted.

Config muss root-owned sein, sensitive Dateien root-only, Symlinks werden abgewiesen, Writes sind atomar. Binder ABI und kernel-validierte Copies werden geprüft. Injector beschränkt Symbol/Prozess/Library und WebUI öffnet keinen TCP-Port. Ein feindlicher Root-Prozess liegt außerhalb vollständiger Abwehr.

<a id="spoof-engine"></a>
## Spoof Engine

Optionaler app-facing Identity Controller. Core Keystore/TEE, Certificate Compatibility, Root of Trust und Boot Protection bleiben auch ausgeschaltet aktiv.

Aktiviert optionale Attestation/Telephony/Build/Region/Refresh-Pfade je nach separaten Controls. Ausschalten löscht gespeicherte Werte nicht. App-Caches können Restart und Build Identity einen Reboot benötigen.

<a id="telephony-identity"></a>
## Telephony Identity

Kann IMEI, MEID, IMSI, ICCID und Telefonnummer über unterstützte Binder APIs für zwei SIM-Slots präsentieren. Checksums, Längen, Syntax, Slot und Input Size werden validiert.

Zuerst wird die echte Android-Antwort abgefragt; Permission Denial, Error oder Null bleiben erhalten. Modem, Baseband, EFS, SIM und Carrier-Identität ändern sich nicht.

<a id="web-interface"></a>
## Web Interface

Feste Runtime-Ownership: `index.html` Markup/Base CSS, `bridge.js` native Bridge/Intents, `policy.js` Policy/State und eigene UI, `ux.js` Presentation/Localization/Guide/Community. Keine standalone Runtime-CSS oder feature-spezifischen JS-Bundles.

Mobile Bottom Navigation, Touch Controls, Responsive Panels, Password Visibility, Progress und Accessibility sind integriert. Kein TCP Listener: Native Module-Manager API, begrenzte Rust Bridge, root-only Queues und strikte Input/Path/Method/Size/Time-Validierung.

<a id="changelog"></a>
## CHANGELOG

V2.5.3 brachte granulare Identity/Security-Patch-Controls, Profiles und Effective State; Härtung von Attestation, KeyMint/StrongBox, DRM Privacy, Upgrade und Android 17; konsolidierte WebUI-Ownership und Übersetzungen; KeyboxHub mit externem Browser; sowie bessere Diagnostics, Cache/Timing, Dependency Security, Regression und Artifact Validation.

<a id="contributing"></a>
## Contributing

Änderungen müssen Fail-closed-Modell, Android 12-17 und KernelSU/APatch erhalten und keine nicht verifizierbare Hardware-Integrity behaupten. Kotlin/Android/Rust Checks sind erforderlich; portable native additions gehören in Rust, first-party C ist verboten und `binder_interceptor.cpp` die einzige C++-Ausnahme.

Binder/XML/ZIP/CBOX/HTTP/Paths/PIDs sind untrusted und brauchen Bounds und Failure Tests. Keine Private Keys, Keyboxes, Tokens, Secrets, generierten APKs/ZIPs committen. User-visible Änderungen benötigen Doku-Updates.

<a id="donate"></a>
## Development Support

Unterstützung ist über die im kanonischen `DONATE.md` aufgeführten USDT TRC20, XMR, USDT/USDC ERC20/BEP20, Binance User ID, PayPal, BuyMeACoffee und die Website möglich. Vor einer Zahlung aktuelle Adressen im englischen Original prüfen.

<a id="languages"></a>
## Language Support

WebUI enthält English, Türkçe, 简体中文, Español, Deutsch, Русский, Bahasa Indonesia, हिन्दी und العربية. Runtime-Kataloge bleiben ausschließlich in `ux.js`; keine locale-spezifischen JS/CSS-Assets. Benutzer-Dokumentation bietet README und zentrale Referenz in denselben neun Sprachen.

Bei Änderungen an user-facing Markdown müssen englisches Canonical und relevante Übersetzungsabschnitte gemeinsam aktualisiert werden.

<a id="logging"></a>
## Logging and Diagnostics

Diagnosen gehen in Android logcat, nicht in eine separate Plaintext-Logdatei. Hauptkommando: `adb logcat -s cleverestricky CleveresTricky`. Service-, Bridge-, Binder- und TEE-Startup-Marker sind hilfreich.

`TAMPER DETECTED`, Binder-ABI-Fehler, abgewiesene Keybox oder Injector Timeout erfordern Analyse. Logs vor Veröffentlichung auf sensible Dateinamen, Packages, Properties und PIDs prüfen.

<a id="theme"></a>
## UI Theme

Minimalistisches monochromes Nothing-OS/iOS-Hybrid: dunkler Charcoal-Hintergrund, hellgrauer Text, silberner Accent, dunkle Panels, grüner Success, roter Danger. System Sans, technische Daten Monospace, Dynamic Island, runde Buttons, iOS-Toggles und Mobile-first Layout.

Touch-Ziele mindestens etwa 44px, vertikaler Flow bevorzugt und Optimierung für Bedienung im KernelSU/APatch-Modulmanager.
