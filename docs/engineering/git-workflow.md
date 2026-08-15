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

### PR 流程

1. 创建 PR → 自动运行 CI（ktfmtCheck + Detekt + Lint + Unit Tests + AssembleDebug）
2. 至少 1 人 Approve；AI 辅助先进行自动化 Code Review
3. 所有 CI 检查通过
4. Create a Merge Commit 合并到 `master`

## AI 协作开发

本项目由 AI 主导开发，使用 `gh` CLI 进行 GitHub 全流程管理。

### gh CLI 常用操作

```bash
# 查阅 Issue/PR
gh issue list --state open
gh issue view 1
gh pr list --state open
gh pr view 1

# 创建与管理
gh issue create --title "feat: xxx" --body "..."
gh pr create --title "feat: xxx" --body "$(cat <<'EOF'
## Summary
...
EOF
)"

# Code Review
gh pr diff 1                    # 查看 PR diff
gh pr review 1 --approve        # 批准 PR
gh pr review 1 --request-changes --body "需要修改..."
gh pr comment 1 --body "LGTM!"

# PR 状态检查与合并
gh pr checks 1                  # 检查 CI 状态
gh pr merge 1 --merge --delete-branch   # Create a Merge Commit（保留原子 commit + 合并提交）

# Release
gh release create v1.0.0 --generate-notes
```

### AI Code Review 流程

1. **PR 创建后**，AI 自动执行：
   ```bash
   gh pr diff <PR_NUMBER>       # 获取 diff
   gh pr view <PR_NUMBER> --json title,body,files  # 获取元信息
   ```
2. **AI 根据审查清单逐项检查**，在 PR 下添加 Review 评论
3. **检测项**：
   - 架构一致性（是否符合 ADR，见 `docs/planning/architecture.md`）
   - 命名规范（是否符合 code-style）
   - 测试覆盖（新增代码是否有对应测试 + `@Preview`）
   - 正确性（去重判定、MTP 会话、MediaStore 事务）
   - 边界情况处理
4. **AI Review 结论**：
   - `--approve` — 无问题，建议合并
   - `--request-changes` — 有问题，列出具体修改点
   - `--comment` — 仅供参考的改进建议

### PR 合并判断标准

AI 辅助判断 PR 是否可合并，基于：
- CI 全部通过（ktfmtCheck + Detekt + Lint + Test + AssembleDebug）
- AI Code Review 通过
- 无未解决的 Review 评论
- PR 与对应 Issue 描述一致
- Commit message 符合 Conventional Commits

## Issue 管理

- Bugs 用 **Bug Report** 模板（`.github/ISSUE_TEMPLATE/bug_report.md`）
- 新功能用 **Feature Request** 模板
- 技术债/重构用 **Tech Debt** 模板
- 所有 PR 关联对应 Issue（`Closes #123`）

## 版本发布

使用语义化版本 [SemVer 2.0.0](https://semver.org/lang/zh-CN/)：

- **MAJOR** (1.x.x) — 不兼容的 API 变更
- **MINOR** (x.1.x) — 向后兼容的功能新增
- **PATCH** (x.x.1) — 向后兼容的 Bug 修复

> **实际发版走 `scripts/release.sh <version>`**（版本单源在 `gradle.properties`，见 `release-checklist.md`「版本纪律」）：
> versionCode 自动 +1 → 跑全量门禁 → commit `release: vX` → tag `vX` → push。tag 只打在门禁绿的 commit 上。

### 发布步骤（脚本等价流程）

1. 更新 `gradle.properties`：`VERSION_NAME` / `VERSION_CODE`（单源，不手改 build 文件）
2. 更新 `CHANGELOG.md`（Unreleased → 版本）
3. 跑全量门禁（`ktfmtCheck` + `detekt` + `lintDebug` + `testDebugUnitTest` + `assembleDebug`）
4. Commit `release: vX` → Tag `vX` → Push
5. GitHub Release 自动构建发布 APK（`release.yml`，需配置 keystore secrets）
