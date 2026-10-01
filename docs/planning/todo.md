# CameraSync — 当前状态 & 未来规划

> 最后更新: 2026-10-01 | 分支: `master`

---

## 当前状态: 🔴 发布前（P7 全部落地，待真机回归）

**撤回"生产就绪 / 0 个已知问题"表述（2026-08-15 第二期评审 R19）；第三期评审（2026-10-01）发现丢片级缺陷后降级为「发布前」——P7 已全部修复（2026-10-01），发布阻断解除；**剩余仅真机回归（Nikon Z30，含双卡 / 大库）与 P3/P6 的设备/网络门控项**。事实状态：

- ✅ 功能层面：P0 正确性止血（去重/路径/管线/剪枝）、P1 核心路径（引导/新照片主路径/传输回看）、P2-1 God Object 拆分、P2-2 核心单测、P2-3 detekt 归零全部落地
- ✅ `testDebugUnitTest` **54 tests 全绿**（P2-2 的 43 条 + 本期 11 条：P7-A 5 + 日期键 3 + 去重表 2 + 取消语义 1，2026-10-01）
- ✅ 构建/静态：`assembleDebug` 通过；detekt **0**；lint **0 errors / 74 warnings**（R35 清理 96→78，R40/R36 后 74）
- ✅ detekt baseline **0 条**（P2-3，2026-08-15）
- ✅ R8 旋转双开 MTP、R12 幽灵权限、R11 主题闭环、R13 电量删除（P4-1/2/3/4，2026-08-15）
- ✅ **P5 工程债深水全部落地**（R10 字符串资源化 / R14 核心路径接 DI / R15 核心屏 Preview / R17 缓存降内存，2026-08-15）
- ✅ **第三期评审 P7 全部闭环**（2026-10-01）：P7-A 正确性（R20–R23，含两项丢片级缺陷）、P7-B 大库性能（R24–R30）、P7-C 打磨（R31–R35 + R36/R40）全部落地（见 [第三期评审](../review/2026-10-01-design-review-3.md) 与 [action-plan](action-plan.md) **P7**）
- ⏳ **待真机回归**：Nikon Z30（含双卡 / 大库场景）确认同步正确性与大库流畅度；R24/R28/R29 的性能改善需真机体感佐证
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
> 2026-10-01 调整：**已完成阶段 P0–P5 归档**至 [`../archive/ACTION_PLAN_P0-P5.md`](../archive/ACTION_PLAN_P0-P5.md)（历史记录，不主动读取）；**P7 大照片库正确性与性能已全部闭环（R20–R35 + R36/R40）**。
> 剩余待办：**P3-1/P3-3 / P6-3**——均为真机（Nikon Z30，含双卡 / 大库）与环境门控；取验证包走 [Manual APK 工作流](../engineering/release-checklist.md#获取验证包手动工作流推荐)。
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

> 2026-10-01 更新：P0–P5 全部闭环并归档（[归档原文](../archive/ACTION_PLAN_P0-P5.md)）。**第三期评审（大库专项）R20–R41 已全部处置**——其中 R21/R23 两项丢片级缺陷已修复（`4585770` `366d36f`），发布阻断解除（action-plan **P7**）。剩余 P3-1/P3-3 / P6-3 依赖真机与环境。

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
| **P0** | **RAW+JPEG 选「全部」只传 RAW，JPEG 静默丢弃（R21）** | ✅ 已修复（`4585770`，P7-1，含回归单测） |
| **P0** | **同名照片跨存储/文件夹被合并丢一张（R23）** | ✅ 已修复（`366d36f`，P7-2，分组键 + 稳定 key + 单测） |
| P1 | 旋转/`stop()` 后协作对象持死 scope，USB 连接静默失效（R20） | ✅ 已修复（`9cbf63d`，P7-4，scope 访问器 + 单测） |
| P1 | 传输预览组数被 `take(6)` 截断、"+N more" 恒 0（R22） | ✅ 已修复（`e1dc004`，P7-3） |
| P1 | 勾选触发整屏全量重算 / BY_DATE O(分区×照片) / 选择集线性（R24/R25/R26） | ✅ 已修复（`b529f2e` `ea852b5` `e7f3395`，P7-5/6/7） |
| P2 | 枚举两遍 / 缩略图堆积 / 扫描无反馈 / 去重表无上限（R27–R30） | ✅ 已修复（`f0847e3` `c1796dc` `cbff80b` `a616506`，P7-8/9/10/11） |
| P3 | 刷新指示失败 / 取消语义 / 详情下全图 / 死代码 / BLE 残留资源（R31–R35） | ✅ 已修复（`90a716a` `2dde500` `878a705` `90de7ee` `82e75f7`，P7-12） |
| P3 | lint 提示：AutoboxingState / Recycle 误报（R40/R36） | ✅ 已处理（`a8c48f1`：`mutableIntStateOf` + `lint.xml` 文档化抑制） |

> 应用功能层面历史 bug 截至 2026-08-02 均已修复；P0–P5（见[归档](../archive/ACTION_PLAN_P0-P5.md)）与 **P7（R20–R35 + R36/R40）**全部闭环。**发布前仍需真机回归（Nikon Z30，含双卡 / 大库场景）**——「生产就绪」保持撤回直至回归通过。

---

## 最近提交 (2026-10-01)

> `master` 与 `origin/master` **同步（已推送，领先 0）**：P2/P4/P5/P6 + 第三期评审文档 + P7 全部修复均已推送（P3-2 ✅）。

```
a8c48f1 chore: use mutableIntStateOf and suppress Recycle false positive (R40/R36)
82e75f7 chore(res): remove BLE leftovers and simplify SDK check (R35)
90de7ee fix(usb): drop dead filter-cache state and close USB on failed open (R34)
878a705 fix(usb): show detail EXIF from the thumbnail, download full on demand (R33)
2dde500 fix(usb): propagate cancellation and clean up temp files (R32)
90a716a fix(ui): bind pull-to-refresh to a real refreshing state (R31)
a616506 perf(usb): cap the dedup table and evict oldest records (R30)
cbff80b perf(usb): stream scan progress into the grid (R29)
c1796dc perf(usb): preload the visible thumbnail window and recycle rotated bitmaps (R28)
f0847e3 perf(usb): drop the pre-count BFS from photo enumeration (R27)
ea852b5 perf(ui): pre-bucket BY_DATE photos and share the date formatter (R25)
b529f2e perf(ui): stop selection toggles from recomputing the whole grid (R24)
e7f3395 perf(usb): back selection with a keyed state map (R26)
```

> P7 已全部落地；下一步为**真机回归**（Nikon Z30，含双卡 / 大库），以及 P3/P6 的设备/网络门控项。
