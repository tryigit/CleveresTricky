# Attestation

**语言:** [English](../../../security/Attestation.md) | [Türkçe](../../tr/security/Attestation.md) | **简体中文** | [Español](../../es/security/Attestation.md) | [Deutsch](../../de/security/Attestation.md) | [Русский](../../ru/security/Attestation.md) | [Bahasa Indonesia](../../id/security/Attestation.md) | [हिन्दी](../../hi/security/Attestation.md) | [العربية](../../ar/security/Attestation.md)

认证层为选定应用提供受控的证书链兼容，同时保留 Android 真实的密钥创建和后续密码学操作。

RKP 基础设施调用者始终保持在 Android 原生的配置路径上。对于被选中的应用 UID，成功的 `generateKey` 响应与后续 `getKeyEntry` 证书读取使用同一条证书兼容路径，避免同一 alias 暴露不同的认证叶证书。

私钥操作仍由 Android KeyMint 或 StrongBox 在请求的安全级别中完成。材料启用前会验证密钥/证书匹配、算法、链结构、有效期、歧义和吊销状态。证书替换不能创建硬件信任根、重新锁定 bootloader 或保证远端判定。

## 硬件 TEE 预配与 OnePlus 恢复说明

CleveresTricky 需要正常工作的硬件 KeyMint/TEE，设计上不模拟或伪造软件 TEE 或伪造认证。当硬件 TEE 通信失败（例如 `SECURE_HW_COMMUNICATION_FAILED` / 错误码 10）时，模块会中止注入以避免系统死锁。

在某些解锁设备上，解锁引导加载程序（Bootloader Unlock）可能会破坏设备端硬件认证或 RKP 预配：
- **OnePlus 13 / 15 解锁设备认证 / TEE RKP 恢复**：如 [wuxianlin 的研究](https://wuxianlin.com/2025/11/12/oneplus-13-15-attestation-rkp-test/) 所述，在测试的 OnePlus 13 和 15 设备上，解锁引导加载程序会损坏 TEE Attestation 和 RKP 预配。
- **设备端预配方案**：该文章指出，通过 `KmInstallKeybox` 设备 ID 预配可在不注入泄露证书/密钥的情况下恢复原生 TEE RKP（以及测试机型上的 Widevine L1 RKP）。这是一种合法的设备端 TEE 预配/恢复途径，而非 Keystore 伪装或模拟。
- **范围与限制**：
  - 这**不能**普遍恢复离线 TEE 认证密钥（offline TEE Attestation Key）。
  - 该方案**严格依赖特定机型与固件版本**；请勿将其普遍推广，也不意味着所有 OnePlus 机型均受支持。
  - **风险警告**：修改设备端 `persist` 或 TEE 预配存在损坏密钥库或导致设备变砖的高风险。在尝试任何 TEE 恢复操作前，请务必完整备份 `persist` 分区及固件。
