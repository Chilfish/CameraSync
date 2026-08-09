# CameraSync 后续行动计划 & 开发步骤

> **依据**: [`docs/review/2026-08-09-design-review.md`](../review/2026-08-09-design-review.md)（Apple 视角设计评审）
> **最后更新**: 2026-08-09 | **原则**: docs-first；先修复再新功能；先写 commit message 再写代码；每 commit 本地跑 detekt + ktfmtCheck

---

## 一、目标

把核心使命「插线传照片」做到 99.9% 可靠：

- **绝不错传** — 去重键一致（R2）
- **绝不丢片** — 剪枝真实化，不留假注释（R3）
- **单一管线** — 一条数据通路，一个所有者（R1）
- 功能做减法，聚焦冷启动与默认主路径（R5）

## 二、排序

**P0 止血 → P1 核心路径 → P2 工程债 → P3 运营收尾**。P0 阻塞发布，其余按序推进。

---

## P0 — 正确性止血 ✅（2026-08-09 完成）

> 4 个原子 commit 已落地，每项 gate（ktfmt + detekt + compileDebugKotlin）本地验证通过。

### P0-1 统一保存路径 ✅ `2961280`

- **Commit**: `fix(usb): use camera model in MediaStore save path`
- **证据**: `GalleryViewModel.kt:1034` / `UsbSyncCoordinator.kt:156` 写死 `"Pictures/CameraSync/Nikon Z30"`
- **动作**: 两处改用 `cameraInfo.model`（fallback "Nikon"）；`strings.xml` 两条写死设备名的文案改通用
- **结果**: 目录名随模型变化，不再写死 "Nikon Z30"

### P0-2 统一去重键 ✅ `b75e87b`

- **Commit**: `fix(usb): unify dedup key across UI and background pipelines`
- **证据**: `GalleryViewModel.kt:781, 851, 904, 921, 957` 硬编码 `storageId=0` vs `UsbSyncCoordinator.kt` 用真实 `storage.id`
- **动作**: `PhotoInfo` 增加 `storageId`；两条管线去重判定一致（顺带清理 1 条 detekt baseline）
- **结果**: 前台 UI 与后台同步对同一照片判定一致

### P0-3 收敛双 MTP 管线 ✅ `9344686`（阶段1；阶段2 降级至 P2-1）

- **Commit**: `refactor(usb): guard MTP session against concurrent open`
- **证据**: `GalleryViewModel.kt:142` / `UsbSyncService.kt:60` 各自 new `NikonUsbManager`
- **动作（实施后调整）**:
  1. ✅ 护栏：`NikonUsbManager` 进程级 `hasActiveSession`（open 置位 / close 复位）；`UsbSyncCoordinator.syncOnce()` 前台会话期间跳过
  2. ⏸ 阶段2（共享 `NikonUsbManager` 实例）降级至 P2-1：实施中发现后台管线**当前未接线**（见 P1-4），并发风险为潜在而非实发；共享实例需 DI 改造 + 核心路径重构，收益当前为零，并入拆 God Object 一并处理
- **结果**: 任一时刻仅一个 `MtpDevice` open；未来接线自动同步默认安全

### P0-4 剪枝真实化 ✅ `220aa12`

- **Commit**: `fix(usb): validate photo identity when checking dedup`
- **证据**: `PhotoSyncManager` 注释声称自动剪枝，`clearAll()`/`clearStorage()` 零调用
- **动作**: 假注释的"自动剪枝"改为**软校验**——dedup 存 `name:size` 身份，handle 复用给新照片时身份不匹配 → 视为未导入。比"重连清空"保留跨会话去重，比"全量枚举剪枝"不破坏文件夹渐进浏览
- **结果**: 不再静默丢片；注释与代码一致（旧 boolean 键升级后失效，触发一次全量重传，dev 阶段可接受）

---

## P1 — 核心路径 Apple 级（产品，3–5 天）✅（2026-08-09 完成）

### P1-1 冷启动一屏引导 ✅ `61fc9a7`

- **Commit**: `feat(usb): add first-run MTP mode guide`
- **动作**: 首次启动一屏说明 ① 相机需切到 MTP/PTP 模式 ② USB 权限 ③ 插线即同步。不做 3 屏 onboarding；冷启动压栈 `FirstRunGuide`（`prefs.guideSeen` 持久化，不再打扰）；设置页新增「使用说明」入口，落地原先的空 stub（顺带消 baseline `UnusedParameter`）
- **验收**: ✅ 新用户首次进入看到引导；引导后不再打扰

### P1-2 新照片成为默认主路径 ✅ `97f7c8e`

- **Commit**: `feat(usb): make new-photos the default view`
- **动作**: `filterMode` 默认 `NEW`（init + 移除 `loadRoot` 里按 downloadFormat 重置）；主 CTA「传输全部新照片 (N)」一键全选新照片 → 预览确认；分组/排序/网格密度收进顶栏溢出菜单（原网格密度图标移除）；downloadFormat 与默认视图解耦（仅控制传输格式，见 `handlesForFormat`）
- **验收**: ✅ 插线 → CTA → 确认，≤3 次点击

### P1-3 传输完成可回看 ✅ `f6d2f9b`

- **Commit**: `feat(usb): show per-session transfer summary`
- **动作**: 传输完成面板新增「本次传输清单」——列出本次保存的全部文件（缩略图 + MediaStore 名称），点击在系统相册打开定位；在清单内下拉关闭返回完成面板而非整个 dismiss
- **验收**: ✅ 传输完成后能追溯到本次传输的文件

### P1-4 自动同步 → 移除死代码 ✅ `6a1c331`

- **Commit**: `refactor(usb): remove unwired auto-sync pipeline`
- **证据**: `UsbSyncService.createStartIntent`/`ACTION_SYNC` **零调用**；`SettingsScreen` 的 auto-sync 开关写 `prefs.autoSyncEnabled` 但无消费者（评审 R7）
- **动作**: 产品决策 = **移除（YAGNI）**。删 `UsbSyncService`/`UsbSyncCoordinator`、无效开关、`autoSyncEnabled`/`autoSyncFlow`、同步通知 channel、Manifest service + 3 条前台/通知权限、`NikonUsbManager.hasActiveSession`（唯一读方是 Coordinator，删后成 write-only）、4 条死字符串、5 条 detekt baseline 条目
- **验收**: ✅ 设置页不再出现无效开关；自动同步彻底移除

---

## P2 — 工程债（2026-08-09 实施中）

### P2-1 拆分 God Object（R6）

- **Headline**: `refactor(usb): split GalleryViewModel into focused modules`
- **动作**: `GalleryViewModel`（1105 行）→ 4 个原子 commit 顺序抽取，**行为不变（纯搬移）**，`GalleryViewModel` 收敛为门面（保留全部公共 API，GalleryScreen/PhotoDetailSheet 零改动）：
  1. `refactor(usb): extract GalleryStateMachine from GalleryViewModel` — sealed state + 筛选/排序/分组/选择纯逻辑（最可测）
  2. `refactor(usb): extract ThumbnailProvider from GalleryViewModel` — 四类缓存 + EXIF 方向
  3. `refactor(usb): extract TransferEngine from GalleryViewModel` — 传输编排（含 MediaStore 保存）
  4. `refactor(usb): extract ConnectionManager from GalleryViewModel` — USB 生命周期 + 浏览/枚举
- **验收**: 行为不变；`LargeClass:GalleryViewModel` baseline 条目随拆分消除

### P2-2 核心路径补单测（R6）

- **Headline**: `test(usb): add dedup and state machine tests`
- **动作**（遵循 CLAUDE.md「Fakes over Mocks」+ Dispatcher 注入）:
  1. `fix(usb): use injected dispatcher in LocalPhotosViewModel scope` — 修 6 个既有失败的根因之一（scope 硬编码 `Dispatchers.IO`）
  2. `test(usb): fix LocalPhotosViewModel tests for plain JVM` — Uri/ContentUris 静态 mock、mockk Cursor 替代 MatrixCursor、删死代码 `baseDir`（连带去掉 Environment mock）、修 package 声明
  3. `test(usb): add PhotoSyncManager dedup tests` — 跨会话剪枝、storageId 一致性（P0-2 前置）；`PhotoSyncManager` 构造注入 `SharedPreferences` 以便用内存 fake
  4. `test(usb): add GalleryStateMachine transition tests` — `Disconnected → Connecting → Loading → Browsing/Empty/Error → Transferring → TransferDone` + 筛选/排序/选择
  5. `test(usb): add TransferEngine failure tests` — 失败重试、取消、MediaStore 保存失败路径
- **验收**: 核心路径单测覆盖，`testDebugUnitTest` 全绿

### P2-3 还清 detekt baseline（todo.md 原 P2）

- **Commit**: `chore: repay detekt baseline debt`
- **动作**: 17 条 → 0，逐步修复后从 `detekt-baseline.xml` 移除对应条目（含删死桩 `getBatteryLevel`/`UnusedParameter`、`TransferRecord` 独立文件、NestedBlockDepth 重构、ComplexCondition 提取局部变量等）
- **验收**: `detekt` 无 baseline 吸收全绿

---

## P3 — 运营收尾（半天）

| # | 事项 | 说明 |
|---|---|---|
| 3-1 | 确认测试设备 | `USB_SYNC.md` §9（Xiaomi MIUI）vs README（Nikon Z30）统一回填（todo.md 原 P1） |
| 3-2 | 推送 & 验证 CI | 推送本地未推送 commit；确认 `ktfmtCheck` / `detekt` / `lint` / `test` / `assembleDebug` 全绿（todo.md 原 P3） |
| 3-3 | 真机回归 | Nikon Z30 连接验证 MTP 同步链路（P0 改动后必做） |

---

## 四、明确不做（Apple 减法）

| 项 | 原因 |
|---|---|
| 云备份（Google Photos / Dropbox） | 另一个产品的命题；solo 项目是陷阱 |
| 视频文件支持 | 大文件 + 不同 MTP 处理；非核心使命 |
| 多相机并发 USB | Android 平台硬限制（仅支持一个 USB host 设备） |
| Wi-Fi 传输 | Z30 缺 infra 模式；有线是差异化卖点 |

---

## 五、验收总览

| ID | 主题 | 对应 review | 验收标准 |
|---|---|---|---|
| P0-1 | 保存路径真实化 | R4 | ✅ 目录名随模型变化（`2961280`） |
| P0-2 | 去重键一致 | R2 | ✅ 双管线判定一致（`b75e87b`） |
| P0-3 | 单 MTP 管线 | R1 | ✅ 阶段1 护栏单 MtpDevice open（`9344686`） |
| P0-4 | 剪枝真实化 | R3 | ✅ 软校验不误判，注释与代码一致（`220aa12`） |
| P1-1 | 冷启动引导 | R5 | ✅ 新用户看到引导（`61fc9a7`） |
| P1-2 | 新照片默认主路径 | R5 | ✅ 插线→传输 ≤3 次点击（`97f7c8e`） |
| P1-3 | 传输回看 | R5 | ✅ 可追溯本次传输（`f6d2f9b`） |
| P1-4 | 自动同步决策 | R7 | ✅ 移除无效开关 + 死代码（`6a1c331`） |
| P2-1 | 拆 God Object | R6 | 纯搬移无行为变化 |
| P2-2 | 核心单测 | R6 | testDebugUnitTest 通过 |
| P2-3 | detekt 归零 | R6 | baseline 空 |
| P3 | 运营收尾 | — | 设备统一 + CI 全绿 + 真机回归 |
