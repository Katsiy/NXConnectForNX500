# 【实测报告】NX500 遥控拍摄失效的根因（相机端问题，非 App 问题）

> 实测时间：2026-10-14　相机：NX500 固件 1.12 / 192.168.5.39 / glibc 2.13 / armv7l
> 结论：**遥控取景与浏览照片完全可用；但"按键注入"在相机端就失败了，与 Android App 无关。**

---

## ⚠️ 后续修正（2026-10-21）：本文主结论已被推翻

本文写于 2026-10-14，当时只看到一个**中间状态**，结论下得太早。后续证据：

**1. 按键注入其实是可用的。** 之后用 OSD 变化块数做客观判据实测：

| 注入 | OSD 变化块 |
|---|---|
| 静止基线（8 帧） | 0 |
| `KP_Up` | 25 |
| `KP_Enter`（OK） | 98 |
| `Menu` | 98 |

键盘注入**每次都可靠**（鼠标注入只在部分区域有效）。所以"注入失败"不成立。

**2. `GLIBC_2.17` 不是"无法绕过"的门槛 —— 官方包本来就是用 chroot 解决的。**
官方发布包（`nx-remote-controller-mod-v0.9_*.zip`）自带一整套 userland：

```
tools/lib/libc.so.6        glibc 2.22
tools/lib/ld-2.22.so       自带动态加载器
tools/usr/lib/libxkbcommon.so.0
tools/usr/lib/libxdo.so.3          ← 本文说"找不到"的那个
tools/usr/bin/nx-input-injector
tools/usr/bin/convert              ← 完整 ImageMagick
```

而 `common.sh` 里是这么用的：

```bash
TOOLS_PATH=$APP_PATH/tools
CHROOT="chroot $TOOLS_PATH"
XDOTOOL="$CHROOT xdotool"
XEV_NX="$CHROOT xev-nx"
```

**即：所有工具都 `chroot` 进自带 userland 运行，用的是包内的 glibc 2.22**，
根本不去碰机身那套 2.13。所以 §6 里"重新交叉编译"那几条方案都是**不必要的工作**。

**3. 本文为什么会得出错误结论？** 当时观测的是 NX-KS 自带脚本
（`/opt/usr/nx-ks/nx-rc/tools/...`）在**未 chroot** 的情况下直接跑 injector，
于是撞上机身 glibc 2.13。官方包的启动方式不是这样。

**保留本文的价值**：§5 的快门时序（S1 半按 → S2 → 松 S1）与 §4 的 XTEST 结论仍然成立，
后来在多机型调研中还被 ge0rg wiki 独立印证（NX300 的 `st key` 也是
"push s1 / click s2 / release s1"）。

---

## 1. 结论速览（**以下状态列已被上面的修正覆盖**）

| 功能 | 状态 | 说明 |
|---|---|---|
| 连接 / 发现相机 | ✅ 可用 | UDP 5681 广播 + HTTP 探测 |
| 浏览照片 / 缩略图 | ✅ 可用 | 8080 CGI `dirlist` + `thumb` |
| 下载原图 / RAW | ✅ 可用 | 80 端口 `/DCIM/...` |
| 实时取景（liveview） | ✅ 可用 | 高清 178ms/帧、低清 126ms/帧 |
| **遥控按键注入** | ~~❌ 相机端失败~~ → **✅ 可用** | 当时误判；见上方修正 |
| 遥控快门（走按键） | ~~❌ 同样失败~~ → **✅ 可用** | S1/S2 时序已实测 |
| `shutter/normal` 接口 | ⚠️ 部分 | 只写 `ADJUSTSHUTTERTYPE`，未见实际拍照 |
| 静音快门 | ⚠️ 未生效 | 同上 |

---

## 2. 根因：`nx-input-injector` 的两级动态库依赖问题

### 2.1 第一级：找不到 `libxdo.so.3`

相机端负责注入按键的可执行文件是 `nx-input-injector`，它依赖 `libxdo.so.3`。

实测该库**存在**于：

```
/opt/usr/nx-ks/nx-rc/tools/usr/lib/libxdo.so.3
```

但 daemon 进程的 `LD_LIBRARY_PATH` **不包含这个目录**，所以加载失败。

### 2.2 第二级：即使补上路径，`libxkbcommon.so.0` 要求 GLIBC_2.17

手动把上面的目录加进 `LD_LIBRARY_PATH` 后，报错变成下一个依赖：

```
libxkbcommon.so.0: symbol ... GLIBC_2.17 not found
```

而本机 glibc 是 **2.13**（`ldd --version` 实测）。这是**硬性 ABI 门槛，无法绕过**。

### 2.3 为什么这套库会带 GLIBC_2.17 需求

随包附带的动态库文件时间戳是 **2026-10-14**（与本次装机同期），
说明它们是**在更高版本 glibc 的构建环境里交叉编译出来的**，与相机自带的 glibc 2.13 不兼容。
即：**打包环节出了问题，不是使用方式的问题。**

---

## 3. 一个重要澄清：XTEST 本身是可用的

排查中有个容易误判的点：**相机自带的 X 服务器其实编译了 XTEST 扩展**。

证据（X 启动日志中的扩展清单）：

```
Initializing built-in extension XTEST
```

而且相机 `/usr/lib` 里**原本就有**：

```
libXtst.so.6
libX11.so.6.3.0
```

结论：**不需要"想办法启用 XTEST"——它本来就是启用的。**
问题纯粹出在那两个第三方库的 glibc 版本上。
所以修复方向应该是"用 glibc 2.13 兼容的方式重新编译/替换这两个库"。

---

## 4. 已排除的可能性（避免重复排查）

| 假设 | 实测结果 |
|---|---|
| 相机没装 telnetd，所以注入没权限 | ❌ 排除。23 端口 telnet 可用，root 免密 |
| 相机没装 httpd | ❌ 排除。8080 有 busybox httpd + CGI |
| 是网络/防火墙问题 | ❌ 排除。80/8080/21/23 全部可达 |
| 是 App 发的 keysym 不对 | ❌ 排除。按键名逐字对照相机端 `keyinput.js`，正确 |
| 是 S1/S2 时序不对 | ❌ 排除。脚本化实测出唯一可行时序（见 §5） |
| 相机端缺 gcc/python，无法编译 | ⚠️ 属实（无 gcc/python/perl，busybox 无 `sendevent`），但不是本次失败的原因 |

---

## 5. 附：快门时序（脚本化实测结论）

相机 S1 = 半按（对焦），S2 = 全按（快门）。**必须 S1 按住不放，期间点击 S2**：

```
keydown(S1 / Super_L)     # 按住半按
sleep 450ms
key(S2 / Super_R)         # 点击全按
sleep 2500ms              # 等写入
keyup(S1 / Super_L)       # 松开
sleep 1200ms              # 冷却
```

**反例**：先 `keyup(S1)` 再发 S2 → 只会对焦，不会拍照（机械两段式开关需要重叠）。

**另一个坑**：一次快门会写 **2 个文件**（`SAM_xxxx.JPG` + `SAM_xxxx.SRW`）。
所以不能用"文件数量差值"判断拍了几张，否则会把 1 张读成 2 张。

---

## 6. 修复选项

> ⚠️ **本节已被上方「后续修正」推翻。** 官方包用 `chroot tools/` 跑自带 glibc 2.22，
> 不需要重新编译任何库。下面几条只适用于"自己搭了一套不带 chroot 的启动脚本"的情况。

| 方案 | 做法 | 可行性 |
|---|---|---|
| ~~**A**~~ | 用 glibc 2.13 兼容工具链重新交叉编译 `libxdo.so.3` 与 `libxkbcommon.so.0` | ❌ 不必要：官方包自带 glibc 2.22 |
| ~~**A2**~~ | 只重编 `libxkbcommon`（静态链接掉 glibc 2.17 依赖） | ❌ 同上 |
| ~~**B**~~ | 让 daemon 启动时把 `tools/usr/lib` 加进 `LD_LIBRARY_PATH` | ⚠️ 仍会撞 GLIBC_2.17；正解是 `chroot tools/` |
| **C** | 不走近端注入，改用相机自带的 `st` 调试命令或直接写 `/dev/input/event*` | ⚠️ `st cap lens` 已被 MCB 占用挡住；`/dev/input` 注入需要自编 ARM 程序 |
| **D（推荐）** | 用官方 `common.sh` 的 `CHROOT="chroot $TOOLS_PATH"` 方式调用工具 | ✅ 官方采用，无需改任何库 |

---

## 7. 复现命令

```bash
# 1) 进相机 shell
telnet 192.168.5.39        # 用户名 root，密码为空

# 2) 确认 glibc 版本（应输出 2.13）
ldd --version

# 3) 看依赖缺哪个
LD_LIBRARY_PATH=/opt/usr/nx-ks/nx-rc/tools/usr/lib \
  /opt/usr/nx-ks/nx-rc/tools/usr/bin/nx-input-injector --help

# 4) 单独验证 libxkbcommon 的符号需求
readelf -sW /opt/usr/nx-ks/nx-rc/tools/usr/lib/libxkbcommon.so.0 | grep GLIBC_2.17
```

---

## 8. 对 App 的影响与当前取舍

- 遥控页面保留，因为它仍能提供**实时取景**这一核心价值。
- 快门按钮保留，因为**相机端一旦修好依赖，App 无需改动即可生效**（协议与按键名都已验证正确）。
- 原"对焦"独立按钮已移除——按键注入不通的前提下它没有意义（用户反馈"按了没反应"，与此一致）。
