# CameraSync — 当前状态 & 未来规划

> 最后更新: 2026-08-09 | 分支: `master`

---

## 当前状态: ✅ 生产就绪 (v2.3)

所有计划的 Sprint (1–4) 已完成，0 个已知问题。

### 已完成功能总览

#### USB 照片同步 (核心)
- [x] USB MTP 连接、照片枚举、下载
- [x] 画廊浏览 (3 列网格、文件夹导航)
- [x] RAW+JPEG 分组 (NEF/JPG 对 + "RAW" 徽章)
- [x] 长按多选 + 批量传输
- [x] 去重 (SharedPreferences)
- [x] 传输速度 & ETA 显示
- [x] 传输完成操作面板 (查看/分享/删除 + 本次传输清单)
- [x] 触觉反馈
- [x] 存储空间状态栏
- [x] 筛选芯片 (全部/新照片/RAW/JPEG，默认新照片)
- [x] EXIF 详情面板 (快门/光圈/ISO/焦距/镜头等)
- [x] 从相机删除照片
- [x] 网格密度切换 (2/3/4 列)
- [x] 传输历史记录
- [x] 失败重试
- [x] 设置页面 (分组、排序、下载格式、主题、网格密度)
- [x] 深色主题 (跟随系统/浅色/深色)
- [x] 渐进式照片加载 (先显示 30 张，后台继续)
- [x] 三种照片分组模式 (按文件夹/按日期/不分组)
- [x] 五种排序方式 (最新优先/按名称/按大小等)
- [x] 下载格式偏好 (全部/仅 JPEG/仅 RAW)
- [x] 传输预览面板 (缩略图、大小统计)

#### 本地相册
- [x] Coil 3.x 图片加载 (替代裸 BitmapFactory)
- [x] MediaStore 查询 (Android 13+ 分区存储兼容)
- [x] 目录浏览 (仿 USB 文件夹导航)
- [x] 面包屑导航
- [x] 本地 EXIF 详情面板
- [x] 下拉刷新

#### BLE GPS 同步 (次要)
- [x] Ricoh GR 系列 GPS + 时间同步
- [x] Sony Alpha 系列 GPS + 时间同步
- [x] 多设备并发同步
- [x] BLE 固件更新检查
- [x] Nikon BLE 死代码已删除

#### 基础设施
- [x] Metro 编译时 DI
- [x] Khronicle 日志引擎 + 日志查看器
- [x] 中文字符串资源化 (stringResource)
- [x] Coil ImageLoader (SingletonImageLoader.Factory)
- [x] 主题系统 (Material 3 + Google Sans Flex 字体)
- [x] 单元测试 (LogcatLogParser, LocalPhotosViewModel)
- [x] 调度器注入 (可测试性)

---

## 延期项目

| 功能 | 原因 |
|------|------|
| 云备份集成 (Google Photos, Dropbox) | 需要云服务对接 |
| 视频文件支持 | 大文件 + 不同 MTP 处理 |
| 多相机并发 USB | Android 仅支持一个 USB 主机设备 |
| NEF Coil 自定义 Fetcher (提取内嵌 JPEG 预览) | MVP 阶段降级为灰色占位符 |

---

## 下一步行动计划

> **活跃行动计划已迁移** → [`action-plan.md`](action-plan.md)（2026-08-09，Apple 视角评审后重排：P0 止血 → P1 核心路径 → P2 工程债 → P3 收尾）。
> 本段只保留旧 P0–P3 的完成记录与去向；新任务一律在 action-plan.md 追踪（docs-first，完成一项勾一项）。

### 旧 P0 — 修复 CI 门禁（ktfmtCheck 红色） ✅

`bcb7bee perf(usb)` 引入 9 个未格式化文件，`e873861 style: apply ktfmtFormat` 已修复；detekt-baseline 同步清理 10 条 UnusedImports（34 → 24）。

### 旧 P1 — 确认测试设备 → 并入 action-plan P3-1

测试设备三处说法矛盾（`USB_SYNC.md` §9 = Xiaomi MIUI；README/CONTRIBUTING = Pixel 9）未闭环，回填见 action-plan P3-1。

### 旧 P2 — 偿还 detekt baseline 债务 → action-plan P2-3

baseline 剩余 **24 条**违规，逐步修复后从 `detekt-baseline.xml` 移除，最终归零。

### 旧 P3 — 推送 & 验证 CI → action-plan P3-2

推送本地未推送 commit（领先远程 5 个）+ 确认 GitHub CI 全绿。

---

## 技术栈

| 组件 | 版本/选择 |
|------|----------|
| Kotlin | 2.3.0 |
| Compose | Material 3 + BOM |
| 图片加载 | Coil 3.x (compose + okhttp) |
| DI | Metro (compile-time) |
| 日志 | Khronicle (com.juul.khronicle) |
| 持久化 | SharedPreferences (USB prefs + dedup) |
| 构建 | Gradle Kotlin DSL + version catalog |
| 最低 SDK | API 33 (Android 13) |
| 测试设备 | Nikon Z30 (见 [USB_SYNC.md](../nikon/USB_SYNC.md#9-verified-with) 已验证设备) |

---

## 项目结构

```
app/src/main/kotlin/dev/sebastiano/camerasync/
├── usb/                          # ★ USB 照片同步 (主功能)
│   ├── NikonUsbManager.kt        # MTP 设备操作
│   ├── GalleryViewModel.kt       # 连接生命周期 + 传输状态 + 筛选
│   ├── GalleryScreen.kt          # 主 UI (网格/文件夹/选择/进度)
│   ├── PhotoSyncManager.kt       # 导入去重
│   ├── PhotoDetailSheet.kt       # EXIF 详情面板
│   ├── TransferHistoryScreen.kt  # 传输历史
│   ├── LocalPhotosViewModel.kt   # 本地相册 ViewModel
│   ├── FirstRunGuideScreen.kt    # 冷启动引导（设置页可重开）
│   └── UsbSyncPreferences.kt     # 用户偏好设置
├── settings/
│   └── SettingsScreen.kt         # 设置页面
├── ui/theme/                     # Material 3 主题
├── logging/                      # Khronicle 日志 + 查看器
├── di/                           # Metro DI
├── NavRoute.kt                   # 导航路由
└── MainActivity.kt               # 单 Activity 入口
```

---

## 已知问题

| 严重度 | 问题 | 状态 |
|---|---|---|
| P0 | 双 MTP 管线：前台 UI 与后台自动同步各持一个 `MtpDevice` 并发操作 | ✅ 阶段1 护栏已修复（`9344686`，action-plan P0-3）；阶段2 共享实例降级 P2-1 |
| P0 | 去重键不一致：UI 硬编码 `storageId=0` vs 后台真实 storageId | ✅ 已修复（`b75e87b`，action-plan P0-2） |
| P0 | "会话级自动剪枝"假注释 | ✅ 已修复（`220aa12`，action-plan P0-4，改软校验） |
| P0 | MediaStore 保存路径硬编码 "Nikon Z30" | ✅ 已修复（`2961280`，action-plan P0-1） |
| P1 | 自动同步未接线：`UsbSyncService` 零调用，`autoSyncEnabled` 无消费者 | ✅ 已移除死代码（`6a1c331`，action-plan P1-4） |
| P2 | detekt baseline 技术债（原 24 条，P0 已清 1 条 → 23 条） | 已由 baseline 吸收，逐步偿还（action-plan P2-3） |

> 应用功能层面历史 bug 均已修复（最后修复 2026-08-02 PhotoCell EXIF 竖构图）。2026-08-09 Apple 视角评审（[review](../review/2026-08-09-design-review.md)）发现的 4 项正确性缺陷 R1–R4 已全部修复（action-plan P0）；R7（自动同步未接线）已按 YAGNI 移除死代码（action-plan P1-4）；R5 三个核心路径项（P1-1/2/3）已全部落地（action-plan P1）。

---

## 最近提交 (2026-08-09)

```
e873861 style: apply ktfmtFormat
9a54205 docs: record pending items in action plan and dev log
cd756a8 chore: enable detekt gate with baseline and add pre-push hook
0251bf3 docs: realign agent guidance and docs system to Float
bcb7bee perf(usb): eliminate blocking thumbnail prefetch, add concurrent preloading
a385378 refactor: remove BLE GPS sync and companion device subsystems
```
