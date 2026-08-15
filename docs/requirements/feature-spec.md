# 功能规格说明书

**项目**: CameraSync | **版本**: 1.0 | **最后更新**: 2026-08-15

---

## F-USB-01: USB 连接与权限

**优先级**: P0 | **所属**: USB 照片同步

### 概述

相机插入 USB-C 后，系统广播 `USB_DEVICE_ATTACHED`，App 请求权限并打开 MTP 会话。

### 流程

```
USB_DEVICE_ATTACHED broadcast
  → UsbManager.requestPermission(device)   # PendingIntent + BroadcastReceiver
  → 用户授权
  → UsbDeviceConnection = UsbManager.openDevice(device)
  → MtpDevice.open(connection)
  → 就绪（可枚举存储与对象）
```

### 边界情况

| 场景 | 处理 |
|---|---|
| 相机处于非 MTP/PTP 模式 | 连接失败，引导页说明切换模式（`FirstRunGuideScreen`） |
| 用户拒绝权限 | 停留在 Disconnected，可再次触发请求 |
| 传输中拔出相机 | 状态机回到 Disconnected，已保存文件不受影响 |

---

## F-USB-02: 浏览与筛选

**优先级**: P0

### 概述

存储/文件夹 BFS 遍历，网格画廊 + 文件夹导航；筛选芯片（全部/新照片/RAW/JPEG，默认新照片）、三种分组（文件夹/日期/不分组）、五种排序、网格密度（2/3/4 列）。

### 状态机

```
Disconnected → Connecting → Loading → Browsing (folders/photos)
                                     → Empty (无照片)
                                     → Error (连接/枚举失败，可重试)
Browsing → Transferring (synced=N, total=M, currentFile)
         → TransferDone (synced=N)
```

### 边界情况

| 场景 | 处理 |
|---|---|
| 存储为空 | 显示 Empty 状态 |
| 枚举超时/失败 | Error 状态 + 重试入口 |
| 大量照片 | 渐进加载：先 30 张，后台继续枚举 |

---

## F-USB-03: 选择与传输

**优先级**: P0

### 概述

长按多选或一键全选新照片，确认后批量传输：`MtpDevice.importFile` → EXIF 读取 → MediaStore `IS_PENDING=1` 写入 → 置 0 发布 → 删临时文件 → 标记已导入。

### 交互细节

1. 主 CTA「传输全部新照片 (N)」一键全选新照片 → 预览确认
2. 传输中显示进度（synced/total + 当前文件名 + 速度/ETA）
3. 完成面板：结果统计 + 本次传输清单（可点开定位）+ 触觉反馈

### 边界情况

| 场景 | 处理 |
|---|---|
| 单文件失败 | 标记失败，可重试（只重传失败项） |
| 用户取消 | 停止剩余传输，已保存文件保留 |
| MediaStore 写入失败 | 清理临时文件，报错不静默 |

---

## F-USB-04: 跨会话去重

**优先级**: P0

### 概述

`PhotoSyncManager` 用 SharedPreferences 记录 `s{storageId}_h{handle}` 键 + `name:size` 身份软校验。

### 行为

- 键命中且身份匹配 → 已导入，跳过
- 键命中但身份不匹配（MTP handle 复用给新照片）→ **视为未导入**
- MTP handle 会话级失效 → 旧键自然不匹配，无需「重连清空」

### 边界情况

| 场景 | 处理 |
|---|---|
| 相机断开重连 | 去重依然生效（键含 storageId） |
| 同名同大小新照片 | 身份相同视为已导入（可接受，非目标场景） |
| 升级旧版本 boolean 键 | 键格式变化触发一次全量重传（dev 阶段可接受） |

---

## F-USB-05: 传输完成回看与历史

**优先级**: P1

- 完成面板「本次传输清单」：缩略图 + MediaStore 名称，点击系统相册定位；下拉关闭返回完成面板
- `TransferHistoryScreen`：历史按时间倒序，显示导入数量与时间

---

## F-LOCAL-01: 本地相册

**优先级**: P1

- Coil 3 加载（`SingletonImageLoader.Factory`）+ MediaStore 查询（Android 13+ 分区存储）
- 目录浏览 + 面包屑导航，仿 USB 文件夹交互；下拉刷新；本地 EXIF 详情面板

---

## F-SET-01: 设置

**优先级**: P1

- 分组（文件夹/日期/不分组）、排序（最新优先/名称/大小等）、下载格式（全部/仅 JPEG/仅 RAW）、主题三态、网格密度、使用说明入口
- 所有偏好经 `UsbSyncPreferences` 持久化

---

## 测试要点

- [ ] 正常传输流程（新照片全选 → 确认 → 完成清单）
- [ ] 去重：重复插线不重复导入；handle 复用不误判（软校验）
- [ ] 筛选/分组/排序组合（多状态 Preview 各一）
- [ ] 传输失败重试、取消、MediaStore 失败路径
- [ ] 状态机迁移 `Disconnected → … → TransferDone`（`GalleryStateMachine` 单测）
- [ ] 真机（Nikon Z30）：连接、枚举、传输、断线重连
