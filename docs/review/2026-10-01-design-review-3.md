# 2026-10-01 — 第三期设计评审（大库场景专项：正确性 + 流畅度）

> **评审框架**: Apple 产品与研发哲学（减法聚焦 / 单一事实源 / 关键路径可靠性）
> **评审对象**: 发布前（P6 收尾）的 CameraSync 全部核心源码 + 活动文档，**专项聚焦「照片很多」时的可用性**
> **上一期**: [2026-08-15-design-review-2.md](2026-08-15-design-review-2.md)（R8–R19）
> **状态**: ✅ 已记录 —— 行动项并入 [`../planning/action-plan.md`](../planning/action-plan.md) **P7**
> **触发**: 用户反馈「实际用起来不太好用顺畅，特别是很多照片的场景」+ 构建验证时发现 lint `Recycle` 告警

---

## 一、评审方法

- **实跑构建门禁**（clean 全量）：`clean detekt ktfmtCheck lintDebug testDebugUnitTest assembleDebug bundleRelease`
- 通读核心链路：`GalleryViewModel` / `GalleryStateMachine` / `ConnectionManager` / `ThumbnailProvider` / `TransferEngine` / `NikonUsbManager` / `PhotoSyncManager` / `UsbSyncPreferences` / `GalleryScreen`（2248 行）/ `PhotoDetailSheet` / `LocalPhotosViewModel` / `MainActivity` / `AppGraph`
- 交叉核对测试覆盖（`TransferEngineTest` / `GalleryStateMachineTest` / `PhotoSyncManagerTest` / `LocalPhotosViewModelTest`）
- 判据：**核心使命聚焦度 / 单一事实源 / 关键路径可靠性 / 大库可用性**

### 构建验证结果（clean 全量，2026-10-01）

| 门禁 | 结果 |
|---|---|
| `assembleDebug` | ✅ `app-debug.apk`（34.5 MB，重新生成） |
| `bundleRelease`（R8 minify + shrink） | ✅ `app-release.aab`（4.5 MB，**未签名**——无 `keystore.properties`） |
| `testDebugUnitTest` | ✅ **43 tests / 0 failed** |
| `detekt` | ✅ 0（baseline 空） |
| `ktfmtCheck` | ✅ 通过 |
| `lintDebug` | ⚠️ 0 errors / **96 warnings / 2 hints** |

> 结论：**构建层面无阻断**。问题在运行时逻辑与大库表现，非编译门禁。

---

## 二、上期处置核验（R8–R19）

| # | 上期发现 | 处置核验 |
|---|---|---|
| R8 | 旋转复活双 MTP | ◐ 双开已治（`stop()` 关闭 MTP）。**但 `stop()` 重建 `scope` 后协作对象仍持旧引用 → 见 R20（同类问题换了形态）** |
| R9 | README 宣称已删功能 | ✅ README 已与代码对齐 |
| R10/R14/R15/R17/R18 | 硬编码字符串 / DI / Preview / 缓存 / 引导标记 | ✅ P5 全部落地 |
| R11/R13 | 主题 / 电量闭环 | ✅ 主题接线、电量删除 |
| R12 | 幽灵权限 | ✅ 已删 |
| R16 | 测试赤字 | ✅ 43 全绿 |
| R19 | 状态失真 | ✅ 撤回「0 已知问题」；**本期延续诚实原则：已发现的新问题全部登记，不写「已无问题」** |

> P0–P5 落地质量高（原子 commit、每步 gate、docs-first）。本期是**首次针对「大库可用性」的专项体检**——过去两期聚焦「正确性止血」与「工程债」，性能与交互成本从未被系统评估。

---

## 三、构建阶段顺带发现的既有问题（lint，R36–R41）

> 编号沿第二期顺延；本节 R36–R41 为构建/lint 验证时顺带看到的问题，R20–R35 为本次大库专项新发现（见下节）。

| # | 证据 | 说明 |
|---|---|---|
| R36 | `LocalPhotosViewModel.kt:183,207,268,282` `Recycle` | lint 报 Cursor 未关闭。**复核为误报**——4 处 query 均用 `?.use { }`；`readPhotoCursor`/`readFileCursor` 的 Cursor 由调用方 `use` 关闭。**但** `queryFolders` 在进入每个目录时都对全库做 `LIKE` 扫描（见 R37），才是真问题 |
| R37 | `app/src/main/res/values/gms_fonts_certs.xml` Typos ×16 | base64 证书误报，可忽略 |
| R38 | 未用资源：`ic_bluetooth_*`、`ic_location_on_24dp`、`ic_add_camera_24dp`、`ic_linked_camera_*`、`avd_syncing_waves`、`colors.xml` 模板色 | BLE/模板残留，随 BLE 移除未清理 |
| R39 | `ConnectionManager.kt:95` `ObsoleteSdkInt`；`mipmap-anydpi-v26` | minSdk 33 下 `SDK_INT >= TIRAMISU` 恒真、`-v26` 目录多余 |
| R40 | `GalleryStateMachine.kt:51` / `GalleryViewModel.kt:171` `AutoboxingStateCreation` | 建议 `mutableIntStateOf` |
| R41 | `UsbSyncPreferences.kt` `UseKtx` ×8；`GalleryScreen.kt:2396` `Uri.parse` | KTX 扩展建议（低优先） |

---

## 四、新发现（大库专项，按严重度，R20–R35）

### 🔴 R20 — `stop()` 重建 scope，但协作对象持有旧引用 → 旋转后 USB 连接静默失效

**证据**
- `GalleryViewModel.kt` — `private var scope = CoroutineScope(ioDispatcher + SupervisorJob())`；`stop()` 里 `scope.cancel()` 后 `scope = CoroutineScope(...)` 重建
- 但 `ConnectionManager` / `ThumbnailProvider` / `TransferEngine` 均在**构造时按值捕获**该 `CoroutineScope`（各自 `private val scope`），三个对象都是 `GalleryViewModel` 初始化时创建一次（`@SingleIn(AppGraph)` 持有）

**影响**：`MainActivity` 的 `DisposableEffect { start(); onDispose { stop() } }` 在**任何配置变更（旋转）/ Activity 重建**时触发：`stop()` 取消并替换 scope，但协作对象仍指向**已取消的旧 scope**。旋转后 `start()` → `onPlugged` → `connectAndBrowse()` 在死 scope 上 `launch` → 协程立即取消、body 不执行 → **页面停在 Disconnected/Connecting，USB 不再枚举，无任何报错**。这是「用起来不顺畅」的头号嫌疑。

**性质**：资源所有权 / 生命周期。上期 R8 修的是「双开 MTP」，本期是「生命周期边界把工作协程也一起杀掉了」。教训同源：**生命周期所有者 owning 的不只是硬件句柄，还有所有派生的协程作用域。**

---

### 🔴 R21 — RAW+JPEG 选「全部」时 JPEG 根本不会传输（丢片）

**证据**
- `GalleryStateMachine.handlesForFormat(ALL)` 返回 `listOfNotNull(raw?.handle, jpg?.handle)`（两个 handle）
- `selectAll` / `toggleSelection` / `selectAllNew` 据此把 raw+jpg **都**放进 `selected`（`GalleryStateMachineTest` 断言 `handlesForFormat(ALL) == [1,2]`）
- 但 `TransferEngine.buildTransferList()` 对每个 group 只产出**一个** `photo to handle`，且 raw 优先：
  ```kotlin
  val h = if (g.raw != null && handleFilter(g.raw.handle)) g.raw.handle
          else if (g.jpg != null && handleFilter(g.jpg.handle)) g.jpg.handle
          else return@mapNotNull null
  ```
- `performTransfer` 以 `toTransfer.size` 为总数 → 进度、成功数、`lastTransferredHandles`、去重标记都只覆盖 RAW

**影响**：勾选、预览面板、目标格式都按「两张」算，实际只下 NEF，**JPEG 被静默丢弃**。README「RAW+JPEG pairs…transfer both at once」未兑现。**这是丢片——核心使命红线。**
**性质**：关键路径正确性。现有测试全部使用单张 `group(p)`，**无一条覆盖 RAW+JPEG 成对传输**，缺陷因此长期隐藏。

---

### 🔴 R22 — 传输预览的组数被 `.take(6)` 截断，摘要与「+N more」失真

**证据**（`GalleryScreen.kt:1617-1622`）
```kotlin
val selectedGroups = viewModel.currentPhotos.filter { viewModel.isGroupSelected(it) }.take(6)
val totalGroups = selectedGroups.size        // ← 已被 take(6) 截断，最大 6
...
val remaining = totalGroups - selectedGroups.size   // ← 恒为 0
```

**影响**：选中 200 组时，预览面板显示「共 6 组」，且「+N more」永不出现；`usb_preview_summary` 的组数参数失真。缩略图只显示 6 张是对的（有意），但**统计口径错**。
**性质**：状态失真（展示层撒谎）。

---

### 🔴 R23 — 同名照片跨存储 / 跨文件夹被合并（丢片）

**证据**（`GalleryViewModel.groupByBaseFilename()`）
```kotlin
val base = p.name.substringBeforeLast(".")          // 仅文件名，不含 storageId / 文件夹
map.getOrPut(base) { mutableListOf() }.add(p)
...
raw = list.find { ... }, jpg = list.find { ... }    // 同名只保留「第一个」
```

**影响**：双卡（或两个文件夹）都有 `DSC_0001.JPG` 时，FLAT / BY_DATE 分组下二者合成**一个** group，只保留其一 → 另一张在 UI 上消失、也不会被传。且 `items(key = it.baseName)` 依赖 baseName 唯一——当前是被「错误合并」掩盖了，一旦修合并逻辑，**同 key 会触发 Lazy 崩溃**。正确键应是 `base + storageId + parentFolder`。
**性质**：关键路径正确性（多存储 / 多文件夹场景丢片）+ 潜在崩溃。

---

### 🟠 R24 — 任何一次勾选都会让整个画廊全量重算（大库卡顿主因）

**证据**
- `GalleryScreen.kt:142` — `val selectionCount = viewModel.selectedCount`：`selectedCount` 是 `SnapshotStateList.size`，读它即订阅整张列表
- `BrowsingContent`（`GalleryScreen.kt:531-556`）在每次重组时无条件计算：
  - `entries.filterIsInstance<...>()` ×3（O(n)）
  - `photos.count { it.hasRaw }`、`photos.count { it.jpg != null }`（O(n) ×2）
  - `host.getNewPhotoCount()` → 对**每一张**照片调 `PhotoSyncManager.isAlreadyImported()`（两次字符串拼接 + 一次 SharedPreferences 读）（O(n)）
- `GalleryViewModel` 未标注 `@Stable`，`BrowsingContent`/`CameraTabContent` 携带新 lambda，**不可跳过** → 勾选 → 重组 `GalleryScreen` → 整块 `BrowsingContent` 重跑 → 重建整个 lazy item provider

**影响**：n=3000 时，每次点选 = 数千次主线程计数 + 数千次 SharedPreferences 读 + 数千次字符串分配，外加 lazy provider 重建。这是「大库下点选、滑动都发涩」的直接来源。
**性质**：Compose 重组经济性 / 大库可用性。修法：选择状态改为可观察的 `Set<Int>` + 每 cell 用 `derivedStateOf`/key 级订阅；计数与筛选结果用 `derivedStateOf` 缓存，勾选不触碰结果集。

---

### 🟠 R25 — BY_DATE 渲染是 O(分区 × 照片)，且每张都 new 一个 `SimpleDateFormat`

**证据**（`GalleryScreen.kt:633-646`）
```kotlin
val datePhotos = filteredPhotos.filter { group ->
    val dateFmt = java.text.SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())  // 每张 new
    dateFmt.format(Date(maxOf(...))) == section.date
}
```
每个 date section 都对**全量** `filteredPhotos` 扫一遍，且比较时**为每张照片新建一个 `SimpleDateFormat`**。

**影响**：50 分区 × 3000 张 = 15 万次日期格式化 + 15 万个 SDF 分配，且随 R24 的重组一起反复触发。BY_DATE 模式在大库下会明显卡。
**性质**：算法复杂度 + 分配风暴。修法：进入 Browsing 前按日期一次性分桶（`groupingBy`），渲染直接取桶。

---

### 🟠 R26 — 选择集是 `SnapshotStateList<Int>`，全选 O(n²)、判定 O(k)、预览 O(n·k)

**证据**
- `GalleryStateMachine.selected = mutableStateListOf<Int>()`；`isSelected(h) = h in selected`（线性）、`toggleSelection` 里 `selected.remove(it)`（线性）
- `selectAll`：`forEach { if (it !in selected) selected.add(it) }` → 每张一次全表扫描 → **O(n²)**
- `TransferPreviewSheet`（`GalleryScreen.kt:1625-1632`）`flatMap{..}.filter { isSelected(handle) }` → 每个 handle 一次线性查找 → **O(n·k)**

**影响**：3000 张时「全选」≈ 数百万次比较，全部在主线程；大选区的预览面板开启会明显卡顿。
**性质**：数据结构选择。修法：`selected` 换成基于 `mutableStateSetOf`/`SnapshotStateSet`（或 `LinkedHashSet` + 单一 `mutableStateOf` 版本号），使 `contains`/`add`/`remove` 接近 O(1)，且勾选只让「受影响的 key」失效。

---

### 🟠 R27 — 枚举照片树跑了两遍（进度估算）

**证据**（`NikonUsbManager.listPhotos()`）
```kotlin
val totalEstimate = countObjectsInStorage(mtpDevice, storageId)   // 先完整 BFS 一遍
while (folderQueue.isNotEmpty()) { ... }                          // 再 BFS 一遍取元数据
```
`countObjectsInStorage()` 为每个文件夹再额外调两次 `getObjectHandles`，纯为进度条估算。

**影响**：大卡上 MTP 往返**约翻倍**，首屏可见照片的等待时间随之翻倍。而 MTP `getObjectHandles` 是 USB 同步往返，成本高。
**性质**：关键路径性能。修法：去掉预估（进度条改为「已扫描 N 张」不确定态），或复用单次遍历中的计数。

---

### 🟠 R28 — 缩略图管线在大目录下会堆积，且旋转未回收

**证据**
- `preloadThumbnails(groups.size.coerceAtMost(50))` — 只预热 50 张；`thumbCache` 128 条、`bitmapCache` 96 条
- `ThumbnailImage`（`GalleryScreen.kt:863-900`）每个新 cell 触发一次 `device.getThumbnail(handle)`（USB 往返），经 `Semaphore(3)` 串行；**原生调用不可取消** → 快速滑动时请求排队，松手后仍在「还债」
- `rotateByDegrees()`（`GalleryScreen.kt:1060`）竖拍每张 `Bitmap.createBitmap` **新建**位图，**未 recycle 源位图** → GC 压力翻倍

**影响**：大目录快速滑动时缩略图跟不上、出现长时间占位；频繁 GC 引发掉帧。
**性质**：大库可用性。修法：预载跟随可视窗口；缓存容量随网格列数与屏幕密度调优；旋转后 recycle 源图或改用 `Matrix` 直绘。

---

### 🟠 R29 — 枚举满 30 张后界面「静默」，长时间无反馈

**证据**（`ConnectionManager.loadRootProgressive()`）：累计到 30 张即切 `Browsing`，此后 `onProgress` 不再更新任何 UI；`currentPhotos` 直到**全部枚举完成**才整体替换。

**影响**：大卡上用户看到约 30 张后就再无进展，可能持续数分钟；期间「新照片 (N)」计数与筛选结果是**半成品**，最终会跳变。用户会以为「卡住了」。
**性质**：交互诚实度。修法：Browsing 后继续增量更新 `currentPhotos`（分批节流），并在顶栏显示「正在扫描… N」的持续指示。

---

### 🟠 R30 — 去重表（SharedPreferences）无上限增长

**证据**：`PhotoSyncManager` 每导入一张即 `putString("s{storageId}_h{handle}", "name:size")`，**从不清理**；`clearAll()`/`clearStorage()` 有实现但**零调用**；`trackedCount`/`clearStorage` 还 `prefs.all` 全量拷贝。

**影响**：多年使用后数万条键，`SharedPreferences` 启动时整体载入内存并解析 XML，拖慢冷启动。注意：这是**存储增长**问题，不是 R3/ADR-008 的「身份软校验」设计问题（那个是对的）——缺的是**上限/淘汰**。
**性质**：长期可用性。修法：设容量上限（如最近 N 次会话或 M 条），或改为「按 storageId+name 去重 + 定期 GC 未再出现的键」。

---

### 🟡 R31 — 相机页下拉刷新永不显示指示器

**证据**：`GalleryScreen.kt:578` `PullToRefreshBox(isRefreshing = false, ...)` 恒 false。

**影响**：用户下拉时没有转圈反馈，不知刷新是否生效。
**性质**：小交互缺陷。修法：绑定真实刷新状态（或至少 `state is Loading`）。

---

### 🟡 R32 — `runCatching` 吞掉 `CancellationException`，temp 文件异常路径泄漏

**证据**
- `TransferEngine.saveToMediaStore()` 用 `runCatching { ... }.getOrElse { ... }` 包裹下载：`runCatching` 捕获 `Throwable`（含 `CancellationException`）→ 取消传输时当前文件被当作「失败」记录，且取消语义被吞
- `NikonUsbManager.downloadPhoto()` 的 `catch` 分支**未删除** `cacheDir/mtp_{handle}` 临时文件 → 失败/取消后残留

**影响**：取消/重试行为不精确；长期在 `cacheDir` 残留大文件（每张可达 26 MB）。
**性质**：协程正确性 + 磁盘卫生。修法：`runCatching` 前先判 `isActive`，或改用 `try/catch (e: CancellationException) { throw e }`；`finally` 里删临时文件。

---

### 🟡 R33 — 详情页每次点开都下载整份 RAW（26 MB）仅为读 EXIF

**证据**：`PhotoDetailSheet` 打开即 `onDownloadFullPhoto(handle)` → `ThumbnailProvider.downloadFullPhoto` 走 `importFile` 下整份文件（磁盘 LRU 3）。而缩略图字节其实**已经在手**（`thumbnailBytes`）。

**影响**：逐张翻看详情时反复下载大文件，明显等待与 USB 占用；EXIF 的多数字段从 MTP 缩略图（含 EXIF）即可得到，全量下载只对少数字段必要。
**性质**：大库可用性。修法：优先从 `thumbnailBytes` 解析 EXIF，全量下载降级为「查看原图」的显式操作。

---

### 🟡 R34 — 死代码 / 资源泄漏

**证据**
- `GalleryStateMachine.filterCacheGeneration` — 只写不读的 `mutableStateOf(0)`，死状态
- `NikonUsbManager.openMtpDevice()` — `mtp.open(conn)` 失败时 `return null` 但**未关闭** `usbConnection`（已赋值的连接泄漏）
- `LocalPhotosViewModel.stop()` 无调用方（DI 单例，可接受，但与 `GalleryViewModel` 的 stop 语义不对称）

**性质**：工程卫生。

---

### 🟡 R35 — BLE 残留资源与过时版本判定（lint）

**证据**：R38/R39 所列——`ic_bluetooth_*` 等图标、`avd_syncing_waves`、`colors.xml` 模板色未随 BLE 移除清理；`ObsoleteSdkInt`（minSdk 33 下冗余判断与 `-v26` 目录）。

**性质**：文档/代码一致性（postmortem 001 同族：删子系统未清干净）+ 轻微包体冗余。

---

## 五、与既有文档的矛盾清单

| # | 文档声明 | 代码实际 | 出处 |
|---|---|---|---|
| 1 | README「RAW+JPEG pairs…transfer both at once」 | `buildTransferList` 每 group 只传一个 handle（JPEG 丢） | `README.md` / `TransferEngine.kt`（R21） |
| 2 | `usb_preview_summary`「已选 N 张，共 M 组」 | M 被 `take(6)` 截断 | `GalleryScreen.kt:1622`（R22） |
| 3 | `todo.md`「BLE GPS 同步 (次要)」列于「已完成功能总览」 | BLE 已于 2026-08-02 移除 | `todo.md`（文档漂移，postmortem 001 同族） |
| 4 | `docs/development-log/README.md` 时间线仅到 2026-08-09 | 存在 `2026-08-15.md` 未索引 | 索引陈旧 |
| 5 | `docs/review/README.md` / `docs/README.md` 仅索引 2026-08-09 评审 | 存在第二期评审未索引 | 索引陈旧 |
| 6 | `todo.md`「最近提交 (2026-08-09)」 | 现状已是 2026-10-01 文档提交 | 状态陈旧 |

> 说明：矛盾 4–6 为本期**顺手修复**的文档漂移（见 §六）；矛盾 1–3 随 P7 一并闭环。

---

## 六、本期同时完成的文档维护（过时清理）

- `docs/development-log/README.md`：时间线补 `2026-08-15` 与 `2026-10-01`
- `docs/review/README.md`：索引补第二期、新增第三期；修正第二期状态
- `docs/README.md`：评审节补两期、存档节补归档的旧行动计划
- `docs/planning/action-plan.md`：**已完成阶段 P0–P5 归档**至 [`../archive/ACTION_PLAN_P0-P5.md`](../archive/ACTION_PLAN_P0-P5.md)，正文只留活跃项 + 新增 **P7**
- `docs/planning/todo.md`：状态与最近提交同步；BLE 条目归入「已移除」

---

## 七、结论与优先级

| 优先级 | 主题 | 发现 |
|---|---|---|
| **P0（发布阻断）** | 丢片：RAW+JPEG 成对传输 / 同名跨存储合并 | R21 / R23 |
| **P1（关键路径）** | 生命周期：`stop()` 后协作对象持死 scope | R20 |
| **P1（关键路径）** | 展示正确性：预览组数截断 | R22 |
| **P1（大库流畅度）** | 勾选触发全屏重算 / BY_DATE 复杂度 / 选择集结构 | R24 / R25 / R26 |
| **P2（大库流畅度）** | 枚举两遍 / 缩略图堆积 / 扫描无反馈 / 去重表无上限 | R27 / R28 / R29 / R30 |
| **P3（打磨）** | 刷新指示 / 取消语义 / 详情不下载全图 / 死代码 / BLE 残留资源 | R31–R35 |

**评分**：工程 **B** / 产品 **B**。相比第二期（B+/B+）**下调半档**——两期都在「正确性止血」，但**「大库可用性」直到用户实际使用才暴露，且其中两项是丢片级缺陷**。核心使命是「插线传照片」，那么「照片多」是常态而非边缘；**未对常态做专项验证，是评测方法的欠账**（此前测试全部使用个位数照片的构造数据）。

**明确不做（延续）**：云备份 / 视频 / 多相机 USB / Wi-Fi / 通用多厂商 MTP。
**本期新增不做**：不引入分页/数据库（Room）重构——先用最小的状态与算法修正解决大库卡顿；若 P7 后仍不达标，再评估 `Paging` 或 `Room` 缓存（届时另立 ADR）。

---

## 八、后续

- 行动项落地见 [`../planning/action-plan.md`](../planning/action-plan.md) **P7 — 大照片库正确性与性能**（P7-A 正确性 / P7-B 性能 / P7-C 打磨）
- 每项遵循 CLAUDE.md：先写 commit message、docs-first、单测兜底（R21/R23 必须先补测试）
