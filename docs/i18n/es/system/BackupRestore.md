# Backup and Restore

**Idioma:** [English](../../../system/BackupRestore.md) | [Türkçe](../../tr/system/BackupRestore.md) | [简体中文](../../zh-CN/system/BackupRestore.md) | **Español** | [Deutsch](../../de/system/BackupRestore.md) | [Русский](../../ru/system/BackupRestore.md) | [Bahasa Indonesia](../../id/system/BackupRestore.md) | [हिन्दी](../../hi/system/BackupRestore.md) | [العربية](../../ar/system/BackupRestore.md)

Exporta configuración y material autorizado dentro de un archive autenticado y cifrado. Requiere contraseña de al menos 12 caracteres y usa una allowlist de archivos conocidos; symlinks, rutas desconocidas y límites excesivos se rechazan.

Import acepta solo CTSB cifrado y limita upload, entries, keyboxes, tamaños individuales y total expandido. Traversal, duplicados, directormodern, symlink destinations, texto, settings o keyboxes inválidos se rechazan antes de escribir. El estado de policy v2 también se valida y publica como un snapshot completo. La configuración de los servidores remotos también se incluye cifrada y ligada al dispositivo; solo se restaura si se descifra y valida en el mismo dispositivo, y sin entrada se conservan los servidores existentes. Aplicar el perfil Default elimina todos los servidores remotos y su contenido de keybox en caché.
