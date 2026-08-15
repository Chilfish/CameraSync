# CameraSync 文档索引

> 文档体系对标 Float（`I:\dev\Float\docs\README.md`）。原则：**文档先行**——先改文档再写代码；已完成阶段归档、不主动读取。

## 需求（requirements/）

| 文档 | 说明 |
|---|---|
| [PRD](requirements/prd.md) | 产品需求文档 — 愿景、用户画像、功能需求、非功能需求、成功指标（取代归档版） |
| [Feature Spec](requirements/feature-spec.md) | 功能规格说明书 — USB 同步/本地相册/设置的流程、状态机、边界情况、测试要点 |
| [User Stories](requirements/user-stories.md) | 用户故事 — 按角色、场景、验收标准组织 |
| [Glossary](requirements/glossary.md) | 术语表 — 业务/产品/技术术语（MTP、去重软校验、NEF、GalleryState 等） |

## 规划（planning/）

| 文档 | 说明 |
|---|---|
| [当前状态 & TODO](planning/todo.md) | 当前状态、已完成功能、未来规划（cloud 备份 / 视频 / 多相机 USB / NEF Coil fetcher） |
| [行动计划](planning/action-plan.md) | **活跃行动计划**：P0 止血（去重/管线/路径/剪枝）→ P1 核心路径 → P2 工程债 → P3 收尾（2026-08-09 起，Apple 视角评审后重排） |
| [Float 对标差距分析](planning/benchmark-float.md) | 对照 Float（`I:\dev\Float`）流程与规范的差距清单：CHANGELOG / requirements / ADR / GitHub 模板 / release 脚本 / skills 等 7 类缺口 + 5 处文档漂移，含落地顺序 |
| [架构决策记录 (ADR)](planning/architecture.md) | 关键架构决策：MTP 方案、单模块、Metro、去重软校验、BLE 移除、质量门禁等 |

## 工程规范（engineering/）

| 文档 | 说明 |
|---|---|
| [Git Workflow](engineering/git-workflow.md) | 分支模型、commit 规范（Conventional Commits）、commit 纪律、PR 流程 / gh CLI / AI Code Review、Issue 管理、版本发布 |
| [Code Style](engineering/code-style.md) | Kotlin/Compose 代码规范、命名约定、状态与协程、测试规范、detekt 规则 |
| [Release Checklist](engineering/release-checklist.md) | 真机发布验证清单：版本纪律（单源 + release 脚本）、R8 冒烟、MTP 同步核心、稳定性 |
| [adb Commands](engineering/adb-commands.md) | adb 常用命令速查、设备状态、Wi-Fi 调试（相机占用 USB 口时）、日志过滤 |

## 评审（review/）

| 文档 | 说明 |
|---|---|
| [评审索引](review/README.md) | 设计评审索引（与 postmortem 互补） |
| [Apple 视角设计评审](review/2026-08-09-design-review.md) | 全项目设计评审：R1 双管线 / R2 去重不一致 / R3 假剪枝 / R4 硬编码路径 / R5 功能膨胀 / R6 测试赤字 |

## 技术参考（活动）

| 文档 | 说明 |
|---|---|
| [Nikon USB Sync](nikon/USB_SYNC.md) | **USB/MTP 权威技术参考**：权限流、BFS 遍历、MediaStore IS_PENDING、MTP 常量、Z30 标识、已验证设备 |
| [Nikon 文档](nikon/README.md) | Nikon USB 照片同步总览 + 文档索引 |
| [NEF EXIF 参考](nef_exif_full.txt) | ExifTool 对 `DSC_0873.NEF` 的完整 EXIF dump（NEF 方向/RAW 处理参考） |

## 存档（archive/）

已完成阶段或已被取代的文档统一归档于此。**仅供历史查阅，不再主动读取**（避免污染上下文）。

| 文档 | 说明 |
|---|---|
| [PRD](archive/PRD.md) | v2 产品需求文档（✅ 2026-08-02 完成） |
| [Sprint 1 Plan](archive/SPRINT_1_PLAN.md) | Sprint 1「Delight & Closure」计划（✅ 完成） |
| [Bug Fix Plan](archive/BUG_FIX_PLAN.md) | 2026-05 布局/显示/下载 Bug 修复计划（✅ 全部修复） |
| [Refactor Local Photos](archive/REFACTOR_LOCAL_PHOTOS.md) | 本地照片迁移 Coil 3 + MediaStore（✅ 完成） |
| [Session Summary](archive/SESSION_SUMMARY.md) | 历史交接文档（2026-05-06 → 2026-08-02） |
| [Multi-Device Architecture](archive/MULTI_DEVICE_ARCHITECTURE.md) | BLE 多设备同步架构（BLE 子系统已移除，仅历史） |
| [Multi-Vendor Support](archive/MULTI_VENDOR_SUPPORT.md) | BLE 多厂商策略（BLE 子系统已移除，仅历史） |

## 历史协议文档（已归档，只读）

BLE GPS 同步子系统已于 2026-08-02 移除（commit `a385378`）。相关协议文档留在 `ricoh/`、`sony/` 目录内，各自 README 标注 **ARCHIVED**，仅作历史参考：

- [`ricoh/`](ricoh/README.md) — Ricoh GR 系列 BLE/Wi-Fi 协议
- [`sony/`](sony/README.md) — Sony Alpha 系列 BLE/PTP/IP 协议

## 合规（legal/）

| 文档 | 说明 |
|---|---|
| [隐私政策](legal/privacy-policy.md) | 本地优先：照片/EXIF 仅本机、零网络上报、权限说明、卸载即删 |

## 项目记录

| 文档 | 说明 |
|---|---|
| [开发日志](development-log/README.md) | 按天开发日志（`YYYY-MM-DD.md`） |
| [Postmortem](postmortem/README.md) | 尸检报告索引——历史踩坑沉淀，开写代码前必读 |

## 根目录文档

| 文档 | 说明 |
|---|---|
| [../README.md](../README.md) | 项目介绍、技术栈、快速开始 |
| [../CLAUDE.md](../CLAUDE.md) | Claude Code 工作规范（单一事实源） |
| [../CONTRIBUTING.md](../CONTRIBUTING.md) | 贡献指南 |
| [../CHANGELOG.md](../CHANGELOG.md) | 变更日志（Keep a Changelog + SemVer） |
| [../LICENSE](../LICENSE) | Apache 2.0 |

## 约定

- 文档使用中文（README / CONTRIBUTING / LICENSE 除外）
- **文档先行**：每个任务第一步先更新对应文档，再写代码；实施过程中随反馈同步修改，而非事后补记
- 开发日志按天记录在 `development-log/`（新的一天新建 `YYYY-MM-DD.md`，跨天按天分开记录）
- 踩坑沉淀到 `postmortem/`（`00X-<主题>.md`），根因是流程级则同步更新 `CLAUDE.md` 强制规范或 `engineering/`
- 已完成阶段的规划文档移入 `archive/`——存档 = 历史记录，不主动读取
- 所有 PR 更新 `CHANGELOG.md`（Unreleased 部分）
