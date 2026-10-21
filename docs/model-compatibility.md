# 其它 NX 系列机型的兼容性调研

> 状态：**前期调研，未做任何实现改动**
> 初版 2026-10-21；同日据社区实证修订；同日再据**发布包二进制实证**补充（见第 8 节，
> 该节结论优先于前文的源码推断）
> 方法：读上游相机端源码（`nx-remote-controller-mod`，本地副本在 `_host/src/`）
> ＋项目自己的 NX500 实测 ＋`ge0rg/samsung-nx-hacks` 机型表与 Wiki
> ＋**上游 GitHub issue 里的真实用户报告**

---

## 0. 结论（已按社区实证修订）

| 机型 | 系统 | 上游 mod 状态 | 本 App 能用吗 | 判断 |
|---|---|---|---|---|
| **NX500** | Tizen / DRIMe5 | 作者主力机型 | ✅ 已实测 | 当前基线 |
| **NX1** | Tizen / DRIMe5 | **作者明说自己没有 NX1，从未验证**；issue #5 零回复 | **未知** | ⚠️ 不要承诺 |
| **NX300** | Tizen 1.0 / DRIMe4 | 用户实测：菜单/设置可用，但**实时取景坏了**、EV 键坏了 | **部分**（缺取景） | ⚠️ 价值有限 |
| NX300M / NX2000 | Tizen / DRIMe4 | 源码有枚举；NX300M 标 `TODO: check` | 未知 | 不建议 |
| NX30 | Tizen / DRIMe4 | 守护进程**无该机型枚举** | ❌ | 需上游先支持 |
| NX3000 / NX3300 / NX mini / Galaxy K Zoom | **Fujitsu M7MU（非 Linux）** | — | ❌ **不可能** | 无落点 |
| NX5 / NX10 / NX11 / NX100 | DRIMe2（µITRON） | — | ❌ | 无落点 |
| NX20 / NX200 / NX210 / NX1000 / NX1100 | DRIMe3（VXWorks） | — | ❌ | 无落点 |

**修订说明**：初版我判断「NX1 几乎零成本、NX300 中等成本」。拿到社区实证后要修正为：
**NX1 是"技术上接近、但从未被任何人验证"；NX300 是"OSD 可能可用、但实时取景在上游就是坏的"**。
两者都不能对外承诺。

---

## 1. 为什么大部分机型直接排除

`ge0rg/samsung-nx-hacks` 的机型表给出决定性依据：**只有 DRIMe4 / DRIMe5 机型跑 Linux**。

```
NX5/NX10/NX11/NX100                  DRIMeII   ❌  µITRON
NX20/NX200/NX210/NX1000/NX1100       DRIMeIII  ❌  VXWorks
NX3000/NX3300/NX mini/Galaxy K Zoom  M7MU      ❌  Fujitsu Milbeaut，不跑 Linux
NX300 / NX2000 / NX300M / NX30       DRIMeIV   ✅  Tizen
NX1 / NX500                          DRIMeV    ✅  Tizen
```

相机端 mod 是 **Linux 用户态守护进程**（读 framebuffer、mmap 取景缓冲、X11 注入）。
非 Linux 机型上没有可执行它的环境 —— 不是"适配量大"，是**没有落点**。

---

## 2. 上游实际支持到哪一步

### 2.1 名称上的支持范围

- `nx-remote-controller-mod` README：**NX1 / NX500 / NX300**
- `NX-KS`（ottokiksmaler/nx500_nx1_modding）：**仅 NX500 / NX1**
- `NX-WA`（WApaperplane）：**NX500 1.12 / NX1 1.41**，其它固件版本拒绝安装

→ **NX300 用户只能裸装 `nx-remote-controller-mod`**，用不了 NX-KS / NX-WA。

### 2.2 ⚠️「支持」与「验证过」是两件事（本轮新增）

仓库活跃度：★26、fork 9、23 个 issue、**最后一次代码更新 2023-02**。

**issue #5「NX1 support」是作者自己开的**，正文：

> "Since I don't have a NX1, I cannot be sure that all functions are operating in NX1.
> Please report the functions that do not work in NX1."

**零评论、零报告。** → **NX1 支持是作者按代码类推写的，从未有人验证过。**

**issue #20「No live picture in NX300」**（已关闭但未修复）：

> "the critical part of it, the live view, is not working on NX300.
> Tried on Android's latest versions and also with an old Android 5.0 version.
> Windows 10 doesn't show the live view either."

后续评论确认"bug still exists"，最后一次更新在 2016 年。
→ **NX300 的实时取景在上游一直是坏的，至今未修。**

**issue #21「[NX300] Everything works, except taking a picture」**：

> "everything works on my nx300 .... except taking an actual picture.
> I can switch modes, go into settings, everything, even take a video.
> But if I click on the regular shutter button, all the camera does is focus."

解决方式是"按住按钮往下滑"。**说明 NX300 上 OSD / 菜单操作可用**，
坏的只是网页那个快门按钮的交互（我们的 App 走 S1/S2 注入，是另一条路）。

**issue #19「EV (+/-) button not working on NX300」**（仍 open）：
NX300 固件 1.45，软件里 EV 键不动作，机身物理键正常。

### 2.3 社区规模很小

- mod 仓库内 issue 搜索 `NX300`：**0 条命中**
- 全站搜索 `NX300 AND (osd OR liveview)`：**0 条**
- 代码搜索 `NX300_FRAME_WIDTH`：**只有原仓库一处**，没有任何 fork 改过这个常量

→ 不能指望社区已经踩过我们要踩的坑。

---

## 3. 机型差异在哪

### 3.1 只有两档：new（NX1/NX500）与 old（其余）

```c
// x_model.c
#define OLD_NX_FRAME_WIDTH 800
#define FRAME_WIDTH        720
#define FRAME_HEIGHT       480

int get_frame_width(void) {
    if (is_old_nx_model()) return OLD_NX_FRAME_WIDTH;  // 800
    else                   return FRAME_WIDTH;         // 720
}
```

识别方式是读相机版本文件后**按名字精确匹配**：

```c
if      (strcmp(name, "NX1")    == 0) model = NX_MODEL_NX1;
else if (strcmp(name, "NX300")  == 0) model = NX_MODEL_NX300;
else if (strcmp(name, "NX300M") == 0) model = NX_MODEL_NX300M;   // 源码标 "TODO: check"
```

枚举里还有 `NX2000`、`NX3000`。**没有 `NX30`** —— NX30 即使有 Linux 也走不到正确分支。

### 3.2 ✅ NX300 屏幕分辨率已获独立证实（本轮新增）

初版只能引 mod 源码说"old 档 = 800"。现在有了硬件侧证据。

`ge0rg` Wiki 的 **NX300-KeyBindings** 页写明：

> "Screen size is **480x800** pixels, as can be determined by running `xdpyinfo | grep 'dimensions:'`"

横向即 **800×480**，与 mod 的 `NX300_FRAME_WIDTH 800` / `FRAME_HEIGHT 480` 完全吻合。
**这一条不需要固件就能确认。**

### 3.3 OSD 镜像

```c
// osd.c —— 块尺寸不分机型
#define NX1_500_FRAME_WIDTH  720
#define NX300_FRAME_WIDTH    800
#define FRAME_HEIGHT         480
#define BLOCK_WIDTH           80     // 所有机型相同
#define BLOCK_HEIGHT          40     // 所有机型相同
#define NUM_BLOCKS (NX300_FRAME_WIDTH * FRAME_HEIGHT / BLOCK_WIDTH / BLOCK_HEIGHT)  // 120
```

| 机型档 | 帧 | 块 | 块数 | 全量响应 |
|---|---|---|---|---|
| new（NX1/NX500） | 720×480 | 80×40 | 9×12 = **108** | `12 + 108×12808` = **1,383,276**（已实测一致） |
| old（NX300 等） | 800×480 | 80×40 | 10×12 = **120** | `12 + 120×12808` = **1,536,972**（未实测） |

**块尺寸不分机型** → 每块仍是 12808 字节，长度公式不变，只是块数变了。

### 3.4 实时取景

| 机型档 | 高画质 | 低画质 |
|---|---|---|
| new | 720×480 → 518,400 | 259,200 |
| old | 800×480 → **576,000** | **288,000** |

我们的解码器**已经支持** 800×480（`Nv12Decoder.FrameSpec.SPECS` 里有
`Triple(800, 480, ...)` 并标注 `// NX300`），因为它按上游 `liveview.js` 抄的。

**但是**：issue #20 说明 NX300 上取景根本没出图。可能原因：
(a) 相机返回的字节数与预期不符，或 (b) 网页端 NV12 解码本身写错了。

我们与网页端有一处关键差异：**`FrameSpec.detect(len)` 按字节数反推规格，会依次尝试
多个候选**；网页端是按机型直接定死。所以**我们的解码器可能比网页端鲁棒** ——
但这只是推测，**必须有真机验证**。

### 3.5 按键

上游 `keyinput.js` 里唯一按机型分支的键：

```javascript
} else if (key == 'EV') {
    if (isNx1()) { return 'XF86WWW'; }    // NX1
    else         { return 'XF86Reload'; } // NX500 / NX300
}
```

我们的 `KeyMap` 写死 `XF86Reload`（对 NX500 正确）。
**NX1 的 EV 应改为 `XF86WWW`**；而 **NX300 的 EV 按 issue #19 是坏的**，换 keysym 未必解决。

网页 UI 里的机型可见性（`index.html`）：

| 键 | 可见性 | 说明 |
|---|---|---|
| `MODE_CUSTOM1` / `MODE_CUSTOM2` | `nx1-nx500-only` | **NX300 没有自定义模式** |
| `MODE_SAS` | 两处 | NX500 显示 "SamsungAutoShot"，NX300 显示 "Lense Priority"（同 keysym，相机行为不同） |
| `MODE_WIFI` | `nx300-only` | 仅 NX300 有 |
| Drive / AF / ISO 标签 | `not-nx1-only` | NX1 上这几个方向键的机身含义不同 |

`ge0rg` Wiki 另记载 NX300 的 `st key mode` **改模式无效** →
**NX300 上 P/A/S/M 等模式键大概率不生效**（与"网页能切模式"矛盾，待真机确认）。

### 3.6 注入机制可能不同（重要）

`ge0rg` Wiki 记载 NX300 自带注入工具：

```
st key [push/release/click] [up|left|right|down|del|ok|s1|s2|menu|pb|fn|ev|rec|wifi|...]
st key jog [cw|ccw]
st key touch [push/release/click] [x] [y]     # 屏幕 480x800，x0,y0 在左下
```

并明确写了快门时序：**"push s1", "click s2", "release s1"** ——
与我们 NX500 上实测的时序一致，说明**快门这条路在 NX300 上应该有解**。

而 EV 用 `st key ... ev` 存在、X11 keysym 那条路却坏（issue #19）——
提示 NX300 可能要**换注入机制**，而不是换 keysym。

---

## 4. 我们代码里的阻塞点

| 位置 | 现状 | 其它机型需要 | 影响 |
|---|---|---|---|
| `NxOsdValidator.FRAME_W/H` | 严格等于 720×480 | 800×480 | **校验直接拒帧，OSD 完全不可用** |
| `NxOsd.BLOCK_COUNT` / `buildHashsParam` | 108 / 864 字符 | **120 / 960 字符** | hashs 长度不对 |
| `OsdView` 画布 | 取自 `EXPECTED_*` 常量 | 需按帧头动态 | 画布尺寸错 |
| `KeyMap` 的 EV | `XF86Reload` | NX1 需 `XF86WWW` | NX1 上 EV 无效 |
| `KeyMap` 的 `MODE_CUSTOM1/2` | 无条件显示 | NX300 不支持 | 点了无反应 |
| `KeyMap` 缺 `MODE_WIFI` | — | 仅 NX300 | 少一个键 |
| `Nv12Decoder.SPECS` | 已含 800×480 | ✅ | 无 |
| `camera/status` 解析 | 只验过 NX500 | `status.c` 有 9 处 `is_nx1()` 分支 | 主界面信息可能显示错 |
| 静音快门 | 无条件提供 | `shutter.c` 实际只对 NX500 有效 | NX1/NX300 上静默无效 |

### 一个关键观察

**协议本身已经是机型无关的**：`NxOsd.parse()` 从响应帧头读
`frameW/frameH/blockW/blockH` 并动态算列数，只校验整除关系。

**卡住的只是 `NxOsdValidator` 那条"必须等于 720×480/80×40"的硬断言** ——
那是当初为"坏数据必须在解析前拦住"故意加的保险（本项目出过越界崩溃）。
代价就是换机型必须动它。

---

## 5. 分级建议（修订后）

### NX1：技术上最接近，但**没有任何验证依据**

- 尺寸与 NX500 **完全相同**（同属 new 档）
- 已知唯一代码差异：**EV 键 keysym**（`XF86Reload` → `XF86WWW`）
- **风险**：上游作者自己都没有 NX1，issue #5 零回复。任何"NX1 可用"的说法
  目前都是**纯代码类推**

### NX300：OSD 可能可用，但**取景在上游就是坏的**

- ✅ 屏幕 800×480 已获硬件侧证实；菜单/OSD 操作按 issue #21 可用
- ❌ **实时取景 broken**（issue #20，2016 年至今）
- ❌ EV 键坏（issue #19，未解决）
- ⚠️ `st key mode` 无效 → 模式键可能不可用
- ⚠️ 需裸装 mod（NX-KS / NX-WA 都不支持）

**这条最关键**：我们的「相机屏幕叠加」是**以实时取景为底图**设计的。
NX300 没有取景 → 叠加退化成"黑底上看相机屏幕"，正是我们想避免的形态。

**但有一个反向机会**：我们的解码器按字节数自适应，可能比网页端鲁棒。
如果真机测出 NX300 的 liveview 其实有数据，那是我们比上游做得好的地方。

### NX300M / NX2000：不建议

- 上游自己对 NX300M 标 `TODO: check`，连稳定跑都没把握

### NX30：不可行（现阶段）

- 守护进程机型枚举里**没有 NX30**，识别不到会落到 unknown 分支

### 非 Linux 机型：不可行

---

## 6. 关于「搞一份官方固件看看」

**结论：不建议，性价比很低。**

本轮实际核查后的理由：

1. **没有现成的 DRIMe4 / DRIMe5 固件解包工具。**
   `ge0rg/samsung-nx-hacks` 的 `tools/` 下只有 `drime3-firmware.py`、
   `m7mu-decompress.py`、`m7mu.py`、`slp-firmware.py` —— 全是旧平台 / M7MU。
   NX300/NX1/NX500 的固件容器格式没有公开解包器。
2. **体积大**：NX300 固件约 324 MB，NX1/NX500 约 335 MB。
3. **能提供的信息已经用更便宜的方式拿到了**：
   - 屏幕分辨率 → `ge0rg` Wiki 用 `xdpyinfo` 实测过（480×800 = 横向 800×480）✅
   - 机型名字符串 → mod 源码的 `strcmp` 已写明 ✅
   - OSD 帧尺寸 → mod 源码 + JS 两处独立一致 ✅
4. **固件唯一可能补充的是 framebuffer 细节**，但那属于 daemon 侧，App 不需要知道。

→ 除非将来要改**相机端**（例如自己写注入器），否则不必碰固件。

---

## 7. 未验证清单

以下**全部未经真机验证**：

1. NX1：OSD 是否真返回 720×480；`camera/status` 字段差异；EV 改 `XF86WWW` 是否有效
2. NX300：OSD 是否真返回 800×480 / 120 块
3. NX300：liveview 到底返回什么（issue #20 只描述"没有画面"，没给字节数）
4. NX300：我们的解码器能否解出它的帧
5. NX300：S1/S2 注入是否可用（`st key` 文档说可以，但没在 mod 的 X11 注入路径上验证过）

**验证成本很低**：任何一台真机 + 仓库里现成的 `tools/camera/verify_osd_framerate.py`，
它会直接打出帧头四个字段、块数与耗时；取景用 `verify_liveview_throughput.py` 打字节数。
**一次运行就能把上面 5 条从"未知"变成"已知"。**

---

## 附：证据来源

| 结论 | 来源 |
|---|---|
| 机型与 SoC / 是否 Linux | `ge0rg/samsung-nx-hacks` README 机型表 |
| **NX300 屏幕 480×800** | **`ge0rg` Wiki `NX300-KeyBindings.md`（`xdpyinfo` 实测）** |
| **NX300 `st key` 注入与快门时序** | **同上** |
| **NX300 取景坏 / EV 坏 / 快门需滑动** | **mod 仓库 issue #20 / #19 / #21** |
| **NX1 从未被验证** | **mod 仓库 issue #5（作者自述无 NX1）** |
| mod 支持 NX1/NX500/NX300 | `mewlips/nx-remote-controller-mod` README |
| 机型枚举与识别 | `nx-remote-controller-daemon/nx_model.h`、`x_model.c` |
| OSD 帧宽 / 块尺寸 / 块数 | `nx-remote-controller-daemon/osd.c` |
| 分机型可见键 | `web_root/index.html`、`controlpanel.js` |
| 静音快门无机型判断 | `api_server.c`、`shutter.c` |
| NX500 实测数值 | 本项目 `docs/protocol.md`、`docs/osd-measurements.md` |

---

## 8. 发布包二进制实证（本轮新增，结论优先于前文推断）

下载了官方 release `v0.9` 的两个包并做二进制比对：

| 包 | 大小 | 下载量 |
|---|---|---|
| `nx-remote-controller-mod-v0.9_nx1_nx500.zip` | 10.0 MB | 1342 |
| `nx-remote-controller-mod-v0.9_nx300.zip` | 11.9 MB | **1662**（比 NX1/NX500 版还多） |

### 8.1 守护进程二进制**字节完全相同**

```
nx1_nx500/app/nx-remote-controller-daemon     157,261 字节  SHA256 2281087070B0A8A5...
nx300/remote/nx-remote-controller-daemon      157,261 字节  SHA256 2281087070B0A8A5...
```

→ **NX300 版不是单独编译的**。机型判断只在运行时发生，跑的是同一份
`osd.c` / `liveview.c` 逻辑。这坐实了「按 `is_old_nx_model()` 分两档」的推断：
**NX300 必然得到 800×480 / 120 块。**

### 8.2 NX300 的 90° 旋转是显式处理的

`osd.c` 抓屏命令（NX300 分支）：

```c
} else { // NX300
    if (w == 480 && h == 800) {
        "xwd -id %d | convert -size 480x800+(XWD_SKIP_BYTES) -depth 8 bgra:- -rotate 270 bgra:-"
    } else {
        "xwd -root | convert -size 480x800+... -rotate 270 bgra:-"
    }
}
```

屏幕物理是**竖屏 480×800**，`-rotate 270` 后变成 **800×480** 再送入分块逻辑。
→ NX300 的 OSD 输出尺寸确定是 800×480，块尺寸仍是 80×40（代码常量）。

### 8.3 NX1 有 EVF 分支，且 **EVF 模式下取景返回空**

`osd.c` 对 NX1 分三种情况：

| 活动窗口 | 处理 | `s_evf` |
|---|---|---|
| `1024x768 @ x=720`（EVF） | `convert -size 1024x768 ... -scale x480 -extent 720x480` | **true** |
| `720x480`（LCD 全屏） | `xwd -id` 直接取 | false |
| 其它 | `xwd -root` 后 `-crop 720x480+0+0` | false |

NX1 的 X 屏幕是 **1744×768**（LCD 720×480 在 0,0；EVF 1024×768 在 720,0）。

而 `liveview.c` 开头：

```c
if (is_nx1() && osd_is_evf()) {
    mg_printf(nc, "HTTP/1.1 200 OK\r\n... Content-Length: 0\r\n\r\n");
    return;                       // 空体！
}
```

→ **NX1 处于 EVF（取景器）模式时，`/api/v1/liveview/get` 返回 HTTP 200 + 0 字节。**
我们的 App 已把"空体"当"暂不可用"重试，不会崩，但用户会看到取景一直没画面。
**用 NX1 时必须切到 LCD 显示。**

### 8.4 低画质平面布局在所有机型上一致

`liveview.c` 的降采样发送：

```c
mg_send(nc, ys, frame_width * frame_height / 4);                          // Y  = w*h/4
mg_send(nc, s_nv12_mem + (frame_width * frame_height),
        frame_width * frame_height / 4 * 2);                              // UV = w*h/2
```

总长 `w*h*3/4`，Y 占 `w*h/4`（即 (w/2)×(h/2)），UV 占 `w*h/2`。
**与 `Nv12Decoder` 的 `expectedSize = w*h/4*3` 完全一致**，NX300 的 800×480
代入同样成立（288,000 字节）。→ 解码器对 NX300 **不需要改**。

### 8.5 取景依赖**硬编码物理地址**，与固件版本绑定

`nx_model.c` 三套地址：

```c
static off_t s_addrs_nx300[] = { 0xc6a14000, 0xc6db6000, 0xc7158000 };           // 3 个
static off_t s_addrs_nx500[] = { 0xbbaea500, 0xbbb68e00, 0xbbbe7700, 0xbba6bc00 }; // 4 个
static off_t s_addrs_nx1[]   = { 0xfb1be000, ..., 0xfb75e000 };                    // 6 个
```

`get_mmap_video_size()` = `frame_size + 4096`（NX300: 580,096；NX1/NX500: 522,496）。

这些是**物理地址**，靠 `mmap` 直读 DRIMe 的取景缓冲，**与固件版本强绑定** ——
官方页写明 NX500 需 1.12、NX1 需 1.41。固件不同则 mmap 可能失败或读到垃圾
→ **取景没画面**。

**这为 issue #20「NX300 没有实时取景」提供了具体的高概率解释**：该用户未报固件版本，
NX300 地址是否对其固件有效无从判断；而 `liveview.c` 只在 `s_nv12_mem != NULL`
时才发数据，探测失败就静默无画面。

### 8.6 NX300 包自带 glibc，绕过了 ABI 问题

NX300 包内含：

```
remote/tools/lib/ld-2.22.so      152 KB
remote/tools/lib/libc.so.6     1193 KB
remote/tools/lib/libm.so.6      425 KB
remote/tools/usr/bin/nx-input-injector   4.7 KB
remote/tools/usr/bin/xdotool             52 KB
remote/tools/usr/bin/convert            4.4 KB
```

→ 之前记录的「按键注入失效：`libxkbcommon` 需 GLIBC 2.17，相机只有 2.13」
**在 NX300 上被这个自带 glibc 2.22 的方案绕过了**，这也解释了为何 NX300 上菜单注入可用。

### 8.7 由本节得出的修正结论

| 项 | 修正后 |
|---|---|
| NX300 OSD 输出尺寸 | **800×480 / 120 块**（二进制实证，不再是推断） |
| NX300 OSD 能否工作 | 代码路径完整且有旋转处理；issue #21 亦证实菜单可操作 → **应该能用** |
| NX300 取景 | 解码侧无需改；若失败，原因在**相机端 mmap / 固件版本**，不在 App 侧 |
| NX1 取景 | **EVF 模式必然空体**；LCD 模式下应正常（同 NX500 尺寸） |
| NX1 OSD | 尺寸恒 720×480（EVF 会被缩放补边到 720×480）→ **与我们的常量一致** |
| 解码器 | 三款机型都不用改 |

**意外收获**：v0.7 的 release 里还附带过作者的 Android App
（`NXRemoteController-v0.7.apk`，1.5 MB），说明这条路作者早年也走过。

---

## 9. 关于「搞一套虚拟机环境」

**结论：对当前问题没有帮助，不建议做。**

虚拟机能"运行软件"，而我们现在缺的是**真实相机硬件的运行时行为**：

| 待确认的问题 | 虚拟机能否回答 |
|---|---|
| OSD 帧尺寸 / 块数 | ❌ 不必——已由发布包二进制确证 |
| 低画质平面布局 | ❌ 不必——源码与我们的实现已一致 |
| **相机端 mmap 地址是否匹配用户固件** | ❌ 物理地址属于 DRIMe SoC，无法仿真 |
| **NX300 取景到底有没有数据** | ❌ 同上 |
| App 在 NX300 上的整体表现 | ❌ 需要真机 |

且要在虚拟机里跑起来还得：解 Tizen 固件（**没有 DRIMe4/5 解包器**）→
交叉编译 daemon（armv7l / glibc 2.13）→ 伪造 framebuffer 与 DRIMe 设备。
投入巨大，产出的仍是对我们自己假设的验证。

**更划算的替代：扩展现有的 mock 相机。**
仓库已有 `tools/mock_camera.py`（双端口模拟，66 项自检通过）。
加一个"机型"参数，让它按 NX300 规格应答（OSD 800×480 / 120 块、
取景 576,000 / 288,000 字节），就能**在无相机情况下完整回归兼容性改动**。
比虚拟机便宜两个数量级，且正好覆盖我们真正会改的那部分代码。

---

## 10. 结论汇总（全部实证后）

| 机型 | 可行性 | 依据强度 |
|---|---|---|
| **NX500** | 已实测可用 | 真机 |
| **NX1** | 代码层面几乎就绪（尺寸同 NX500、OSD 恒 720×480），但**零真机验证**；EVF 模式下取景为空，需提示切到 LCD | 二进制 + 源码；**无真机** |
| **NX300** | **OSD 应该可用**（800×480/120 块，旋转已处理）；**取景取决于相机端 mmap 与固件版本**；需裸装 mod | 二进制 + 源码 + issue #21；**无真机** |
| NX300M / NX2000 | 不建议 | 上游标 TODO |
| NX30 | 不可行（枚举缺失） | 源码 |
| 非 Linux 机型 | 不可行 | ge0rg 机型表 |

**下一步仍只有一个：找一台真机跑一次 `tools/camera/verify_osd_framerate.py`。**
它会直接打出帧头四个字段与块数，把上表的"无真机"补上。
---

## 11. 实现状态（v0.16.0 已实现）

本节记录"调研结论落地成了什么"。**NX500 以外的机型仍未真机验证**，
下面的规格全部来自上游源码与官方发布包考证。

### 11.1 代码改动

| 位置 | 改动 |
|---|---|
| `net/CameraProfile.kt`（新增） | 机型档案：OSD 帧尺寸、EV keysym、自定义/Wi-Fi 模式开关、是否已验证 |
| `net/NxOsdValidator.kt` | 帧尺寸改为**以响应帧头为准**；块尺寸仍强制 80×40（协议常量）；块数上限 120 |
| `net/NxCamera.kt` | 新增 `profile`（读 `camera/info` 后更新）；`osd()` 的 hashs 校验改为**恒定 120 组** |
| `ui/OsdView.kt` | 画布/位图/哈希表按帧尺寸动态，按协议上限 120 预分配；叠加比例按实际帧尺寸校验 |
| `ui/LiveViewView.kt` | 新增可设置的 `osdFrameW/H`，用于判断取景与叠加层是否同比例 |
| `remote/KeyMap.kt` | 新增 `groupsFor(profile)`：EV keysym 随机型、NX300 无自定义模式、NX300 多一个 Wi-Fi 键（`F3`） |
| `ui/RemoteActivity.kt` | 新增 `applyCameraProfile()`，在 `configureLiveView()` 末尾应用；机型变化时重建按键面板 |

### 11.2 为什么 `hashs` 固定 120 组

守护进程（`osd.c` 的 `osd_http_send`）：

```c
int last_hashs[NUM_BLOCKS];                      // NUM_BLOCKS = 120，栈上定长
for (i = 0; i < (hm->body.len - 6) / 8; i++)     // 按请求体长度循环写入
    last_hashs[i] = strtol(...);
num_blocks = frame_width * frame_height / block_w / block_h;   // 108 或 120
for (i = 0; i < num_blocks; i++) if (hash[i] != last_hashs[i]) send_count++;
```

- 发**少于** 120 → 超出部分是未初始化的栈值，比较必然不等 → 那些块每次重发
- 发**多于** 120 → 写越界（栈溢出）
- **正好 120** → 对 108 块的 NX500，多出的 12 项写进数组内、且循环只用到前 108 项，安全

所以客户端固定发 120 组，**与机型无关**，也不需要先知道机型。

### 11.3 无需相机即可回归

`tools/verify_mock_osd.py` 会拉起 mock 相机，分别以 NX500 / NX1 / NX300 身份检查：

- 帧头尺寸（720×480 / 800×480）
- 块数（108 / 120）与全量长度（1,383,276 / 1,536,972）
- 增量是否真的只回变化块
- 少发/多发哈希时的服务端行为

`tools/mock_camera.py` 新增了 `POST /api/v1/osd/get`，按 `--model` 决定规格。

### 11.4 仍未验证的部分

以下需要在真机上确认（任一台 NX1 / NX300）：

1. OSD 是否真按 800×480 / 120 块返回
2. `/api/v1/camera/status` 的字段语义（`status.c` 里有 9 处 `is_nx1()` 分支）
3. NX1 的 EV 键改成 `XF86WWW` 后是否真的能用
4. NX300 的取景是否有数据（上游 issue #20 报告无画面；我们推断是相机端 mmap
   地址与固件版本不匹配，属相机端问题）
5. NX1 在 EVF 模式下取景返回空体（上游 `liveview.c` 显式如此），App 会显示"暂不可用"，
   需提示用户切到 LCD

验证方式：接上相机后跑 `python tools/camera/verify_osd_framerate.py <相机IP>`，
它会直接打出帧头四个字段与块数。