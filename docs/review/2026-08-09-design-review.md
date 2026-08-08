# 2026-08-09 — Apple 视角全项目设计评审

> **评审框架**: Apple 产品与研发哲学（减法聚焦 / 单一事实源 / 核心路径可靠性）
> **评审对象**: CameraSync v2.3 全代码库 + 活动文档
> **状态**: ✅ 已记录 —— 行动项见 [`../planning/action-plan.md`](../planning/action-plan.md)

---

## 一、评审摘要

工程纪律顶级：49 commits（2026-05 至今）全 Conventional Commits、pre-push 全量 gate、postmortem 沉淀、docs-first、对标 Float 的文档体系。放到 Apple R&D 眼里，这是一支**自我修养极好的工程师团队**。

但 Apple 的判读总是先问「用户得到了什么，再看代码」。这个视角下：

> **"生产就绪 v2.3 / 0 已知问题"这个标签，掩盖了至少 3 个会咬人的正确性缺陷，以及一个被放弃但从未关闭的已验证痛点。**

**评分：工程 A- / 产品 B-。** 不缺功能，缺的是 Apple 那句名言——*"It's not a feature. It's the product."* 产品就是那条 USB 线，精力应全部压在"插上就传、绝不错传、绝不丢片"。

---

## 二、评审方法

- 通读 `docs/` 全部活动文档 + `docs/archive/PRD.md`
- 通读核心源码：`GalleryViewModel` / `GalleryScreen` / `NikonUsbManager` / `PhotoSyncManager` / `UsbSyncCoordinator` / `UsbSyncService`
- 对照 git 历史（`023945b` → `97f6500`）
- 用三个 Apple 判据打分：**核心使命聚焦度 / 单一事实源 / 关键路径可靠性**

---

## 三、发现

按严重度排列。每个发现：证据（file:line，可复核）→ 影响 → 性质。

### 🔴 R1 — 双 MTP 管线：同一 USB 连接被两个所有者操作

**证据**
- `GalleryViewModel.kt:142` — `private val nikon = NikonUsbManager(usbManager)`（前台 UI 管线）
- `UsbSyncService.kt:60` — `private val nikonUsbManager by lazy { NikonUsbManager(usbManager) }`（后台自动同步管线）
- 两条路径各自 `openMtpDevice()` 挂在同一条物理 USB 上

**影响**：`android.mtp.MtpDevice` **非线程安全**。前台滚动缩略图（`getThumbnail`）与后台 `syncOnce()`（`importFile`）并发 → MTP 调用相互踩踏，轻则返回 null、重则传输中断。文档里画了两套架构（`USB_SYNC.md` §7），代码里也真有两套实现。

**性质**：架构级单一事实源缺失——postmortem 001 病根的架构版（文档脱节升级为代码双实现）。

### 🔴 R2 — 去重键不一致：核心承诺被违背

**证据**
- `GalleryViewModel.kt:781, 851, 904, 921, 957` — **6 处** `isAlreadyImported / markAsImported` 硬编码 `storageId=0`
- `UsbSyncCoordinator.kt:99, 119` — 使用真实 `storage.id`

**影响**：前台 UI 传输记录为 `(0, handle)`，后台同步查询 `(realStorageId, handle)`。**键不匹配 → 同一张照片被两条管线各下载一次，或互相漏判。** Z30 恰好 storageId=0 未爆；换机内 + SD 双存储的机型必然复现。"去重"（"Photos already imported are automatically skipped"）是 PRD 明确卖点。

**性质**：正确性缺陷 + 双管线（R1）的下游症状。

### 🟠 R3 — "会话级自动剪枝"是注释撒谎

**证据**
- `PhotoSyncManager.kt:10` 注释声称 "if the camera's session changes, old handles become invalid and will be pruned automatically"
- 但 `clearAll()` / `clearStorage()` 全仓库（main + test）**零调用**——只定义了没人用

**影响**：MTP 句柄是会话级的。相机重连、格式化、翻页后句柄号可能复用 → 旧键把**新照片静默误判为"已导入"而跳过**。这是静默丢片，比重复下载更可怕。

**性质**：注释/文档宣称了代码未实现的行为——与 postmortem 001 同根（刚修完文档与代码脱节，又写了一段假注释）。

### 🟠 R4 — 硬编码 "Nikon Z30"，违反自家强制规范

**证据**
- `GalleryViewModel.kt:1034` — `val path = "Pictures/CameraSync/Nikon Z30"`
- `UsbSyncCoordinator.kt:156` — 同样的写死路径
- `res/values/strings.xml:39,169` — 写死设备名
- 同文件 `GalleryViewModel.kt:966` 却已用 `cameraInfo?.model` 记传输历史

**影响**：换一个 Nikon 机型（哪怕 Z fc），照片全进 "Nikon Z30" 文件夹。**真实 defect**，直接违反 CLAUDE.md「禁止硬编码字符串」。

**性质**：正确性缺陷 + 规范违反。

### 🟠 R5 — 功能膨胀稀释核心使命 + 冷启动痛点未闭环

**证据**
- 3 种分组 × 5 种排序 × 2/3/4 列网格 × 4 个筛选 chip × 传输历史 × 日志查看器……
- PRD P4（冷启动无引导、MTP 模式要求无人告知）**从未落地**：
  - `MainActivity.kt:136` — `onNavigateToOnboarding = {}` 是**空回调 stub**
  - detekt baseline 甚至把 `UnusedParameter:SettingsScreen.kt$onNavigateToOnboarding` 记了债
  - `todo.md` 却宣称 "0 个已知问题"

**影响**：用户要在 40+ 个开关里翻到"新照片"；一个**已验证的中等痛点被静默放弃，还标记为"无问题"**。"生产就绪"是工程师定义的，不是用户验证的。

**性质**：产品聚焦缺陷 + 状态报告失真。

### 🟡 R6 — 测试赤字 + God Object

**证据**
- 主代码 ~6100 行，仅 2 个单测文件（`LogcatLogParserTest` / `LocalPhotosViewModelTest`）
- `GalleryViewModel.kt` **1099 行**、`GalleryScreen.kt` **1913 行**——连接/枚举/选择/过滤/排序/传输/MediaStore/EXIF/四个缓存全挤在一类
- CLAUDE.md 写 "Fakes over Mocks" / "Dispatcher 注入"，测试却没跟上

**影响**：核心状态机、去重、双管线全部裸奔。Apple R&D 信条：**没测试 = 没承诺**。detekt 的 `TooManyFunctions` 靠 baseline 压着，正是债务累积的方式。

**性质**：工程债。

---

## 四、与既有文档的矛盾清单

| # | 文档声明 | 代码实际 | 出处 |
|---|---|---|---|
| 1 | "0 个已知问题" | R1–R4 存在且未记录 | `docs/planning/todo.md` |
| 2 | "会话级自动剪枝" | `clearAll`/`clearStorage` 零调用 | `PhotoSyncManager.kt:10` |
| 3 | 测试设备三处说法矛盾 | `USB_SYNC.md` §9 = Xiaomi MIUI；README = Nikon Z30 | P1（未闭环） |

> 结论：文档体系很健全，但**自评状态的诚实度**欠账——这正是 Apple 最看重的一点。

---

## 五、结论与优先级

| 优先级 | 主题 | 对应发现 |
|---|---|---|
| P0 | 正确性止血：单管线、去重一致、路径真实、剪枝真实 | R1–R4 |
| P1 | 核心路径：冷启动引导、新照片默认主路径、传输回看 | R5 |
| P2 | 工程债：拆 God Object、补核心单测、还清 detekt baseline | R6 |
| P3 | 运营收尾：确认测试设备、推送、验证 CI | — |

**明确不做**：云备份 / 视频 / 多相机 USB。多相机 USB 是 Android 平台硬限制；cloud/video 是另一个产品的命题，对 solo 项目是陷阱。

> 完整行动步骤见 [`../planning/action-plan.md`](../planning/action-plan.md)。
