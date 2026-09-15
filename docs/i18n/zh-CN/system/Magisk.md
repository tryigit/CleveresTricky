# Magisk 支持

**语言:** [English](../../../system/Magisk.md) | [Türkçe](../../tr/system/Magisk.md) | **简体中文** | [Español](../../es/system/Magisk.md) | [Deutsch](../../de/system/Magisk.md) | [Русский](../../ru/system/Magisk.md) | [Bahasa Indonesia](../../id/system/Magisk.md) | [हिन्दी](../../hi/system/Magisk.md) | [العربية](../../ar/system/Magisk.md)

## 重要建议

> [!CAUTION]
> **CleveresTricky 不推荐在 Magisk 环境下使用。**
>
> 现代应用检测框架与 Google Play Integrity 能够主动检测用户态挂载命名空间、root 二进制文件以及 Zygote 注入。Magisk 的用户态架构留下了显眼的检测痕迹，使得长期有效的 root 隐藏变得极其困难。
>
> 有关内核级与用户态 root 实现的架构对比与隐蔽性分析，请参阅：
> 👉 **[Advanced Android Root Guide: KernelSU, APatch & Concealment | Yiğit - tryigit.dev](https://tryigit.dev/advanced-android-root-architecture-concealment/)**
>
> **在条件允许的情况下，请优先选择 [KernelSU](https://kernelsu.org) 或 [APatch](https://apatch.dev)。**

---

## 无 WebUI 模式（Headless 架构）

KernelSU 和 APatch 在其管理器应用内内置了模块 WebUI 扩展环境。Magisk 并不支持此项规范。

因此，在 Magisk 下安装时，**CleveresTricky 将以无 WebUI 的后台守护进程模式运行**。核心原生守护进程（`cleverestrickyd`）、后端服务以及 Keystore 拦截器均保持完整功能，但所有设置都需要通过直接编辑 `/data/adb/cleverestricky/` 目录下的文件进行手动配置。

---

## 手动配置指南

所有模块配置与策略文件均存放在 `/data/adb/cleverestricky/` 目录中。

### 1. 作用域与目标

* **`target.txt`**: 目标包名列表（每行一个包名），用于 Keystore 认证伪装。
  ```text
  com.google.android.gms
  com.google.android.gms.unstable
  com.android.vending
  ```
* **`global_mode`**: 空标记文件。
  * **存在时**: 全局模式生效（拦截除系统及 root 相关的全部应用）。
  * **不存在时**: 仅针对 `target.txt` 中明确列出的应用进行拦截。
  * *命令:* `touch /data/adb/cleverestricky/global_mode`（启用）或 `rm -f /data/adb/cleverestricky/global_mode`（禁用）。

* **`identity_target.txt`**: 针对设备构建属性（Build Props）伪装的目标包名。
* **`global_identity_mode`**: 空标记文件。存在时对非系统应用全局生效设备身份伪装。

---

### 2. 设备身份与属性伪装

* **`spoof_build_vars`**: 以 `KEY=VALUE` 格式定义需要伪装的设备属性：
  ```properties
  MANUFACTURER=Google
  MODEL=Pixel 8 Pro
  FINGERPRINT=google/husky/husky:14/UQ1A.240105.004/11269998:user/release-keys
  BRAND=google
  PRODUCT=husky
  DEVICE=husky
  RELEASE=14
  ID=UQ1A.240105.004
  INCREMENTAL=11269998
  TYPE=user
  TAGS=release-keys
  ```
* **`security_patch.txt`**: 安全补丁日期（例如 `2026-03-05`）。留空或删除此文件则自动与系统属性对齐。
* **`boot_props_mode`**: 控制引导加载程序（Bootloader）属性模拟方式（`auto`、`manual` 或 `disabled`）。

---

### 3. 硬件 Keybox

* **`keybox.xml`**: 将有效的硬件 attestation 密钥箱 XML 直接放入 `/data/adb/cleverestricky/keybox.xml`。请确保文件权限受到严格保护（`chmod 600`）。
* **`keyboxes/`**: 用于存放多个 keybox 文件的子目录。

---

### 4. DRM 与隐私作用域

* **`drm_packages.txt`**: 需要跳过 Keystore 拦截以保持 Widevine L1 硬件 DRM 正常的媒体应用包名：
  ```text
  com.netflix.mediaclient
  com.amazon.avod.thirdpartyclient
  com.disney.disneyplus
  ```

---

### 5. 功能开关标记文件

通过创建标记文件（`touch <file>`）启用功能，或通过删除标记文件（`rm -f <file>`）禁用：

| 标记文件 | 存在时的效果 |
| :--- | :--- |
| `spoof_enabled` | 启用身份伪装核心引擎。 |
| `spoof_build_identity` | 启用设备构建属性伪装。 |
| `auto_keybox_check` | 自动验证 keybox 并检查吊销状态。 |
| `drm_passthrough` | 为 `drm_packages.txt` 中的包启用 DRM 直通保护。 |
| `hide_sensitive_props` | 隐藏敏感的 root、调试与引导加载程序状态属性。 |
| `tee_broken_mode` | 在硬件 TEE 损坏的设备上启用软件认证回退。 |
| `debug_logging` | 在 `native_runtime.log` 中记录详细的原生调试日志。 |

---

## 生效与验证

### 使配置更改生效
由于 Magisk 缺乏 WebUI 动态重载通道，修改配置文件或标记后建议重启设备：
```sh
su -c "reboot"
```

### 查看运行日志
```sh
su -c "cat /data/adb/cleverestricky/native_runtime.log"
```

### 验证运行进程
```sh
su -c "ps -A | grep -E 'cleverestrickyd|cleverestricky_backend'"
```

### 生成诊断日志包
```sh
su -c "/data/adb/modules/cleverestricky/action.sh"
```
诊断压缩包将生成在 `/data/adb/cleverestricky/bugreports/` 目录下。
