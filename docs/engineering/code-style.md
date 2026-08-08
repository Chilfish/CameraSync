# 代码规范

**项目**: CameraSync | **对标**: Float `docs/engineering/code-style.md` | **最后更新**: 2026-08-09

## Kotlin 代码风格

遵循 [Google Kotlin Style Guide](https://developer.android.com/kotlin/style-guide)，通过 **ktfmt**（kotlinlang 风格）+ **detekt**（含 compose-rules）自动检查。

### 命名约定

| 类型 | 风格 | 示例 |
|---|---|---|
| 类/接口 | PascalCase | `NikonUsbManager`, `PhotoSyncManager` |
| 函数/方法 | camelCase | `markAsImported()`, `enumeratePhotos()` |
| 常量 | UPPER_SNAKE_CASE | `PREFS_NAME`, `ACTION_USB_PERMISSION` |
| Compose 函数 | PascalCase | `GalleryScreen()`, `PhotoDetailSheet()` |
| XML 资源 | snake_case | `nikon_usb_device_filter.xml`, `strings.xml` |
| 包名 | 全小写 + 点分隔 | `dev.sebastiano.camerasync.usb` |

### Compose 约定

- 每个 `@Composable` 函数接受 `Modifier` 参数（放在第一个可选参数位置）
- State 提升到合理的最小层级
- `remember` 只用于临时 UI 状态，业务状态在 ViewModel
- 回调使用 `onXxx` 命名：`onNavigateBack`, `onFolderClick`, `onGroupingChanged`
- Preview 函数标记为 `private`
- **每个 Screen 必须有 `@Preview`**（强制规范，见 `CLAUDE.md`）

### Kotlin 特性使用

```kotlin
// PREFER: expression body
fun isImported(storageId: Int, handle: Int): Boolean = prefs.getBoolean(key(storageId, handle), false)

// PREFER: trailing comma（减少 diff）
data class GalleryEntry(
    val handle: Int,
    val name: String,
    val isRaw: Boolean,
)

// PREFER: sealed interface over sealed class for state
sealed interface GalleryState {
    data object Disconnected : GalleryState
    data class Browsing(
        val cameraInfo: NikonUsbManager.CameraInfo?,
        val storages: List<NikonUsbManager.StorageInfo>,
        val entries: List<GalleryEntry>,
    ) : GalleryState
    data class Transferring(val progress: TransferProgress) : GalleryState
}
```

### 禁止事项

| 禁止 | 替代方案 |
|---|---|
| `!!` 强制解包 | `?.let {}` 或 `?:` 提供默认值 |
| `lateinit var` 可变注入 | 构造函数注入 |
| 全局可变状态 | StateFlow / Compose State |
| 硬编码字符串 | `strings.xml` 资源 |
| `android.util.Log` | `com.juul.khronicle.Log` + 文件级 `private const val TAG` |
| 通配符 import | 显式 import 所有符号 |

## 资源规范

- 所有用户可见字符串在 `res/values/strings.xml`（**中文**），UI 用 `stringResource()` / `context.getString()`
- 颜色用 Material 3 Token；尺寸用语义化 ID

## 状态与协程

- **UDF**：ViewModel 暴露 `mutableStateOf<SealedInterface>`（如 `GalleryState`），Composable 通过 `.value` + `when` 渲染
- **服务级状态**用 `MutableStateFlow`；**响应式列表**用 `SnapshotStateList`（`mutableStateListOf`）
- **Dispatcher 注入**：ViewModel/协调器必须注入 `CoroutineDispatcher`（如 `Dispatchers.IO`），测试用 `runTest` + `advanceUntilIdle()`
- **一次性事件**用 Channel；可重读状态用 StateFlow（见 postmortem `003`）

## 测试规范

- **命名**: `methodName_condition_expectedResult()` 或 backtick 描述式
- **Given-When-Then** 结构，`runTest` + 虚拟时间
- **Fakes 优先于 Mocks** — 数据层测试使用 fake 实现（新接口必须提供 fake）
- **ViewModel 测试** — 注入测试 Dispatcher，`advanceUntilIdle()` 推进
- USB 集成测试需要真机 + Nikon 相机

## Detekt 配置

配置文件位于 `detekt.yml`（根目录）。主要规则：

- `maxIssues: 0` — 零容忍
- 启用 **compose-rules**（18 条）：`ComposableNaming`, `ModifierClickableOrder`, `ModifierMissing`, `MutableStateAutoboxing`, `ViewModelInjection` 等
- 启用 `ForbiddenComment`（STOPSHIP）、`MaxLineLength=150`、`MandatoryBracesLoops`
- 协程规则：`GlobalCoroutineUsage`, `SleepInsteadOfDelay`
- **提交前本地跑 `./gradlew detekt`**（CI 也会跑）
