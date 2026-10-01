# Play 上架材料（P6-3）— 🗄️ 已归档，不适用

> **⚠️ ARCHIVED（2026-10-01）**：发行渠道确定为**仅 GitHub Release**（不上架 Google Play），本文档不再适用，仅供历史查阅。P6-3 标记为「不做」。
>
> 依据：PRD §1（愿景/价值主张/成功指标）+ `docs/legal/privacy-policy.md`（数据安全口径）+ action-plan P6-3。
> 最后更新：2026-08-15（归档 2026-10-01）| 原状态：✅ store listing 文案；⏳ 截图 / feature graphic；⏳ 隐私政策托管 URL。
> 一致性约束：App 界面语言为中文（`res/values/strings.xml`），零网络请求、零运行时权限（Manifest 仅 `uses-feature usb.host required=false`），文案不得与代码/隐私政策冲突。

## 一、应用基本信息

| 项 | 值 |
|---|---|
| 应用名称 | CameraSync |
| 包名 | `dev.sebastiano.camerasync` |
| 版本 | v1.0.0（`gradle.properties` 单源，versionCode 1） |
| 类别 | 摄影（Photography） |
| 系统要求 | Android 13+（minSdk 33 / target 36），需 USB OTG 主机能力 |
| 支持相机 | Nikon 系列（MTP/PTP 模式；已验证 **Nikon Z30**） |
| 界面语言 | 中文（简体） |
| 隐私政策 URL | ⏳ 待托管（内容见 [`privacy-policy.md`](privacy-policy.md)，建议 GitHub Pages 静态托管） |
| 联系渠道 | GitHub Issues：<https://github.com/Chilfish/CameraSync/issues> |

## 二、短描述（≤ 80 字符）

**zh-CN**（主）：

> 插线即传 Nikon 相机照片：跨会话去重不重复导入，RAW+JPEG 成组传输，全程离线。

**en-US**（备选，多市场时启用）：

> Plug in and sync Nikon camera photos over USB: cross-session dedup, RAW+JPEG pairs, fully offline.

## 三、完整描述

### zh-CN（主）

> CameraSync 是一款面向 Nikon 相机用户的 **USB 有线照片同步 App**。插上 USB-C 线，把相机里的照片快速、可靠、可追溯地传到手机——不丢片、不错传、不重复传。

**主要功能**

- **插线即传** — USB 即插即用，MTP 标准协议，无需配对、无需 Wi-Fi
- **绝不重复导入** — 跨会话去重（MTP storageId + handle + 名称/大小软校验），重复插线不会重复导入
- **RAW + JPEG 成组** — NEF/JPG 自动成组展示与传输，支持「全部 / 仅 JPEG / 仅 RAW」下载格式
- **新照片优先** — 默认只显示新照片，一键全选传输，插线到传输 ≤ 3 次点击
- **EXIF 详情** — 快门、光圈、ISO、焦距、镜头等参数一目了然
- **本地相册** — 传输的照片自动归档到 `Pictures/CameraSync/{相机型号}/`，支持目录浏览、下拉刷新、EXIF 查看
- **传输管理** — 速度与 ETA 显示、失败重试、传输历史、本次传输清单回看、从相机删除照片
- **原生体验** — Material 3 设计、深色主题（跟随系统 / 浅色 / 深色）、渐进式照片加载、触觉反馈

**隐私与安全**

- **全程离线** — App 不发起任何网络请求，无广告、无统计 SDK、无账号
- **本地优先** — 照片与 EXIF 只保存在设备本地，不收集任何个人信息（详见隐私政策）

**使用前提**

- Android 13 及以上、支持 USB OTG 的设备
- Nikon 相机（需切换至 MTP/PTP 模式）；已验证：Nikon Z30

### en-US（备选，多市场时启用）

> CameraSync is a USB-tethered photo sync app for Nikon cameras. Plug in a USB-C cable to move photos from your camera to your phone — fast, reliable, and traceable: no lost shots, no mis-transfers, no duplicates.

**Features**

- Plug and sync — USB MTP, no pairing, no Wi-Fi
- Cross-session dedup (MTP storageId + handle + name/size soft check) — re-plugging never re-imports
- RAW + JPEG pairs (NEF/JPG) shown and transferred together; transfer all / JPEG only / RAW only
- New photos first — one tap to select and transfer all new photos (≤ 3 taps from plug-in to transfer)
- EXIF details — shutter, aperture, ISO, focal length, lens, and more
- Local album — photos archived to `Pictures/CameraSync/{camera model}/` with folder browsing and refresh
- Transfer management — speed & ETA, retry on failure, history, per-session summary, delete from camera
- Material 3, dark theme (system / light / dark), progressive photo loading, haptic feedback

**Privacy & security**

- Fully offline — no network requests, no ads, no analytics, no account
- Local-first — photos and EXIF stay on device; no personal data collected

**Requirements**

- Android 13+ with USB OTG support
- Nikon camera in MTP/PTP mode (verified: Nikon Z30)

## 四、版本更新说明（What's new，v1.0.0）

> zh-CN（主）：

> 首个正式版本：USB 有线照片同步、跨会话去重、RAW+JPEG 成组传输、EXIF 详情、本地相册、深色主题。

> en-US（备选）：

> First release: USB-tethered photo sync, cross-session dedup, RAW+JPEG pairs, EXIF details, local album, dark theme.

## 五、数据安全表单（Data Safety）

> 依据 [`privacy-policy.md`](privacy-policy.md) §四（零网络请求）+ Manifest（无权限声明）。填表口径：

| 问题 | 答案 |
|---|---|
| 是否收集或共享任何用户数据类型 | **否**（不收集任何数据类型，无传输/存储/设备标识数据） |
| 数据加密 / 数据删除请求 | 不适用（无数据被收集） |
| 广告 / 分析 / 崩溃上报 SDK | 无（不含任何第三方 SDK） |
| 网络请求 | 无（USB 有线同步全程离线） |

> ⚠️ 若未来引入崩溃上报或任何网络能力，必须先修订隐私政策 + 本表（隐私政策 §六 变更条款）。

## 六、内容分级

- 按 Play Console 内容分级问卷自评，预期 **Everyone**（无暴力 / 成人内容 / 用户生成内容分发，仅展示用户自己的照片）。
- 最终以 Play Console 问卷提交结果为准。

## 七、素材清单

| 素材 | 规格 | 状态 |
|---|---|---|
| 应用图标 | 已有 `@mipmap/ic_launcher` | ✅ |
| 截图（建议 6–8 张，zh-CN 界面） | 冷启动引导 / 画廊网格 / 筛选与分组 / 传输中（速度+ETA）/ 完成回看（本次传输清单）/ EXIF 详情 / 本地相册 / 设置 | ⏳ 待 Nikon Z30 真机 |
| Feature graphic | 1024×500 | ⏳ 待设计 |
| 隐私政策 URL | 静态托管 | ⏳ 待托管 |
| 商店文案 | 本文档 | ✅ |

## 八、上架前核对清单

- [ ] 真机回归通过（action-plan P3-3 + `docs/engineering/release-checklist.md` 全项）
- [ ] `bash scripts/release.sh 1.0.0` 发版（门禁全绿后 commit + tag + push，CI 构建签名 APK）
- [ ] Play Console：创建应用 → 填基本信息 → 粘贴本文档文案 → 上传素材 → 提交 Data Safety 表单 → 内容分级问卷
- [ ] 复查一致性：商店文案 ↔ PRD 功能 ↔ 隐私政策 ↔ 实际 UI（postmortem 001 教训：文档与代码单一事实源）
