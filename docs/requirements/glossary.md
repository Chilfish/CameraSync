# 术语表

**项目**: CameraSync | **最后更新**: 2026-08-15

---

## 业务术语

| 术语 | 英文 | 说明 |
|---|---|---|
| 相机模型 | Camera Model | 相机型号（如 Nikon Z30），用于 MediaStore 保存路径 |
| 照片组 | PhotoGroup | RAW + JPEG 同底文件（同名不同扩展名）的成组条目 |
| 新照片 | New Photos | 相机上未被本机导入过的照片（去重判定的反面） |
| 下载格式 | Download Format | 传输时包含的照片格式（全部 / 仅 JPEG / 仅 RAW） |
| 传输会话 | Transfer Session | 一次插线后从选择到传输完成的完整过程 |

## 产品术语

| 术语 | 英文 | 说明 |
|---|---|---|
| 插线即传 | Plug-and-Sync | USB 插入后自动检测相机（配合手动传输） |
| 跨会话去重 | Cross-session Dedup | 断开重连后仍能识别已导入照片，不重复传输 |
| 本次传输清单 | Session Transfer List | 传输完成面板中列出本次保存的全部文件 |
| 冷启动引导 | First-run Guide | 首次启动的一屏 MTP 模式说明 |

## 技术术语

| 术语 | 英文 | 说明 |
|---|---|---|
| MTP | Media Transfer Protocol | 相机通过 USB 呈现存储的标准协议（`android.mtp.MtpDevice`） |
| PTP | Picture Transfer Protocol | MTP 的前身，相机 USB 模式之一（MTP/PTP） |
| USB Host | USB Host | 手机作为主机读取相机设备 |
| Object Handle | Object Handle | MTP 中标识对象的会话级句柄，断开重连后失效 |
| Storage ID | Storage ID | MTP 存储（SD 卡/机身存储）的标识 |
| BFS | Breadth-First Search | 相机存储/文件夹遍历算法 |
| MediaStore | MediaStore | Android 媒体库，照片经 `IS_PENDING` 事务写入 |
| IS_PENDING | IS_PENDING | MediaStore 写入事务标志（置 1 写入 → 置 0 发布） |
| NEF | Nikon Electronic Format | Nikon RAW 格式（含内嵌 JPEG 预览） |
| EXIF | Exchangeable Image File Format | 照片元数据（快门/光圈/ISO/焦距/镜头等） |
| 去重软校验 | Soft Identity Check | 用 `name:size` 身份判定「同一照片」，handle 复用给新照片时视为未导入 |
| Metro | Metro | 编译期依赖注入框架（`@DependencyGraph AppGraph`） |
| Khronicle | Khronicle | 日志库（`com.juul.khronicle.Log`，禁止 `android.util.Log`） |
| UDF | Unidirectional Data Flow | 单向数据流：ViewModel 状态 → UI，UI 事件 → ViewModel |
| GalleryState | GalleryState | 画廊状态机（`Disconnected → Connecting → Loading → Browsing/Empty/Error → Transferring → TransferDone`） |

## 文件与格式

| 术语 | 说明 |
|---|---|
| `.NEF` | Nikon RAW 原始格式 |
| `.JPG` / `.JPEG` | 标准 JPEG 图片 |
| `.HEIC` | 高效图像格式（部分机型支持） |
