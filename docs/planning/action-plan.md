# CameraSync 后续行动计划 & 开发步骤

> **依据**: [第一期评审](../review/2026-08-09-design-review.md)（R1–R7）+ [第二期评审](../review/2026-08-15-design-review-2.md)（R8–R19）+ [第三期评审](../review/2026-10-01-design-review-3.md)（R20–R41，大库专项）
> **最后更新**: 2026-10-01 | **原则**: docs-first；先修复再新功能；先写 commit message 再写代码；每 commit 本地跑 detekt + ktfmtCheck

---

## 一、目标

把核心使命「插线传照片」做到 99.9% 可靠：

- **绝不错传** — 去重键一致（R2）
- **绝不丢片** — 剪枝真实化（R3）；**RAW+JPEG 成对必传（R21）；同名单张不合并（R23）**
- **单一管线** — 一条数据通路，一个所有者（R1）；**资源有生命周期所有者，旋转不复活双开（R8）；生命周期边界不误杀派生协程（R20）**
- **状态诚实** — 文档、UI 宣称与代码一致（R9/R13/R19/R22）
- **大库可用** — 「照片很多」是常态：大库下点选/滑动/扫描仍然顺畅（R24–R30）
- 功能做减法，聚焦冷启动与默认主路径（R5）

## 二、排序

**已完成：P0–P5 工程阶段 → P7 大照片库正确性与性能（P7-A 正确性 / P7-B 性能 / P7-C 打磨）✅ 2026-10-01。**
**待办：P3 运营收尾（设备门控）→ P6 发布闭环（设备/环境门控）并行等待环境；真机回归（Nikon Z30，含双卡 / 大库）。**

> **P7 进度（2026-10-01）**：R20–R35、R36、R40 全部落地（R21/R23 两项丢片级缺陷闭环，发布阻断解除）；`testDebugUnitTest` **54 全绿**、detekt 0、lint **0 errors / 74 warnings**。**仍需真机（Nikon Z30，含双卡 / 大库）回归确认性能与交互体感。**

---

## 三、已完成阶段（P0–P5）✅

> 详细原文已归档至 [`../archive/ACTION_PLAN_P0-P5.md`](../archive/ACTION_PLAN_P0-P5.md)（历史记录，不主动读取）。下表为摘要。

| 阶段 | 主题 | 结果 | 关键 commit |
|---|---|---|---|
| P0 | 正确性止血（去重键/保存路径/单管线/软校验） | ✅ 2026-08-09 | `2961280` `b75e87b` `9344686` `220aa12` |
| P1 | 核心路径（引导 / 新照片主路径 / 传输回看 / 移除死代码） | ✅ 2026-08-09 | `61fc9a7` `97f7c8e` `f6d2f9b` `6a1c331` |
| P2 | 工程债（拆 God Object / 43 单测 / detekt 归零） | ✅ 2026-08-15 | `62f1922` `c75fc72` `22e9f8e` `976fd3f` `9f373eb` |
| P4 | 信任与生命周期（旋转单开 / 幽灵权限 / 主题电量 / 引导标记） | ✅ 2026-08-15 | `18c3b9b` `f0f12f3` `bb1dc29` |
| P5 | 工程债深水（字符串资源化 / 接 DI / Preview / 缓存降内存） | ✅ 2026-08-15 | `6785430` `ad54502` `35ca39b` |

---

## P3 — 运营收尾（设备/网络门控）

| # | 事项 | 说明 | 状态 |
|---|---|---|---|
| 3-1 | 确认测试设备 | `USB_SYNC.md` §9（Xiaomi MIUI）vs README（Nikon Z30）统一回填 | ⏳ 待设备 |
| 3-2 | 推送 & 验证 CI | 推送本地未推送 commit；确认 `ktfmtCheck` / `detekt` / `lint` / `test` / `assembleDebug` 全绿（本环境曾 SSH 不可达） | ⏳ 待网络 |
| 3-3 | 真机回归 | Nikon Z30 连接验证 MTP 同步链路（每批改动后必做） | ⏳ 待设备 |
| 3-4 | README 真实化（R9） | ✅ 已完成 | ✅ |

---

## P6 — 发布闭环（设备/环境门控）

| # | 事项 | 说明 | 状态 |
|---|---|---|---|
| 6-1 | CHANGELOG 制度 | `CHANGELOG.md` 已建；**PR 更新 Unreleased 纪律执行**（CLAUDE.md 已声明） | ◐ 持续 |
| 6-2 | 状态诚实化（R19） | ✅ `todo.md` 撤回「0 已知问题」；`CLAUDE.md` Current State 改为发布前状态 | ✅ |
| 6-3 | Play 上架材料 | 隐私政策 ✅ / 权限核对 ✅ / store listing 文案 ✅（`docs/legal/store-listing.md`）；**剩余：截图 + feature graphic（待 Nikon Z30 真机）、隐私政策托管 URL、Play Console 提交** | ◐ |
| 6-4 | 发布后指标 | ✅ `docs/planning/release-metrics.md`（零埋点，从 TransferHistory + 日志聚合） | ✅ |

> **发布阻断重排**：第三期评审新增的两项丢片缺陷（R21/R23）**先于** P6-3 剩余素材推进——素材可后补，丢片不可。

---

## P7 — 大照片库正确性与性能（新增，发布阻断）🔴

> **依据**: [第三期评审](../review/2026-10-01-design-review-3.md)（R20–R41）
> **背景**: 用户反馈「照片很多时不好用顺畅」。过去两期未对大库做专项验证——测试数据均为个位数照片，性能与交互成本从未被评估。本阶段补齐。
> **纪律**: 每项先写 commit message；**R21/R23 必须先补失败测试再修**（当前测试用单张 group，未覆盖成对传输与同名跨存储）。

### P7-A 正确性（丢片级，最先做）✅ 2026-10-01 完成

> R21 `4585770` · R23 `366d36f` · R22 `e1dc004` · R20 `9cbf63d` —— 48 单测全绿；验收记录见 [开发日志 2026-10-01](../development-log/2026-10-01.md)。

#### P7-1 RAW+JPEG 成对传输（R21）✅

- **现状**: `TransferEngine.buildTransferList()` 每 group 只产出 1 个 `photo to handle`（raw 优先），选「全部」时 JPEG 静默丢弃；进度/计数/去重标记与之不匹配
- **动作**: 改为按 `handlesForFormat` 展开为多对（`flatMap`），`selected` 中所有 handle 都参与；`performTransfer` 的 total/成功计数随之正确
- **测试**: 先补 `TransferEngineTest`：RAW+JPEG 双选 → 期望两次 `downloadPhoto` + `lastTransferredHandles` 含两个 handle + `TransferDone.synced == 2`
- **验收**: 选「全部」传输 RAW+JPEG 对，两张都落 MediaStore

#### P7-2 分组键纳入存储/文件夹（R23）✅

- **现状**: `GalleryViewModel.groupByBaseFilename()` 仅按文件名聚合 → 双卡同名照片合并丢一张；`items(key = baseName)` 依赖唯一性，修合并后同 key 会崩
- **动作**: group 键改为 `base + storageId + parentFolder`；`PhotoGroup` 增加稳定 `key`（供 Lazy `items` 使用），`baseName` 仅作显示
- **测试**: `GalleryStateMachineTest` 加：同 `baseName` 不同 `storageId` → 两个 group
- **验收**: 双卡/多文件夹同名照片各自独立、均可传输；网格无重复 key

#### P7-3 传输预览统计口径（R22）✅

- **现状**: `totalGroups = selectedGroups.size` 在 `.take(6)` 之后 → 组数封顶 6，「+N more」恒 0
- **动作**: 先算全量 `allSelectedGroups` 再取前 6；`totalGroups = allSelectedGroups.size`；`remaining = totalGroups - shown.size`
- **验收**: 选 200 组时摘要显示 200 组，出现「+194」

#### P7-4 生命周期与协程作用域所有权（R20）✅

- **现状**: `GalleryViewModel.stop()` 取消并重建 `scope`，但 `ConnectionManager`/`ThumbnailProvider`/`TransferEngine` 构造时按值捕获旧 scope → 旋转后 `connectAndBrowse()` 在死 scope 上 launch，静默失败
- **动作（择一，实施时定稿）**:
  - **A（推荐）**: `stop()` 不再重建 scope，改为「取消子 Job 但保留 scope」——用 `SupervisorJob` 的子 Job 作 `stop()`/`start()` 的作用域边界，或
  - **B**: 让三个协作对象改为读取 `() -> CoroutineScope` 访问器（与现有 `mtp`/`cameraInfo` 一致），`stop()` 替换后自动生效
- **测试**: 单测覆盖「stop 后再次 start，协作对象在活跃 scope 上执行」
- **验收**: 旋转后重连正常枚举、无静默失败

### P7-B 大库性能（流畅度）✅ 2026-10-01 完成

> R26 `e7f3395` · R24 `b529f2e` · R25 `ea852b5` · R27 `f0847e3` · R28 `c1796dc` · R29 `cbff80b` · R30 `a616506`。

#### P7-5 消除勾选触发的全屏重算（R24）✅

- **现状**: `GalleryScreen` 读 `selectedCount` → 勾选重组整个画廊；`BrowsingContent` 每次重算 `filterIsInstance`×3 + 两次 `count` + `getNewPhotoCount()`（每张一次 SharedPreferences 读）
- **动作**:
  1. 选择状态改为**按 key 可观察**（set + 版本），cell 只订阅自身 key，勾选只重绘受影响 cell
  2. `rawCount` / `jpgCount` / `newCount` / `filteredGroups` 全部改 `derivedStateOf`（仅当 `currentPhotos` / `filter` / `sort` 变化时重算，勾选不触发）
  3. `BrowsingContent` 依赖收窄/标注稳定性，避免整块重组
- **验收**: 3000 张下连续勾选无可感知卡顿（可用 `Layout Inspector` recomposition 计数佐证）

#### P7-6 BY_DATE 预分桶（R25）✅

- **现状**: 每个 date section 对全量 `filteredPhotos` 扫一遍，且比较时每张新建 `SimpleDateFormat`
- **动作**: 进入 Browsing 前一次性 `groupBy(日期)` 分桶；`SimpleDateFormat` 提为单例；渲染直接取桶
- **验收**: BY_DATE 模式大库滚动/勾选不掉帧

#### P7-7 选择集改常量时间结构（R26）✅

- **现状**: `selected: SnapshotStateList<Int>`，`selectAll` O(n²)、`isSelected` O(k)、预览 O(n·k)
- **动作**: 改为 `Set<Int>`（或 `mutableStateSetOf`）承载；`handlesForFormat` 结果直接批量 `addAll`；预览用集合运算
- **验收**: 全选 3000 张瞬时完成

#### P7-8 去掉重复 MTP 遍历（R27）✅

- **现状**: `listPhotos()` 前先 `countObjectsInStorage()` 完整 BFS 一遍仅用于进度估算
- **动作**: 删除预估遍历；进度改为「已扫描 N 张」不确定态（或复用单次遍历计数）
- **验收**: 首屏可见照片时间约减半（大卡可感知）

#### P7-9 缩略图管线优化（R28）✅

- **现状**: 预载仅 50、缓存 128/96、请求不可取消且随快速滑动排队、`rotateByDegrees` 未 recycle 源图
- **动作**: 预载跟随可视窗口（按首/尾可见 index 扩窗）；容量随列数/密度调优；旋转后 recycle 源位图或改 `Matrix` 直绘
- **验收**: 大目录快速滑动缩略图跟手、无明显 GC 抖动

#### P7-10 扫描进度持续反馈（R29）✅

- **现状**: 满 30 张切 Browsing 后 UI 完全静默，`currentPhotos` 直到枚举结束才替换
- **动作**: Browsing 后分批（节流）增量更新 `currentPhotos`；顶栏显示「正在扫描… N」持续指示
- **验收**: 大卡扫描全程有可见进展，「新照片」计数渐进逼近终值

#### P7-11 去重表容量治理（R30）✅

- **现状**: `PhotoSyncManager` 记录无上限增长，`clearAll`/`clearStorage` 零调用
- **动作**: 设容量上限（如最近 N 次会话 / M 条 LRU），或「按 storageId+name 去重 + 定期 GC 未复现键」；保留 ADR-008 的 `name:size` 软校验语义
- **验收**: 长期使用后 prefs 条目有界，冷启动不受影响

### P7-C 打磨（P3）✅ 2026-10-01 完成

> R31 `90a716a` · R32 `2dde500` · R33 `878a705` · R34 `90de7ee` · R35 `82e75f7` · R40/R36 `a8c48f1`。

#### P7-12 交互与健壮性收尾（R31–R35）✅

- **R31** 相机页 `PullToRefreshBox(isRefreshing = false)` → 绑定真实刷新状态
- **R32** `runCatching` 不吞 `CancellationException`；`downloadPhoto` 异常路径删除 temp 文件
- **R33** 详情页优先从 MTP 缩略图解析 EXIF，全量下载降级为显式「查看原图」
- **R34** 删 `GalleryStateMachine.filterCacheGeneration` 死状态；`openMtpDevice` 失败时关闭 `usbConnection`
- **R35** 清理 BLE 残留资源（`ic_bluetooth_*` / `ic_location_on_*` / `ic_add_camera_*` / `ic_linked_camera_*` / `avd_syncing_waves` / 模板色）；修 `ObsoleteSdkInt`（`ConnectionManager.kt:95` + `mipmap-anydpi-v26`）
- **附**: `AutoboxingStateCreation` → `mutableIntStateOf`（R40）；lint `Recycle` 为误报，可在 `lint.xml` 抑制并留注释（R36）

---

## 四、明确不做（Apple 减法）

| 项 | 原因 |
|---|---|
| 云备份（Google Photos / Dropbox） | 另一个产品的命题；solo 项目是陷阱 |
| 视频文件支持 | 大文件 + 不同 MTP 处理；非核心使命 |
| 多相机并发 USB | Android 平台硬限制（仅支持一个 USB host 设备） |
| Wi-Fi 传输 | Z30 缺 infra 模式；有线是差异化卖点 |
| 通用 MTP 多厂商支持 | 保持 Nikon-only，直至单设备可靠性 99.9%（第二期评审） |
| Paging / Room 重构 | 先用最小状态与算法修正解决大库卡顿；若 P7 后仍不达标，再另立 ADR 评估 |

---

## 五、验收总览

| ID | 主题 | 对应 review | 验收标准 |
|---|---|---|---|
| P3-1..3-3 | 设备统一 / CI / 真机回归 | — | 待设备与环境 |
| P6-1 | CHANGELOG 纪律 | — | PR 更新 Unreleased |
| P6-3 | 上架材料 | — | 截图 + feature graphic + 托管 URL（待真机） |
| ✅ P7-1 | RAW+JPEG 成对传输 | R21 | 选「全部」两张都落盘 + 新单测绿（2026-10-01） |
| ✅ P7-2 | 分组键纳入存储/文件夹 | R23 | 同名不合并 + 网格无重复 key + 新单测绿（2026-10-01） |
| ✅ P7-3 | 预览统计口径 | R22 | 组数正确 + 「+N more」显示（2026-10-01） |
| ✅ P7-4 | 协程作用域所有权 | R20 | 旋转后重连正常枚举（2026-10-01） |
| ✅ P7-5 | 消除勾选全屏重算 | R24 | 勾选只重绘受影响 cell；计数/分组记忆化（2026-10-01） |
| ✅ P7-6 | BY_DATE 预分桶 | R25 | 单次分桶 + 线程安全格式化（2026-10-01） |
| ✅ P7-7 | 选择集常量时间 | R26 | SnapshotStateMap + 按 key 订阅，全选 O(n)（2026-10-01） |
| ✅ P7-8 | 去重复遍历 | R27 | 单次 BFS，进度为不确定态（2026-10-01） |
| ✅ P7-9 | 缩略图管线 | R28 | 可视窗口预载 + 取消旧预载 + 回收源位图（2026-10-01） |
| ✅ P7-10 | 扫描进度反馈 | R29 | 每 50 张增量刷新 + 「正在扫描… N 张」（2026-10-01） |
| ✅ P7-11 | 去重表容量 | R30 | 追加序号 + 超 1 万条淘汰最旧（2026-10-01） |
| ✅ P7-12 | 交互/健壮性收尾 | R31–R35 | 刷新指示 / 取消语义 / 详情 EXIF / 死代码 / 残留资源（2026-10-01） |
