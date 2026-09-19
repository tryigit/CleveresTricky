# Keybox Manager

**语言:** [English](../../../security/KeyboxManager.md) | [Türkçe](../../tr/security/KeyboxManager.md) | **简体中文** | [Español](../../es/security/KeyboxManager.md) | [Deutsch](../../de/security/KeyboxManager.md) | [Русский](../../ru/security/KeyboxManager.md) | [Bahasa Indonesia](../../id/security/KeyboxManager.md) | [हिन्दी](../../hi/security/KeyboxManager.md) | [العربية](../../ar/security/KeyboxManager.md)

Keybox Manager 加载、验证、选择和监控授权 attestation key material，支持 legacy 单文件、多 XML 和 encrypted CBOX。Application Rule 可引用指定已验证文件，remote source 在同样的本地验证完成前仍视为不可信。

每个 private key 必须匹配 leaf certificate，并检查算法、chain、日期、重复/歧义、revocation。有效的密钥材料在开机时无需等待网络即可立即生效。启用 Automatic Keybox Check 时将在联网后在后台执行吊销检查；禁用时则允许自定义或被吊销的材料。包含损坏条目的 pool 整体拒绝。真实 keybox 不应提交到源码仓库。每个已存储的 Keybox 都可以在 WebUI 中禁用而不删除；被禁用的条目仍保留在列表中，但会立即退出活动池并在选择时被跳过。该选择保存在配置目录的纯文本 `disabled_keyboxes` 文件中，每行一个 `作用域:文件名`（`keyboxes:keybox2.xml`、`root:keybox.xml`），因此不受模块更新影响，可手动或通过 Magisk 工具维护，删除该文件即可重新启用所有 Keybox。粘贴或拖放的 Keybox 一律保存为 `keybox.xml`；若名称已被占用则自动选择下一个可用名称（`keybox2.xml`、`keybox3.xml`……），绝不会静默替换已有 Keybox。
