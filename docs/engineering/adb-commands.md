# adb 常用命令速查

本文档记录日常开发中常用的 adb 命令、设备状态及其处理方式。基于 Android Studio Platform Tools 的 `adb`。

> ⚠️ **CameraSync 特情**：手机 USB 口被相机占用时，**USB adb 不可用**——调试相机连接请改用 **Wi-Fi 调试**（`adb pair` / `adb connect`），或先断开相机再用 USB adb。

## 设备连接与状态

### 常用命令

```bash
adb devices            # 列出已连接设备及其状态
adb devices -l         # 带详情（型号、transport_id）
adb kill-server        # 杀掉 adb 服务（卡死时先试这个）
adb start-server       # 启动 adb 服务（一般自动启动）
adb connect <ip:port>  # 连接 WiFi 调试设备（如 adb connect 192.168.1.5:5555）
adb disconnect <ip>    # 断开 WiFi 设备
adb -s <serial> <cmd>  # 指定某个设备执行命令（多设备时必须）
```

### 设备状态一览

| 状态 | 含义 | 处理方式 |
|---|---|---|
| `device` | 已连接且授权，正常可用 | — |
| `unauthorized` | 设备未授权 USB 调试 | 解锁手机，点弹窗「允许」；若无弹窗，`adb kill-server` 后重连 |
| `authorizing` | 正在等待授权弹窗被确认 | 解锁屏幕，手机上点「允许」 |
| `offline` | 设备失联/休眠/驱动异常 | 断开 USB 重插；`adb kill-server` + `adb devices`；WiFi 调试设备多为地址失效，`adb disconnect` 清理 |
| 无设备输出 | 没检测到设备 | 确认 USB 调试已开、用数据线（非仅充电线）；**若手机正连着相机，先拔相机** |

> 实战经验：开了 WiFi 调试后 `adb devices` 会残留僵尸 `offline` 连接。它们不影响 USB 设备，但会让不带 `-s` 的命令报「多设备」。要么 `-s` 锁定目标，要么 `adb disconnect` 清理。

## 安装 / 卸载

```bash
adb install <apk>                    # 全新安装
adb install -r <apk>                 # 覆盖安装，保留数据（日常装 debug 包最常用）
adb install -d -r <apk>              # 允许降级版本（debug 签名冲突时）
adb uninstall <package>              # 卸载（保留数据）
adb uninstall -k <package>           # 卸载但保留 /data 与缓存
adb push <local> <remote>            # 传文件到设备
adb pull <remote> <local>            # 从设备拉取文件
```

## 应用与 Shell

```bash
adb shell                           # 进入设备 shell
adb shell pm list packages | grep camerasync   # 找包
adb shell am start -n dev.sebastiano.camerasync/.MainActivity   # 启动 App
adb shell am force-stop dev.sebastiano.camerasync   # 强制停止
adb shell dumpsys activity activities | grep ResumedActivity   # 当前前台 Activity
adb shell cmd package resolve-activity --brief dev.sebastiano.camerasync  # 查启动 Activity
adb shell screencap -p /sdcard/s.png && adb pull /sdcard/s.png   # 截屏
adb exec-out screencap -p > shot.png                             # 截屏（直接写本地，推荐）
adb shell screenrecord /sdcard/v.mp4     # 录屏（Ctrl+C 结束）
```

## 日志

```bash
adb logcat                       # 全量日志
adb logcat -c                    # 清空缓冲区
adb logcat | grep -iE "NikonUsbManager|GalleryVM|PhotoSync|CameraSync"   # 过滤应用日志
adb logcat -s AndroidRuntime     # 只输出崩溃栈
adb logcat -v time               # 带时间戳
adb bugreport                    # 导出完整 bug 报告（很大）
```

## USB 相机调试提示（CameraSync）

- **Wi-Fi 调试优先**：相机占着 USB 口，用 `adb pair`（Android 11+ 无线调试）或 `adb connect` 连接手机
- 连接相机前先 `adb logcat -c` 清缓冲，插线后过滤 `NikonUsbManager` / `GalleryVM` 观察 MTP 打开与枚举
- 验证照片落盘：`adb shell ls /sdcard/Pictures/CameraSync/`（或 `adb shell content query --uri content://media/external/images/media`）

## 补充

- 查看 adb 版本：`adb version`；平台工具路径：Android Studio 内置 `%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe`
- Windows PowerShell 下管道 grep 需用 `Select-String` 或切换到 Git Bash
