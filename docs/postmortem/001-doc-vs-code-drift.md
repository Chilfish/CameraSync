# 001 — 文档与代码脱节：agent 引导读到已删除的子系统

## 摘要

CLAUDE.md 是符号链接，指向 AGENTS.md——一份 25KB、严重过时的文档：通篇描述已于 2026-08-02（commit `a385378`）删除的 BLE GPS 子系统（`vendors/`、`devicesync/`、`devices/` 等），并声称"无 Jetpack Navigation、自研 NavRoute 栈"（实际已用 Navigation 3）与测试设备 "Pixel 9 + Android 15"（实际 `docs/nikon/USB_SYNC.md` 记录 Xiaomi MIUI）。

## 影响

- Claude Code 每次进入仓库都加载 AGENTS.md，拿到的是**已不存在的架构**：给 agent 的错误地图，导航即迷路
- BLE 相关改动被误触发；README / CONTRIBUTING 同样残留 BLE 引用，文档与代码三处互相矛盾
- 符号链接在 Windows 检出为普通 9 字节文件，不易被察觉为"链接"，隐藏了真正内容所在

## 时间线

- 早期：CLAUDE.md 以符号链接指向 AGENTS.md（`git ls-files -s` mode `120000`，blob = `AGENTS.md`）
- 2026-08-02 `a385378`：删除 BLE GPS 子系统（~15 文件）——但 AGENTS.md / README / CONTRIBUTING 未同步清理
- 2026-08-09（本次）：对标 Float 审查时发现全部脱节，系统性修复

## 根因

1. **删除子系统时未同步删除/更新相关文档**——文档先行原则只覆盖"写"，未覆盖"删"
2. **多份 agent 引导文档并存且互相引用**（AGENTS.md 被 CLAUDE.md 链接），没有单一事实源，改动只更新其中一份必然脱节
3. **符号链接这种间接层在 Windows 上不可见**，链接指向的内容与文件名给人"同一份文档"的错觉

## 行动项

- [x] CLAUDE.md 转为真实普通文件（mode `120000` → `100644`），内容重写为真实现状（USB-only / Navigation3 / Metro DI）
- [x] AGENTS.md 弃用为指向 CLAUDE.md 的指针（保留给 Cursor/Copilot 等读 AGENTS.md 的工具）
- [x] README / CONTRIBUTING 清理 BLE 残留引用，统一测试设备说明
- [x] 归档过时/完成文档到 `docs/archive/`，建立 `docs/README.md` 索引
- [x] CLAUDE.md 强制规范增加「文档先行」，postmortem 索引「高频雷区」增加「删代码必删文档」「单一事实源」
