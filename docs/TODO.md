# CameraSync — 当前状态 & 未来规划

> 最后更新: 2026-08-02 | 分支: `master`

---

## 当前状态: ✅ 生产就绪 (v2.3)

所有计划的 Sprint (1–4) 已完成，0 个已知问题。

### 已完成功能总览

#### USB 照片同步 (核心)
- [x] USB MTP 连接、照片枚举、下载
- [x] 画廊浏览 (3 列网格、文件夹导航)
- [x] RAW+JPEG 分组 (NEF/JPG 对 + "RAW" 徽章)
- [x] 长按多选 + 批量传输
- [x] 后台自动同步 (前台 Service)
- [x] 去重 (SharedPreferences)
- [x] 传输速度 & ETA 显示
- [x] 传输完成操作面板 (查看/分享/删除)
- [x] 触觉反馈
- [x] 存储空间状态栏
- [x] 筛选芯片 (全部/新照片/RAW/JPEG)
- [x] 富通知 (BigPictureStyle)
- [x] EXIF 详情面板 (快门/光圈/ISO/焦距/镜头等)
- [x] 从相机删除照片
- [x] 网格密度切换 (2/3/4 列)
- [x] 传输历史记录
- [x] 失败重试
- [x] 设置页面 (自动同步、分组、排序、下载格式、主题)
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
| 测试设备 | Pixel 9 + Android 15 + Nikon Z30 |

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
│   ├── UsbSyncService.kt         # 前台服务 (后台同步)
│   ├── UsbSyncCoordinator.kt     # 自动同步生命周期
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

**0 个已知问题。** 最后修复于 2026-08-02 (PhotoCell EXIF 竖构图).

---

## 最近提交 (2026-08-02)

```
4042515 fix: add kotlinx-atomicfu dependency for Khronicle logger
a385378 refactor: remove BLE GPS sync and companion device subsystems
fcd4f8c refactor: migrate local photos to Coil + fix USB MTP enumeration crashes
52a550d fix: local photos display — scoped storage, EXIF orientation, preview, refresh
90cc77b fix: query MediaStore.Files for NEF — Images table excludes RAW
```
