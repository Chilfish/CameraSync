# 尸检报告索引（Postmortems）

> **开写任何代码前，先读本页。** 本仓库历史踩坑全部沉淀于此，对照「高频雷区」自查后再动工，避免重复返工。
>
> 原则：**blameless** —— 不追究"谁写错了"，只追"什么系统条件允许它发生"，然后修系统和流程。

## 索引表

| 编号 | 主题 | 根因归类 | 一句话教训 | 状态 |
|---|---|---|---|---|
| [001](001-doc-vs-code-drift.md) | 文档与代码脱节：AGENTS.md 描述已删除的 BLE 子系统、错误的导航实现与测试设备 | 文档未随代码演进 / 单一事实源缺失 | 删除子系统时同步清理文档；维护唯一事实源（CLAUDE.md）；符号链接 CLAUDE.md 指向过时文档会让 agent 拿错上下文 | 已修复（2026-08-09） |

## 高频雷区（写码前自查）

### 1. 静态分析 / 格式（每 commit 提交前本地跑 `./gradlew detekt` + `./gradlew ktfmtCheck`，不要等 pre-push/CI）

- **detekt 迟检测**：写码期间就开 detekt，别等 push（见 [001](001-doc-vs-code-drift.md) 同源：工具反馈滞后 → 写后重写循环）
- **ktfmt / ImportOrdering**：提交前跑 `ktfmtFormat`；删未用 import
- **TooManyFunctions / LongMethod**：文件级函数太多、方法超长时主动拆分
- **不要写「为未来预留」的死代码**：YAGNI，改 API 同步删旧入口

### 2. 设计 / 建模

- **一次性事件 vs 状态**：会被"消费"且只消费一次的是事件 → `Channel`；可重读的是状态 → `StateFlow`
- **共享单例状态**：默认假想"可能有多个订阅者"，别假设只有一个 collector
- **MTP 句柄是会话级**：`PhotoSyncManager` 去重键（`storageId + handle`）在相机断开/重连后失效，枚举新句柄不会误匹配旧键

### 3. 文档 / 流程（非代码）

- **文档先行**：先更新文档再写代码，随实施同步修改，事后补记 = 必然脱节
- **删代码必删文档**：移除子系统（如 BLE）时，同步清理 README / CONTRIBUTING / 文档索引中的引用（见 [001](001-doc-vs-code-drift.md)）
- **单一事实源**：agent 引导文档只保留一份真实的（CLAUDE.md），其余文件（AGENTS.md）改为指针，避免多份文档互相矛盾
- **CLAUDE.md 用真实文件**：符号链接在 Windows 检出成普通文件、内容指向别处，极易被忽视导致 agent 读到过时内容

## 如何新增一条 postmortem

1. 遇到返工/事故 → 先跑 `git log` 定位 commit 与影响，查 `docs/development-log/README.md`
2. 复制 [TEMPLATE.md](TEMPLATE.md)，编号顺延（`00X-<短横线主题>.md`），补摘要/影响/时间线/根因/行动项
3. 在本索引表加一行；若属于既有根因归类则在「高频雷区」补规则
4. 在 `docs/development-log/` 记录当天事件
5. 根因是流程级 → 同步更新 `CLAUDE.md` 强制规范或 `docs/engineering/*.md`
