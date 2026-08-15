# 产品需求文档 (PRD)

**项目**: CameraSync | **版本**: 2.0（取代 `docs/archive/PRD.md`） | **日期**: 2026-08-15 | **作者**: Chilfish

## 1. 产品概述

### 1.1 产品愿景

CameraSync 是一款面向 Nikon 系列相机用户的**有线 USB 照片同步 App**。插上 USB-C 线，把相机里的照片快速、可靠、可追溯地传到手机——不丢片、不错传、不重复传。

### 1.2 核心价值主张

- **插线即传** — USB 即插即用，MTP 标准协议，无需配对、无需 Wi-Fi
- **绝不错传 / 绝不丢片** — 跨会话去重（`storageId + handle` + `name:size` 身份软校验），重复插线不重复导入
- **原生 Android 体验** — Material 3 + 深色主题 + 渐进加载，本地优先、无网络依赖
- **RAW 友好** — NEF/JPEG 成组展示与传输，EXIF 详情可查

### 1.3 目标用户

| 用户画像 | 场景 | 核心需求 |
|---|---|---|
| Nikon 相机用户（Z 系列等） | 旅行/活动后把照片导到手机 | 快速传输、RAW+JPEG 一起拿 |
| 轻度后期用户 | 手机上看片、挑片 | 新照片快速定位、EXIF 查看 |
| 无电脑用户 | 出门在外需要备份相机照片 | 有线直传，不依赖网络/电脑 |

### 1.4 竞品分析

| 方案 | 优势 | 劣势 |
|---|---|---|
| 相机官方 App（Nikon SnapBridge） | 官方支持、无线 | Wi-Fi 慢、配对繁琐、不稳定 |
| 读卡器 + 文件管理器 | 通用 | 需额外硬件、无 RAW/EXIF 体验 |
| Google Files / USB OTG 浏览 | 系统自带 | 无去重、无分组、无传输管理 |
| **CameraSync** | 有线快、跨会话去重、RAW+JPEG 分组、EXIF | 仅 Nikon（当前）、需 USB-C 线 |

### 1.5 成功指标

| 指标 | 目标 |
|---|---|
| 传输可靠性 | 不丢片、不错传、重复插线零重复导入 |
| 核心路径耗时 | 插线 → 全选新照片 → 传输 ≤ 3 次点击 |
| 传输速度 | 充分利用 MTP 带宽，无阻塞式预加载 |
| 崩溃可观测 | release 日志本地落盘，零自动上报（隐私） |

## 2. 功能需求

### 2.1 已实现（v1.0.0 / 内部 v2.3）

| ID | 功能 | 优先级 |
|---|---|---|
| F-USB-01 | USB MTP 连接与权限（PendingIntent + BroadcastReceiver） | P0 |
| F-USB-02 | 存储/文件夹 BFS 遍历 + 网格画廊 + 文件夹导航 | P0 |
| F-USB-03 | 筛选（全部/新照片/RAW/JPEG，默认新照片）+ 分组 + 排序 | P0 |
| F-USB-04 | 选择与批量传输（MediaStore IS_PENDING + `Pictures/CameraSync/{模型}/`） | P0 |
| F-USB-05 | 跨会话去重（软校验，handle 复用不误判） | P0 |
| F-USB-06 | 传输完成回看（本次传输清单）与失败重试 | P0 |
| F-USB-07 | 传输历史、从相机删除、EXIF 详情、RAW+JPEG 分组 | P1 |
| F-USB-08 | 冷启动 MTP 模式引导 | P1 |
| F-LOCAL-01 | 本地相册（Coil 3 + MediaStore + 目录浏览 + EXIF） | P1 |
| F-SET-01 | 设置（分组/排序/下载格式/主题/网格密度/使用说明） | P1 |

### 2.2 Backlog（未排期）

| ID | 功能 | 优先级 | 说明 |
|---|---|---|---|
| F-BL-01 | 视频文件支持 | P2 | 大文件 + 不同 MTP 处理，非核心使命 |
| F-BL-02 | NEF 内嵌 JPEG 预览（Coil 自定义 Fetcher） | P2 | 当前 NEF 用灰色占位符 |
| F-BL-03 | 云备份集成（Google Photos / Dropbox） | P3 | 另一个产品的命题，明确不做 |
| F-BL-04 | 多相机并发 USB | P3 | Android 仅支持一个 USB host 设备，硬限制 |
| F-BL-05 | Wi-Fi 传输 | P3 | Z30 缺 infra 模式；有线是差异化卖点 |

## 3. 非功能需求

### 3.1 性能

- 冷启动直达主路径（无 3 屏 onboarding 阻塞）
- 照片渐进加载：先显示 30 张，后台继续枚举
- 传输不阻塞 UI（协程 + Dispatcher 注入）

### 3.2 安全与隐私

- 照片与 EXIF 仅保存在本机 MediaStore，**零网络上报**
- 不收集任何 PII，无广告/统计 SDK（见 `docs/legal/privacy-policy.md`）
- release 日志只落本地，导出时脱敏

### 3.3 可访问性

- TalkBack 语义标注（contentDescription）
- 最小触摸目标 48dp、触觉反馈

### 3.4 兼容性

- Min SDK: 33 (Android 13) | Target SDK: 36
- 支持设备：Nikon 系列（MTP/PTP，已验证 Z30）
- 保存路径：`Pictures/CameraSync/{cameraModel}/YYYY-MM-DD/`
