# Release Checklist

> 目标：验证 release 包（R8 minify + shrink + 签名）在真机上满足发布标准。
> 执行环境：64 位 Android 真机 + `app/build/outputs/apk/release/app-release.apk`。

## 获取验证包（手动工作流，推荐）

真机验证无需本地构建——用 GitHub Actions 手动打包（`.github/workflows/manual-apk.yml`）：

1. Actions → **Manual APK** → Run workflow
2. `build_type` 选 `debug`（快速安装）或 `release`（验证 R8 混淆 + 签名）
3. 需要时勾选 `run_gate`（先跑 ktfmtCheck + detekt + lint + 单测）
4. 运行完成后下载 artifact：`camerasync-<version>-<type>-<sha>.apk` + `SHA256SUMS.txt`
5. `adb install -r <apk>`，或拷贝到手机直接安装

> release 类型需要仓库 Secrets：`RELEASE_KEYSTORE_BASE64` / `RELEASE_KEY_ALIAS` / `RELEASE_KEY_PASSWORD` / `RELEASE_STORE_PASSWORD`（与 `release.yml` 同一套）。缺 Secrets 时产物为未签名 APK，仅供功能验证、不可发布。

## 首次发版准备（keystore + Secrets，一次性）

发行渠道为 **GitHub Release**（不上架 Google Play）。发版前需备好签名密钥：

> **本机现状（2026-10-01）**：签名材料已就绪——keystore 与凭据备份在 **`%USERPROFILE%\.camerasync\`**（`release.jks` + `keystore.properties` + `CREDENTIALS.md`，**在仓库外，勿提交**），仓库 4 个 Secrets 亦已配置。若换机器，按本节重建即可。

1. 生成 release keystore（**务必离线备份，丢失后无法对已安装版本升级**）：

   ```bash
   keytool -genkeypair -v -keystore release.jks -alias camerasync \
     -keyalg RSA -keysize 4096 -validity 10950 -storetype PKCS12 \
     -storepass <STORE_PASSWORD> -keypass <STORE_PASSWORD> \
     -dname "CN=CameraSync, OU=Mobile, O=CameraSync, L=Unknown, ST=Unknown, C=CN"
   ```

   - keystore 放在仓库根 `release.jks`（已被 `.gitignore` 的 `*.jks` 覆盖，**绝不提交**）
   - 本地另有 `app/keystore.properties`（同样被忽略），供 `assembleRelease` 本机签名

2. 写入仓库 Secrets（`Settings → Secrets and variables → Actions`，或 `gh secret set`）：

   | Secret | 值 |
   |---|---|
   | `RELEASE_KEYSTORE_BASE64` | `base64 -w0 release.jks` |
   | `RELEASE_KEY_ALIAS` | keystore alias（如 `camerasync`） |
   | `RELEASE_KEY_PASSWORD` | key 密码 |
   | `RELEASE_STORE_PASSWORD` | store 密码 |

   ```powershell
   # PowerShell 生成 base64 并写入
   $b64 = [Convert]::ToBase64String([IO.File]::ReadAllBytes("release.jks"))
   $b64 | gh secret set RELEASE_KEYSTORE_BASE64 --repo <owner>/<repo>
   gh secret set RELEASE_KEY_ALIAS --repo <owner>/<repo> --body "camerasync"
   gh secret set RELEASE_KEY_PASSWORD --repo <owner>/<repo> --body "<KEY_PASSWORD>"
   gh secret set RELEASE_STORE_PASSWORD --repo <owner>/<repo> --body "<STORE_PASSWORD>"
   ```

> 无需 `RELEASE_TOKEN`：`release.yml` 使用默认 `GITHUB_TOKEN`（`permissions: contents: write`）创建 Release。

## 版本纪律（P0）

- 版本**单源**在 `gradle.properties`（`VERSION_NAME` / `VERSION_CODE`），`app/build.gradle.kts` 读取，禁止手改 build 文件
- 发版走 `bash scripts/release.sh <version>`：versionCode 自动 +1 → 跑全量门禁 → commit `release: vX` → tag `vX` → push（对标 Float：tag 只打在门禁绿的 commit 上）
- 推 tag 触发 `release.yml` → 自动创建 GitHub Release 并附签名 APK（**无需手动 `gh release create`**）
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
