# Z-Flow 技术方案与系统架构设计文档 (V1.0)

## 1. 项目概述
### 1.1 目标
在 Android 平台上复刻并优化尼康官方 `Wireless Transmitter Utility` 的功能，实现 Z30 相机与手机之间的静默、自动化、高性能图片传输。

### 1.2 核心场景
- **即拍即传**：摄影师在 Z30 上按下快门，图片在 2 秒内静默传输至手机后台。
- **自动化连接**：用户开启手机热点后，App 自动唤醒并完成与相机的 PTP/IP 握手。
- **极速预览**：App 内置高性能缓存，提供优于系统相册的 RAW/JPG 浏览体验。

---

## 2. 需求分析 (Requirement Analysis)

### 2.1 功能需求
- **RF-01 (配对模块)**：模拟 WTU 的 8 位数字验证码配对协议，生成并交换永久识别 GUID。
- **RF-02 (网络监听)**：在局域网（通常是手机热点）内监听相机的连接请求。
- **RF-03 (自动接收)**：支持相机“自动发送”模式，实时处理相机推送到 PTP 堆栈的图片。
- **RF-04 (背景运行)**：利用 Android Foreground Service 保持连接，防止内存回收导致同步中断。
- **RF-05 (文件管理)**：支持按日期自动分包，并提供 JPEG 预览图与 RAW 原始文件的分类存储。

### 2.2 非功能需求

- **性能**：单张全尺寸 JPEG (约 8-10MB) 传输时间在 5GHz 下需低于 1.5s。
- **稳定性**：断连后（如相机待机进入睡眠）需支持蓝牙唤醒或心跳包自动重连。

---

## 3. 技术架构 (Technical Architecture)

### 3.1 协议栈 (Protocol Stack)
本应用基于 **PTP/IP (ISO 15740-4)** 标准协议，并针对尼康私有握手进行封装。

- **传输层**：TCP/IP (默认端口: 15740)
- **网络层模式**：Infrastructure Mode (基础设施模式)。手机开启 Hotspot，相机作为 Client。
- **应用层**：PTP/IP Responder (手机端实现)

### 3.2 软件层级
1.  **Transport Layer (JNI/NDK)**：基于 `libgphoto2` 的 C++ 核心，负责底层 Socket 通信与 PTP 事件轮询。
2.  **Protocol Wrapper (Kotlin)**：将 PTP 操作代码（如 `GetDeviceInfo`, `GetObject`, `InitiateCapture`）封装为 RxJava 或 Coroutines 流。
3.  **Core Service (Android Service)**：管理全局唯一的 TCP 长连接，处理生命周期。
4.  **Data Layer (SQLite/Room)**：存储图片元数据（EXIF/缩略图索引），实现快速检索。
5.  **UI Layer (Jetpack Compose)**：Material 3 风格的极简界面。

---

## 4. 核心工作流设计 (Core Workflow)

### 4.1 初始化配对流程 (Initial Pairing)
1.  **用户侧**：相机进入“连接到计算机”方案，点击“配对”。
2.  **App 侧**：启动发现服务，监听 UDP 端口。
3.  **握手**：
    - 相机发送 `Init_Command_Request` 包含相机主机名。
    - App 回应其生成的 `GUID`。
    - 相机屏幕显示 8 位验证码。
    - 用户在 App 输入验证码，完成 `MD5-based` 质问响应认证。
4.  **持久化**：App 存储相机的 GUID 与 IP，后续连接跳过此步。

### 4.2 自动化同步生产线 (Sync Pipeline)
1.  **事件触发**：相机按下快门，生成新文件。
2.  **PTP 事件信令**：相机发出 `Event_ObjectAdded (0x4002)`。
3.  **获取数据**：
    - App 调用 `GetPartialObject` (仅获取头部 EXIF 确认类型)。
    - App 调用 `GetObject` 获取完整文件流。
4.  **存储与反传**：写入 `Scoped Storage`，下载完成后向相机返回 `Response_OK`。

---

## 5. 关键技术难点与对策

| 技术难点 | 对策/方案 |
| :--- | :--- |
| **Android 后台限制** | 采用 `Foreground Service` + `Persistent Notification`。申请忽略电池优化权限。 |
| **PTP/IP 握手黑盒** | 参照尼康公开的 Remote SDK (PC版) 逻辑，并参考 `qDslrDashboard` 开源项目的私有 OpCode 映射表。 |
| **网络波动** | 实现指数退避重连机制 (Exponential Backoff)，并在 TCP 层开启 `KeepAlive` 探测相机心跳。 |
| **大文件缩略图** | 使用 `Glide` 或 `Coil` 进行二级缓存，利用 `HEIF` 格式在手机端生成极小预览包以便无限滑动不卡顿。 |

---

## 6. 数据存储方案

- **存储路径**：`/Pictures/Z-Flow/[Camera_Name]/[Date]/`
- **元数据**：
  - `ImageID` (PTP ObjectHandle)
  - `SyncStatus` (Pending/Success/Failed)
  - `EXIF_Snapshot` (ISO, Aperture, Shutter)

---

## 7. 开发里程碑 (Milestones)

- **Phase 1 (MVP)**：完成 TCP 15740 端口的基本连接与手动配对。
- **Phase 2 (Sync)**：实现 `ObjectAdded` 监听，图片能自动保存到 `MediaBuffer`。
- **Phase 3 (Optimization)**：加入热点自动切换逻辑与蓝牙辅助唤醒功能。
- **Phase 4 (Gallery)**：完成内置专业画廊，支持直方图显示与 RAW 预览。

---

## 8. 附录：Z30 特有配置参考
- **SSID 设置**：需引导用户将 Z30 设置为客户端模式。
- **端口配置**：固定使用 15740 端口作为 PTP 控制流，数据流采用动态端口（由 PTP/IP 协议头协商决定）。
