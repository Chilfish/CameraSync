# CameraSync 文档索引

> 文档体系对标 Float（`I:\dev\Float\docs\README.md`）。原则：**文档先行**——先改文档再写代码；已完成阶段归档、不主动读取。

## 规划（planning/）

| 文档 | 说明 |
|---|---|
| [当前状态 & TODO](planning/todo.md) | 当前状态、已完成功能、未来规划（cloud 备份 / 视频 / 多相机 USB / NEF Coil fetcher） |

## 工程规范（engineering/）

| 文档 | 说明 |
|---|---|
| [Git Workflow](engineering/git-workflow.md) | 分支模型、commit 规范（Conventional Commits）、commit 纪律、代码审查、版本发布 |
| [Code Style](engineering/code-style.md) | Kotlin/Compose 代码规范、命名约定、测试规范、detekt 规则 |

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
| [../LICENSE](../LICENSE) | Apache 2.0 |

## 约定

- 文档使用中文（README / CONTRIBUTING / LICENSE 除外）
- **文档先行**：每个任务第一步先更新对应文档，再写代码；实施过程中随反馈同步修改，而非事后补记
- 开发日志按天记录在 `development-log/`（新的一天新建 `YYYY-MM-DD.md`，跨天按天分开记录）
- 踩坑沉淀到 `postmortem/`（`00X-<主题>.md`），根因是流程级则同步更新 `CLAUDE.md` 强制规范或 `engineering/`
- 已完成阶段的规划文档移入 `archive/`——存档 = 历史记录，不主动读取
- 所有 PR 更新 `CHANGELOG.md`（Unreleased 部分）
