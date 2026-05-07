# Bug Fix Plan — 2026-05-07

> 真机测试发现的问题，按严重程度分组修复。

## NEF 竖构图元数据参考

```bash
exiftool -fast -G -t -m -q -H "F:\Pictures\Nikon\raw\DSC_0873.NEF"
```

关键字段：
- `EXIF 0x0112 Orientation → Rotate 270 CW`（竖构图）
- `EXIF Thumbnail TIFF`（58KB，相机内嵌缩略图）
- `MakerNotes Preview Image`（125KB，较大预览图）
- MTP `getThumbnail()` 返回的是 camera-embedded preview，Nikon Z30 可能已预旋转

---

## Group A: 布局问题 (P0)

### A1. 存储栏/筛选 Chip/照片网格 层叠错位
**现象**：StorageStatusBar、FilterChipsRow 像绝对定位固定在顶部，照片网格在它们下面但不滚动。

**根因**：`BrowsingContent` 中，`Column { StorageStatusBar + FilterChipsRow }` 和 `PullToRefreshBox { LazyGrid }` 是**兄弟 composable**，没有外层布局包裹，Compose 把它们渲染在同一位置。

**修复**：用外层 `Column` 包起来：
```kotlin
Column(modifier = Modifier.fillMaxSize()) {
    StorageStatusBar(...)
    FilterChipsRow(...)
    PullToRefreshBox(modifier = Modifier.weight(1f)) { LazyGrid }
}
```

### A2. 长按选择后单击应选图而非预览
**现象**：长按进入选择模式后，单击照片弹出预览而不是选择/取消选择。

**根因**：`PhotoCell` 用 `combinedClickable(onClick = 预览, onLongClick = 选择)`。进入选择模式后 onClick 没改行为。

**修复**：当 `_selected.isNotEmpty()` 时，onClick 执行 `onToggle()`；否则执行原有预览逻辑。即 `onClick = { if (vm.selectedCount > 0) onToggle() else onPhotoClick?.invoke() }`

---

## Group B: 照片显示问题 (P1)

### B1. 竖构图照片横向显示
**现象**：NEF 竖拍照片（Orientation=Rotate 270 CW）在网格里横着。

**根因**：`rotateByExif()` 从 MTP 缩略图的 JPEG EXIF 提取 Orientation，但 NEF 的 MTP 缩略图可能：
- 不含 EXIF 数据（无法提取 orientation）
- 已被相机固件预旋转（double rotation）

**修复**：
1. 确认 `extractOrientation()` 能正确提取 MTP 缩略图的 EXIF orientation
2. 如果 NEF 缩略图不含 EXIF，回退从 `MtpObjectInfo.thumbPixWidth/thumbPixHeight` 推断
3. NEF 预览图 orientation 来自 JPEG 内嵌预览，但 exiftool 显示 NEF 的 EXIF Thumbnail 是 TIFF 格式（58KB），可能没有 JPEG EXIF 头。所以 `extractOrientation()` 对 NEF 缩略图可能总是失败。
4. 方案：NEF 文件从 `NikonUsbManager.PhotoInfo.thumbPixWidth/thumbPixHeight` 判断纵横比，宽<高→竖构图。对于有 EXIF 的 JPEG 缩略图，正常提取。

### B2. 照片按日期分组格式不对
**现象**：按日期分组时 section header 和照片顺序错乱。

**根因**：`BrowsingContent` 中 BY_DATE 渲染逻辑：遍历 `dateSections`，每个 section 里又从 `filteredPhotos` 中重新按日期筛选。`groupBy` 如果不排序，section 顺序是 `LinkedHashMap` 的插入顺序（按首次出现的照片日期），不是时间顺序。

**修复**：
1. 在 `loadRoot()` BY_DATE 分支中，对 `dateGroups` 按日期降序排序
2. 在 `BrowsingContent` 中，section 内的 photos 渲染改用 entries 中相邻的 PhotoGroup（而不是重新 filter），避免排序不一致

### B3. PhotoDetailSheet EXIF 为空
**现象**：点击照片弹出的详情页没有 EXIF 数据。

**根因**：`PhotoDetailSheet.extractExif()` 从 MTP thumbnail bytes 提取 EXIF。但 NEF 的 MTP 缩略图是 TIFF 格式（没有 JPEG EXIF 标签），或者缩略图太小不含完整 EXIF。

**修复**：
1. 不依赖 MTP 缩略图提取 EXIF。改用 `MtpDevice.getObjectInfo(handle)` 拿到 `MtpObjectInfo`，提取其中的元数据（dateCreated, thumbPixWidth 等）
2. `NikonUsbManager.PhotoInfo` 已经包含 `thumbPixWidth/thumbPixHeight`，可以用这些
3. 对于 JPEG 文件，尝试从下载的临时文件提取完整 EXIF；对于 NEF，从 `MtpObjectInfo` 获取日期/尺寸基础信息即可

---

## Group C: 下载 & 预览 (P2)

### C1. 下载路径去掉日期分组
**现象**：照片保存到 `Pictures/CameraSync/Nikon Z30/2026-05-07/`，用户希望直接到 `Pictures/CameraSync/Nikon Z30/`。

**修复**：`saveToMediaStore()` 中删掉 `$dateFolder` 路径段。

### C2. 预览图片模糊
**现象**：`PhotoDetailSheet` 和 `TransferPreviewSheet` 用的 MTP 缩略图（~160×120）放大后模糊。

**根因**：MTP `getThumbnail()` 返回的缩略图尺寸小（相机决定）。Nikon Z30 的 MTP thumbnail 可能是 160×120。

**修复**：
1. PhotoDetailSheet：如果已下载到手机，用 MediaStore 的完整图片；否则尝试下载临时文件
2. 网格 PhotoCell：MTP 缩略图够用（小），不需要改
3. TransferPreviewSheet：同 MTP 缩略图，可接受

---

## 执行顺序

### 第一批（并行，不冲突）：
- ~~**Agent A**: A1（布局）+ A2（选择模式交互）~~ ✅
- ~~**Agent B**: C1（下载路径）+ C2（预览质量）~~ ✅

### 第二批（并行）：
- ~~**Agent C**: B1（竖构图旋转）~~ ✅
- ~~**Agent D**: B2（日期分组排序）~~ ✅

### 第三批：
- ~~**Agent E**: B3（EXIF 为空）~~ ✅
- ~~**Agent F**: 完整 EXIF 字段 + NEF 预览重构~~ ✅
