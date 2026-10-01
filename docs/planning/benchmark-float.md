# Float 对标差距分析（Benchmark Float）

> **依据**: `I:\dev\Float\`（CLAUDE.md + docs/）完整对照阅读
> **最后更新**: 2026-10-01 | **原则**: 完全对标 Float 的流程与规范；保留 CameraSync 已定架构决策（单模块 / Metro / Khronicle）
> **约定**: `✅` 已对齐 · `🟡` 部分对齐 · `❌` 缺失/悬空
>
> **⚠️ 本文是 2026-08-15 的差距快照**：下表多数缺口**已闭环**——A1 CHANGELOG、B1 requirements/、B2 ADR、B3 release-checklist、B4 adb-commands、B5 privacy-policy、C1/C2 GitHub 模板、D1 版本单源 + `release.sh`、F1/F4 CLAUDE.md 章节、G1/G3/G4 均已落地（见 `CHANGELOG.md` Unreleased）。唯一未做的是 P4 可选项（E1 skills、D2 jacoco 覆盖率）。下表的 `❌/🟡` 为**当时状态**，不代表现状。

---

## 一、总体结论

CameraSync 的 docs 体系骨架（`README 索引 / engineering / postmortem / development-log / archive / review`）与 CLAUDE.md 强制规范已于 2026-08-09 完成首轮对标（见 `development-log/2026-08-09.md`）。**流程机制已对齐，资产与内容仍有缺口**：Float 多出 requirements 体系、ADR、GitHub 模板、CHANGELOG、legal、release 脚本、skills 等 7 类资产；另有 5 处现有文档漂移/悬空引用需顺带修复。

## 二、差距清单

### A. 根目录文档

| # | 项 | Float | CameraSync | 差距 | 建议动作 |
|---|---|---|---|---|---|
| A1 | `CHANGELOG.md` | ✅ Keep a Changelog + SemVer，PR 必更新 Unreleased | ❌ 不存在，但 `docs/README.md` 约定已引用它（悬空） | 缺失 + 悬空引用 | 新建 `CHANGELOG.md`（Unreleased 段 + 回溯 v1.0.0/v2.3 条目） |
| A2 | `README.md` | ✅ 含 Getting Started（pre-push 引导） | 🟡 仍宣称「Background Sync: foreground service」——P1-4 已删 `UsbSyncService` | 漂移（postmortem 001 同族） | 删除/改写该 Feature 条目 |
| A3 | `CODE_OF_CONDUCT.md` / `LICENSE` | ✅ | ✅（Apache 2.0） | 无 | — |

### B. docs 体系

| # | 项 | Float | CameraSync | 差距 | 建议动作 |
|---|---|---|---|---|---|
| B1 | `docs/requirements/`（PRD / feature-spec / glossary / user-stories） | ✅ 活跃 | ❌ PRD 已归档（`archive/PRD.md`），无活跃 requirements/ | 缺失 | 复活并更新 PRD；新建 glossary（MTP/USB/去重/NEF 术语）、user-stories（按用户场景 + 验收标准）、feature-spec（USB 同步状态机 / 本地相册 / 设置） |
| B2 | `docs/planning/architecture.md`（ADR） | ✅ ADR-001..007（含 NOT ADOPTED 记录） | ❌ 无 ADR，关键决策散落在日志/评审里 | 缺失 | 新建 ADR：MTP 方案（不逆向）、单模块决策、Metro DI、去重软校验（`name:size`）、BLE 移除、NEF 方向处理等 |
| B3 | `docs/engineering/release-checklist.md` | ✅ 真机发布验证清单（版本纪律 / R8 冒烟 / 功能逐项） | ❌ 无 | 缺失 | 新建（真机回归：MTP 同步 / 去重跨会话 / 断线重连 / 权限 / 16KB 对齐等） |
| B4 | `docs/engineering/adb-commands.md` | ✅ 设备状态表 + 多设备 + 日志 | ❌ 无（USB/MTP 调试恰好最依赖 adb） | 缺失 | 新建（照 Float 结构 + MTP/`pm`/logcat 过滤 CameraSync tag） |
| B5 | `docs/legal/privacy-policy.md` | ✅ 本地优先 + 权限说明 + 脱敏 | ❌ 无 | 缺失 | 新建（照片/EXIF 仅本地、USB 权限、无网络上报、卸载即删） |
| B6 | `docs/review/` | ❌（Float 无独立 review/，处置放 planning） | ✅ 已有 | CameraSync 领先 | 保留 |
| B7 | `docs/nikon/` 技术参考 | ❌（领域不同） | ✅ 已有 | CameraSync 领先 | 保留 |

### C. GitHub 工程化

| # | 项 | Float | CameraSync | 差距 | 建议动作 |
|---|---|---|---|---|---|
| C1 | `.github/ISSUE_TEMPLATE/`（bug_report / feature_request / tech-debt） | ✅ 三个模板（front-matter 带 type/label） | ❌ 无 | 缺失 | 新建三个模板（title 前缀 `fix:` / `feat:` / `refactor:`，label 对齐 commit 纪律） |
| C2 | `.github/PULL_REQUEST_TEMPLATE.md` | ✅ Summary/Type/Related Issue/Checklist（含 style guide + Preview + 无硬编码勾选） | ❌ 无 | 缺失 | 新建（Checklist 对齐审查清单：`@Preview`、本地 detekt+ktfmtCheck） |
| C3 | `.github/workflows/` | ✅ CI（detekt/lint/test/assemble）+ release | 🟡 有 ci.yml + release.yml，但 `update_firmware_data.yml` 是 BLE 时代残留死 workflow | 死配置 | 删除 `update_firmware_data.yml`（BLE 已移除，postmortem 001「删代码必删配置」） |

### D. 构建 / 发布工程化

| # | 项 | Float | CameraSync | 差距 | 建议动作 |
|---|---|---|---|---|---|
| D1 | 版本单源 + release 脚本 | ✅ `gradle.properties` 的 `VERSION_NAME/VERSION_CODE` + `scripts/release.sh`（门禁→commit `release: vX`→tag→push，tag 只打绿 commit） | ❌ versionCode=1 / versionName="1.0.0" 写死在 `app/build.gradle.kts`，无脚本 | 缺失 | 版本单源迁到 `gradle.properties`，`build.gradle.kts` 读取；新建 `scripts/release.sh`（门禁 = ktfmt + detekt + lint + test + assemble） |
| D2 | 覆盖率 gate | ✅ jacoco + `jacocoTestReport/jacocoTestCoverageVerification` 进 pre-push 与 CI（教训 006：新 gate 先本地跑绿再进 CI） | ❌ 无 jacoco | 缺失（可选项） | 评估后引入：先本地跑绿再加（遵守 006），P2-2 核心单测补齐后才有意义 |
| D3 | detekt 配置目录 | ✅ `config/detekt/detekt.yml` + `baseline.xml` | 🟡 根目录 `detekt.yml` + `detekt-baseline.xml` | 位置不同，机制一致 | 低优先：可对齐到 `config/detekt/` |
| D4 | Convention plugins / 多模块 | ✅ `build-logic/` 10 插件 | ❌ 单模块 `:app` | 架构决策差异 | **不对标**（CLAUDE.md 已定单模块） |
| D5 | `.githooks/pre-push` | ✅ detekt/lint/test/coverage/assemble | ✅ ktfmt/detekt/lintDebug/test/assemble | 无（已对齐） | — |

### E. Agent 资产（skills）

| # | 项 | Float | CameraSync | 差距 | 建议动作 |
|---|---|---|---|---|---|
| E1 | `.claude/skills/android-kotlin-compose` | ✅ 引 Drjacky/claude-android-ninja | ❌ 无 | 缺失（可选） | 引入（对 Android 任务自动加载有用） |
| E2 | `.agents/skills/material-3-expressive` | ✅ M3 Expressive 设计参考 | ❌ 无 | 不适用 | **不对标**（CameraSync 用 stable M3，未走 expressive 路线） |

### F. CLAUDE.md 差距

| # | 项 | Float | CameraSync | 建议动作 |
|---|---|---|---|---|
| F1 | GitHub CLI Flow（`gh issue/pr/checks/merge`） | ✅ 有 | ❌ 无 | 补章节（CameraSync 用 GitHub） |
| F2 | Project Skills 章节 | ✅ 有 | ❌ 无 | 引入 E1 后补 |
| F3 | Dependency health（`dependencyUpdates` / `buildHealth`） | ✅ 有 | ❌ 无 | 可选：未用 ben-manes/Gradle Doctor，不引入 |
| F4 | Key reference projects | ✅ NIA / RikkaHub | ❌ 无 | 补上游 rock3r/CameraSync 引用 |
| F5 | 模块依赖图 | ✅ | ❌ 单模块 | 不对标 |
| F6 | 🔴 强制规范（commit 先行 / @Preview / 本地 detekt / 先读 postmortem） | ✅ | ✅ 已对齐 | — |

### G. 文档深度（内容差距，非资产差距）

| # | 项 | Float | CameraSync | 建议动作 |
|---|---|---|---|---|
| G1 | `git-workflow.md`：PR 流程 / gh CLI / AI Code Review / PR 合并判断标准 / Issue 管理 | ✅ 完整五节 | 🟡 只有审查清单 + Merge 策略 | 补齐「PR 流程」「gh CLI 常用操作」「AI Code Review 流程」「Issue 管理」章节 |
| G2 | `postmortem/README.md` 高频雷区 | ✅ 量化表（规则/阈值/踩坑次数/对策）+ 设计建模 + 流程三类 | 🟡 结构同源但条目薄、无量化 | 按 Float 结构重排为三节；随历史自然增厚（不虚构条目） |
| G3 | `code-style.md` | ✅ 含测试命名 / Detekt 配置 / XML 资源规范 | 🟡 大体已对标；`命名约定`表缺 StateFlow 行；**「见 postmortem `003`」悬空引用**（本仓无 003） | 补 StateFlow 命名行；悬空引用改指本仓 postmortem「设计/建模」节 |
| G4 | `docs/README.md` 索引 | ✅ 含 CHANGELOG 等根文档 | 🟡 引用不存在的 `CHANGELOG.md` | A1 新建后闭环 |

### H. 现有漂移 / 悬空引用汇总（对标过程中顺带修复）

| # | 位置 | 问题 | 动作 |
|---|---|---|---|
| H1 | `docs/README.md:78` | 约定「所有 PR 更新 CHANGELOG.md」但文件不存在 | 新建 CHANGELOG.md（A1） |
| H2 | `docs/engineering/code-style.md:75` | 「见 postmortem `003`」——本仓无 003（Float 编号） | 改指本仓 postmortem README |
| H3 | `README.md` | 「Background Sync: foreground service」——P1-4 已删 `UsbSyncService` | 删除/改写 Feature 条目 |
| H4 | `.github/workflows/update_firmware_data.yml` | BLE 时代残留死 workflow | 删除 |
| H5 | 版本叙事 | CLAUDE.md/README 说「v2.3」，`build.gradle.kts` versionName="1.0.0" | D1 版本单源后统一（v2.3 是内部阶段标签，可注「对外 1.0.0」） |

## 三、明确不对标（保留 CameraSync 决策）

- **单模块 `:app`** vs Float 多模块 `:core:*` / `:feature:*`（CLAUDE.md 已定，避免无收益重构）
- **Metro**（编译期 DI）vs Float Hilt；**Khronicle** vs Float Timber（日志敏感信息处理各自成立）
- **stable Material 3** vs Float M3 Expressive alpha（不引入 expressive skills/ADR）
- 覆盖率 gate 默认**不引入**，除非 P2-2 单测补齐后评估（先本地跑绿，遵守 006 教训）

## 四、落地顺序建议

| 优先级 | 内容 | 对应项 |
|---|---|---|
| **P0 对标硬伤**（半 commit） | 新建 CHANGELOG.md；修 README 漂移；删 `update_firmware_data.yml`；修 code-style 悬空引用 | A1, A2, C3, H2, H3, H4 |
| **P1 文档资产** | 重建 `requirements/`（PRD 复活 + glossary + user-stories + feature-spec）；新建 `planning/architecture.md` ADR；新建 `docs/legal/privacy-policy.md`；git-workflow 补 PR/gh/AI review/Issue 章节 | B1, B2, B5, G1 |
| **P2 GitHub 工程化** | 新建 `.github/ISSUE_TEMPLATE/` ×3 + `PULL_REQUEST_TEMPLATE.md`；CLAUDE.md 补 GitHub CLI Flow + Key reference projects | C1, C2, F1, F4 |
| **P3 发布工程化** | 版本单源迁移 + `scripts/release.sh`；新建 `engineering/release-checklist.md` + `adb-commands.md` | D1, B3, B4 |
| **P4 可选** | 引入 `.claude/skills/android-kotlin-compose` + CLAUDE.md Project Skills 节；jacoco coverage gate（先本地跑绿）；detekt 目录对齐 `config/detekt/` | E1, F2, D2, D3 |

> 每项遵循 commit 纪律：先写 commit message、原子 commit、本地跑 `detekt` + `ktfmtCheck`。文档类 commit 用 `docs:` 前缀。
