# 本地相册重构方案 — Local Photos Refactoring

> **Date**: 2026-06-19  
> **Status**: 执行中  
> **Branch**: `refactor/local-photos-coil`

## 1. 背景 & 调研

### 1.1 当前问题

`LocalPhotosViewModel` 使用裸 `BitmapFactory` 加载本地照片，存在严重稳定性问题：

| 问题 | 根因 | 影响 |
|------|------|------|
| 加载时闪退 | 30+ 个 `LaunchedEffect` 同时 `decodeFile`，NEF 20-40MB，OOM | 🔴 致命 |
| 无缩略图缓存 | 每次 recompose 重新解码 | 🟡 性能差 |
| 无并发控制 | `LazyVerticalStaggeredGrid` 所有可见 cell 同时加载 | 🔴 内存风暴 |
| 无错误隔离 | 单文件解码失败 → 全屏崩溃 | 🔴 不稳定 |
| 无目录浏览 | `collectFiles()` 递归 flatten，无法按目录查看 | 🟡 体验差 |
| NEF 方向检测不稳定 | `BitmapFactory.decodeFile` on TIFF-based NEF 可能失败 | 🟡 显示错乱 |

### 1.2 业界方案调研

#### Google Photos 架构（原文：stack.convex.dev/mobile-first）

由前 Google Photos Android 技术负责人撰写：

> "We synced the metadata of photos on the device into a similar structure as the cloud metadata in the same SQLite database. We then created a new synthetic table that was the union of the two libraries. The union key was the file hash of the original file."

核心设计决策：
- **SQLite 元数据索引**：每张照片 <1KB，100 万张 ≈ 1GB 元数据（可接受）
- **分页加载**：从 DB 按需加载当前页面的元数据，滚出后丢弃
- **缩略图与全图分离**：网格用缩略图，点击后才加载全图
- **本地 + 云端 Union**：同一个 grid 里混合显示

#### Android 官方推荐（developer.android.com）

官方 `<social-and-messaging/guides/media-thumbnails>` 文档明确：

> "Image loading libraries do a lot of the heavy lifting for you. The following code demonstrates the use of the Coil image loading library."

推荐优先级：
1. **Coil / Glide**（首选，自动处理缓存、采样、并发）
2. `ContentResolver.loadThumbnail()`（API 29+，系统级缩略图）
3. `ThumbnailUtils.createImageThumbnail()`（文件路径可用时）
4. `ImageDecoder`（API 28+，支持重采样）
5. `BitmapFactory`（最低级 fallback）

#### Coil 3.x（coil-kt.github.io/coil）

- `AsyncImage(model = File(...))` 直接支持本地文件
- `ImageRequest.Builder(context).data(file).size(360, 360).build()` 精确控制
- 内置内存缓存 + 磁盘缓存（默认 ~250MB）
- `addLastModifiedToFileCacheKey` — 文件修改时间参与缓存 key
- 支持自定义 `Fetcher` 和 `Decoder`，适合 NEF 等非标准格式
- Compose 原生集成，自动跟随 `LazyVerticalStaggeredGrid` 生命周期（暂停/取消离屏请求）

### 1.3 决策

**采用 Coil 3.x（`coil-compose`）+ MediaStore 查询**。

理由：
- Google 官方推荐、Android 生态标准答案
- 自带 LRU 内存缓存 + 磁盘缓存，不需要手写
- 自动处理并发、OOM、采样、EXIF 方向
- Compose 原生支持 `AsyncImage`，一行代码替代当前 50+ 行
- 无需新引入 Glide（更重）或手写缓存（已经失败）

## 2. 技术方案

### 2.1 架构变更

```
Before (current):                    After (target):
──────────                           ────────
LocalPhotosViewModel                 LocalPhotosViewModel
├── File.listFiles() (不可靠)        ├── MediaStore query (可靠)
├── collectFiles() 递归 flatten      ├── 按 BUCKET_DISPLAY_NAME 分组 → 目录浏览
├── 无缓存                            ├── Coil ImageLoader (内存+磁盘缓存)
├── BitmapFactory 裸解                ├── AsyncImage / SubcomposeAsyncImage
├── 无分页                            ├── 虚拟分页（LazyVerticalStaggeredGrid 天然支持）
└── 单层列表                          └── 目录层级导航（仿 USB folder browsing）
```

### 2.2 新增依赖

```toml
# gradle/libs.versions.toml
[versions]
coil = "3.5.0"

[libraries]
coil-compose = { group = "io.coil-kt.coil3", name = "coil-compose", version.ref = "coil" }
coil-network-okhttp = { group = "io.coil-kt.coil3", name = "coil-network-okhttp", version.ref = "coil" }
```

### 2.3 数据层：重写 LocalPhotosViewModel

```kotlin
// 核心变更：
// 1. 数据源：MediaStore Cursor → LocalPhoto entries
// 2. 目录浏览：query by RELATIVE_PATH / BUCKET_DISPLAY_NAME
// 3. 移除手写 BitmapFactory 所有代码
// 4. 移除 orientationCache（Coil 自动处理 EXIF）
// 5. 保留 RAW+JPEG 分组逻辑
```

**MediaStore 查询策略**：

```kotlin
// 目录列表：SELECT DISTINCT RELATIVE_PATH FROM MediaStore.Images
// 照片列表：SELECT _ID, DATA, DISPLAY_NAME, DATE_MODIFIED, SIZE, MIME_TYPE, WIDTH, HEIGHT, ORIENTATION
//           FROM MediaStore.Images WHERE RELATIVE_PATH LIKE ?
// NEF 文件：通过 MediaStore.Files 补充查询
```

### 2.4 UI 层：用 AsyncImage 替代 LocalPhotoCell

```kotlin
// Before: 50+ 行手写 BitmapFactory + 旋转 + 缓存
// After: 5 行 Coil
AsyncImage(
    model = ImageRequest.Builder(LocalContext.current)
        .data(group.thumbnailFile)
        .size(360, 360)
        .crossfade(true)
        .build(),
    contentDescription = group.baseName,
    modifier = Modifier.fillMaxWidth().aspectRatio(3f / 2f),
    contentScale = ContentScale.Crop,
)
```

### 2.5 目录浏览

仿照 USB 相册的 folder browsing 模式：

```
GalleryScreen (root)
├── Tab: USB 同步 (现有)
├── Tab: 本地相册 (新)
│   ├── 顶层: 显示 Pictures/CameraSync/ 下的子目录列表
│   ├── 点击目录 → 进入该目录，显示照片网格
│   ├── 顶部面包屑: CameraSync > Nikon Z30
│   └── 长按选择 + 批量操作（分享/删除/导出）
└── Tab: BLE 设备 (现有)
```

### 2.6 NEF 处理策略

Coil 自带 `BitmapFactoryDecoder` 无法解码 NEF（TIFF-based proprietary），需要自定义：

```kotlin
// 选项 A: 自定义 Fetcher — NEF 文件 → 提取内嵌 JPEG 预览 → 交给标准 Decoder
class NefFetcher : Fetcher<File> {
    override suspend fun fetch(): SourceResult {
        // 使用 ExifInterface 提取 NEF 中的 JPEG 预览
        // Nikon Z30 NEF: EXIF Thumbnail 是 58KB TIFF, MakerNotes Preview 是 125KB JPEG
        val preview = extractNefPreview(file)
        return SourceResult(preview, "image/jpeg")
    }
}

// 选项 B: 降级方案 — 对 NEF 使用 File 作为 model,
// Coil 会尝试 BitmapFactory（可能失败）→ 显示 error placeholder → 点击时下载全图
```

**推荐选项 B**（MVP 阶段）+ 后续加选项 A。NEF 网格预览可以 fallback 到灰色占位符，不影响稳定性。

## 3. 实施步骤

### Phase 1: 添加 Coil 依赖 & 配置 ✅ (2026-06-19)

- [x] `gradle/libs.versions.toml`: 添加 coil 版本和依赖
- [x] `app/build.gradle.kts`: 添加 `coil-compose` + `coil-network-okhttp`
- [x] `CameraSyncApp.kt`: 实现 `SingletonImageLoader.Factory`，配置 `ImageLoader`
- [ ] 验证：`./gradlew build` 通过（进行中）

### Phase 2: 重写 LocalPhotosViewModel ✅ (2026-06-19)

- [x] 删除手写 `loadThumbnail()`、`calculateInSampleSize()`
- [x] 删除 `orientationCache`（Coil 自动处理）
- [x] 删除 `populateOrientationsFromDimensions()`（不再需要）
- [x] 删除 `preloadOrientations()`（不再需要）
- [x] 改为纯 MediaStore Cursor 查询（`queryFolders` + `queryPhotos`）
- [x] 保留 `groupByBaseName()`、`LocalPhoto`、`LocalPhotoGroup` 数据模型
- [x] 新增 `LocalFolder` 数据模型
- [x] 新增 `loadRoot()` — 加载根目录的文件夹 + 照片
- [x] 新增 `enterFolder(relativePath)` — 按目录加载
- [x] 新增 `goBack()` / `refresh()` 导航方法
- [x] 新增 `Mutex` 防止重复 scan

### Phase 3: 重写 LocalTabContent UI ✅ (2026-06-19)

- [x] 重写 `LocalPhotoCell` → `AsyncImage` + Coil `ImageRequest`
- [x] 新增 `LocalBreadcrumb` 面包屑导航
- [x] 新增 `LocalFolderCell` 目录卡片（FullLine span）
- [x] 重写 `LocalPhotoDetail` → `AsyncImage` 全图 + EXIF 提取
- [x] 错误处理：Coil 自动 fallback 到 placeholder/error painter
- [x] RAW 标记保留

### Phase 4: 删除旧代码 & 统一导出

- [ ] 删除 `res/xml/nikon_usb_device_filter.xml` 中的冗余
- [ ] 确保 `AndroidManifest.xml` 权限声明完整
- [ ] 移除不再需要的 `MANAGE_EXTERNAL_STORAGE` 提示（MediaStore 不需要）
- [ ] 运行 `./gradlew ktfmtFormat`

### Phase 5: 测试 & 回归验证（1h）

- [ ] 运行现有测试: `./gradlew test`
- [ ] 新增: `LocalPhotosViewModelTest` — MediaStore query mock
- [ ] 真机验证：Pixel 9 + Android 15 + NEF/JPEG 混合目录

## 4. 风险评估

| 风险 | 概率 | 缓解措施 |
|------|------|----------|
| Coil 不支持 NEF 预览 | 高 | Phase 2 降级为灰色 placeholder，Phase 4 加 NefFetcher |
| MediaStore 未索引 NEF | 中 | `MediaStore.Files` fallback 查询；保留 `File.listFiles()` 作为 tertiary fallback |
| `AsyncImage` 与 `LazyVerticalStaggeredGrid` 的 key 冲突 | 低 | 使用 `key = { it.cacheKey }`，与当前一致 |
| Coil 3.x breaking changes vs Coil 2 | 低 | 项目不使用 Coil 2，无迁移成本 |

## 5. 参考文档

- [Google Photos 架构](https://stack.convex.dev/mobile-first) — SQLite 元数据索引 + 分页策略
- [Android Media Thumbnails Guide](https://developer.android.com/social-and-messaging/guides/media-thumbnails) — Coil 推荐 + 各级 API 对比
- [Coil 3.x 文档](https://coil-kt.github.io/coil/) — AsyncImage, ImageLoader, Cache 配置
- [Android MediaStore Guide](https://developer.android.com/training/data-storage/shared/media) — Cursor query, loadThumbnail

---

*本文档由 Alma (睦) 在调研后撰写，用于指导重构实施。*
