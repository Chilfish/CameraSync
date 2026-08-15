# Release Checklist

> 目标：验证 release 包（R8 minify + shrink + 签名）在真机上满足发布标准。
> 执行环境：64 位 Android 真机 + `app/build/outputs/apk/release/app-release.apk`。

## 版本纪律（P0）

- 版本**单源**在 `gradle.properties`（`VERSION_NAME` / `VERSION_CODE`），`app/build.gradle.kts` 读取，禁止手改 build 文件
- 发版走 `bash scripts/release.sh <version>`：versionCode 自动 +1 → 跑全量门禁 → commit `release: vX` → tag `vX` → push（对标 Float：tag 只打在门禁绿的 commit 上）
- versionCode 严格递增，禁止回退（覆盖安装判定依赖它）

## 构建与安装

- [ ] `./gradlew assembleRelease` 产出已签名 APK
- [ ] `apksigner verify --print-certs app-release.apk` 显示预期证书（CN 与 keystore 一致）
- [ ] `adb install -r app-release.apk` 安装成功
- [ ] 冷启动进入画廊无闪退

## R8 冒烟

- [ ] release 包（minify 开）真机跑通 MTP 连接与传输，无 R8 崩溃（反射/序列化路径）
- [ ] 各页面 / 导航 / 返回键无 `ClassNotFoundException` / 反射错误

## USB/MTP 同步核心（真机 Nikon Z30）

- [ ] 插线 → USB 权限弹窗 → 授权后自动连接
- [ ] 枚举 + 网格画廊 + 文件夹导航正常
- [ ] 筛选（新照片默认）/ 分组 / 排序 / 网格密度
- [ ] 全选新照片 → 传输 → 完成面板「本次传输清单」可追溯
- [ ] **去重**：重复插线不重复导入；相机重连（handle 失效）后去重仍生效
- [ ] RAW + JPEG 成组展示与传输（下载格式：全部/仅 JPEG/仅 RAW）
- [ ] 传输失败重试、取消
- [ ] 传输历史可查
- [ ] 相机端删除照片
- [ ] 本地相册浏览 + EXIF

## 首启与设置

- [ ] 首次启动看到 MTP 模式引导；引导后不再打扰（设置页可重开）
- [ ] 设置项（分组/排序/下载格式/主题/网格密度）持久化生效
- [ ] 深色主题三态正常

## 稳定性

- [ ] 进程重建后状态正常
- [ ] 旋转屏幕 UI 正常
- [ ] 传输中拔线：回到 Disconnected，已保存文件完好
- [ ] **16KB 页面对齐**：release 包在 64 位设备正常启动（纯 Kotlin 无 native lib，预期天然满足，验证即可）

## 发布专项

- [ ] release 构建日志可落盘（Khronicle WARN+），debug 日志可查看
- [ ] 日志导出无敏感信息、不含照片内容
- [ ] 隐私核对：App 无任何网络请求（`adb shell dumpsys netstats` / logcat 无网络活动）

> 真机验证由作者在发布前执行，勾选并记录日期 / 设备型号。
