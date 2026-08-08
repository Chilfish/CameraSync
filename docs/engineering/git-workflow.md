# Git 开发流程

**项目**: CameraSync | **对标**: Float `docs/engineering/git-workflow.md` | **最后更新**: 2026-08-09

## 分支模型

采用 **Trunk-Based Development**（简化版，单模块小仓库）：

| 分支类型 | 命名格式 | 用途 | 生命周期 |
|---|---|---|---|
| `master` | — | 稳定分支，始终可发布 | 永久 |
| `feat/*` | `feat/usb-multi-camera` | 功能开发 | 合并后删除 |
| `fix/*` | `fix/mtp-handle-prune` | Bug 修复 | 合并后删除 |
| `refactor/*` | `refactor/coil-migration` | 重构 | 合并后删除 |
| `docs/*` | `docs/git-workflow` | 文档更新 | 合并后删除 |
| `release/*` | `release/1.1.0` | 发布准备（仅版本号/CHANGELOG） | 合并后删除 |

## Commit 规范

遵循 [Conventional Commits 1.0.0](https://www.conventionalcommits.org/)。

### 格式

```
<type>(<scope>): <description>

[optional body]

[optional footer(s)]
```

### Type

| Type | 说明 | 示例 |
|---|---|---|
| `feat` | 新功能 | `feat(usb): add multi-camera USB enumeration` |
| `fix` | Bug 修复 | `fix(usb): prune stale MTP handles on session change` |
| `refactor` | 重构（不改变行为） | `refactor(photos): migrate local loading to Coil 3` |
| `test` | 测试 | `test(viewmodel): add GalleryViewModel state tests` |
| `docs` | 文档 | `docs: rebuild docs system to benchmark Float` |
| `style` | 格式化 | `style: apply ktfmtFormat` |
| `chore` | 构建/工具 | `chore: add detekt to CI` |
| `perf` | 性能优化 | `perf(usb): eliminate blocking thumbnail prefetch` |

### Scope

Scope 用功能/模块名：`usb`、`photos`、`settings`、`logging`、`di`、`theme`、`navigation`、`docs`、`build`。

### 规则

- **Description 用英文祈使句**（命令式）：`add`、`fix`、`remove`（不用 `added`、`fixed`）
- 首字母小写、不加句号、不超过 72 字符
- **Breaking change**: footer 中标记 `BREAKING CHANGE: description`

### Commit 纪律

> **先想 commit message，再动工写代码。** 避免"上帝 commit"（一个超大 commit 包含所有变更）。

1. **写代码前**，先用 Conventional Commit 格式确定 commit message（如 `feat(usb): add folder download support`）
2. **围绕这个 message 的范围编写代码**，超出范围的工作留给下一个 commit
3. **当 diff 变大时（>10 文件或 >200 行），主动拆分**为多个独立 commit
4. 每个 commit 应能独立通过 CI 检查（detekt + ktfmtCheck + lint + test + assembleDebug）
5. 模块创建、功能实现、配置修改、文档更新应分开 commit

典型拆分示例：
```bash
# Commit 1: 模块基础设施
git commit -m "feat(usb): add UsbSyncPreferences with per-camera settings"

# Commit 2: 交互
git commit -m "feat(settings): wire theme mode and grid columns into SettingsScreen"

# Commit 3: 文档
git commit -m "docs: update development log for settings work"
```

## 代码审查

### 审查清单

- [ ] 代码逻辑正确，覆盖边界情况
- [ ] 测试充分（新功能有测试、改动无回归）
- [ ] 每个 `@Composable` Screen 有对应的 `@Preview`（视为 E2E 测试的一部分，多状态组件每个状态一个 Preview）
- [ ] 遵循代码规范（见 `code-style.md`）
- [ ] 无硬编码、无 `!!`、无 TODOs
- [ ] 相关文档已更新（文档先行）
- [ ] 提交前已本地跑 `./gradlew detekt` + `./gradlew ktfmtCheck`

### Merge 策略

- **Create a Merge Commit** — 保留 PR 内每个原子 commit，同时生成合并提交，PR 在历史中可追溯（契合「每 commit 独立过 CI」纪律）
- 各 commit message 沿用 Conventional Commits 格式；PR 标题用于 PR 描述与关联 Issue
- 若 PR 内含大量 WIP / 格式修正等无意义 commit，先本地 `git rebase -i` 整理为原子 commit 再合并

## 版本发布

使用语义化版本 [SemVer 2.0.0](https://semver.org/lang/zh-CN/)：

- **MAJOR** (1.x.x) — 不兼容的 API 变更
- **MINOR** (x.1.x) — 向后兼容的功能新增
- **PATCH** (x.x.1) — 向后兼容的 Bug 修复

### 发布步骤

1. 从 `master` 创建 `release/x.y.z` 分支
2. 更新 `CHANGELOG.md`（Unreleased → 版本）
3. 更新 `versionCode` / `versionName`（`app/build.gradle.kts`，仅此一次变更）
4. 创建 PR → 合并到 `master`
5. 打 Tag：`git tag v1.0.0 && git push --tags`
6. GitHub Release 自动构建发布 APK（`release.yml`，需配置 keystore secrets）
