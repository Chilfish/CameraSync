# 无线传输（WiFi/PTP-IP）调研与分期方案

> 立项：2026-10-01（v1.0.0 之后的新里程碑）。决策见 [ADR-011](architecture.md#adr-011-抽离传输抽象-camerasource无线方向定为-wifiptp-ip事件驱动)。
> 本文记录方向调研结论与分期计划；**Phase 0 为纯重构，不改 USB 行为**。

## 1. 结论速览

- **照片传输走 WiFi（PTP/IP），不走蓝牙**。BLE 实际吞吐仅几十 KB/s，仅用于握手激活、保活与状态；一张 25MB NEF 走 BLE 需数分钟，不可用。
- **Z30 无 FTP**。FTP 上传是 Z8/Z9/Zf 的能力；Z30 官方无线路径只有 SnapBridge（智能设备）与「连接至计算机」（Wireless Transmitter Utility）。
- **Z 系列激活 WiFi 的 PTP-IP 服务可能需要 BLE 握手**（NikonLink 实测：Z5 的 15740 端口在 WiFi 开启后不响应，需先做 BLE GATT 连接）。因此蓝牙在本项目中的定位是「钥匙」，不是「管道」。
- **业界实现「拍完自动传」的标准做法是事件驱动**：保持连接 + 监听相机事件通道的 `ObjectAdded`（0x4002），而非轮询，也不是相机主动推。
- **「相机主动推」在 Z30 上只有冒充 WTU 接收端（配对 + 认证码）或逆向 SnapBridge 两条路，成本高且无先例**——降级为后续可选项。

## 2. Z30 的能力（官方手册）

- **无线模式**：
  - **AP 模式（相机热点）**：相机显示 SSID + 密码，手机连入；**手机连上后失去外网**。
  - **基础设施 / STA 模式**：相机加入路由器或手机热点；手机可保持外网。Nikon 对 STA 有「信任主机」门控，**首次须注册一次**否则握手被拒。
- **智能设备（SnapBridge）**：BLE 常连，自动传 200 万像素小图；原图/大文件自动切 WiFi。
- **连接至计算机（WTU）**：相机 → PC 推送，支持「拍完自动上传」，需配对 + 认证码。这是 Z30 唯一的真·相机主动推。
- Z30 支持 USB/MTP（现有唯一路径）。

## 3. PTP/IP 协议要点（Phase 1 的实现基础）

- **ISO 15740 over TCP，端口 15740**。两条 TCP 连接：**命令/数据通道**（主机发起，双工数据分段）+ **事件通道**（相机上报）。
- 包类型（Init_Command_Request/Ack、Init_Event_Request/Ack、Init_Fail、Cmd_Request/Response、Event、Start/Data/End_Data_Packet、Ping/Pong）有公开逆向文档。
- 常用操作码：`ObjectAdded` 事件 `0x4002`、`GetObject` `0x1009`、`GetPartialObject` `0x101B`（只读文件头，避免整份 NEF）、批量元数据 `0x9805`、`GetThumb` `0x100A`。
- **工程雷区**（各实现均踩过）：
  - Android「智能网络切换」会把 `192.168.1.1` 的流量送去移动数据 → socket 必须 `Network.bindSocket()` 绑定 WiFi。
  - 事件通道需心跳（约每 8s 一个 `PKT_PROBE_REQ`），否则相机 ~10s 空闲主动断连。
  - Nikon STA 需先注册主机：`PrepareHost 0x952B` / `ConfirmHost 0x935A`。
  - 相机只有一个 PTP/IP 客户端槽位，被占用时需提示用户断开其他设备。

## 4. 竞品速览（2026-07 起密集爆发）

| 产品 | 形态 | 要点 |
|---|---|---|
| SnapBridge（官方） | iOS+安卓 | BLE 常连 + 自动 2MP；原图切 WiFi；连接稳定性被诟病 |
| NX Mobile Air / 影速传（官方） | 安卓 | USB 或 FTP 批量高速；1.6.0 起免费 |
| **ZRelay** | 安卓，免费+Pro | 老款 Z 走 5GHz；实时取景/全参数遥控；未上架 |
| **ZTransfer / Z传** | 安卓，分级订阅 | NEF/JPEG/视频、>4GB 分块续传、滤镜水印 |
| **ZineControl** | iOS+安卓（Play） | 面向电影工作流，实时监看 + 遥控 |
| **N-Link** | 安卓，开源 | 「永不断联」：BLE 心跳 + 前台服务 + <3s 重连；三通道；`ObjectAddedInSDRAM` 逐张入册 |
| **IkunBridge** | 安卓，开源 | 专做 Z30：`192.168.1.1:15740`、事件监听自动下载 |
| **NikonLink** | 安卓，开源 | BLE 激活 PTP-IP + 回退直连；记录 Z5 BLE/端口关键发现 |
| **Camera_Bridge** | 安卓，开源 | WiFi PTP/IP + USB MTP 双通道；前台服务防热点休眠 |
| **AeroShutter** | Go TUI + 移动端 | ObjectAdded 自动导入、4MiB 分块续传、保留蜂窝数据 |
| Zensō 远映 | iOS，买断 | Z 全系、后台保持、拍完自动传、GPS 写 EXIF |

**共性**：均需网络/定位/前台服务权限，且多为「遥控 + 监看 + 传图」全家桶。**CameraSync 的差异化仍是单一用途、本地优先、USB 原生。**

## 5. 分期计划

### Phase 0 —— 传输抽象（本次，纯重构，USB 行为不变）

- 新增 `camera/` 包：`CameraSource` 接口 + 传输无关模型（`CameraInfo` / `StorageInfo` / `PhotoInfo` / `FolderInfo`）。
- `NikonUsbManager` → `UsbCameraSource`（实现 `CameraSource`）。
- `ConnectionManager` / `ThumbnailProvider` / `TransferEngine` / `GalleryViewModel` 改为依赖 `CameraSource`，不再出现 `android.mtp.MtpDevice`。
- 新增 `FakeCameraSource`（Fakes over Mocks）。
- **验收**：USB 全链路行为与 `v1.0.0` 一致；`testDebugUnitTest` 全绿；detekt/ktfmt 通过。

### Phase 1 —— 手动 WiFi（PTP/IP）

- 新增 `WifiCameraSource : CameraSource`：TCP 15740 双通道 + 握手 + 心跳。
- BLE 握手激活（如需）+ AP/STA 连接引导；socket 绑定 WiFi 网络。
- 复用现有 UI / 去重 / 传输历史；浏览器与传输路径不改。
- **前置**：新 ADR（网络权限 + 前台服务的产品身份变更）+ 隐私政策更新。

### Phase 2 —— 自动传（事件驱动，可选相机推送）

- 前台服务常驻 + 监听 `ObjectAdded` → 新照片自动入库。
- 可选：Z8/Z9/Zf 走 FTP；Z30 若要走真·相机推送，需实现 WTU 接收端（配对 + 认证码）。
