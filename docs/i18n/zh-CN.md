# CleveresTricky 中文文档

**语言：** [English](../../README.md) | [Türkçe](tr.md) | **简体中文** | [Español](es.md) | [Deutsch](de.md) | [Русский](ru.md) | [Bahasa Indonesia](id.md) | [हिन्दी](hi.md) | [العربية](ar.md)

[中文 README](../../README.zh-CN.md)

> 本文件提供所有面向用户 Markdown 文档的简体中文参考。若技术细节存在差异，以英文原文和源代码为准。

<a id="application-rules"></a>
## Application Rules

Application Rules 可以为符合条件的应用分配设备模板、经过验证的本地 keybox 或隐私策略。有效规则本身就是明确目标，因此不需要额外 scope 条目。`inherit` 保持全局策略；`isolate` 从受保护随机种子派生稳定的应用级 IMEI、IMSI、ICCID、MEID、电话号码、序列号、支持的 attestation 标识以及现代 DRM `deviceUniqueId` 假名；`redact` 对支持的 telephony 与 attestation 标识返回空值，同时保留 Android 权限失败。

Attestation 身份替换需要有效且已验证的 keybox。DRM identifier isolation 与 DRM Keystore Passthrough 相互独立。Shared UID 的包会被作为同一确定性上下文处理，真实包名来自 Package Manager，而不是请求内容。规则以有界 trie 和不可变 snapshot 管理，成功 reload 时相关缓存同步清理。

<a id="application-scope"></a>
## Application Scope

Application Scope 决定哪些 Android 应用 UID 可以接收证书或身份兼容处理。Targeted mode 是日常推荐模式，`target.txt` 的精确包名和有限 wildcard 会通过 Package Manager 解析到真实 caller UID。共享 UID 的应用在 Binder 层属于同一调用身份。

Global Mode 不要求 `target.txt` 条目，但 system identity 和受保护基础设施仍排除在替换范围之外。包解析失败时 fail closed。规则和短期 decision cache 一起原子替换，非法更新不会覆盖最后一个有效状态。

<a id="attestation"></a>
## Attestation

认证层为选定应用提供受控的证书链兼容，同时保留 Android 真实的密钥创建和后续密码学操作。

RKP 基础设施调用者始终保持在 Android 原生的配置路径上。对于被选中的应用 UID，成功的 `generateKey` 响应与后续 `getKeyEntry` 证书读取使用同一条证书兼容路径，避免同一 alias 暴露不同的认证叶证书。

私钥操作仍由 Android KeyMint 或 StrongBox 在请求的安全级别中完成。材料启用前会验证密钥/证书匹配、算法、链结构、有效期、歧义和吊销状态。证书替换不能创建硬件信任根、重新锁定 bootloader 或保证远端判定。

<a id="automatic-keybox-check"></a>
## Automatic Keybox Check

Automatic Keybox Check 在不持续扫描存储的情况下维护 keybox 与 revocation 状态。正常文件变化由 observer 处理，在某些文件系统上使用低频 fallback。重复错误不会产生重叠 worker。

每次刷新都会重新验证 key/certificate、chain、算法、有效期、歧义和吊销状态。无法确定 revocation 时新材料不会启用。缓存按文件数量和大小限制，未变化的已验证文件可复用解析结果。

<a id="backup-restore"></a>
## Backup and Restore

Backup/Restore 使用一个 authenticated encrypted archive 迁移配置和授权 key material。Export 要求至少 12 字符密码，只从 allowlist 收集已知配置和普通 keybox 文件，并拒绝 symlink、未知路径、过多文件和超限大小。

Import 仅接受加密 CTSB，限制 upload、entry 数量、keybox 数量、单文件大小和总 expanded size。Traversal、重复名、目录、symlink destination、非法文本/设置/keybox 会在写入前全部拒绝。Version two policy 和 profile 引用也会验证并作为完整 snapshot 发布。

<a id="boot-properties"></a>
## Boot Properties

Boot Properties 是核心 userspace property 视图，用于减少应用读取常见 unlocked、debug、warranty、verified boot 和 recovery 标记时的暴露。固定 property 集在 Zygote 之前应用，并独立于可选身份功能保持启用。

`boot_props_mode` 只控制可选 template Build Identity compatibility，可为 `auto`、`force`、`disable`，不会关闭核心 boot property protection。该视图不会物理锁回 bootloader、修复 verified boot 或改变 TEE root of trust。

<a id="build-identity"></a>
## Build Identity

Build Identity 将完整设备模板应用到 fingerprint 和支持的 app-visible Build 字段。它是可选功能，需要 Spoof Engine，并因 Android 在早期启动捕获这些值而需要重启。模板覆盖 manufacturer、model、brand、product、device、fingerprint、release、build ID、incremental、type、tags 和 security patch，任意 `ro.*` 输入会被拒绝。

Auto Identity 可从 Google 公共元数据解析 Pixel beta/canary Build Identity 并保存，但不会自动启用引擎。Build Identity、Security Patch、Region、Telephony、Attestation Identity 分别解析。

<a id="building"></a>
## Building

构建需要 Java 21、Android SDK API 36、NDK 27.3.13750724、CMake 3.22.1、stable Rust、ARM64/x86 64 Android Rust targets、Cargo NDK 与 git submodules。需要运行 Kotlin/Android 检查、Rust fmt/clippy/tests 和模块 unit tests。

CI 同时验证 shell、SELinux、module template、Kotlin/Java/Rust、双架构、release/debug ZIP 与 Encryptor。First-party C 被禁止，`binder_interceptor.cpp` 是唯一允许的 first-party C++ Android ABI 边界。Release 使用 `./gradlew zipRelease`。

<a id="certificate-safe-mode"></a>
## Certificate Safe Mode

Certificate Safe Mode 是旧版配置概念。当前 WebUI 不提供关闭核心 Keystore/TEE compatibility 的开关。Global Mode 与 Application Rules 决定 scope，而 Spoof Engine 只控制身份值。

旧安装中的 `tee_broken_mode` 仅用于迁移/兼容读取，核心 targeting 不再依赖它。排查时应缩小 scope、使用合适 passthrough，或在受控环境移除相关 key material。

<a id="diagnostics"></a>
## Diagnostics

首先在 Dashboard 检查版本、Spoof Engine、profile、keybox 数量、target size、RKP、DRM 和 native feature state，并在 Logs 中找到首次错误。WebUI 无法启动时检查 logcat、daemon、`webroot`、architecture-specific `webui_bridge` 与 module manager 状态。

建议使用 Minimal + reboot 建立 genuine baseline，再逐步启用 targeted Spoof Engine、单个授权 key source/rule，以及 Build Identity、Telephony、Boot Properties 或 broad scope。Effective State inspector 会报告 matched rule/profile、scope、template、keybox ref、privacy、feature decisions、patch、RKP/DRM、KeyMint/StrongBox、provider coexistence 和 reboot requirement，但绝不返回私钥。

<a id="drm-passthrough"></a>
## DRM Keystore Passthrough and Identifier Privacy

DRM Keystore Passthrough 让选定媒体应用保持在 Android 原生 Keystore certificate path。DRM Identifier Privacy 只针对支持的 stable AIDL DRM，在 `privacy=isolate` 应用读取 `deviceUniqueId` 时返回稳定的应用级假名，不用真实 DRM ID 作为派生输入。

`drm_packages.txt` 支持精确包名和有限 wildcard。Privacy hook 只处理稳定 AIDL `IDrmFactory` / `IDrmPlugin.getPropertyByteArray("deviceUniqueId")`，不会修改 legacy HIDL、security level、license、provisioning、content key、session、HDCP 或 string properties。接口不符合预期时 fail open，保留原生响应。

<a id="encrypted-storage"></a>
## Encrypted Storage

CBOX 使用 authenticated AES-256-GCM 存储和传输 keybox，并通过 authentication data 绑定 metadata。密码型容器使用有界 key derivation，本地 protected cache key 位于私有配置区域。

Unlock 只通过原生 module-manager WebUI transport 接受。解密成功不能绕过 keybox verification，仍需检查 private key、certificate、chain、date、algorithm 与 revocation。Root compromise 后已解锁数据仍可能被读取。

<a id="identity-refresh"></a>
## Identity Refresh

Identity Refresh 为下一次启动准备新的 validated app-facing identity，而不改变当前 boot 的 active snapshot。Early boot 会验证 staged file 的 path/type/size/permission/control，再原子 promote，随后 Build properties 与 service 使用同一 snapshot。

IMEI/ICCID checksum、数字长度和 serial charset 都有界。手动编辑会丢弃旧 staged snapshot；在启动前关闭 Spoof Engine 或 Identity Refresh 会阻止不希望的 promotion。

<a id="installer"></a>
## Installer

Installer 安装完整 KernelSU/APatch 模块，包括 service、native payload、scripts、policy、metadata 与 integrity records。支持 Android 12-17、ARM64、x86 64；Magisk 和 recovery 会在产生 partial install 前停止。

每个 packaged payload 都有 SHA 256 记录，安装和 runtime 都检查 regular file、symlink 与 unexpected payload。Archive 内部 hash 不是发布者身份凭证，因此官方 Release 另有 `SHA256SUMS` 与 GitHub signed build provenance。

<a id="keybox-manager"></a>
## Keybox Manager

Keybox Manager 加载、验证、选择和监控授权 attestation key material，支持 legacy 单文件、多 XML 和 encrypted CBOX。Application Rule 可引用指定已验证文件，remote source 在同样的本地验证完成前仍视为不可信。

每个 private key 必须匹配 leaf certificate，并检查算法、chain、日期、重复/歧义、revocation。无法确定 revocation 的新材料不启用，包含坏条目的 pool 整体拒绝。真实 keybox 不应提交到源码仓库。

<a id="native-architecture"></a>
## Native Architecture

所有可移植 native logic 使用 Rust。没有 first-party C，只有因 private Android libbinder object ABI 必需的 `binder_interceptor.cpp` 作为 first-party C++ 例外。Rust native core 负责 Binder layout/stream validation、FD classification、kernel-validated copy、版本和控制数据解析。

Rust injector 负责参数、日志、文件校验、SELinux socket、随机 abstract socket、FD transfer、maps/symbol、ptrace、register、remote memory、loader 与 cleanup。临时 target stack 写入使用有界 journal 恢复。C++ 例外不能扩张。

<a id="patch-levels"></a>
## Patch Levels

`security_patch.txt` 提供 System、Vendor、Boot 的 global/per-app attestation patch rules。支持 calendar、`today`、`device_default`、`prop`、`no`；version two 中每个组件独立支持 Device、Property、Manual、Automatic、Omit。

Parsing 对大小、section、package、field、date、value 做限制，非法文件不会部分更新运行状态。Automatic mode 使用日历逻辑。Patch presentation 不会实际安装安全更新、修改 kernel/vendor firmware 或保证远程判定。

<a id="performance"></a>
## Performance and Memory

核心 Keystore interception 在服务健康时持续注册。Spoof Engine 关闭会停用不必要的 optional identity、DRM privacy、build/region 和 telephony 工作，而 core certificate 与 boot protection 继续运行。Automatic Keybox Check 有独立开关。

Rust Binder parser 使用固定数组，descriptor cache 为 64 个固定槽。DRM controller、package/rule/certificate/keybox 等缓存都有严格 entry/byte 上限，并避免 busy polling。Release Rust 使用 LTO、size optimization 与 hardened linker 设置。

<a id="profiles"></a>
## Profiles

Profiles 以一次经过验证的事务应用一组可选设置；核心 boot、Keystore 与 RKP 基础设施保护始终独立保持启用。

Daily Compatibility 使用定向范围和 keybox 监控；Default 是保守的可选身份配置；Maximum Compatibility 启用 Global Mode、build identity、identity refresh 与 telephony，并关闭 DRM passthrough；Minimal 关闭可选身份和计划 keybox 检查。这些预设都不会改变 RKP 基础设施保护。

旧配置可能仍包含已退役的 `rkp_passthrough` 标记，但运行时的 generated-key 行为不再依赖它。Version two profile 可保存应用分配、template、已验证 keybox、privacy、patch 以及可选 identity/DRM 设置；旧 RKP 字段仅用于迁移兼容，不再作为 WebUI 的实时选项。

<a id="provider-coexistence"></a>
## Provider Coexistence

Automatic Build Identity 会检测其他已启用 fingerprint/property provider，例如常见 PIF、`autopif`/`auto_pif` 和 PlayCurl 变体，避免覆盖它们。

有冲突时 optional Build properties 保持不变，但 attestation、keybox、patch、RKP、DRM 和 telephony 仍可工作。Force mode 明确绕过检测，日常建议 automatic。

<a id="region-properties"></a>
## Region Properties

Region Properties 通过一小组固定 Android properties 提供可选、有界的 China-region 视图。Hardware/SIM/operator country、hardware level、radio marker 等值固定在代码中，不接受任意用户 property。

Spoof Engine 开启时在 Zygote 前应用。它不会改变真实 SIM 国家、radio registration、modem firmware、secure hardware sales region 或 carrier account。

<a id="remote-sources"></a>
## Remote Sources

Remote Sources 只从明确配置的 HTTPS endpoint 获取授权 keybox material。Host/port/path/timeout/refresh/auth/header/response size 均有界，secret 不出现在 status response。

可要求签名内容。Signature、XML/CBOX、size、keybox、certificate 与 revocation 检查完成前数据不会激活。失败刷新不会用坏下载覆盖已有 verified material。

<a id="rkp-protection"></a>
## RKP Protection

Remote Key Provisioning 保护会让 Android provisioning 基础设施保持在真实平台路径上。Android/Google RKP 与旧 Remote Provisioner 包始终排除在证书替换范围之外；系统 UID 与无法解析包名的情况也会 fail closed。

RKP 基础设施调用者从不被修改。对于目标应用 UID，`generateKey` 与后续 `getKeyEntry` 证书响应使用统一的兼容路径，从而避免同一 alias 出现两个不同的 attestation leaf。

旧的 `rkp_passthrough` 开关已经退役。旧配置或备份中可以继续存在该标记，但它不再控制 generated-key 行为，也不会作为 WebUI runtime toggle 暴露。内置 Profiles 不再改变 RKP 行为，RKP 基础设施保护始终开启。

CleveresTricky 不模拟 RKP 服务器、不生成 provisioning credential，也不改变硬件 provisioning root。

<a id="security-model"></a>
## Security Model

Root service、OS、KernelSU/APatch、installed module files 和明确授权 key material 属于本地 trusted boundary。App、Binder content、upload、remote response、config edit、archive、rules、templates、paths 和 network metadata 都是不可信输入。

配置目录必须真实、root-owned，敏感文件 root-only，symlink 被拒绝，写入原子化。Native parser 先验证 live Binder ABI，再通过 kernel-validated bounded copy 解析。Injector 限制 symbol/process/library，WebUI 不开放 TCP port，只通过 strict native bridge/queue。恶意 root process 仍超出可完全防御范围。

<a id="spoof-engine"></a>
## Spoof Engine

Spoof Engine 是可选 app-facing identity 控制器。即使关闭，核心 Keystore/TEE interception、certificate compatibility、root-of-trust 与 boot protection 仍继续。

开启后可配合各自控制启用 attestation identity、Telephony、Build Identity、Region 与 Identity Refresh。关闭只停止呈现，不删除保存值。App 可能缓存旧值，因此 live change 后可能需要重启 app，Build Identity 变化需要 reboot。

<a id="telephony-identity"></a>
## Telephony Identity

Telephony Identity 可在支持的 Android telephony Binder API 中替换 IMEI、MEID、IMSI、ICCID、phone number，并支持两个 SIM slot。校验 checksum、长度、hex/phone syntax、slot 和 input size。

Interceptor 先获取真实 Android response，权限拒绝、error 或 null 都保留，不会绕过权限。它只影响 app-facing 值，不修改 modem、baseband、EFS、实体 SIM 或运营商看到的身份。

<a id="web-interface"></a>
## Web Interface

WebUI runtime ownership 固定为 `index.html` 静态结构/base CSS，`bridge.js` native bridge/external intents，`policy.js` policy/state API 与 policy-owned UI，`ux.js` general presentation/localization/guide/community UX。没有 standalone runtime CSS，也不增加 feature-specific JS bundle。

移动端使用底部安全区导航、触摸尺寸控件、responsive panels、password visibility、progress state 和 accessible tabs。WebUI 不监听 TCP port；module manager native API 通过有界 Rust bridge 与 root-only queue 调用 service，所有 path/method/size/time/input 都重复验证。

<a id="changelog"></a>
## CHANGELOG

V2.5.3 增加细粒度 identity/security patch controls、named profiles 与 Effective State；加强 attestation、KeyMint/StrongBox、DRM identifier privacy、升级流程与 Android 17；统一 WebUI 文件 ownership、恢复内置翻译、改进 Configuration Management 和移动导航；加入 KeyboxHub external-browser helper；并加强 diagnostics、cache/timing、dependency security、regression 与 release artifact validation。

<a id="contributing"></a>
## Contributing

贡献必须保持 fail-closed security model、Android 12-17 与 KernelSU/APatch 范围，并避免无法验证的 hardware-backed integrity 声明。运行相关 Kotlin/Android 与 Rust checks；可移植 native 新增必须用 Rust，first-party C 禁止，`binder_interceptor.cpp` 是唯一 first-party C++ 例外。

Binder/XML/ZIP/CBOX/HTTP/path/PID 都视为不可信，必须显式限制，变化行为需要失败路径 regression tests。不要提交 private keys、keyboxes、tokens、device secrets、generated APK/ZIP，并同步用户文档。

<a id="donate"></a>
## Development Support

如果项目对你有帮助，可以通过官方 `DONATE.md` 中列出的 USDT TRC20、Monero XMR、USDT/USDC ERC20/BEP20、Binance User ID、PayPal、BuyMeACoffee 或项目作者网站支持开发。发送前请以英文 `DONATE.md` 的当前地址为准。

<a id="languages"></a>
## Language Support

WebUI 内置九种语言：English、Türkçe、简体中文、Español、Deutsch、Русский、Bahasa Indonesia、हिन्दी、العربية。Runtime locale catalog 只属于 `ux.js`，不创建 locale-specific JS/CSS runtime asset。用户 Markdown 文档通过九种 README 和这些集中式语言参考提供。

新的用户可见 Markdown 变化应同步更新英文 canonical 文档与相关本地化参考。技术冲突时以英文原文和源代码为准。

<a id="logging"></a>
## Logging and Diagnostics

CleveresTricky 不单独存储明文日志，诊断写入 Android logcat。常用命令是 `adb logcat -s cleverestricky CleveresTricky`。启动时可关注 service 欢迎、bridge/server、Binder interceptor 和 TEE registration 标记。

`TAMPER DETECTED`、Binder ABI validation failure、rejected keybox、injector timeout 需要处理。发布日志前请检查，因为文件名、包名、设备属性和 PID 仍可能敏感。

<a id="theme"></a>
## UI Theme

WebUI 使用极简 monochrome 的 Nothing OS / iOS hybrid 风格：深色 charcoal 背景、浅灰文字、银色 accent、深灰 panel、绿色 success 与红色 danger。使用系统 sans-serif、技术数据 monospace、Dynamic Island 通知、圆角按钮、iOS-style toggles 和 mobile-first responsive layout。

触控目标应约为 44px 或更高，优先垂直内容流，并针对 KernelSU/APatch 内的手机操作优化。
