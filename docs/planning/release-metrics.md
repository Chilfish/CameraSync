# 发布后观测计划（P6-4）

> 依据：PRD §1.5 成功指标 + action-plan P6-4。目标：**不新增任何埋点/上报**（隐私政策承诺零网络请求），仅从本地已有数据聚合可观测信号。

## 对齐 PRD 成功指标

| PRD 指标 | 本地数据源 | 观测方式 |
|---|---|---|
| 传输可靠性（不丢片/不错传/重复插线零重复导入） | `TransferHistory`（每会话 `date|count|cameraModel`）+ logcat `TransferEngine` 的 `Transfer failed: <name>`（ERROR） | 导出日志中统计 `Transfer failed` 出现率；核对重复插线后 `usb_transfer_all_new` 计数（应为 0） |
| 核心路径耗时（插线→传输 ≤3 次点击） | 无埋点 | 人工回归（见下）；通过 Khronicle `ConnectionManager` 的 connect/load 时长日志粗估 |
| 传输速度（充分利用 MTP 带宽） | logcat 无速度日志 | 人工用大卡（>500 张）实测 MB/s，与 USB 2.0 上限对比 |
| 崩溃可观测 | release 日志本地落盘（logcat）+ 用户主动导出 | 用户报告崩溃 → 日志查看器导出 → 按 `AndroidRuntime`/`FATAL` 归类 |

## 执行方式（无新增代码）

1. **发布前基线**：真机 Nikon Z30 回归（action-plan P3-3）：插线→传输→回看→删除→重插线去重为 0。
2. **发布后收集**：依赖用户通过「日志 → 导出」主动分享；在 GitHub Issues 提供导出模板（`logcat -d` 即可，无需 root）。
3. **聚合脚本**（可选，`scripts/` 下，不入库）：
   - `Transfer failed` / `connectAndBrowse failed` / `loadRoot failed` 计数 → 传输失败率
   - `MtpDevice opened` 计数 vs 崩溃报告 → 连接稳定性
4. **评估节奏**：v1.0.0 发布后 2 周 / 4 周两个观察点，对照 PRD 指标给出达标/未达标结论；未达标项回流 action-plan。

> 注意：不引入 Crashlytics 等任何第三方 SDK（隐私政策 §四 零网络请求是硬约束）。若后续确需崩溃上报，须先修订隐私政策并单独决策。
