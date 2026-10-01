# 行动计划归档 — P0–P5（已完成，2026-08-09 → 2026-08-15）

> **ARCHIVED（历史记录，不主动读取）。** 本文件是 [`../planning/action-plan.md`](../planning/action-plan.md) 中 P0–P5 已完成阶段的原文归档。
> 归档日期：2026-10-01。活跃事项（P3 尾项、P6 收尾、P7 大库）见 action-plan.md。
> 依据：[第一期评审](../review/2026-08-09-design-review.md)（R1–R7）+ [第二期评审](../review/2026-08-15-design-review-2.md)（R8–R19）。

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

## P2 — 工程债 ✅

### P2-1 拆分 God Object（R6）✅ `62f1922` `c75fc72` `22e9f8e` `976fd3f`（附 `b1fa3a3` 修复）

- **Headline**: `refactor(usb): split GalleryViewModel into focused modules`
- **动作**: `GalleryViewModel`（1105 行）→ 4 个原子 commit 顺序抽取，**行为不变（纯搬移）**，`GalleryViewModel` 收敛为门面（保留全部公共 API，GalleryScreen/PhotoDetailSheet 零改动）：
  1. ✅ `62f1922` `refactor(usb): extract GalleryStateMachine from GalleryViewModel` — sealed state + 筛选/排序/分组/选择纯逻辑（最可测）
  2. ✅ `c75fc72` `refactor(usb): extract ThumbnailProvider from GalleryViewModel` — 四类缓存 + EXIF 方向
  3. ✅ `22e9f8e` `refactor(usb): extract TransferEngine from GalleryViewModel` — 传输编排（含 MediaStore 保存）
  4. ✅ `976fd3f` `refactor(usb): extract ConnectionManager from GalleryViewModel` — USB 生命周期 + 浏览/枚举
  5. ✅ `b1fa3a3` `fix(usb): assign currentPhotos instead of recursing in updateCurrentPhotos`（拆分暴露的既有 bug）
- **验收**: 行为不变；`LargeClass:GalleryViewModel` baseline 条目随拆分消除

### P2-2 核心路径补单测（R6/R16）✅（2026-08-15，43 tests 全绿）

- **Headline**: `test(usb): add dedup and state machine tests`
- 落地（遵循 CLAUDE.md「Fakes over Mocks」+ Dispatcher 注入）:
  1. ✅ `11d0652` `fix(usb): use injected dispatcher in LocalPhotosViewModel scope` — scope/stop() 用注入 ioDispatcher + 删 baseDir 死代码
  2. ✅ `32721a2` `fix(usb): return to root state when leaving top-level folder` — goBack 根目录归一化（拆分暴露的真 bug）
  3. ✅ `d263935` `test(usb): fix LocalPhotosViewModel tests for plain JVM` — package 声明、Uri/ContentUris 静态 mock、mockk Cursor 替代 MatrixCursor
  4. ✅ `a418d23` `chore: return default values for android.jar stubs` — `unitTests.isReturnDefaultValues`（ContentValues 等桩方法不再抛 not mocked）
  5. ✅ `b015182` `test(usb): add PhotoSyncManager dedup tests` — 构造注入 SharedPreferences + `InMemorySharedPreferences` fake（6 条）
  6. ✅ `cf88244` `test(usb): add GalleryStateMachine transition and filter tests`（10 条）
  7. ✅ `134674b` `test(usb): add TransferEngine failure and retry tests`（8 条）
- **验收**: ✅ `testDebugUnitTest` 19/6 红 → **43/0 全绿**

### P2-3 还清 detekt baseline ✅ `9f373eb`

- **Commit**: `chore: repay detekt baseline debt`
- 动作: 17 条 → 0，`detekt-baseline.xml` 清空：删电量死桩 `getBatteryLevel`（P4-3 决策联动）、`TransferRecord` 独立文件、`extractExif` 委托去重 + 抽 `formatExifDate`、ComplexCondition 提取局部变量、`totalSelected` 死代码、Metro 死 override；MTP/MediaStore 宽 catch 处 `@Suppress` + 注释
- **验收**: ✅ `detekt` 无 baseline 吸收全绿

---

## P4 — 信任与生命周期（✅ 2026-08-15 全部落地）

> 依据 R8/R12/R11/R13/R18。此阶段是"发布前信任"：用户看到的、系统信任的、进程存活的都要是真的。

### P4-1 修复 GalleryViewModel 生命周期所有权（R8）✅ `18c3b9b`

- **Headline**: `fix(usb): pair MTP lifecycle with composable dispose and defer guide marker`
- **方案**: B（DisposableEffect 配对）——`MainActivity` 根部 `DisposableEffect(Unit) { start(); onDispose { stop() } }`；`ConnectionManager.stop()` 完整停止（注销 receiver + **关闭 MtpDevice**）；`GalleryScreen` 移除 `LaunchedEffect start`（VM 生命周期上移到根，跨屏导航不断开）
- **验收**: ✅ 旋转前后仅一个 `MtpDevice` open；receiver 随 dispose 注销。方案 A（DI 保留实例）留待 P5-2

### P4-2 删除幽灵权限（R12）✅ `f0f12f3`

- **Commit**: `chore: remove unused MANAGE_EXTERNAL_STORAGE permission`
- **验收**: ✅ Manifest 无 MANAGE_EXTERNAL_STORAGE；README Permissions 一节同步真实化

### P4-3 产品闭环决策：主题 / 电量（R11/R13）✅

- **主题（R11）**: ✅ `bb1dc29` `feat(settings): add theme selector card` — 设置页三选一（跟随系统/浅色/深色，复用 `settings_theme_*` strings）；MainActivity 以 Compose 状态承载 themeMode 即时生效
- **电量（R13）**: ✅ 删除（YAGNI）——`getBatteryLevel` 恒 null 死桩随 P2-3 `9f373eb` 全链路删除
- **决策原则**: 要么用户能用，要么 UI 里不存在。✅ 已执行

### P4-4 引导标记后置（R18）✅ `18c3b9b`

- **动作**: `guideSeen` 置位移到 `onDone`（FirstRunGuideScreen 回调）；压栈前不再前置置位
- **验收**: ✅ 崩溃后引导不会永久消失；主动返回则下次冷启动再见

---

## P5 — 工程债深水区（✅ 2026-08-15 全部落地）

### P5-1 硬编码字符串资源化（R10）✅

- **Headline**: `refactor(ui): move hardcoded strings to resources`
- **动作**: `SettingsScreen` ~20 处、`GalleryScreen` ~10 处、`FirstRunGuideScreen`、`GalleryViewModel`（"计算中…"）全部改 `stringResource()`；优先复用 `strings.xml` 已定义未使用的 key；缺的补 key
- **实施扩展**: 顺带处理 ConnectionManager 状态消息、EXIF 标签/取值（纯函数 + `ExifValue` sealed type）、LogViewer/TransferHistory/PhotoDetailSheet；清理 52 条未用字符串
- **验收**: ✅ 主代码无中文硬编码（仅注释残留）；lint/detekt 全绿

### P5-2 核心路径接入 DI + 注入 dispatcher（R14）✅

- **Headline**: `refactor(di): inject GalleryViewModel dependencies from AppGraph`
- **动作**: `AppGraph` 增加 `GalleryViewModel`/`LocalPhotosViewModel`/`NikonUsbManager` 提供者；`GalleryViewModel` scope 用注入 `ioDispatcher`；与 P4-1 方案 A 合并实施（`@SingleIn` 保留实例）
- **验收**: ✅ GalleryViewModel 构造注入 ioDispatcher；旋转 UX 与 P4-1 方案 B 一致（`stop()` 归位状态）

### P5-3 核心屏 Preview（R15）✅

- **Headline**: `feat(ui): add gallery screen previews`
- **动作**: `GalleryScreen` 各状态各一个 `@Preview`；`SettingsScreen` 一个；抽 `GalleryScreenHost` 接口 + `TransferDonePanel` 纯渲染
- **验收**: ✅ CLAUDE.md 🔴 强制规范全覆盖

### P5-4 降低 fullPhotoCache OOM 风险（R17）✅

- **Headline**: `fix(usb): cap full-photo cache and use path-based EXIF`
- **动作**: `downloadFullPhoto` 改 temp 文件缓存（磁盘 LRU 3，淘汰/断开即删）+ 路径构造 `ExifInterface`；不再持有 26MB 字节数组
- **验收**: ✅ 峰值内存可预测；断连清理无残留

---

## 验收总览（P0–P5）

| ID | 主题 | 对应 review | 验收标准 |
|---|---|---|---|
| P0-1 | 保存路径真实化 | R4 | ✅ 目录名随模型变化（`2961280`） |
| P0-2 | 去重键一致 | R2 | ✅ 双管线判定一致（`b75e87b`） |
| P0-3 | 单 MTP 管线 | R1 | ✅ 单 MtpDevice open（`9344686`） |
| P0-4 | 剪枝真实化 | R3 | ✅ 软校验不误判（`220aa12`） |
| P1-1 | 冷启动引导 | R5 | ✅（`61fc9a7`） |
| P1-2 | 新照片默认主路径 | R5 | ✅（`97f7c8e`） |
| P1-3 | 传输回看 | R5 | ✅（`f6d2f9b`） |
| P1-4 | 自动同步决策 | R7 | ✅ 移除死代码（`6a1c331`） |
| P2-1 | 拆 God Object | R6 | ✅（`62f1922` `c75fc72` `22e9f8e` `976fd3f` + `b1fa3a3`） |
| P2-2 | 核心单测 | R6/R16 | ✅ 43 全绿（`d263935` `b015182` `cf88244` `134674b`） |
| P2-3 | detekt 归零 | R6 | ✅ baseline 空（`9f373eb`，17 → 0） |
| P4-1 | MTP 生命周期所有权 | R8 | ✅（`18c3b9b`） |
| P4-2 | 删幽灵权限 | R12 | ✅（`f0f12f3`） |
| P4-3 | 主题/电量闭环 | R11/R13 | ✅（`bb1dc29` / `9f373eb`） |
| P4-4 | 引导标记后置 | R18 | ✅（`18c3b9b`） |
| P5-1 | 字符串资源化 | R10 | ✅（`d3d9c13` `ce74c43` `87f41ae` `34c7113` `3777440` `8252b74` `477115d` `361abc3` `36c21f4`） |
| P5-2 | 核心路径接 DI | R14 | ✅（`6785430`） |
| P5-3 | 核心屏 Preview | R15 | ✅（`35ca39b` `c30ce08` `8ee67eb`） |
| P5-4 | 缓存降内存 | R17 | ✅（`ad54502`） |
