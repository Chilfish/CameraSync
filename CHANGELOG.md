# Changelog

All notable changes to CameraSync will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/lang/zh-CN/).

> 首个正式版本（对外 v1.0.0，内部阶段标签 v2.3）尚未发布，当前全部变更记录在 `[Unreleased]`。
> 版本单源在 `gradle.properties`，发版走 `scripts/release.sh`（见 `docs/engineering/release-checklist.md`）。

## [Unreleased]

### 文档与流程（对标 Float，2026-08-15）

- 完全对标 Float（`I:\dev\Float`）的流程与规范，差距清单见 `docs/planning/benchmark-float.md`
- 新增 `CHANGELOG.md` / `docs/requirements/`（PRD、Glossary、User Stories、Feature Spec）/ `docs/planning/architecture.md`（ADR-001..010）/ `docs/legal/privacy-policy.md`
- 新增 GitHub Issue（bug / feature / tech-debt）与 PR 模板；`git-workflow.md` 补齐 PR 流程、gh CLI、AI Code Review、Issue 管理章节
- 版本单源迁至 `gradle.properties`（`VERSION_NAME` / `VERSION_CODE`）+ 新增 `scripts/release.sh`（门禁 → commit → tag → push）；新增 `docs/engineering/release-checklist.md`、`adb-commands.md`
- 文档漂移修复：README「Background Sync」条目删除（自动同步已移除）、code-style「postmortem 003」悬空引用改指本仓「设计/建模」节、删除 BLE 残留 workflow

### Added — USB 照片同步（核心）

- USB MTP 连接、照片枚举、文件夹导航、3 列网格画廊、渐进式照片加载（先 30 张，后台继续）
- RAW + JPEG 分组（NEF/JPG 对 + RAW 徽章）、EXIF 详情面板（快门/光圈/ISO/焦距/镜头）
- 长按多选批量传输、传输速度与 ETA、传输完成回看（本次传输清单）
- 去重（SharedPreferences，`storageId + handle` 键 + `name:size` 身份软校验，跨会话）
- 筛选（全部 / 新照片 / RAW / JPEG，默认新照片）、三种分组、五种排序、网格密度切换
- 失败重试、传输历史、从相机删除照片、触觉反馈、存储空间状态栏
- 冷启动 MTP 模式引导（首启一屏，设置页可重开）

### Added — 本地相册

- Coil 3 图片加载（替代裸 BitmapFactory）、MediaStore 查询（Android 13+ 分区存储）
- 目录浏览（仿 USB 文件夹导航）、面包屑导航、本地 EXIF 详情面板、下拉刷新

### Added — 设置与主题

- 设置页（分组、排序、下载格式、主题、网格密度）、深色主题三态（跟随系统/浅色/深色）、中文字符串资源化

### Added — 基础设施

- Metro 编译期 DI、Khronicle 日志引擎 + 日志查看器、Material 3 主题系统、调度器注入（可测试性）
- detekt（含 compose-rules）+ ktfmt 质量门禁、`.githooks/pre-push`、GitHub Actions CI

### Removed

- BLE GPS 同步子系统（Ricoh / Sony）于 2026-08-02 移除（commit `a385378`），协议文档归档 `docs/ricoh/`、`docs/sony/`
- 未接线的自动同步死代码（`UsbSyncService` / `UsbSyncCoordinator` / 无效开关）于 2026-08-09 移除（YAGNI）

### 工程（2026-08-09 → 08-15）

- 文档体系对标 Float：CLAUDE.md 真实化、AGENTS.md 弃用、engineering / postmortem / development-log / archive / review 体系搭建
- Apple 视角设计评审两期（R1–R19）落地与处置：双管线收敛、去重键统一、剪枝真实化、保存路径真实化、核心路径打磨、God Object 拆分、评审 R8 生命周期 / R12 幽灵权限等进入 P4–P6 行动计划
