# CameraSync — 当前状态 & 未来规划

> 最后更新: 2026-10-01 | 分支: `master`

---

## 当前状态: 🔴 发布前（P7 大库修复中）

**撤回"生产就绪 / 0 个已知问题"表述（2026-08-15 第二期评审 R19）；第三期评审（2026-10-01）发现丢片级缺陷后，状态降级为「发布前（P7 修复中）」。** 事实状态：

- ✅ 功能层面：P0 正确性止血（去重/路径/管线/剪枝）、P1 核心路径（引导/新照片主路径/传输回看）、P2-1 God Object 拆分、P2-2 核心单测、P2-3 detekt 归零全部落地
- ✅ `testDebugUnitTest` **43 tests 全绿**（P2-2，2026-08-15；2026-10-01 clean 全量复核仍全绿）
- ✅ detekt baseline **0 条**（P2-3，2026-08-15）
- ✅ R8 旋转双开 MTP、R12 幽灵权限、R11 主题闭环、R13 电量删除（P4-1/2/3/4，2026-08-15）
- ✅ **P5 工程债深水全部落地**（R10 字符串资源化 / R14 核心路径接 DI / R15 核心屏 Preview / R17 缓存降内存，2026-08-15）
- ✅ 构建层面：`assembleDebug` / `bundleRelease`（R8）本地 clean 全量通过（2026-10-01）；lint **0 errors / 96 warnings**
- 🔴 **第三期评审新增待修**：[第三期评审](../review/2026-10-01-design-review-3.md)（R20–R41）——**R21 RAW+JPEG 丢片 / R23 同名跨存储合并丢片**（发布阻断），R20 旋转后连接静默失效，R22 预览统计失真，R24–R30 大库卡顿。行动项见 [action-plan](action-plan.md) **P7**
- 完整已知问题清单见下方「已知问题」表 + 三期评审文档

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
- [x] 设置页面 (分组、排序、下载格式、网格密度、主题、使用说明)
- [x] 深色主题渲染 + 切换入口 (跟随系统/浅色/深色，设置页三选一)
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

#### 已移除子系统（历史，勿列为"已完成功能"）
- ~~BLE GPS 同步（Ricoh GR / Sony Alpha 系列）~~ —— **已于 2026-08-02 移除**（commit `a385378`）；协议文档归档 `docs/ricoh/`、`docs/sony/`，仅供历史查阅。USB/MTP 是唯一功能路径。

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

> **活跃行动计划** → [`action-plan.md`](action-plan.md)。
> 2026-10-01 调整：**已完成阶段 P0–P5 归档**至 [`../archive/ACTION_PLAN_P0-P5.md`](../archive/ACTION_PLAN_P0-P5.md)（历史记录，不主动读取）。
> 待办：**P3** 运营收尾（设备/网络门控）→ **P6** 发布闭环（设备门控）→ **P7 大照片库正确性与性能**（第三期评审新增，**R21/R23 丢片级，发布阻断**）。
> 新任务一律在 action-plan.md 追踪（docs-first，完成一项勾一项）。

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
│   ├── NikonUsbManager.kt        # MTP 设备操作（枚举/读取/删除）
│   ├── GalleryViewModel.kt       # 门面（P2-1 拆分后保留公共 API）
│   ├── GalleryStateMachine.kt    # 状态机 + 筛选/排序/分组/选择纯逻辑
│   ├── ConnectionManager.kt      # USB 生命周期 + 浏览/枚举
│   ├── ThumbnailProvider.kt      # 四类缓存 + EXIF 方向
│   ├── TransferEngine.kt         # 传输编排 + MediaStore 保存
│   ├── GalleryScreen.kt          # 主 UI (网格/文件夹/选择/进度；2248 行，待拆)
│   ├── PhotoSyncManager.kt       # 导入去重
│   ├── PhotoDetailSheet.kt       # EXIF 详情面板
│   ├── TransferHistoryScreen.kt  # 传输历史
│   ├── TransferRecord.kt         # 传输历史条目
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

> 2026-10-01 更新：P0–P5 全部闭环并归档（[归档原文](../archive/ACTION_PLAN_P0-P5.md)）。**第三期评审（大库专项）新增 R20–R41**，行动项见 action-plan **P7**；其中 **R21/R23 为丢片级、发布阻断**。P3/P6 剩余项依赖真机与网络环境。

| 严重度 | 问题 | 状态 |
|---|---|---|
| P0 | 双 MTP 管线：前台 UI 与后台自动同步各持一个 `MtpDevice` 并发操作 | ✅ 已修复（`9344686` 护栏 + `6a1c331` 移除后台管线） |
| P0 | 去重键不一致：UI 硬编码 `storageId=0` vs 后台真实 storageId | ✅ 已修复（`b75e87b`，action-plan P0-2） |
| P0 | "会话级自动剪枝"假注释 | ✅ 已修复（`220aa12`，action-plan P0-4，改软校验） |
| P0 | MediaStore 保存路径硬编码 "Nikon Z30" | ✅ 已修复（`2961280`，action-plan P0-1） |
| P1 | 自动同步未接线：`UsbSyncService` 零调用，`autoSyncEnabled` 无消费者 | ✅ 已移除死代码（`6a1c331`，action-plan P1-4） |
| P2 | detekt baseline 技术债（17 条） | ✅ 已还清（`9f373eb`，action-plan P2-3，baseline 归零） |
| P2 | `testDebugUnitTest` 6 个失败（LocalPhotosViewModelTest） | ✅ 已修复 + 补 25 条核心单测（`d263935` `b015182` `cf88244` `134674b`，43 全绿） |
| P0 | 旋转重建 Activity 后双 `MtpDevice` open 同一连接（R8） | ✅ 已修复（`18c3b9b`，action-plan P4-1，DisposableEffect 配对） |
| P0 | `MANAGE_EXTERNAL_STORAGE` 幽灵权限（R12） | ✅ 已删（`f0f12f3`，action-plan P4-2） |
| P1 | 主题"宣称已实现"实际不可用（R11） | ✅ 已接线（`bb1dc29`，action-plan P4-3，设置页三选一） |
| P1 | 电量"宣称已实现"实际恒 null（R13） | ✅ 已删除（`9f373eb`，action-plan P4-3，YAGNI） |
| P2 | 硬编码字符串、核心屏零 Preview、核心路径未接 DI（R10/R15/R14） | ✅ 已修复（P5-1/2/3，2026-08-15：资源化 + Preview 全覆盖 + AppGraph 注入） |
| P2 | fullPhotoCache 300MB OOM 风险（R17） | ✅ 已修复（P5-4，2026-08-15：磁盘 LRU 3 + 路径 EXIF） |
| **P0** | **RAW+JPEG 选「全部」只传 RAW，JPEG 静默丢弃（R21）** | 🔴 待修（action-plan **P7-1**，需先补单测） |
| **P0** | **同名照片跨存储/文件夹被合并丢一张（R23）** | 🔴 待修（action-plan **P7-2**，需先补单测） |
| P1 | 旋转/`stop()` 后协作对象持死 scope，USB 连接静默失效（R20） | 🔴 待修（action-plan **P7-4**） |
| P1 | 传输预览组数被 `take(6)` 截断、"+N more" 恒 0（R22） | 🔴 待修（action-plan **P7-3**） |
| P1 | 勾选触发整屏全量重算 / BY_DATE O(分区×照片) / 选择集线性（R24/R25/R26） | 🟠 待修（action-plan **P7-5/6/7**） |
| P2 | 枚举两遍 / 缩略图堆积 / 扫描无反馈 / 去重表无上限（R27–R30） | 🟠 待修（action-plan **P7-8/9/10/11**） |
| P3 | 刷新指示失败 / 取消语义 / 详情下全图 / 死代码 / BLE 残留资源（R31–R35） | 🟡 待修（action-plan **P7-12**） |

> 应用功能层面历史 bug 截至 2026-08-02 均已修复；P0/P1/P4/P5 全部闭环（见[归档](../archive/ACTION_PLAN_P0-P5.md)）。但 **2026-10-01 第三期评审（[review](../review/2026-10-01-design-review-3.md)）发现两项丢片级缺陷（R21/R23）尚未修复**——「无已知问题」不成立，「生产就绪」撤回。P7 完成后需真机回归（Nikon Z30，含双卡/大库场景）方可再评估发布。

---

## 最近提交 (2026-10-01)

> `master` 领先 `origin/master` **38 个 commit**（P2/P4/P5/P6 全部落地未推送；P3-2 待网络环境）。

```
a1e0177 docs: sync P6 store-listing status across changelog and planning
67f476c docs(legal): add Play store listing material
c2a17c1 docs: sync status after P5 implementation
7815abf docs: align privacy policy and add release metrics plan
173d5c0 chore: remove unused storage and vibration permissions
6785430 refactor(di): inject gallery view models from AppGraph
8ee67eb feat(ui): add gallery state and settings screen previews
35ca39b refactor(usb): decouple gallery screens from view model for previews
ad54502 fix(usb): cap full-photo cache and use path-based EXIF
```

> 下一批：`docs: add third design review and P7 plan`（本期）+ P7-A 修复（R21/R23 先行）。
