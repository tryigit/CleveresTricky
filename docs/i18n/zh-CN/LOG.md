# 日志记录与诊断 (Logging and Diagnostics)

**语言:** [English](../../../LOG.md) | [Türkçe](../tr/LOG.md) | **简体中文** | [Español](../es/LOG.md) | [Deutsch](../de/LOG.md) | [Русский](../ru/LOG.md) | [Bahasa Indonesia](../id/LOG.md) | [हिन्दी](../hi/LOG.md) | [العربية](../ar/LOG.md)

CleveresTricky 提供了多种内置机制，用于获取诊断快照、运行时日志和紧急错误报告。在提交 Issue 或报告异常行为时，提供诊断信息和日志对于定位根本原因至关重要。

---

## 方法 1：WebUI 管理界面（推荐）

WebUI 提供了最便捷的诊断收集方式，无需 ADB 或电脑支持。

### A. 支持诊断快照 (Support Diagnostics Snapshot)
1. 在浏览器或 Root 管理器中打开 CleveresTricky WebUI。
2. 转到 **信息与资源 (Info & Resources)** 标签页。
3. 滚动到 **支持诊断 (Support Diagnostics)** 区域。
4. 点击 **复制诊断信息 (Copy Diagnostics)** 按钮。
5. 将复制的内容直接粘贴到 GitHub Issue 模板的 **Support Diagnostics Snapshot** 部分。

> [!NOTE]
> 诊断快照严格保护隐私。仅包含模块状态、运行时版本、Root 环境类型、内存/CPU 使用率以及功能开关状态。绝不包含私钥、凭证、Keybox 证书、应用包名或身份属性值。

### B. WebUI 实时日志与调试日志 (Debug Logging)
1. 在 WebUI 中，转到 **日志 (Logs)** 标签页。
2. 将 **Debug Logging** 开关切换为 **开启**（开关变为绿色）。这将在无需安装 Debug APK 的情况下启用详细的运行时跟踪。
3. 在设备上复现问题或重新执行 Play Integrity 测试。
4. 返回 WebUI **Logs** 标签页并点击 **复制 (Copy)** 按钮。
5. 将日志粘贴到错误报告中。
6. 完成后请将 **Debug Logging** 关闭，以节省系统资源。

---

## 方法 2：一键紧急错误报告归档 (`action.sh`)

CleveresTricky 内置了一个自动错误报告工具，可将系统日志、模块状态和 Root 环境信息打包为压缩归档文件（`.tar.gz`）。

### 通过 Root 管理器：
- 在 **KernelSU** 或 **APatch** 中，点击 CleveresTricky 模块卡片旁边的 **操作 (Action)** 按钮。

### 通过终端 (Termux / Root Shell)：
```bash
su -c /data/adb/modules/cleverestricky/action.sh
```

### 报告文件位置：
- 生成的归档保存在：
  ```
  /data/adb/cleverestricky/bugreports/CleveresTricky_Bugreport_<时间戳>.tar.gz
  ```
  （在系统允许的情况下也会自动复制到设备的 `Download/` 目录）。
- 您可以将此文件直接作为附件上传到 GitHub Issue。

> [!TIP]
> 为保护您的数据安全与隐私，Keybox XML/CBOX 文件和私密凭证已被明确排除在该归档之外。

---

## 方法 3：原生守护进程运行时日志 (`native_runtime.log`)

原生后台守护进程将其初始化、Hook 安装和运行状态持续记录到：
```
/data/adb/cleverestricky/native_runtime.log
```

在终端中查看最新运行日志：
```bash
su -c "tail -n 100 /data/adb/cleverestricky/native_runtime.log"
```

---

## 方法 4：Android Logcat（ADB 或终端）

如果 WebUI 无法打开或模块服务未能启动，Android logcat 可直接反映早期启动和 Binder 事务状态。

### 通过 ADB 实时捕获：
```bash
adb logcat -s cleverestricky CleveresTricky
```

### 在 Termux 或 Root Shell 中：
```bash
su -c "logcat -d -s cleverestricky CleveresTricky"
```

### 干净的启动捕获（通过重启 Keystore）：
```bash
adb logcat -c
adb shell su -c 'setprop ctl.restart keystore2'
adb logcat -d -s cleverestricky CleveresTricky
```

### 关键启动标记：
- `Welcome to Service!`
- `Web server on port ...`
- `libbinder ioctl hook installed successfully`
- `Keystore Binder interceptor registered`
- `TEE SecurityLevel interceptor registered`

### KeyMint / TEE 硬件故障标记：
如果您在日志中看到：
```text
[WARN] Platform KeyMint HAL unreachable or TEE broken (SECURE_HW_COMMUNICATION_FAILED).
[INFO] CleveresTricky requires a functional hardware KeyMint; aborting injection to prevent framework deadlock.
[INFO] Please consult documentation (docs/security/Attestation.md or docs/LOG.md) for TEE recovery guidance.
```
这表明设备硬件 TEE 或厂商 KeyMint HAL 通信失败（错误码 -49 / 10）。CleveresTricky 会触发内部熔断机制中止注入，以防止系统死锁。有关设备端 TEE 恢复选项（如 OnePlus 13/15 解锁设备 TEE RKP 恢复），请参阅 [Attestation.md](security/Attestation.md#硬件-tee-预配与-oneplus-恢复说明)。

> [!WARNING]
> 公开发布日志前请仔细检查。虽然凭证和 WebUI Token 绝不会被记录，但设备型号名称、进程 ID 和包名仍可能可见。

---

## 提交 Issue 时请提供

在 GitHub 上提交 Bug 报告时，请附带：
1. **支持诊断快照**（来自 WebUI 信息标签页）
2. **日志**（来自 WebUI Logs 开启 Debug Logging 后的输出、`action.sh` 归档或 logcat）
3. 设备型号、Android 版本及 Root 方式（KernelSU / APatch / Magisk）
