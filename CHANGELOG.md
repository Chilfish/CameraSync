# Changelog

All notable changes to CameraSync will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/lang/zh-CN/).

> **v1.0.0 已于 2026-10-01 通过 GitHub Release 发行**（内部阶段标签 v2.3）。此后变更记于新的 `[Unreleased]` 段。
> 版本单源在 `gradle.properties`，发版走 `scripts/release.sh`（见 `docs/engineering/release-checklist.md`）。

## [Unreleased]

### 架构 — 传输抽象 `CameraSource`（Phase 0，纯重构）

- 新增 `camera/` 包：`CameraSource` 接口 + 传输无关模型（`CameraInfo` / `StorageInfo` / `PhotoInfo` / `FolderInfo`），来自 [ADR-011](docs/planning/architecture.md)
- `NikonUsbManager` → `UsbCameraSource`（实现 `CameraSource`）；`ConnectionManager` / `ThumbnailProvider` / `TransferEngine` / `GalleryViewModel` 不再传 `android.mtp.MtpDevice`，只依赖接口
- 新增测试 fake `FakeCameraSource`（Fakes over Mocks）；USB 全链路行为不变
- 立项无线方向（WiFi/PTP-IP 事件驱动）：[`docs/planning/wireless-transfer.md`](docs/planning/wireless-transfer.md)

## [1.0.0] - 2026-10-01

### 发布闭环与发行渠道（2026-10-01）

- **发行渠道确定为仅 GitHub Release**（不上架 Google Play）：`action-plan` P6-3 标记不做，`docs/legal/store-listing.md` 归档至 `docs/archive/`
- **`release.yml` 改为推 tag `v*` 触发**：原先监听 `release: published`，而 `scripts/release.sh` 只推 tag、不建 Release，发布工作流永不触发；改为推 tag 即解码 keystore → 构建签名 APK → **自动创建 GitHub Release**（`generate_release_notes`），并去掉多余的 `RELEASE_TOKEN` PAT（改用默认 `GITHUB_TOKEN`）
- `release-checklist.md` 新增「首次发版准备（keystore + Secrets）」；签名 keystore 与 4 个 Secrets 已配置；真机回归（Nikon Z30，含双卡 / 大库）通过

### 新增 — NEF 内嵌 JPEG 预览（2026-10-01）

- 本地相册的 RAW 不再显示灰色占位：新增 Coil `Fetcher`，从 NEF 提取内嵌的最大 JPEG 预览渲染（`NefPreview` / `NefFetcher`），带 8 条 LRU 缓存；仅接管 `.nef` / `.nrw`，找不到预览时回落原有占位

### 工程（2026-10-01）

- **拆分 `GalleryScreen.kt`**（2518 行 → 6 个文件，最大 736 行），纯移动：`GalleryBrowsing` / `GalleryTransferUi` / `GalleryLocalTab` / `ExifValue` / `GalleryPreviews`
- **lint 74 → 26 warnings**：删除 4 个未用 drawable、偏好设置改 KTX `edit {}`、`Uri.parse` → `String.toUri()`、按文件抑制 `gms_fonts_certs.xml` 的 Typos 误报
- 单测 54 → **61 全绿**（新增 NEF 预览提取 7 条）

### 文档与流程（对标 Float，2026-08-15）

- 完全对标 Float（`I:\dev\Float`）的流程与规范，差距清单见 `docs/planning/benchmark-float.md`
- 新增 `CHANGELOG.md` / `docs/requirements/`（PRD、Glossary、User Stories、Feature Spec）/ `docs/planning/architecture.md`（ADR-001..010）/ `docs/legal/privacy-policy.md`
- 新增 GitHub Issue（bug / feature / tech-debt）与 PR 模板；`git-workflow.md` 补齐 PR 流程、gh CLI、AI Code Review、Issue 管理章节
- 版本单源迁至 `gradle.properties`（`VERSION_NAME` / `VERSION_CODE`）+ 新增 `scripts/release.sh`（门禁 → commit → tag → push）；新增 `docs/engineering/release-checklist.md`、`adb-commands.md`
- 文档漂移修复：README「Background Sync」条目删除（自动同步已移除）、code-style「postmortem 003」悬空引用改指本仓「设计/建模」节、删除 BLE 残留 workflow

### CI（2026-10-01）

- 新增 `.github/workflows/manual-apk.yml`：手动触发（`workflow_dispatch`）的打包工作流，供真机验证取包——输入 `build_type`（debug/release）+ `run_gate`；release 复用 `release.yml` 的签名 Secrets 并顺带验证 R8；产物按版本 + 短 SHA 命名并附 `SHA256SUMS.txt`，上传 artifact（14 天）
- 文档同步：`CLAUDE.md` 新增「CI（GitHub Actions）」节；`git-workflow.md` 补 CI 工作流说明；`release-checklist.md` 补「获取验证包（手动工作流）」；`docs/README.md` 更新索引

### 文档与评审（2026-10-01）

- 新增第三期设计评审（大库场景专项，R20–R41）：`docs/review/2026-10-01-design-review-3.md`——含 2 项丢片级缺陷（RAW+JPEG 未成对传输、同名跨存储照片被合并）与 7 项大库性能发现
- 行动计划重排：新增 **P7 大照片库正确性与性能**；已完成阶段 P0–P5 归档至 `docs/archive/ACTION_PLAN_P0-P5.md`
- 文档维护：补 `development-log/README.md` 与 `review/README.md` 遗漏索引；`todo.md` 状态真实化（撤回生产就绪）并将已移除的 BLE 条目移出「已完成功能」
- 构建验证（clean 全量）：`assembleDebug` / `bundleRelease` / 43 单测 / detekt / ktfmt 通过；lint 0 errors

### 修复（2026-10-01，第三期评审 P7-A）

- 修复 RAW+JPEG 成对传输：选「全部」时 JPEG 不再被静默丢弃——`buildTransferList` 按选中 handle 展开（R21）
- 修复同名照片跨存储/文件夹被合并丢一张；分组键改为 `base + storageId + parentFolder`，`PhotoGroup` 增加稳定 `key`（R23）
- 修复传输预览统计口径：`totalGroups` 在 `.take(6)` 之前取值，「+N more」恢复正常（R22）
- 修复协程作用域所有权：协作对象改为读取 `() -> CoroutineScope`，旋转/停止后重启不再在已取消 scope 上静默失败（R20）
- 单元测试 43 → **48 全绿**（新增成对传输、跨存储/文件夹分组、scope 契约回归测试）

> P7 全部闭环（P7-A 正确性 / P7-B 性能 / P7-C 打磨）；真机回归（Nikon Z30，含双卡/大库）待做。

### 性能与修复（2026-10-01，第三期评审 P7-B / P7-C）

- 大库性能：勾选不再重组整屏，派生计数/导入标记按相机列表记忆化（R24）；BY_DATE 单次分桶 + 线程安全日期格式（R25）；选择集改 `SnapshotStateMap`、全选 O(n)（R26）；去掉枚举前的重复 BFS（R27）；缩略图按可视窗口预载并可取消、旋转后回收源位图（R28）；扫描中每 50 张增量刷新并显示「正在扫描… N 张」（R29）；去重表超 1 万条淘汰最旧（R30）
- 交互与健壮性：下拉刷新绑定真实状态（R31）；不吞 `CancellationException` 且失败/取消后删除临时文件（R32）；详情页 EXIF 取自 MTP 缩略图、全量下载改为显式「查看原图」（R33）；删死代码 `filterCacheGeneration`、`openMtpDevice` 失败时关闭 USB 连接（R34）；清理 BLE 残留资源、冗余 `-v26` 目录与恒真 SDK 判断（R35）
- 工程：`gridColumns` 改 `mutableIntStateOf`（R40）；`Recycle` 误报以 `app/lint.xml` 文档化抑制（R36）
- 单元测试 48 → **54 全绿**；lint 96 → **74 warnings / 0 errors**

### Added — USB 照片同步（核心）

- USB MTP 连接、照片枚举、文件夹导航、3 列网格画廊、渐进式照片加载（先 30 张，后台继续）
- RAW + JPEG 分组（NEF/JPG 对 + RAW 徽章）、EXIF 详情面板（快门/光圈/ISO/焦距/镜头）
- 长按多选批量传输、传输速度与 ETA、传输完成回看（本次传输清单）
- 去重（SharedPreferences，`storageId + handle` 键 + `name:size` 身份软校验，跨会话）
- 筛选（全部 / 新照片 / RAW / JPEG，默认新照片）、三种分组、五种排序、网格密度切换
- 失败重试、传输历史、从相机删除照片、触觉反馈、存储空间状态栏
- 冷启动 MTP 模式引导（首启一屏，设置页可重开）

### Added — 本地相册

- Coil 3 图片加载（替代裸 BitmapFactory）、MediaStore 查询（Android 13+ 分区存储）
- 目录浏览（仿 USB 文件夹导航）、面包屑导航、本地 EXIF 详情面板、下拉刷新

### Added — 设置与主题

- 设置页（分组、排序、下载格式、主题、网格密度）、深色主题三态（跟随系统/浅色/深色）、中文字符串资源化

### Added — 基础设施

- Metro 编译期 DI、Khronicle 日志引擎 + 日志查看器、Material 3 主题系统、调度器注入（可测试性）
- detekt（含 compose-rules）+ ktfmt 质量门禁、`.githooks/pre-push`、GitHub Actions CI

### Removed

- BLE GPS 同步子系统（Ricoh / Sony）于 2026-08-02 移除（commit `a385378`），协议文档归档 `docs/ricoh/`、`docs/sony/`
- 未接线的自动同步死代码（`UsbSyncService` / `UsbSyncCoordinator` / 无效开关）于 2026-08-09 移除（YAGNI）

### 工程（2026-08-09 → 08-15）

- 文档体系对标 Float：CLAUDE.md 真实化、AGENTS.md 弃用、engineering / postmortem / development-log / archive / review 体系搭建
- Apple 视角设计评审两期（R1–R19）落地与处置：双管线收敛、去重键统一、剪枝真实化、保存路径真实化、核心路径打磨、God Object 拆分、评审 R8 生命周期 / R12 幽灵权限等进入 P4–P6 行动计划

### P5 工程债深水（2026-08-15）

- **字符串资源化（R10）**：全部用户可见中文移入 `strings.xml`（含 VM/Manager 状态消息、EXIF 标签与取值）；EXIF 提取纯函数化（`ExifValue`，渲染时解析）；清理 52 条未用字符串
- **核心路径接 DI（R14）**：`AppGraph` 提供 `NikonUsbManager` / `GalleryViewModel` / `LocalPhotosViewModel`（`@SingleIn`），GalleryViewModel 构造注入 dispatcher
- **核心屏 Preview（R15）**：Gallery 八状态 + Settings + TransferHistory 各一 `@Preview`；抽 `GalleryScreenHost` 接口与 `TransferDonePanel` 纯渲染支撑可预览
- **缓存降内存（R17）**：fullPhotoCache 由 12 条内存字节数组改为磁盘 temp 文件 LRU 3，EXIF 走路径构造
- **权限核对（P6-3 联动）**：移除 `VIBRATE` / `READ_EXTERNAL_STORAGE` / `WRITE_EXTERNAL_STORAGE` / `READ_MEDIA_IMAGES`（minSdk 33 下均无用或零运行时请求）；隐私政策同步消除「前台服务通知」「release 仅 WARN+」不实声明
- **发布后观测（P6-4）**：新增 `docs/planning/release-metrics.md`（零埋点，从 TransferHistory + 日志聚合对齐 PRD 成功指标）
- **上架材料（P6-3）**：新增 `docs/legal/store-listing.md`（Play Store listing 文案——短/长描述、What's new、Data Safety 表单口径、内容分级、素材清单、上架前核对清单）；截图与隐私政策托管待真机/托管环境
