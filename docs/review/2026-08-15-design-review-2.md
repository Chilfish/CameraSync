# 2026-08-15 — Apple 视角第二期设计评审（P2-1 之后）

> **评审框架**: Apple 产品与研发哲学（减法聚焦 / 单一事实源 / 关键路径可靠性）
> **评审对象**: P0/P1/P2-1 落地后的 CameraSync 全代码库 + 活动文档
> **上一期**: [2026-08-09-design-review.md](2026-08-09-design-review.md)（R1–R7）
> **状态**: ✅ 已记录 —— 行动项并入 [`../planning/action-plan.md`](../planning/action-plan.md) P4/P5/P6

---

## 一、评审方法

- 通读核心源码：`GalleryViewModel`（facade，1105→377 行）、`GalleryStateMachine`、`ThumbnailProvider`、`TransferEngine`、`ConnectionManager`、`NikonUsbManager`、`PhotoSyncManager`、`GalleryScreen`（2152 行）、`SettingsScreen`、`LocalPhotosViewModel`、`MainActivity`、`AndroidManifest.xml`、`AppGraph`
- 实跑 `./gradlew testDebugUnitTest`：**19 tests / 6 failed**（全部 `LocalPhotosViewModelTest`，纯 JVM mock 问题）
- 对照 `README.md` / `CLAUDE.md` / `action-plan.md` / `todo.md` / postmortem 001
- 判据不变：**核心使命聚焦度 / 单一事实源 / 关键路径可靠性**

---

## 二、上期处置核验（R1–R7）

| # | 上期发现 | 处置核验 |
|---|---|---|
| R1 | 双 MTP 管线 | ✅ 单管线由构造保证（后台服务已删）。**但见 R8：生命周期漏洞可经旋转复活同类并发** |
| R2 | 去重键不一致 | ✅ `PhotoInfo.storageId` 贯穿两处读取（`PhotoSyncManager.kt:47-48` `s{storageId}_h{handle}`），双管线判定一致 |
| R3 | 假"自动剪枝" | ✅ 软校验（`name:size`）落地（`PhotoSyncManager.kt:50`），注释与代码一致 |
| R4 | 硬编码设备路径 | ✅ 改用 `cameraInfo.model`；`"Pictures/CameraSync"` 前缀 + `"Nikon"` fallback 仍为硬编码常量（产品级决策，可接受，文案需资源化） |
| R5 | 冷启动痛点 | ✅ 引导 / 新照片主路径 / 传输回看全部落地（P1-1/2/3） |
| R6 | God Object / 测试赤字 | ◐ VM 层拆分到位（4 模块职责清晰、构造注入），**但 `GalleryScreen` 2152 行未拆、测试赤字未偿（R16）** |
| R7 | 自动同步未接线 | ✅ 死代码移除（`6a1c331`）。**但 README 未同步清理（R9）** |

> 结论：P0/P1 落地质量高（原子 commit、每步本地 gate、docs-first），是这轮最重要的正向资产。
> 但「修复旧谎言，又产生新谎言」——README 宣称已删服务、todo 宣称 0 已知问题。

---

## 三、新发现（按严重度）

### 🔴 R8 — 旋转复活双 MTP 并发：硬件资源没有生命周期所有者

**证据**
- `MainActivity.kt:59` — `val galleryViewModel = remember { GalleryViewModel(app) }`：非 `ViewModel`/`rememberSaveable`，配置变更即重建
- `AndroidManifest.xml` — 无 `android:configChanges`，默认旋转重建 Activity
- `GalleryScreen.kt:122` — 只有 `LaunchedEffect(Unit) { viewModel.start() }`，**无 `DisposableEffect`/`onDispose` 配对**
- `GalleryViewModel.kt:229-234` — `stop()` 全仓库**零调用**（grep 仅 1 处定义）；receiver 用 app context 注册（`ConnectionManager.kt:100-108`），永不注销

**影响**：旋转后旧实例 MTP 未 close、receiver 未注销、全对象图泄漏；新实例 `start()` 在 `deviceList` 发现相机 → 第二个 `MtpDevice` open **同一物理连接** → 前台缩略图/浏览与旧实例 MTP 调用并发踩踏。**R1 的并发风险经"旋转"路径复活，且无需拔插即可触发。**
**性质**：关键路径可靠性 / 资源所有权缺失。P0-3 修了"双实现"，没修"双所有者"。Apple 判读：**USB 连接是独占硬件资源，谁打开谁负责关闭，必须在生命周期边界成对出现。**

### 🔴 R12 — 幽灵权限 MANAGE_EXTERNAL_STORAGE

**证据**
- `AndroidManifest.xml:15` — 声明 `MANAGE_EXTERNAL_STORAGE`（全部文件访问）
- 全代码零使用：无 `isExternalStorageManager`、无 runtime request；保存走 MediaStore `IS_PENDING`（`TransferEngine.kt:171-199`），本已合规

**影响**：Play 政策红线（"所有文件访问"仅限文件管理核心功能，CameraSync 不属于）；安装页信任损耗（"为什么传照片要读全部文件？"）。**发布阻断级。**
**性质**：核心使命聚焦度 / 信任。

### 🟠 R9 — README 宣称已删除功能（postmortem 001 复发）

**证据**
- `README.md:14` — "Background Sync: A foreground service keeps transfers running..."
- `README.md:101-102` — 项目结构仍列 `UsbSyncService.kt` / `UsbSyncCoordinator.kt`（`6a1c331` 已删）
- `README.md:43` — "Download All"（实际主 CTA 为「传输全部新照片 (N)」）

**影响**：新用户/审阅者被误导。postmortem 001「删代码必删文档」的教训在 P1-4 后未执行——**同一系统性缺陷二次复发**。
**性质**：单一事实源缺失。

### 🟠 R16 — 测试赤字未偿还（上期 R6 遗留）

**证据**
- 实跑 `testDebugUnitTest`：**19 tests / 6 failed**（全部 `LocalPhotosViewModelTest`：mockable.jar not mocked / MatrixCursor / 真 bug `goBack` 等，见 `docs/development-log/2026-08-09.md` P2 现状核对）
- 核心逻辑零测试：`PhotoSyncManager` 去重、`GalleryStateMachine` 迁移/筛选/排序/选择、`TransferEngine` 失败/取消/MediaStore 保存路径
- action-plan P2-2 计划在案未落地；工作区仅 `LocalPhotosViewModel` 的 dispatcher 注入改动未提交

**性质**：关键路径可靠性。Apple 信条：**没测试 = 没承诺。**

### 🟠 R13 — 电量是"实现"的谎言

**证据**
- `NikonUsbManager.kt:327` — `getBatteryLevel` 恒返回 `null`（detekt baseline `FunctionOnlyReturningConstant` 死桩）
- `ConnectionManager.kt:56,202` — `batteryLevel` 恒 null；UI 条件渲染（`GalleryScreen.kt:1162`）永不显示
- `todo.md` 却勾选 PRD F12 ✅；`strings.xml:210` 已定义 `usb_battery_level`

**影响**：产品宣称了代码未实现行为（R5 同族）。用户看到"功能清单里有电量"，实际没有。
**性质**：状态失真。**决策二选一**：PTP `GetDevicePropValue(0xD303)` 真实现（Z30 未必支持，需真机验证），或删除电量 UI 承诺与 todo 勾选。

### 🟠 R19 — "0 已知问题"状态失真（上期 R5 未根治）

**证据**
- `todo.md:7-9` — "✅ 生产就绪 (v2.3) / 所有计划的 Sprint (1–4) 已完成，0 个已知问题"
- 同一文件下方"已知问题"表 + **6 个红测试 + 17 条 detekt baseline + P2/P3 未完成**

**影响**：状态报告是决策依据。标签失真让"发布"从工程决定变成赌博。
**性质**：状态诚实度——系统性，需流程修复而非单次澄清。

### 🟡 R10 — 硬编码字符串泛滥（违反自家强制规范）

**证据**
- `SettingsScreen.kt` ~20 处写死中文：`"设置"` `"返回"` `"照片网格"` `"照片分组"` `"照片排序"` `"默认下载格式"` `"全部"` `"仅 JPEG"` `"仅 RAW"` `"按文件夹"` `"按日期"` `"不分组"` `"最新优先"` `"按名称"` `"按大小"` `"传输历史"` `"查看以往的同步记录"` 等
- `GalleryScreen.kt`：`:473` `"已扫描 %d / %d 张"`、`:1840` `"没有已导出的照片"`、`:1849` `"此目录下没有照片"`、`:1771` contentDescription `"关闭"`、`:2068` `"EXIF 信息"`、`:1971` `"文件名"`、`:1905/:1916` `"←"`/`"↻"`、`:1933` `"📁"`、`:1997` `"RAW"`
- `FirstRunGuideScreen.kt:46` — contentDescription `"返回"`；`GalleryViewModel.kt:61,72` — `"计算中…"`
- **讽刺点**：`strings.xml` 已定义 `settings_grid_density`/`settings_history`/`settings_theme_*`/`usb_exif_*` 等，屏幕却写死中文——资源与代码双份事实

**性质**：规范违反（CLAUDE.md 🔴 禁止硬编码字符串）+ 单一事实源。

### 🟡 R11 — 主题偏好"读了没人写"

**证据**
- `MainActivity.kt:55` — 读 `prefs.getThemeMode()`；`UsbSyncPreferences.kt:90` — `setThemeMode` **零调用**
- `SettingsScreen.kt` 无主题卡片；`strings.xml:159-162` 已定义 `settings_theme*`

**影响**：PRD F16「深色主题」声称完成（todo ✅），用户实际无法切换。与 R7 同族（方向相反：有消费者无写入口）。
**性质**：半成品功能。**决策**：接线设置页主题卡片（三选一，复用现有 strings）或删除。

### 🟡 R14 — DI 是展示性的

**证据**
- `AppGraph.kt` 提供 Context / ioDispatcher / LogRepository / LogViewerViewModel；核心路径（`GalleryViewModel`、`LocalPhotosViewModel`、`NikonUsbManager`、`PhotoSyncManager`、`UsbSyncPreferences`）全部手搓 `remember { GalleryViewModel(app) }`
- `GalleryViewModel.kt:124` — 自带 `CoroutineScope(Dispatchers.IO + …)`，违反 CLAUDE.md「Dispatcher 注入」
- 6 个红测试的根因之一正是 scope 硬编码（`LocalPhotosViewModelTest` 等不到真实 IO 线程）

**影响**：基础设施与核心功能两层皮——CLAUDE.md 规范在核心路径不成立；测试无法注入 fake 调度器。
**性质**：工程债 / 规范未贯彻。修复方向：`GalleryViewModel` 构造注入（app / ioDispatcher / prefs），顺带解决 R8（实例移入 DI 层 retained）。

### 🟡 R15 — 主屏零 Preview

**证据**
- 全仓库仅 3 个 `@Preview`（`LogViewerScreen`×2、`FirstRunGuideScreen`×1）
- `GalleryScreen`（2152 行，核心屏）**零 Preview**

**性质**：CLAUDE.md 🔴 强制规范「每个 @Composable Screen 必须有 @Preview」被主屏违反；E2E/视觉回归基础缺失。

### 🟡 R17 — 全片缓存 OOM 风险

**证据**
- `ThumbnailProvider.kt:52-58` — `fullPhotoCache` LRU **12 条 × NEF ~26MB ≈ 300MB**（注释自认）
- `downloadFullPhoto`（:215-238）— 先 `importFile` 到 temp 再 `readBytes()` 全量进内存并缓存
- Manifest 未开 `largeHeap`；中低端机型 heap 192–256MB

**影响**：翻看 12 张 NEF 详情即 OOM。
**修复方向**：EXIF 用 temp 文件路径构造 `ExifInterface`（`LocalPhotoDetail` 已示范 path 构造，`GalleryScreen.kt:2023`），缓存上限收到 3–4 条或改磁盘缓存。

### 🟡 R18 — 引导标记"先写后看"

**证据**
- `MainActivity.kt:70-75` — `LaunchedEffect(Unit) { if (!prefs.guideSeen) { prefs.guideSeen = true; backStack.add(FirstRunGuide) } }`

**影响**：压栈后崩溃/被杀 → 引导永远不再显示（标记已置位）。应「展示完成（onDone）时置位」。
**性质**：小正确性缺陷。

---

## 四、与既有文档的矛盾清单（更新）

| # | 文档声明 | 代码实际 | 出处 |
|---|---|---|---|
| 1 | "0 个已知问题" | 6 个红测试 + 17 条 baseline + P2/P3 未完成 | `todo.md:7-9` |
| 2 | "Background Sync: foreground service" | `UsbSyncService` 已删（`6a1c331`） | `README.md:14` |
| 3 | 项目结构含 `UsbSyncService`/`UsbSyncCoordinator` | 已删 | `README.md:101-102` |
| 4 | "深色主题 ✅" | `setThemeMode` 零调用，设置页无入口 | `todo.md` / `UsbSyncPreferences.kt:90` |
| 5 | "相机电量 🔋 ✅"（F12） | `getBatteryLevel` 恒 null | `todo.md` / `NikonUsbManager.kt:327` |
| 6 | 测试设备三处说法（遗留） | `USB_SYNC.md` §9 = Xiaomi MIUI；README = Nikon Z30 | action-plan P3-1 |

---

## 五、结论与优先级

| 优先级 | 主题 | 发现 |
|---|---|---|
| P0 | 收尾 P2：测试全绿 + detekt baseline 归零 | R16 |
| P0 | 发布阻断：删 `MANAGE_EXTERNAL_STORAGE`、README 真实化 | R12 / R9 |
| P1 | 生命周期：`GalleryViewModel` 所有权修复（防旋转双开） | R8 |
| P1 | 产品闭环决策：主题 / 电量 接线或删除 | R11 / R13 |
| P2 | 规范回填：字符串资源化、核心屏 Preview、核心路径接 DI | R10 / R15 / R14 |
| P2 | 可靠性：`fullPhotoCache` 降内存、引导标记后置 | R17 / R18 |
| P3 | 状态诚实化（撤回"0 已知问题"）+ CHANGELOG 制度落地 | R19 |

**明确不做（延续 + 新增）**：云备份 / 视频 / 多相机 USB / Wi-Fi；**通用 MTP 多厂商支持**（保持 Nikon-only，直至单设备可靠性 99.9%，再谈扩展）。

**评分（更新）**：工程 **B+** / 产品 **B+**。P0/P1 落地质量高，但「状态诚实度」与「资源所有权」是系统性欠账——前者靠流程，后者靠架构。

---

## 六、并行工作流落地核对（2026-08-15 同日）

评审期间仓库有并行工作流（对标 Float 补齐资产）。核对结果：

| 项 | 并行流落地 | 本评审对应 |
|---|---|---|
| `CHANGELOG.md` | ✅ 已建（Unreleased + 回溯 v1.0.0） | P6-1 自动满足，改为「PR 更新纪律执行」 |
| `docs/requirements/`（PRD / feature-spec / glossary / user-stories） | ✅ 已建 | —（新 PRD F-SET-01 仍列「主题」设置项，R11 依旧成立） |
| `docs/planning/architecture.md`（ADR） | ✅ 已建（ADR-001..） | — |
| `docs/legal/privacy-policy.md` | ✅ 已建 | P6-3 上架材料部分满足 |
| GitHub 模板 / workflows（ci / release） | ✅ 已建 | — |
| `docs/engineering/release-checklist.md` / `adb-commands.md` | ✅ 已建 | — |
| README「Background Sync」功能条目 | ✅ 并行流已删 | R9 部分修复 |
| README 项目结构段 `UsbSyncService`/`UsbSyncCoordinator` | ✅ 本评审已删 | R9 全部闭环 |
| CLAUDE.md `Current State`「0 known issues」 | ✅ 本评审已改为发布前状态 | R19 流程修复落地 |

> 结论：R9/R19 已闭环（README + CLAUDE.md 与代码单一事实源对齐）。R12（幽灵权限）、R8（生命周期）、R11/R13（主题/电量闭环）、R16（测试 6 红）仍在代码层，见 action-plan P4。
