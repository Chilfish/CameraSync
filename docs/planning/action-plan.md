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

## P1 — 核心路径 Apple 级（产品，3–5 天）

### P1-1 冷启动一屏引导（R5 / PRD P4）

- **Commit**: `feat(usb): add first-run MTP mode guide`
- **动作**: 解决已验证痛点 P4——首次启动一屏说明：① 相机需切到 MTP/PTP 模式 ② USB 权限 ③ 插线即同步。不做 3 屏 onboarding；`MainActivity.kt:136` 空回调 stub 落地或删除
- **验收**: 新用户首次进入看到引导；引导后不再打扰

### P1-2 新照片成为默认主路径（R5）

- **Commit**: `feat(usb): make new-photos the default view`
- **动作**: 连接后默认落在「新照片」视图；主 CTA「传输全部新照片 (N)」；分组/排序/网格密度收进溢出菜单（Apple 减法）
- **验收**: 从插线到首次传输 ≤ 3 次点击

### P1-3 传输完成可回看

- **Commit**: `feat(usb): show per-session transfer summary`
- **动作**: 传输完成面板补「本次传输清单」入口（已传输的照片可回到相册定位）
- **验收**: 传输完成后能追溯到本次传输的文件

### P1-4 自动同步接线或删除（实施新发现 R7）

- **Commit**: 待定（接线：`feat(usb): wire auto-sync to USB attach`；或移除：`refactor(usb): remove unwired auto-sync pipeline`）
- **证据**: `UsbSyncService.createStartIntent`/`ACTION_SYNC` **零调用**；`SettingsScreen` 的 auto-sync 开关写 `prefs.autoSyncEnabled` 但该值无消费者；`todo.md` 此前却宣称"后台自动同步 ✅"
- **动作**: 产品决策二选一——① 接线：USB attach 时（App 未前台）启动 service 自动同步；② 移除开关 + `UsbSyncService`/`UsbSyncCoordinator` 死代码（YAGNI）。**当前开关是无效开关，属"注释撒谎"家族**
- **验收**: 自动同步要么真实可用，要么彻底移除，设置页不再出现无效开关

---

## P2 — 工程债（1 周）

### P2-1 拆分 God Object（R6）

- **Commit**: `refactor(usb): split GalleryViewModel into focused modules`
- **动作**: `GalleryViewModel`（1099 行）→ `ConnectionManager`（USB 生命周期）/ `TransferEngine`（传输编排）/ `ThumbnailProvider`（四类缓存 + EXIF）/ `GalleryStateMachine`（sealed state + 筛选排序）；`GalleryScreen`（1913 行）拆子组件
- **验收**: 行为不变（diff 纯搬移）；`detekt` 的 `TooManyFunctions`/`LongMethod` baseline 条目减少

### P2-2 核心路径补单测（R6）

- **Commit**: `test(usb): add dedup and state machine tests`
- **动作**（遵循 CLAUDE.md「Fakes over Mocks」+ Dispatcher 注入）:
  - `PhotoSyncManagerTest`: 跨会话剪枝、storageId 一致性（P0-2 前置）
  - `GalleryStateMachineTest`: `Disconnected → Connecting → Loading → Browsing/Empty/Error → Transferring → TransferDone` 全迁移
  - `TransferEngineTest`: 失败重试、取消、MediaStore 保存失败路径
- **验收**: 核心路径单测覆盖（`testDebugUnitTest` 通过）

### P2-3 还清 detekt baseline（todo.md 原 P2）

- **Commit**: `chore: repay detekt baseline debt`
- **动作**: 24 条 → 0，逐步修复后从 `detekt-baseline.xml` 移除对应条目
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
| P1-1 | 冷启动引导 | R5 | 新用户看到引导 |
| P1-2 | 新照片默认主路径 | R5 | 插线→传输 ≤3 次点击 |
| P1-3 | 传输回看 | R5 | 可追溯本次传输 |
| P2-1 | 拆 God Object | R6 | 纯搬移无行为变化 |
| P2-2 | 核心单测 | R6 | testDebugUnitTest 通过 |
| P2-3 | detekt 归零 | R6 | baseline 空 |
| P3 | 运营收尾 | — | 设备统一 + CI 全绿 + 真机回归 |
