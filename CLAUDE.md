# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

CameraSync — 基于 USB/MTP 的相机照片有线同步 Android 应用（Nikon 系列）。

## Project Context

- **Package**: `dev.sebastiano.camerasync`
- **Language**: Kotlin 2.3.0 | **AGP**: 8.13.2 | **Min SDK**: 33 | **Target SDK**: 36
- **UI**: Jetpack Compose (BOM 2026.01.00) + Material 3 + Navigation 3（type-safe，`@Serializable` routes）
- **Architecture**: MVVM + UDF，单模块 `:app`，USB/MTP 同步管线状态机
- **DI**: Metro（编译期依赖注入，`@DependencyGraph AppGraph`）
- **图片加载**: Coil 3.x（本地照片 + MediaStore 查询）
- **持久化**: SharedPreferences（USB 去重 + 偏好设置）
- **Logging**: Khronicle（`com.juul.khronicle.Log`，禁止 `android.util.Log`）
- **Format/Static**: ktfmt（kotlinlang 风格）+ detekt（含 compose-rules）
- **VCS**: GitHub，Conventional Commits
- **UI 语言**: 中文（`res/values/strings.xml`）
- **规范对标**: 本项目流程与规范对标 Float（`I:\dev\Float`）——commit 纪律、文档先行、postmortem、git hooks 均借鉴自该仓库

## Essential Commands

```bash
./gradlew assembleDebug                   # 完整构建
./gradlew testDebugUnitTest               # 单元测试
./gradlew ktfmtCheck                      # 格式检查
./gradlew ktfmtFormat                     # 格式化（提交前）
./gradlew detekt                          # 静态分析（含 compose-rules）
./gradlew lintDebug                       # Android lint
./gradlew detekt ktfmtCheck lintDebug testDebugUnitTest assembleDebug  # CI gate（push 前跑）

# Git hooks
git config core.hooksPath .githooks       # 启用 pre-push hook
bash .githooks/pre-push                   # 手动运行（CI gate）
```

## Project Structure & Key Patterns

### Source Layout

单模块 `:app`，源码在 `app/src/main/kotlin/dev/sebastiano/camerasync/`：

| 目录 | 职责 |
|---|---|
| `usb/` | 主功能：USB/MTP 照片同步管线（Nikon 相机） |
| `logging/` | Khronicle 日志仓库（`LogcatLogRepository`）+ 日志查看器（`LogViewer*`） |
| `settings/` | 设置页（主题、RAW/JPEG 分组、排序、下载格式、网格列数） |
| `ui/theme/` | Material 3 主题（`Color.kt` / `Type.kt` / `Theme.kt`） |
| `di/` | Metro 依赖注入（`AppGraph` / `Metro*Factory` / `ActivityKey`） |
| 根包 | `CameraSyncApp` / `MainActivity` / `NavRoute` |

### USB Photo Sync Pipeline（`usb/`）

- `NikonUsbManager` — `android.mtp.MtpDevice` 封装：USB 权限（PendingIntent + BroadcastReceiver）、存储/文件夹 BFS 遍历、对象读取
- `GalleryViewModel` — 核心状态机（sealed interface `GalleryState`）：`Disconnected → Connecting → Loading → Browsing / Empty / Error → Transferring → TransferDone`
- `PhotoSyncManager` — SharedPreferences 去重（`storageId + handle` 键 + `name:size` 身份软校验，跨会话，身份不匹配视为未导入）
- `LocalPhotosViewModel` — Coil 3 + MediaStore 加载本地照片
- `UsbSyncPreferences` / `PhotoDetailSheet` / `TransferHistoryScreen` / `GalleryScreen` / `GalleryFolderScreen` / `FirstRunGuideScreen`

### Navigation 3（`MainActivity.kt` + `NavRoute.kt`）

- `NavRoute` 是 `@Parcelize @Serializable` sealed interface：`Gallery` / `GalleryFolder(storageId, folderHandle, folderName)` / `FirstRunGuide` / `LogViewer` / `Settings` / `TransferHistory`
- `MainActivity` 用 `NavDisplay(backStack, …)` + `NavEntry(key)` + `when(key)` 手工映射路由（非 XML 图）

### DI（Metro）

`@DependencyGraph interface AppGraph` + `@Provides`（Application Context / IO Dispatcher / LogRepository）+ `MetroViewModelFactory`；`MainActivity` 用 `@Inject`。新增依赖在 `AppGraph` 声明 `@Provides` 或用构造注入。

## Key Conventions

- **文档先行（docs-first）**：每个任务先更新相关文档（`docs/development-log/`、`docs/planning/`、方案文档），再写代码；实施过程中随反馈同步修改，而非事后补记。已完成阶段的文档归档到 `docs/archive/`——**历史记录，不主动读取**
- **UDF**：ViewModel 暴露 `mutableStateOf<SealedInterface>` 状态，Composable 通过 `.value` + `when` 渲染；业务状态在 ViewModel，`remember` 只用于临时 UI 状态
- **Dispatcher 注入**：ViewModel/协调器必须注入 `CoroutineDispatcher`（如 `Dispatchers.IO`），测试用 `runTest` + `advanceUntilIdle()`
- **Fakes over Mocks**：新接口必须提供 fake 实现（便于测试）
- **资源**：所有用户可见字符串在 `res/values/strings.xml`（中文），用 `stringResource()` / `context.getString()`
- **日志**：一律 `com.juul.khronicle.Log`，文件级 `private const val TAG`；禁止 `android.util.Log`
- **禁止**：`!!`、`lateinit var`、通配符 import、硬编码字符串/颜色/尺寸

### 🔴 强制规范（必须遵循）

1. **先写 commit message 再写代码**（详见 `docs/engineering/git-workflow.md#commit-纪律`）。每个 commit 是单一关注点的原子提交，避免上帝 commit；当 diff >10 文件或 >200 行时必须拆分。模块创建、功能实现、配置修改、文档更新分开 commit。

2. **每个 `@Composable` Screen 必须有 `@Preview`**（详见 `docs/engineering/git-workflow.md#审查清单`）。Preview 是 E2E 测试的基础，多状态组件每个状态一个 Preview，与 UI 代码同编写，不可延后。

3. **提交前本地跑 `./gradlew detekt` + `./gradlew ktfmtCheck`**，确认通过再走，不要等 pre-push hook/CI 才发现违规（见 `docs/postmortem/README.md`）。

4. **开写代码前先读尸检报告**（`docs/postmortem/README.md`）：本仓库历史踩坑沉淀于此，对照「高频雷区」自查。遇到新的返工/事故按 [TEMPLATE.md](docs/postmortem/TEMPLATE.md) 沉淀一条 postmortem。

## Current State

**生产就绪 v2.3**：0 known issues（已完成阶段文档归档于 `docs/archive/`，see `docs/README.md`）。

- USB/MTP 照片同步是**唯一**功能路径；BLE GPS 子系统已于 2026-08-02 移除（commit `a385378`，Ricoh/Sony 文档归档于 `docs/ricoh/`、`docs/sony/` 供历史查阅）
- 活跃文档：`docs/README.md`（索引）、`docs/development-log/`（按天开发日志）、`docs/planning/`（规划）、`docs/engineering/`（工程规范）
- 测试设备：Nikon Z30（USB）

## Git Hooks

Pre-push hook 在 `.githooks/pre-push` → `detekt + ktfmtCheck + lint + test + assembleDebug`。启用：`git config core.hooksPath .githooks`。手动运行：`bash .githooks/pre-push`。
