# Protokollierung und Diagnose (Logging and Diagnostics)

**Sprache:** [English](../../../LOG.md) | [Türkçe](../tr/LOG.md) | [简体中文](../zh-CN/LOG.md) | [Español](../es/LOG.md) | **Deutsch** | [Русский](../ru/LOG.md) | [Bahasa Indonesia](../id/LOG.md) | [हिन्दी](../hi/LOG.md) | [العربية](../ar/LOG.md)

CleveresTricky bietet mehrere integrierte Mechanismen zur Erfassung von Diagnoseschnappschüssen, Laufzeitprotokollen und Notfall-Fehlerberichten. Beim Erstellen eines GitHub-Issues oder beim Melden unerwarteten Verhaltens ist die Bereitstellung von Diagnose- und Protokolldaten unerlässlich, um die Ursache zu ermitteln.

---

## Methode 1: WebUI-Verwaltungsoberfläche (Empfohlen)

Die WebUI bietet den schnellsten und unkompliziertesten Weg, Diagnosen ohne ADB oder Computer direkt auf dem Gerät zu erfassen.

### A. Support-Diagnoseschnappschuss (Support Diagnostics Snapshot)
1. Öffnen Sie die CleveresTricky WebUI im Browser oder im Root-Manager.
2. Wechseln Sie zum Reiter **Info & Ressourcen (Info & Resources)**.
3. Scrollen Sie zum Abschnitt **Support-Diagnose (Support Diagnostics)**.
4. Klicken Sie auf **Diagnose kopieren (Copy Diagnostics)**.
5. Fügen Sie den kopierten Text direkt in das GitHub-Issue unter **Support Diagnostics Snapshot** ein.

> [!NOTE]
> Der Diagnoseschnappschuss ist datenschutztechnisch strikt begrenzt. Er enthält nur den Modulstatus, die Laufzeitversion, die Root-Umgebung, die Speicher-/CPU-Auslastung und die Funktionsschalter. Private Schlüssel, Anmeldedaten, Keybox-Zertifikate, Paketnamen oder Identitätswerte werden niemals erfasst.

### B. Live-Protokolle & Debug-Protokollierung (Debug Logging)
1. Navigieren Sie in der WebUI zum Reiter **Protokolle (Logs)**.
2. Schalten Sie den Schalter **Debug Logging** auf **EIN** (Schalter wird grün). Dies aktiviert detaillierte Ablaufverfolgungen, ohne dass eine Debug-Version installiert werden muss.
3. Reproduzieren Sie das Problem oder die Play-Integrity-Prüfung auf Ihrem Gerät.
4. Kehren Sie zum Reiter **Logs** zurück und klicken Sie auf **Kopieren (Copy)**.
5. Fügen Sie die Protokolle in Ihren Fehlerbericht ein.
6. Schalten Sie **Debug Logging** anschließend wieder aus, um Systemressourcen zu schonen.

---

## Methode 2: Notfall-Fehlerbericht per Knopfdruck (`action.sh`)

CleveresTricky enthält ein automatisiertes Skript, das Systemprotokolle, Modulstatus und Root-Umgebungsdaten in ein komprimiertes Archiv (`.tar.gz`) bündelt.

### Über den Root-Manager:
- Tippen Sie in **KernelSU** oder **APatch** auf die Schaltfläche **Aktion (Action)** neben der CleveresTricky-Modulkarte.

### Über das Terminal (Termux / Root-Shell):
```bash
su -c /data/adb/modules/cleverestricky/action.sh
```

### Speicherort des Berichts:
- Das erstellte Archiv wird gespeichert unter:
  ```
  /data/adb/cleverestricky/bugreports/CleveresTricky_Bugreport_<zeitstempel>.tar.gz
  ```
  (und wird, falls zugänglich, automatisch in den `Download/`-Ordner kopiert).
- Sie können dieses Archiv direkt als Dateianhang an Ihr GitHub-Issue anhängen.

> [!TIP]
> Keybox-XML/CBOX-Dateien und vertrauliche Schlüssel werden zum Schutz Ihrer Daten bewusst nicht in dieses Archiv aufgenommen.

---

## Methode 3: Laufzeitprotokoll des nativen Daemons (`native_runtime.log`)

Der native Hintergrunddienst protokolliert Initialisierung, Hooks und Betriebsstatus fortlaufend in:
```
/data/adb/cleverestricky/native_runtime.log
```

Neueste Protokolle im Terminal anzeigen:
```bash
su -c "tail -n 100 /data/adb/cleverestricky/native_runtime.log"
```

---

## Methode 4: Android Logcat (ADB oder Terminal)

Wenn die WebUI nicht startet oder der Dienst blockiert ist, liefert Android logcat direkte Einblicke in den Startvorgang und Binder-Transaktionen.

### Live-Erfassung über ADB:
```bash
adb logcat -s cleverestricky CleveresTricky
```

### In Termux oder lokaler Root-Shell:
```bash
su -c "logcat -d -s cleverestricky CleveresTricky"
```

### Sauberer Neustart-Mitschnitt (Keystore-Neustart):
```bash
adb logcat -c
adb shell su -c 'setprop ctl.restart keystore2'
adb logcat -d -s cleverestricky CleveresTricky
```

### Wichtige Startmarkierungen:
- `Welcome to Service!`
- `Web server on port ...`
- `libbinder ioctl hook installed successfully`
- `Keystore Binder interceptor registered`
- `TEE SecurityLevel interceptor registered`

### KeyMint / TEE Hardwarefehler-Markierung:
Wenn Sie Folgendes sehen:
```text
[WARN] Platform KeyMint HAL unreachable or TEE broken (SECURE_HW_COMMUNICATION_FAILED).
[INFO] CleveresTricky requires a functional hardware KeyMint; aborting injection to prevent framework deadlock.
[INFO] Please consult documentation (docs/security/Attestation.md or docs/LOG.md) for TEE recovery guidance.
```
Dies zeigt an, dass die Hardware-TEE- oder herstellerspezifische KeyMint-HAL-Kommunikation fehlschlägt (Fehlercode -49 / 10). CleveresTricky bricht die Interzeption absichtlich ab, um Deadlocks im Framework zu verhindern. Weitere Informationen zu geräteseitigen TEE-Wiederherstellungsoptionen finden Sie unter [Attestation.md](security/Attestation.md#hardware-tee-bereitstellung-und-oneplus-wiederherstellungshinweis).

> [!WARNING]
> Überprüfen Sie Protokolle vor der Veröffentlichung. Passwörter und WebUI-Tokens werden nie erfasst, Gerätenamen, Prozess-IDs und Paketnamen können jedoch enthalten sein.

---

## Erforderliche Angaben für Issues

Bitte fügen Sie beim Melden eines Fehlers auf GitHub folgende Informationen bei:
1. **Support-Diagnoseschnappschuss** (aus dem WebUI-Reiter Info)
2. **Protokolle** (aus WebUI Logs mit aktivem Debug Logging, `action.sh`-Archiv oder logcat)
3. Gerätemodell, Android-Version und Root-Methode (KernelSU / APatch / Magisk)
