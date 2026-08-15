# 架构决策记录

> 记录关键架构决策及其理由。格式: 日期 / 决策 / 背景 / 后果。遵循 docs-first：新决策先在此登记再动代码。

## ADR-001: 基于 rock3r/CameraSync 重写，聚焦 Nikon USB 有线同步

- **日期**: 2026-05（重写） / 2026-08-02（定稿）
- **决策**: 在 [rock3r/CameraSync](https://github.com/rock3r/CameraSync)（BLE GPS 同步）基础上重写，产品方向改为 **Nikon 相机 USB/MTP 有线照片同步**
- **背景**: 原项目主打 Ricoh/Sony BLE GPS 同步；重写后 USB 有线传输成为唯一功能路径
- **后果**: BLE 子系统与协议文档（Ricoh/Sony）归档保留供历史查阅（commit `a385378`）

## ADR-002: 单模块 `:app`

- **日期**: 2026-05
- **决策**: 保持单模块 `:app`，不引入 `:core:*` / `:feature:*` 多模块结构
- **理由**: 唯一功能路径 + solo 维护；多模块拆分无收益（对照 Float 多模块结构后维持此决策）
- **后果**: `GalleryViewModel` 曾膨胀至 1105 行，通过包内拆类（StateMachine / ThumbnailProvider / TransferEngine / ConnectionManager）收敛，而非模块化

## ADR-003: 使用标准 `android.mtp.MtpDevice` API，不逆向协议

- **日期**: 2026-05
- **决策**: 依赖 Android 内置 MTP API（`UsbManager` + `MtpDevice`），不逆向 Nikon 私有协议
- **理由**: 相机以标准 MTP/PTP 呈现存储；`UsbSyncService` 等封装足够
- **后果**: 支持范围 = 所有 MTP 兼容 Nikon 机型（已验证 Z30）；新增机型一般零代码改动

## ADR-004: Metro 编译期依赖注入

- **日期**: 2026-05
- **决策**: 使用 Metro（`@DependencyGraph AppGraph` + `@Provides`），替代运行时 DI 框架
- **理由**: 编译期校验、无反射开销、Kotlin 原生
- **后果**: 新增依赖在 `AppGraph` 声明 `@Provides` 或构造注入；`MainActivity` 用 `@Inject`

## ADR-005: MVVM + UDF + `mutableStateOf<SealedInterface>`

- **日期**: 2026-05
- **决策**: ViewModel 暴露 `mutableStateOf<GalleryState>`（sealed interface），Composable 通过 `.value` + `when` 渲染；UI 事件经 `onEvent`/回调上抛
- **理由**: 与 Compose 组合模型匹配；状态机清晰可测
- **后果**: 业务状态全部在 ViewModel，`remember` 只用于临时 UI 状态；服务级状态用 `MutableStateFlow`，响应式列表用 `SnapshotStateList`

## ADR-006: Coil 3 + MediaStore 加载本地照片

- **日期**: 2026-06（REFACTOR_LOCAL_PHOTOS）
- **决策**: 本地照片用 Coil 3（`SingletonImageLoader.Factory`）+ MediaStore 查询（Android 13+ 分区存储）
- **理由**: 替代裸 `BitmapFactory`，内存与解码由 Coil 托管；分区存储下 MediaStore 是唯一合法读取路径
- **后果**: 照片保存经 MediaStore `IS_PENDING` 事务写入 `Pictures/CameraSync/{模型}/`

## ADR-007: Khronicle 日志，禁止 `android.util.Log`

- **日期**: 2026-05
- **决策**: 统一 `com.juul.khronicle.Log`，文件级 `private const val TAG`
- **理由**: 日志仓库（`LogcatLogRepository`）可注入、可测，支持日志查看器
- **后果**: 硬性规范入 `CLAUDE.md`；`android.util.Log` 被 detekt 拦截

## ADR-008: 去重用 `name:size` 身份软校验，替代「会话自动剪枝」

- **日期**: 2026-08-09（P0-4，commit `220aa12`）
- **决策**: `PhotoSyncManager` 去重键 `s{storageId}_h{handle}` 存 `name:size` 身份；handle 复用给新照片时身份不匹配 → 视为未导入
- **背景**: 旧注释声称「自动剪枝」但 `clearAll()`/`clearStorage()` 零调用（假注释）
- **理由**: 比「重连清空」保留跨会话去重，比「全量枚举剪枝」不破坏文件夹渐进浏览
- **后果**: 不再静默丢片；旧 boolean 键升级后失效触发一次全量重传（dev 阶段可接受）

## ADR-009: 移除未接线的自动同步（YAGNI）

- **日期**: 2026-08-09（P1-4，commit `6a1c331`）
- **决策**: 删除 `UsbSyncService` / `UsbSyncCoordinator` / `autoSyncEnabled` 开关 / 同步通知 channel / Manifest 前台服务与权限
- **背景**: 评审发现 `createStartIntent`/`ACTION_SYNC` 零调用，开关无消费者（假功能）
- **后果**: 设置页不再出现无效开关；单 MTP 管线由构造保证（P0-3 护栏随之移除）

## ADR-010: 质量门禁 ktfmt + detekt（含 compose-rules）+ pre-push

- **日期**: 2026-08-09
- **决策**: ktfmt（kotlinlang 风格）+ detekt `maxIssues: 0`（含 compose-rules 18 条）+ `.githooks/pre-push`（ktfmtCheck + detekt + lintDebug + test + assembleDebug）
- **背景**: 对标 Float 质量流程；detekt 曾迟检测导致「写后重写」循环
- **后果**: 强制规范入 `CLAUDE.md`：先写 commit message、每 commit 本地跑 gate；存量违规用 `detekt-baseline.xml` 吸收并逐步偿还（17 → 0）

## 技术栈总览

| 层 | 技术 |
|---|---|
| UI | Jetpack Compose + Material 3 + Navigation 3（type-safe `@Serializable` 路由） |
| DI | Metro（编译期） |
| 图片 | Coil 3（本地照片 + MediaStore） |
| 持久化 | SharedPreferences（USB 去重 + 偏好设置） |
| 日志 | Khronicle |
| USB | `android.mtp.MtpDevice`（标准 MTP） |
| 质量 | ktfmt + detekt（compose-rules）+ Android lint |
| 测试 | JUnit + kotlinx-coroutines-test + MockK（Fakes over Mocks） |
