#!/system/bin/sh
set -e

MODULE_ID="cleverestricky"
MODDIR="/data/adb/modules/$MODULE_ID"
CONFIG_DIR="/data/adb/$MODULE_ID"
SHELL_DIR="/data/user_de/0/com.android.shell"
WEBUI_BRIDGE="$MODDIR/webui_bridge"
FROM_WEBUI="${FROM_WEBUI:-0}"
LANG_CODE="en"
RAW_LOCALE=""
workspace=""
tmp=""

# Android shells use either 512-byte or 1 KiB file-size blocks. This keeps the
# staged archive at or below the native publisher's 256 MiB streaming bound.
REPORT_FILE_BLOCK_LIMIT=262144

# Bound the uncompressed collection before tar sees it. Directory snapshots keep
# at most 128 regular files. The native collector pins each source directory and
# reads at most 1 MiB from a single O_NOFOLLOW descriptor. Generated command logs
# are independently capped to at most 8 MiB on Android shells that use 1 KiB
# ulimit blocks (and 4 MiB on 512-byte-block shells).
REPORT_COPY_FILE_LIMIT=128
REPORT_LOG_FILE_BLOCK_LIMIT=8192
report_copy_count=0

umask 077

cleanup() {
    if [ -n "$workspace" ] && [ -d "$workspace" ] && [ ! -L "$workspace" ]; then
        rm -rf "$workspace" 2>/dev/null || true
    fi
}
trap cleanup EXIT
trap 'exit 1' INT TERM

detect_language() {
    RAW_LOCALE=""

    for locale_candidate in \
        "$(getprop persist.sys.locale 2>/dev/null || true)" \
        "$(settings get system system_locales 2>/dev/null | cut -d, -f1 || true)" \
        "$(getprop ro.product.locale 2>/dev/null || true)" \
        "$(getprop persist.sys.language 2>/dev/null || true)"; do
        case "$locale_candidate" in
            ""|null|NULL) ;;
            *)
                RAW_LOCALE="$locale_candidate"
                break
                ;;
        esac
    done

    normalized_locale=$(printf '%s' "$RAW_LOCALE" | tr '_' '-' | tr '[:upper:]' '[:lower:]' 2>/dev/null || true)
    case "$normalized_locale" in
        tr|tr-*) LANG_CODE="tr" ;;
        zh|zh-*) LANG_CODE="zh" ;;
        es|es-*) LANG_CODE="es" ;;
        de|de-*) LANG_CODE="de" ;;
        ru|ru-*) LANG_CODE="ru" ;;
        id|id-*|in|in-*) LANG_CODE="id" ;;
        hi|hi-*) LANG_CODE="hi" ;;
        ar|ar-*) LANG_CODE="ar" ;;
        *) LANG_CODE="en" ;;
    esac
}

message() {
    case "$LANG_CODE:$1" in
        tr:GENERATING) printf '%s\n' "Acil durum raporu oluşturuluyor ..." ;;
        tr:BASIC) printf '%s\n' "Temel bilgiler toplanıyor ..." ;;
        tr:ADDING) printf '%s\n' "Ekleniyor:" ;;
        tr:LOGS) printf '%s\n' "Sistem günlükleri toplanıyor ..." ;;
        tr:ROOT_LOGS) printf '%s\n' "Root ortamı günlükleri toplanıyor ..." ;;
        tr:COMPRESSING) printf '%s\n' "Rapor sıkıştırılıyor ..." ;;
        tr:GENERATED) printf '%s\n' "Rapor oluşturuldu:" ;;
        tr:SHARING) printf '%s\n' "Rapor paylaşılmaya çalışılıyor ..." ;;
        tr:SHARE_FAILED) printf '%s\n' "Paylaşım kullanılamıyor; rapor yerel olarak kaydedildi." ;;
        tr:ARCHIVE_FAILED) printf '%s\n' "Rapor arşivi oluşturulamadı." ;;
        tr:REPORT_INFO) printf '%s\n' "CleveresTricky acil durum raporu. Bu arşiv, sorun gidermek için cihaz/modül durumunu ve tanılama günlüklerini içerir." ;;
        tr:REPORT_LANGUAGE) printf '%s\n' "Rapor dili:" ;;
        tr:NOTICE) printf '%s\n' "Bu arşiv hassas sistem günlükleri içerebilir. Paylaşmadan önce inceleyin. CleveresTricky keybox XML/CBOX dosyaları bilerek toplanmaz." ;;
        tr:WARNING)
            printf '%s
' "Aksiyon butonuna tıkladınız." \
                          "Bu buton çok acil durumlarda log oluşturmanızı sağlar." \
                          "Lütfen modül ayarlarına girmek istiyorsanız diğer Webui butonunu kullanın." \
                          "5 saniye sonra log oluşturulacak."
            ;;

        zh:GENERATING) printf '%s\n' "正在生成紧急诊断报告 ..." ;;
        zh:BASIC) printf '%s\n' "正在收集基本信息 ..." ;;
        zh:ADDING) printf '%s\n' "正在添加：" ;;
        zh:LOGS) printf '%s\n' "正在收集系统日志 ..." ;;
        zh:ROOT_LOGS) printf '%s\n' "正在收集 Root 环境日志 ..." ;;
        zh:COMPRESSING) printf '%s\n' "正在压缩报告 ..." ;;
        zh:GENERATED) printf '%s\n' "报告已生成：" ;;
        zh:SHARING) printf '%s\n' "正在尝试分享报告 ..." ;;
        zh:SHARE_FAILED) printf '%s\n' "无法使用分享功能；报告已保存在本地。" ;;
        zh:ARCHIVE_FAILED) printf '%s\n' "无法创建报告压缩包。" ;;
        zh:REPORT_INFO) printf '%s\n' "CleveresTricky 紧急诊断报告。此压缩包包含用于故障排查的设备/模块状态和诊断日志。" ;;
        zh:REPORT_LANGUAGE) printf '%s\n' "报告语言：" ;;
        zh:NOTICE) printf '%s\n' "此压缩包可能包含敏感的系统日志。分享前请先检查。CleveresTricky keybox XML/CBOX 文件不会被主动收集。" ;;
        zh:WARNING)
            printf '%s
' "您点击了操作按钮。" \
                          "此按钮用于在非常紧急的情况下生成日志。" \
                          "如果您想进入模块设置，请使用另一个 WebUI 按钮。" \
                          "5 秒后将生成日志。"
            ;;

        es:GENERATING) printf '%s\n' "Generando informe de emergencia ..." ;;
        es:BASIC) printf '%s\n' "Recopilando información básica ..." ;;
        es:ADDING) printf '%s\n' "Añadiendo:" ;;
        es:LOGS) printf '%s\n' "Recopilando registros del sistema ..." ;;
        es:ROOT_LOGS) printf '%s\n' "Recopilando registros del entorno root ..." ;;
        es:COMPRESSING) printf '%s\n' "Comprimiendo el informe ..." ;;
        es:GENERATED) printf '%s\n' "Informe generado en:" ;;
        es:SHARING) printf '%s\n' "Intentando compartir el informe ..." ;;
        es:SHARE_FAILED) printf '%s\n' "No se pudo compartir; el informe se guardó localmente." ;;
        es:ARCHIVE_FAILED) printf '%s\n' "No se pudo crear el archivo del informe." ;;
        es:REPORT_INFO) printf '%s\n' "Informe de emergencia de CleveresTricky. Este archivo contiene el estado del dispositivo/módulo y registros de diagnóstico para solucionar problemas." ;;
        es:REPORT_LANGUAGE) printf '%s\n' "Idioma del informe:" ;;
        es:NOTICE) printf '%s\n' "Este archivo puede contener registros sensibles del sistema. Revísalo antes de compartirlo. Los archivos keybox XML/CBOX de CleveresTricky se excluyen deliberadamente." ;;
        es:WARNING)
            printf '%s
' "Hizo clic en el botón de acción." \
                          "Este botón le permite generar un registro en situaciones muy urgentes." \
                          "Utilice el otro botón de WebUI si desea ingresar a la configuración del módulo." \
                          "Se generará un registro en 5 segundos."
            ;;

        de:GENERATING) printf '%s\n' "Notfallbericht wird erstellt ..." ;;
        de:BASIC) printf '%s\n' "Grundlegende Informationen werden gesammelt ..." ;;
        de:ADDING) printf '%s\n' "Wird hinzugefügt:" ;;
        de:LOGS) printf '%s\n' "Systemprotokolle werden gesammelt ..." ;;
        de:ROOT_LOGS) printf '%s\n' "Root-Umgebungsprotokolle werden gesammelt ..." ;;
        de:COMPRESSING) printf '%s\n' "Bericht wird komprimiert ..." ;;
        de:GENERATED) printf '%s\n' "Bericht erstellt unter:" ;;
        de:SHARING) printf '%s\n' "Bericht wird zum Teilen geöffnet ..." ;;
        de:SHARE_FAILED) printf '%s\n' "Teilen ist nicht verfügbar; der Bericht wurde lokal gespeichert." ;;
        de:ARCHIVE_FAILED) printf '%s\n' "Das Berichtsarchiv konnte nicht erstellt werden." ;;
        de:REPORT_INFO) printf '%s\n' "CleveresTricky-Notfallbericht. Dieses Archiv enthält Geräte-/Modulstatus und Diagnoseprotokolle zur Fehleranalyse." ;;
        de:REPORT_LANGUAGE) printf '%s\n' "Berichtssprache:" ;;
        de:NOTICE) printf '%s\n' "Dieses Archiv kann sensible Systemprotokolle enthalten. Vor dem Teilen prüfen. CleveresTricky-Keybox-Dateien im XML/CBOX-Format werden bewusst nicht gesammelt." ;;
        de:WARNING)
            printf '%s
' "Sie haben die Aktionsschaltfläche geklickt." \
                          "Diese Schaltfläche ermöglicht es Ihnen, in sehr dringenden Fällen ein Protokoll zu erstellen." \
                          "Bitte verwenden Sie die andere WebUI-Schaltfläche, wenn Sie die Moduleinstellungen aufrufen möchten." \
                          "Ein Protokoll wird in 5 Sekunden erstellt."
            ;;

        ru:GENERATING) printf '%s\n' "Создаётся аварийный диагностический отчёт ..." ;;
        ru:BASIC) printf '%s\n' "Сбор основной информации ..." ;;
        ru:ADDING) printf '%s\n' "Добавляется:" ;;
        ru:LOGS) printf '%s\n' "Сбор системных журналов ..." ;;
        ru:ROOT_LOGS) printf '%s\n' "Сбор журналов root-среды ..." ;;
        ru:COMPRESSING) printf '%s\n' "Сжатие отчёта ..." ;;
        ru:GENERATED) printf '%s\n' "Отчёт создан:" ;;
        ru:SHARING) printf '%s\n' "Попытка открыть отчёт для отправки ..." ;;
        ru:SHARE_FAILED) printf '%s\n' "Отправка недоступна; отчёт сохранён локально." ;;
        ru:ARCHIVE_FAILED) printf '%s\n' "Не удалось создать архив отчёта." ;;
        ru:REPORT_INFO) printf '%s\n' "Аварийный отчёт CleveresTricky. Архив содержит состояние устройства/модуля и диагностические журналы для поиска неисправностей." ;;
        ru:REPORT_LANGUAGE) printf '%s\n' "Язык отчёта:" ;;
        ru:NOTICE) printf '%s\n' "Архив может содержать чувствительные системные журналы. Проверьте его перед отправкой. Файлы keybox CleveresTricky XML/CBOX намеренно не собираются." ;;
        ru:WARNING)
            printf '%s
' "Вы нажали кнопку действия." \
                          "Эта кнопка позволяет создать журнал в очень срочных ситуациях." \
                          "Пожалуйста, используйте другую кнопку WebUI, если вы хотите войти в настройки модуля." \
                          "Журнал будет создан через 5 секунд."
            ;;

        id:GENERATING) printf '%s\n' "Membuat laporan darurat ..." ;;
        id:BASIC) printf '%s\n' "Mengumpulkan informasi dasar ..." ;;
        id:ADDING) printf '%s\n' "Menambahkan:" ;;
        id:LOGS) printf '%s\n' "Mengumpulkan log sistem ..." ;;
        id:ROOT_LOGS) printf '%s\n' "Mengumpulkan log lingkungan root ..." ;;
        id:COMPRESSING) printf '%s\n' "Mengompresi laporan ..." ;;
        id:GENERATED) printf '%s\n' "Laporan dibuat di:" ;;
        id:SHARING) printf '%s\n' "Mencoba membagikan laporan ..." ;;
        id:SHARE_FAILED) printf '%s\n' "Berbagi tidak tersedia; laporan disimpan secara lokal." ;;
        id:ARCHIVE_FAILED) printf '%s\n' "Arsip laporan tidak dapat dibuat." ;;
        id:REPORT_INFO) printf '%s\n' "Laporan darurat CleveresTricky. Arsip ini berisi status perangkat/modul dan log diagnostik untuk pemecahan masalah." ;;
        id:REPORT_LANGUAGE) printf '%s\n' "Bahasa laporan:" ;;
        id:NOTICE) printf '%s\n' "Arsip ini dapat berisi log sistem sensitif. Tinjau sebelum membagikan. File keybox XML/CBOX CleveresTricky sengaja tidak dikumpulkan." ;;
        id:WARNING)
            printf '%s
' "Anda mengklik tombol tindakan." \
                          "Tombol ini memungkinkan Anda membuat log dalam situasi yang sangat mendesak." \
                          "Silakan gunakan tombol WebUI lainnya jika Anda ingin masuk ke pengaturan modul." \
                          "Log akan dibuat dalam 5 detik."
            ;;

        hi:GENERATING) printf '%s\n' "आपातकालीन रिपोर्ट बनाई जा रही है ..." ;;
        hi:BASIC) printf '%s\n' "मूल जानकारी एकत्र की जा रही है ..." ;;
        hi:ADDING) printf '%s\n' "जोड़ा जा रहा है:" ;;
        hi:LOGS) printf '%s\n' "सिस्टम लॉग एकत्र किए जा रहे हैं ..." ;;
        hi:ROOT_LOGS) printf '%s\n' "रूट वातावरण के लॉग एकत्र किए जा रहे हैं ..." ;;
        hi:COMPRESSING) printf '%s\n' "रिपोर्ट संपीड़ित की जा रही है ..." ;;
        hi:GENERATED) printf '%s\n' "रिपोर्ट बनाई गई:" ;;
        hi:SHARING) printf '%s\n' "रिपोर्ट साझा करने का प्रयास किया जा रहा है ..." ;;
        hi:SHARE_FAILED) printf '%s\n' "साझा करना उपलब्ध नहीं है; रिपोर्ट स्थानीय रूप से सहेजी गई है।" ;;
        hi:ARCHIVE_FAILED) printf '%s\n' "रिपोर्ट आर्काइव नहीं बनाया जा सका।" ;;
        hi:REPORT_INFO) printf '%s\n' "CleveresTricky आपातकालीन रिपोर्ट। इस आर्काइव में समस्या निवारण के लिए डिवाइस/मॉड्यूल स्थिति और डायग्नोस्टिक लॉग शामिल हैं।" ;;
        hi:REPORT_LANGUAGE) printf '%s\n' "रिपोर्ट भाषा:" ;;
        hi:NOTICE) printf '%s\n' "इस आर्काइव में संवेदनशील सिस्टम लॉग हो सकते हैं। साझा करने से पहले इसकी समीक्षा करें। CleveresTricky keybox XML/CBOX फ़ाइलें जानबूझकर एकत्र नहीं की जातीं।" ;;
        hi:WARNING)
            printf '%s
' "आपने एक्शन बटन पर क्लिक किया है।" \
                          "यह बटन आपको बहुत जरूरी स्थितियों में लॉग जनरेट करने की अनुमति देता है।" \
                          "यदि आप मॉड्यूल सेटिंग्स में प्रवेश करना चाहते हैं तो कृपया अन्य WebUI बटन का उपयोग करें।" \
                          "5 सेकंड में एक लॉग जनरेट किया जाएगा।"
            ;;

        ar:GENERATING) printf '%s\n' "جارٍ إنشاء تقرير طوارئ ..." ;;
        ar:BASIC) printf '%s\n' "جارٍ جمع المعلومات الأساسية ..." ;;
        ar:ADDING) printf '%s\n' "جارٍ إضافة:" ;;
        ar:LOGS) printf '%s\n' "جارٍ جمع سجلات النظام ..." ;;
        ar:ROOT_LOGS) printf '%s\n' "جارٍ جمع سجلات بيئة الروت ..." ;;
        ar:COMPRESSING) printf '%s\n' "جارٍ ضغط التقرير ..." ;;
        ar:GENERATED) printf '%s\n' "تم إنشاء التقرير في:" ;;
        ar:SHARING) printf '%s\n' "جارٍ محاولة مشاركة التقرير ..." ;;
        ar:SHARE_FAILED) printf '%s\n' "المشاركة غير متاحة؛ تم حفظ التقرير محليًا." ;;
        ar:ARCHIVE_FAILED) printf '%s\n' "تعذر إنشاء أرشيف التقرير." ;;
        ar:REPORT_INFO) printf '%s\n' "تقرير طوارئ CleveresTricky. يحتوي هذا الأرشيف على حالة الجهاز/الوحدة وسجلات التشخيص لاستكشاف المشكلات." ;;
        ar:REPORT_LANGUAGE) printf '%s\n' "لغة التقرير:" ;;
        ar:NOTICE) printf '%s\n' "قد يحتوي هذا الأرشيف على سجلات نظام حساسة. راجعه قبل المشاركة. لا يتم جمع ملفات CleveresTricky keybox بصيغة XML/CBOX عمدًا." ;;
        ar:WARNING)
            printf '%s
' "لقد نقرت على زر الإجراء." \
                          "يتيح لك هذا الزر إنشاء سجل في الحالات الطارئة جداً." \
                          "يرجى استخدام زر WebUI الآخر إذا كنت ترغب في الدخول إلى إعدادات الوحدة." \
                          "سيتم إنشاء السجل خلال 5 ثوانٍ."
            ;;

        en:GENERATING|*:GENERATING) printf '%s\n' "Generating emergency report ..." ;;
        en:BASIC|*:BASIC) printf '%s\n' "Collecting basic information ..." ;;
        en:ADDING|*:ADDING) printf '%s\n' "Adding:" ;;
        en:LOGS|*:LOGS) printf '%s\n' "Collecting system logs ..." ;;
        en:ROOT_LOGS|*:ROOT_LOGS) printf '%s\n' "Collecting root environment logs ..." ;;
        en:COMPRESSING|*:COMPRESSING) printf '%s\n' "Compressing report ..." ;;
        en:GENERATED|*:GENERATED) printf '%s\n' "Report generated at:" ;;
        en:SHARING|*:SHARING) printf '%s\n' "Trying to share the report ..." ;;
        en:SHARE_FAILED|*:SHARE_FAILED) printf '%s\n' "Sharing is unavailable; the report was saved locally." ;;
        en:ARCHIVE_FAILED|*:ARCHIVE_FAILED) printf '%s\n' "Could not create the report archive." ;;
        en:REPORT_INFO|*:REPORT_INFO) printf '%s\n' "CleveresTricky emergency report. This archive contains device/module status and diagnostic logs for troubleshooting." ;;
        en:REPORT_LANGUAGE|*:REPORT_LANGUAGE) printf '%s\n' "Report language:" ;;
        en:NOTICE|*:NOTICE) printf '%s\n' "This archive may contain sensitive system logs. Review it before sharing. CleveresTricky keybox XML/CBOX files are intentionally not collected." ;;
        en:WARNING|*:WARNING)
            printf '%s
' "You clicked the action button." \
                          "This button allows you to generate a log in very urgent situations." \
                          "Please use the other WebUI button if you want to enter the module settings." \
                          "A log will be generated in 5 seconds."
            ;;
    esac
}

print_log() {
    if [ "$FROM_WEBUI" != "1" ]; then
        printf '%s\n' "$*"
    fi
}

send_bugreport() {
    share_file="$1"
    case "$share_file" in
        ""|*[!A-Za-z0-9._-]*) return 2 ;;
    esac
    share_path="$SHELL_DIR/files/bugreports/$share_file"
    [ -f "$share_path" ] && [ ! -L "$share_path" ] || return 1

    su 2000 -c "am start -a android.intent.action.SEND --eu android.intent.extra.STREAM content://com.android.shell/bugreports/$share_file -t '*/*' --grant-read-uri-permission" >/dev/null 2>&1
}

copy_report_file() {
    "$WEBUI_BRIDGE" copy-report-file "$report_nonce" "$1" "$2" "$3"
}

copy_report_path() {
    copy_src="$1"
    copy_group="$2"
    [ "$report_copy_count" -lt "$REPORT_COPY_FILE_LIMIT" ] || return 0
    [ -e "$copy_src" ] && [ ! -L "$copy_src" ] || return 0

    print_log "$(message ADDING) $copy_src"
    remaining_files=$((REPORT_COPY_FILE_LIMIT - report_copy_count))
    report_file_list="$workspace/.report-files"
    : > "$report_file_list"
    if [ -d "$copy_src" ]; then
        copy_source_kind="directory"
        copy_source_label=${copy_src##*/}
        find "$copy_src" -xdev -type f 2>/dev/null | head -n "$remaining_files" > "$report_file_list" || true
    elif [ -f "$copy_src" ]; then
        copy_source_kind="file"
        printf '%s\n' "$copy_src" > "$report_file_list"
    else
        rm -f "$report_file_list"
        return 0
    fi

    while IFS= read -r report_file; do
        [ "$report_copy_count" -lt "$REPORT_COPY_FILE_LIMIT" ] || break
        if [ ! -f "$report_file" ] || [ -L "$report_file" ]; then
            continue
        fi
        if [ "$copy_source_kind" = directory ]; then
            case "$report_file" in
                "$copy_src"/*)
                    source_root="$copy_src"
                    source_relative_path=${report_file#"$copy_src"/}
                    relative_report_path="$copy_source_label/$source_relative_path"
                    ;;
                *) continue ;;
            esac
        else
            source_root=${copy_src%/*}
            [ -n "$source_root" ] || source_root=/
            source_relative_path=${copy_src##*/}
            relative_report_path=${report_file##*/}
        fi
        case "$source_relative_path" in
            ""|/*|..|../*|*/..|*/../*) continue ;;
        esac
        destination_relative_path="$copy_group/$relative_report_path"
        case "$destination_relative_path" in
            ""|/*|..|../*|*/..|*/../*) continue ;;
        esac

        if copy_report_file \
            "$source_root" "$source_relative_path" "$destination_relative_path" \
            >/dev/null 2>&1; then
            report_copy_count=$((report_copy_count + 1))
        fi
    done < "$report_file_list"
    rm -f "$report_file_list"
}

write_bounded_log() {
    log_output="$1"
    shift
    (ulimit -f "$REPORT_LOG_FILE_BLOCK_LIMIT" && "$@") > "$log_output" 2>&1
}

write_payload_hashes() {
    hashes_out="$tmp/module-payload-hashes.txt"
    : > "$hashes_out"
    if [ -d "$MODDIR" ] && [ ! -L "$MODDIR" ]; then
        find "$MODDIR" -type f -name '*.sha256' 2>/dev/null | sort 2>/dev/null | while IFS= read -r hash_file; do
            if [ ! -f "$hash_file" ] || [ -L "$hash_file" ]; then
                continue
            fi
            relative_hash=${hash_file#"$MODDIR"/}
            printf '===== %s =====\n' "$relative_hash" >> "$hashes_out"
            cat "$hash_file" >> "$hashes_out" 2>/dev/null || true
            printf '\n' >> "$hashes_out"
        done
    fi
}

create_archive() {
    archive_out="$1"
    if [ -x /system/bin/tar ]; then
        /system/bin/tar -czf "$archive_out" -C "$tmp" .
        return $?
    fi
    if command -v tar >/dev/null 2>&1; then
        tar -czf "$archive_out" -C "$tmp" .
        return $?
    fi
    if [ -x /system/bin/toybox ]; then
        /system/bin/toybox tar -czf "$archive_out" -C "$tmp" .
        return $?
    fi
    return 1
}

generate_report_nonce() {
    [ -r /proc/sys/kernel/random/uuid ] || return 1
    IFS= read -r random_uuid < /proc/sys/kernel/random/uuid || return 1
    report_nonce=$(printf '%s' "$random_uuid" | tr -d '-' | tr '[:upper:]' '[:lower:]')
    [ "${#report_nonce}" -eq 32 ] || return 1
    case "$report_nonce" in
        *[!0-9a-f]*) return 1 ;;
    esac
    printf '%s\n' "$report_nonce"
}

detect_language

if [ "$FROM_WEBUI" = "1" ] && [ "${1:-}" = "--send" ]; then
    send_bugreport "${2:-}"
    exit $?
fi


if [ "$FROM_WEBUI" != "1" ]; then
    print_log "$(message WARNING)"
    sleep 5
fi

print_log "$(message GENERATING)"
stamp=$(date +%Y%m%d-%H%M%S)
case "$stamp" in
    ""|*[!0-9-]*) stamp="unknown" ;;
esac

if [ ! -d "$CONFIG_DIR" ] || [ -L "$CONFIG_DIR" ]; then
    print_log "$(message ARCHIVE_FAILED)"
    exit 1
fi
if ! chown 0:0 "$CONFIG_DIR" 2>/dev/null || ! chmod 0700 "$CONFIG_DIR" 2>/dev/null; then
    print_log "$(message ARCHIVE_FAILED)"
    exit 1
fi
report_nonce=$(generate_report_nonce) || {
    print_log "$(message ARCHIVE_FAILED)"
    exit 1
}
workspace="$CONFIG_DIR/.bugreport-$report_nonce"
if ! mkdir "$workspace" 2>/dev/null || ! chmod 0700 "$workspace" 2>/dev/null; then
    print_log "$(message ARCHIVE_FAILED)"
    exit 1
fi
tmp="$workspace/payload"
if ! mkdir "$tmp" 2>/dev/null || ! chmod 0700 "$tmp" 2>/dev/null; then
    print_log "$(message ARCHIVE_FAILED)"
    exit 1
fi
staged_archive="$workspace/report.tar.gz"
has_shell=false
filename="CleveresTricky-bugreport-$stamp-$report_nonce.tar.gz"

print_log "$(message BASIC)"
root_managers=""
[ -d /data/adb/ksu ] && root_managers="${root_managers} KernelSU"
[ -d /data/adb/ap ] && root_managers="${root_managers} APatch"
[ -d /data/adb/magisk ] && root_managers="${root_managers} Magisk"
[ -n "$root_managers" ] || root_managers=" Unknown"

daemon_pids=$(pidof CleveresTricky 2>/dev/null || true)
[ -n "$daemon_pids" ] || daemon_pids="not running"
module_state="enabled"
[ -e "$MODDIR/disable" ] && module_state="disabled"
[ -e "$MODDIR/remove" ] && module_state="pending removal"

{
    printf 'CleveresTricky Emergency Report\n'
    printf 'Generated: %s\n' "$(date '+%Y-%m-%d %H:%M:%S %z' 2>/dev/null || date)"
    printf 'Detected locale: %s\n' "${RAW_LOCALE:-unknown}"
    printf 'Report language: %s\n\n' "$LANG_CODE"
    printf 'Kernel: %s\n' "$(uname -r 2>/dev/null || true)"
    printf 'Architecture: %s\n' "$(uname -m 2>/dev/null || true)"
    printf 'SDK: %s\n' "$(getprop ro.build.version.sdk 2>/dev/null || true)"
    printf 'SDK_FULL: %s\n' "$(getprop ro.build.version.sdk_full 2>/dev/null || true)"
    printf 'Security patch: %s\n' "$(getprop ro.build.version.security_patch 2>/dev/null || true)"
    printf 'Fingerprint: %s\n' "$(getprop ro.build.fingerprint 2>/dev/null || true)"
    printf 'Manufacturer: %s\n' "$(getprop ro.product.manufacturer 2>/dev/null || true)"
    printf 'Model: %s\n' "$(getprop ro.product.model 2>/dev/null || true)"
    printf 'Device: %s\n' "$(getprop ro.product.device 2>/dev/null || true)"
    printf 'ABI: %s\n' "$(getprop ro.product.cpu.abi 2>/dev/null || true)"
    printf 'SELinux: %s\n' "$(getenforce 2>/dev/null || printf unknown)"
    printf 'Root environment:%s\n' "$root_managers"
    printf 'Module state: %s\n' "$module_state"
    printf 'Daemon PID(s): %s\n' "$daemon_pids"
    printf '\n======== module.prop ========\n'
    if [ -f "$MODDIR/module.prop" ] && [ ! -L "$MODDIR/module.prop" ]; then
        cat "$MODDIR/module.prop" 2>/dev/null || true
    else
        printf 'module.prop unavailable\n'
    fi
    printf '\n======== disk ========\n'
    df -h /data 2>/dev/null || df /data 2>/dev/null || true
} > "$tmp/basic.txt"

{
    message REPORT_INFO
    printf '%s %s\n' "$(message REPORT_LANGUAGE)" "$LANG_CODE"
    printf '\n%s\n' "$(message NOTICE)"
} > "$tmp/REPORT.txt"

{
    printf '======== module directory ========\n'
    ls -la "$MODDIR" 2>/dev/null || true
    printf '\n======== process ========\n'
    ps -A 2>/dev/null | awk 'tolower($0) ~ /cleverestricky/' || true
    printf '\n======== mounts ========\n'
    mount 2>/dev/null | grep -i -e 'cleverestricky' -e '/data/adb/modules' 2>/dev/null || true
} > "$tmp/runtime.txt"

write_payload_hashes

print_log "$(message LOGS)"
if ! write_bounded_log "$tmp/logcat-all.log" logcat -b all -d -v threadtime; then
    write_bounded_log "$tmp/logcat-all.log" logcat -d -v threadtime || true
fi
if [ -f "$tmp/logcat-all.log" ]; then
    write_bounded_log "$tmp/logcat-cleverestricky.log" grep -i 'cleverestricky' "$tmp/logcat-all.log" || true
fi

write_bounded_log "$tmp/dmesg.log" dmesg || true

copy_report_path "$CONFIG_DIR/logs" "cleverestricky"
copy_report_path "$CONFIG_DIR/log" "cleverestricky"
copy_report_path "$CONFIG_DIR/bugreports" "cleverestricky"
copy_report_path "$CONFIG_DIR/crash" "cleverestricky"
copy_report_path "$MODDIR/logs" "cleverestricky-module"

print_log "$(message ROOT_LOGS)"
copy_report_path "/data/adb/ksu/log" "root-manager/KernelSU"
copy_report_path "/data/adb/ap/log" "root-manager/APatch"
copy_report_path "/data/adb/magisk/log" "root-manager/Magisk"
copy_report_path "/cache/magisk.log" "root-manager/Magisk"
copy_report_path "/data/tombstones" "android"
copy_report_path "/data/system/dropbox" "android"
copy_report_path "/sys/fs/pstore" "android"
copy_report_path "/data/anr" "android"

print_log "$(message COMPRESSING)"
if ! (ulimit -f "$REPORT_FILE_BLOCK_LIMIT" && create_archive "$staged_archive"); then
    print_log "$(message ARCHIVE_FAILED)"
    exit 1
fi
if [ ! -x "$WEBUI_BRIDGE" ] || [ -L "$WEBUI_BRIDGE" ]; then
    print_log "$(message ARCHIVE_FAILED)"
    exit 1
fi
out=$("$WEBUI_BRIDGE" publish-report "$report_nonce" "$filename") || {
    print_log "$(message ARCHIVE_FAILED)"
    exit 1
}
case "$out" in
    "$SHELL_DIR/files/bugreports/"*) has_shell=true ;;
    /storage/emulated/0/Download/*|/data/local/tmp/*) ;;
    *)
        print_log "$(message ARCHIVE_FAILED)"
        exit 1
        ;;
esac
filename=${out##*/}
case "$filename" in
    ""|*[!A-Za-z0-9._-]*)
        print_log "$(message ARCHIVE_FAILED)"
        exit 1
        ;;
esac

print_log "$(message GENERATED) $out"

if $has_shell && [ "$FROM_WEBUI" != "1" ]; then
    print_log "$(message SHARING)"
    if ! send_bugreport "$filename"; then
        print_log "$(message SHARE_FAILED)"
    fi
fi

if [ "$FROM_WEBUI" = "1" ]; then
    printf '%s\n' "$out"
fi
