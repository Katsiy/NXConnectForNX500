# NX 连接 (NXConnect) —— 三星 NX500 照片传输与遥控 Android App

当前版本 **0.10.0**（versionCode 11）。App 启动页与遥控页标题都会显示版本号与构建时间，
方便确认真机装的是哪个包。

一个从零实现的 Android 客户端：连上三星 NX500 的 Wi-Fi，**浏览相机 SD 卡、把照片/视频/RAW 下载到手机、远程取景与按快门**。

协议不是猜的——全部来自对相机端 [`nx-remote-controller-mod`](https://github.com/mewlips/nx-remote-controller-mod) 的 C 源码、[`WApaperplane/NXWA-nx500_modding`](https://github.com/WApaperplane/NXWA-nx500_modding)（`nxwa` 分支）的 CGI 脚本，以及 [`ge0rg/samsung-nx-hacks`](https://github.com/ge0rg/samsung-nx-hacks) 的逆向资料；并且**在真机上逐项验证过**。逐条契约见 [NX500_PROTOCOL.md](NX500_PROTOCOL.md)。

---

## 1. 交付物

| 文件 | 说明 |
|---|---|
| [dist/NXConnect-0.9.0.apk](dist/NXConnect-0.9.0.apk) | **可直接安装的 APK**（v2 签名，Android 7.0+） |
| [NXConnect/](NXConnect/) | 完整 Android 工程（Kotlin，24 个源文件 + 5 个测试文件） |
| [_本会话整理-NXConnect-App/01_有用-技术文档/NX500_PROTOCOL.md](_本会话整理-NXConnect-App/01_有用-技术文档/NX500_PROTOCOL.md) | 相机 HTTP 协议逆向结果（端点/帧格式/按键表/坑）+ 真机实测响应原文 |
| [相机端遥控失效诊断报告.md](_本会话整理-NXConnect-App/01_有用-技术文档/相机端遥控失效诊断报告.md) | 遥控快门的一段排查记录（glibc 版本不匹配的根因与修复方向） |
| [兑现实测指南.md](兑现实测指南.md) | 真机联调步骤、预期结果、故障排查 |
| [_本会话整理-NXConnect-App/03_有用-开发测试工具/](_本会话整理-NXConnect-App/03_有用-开发测试工具/) | `mock_camera.py`（PC 端模拟相机）、`verify_mock_camera.py`（66 项契约自检）、`verify_nv12.py`（解码与边界回归）、`camera_st_capture_test.py`（telnet 自检） |

## 1.1 版本历史

| 版本 | 变化 |
|---|---|
| **0.10.0** | **新增 A/B/C 三组遥控按键**（可展开面板，30 个键）：A 组曝光参数（ISO / 白平衡 / 测光 / 对焦模式 / 对焦触发 / 景深预览）、B 组导航与转盘（方向键 / OK / MENU / Fn / 播放 / 删除 / 主副转盘 / 滚轮 / 录制）、C 组拍摄模式与驱动（P/A/S/M / 智能 / 场景 / SAS / 自定义1-2 / 单张 / 连拍 / 高速连拍 / 定时 / 包围）；**「曝光补偿」与「AE 锁定」改为按住生效**（keydown/keyup，配合转盘调节才和机身手感一致）；**遥控期间手机屏幕常亮**；**B 门时可选自动关相机屏幕**（省电 + 避免漏光，与手机常亮互不冲突） |
| 0.9.0 | **修掉热点场景下连不上相机**（发现流程的顺序错误：UDP 广播在绑定网络**之前**就发了，手机连着无外网的相机热点时广播从蜂窝网卡出去 → 相机永远收不到。现在改为**按网络逐个"先绑网、再广播、再扫描"**）；补 `CHANGE_WIFI_MULTICAST_STATE` 权限并持有 `MulticastLock`；手填 IP 时也先绑网络；**新增整段 /24 扫描兜底**；**新增 B 门长曝光** |
| 0.8.0 | **UI 全面重做为 Material 3**（动态配色令牌、圆角卡片、填充/描边按钮体系、表格化信息展示）；**修掉异形屏适配**（内容延伸到系统栏下方，但给顶部工具栏与底部操作区补 insets，不再被状态栏/刘海遮挡）；**去掉"更多按键"折叠面板**，改为三个常用功能键直接呈现；**换新图标**（含自适应图标与 5 个密度 PNG）；说明文字改浅灰，移除"模式/驱动 UNKNOWN"两条冗余提示 |
| 0.7.1 | **修掉时区导致的 1 小时偏差**：改用 `date -s "YYYY-MM-DD HH:MM:SS"` 设**墙上时间**（`@epoch` 会被相机时区换算，实测相机时区是 KST 而用户在 UTC+8）；提示符剥离改用正则，不再残留 `[root@drime5 ~]` |
| 0.7.0 | **连接后自动同步相机时间**（实测相机时钟停在 2015 年，偏差 11.6 年）；**主界面新增相机信息面板**；静音快门改为开关 |
| 0.6.0 | **修掉低画质崩溃**（NV12 平面布局理解错误：低画质 Y 平面也是半分辨率）；RAW 改存公共 `Download/NXConnect`；移除无意义的对焦按钮 |
| 0.5.0 | 崩溃日志落盘并在下次启动弹出；取景循环兜住 `Throwable`（含 OOM）；画质切换时安全重建 Bitmap |
| 0.4.0 | 快门时序改为"**S1 按住 + S2 单击**"（脚本化实测出的唯一可行时序）；取景解码移出主线程；RAW 提供下载入口 |
| 0.3.0 | 快门时序第一版修正、切换画质不再退出取景、取景 keep-alive、下载前体积确认 |
| 0.2.0 | 真机字段名精确匹配（`fw_ver`/`mac_address`）、`battery_percent=-1` 按未知处理、UDP 广播发现、RAW 占位与体积显示、目录页体积解析 |
| 0.1.0 | 首个可用版本：连接/发现、相册浏览、缩略图、多选下载入库、取景与遥控面板 |

## 1.3 UI 设计说明（Material 3）

- **配色**：按 Material 3 的角色令牌定义（`colorPrimary`/`colorSurfaceContainer`/`colorOutline` 等），
  主色蓝青与启动图标一致；深色主题优先。
- **组件**：`MaterialToolbar`、`MaterialCardView`（`Widget.Material3.CardView.Filled`）、
  `TextInputLayout.OutlinedBox`（圆角 14dp）、填充按钮（主操作）/ `.Tonal`（次主操作）/
  `.Outlined`（并列选项）三级层次；信息用 `TableLayout` 做键值对齐。
- **异形屏适配**：`EdgeToEdge.setup()` 关闭 `decorFitsSystemWindows` 让内容延伸到系统栏下方，
  再由 `EdgeToEdge.padTop/padBottom/padHorizontal` 给具体视图补 `statusBars + displayCutout` /
  `navigationBars` 内边距。**这是之前顶部被状态栏挡住的原因——只设了透明状态栏却没处理 insets。**
- **说明性文字**：统一 `NxNote` 样式（`nx_text_note` 浅灰），不再用警告色堆在界面上。

## 1.2 相机信息的来源与限制（实测）

主界面信息面板的数据来源分两层：

| 项 | 来源 | 可靠性 |
|---|---|---|
| 机型 / 固件 / MAC | daemon HTTP `/api/v1/camera/info` | ✅ 可靠 |
| 电量档位 / 充电 / 模式 | daemon HTTP `/api/v1/camera/status` | ✅ 可靠 |
| 相机时间 / 时间偏差 / SD 卡余量 / 运行时间 | 相机 shell（telnet 23，root 免密） | ✅ 装了 mod 就有 |
| **电量百分比** | — | ❌ **相机硬件不提供**（`st bat get_soc` 返回 ret=-1，sysfs 只有 `capacity=1330` 原始值） |
| **镜头信息** | — | ❌ **拿不到**：`st cap lens` 返回 `Cann't execute capture command over MCB!`，因为相机 App 正占用 MCB 总线 |
| `drive` 字段 | daemon | ⚠️ 实测常为 `UNKNOWN`（daemon 读不到，不是 App 问题） |
| `capdtm-api` 参数 | 8080 CGI | ⚠️ 实测全部返回空字符串 |

**时间同步的做法与已知限制**：

- **按墙上时间字符串设置**（`date -s "YYYY-MM-DD HH:MM:SS"`），不是 `@epoch`。
  原因：`@epoch` 设的是绝对时间，显示要经相机时区换算；而实测**相机时区是 KST(UTC+9)，
  用户实际在 UTC+8**，结果相机比手机快 1 小时（用户实测报过这个 bug）。
  改成字符串后相机按自己时区解释它，**显示时间就等于手机时间**（真机验证：读回完全一致）。
- 偏差也按**墙上时间**算，而不是比较 epoch —— 用户关心的是"相机显示的时间对不对"。
- **RTC 写不进去**：`hwclock -w` 报 `select() to /dev/rtc ... timed out`，且 `/etc/adjtime` 只读。
  所以同步只对当前开机周期有效，相机重启后可能回退；因此 App **每次连接都会检查并按需重同步**。

## 2. 先说清楚前提：相机端必须有服务

**原厂 NX500 固件里没有 HTTP 服务器**——Tizen 里缺 httpd/telnetd/ftpd 可执行文件（来源：MultiNX README 原文）。`/api/v1/*` 这些接口是第三方 mod 加进去的。

所以 App 按**三种相机形态**自适应（运行期探测，不是写死假设）：

| 形态 | 前提 | App 能做什么 |
|---|---|---|
| **完全体** `FULL_MOD` | 相机装了 nx-remote-controller-mod / NX-KS2 / NX-WA，按 `MOBILE` 键启动 | 实时列目录、缩略图、原图下载、**实时取景、按键注入、快门** |
| **简化体** `STATIC_ONLY` | 只有 80 端口文件服务（如 SD 卡 `autoexec.sh` 起 busybox httpd） | 列目录（解析 HTML 目录页）、原图下载 |
| **原厂** `STOCK` | 什么都没装 | 无 HTTP 通路，App 会明确告诉你缺什么 |

App 连上后会做一次**能力探测**并把结果写在状态行里，不会让你对着空白相册猜原因。

## 3. 功能

- **连接**：自动探测（遍历网关 + 已知历史地址 + 本机同网段常见主机位）或手填 IP；端口可改（默认 80/8080）
- **网络绑定**：相机热点**没有外网**，手机同时开蜂窝时请求会走错接口——App 用 `bindProcessToNetwork` 把流量钉在相机所在 Wi-Fi 上
- **相册**：目录倒序、目录内文件倒序（最新在前）；缩略图由相机端生成（15–30KB），不拉 4–8MB 原图
- **多选批量下载**：前台服务下载，通知栏进度，直接进系统相册（Android 10+ 走 MediaStore 免存储权限）
- **原图查看**：左右翻页、双指缩放、双击放大、拖动，降采样解码防止 OOM
- **遥控**：实时取景（自实现 NV12 解码）、半按对焦、快门、静音快门（NX500 电子快门）、播放/拍摄切换、16 个功能键注入、30 秒保活心跳

## 4. 快速开始

### 4.1 装 APK

```powershell
# 手机开启 USB 调试后
C:\DsNX\_host\android-sdk\platform-tools\adb.exe install -r C:\DsNX\dist\NXConnect-0.3.0.apk
```

或直接把 [dist/NXConnect-0.3.0.apk](dist/NXConnect-0.3.0.apk) 传到手机点击安装（需允许"未知来源"）。

### 4.2 用相机

1. 相机上**按 `MOBILE` 键**（装了 NX-KS/NX-WA 时这会启动相机的 Web 遥控服务）
2. 记下相机屏幕显示的 IP（形如 `192.168.43.90` 或 `192.168.0.105`）
3. 手机连到**与相机同一个网络**：
   - 相机做热点 → 手机连 `AP_SSC_*` 或 `NX500*` 开头的 Wi-Fi
   - 相机接家里路由器 → 手机连同一个路由器
4. 打开 App → 点「自动探测」，或填 IP 后点「连接」

## 5. 从源码构建

工具链已就位在工作区（无需污染系统环境）：JDK 21、Android SDK 35、Gradle 8.11.1。

```powershell
$env:JAVA_HOME='C:\DsNX\_host\jdk'
$env:ANDROID_HOME='C:\DsNX\_host\android-sdk'
$env:ANDROID_SDK_ROOT=$env:ANDROID_HOME
$env:PATH="$env:JAVA_HOME\bin;$env:PATH"

# 正式版（自带签名，可直接安装）
& 'C:\DsNX\_host\gradle\bin\gradle.bat' -p C:\DsNX\NXConnect :app:assembleRelease --no-daemon
# 产物：NXConnect\app\build\outputs\apk\release\app-release.apk
```

依赖走阿里云镜像（`settings.gradle.kts` 里已配 google/public/gradle-plugin 三个），Maven Central 与 dl.google.com 作为兜底。

## 6. 无相机时的验证

```powershell
# 1) 启动模拟相机（含真实缩略图生成、快门后自动新增照片）
python C:\DsNX\mock_camera.py --port 8081 --cgi-port 8082 --seed 6

# 2) 协议一致性自检（17 项，含"缩略图失败也返回 200"这类坑）
python C:\DsNX\verify_mock_camera.py http://127.0.0.1:8081 http://127.0.0.1:8082

# 3) 取景解码算法验证（Kotlin 整数实现 vs 标准 BT.601）
python C:\DsNX\verify_nv12.py
```

手机上联调模拟相机的做法：让手机与 PC 同一局域网，App 里填 **PC 的 IP**，端口改成 **8081 / 8082**。

## 7. App 依赖的协议契约（摘要）

**80 端口（daemon）**

| 端点 | 用途 |
|---|---|
| `GET /api/v1/camera/info` | 机型 / 固件 / MAC（也是自动探测的命中判据） |
| `GET /api/v1/camera/status` | 电量 / 充电 / 模式 |
| `GET /api/v1/liveview/get`、`get_low` | **裸 NV12** 帧（不是 JPEG）：720×480 高画质 518400 字节、低画质 259200 字节 |
| `GET /api/v1/input/inject?key=<keysym>` | 按键注入；快门键 `Super_R`，对焦 `Super_L` |
| `GET /api/v1/shutter/normal\|silent` | 快门（silent 为 NX500 专属静音） |
| `GET /api/v1/input/inject_keep_alive` | 保活，防相机休眠断网 |
| `GET /DCIM/<dir>/<file>` | 原图下载（4–8 MB/张） |

**8080 端口（CGI）**

| 端点 | 用途 |
|---|---|
| `GET /cgi-bin/dirlist?p=<相对路径>` | **实时** readdir 的 JSON 目录（新拍照片立刻可见） |
| `GET /cgi-bin/thumb?f=&w=64..1024&q=50..95` | 相机端 ImageMagick 生成的 JPEG 缩略图 |
| `GET /cgi-bin/capdtm-api?action=list` | 拍摄参数快照 |

**三个必须遵守的坑**（踩了就会出玄学故障）：

1. **缩略图并发必须 ≤ 2**。相机是单核 ARM，`convert` 打满 CPU 会饿死 80 端口心跳，前端会误报"相机已断开"。App 里用 `Semaphore(2)` 硬限流。
2. **缩略图失败也返回 HTTP 200 + `text/plain`**（正文 `thumb error: …`）。必须校验 Content-Type/JPEG 魔数，不能只看状态码。
3. **相册列表不要用 80 端口的 `/DCIM` 目录页**：那是 daemon 启动时的快照，新照片永远不出现。优先 8080 的实时 dirlist。

## 8. 工程结构

```
NXConnect/app/src/main/java/com/nxwa/nxconnect/
├── NxApp.kt                    全局单例：当前相机、端口、日志缓冲
├── data/
│   ├── Models.kt               DcimFolder / MediaEntry / CameraInfo / DirListing
│   └── NxNetworkFacts.kt       组网方式、候选网段、给用户的连接指引
├── net/
│   ├── NxHttp.kt               极简 HTTP 客户端（流式下载 + 进度回调）
│   ├── NetworkBinder.kt        把进程流量绑定到相机所在 Wi-Fi
│   ├── CameraDiscovery.kt      并发地址探测 + 相机/非相机判据
│   ├── CameraMode.kt           三种相机形态 + 运行期能力探测模型
│   └── NxCamera.kt             ★ 全部协议端点（80 daemon + 8080 CGI）
├── media/Nv12Decoder.kt        取景帧 NV12→ARGB（整数运算 + Bitmap 复用）
├── remote/KeyMap.kt            按键 → X11 keysym 全表
├── transfer/
│   ├── MediaStoreWriter.kt     写入系统相册（Q+ 走 MediaStore）
│   └── DownloadService.kt      前台下载服务 + 通知进度
└── ui/
    ├── MainActivity.kt         连接 / 探测 / 能力探测结果
    ├── GalleryActivity.kt      目录 → 网格 → 多选下载
    ├── ViewerActivity.kt       原图翻页查看
    ├── RemoteActivity.kt       取景 + 快门 + 按键面板
    ├── LiveViewView.kt         自绘取景控件（拉流循环）
    ├── ZoomImageView.kt        自写缩放（不引第三方库）
    ├── ThumbLoader.kt          缩略图缓存 + 并发闸门
    └── Adapters.kt             目录/网格适配器（含复用竞态保护）
```

**设计取舍**：刻意把依赖压到最少（只有 AndroidX + Material），HTTP 用 `HttpURLConnection`、缩放自己写、图片加载自己写——少一个依赖就少一份构建与镜像风险。

## 9. 已验证 / 未验证

**已在真机验证（2026-10-14，NX500 固件 1.12，相机 192.168.5.39）**

实测响应原文见 [NX500_PROTOCOL.md](NX500_PROTOCOL.md) 第 0.2 节。要点：

- 相机端 mod **完整部署**：80 端口 daemon 与 8080 CGI 群都在响应
- **JSON 字段名与 swagger 描述不一致**：`fw_ver`（不是 `firmware`）、`mac_address`（不是 `mac`）、
  状态字段是蛇形 `battery_charging` / `battery_percent` / `battery_level`
- **`battery_percent = -1` 是"未知"哨兵值**（App 已按未知处理，不会显示「-1%」）
- 相机接家用路由器时是 **DHCP 客户端**（实测 `.39`，网关是 `.1`）——
  所以发现靠 **UDP 5681 广播**（报文里带自己的 IP），而不是探网关
- 8080 缩略图 CGI 已部署，且**失败时确实返回 200 + `text/plain`**
  （实测 30 字节 `thumb error: source not found`），证实了"必须校验 Content-Type"这条契约
- **`/api/v1/shutter/normal` 不触发拍摄**：实测 HTTP 200、0.68 秒返回、文件数 98 → 98。
  它的实现是 `st cap capdtm setusr ADJUSTSHUTTERTYPE`，只切换快门类型。
  App 的「快门」已改为 **S1 半按对焦 → S2 全按拍摄** 的按键注入组合
- **目录里全是 RAW**：实测 `100PHOTO` 有 98 个文件，其中 91 个 `.SRW`（19–34MB）
  与少量 `.JPG`（7.4–9.2MB）。相机端 `thumb-cgi` 的扩展名白名单**不含 srw**，
  所以 RAW 走占位格显示 + 标注体积，点开/下载才拉原图（不可能为网格预览传 30MB）
- 文件真实大小只能从 **80 端口的 mongoose 表格目录页**取（8080 的 dirlist 不给大小），
  已实现解析并在相册里显示每张与所选合计的体积

**已在本机验证**

- **69 项单元测试全部通过**：协议层是纯 JVM 代码（不 import 任何 Android 类），
  测试直接跑真实的 `NxCamera`/`NxHttp`/`Nv12Decoder`/`ListingParser` 实现，
  对端是 socket 级假相机；真机 JSON 与 HTML 响应已固化为测试基线
- 取景解码与标准 BT.601 浮点实现逐像素比对：最大偏差 1（纯整数舍入）
- 10 种帧规格长度互不冲突，`FrameSpec.detect` 判定无歧义
- 工程可编译，产出签名 APK（v2 签名校验通过，minSdk 24 / targetSdk 35）

**仍未验证**

- **按键注入能否真正触发拍摄**：本次实测未触发，但当时相机状态是
  `mode:"UNKNOWN"`（daemon 读不到拍摄状态），很可能不在拍摄模式。
  需要在相机处于**拍摄模式**时复测——这直接决定遥控快门是否可用
- liveview 在真机上的**实测帧率与色彩**（算法已对齐标准，若偏色则是相机端 UV 布局差异）
- `/api/v1/osd/get` 的编码格式
- 真机缩略图的**成功路径**（当前只验证了 JPG 的失败路径；`.SRW` 因白名单限制必然失败）
- 原厂 UPnP/SOAP 通路（完全不改相机就能连）—— 协议骨架已整理，尚未实现

详细实测步骤见 [兑现实测指南.md](兑现实测指南.md)。

## 10. 许可证

派生自 AGPL-3.0 的 NX500 modding 生态（`nx-remote-controller-mod`、`nx500_nx1_modding`），协议知识来自这些项目，故本工程同样以 **AGPL-3.0** 发布。
