# capdtm 参数表（NX500 固件 1.12 实测）

> 实测时间：2026-10-22　相机：NX500 固件 1.12 / 192.168.5.39 / root telnet

## 0. 结论先说：**参数一直都能读**；写只改影子变量（见第 6 节）

我们长期在 README 与 protocol.md 里写着「capdtm 只能读到 1 个参数，
无法读写曝光参数，所以只能靠屏幕镜像」。**这个结论是错的**，错因很具体：

| 错误做法 | 正确做法 |
|---|---|
| st cap capdtm getusr USERDATA_ISO | st cap capdtm getusr ISO |
| st cap capdtm setusr ISO ISO_400 | st cap capdtm setvar 或按 ID 写 |

usrlist 里参数确实**叫** USERDATA_*，但 getusr **只认去掉 USERDATA_
前缀的短名**。带了前缀就返回 Invalid argument[2]=USERDATA_ISO，
看起来就像"参数不存在"——我们因此误判了很久。

## 1. 正确用法

`sh
# 列出全部 usr 参数（129 个）
st cap capdtm usrlist
# 列出全部变量（66 个）
st cap capdtm varlist

# 读：短名 / 序号 / 十进制均可
st cap capdtm getusr ISO            # -> UserData is ISO_100 (0x50001)
                                    #    （真机输出行首还有一个 NUL 字节，解析时要剔掉）
st cap capdtm getusr 5              # -> 同上（5 是 usrlist 的序号）

# 读变量
st cap capdtm getvar TV             # -> Variable Data is 0x00000000_0000ffb0

# 写变量（数据 + 长度）
st cap capdtm setvar EVC 0x0e 4     # -> VariableData is set
st cap capdtm setvar TV 0x0000ffa0 4

# 写 usr 参数：第二个参数也要 ID/十进制，**不能**用 ISO_400 这种名字
st cap capdtm setusr ISO ISO_400    # ✗ Invalid argument[3]=ISO_400
`

## 2. 实测：读（全部成功）

| 命令 | 返回 |
|---|---|
| getusr ISO | ISO_100 (0x50001) |
| getusr WB | WB_AUTO (0x60000) |
| getusr METERING | METERING_CENTERWEIGHTED (0xd0001) |
| getusr DRIVE | DRIVE_SINGLE (0xa0001) |
| getusr IMAGEQUALITY | IMAGEQUALITY_RAW_SUPERFINE (0x40004) |
| getusr AFMODE | AFMODE_SINGLE (0x70000) |
| getvar AV | x00000000_00000060 |
| getvar TV | x00000000_0000ffb0 |
| getvar SV | x00000000_00000000 |
| getvar EVC | x00000000_0000000a |
| getvar WBCOLORTEMP | x00000000_0000157c（= 5500 K） |
| getvar BULBTIME | x00000000_00000000 |
| getvar S2PUSHED | x00000000_00000000（快门未按下） |

## 3. 实测：写能改写并回读，**但相机并未消费**（见第 6 节）

| 操作 | 结果 |
|---|---|
| setvar EVC 0x0e 4 | VariableData is set，回读 x0000000e ✅ |
| setvar TV 0x0000ffa0 4 | VariableData is set，回读 x0000ffa0 ✅ |
| 还原 EVC=0x0a / TV=0xffb0 | 回读一致 ✅ |
| setusr ISO ISO_400 | ✗ Invalid argument[3] —— 第二个参数要 ID |

## 4. 未解的问题：AV / TV 的编码

实测 AV = 0x60、TV = 0xFFB0、EVC = 0x0A，但**还没确定换算关系**：

- TV = 0xFFB0 按有符号 16 位是 -80；若按 APEX×32，则 -80/32 = -2.5 → 2^2.5 ≈ 5.7 s
- EVC = 10，若按 1/10 档则 +1.0 EV，若按 1/3 档则 +3.3 EV
- AV = 0x60 = 96，若 APEX×32 则 3.0 → f/2.8

**没有验证过**。确定方法：把 TV 设成几个已知值，看相机屏幕显示多少秒；
或在 App 里读出来后与相机屏幕对照。**在验证之前不要把这套换算写进代码。**

## 5. 对项目的意义

如果 App 能走 telnet（23）执行 st 命令，那么：

| 能力 | 之前 | 现在 |
|---|---|---|
| 显示当前 ISO / 白平衡 / 测光 / 驱动 / 画质 / 对焦模式 | ❌ 只能靠 OSD 镜像看图 | ✅ getusr 直接读 |
| 显示光圈 / 快门 / 曝光补偿 | ❌ 同上 | ✅ getvar 直接读（编码待解） |
| 修改曝光补偿 | ❌ 只能注入按键 | ✅ setvar EVC 直接写 |
| 修改快门速度 | ❌ 同上 | ✅ setvar TV 直接写 |
| 感知快门按钮按下 | ❌ | ✅ getvar S2PUSHED |
| 注入触摸 | ⚠️ 鼠标注入只在部分区域有效 | ❓ setvar TOUCH_POSX/POSY 待验证 |

**前提**：telnet 由相机端第三方组件提供（原厂固件没有），且需要用户开启。
没有 telnet 时仍要回落到现有的 OSD 镜像 + 按键注入。

---

## 6. 写入只改影子变量（2026-10-22 补）

`setvar` 返回 `VariableData is set`、回读也确实变了，**但这不等于相机用了它**。
判据是"相机是否做范围校验"——正常实现应该夹紧越界值：

```
设 TV = 0x00007fff (+32767)  -> 回读 0x00007fff   ← 原样接受，没夹紧
设 TV = 0x0000f000 (-4096)   -> 回读 0x0000f000   ← 原样接受
设 TV = 0x00008001 (-32767)  -> 回读 0x00008001   ← 原样接受
设 AV = 0x00000000 / 0x7fff  -> 回读一致，也没夹紧
```

NX500 的 S 档范围是 1/4000s ~ 30s，对应 APEX 约 `[−4.9, +11.97]`；
若按 APEX×32 编码，合法区间应是 `[−157, +383]`。相机对 `±32767` 都不做反应，
说明 `setvar` 写的是一个和曝光引擎解耦的影子存储。

**结论：写入未通。** 要真正改曝光，得找让相机重载参数的触发条件，
或者走 PTP operation（见第 8 节的最后一段）。

## 7. App 侧实现（只读）

`NxTelnet` + `NxCapdtm` + `NxCapdtmReader` 实现**只读**通路。
主界面在 telnet 可用时追加 ISO / 白平衡 / 测光 / 驱动 / 画质 / 对焦模式 / 曝光模式
七行真实参数（全部来自 `getusr`，已实测跟得上相机状态）；telnet 不可用时静默跳过。

`AV` / `TV` / `SV` / `EVC` **不显示**，因为它们不跟踪状态（第 8 节）。
这些字段仍保留在 `CapdtmSnapshot` 里，但类注释已标明不可信。

## 8. 判定实验（2026-10-22）：`getvar` 的曝光变量**不跟踪相机状态**

第 4 节留下的"编码未定"问题，用对照实验查清了 —— 结论比编码问题更糟：
**这组变量根本不会更新**。

三次读取，相机状态差异极大，读数却完全一致：

| # | 相机屏幕 | DIALMODE/ISO（`getusr`） | AV | TV | SV | EVC |
|---|---|---|---|---|---|---|
| 1 | S 档，f/22，30″ | `DIALMODE_SHUTTERSPEED` | `0x60` | `0xFFB0` | `0` | `0x0A` |
| 2 | M 档，f/8.0，30″，ISO 100 | `DIALMODE_MANUAL` / `ISO_100` | `0x60` | `0xFFB0` | `0` | `0x0A` |
| 3 | 同 2，且取景流活跃（每帧 259200 字节） | 同上 | `0x60` | `0xFFB0` | `0` | `0x0A` |

另外把 `varlist` 在状态 1 与状态 2 各 dump 一次做 diff：
**61 个变量逐字节相同，零变化。**

### 结论

- ❌ 它**不是**用户设定值（模式从 S 变 M、光圈从 f/22 变 f/8，读数不变）
- ❌ 它**也不是**取景/测光算出来的值（取景跑起来后仍不变）
- ✅ 它是一组**不会被更新的寄存器**，很可能是未初始化默认值

### 反过来被证实的

同一批实验里，`getusr` **完全跟得上真实状态**：

| 屏幕变化 | `getusr` 表现 |
|---|---|
| S 档 → M 档 | `DIALMODE_SHUTTERSPEED` → `DIALMODE_MANUAL` ✅ |
| ISO 任意 | `ISO_100` 与屏幕一致 ✅ |

**所以可用的是 `getusr`（129 个参数），不可用的是 `getvar` 里的曝光量（AV/TV/SV/EVC）。**
这也是为什么 App 只显示 `getusr` 那七行。

### 光圈/快门该往哪找

相机能力表里有 PTP operation：

```
OperationSupport -> ... 0x1101 (Set Focus) 0x1102 (Set Aperture) ...
```

那是相机真正的控制协议，比用户态变量可靠。要找的是它的承载通道
（USB PTP，或 `st` 里是否有转发命令）。

### 一个顺带发现：`varlist` 的 "ID(Hex)" 列其实是**值**

`VARIABLE_WBCOLORTEMP` 的 `ID(Hex)` 是 `0x157C` = **5500** —— 显然不是标识符，
而是色温值。`VARIABLE_WBADJUST` 的 `0x00070007` 也是两个 16 位数据打包。
所以那一列可以当"当前值"读，只是**曝光那几个恰好一直不动**。

---

## 附 A：usrlist（129 个，NX500 1.12 实测）

``
     +-------------------------------+-------------------------------+----------+--------+
     + Data ID Name                  | Param ID Name                 + ID(Hex)  +ID(Dec) +
     +---------------------------------------------------------------+----------+--------+
[  0]| USERDATA_DIALMODE             | DIALMODE_SHUTTERSPEED         |0x00000004|       4|
[  1]| USERDATA_SHOOTINGMODE         | SHOOTINGMODE_SHUTTERSPEED     |0x00010003|   65539|
[  2]| USERDATA_IMAGESIZE            | IMAGESIZE_NORMAL_28M          |0x00020000|  131072|
[  3]| USERDATA_IMAGEASPECTRATIO     | IMAGEASPECTRATIO_IMAGEAR_3_2  |0x00030000|  196608|
[  4]| USERDATA_IMAGEQUALITY         | IMAGEQUALITY_RAW_SUPERFINE    |0x00040004|  262148|
[  5]| USERDATA_ISO                  | ISO_100                       |0x00050001|  327681|
[  6]| USERDATA_WB                   | WB_AUTO                       |0x00060000|  393216|
[  7]| USERDATA_AFMODE               | AFMODE_SINGLE                 |0x00070000|  458752|
[  8]| USERDATA_AFAREA               | AFAREA_FACEDETECTION          |0x00080003|  524291|
[  9]| USERDATA_FACEDETECT           | FACEDETECT_NORMAL             |0x00090001|  589825|
[ 10]| USERDATA_DRIVE                | DRIVE_SINGLE                  |0x000A0001|  655361|
[ 11]| USERDATA_BURSTRATE            | BURSTRATE_RATE10FPS           |0x000B0002|  720898|
[ 12]| USERDATA_FLASHMODE            | FLASHMODE_OFF                 |0x000C0000|  786432|
[ 13]| USERDATA_METERING             | METERING_CENTERWEIGHTED       |0x000D0001|  851969|
[ 14]| USERDATA_MOVIEMETERING        | MOVIEMETERING_MULTI           |0x000E0002|  917506|
[ 15]| USERDATA_LINKAE2AF            | LINKAE2AF_OFF                 |0x000F0000|  983040|
[ 16]| USERDATA_BRIGHTNESSADJUSTGUIDE| BRIGHTNESSADJUSTGUIDE_OFF     |0x00100000| 1048576|
[ 17]| USERDATA_FRAMEEXPOSURE        | FRAMEEXPOSURE_ON              |0x00110001| 1114113|
[ 18]| USERDATA_MULTIEXPOSURE        | MULTIEXPOSURE_OFF             |0x00120000| 1179648|
[ 19]| USERDATA_MULTIEXPOSURETYPE    | MULTIEXPOSURETYPE_SAVEALL     |0x00130000| 1245184|
[ 20]| USERDATA_PW                   | PW_STANDARD                   |0x00140000| 1310720|
[ 21]| USERDATA_SMARTRANGE           | SMARTRANGE_ON                 |0x00150001| 1376257|
[ 22]| USERDATA_MOVIESMARTRANGE      | MOVIESMARTRANGE_OFF           |0x00160000| 1441792|
[ 23]| USERDATA_AFPRIORITYRLS        | AFPRIORITYRLS_OFF_BALANCE     |0x00170001| 1507329|
[ 24]| USERDATA_OIS                  | OIS_OFF                       |0x00180000| 1572864|
[ 25]| USERDATA_FACETONE             | FACETONE_LEVEL1               |0x00190000| 1638400|
[ 26]| USERDATA_FACERETOUCH          | FACERETOUCH_LEVEL3            |0x001A0002| 1703938|
[ 27]| USERDATA_SMARTART             | SMARTART_OFF                  |0x001B0000| 1769472|
[ 28]| USERDATA_SMARTARTLEVEL        | SMARTARTLEVEL_LEVEL0          |0x001C0000| 1835008|
[ 29]| USERDATA_EVSTEP               | EVSTEP_ONETHIRD               |0x001D0000| 1900544|
[ 30]| USERDATA_ISONR                | ISONR_HIGH                    |0x001E0003| 1966083|
[ 31]| USERDATA_LTNR                 | LTNR_ON                       |0x001F0001| 2031617|
[ 32]| USERDATA_AEBRKORDER           | AEBRKORDER_PZM                |0x00200002| 2097154|
[ 33]| USERDATA_AEBRKAREA            | AEBRKAREA_1OVER3              |0x00210000| 2162688|
[ 34]| USERDATA_WBBRKSET             | WBBRKSET_AB3                  |0x00220000| 2228224|
[ 35]| USERDATA_PWBRKSTANDARD        | PWBRKSTANDARD_SET             |0x00230001| 2293761|
[ 36]| USERDATA_PWBRKVIVID           | PWBRKVIVID_SET                |0x00240001| 2359297|
[ 37]| USERDATA_PWBRKPORTRAIT        | PWBRKPORTRAIT_SET             |0x00250001| 2424833|
[ 38]| USERDATA_PWBRKLANDSCAPE       | PWBRKLANDSCAPE_SET            |0x00260001| 2490369|
[ 39]| USERDATA_PWBRKFOREST          | PWBRKFOREST_SET               |0x00270001| 2555905|
[ 40]| USERDATA_PWBRKRETRO           | PWBRKRETRO_SET                |0x00280001| 2621441|
[ 41]| USERDATA_PWBRKCOOL            | PWBRKCOOL_SET                 |0x00290001| 2686977|
[ 42]| USERDATA_PWBRKCALM            | PWBRKCALM_SET                 |0x002A0001| 2752513|
[ 43]| USERDATA_PWBRKCLASSIC         | PWBRKCLASSIC_SET              |0x002B0001| 2818049|
[ 44]| USERDATA_PWBRKCUSTOM1         | PWBRKCUSTOM1_SET              |0x002C0001| 2883585|
[ 45]| USERDATA_PWBRKCUSTOM2         | PWBRKCUSTOM2_SET              |0x002D0001| 2949121|
[ 46]| USERDATA_PWBRKCUSTOM3         | PWBRKCUSTOM3_SET              |0x002E0001| 3014657|
[ 47]| USERDATA_PWBRKCUSTOM4         | PWBRKCUSTOM4_SET              |0x002F0001| 3080193|
[ 48]| USERDATA_AFLIGHT              | AFLIGHT_HIGH                  |0x00300001| 3145729|
[ 49]| USERDATA_AEL                  | AEL_AEL                       |0x00310000| 3211264|
[ 50]| USERDATA_COLORSPACE           | COLORSPACE_SRGB               |0x00320000| 3276800|
[ 51]| USERDATA_QUICKVIEWTIME        | QUICKVIEWTIME_1SEC            |0x00330001| 3342337|
[ 52]| USERDATA_MONITOROUT           | MONITOROUT_LCD                |0x00340000| 3407872|
[ 53]| USERDATA_HDMIOUT              | HDMIOUT_1080I                 |0x00350003| 3473411|
[ 54]| USERDATA_MOVIESIZE            | MOVIESIZE_FHD                 |0x00360000| 3538944|
[ 55]| USERDATA_MOVIEFRAMERATE       | MOVIEFRAMERATE_FPS30          |0x00370004| 3604484|
[ 56]| USERDATA_MOVIEFADER           | MOVIEFADER_OFF                |0x00380000| 3670016|
[ 57]| USERDATA_MOVIEAE              | MOVIEAE_P                     |0x00390000| 3735552|
[ 58]| USERDATA_3DMOVIEFRAMERATE     | F3DMOVIEFRAMERATE_FPS30       |0x003A0001| 3801089|
[ 59]| USERDATA_LDC                  | LDC_ON                        |0x003B0001| 3866625|
[ 60]| USERDATA_ISOSTEP              | ISOSTEP_ONE                   |0x003C0001| 3932161|
[ 61]| USERDATA_ISOEXPANSION         | ISOEXPANSION_ON               |0x003D0001| 3997697|
[ 62]| USERDATA_SMARTFILTERTYPE      | SMARTFILTERTYPE_OFF           |0x003E0000| 4063232|
[ 63]| USERDATA_SMARTFILTERSIZE      | SMARTFILTERSIZE_SIZE0         |0x003F0000| 4128768|
[ 64]| USERDATA_ISOAUTOMAX           | ISOAUTOMAX_ISO6400            |0x00400011| 4194321|
[ 65]| USERDATA_MFASSIST             | MFASSIST_ENLARGEX8            |0x00410002| 4259842|
[ 66]| USERDATA_FOCUSPEAKING         | FOCUSPEAKING_RED              |0x00420002| 4325378|
[ 67]| USERDATA_HDR                  | HDR_OFF                       |0x00430000| 4390912|
[ 68]| USERDATA_FDMODE               | FDMODE_OFF                    |0x00440000| 4456448|
[ 69]| USERDATA_IZOOM                | IZOOM_X1                      |0x00450000| 4521984|
[ 70]| USERDATA_3DSHOT               | F3DSHOT_OFF                   |0x00460000| 4587520|
[ 71]| USERDATA_3DAUTOMODE           | F3DAUTOMODE_ON                |0x00470001| 4653057|
[ 72]| USERDATA_DMF                  | DMF_OFF                       |0x00480000| 4718592|
[ 73]| USERDATA_FASTCONTINUOUS       | FASTCONTINUOUS_OFF            |0x00490000| 4784128|
[ 74]| USERDATA_SMARTMOVIE           | SMARTMOVIE_OFF                |0x004A0000| 4849664|
[ 75]| USERDATA_LENSEFFECT           | LENSEFFECT_OFF                |0x004B0000| 4915200|
[ 76]| USERDATA_HDRLEVEL             | HDRLEVEL_LV2                  |0x004C0001| 4980737|
[ 77]| USERDATA_HDRARTLEVEL          | HDRARTLEVEL_LV1               |0x004D0000| 5046272|
[ 78]| USERDATA_LLSLEVEL             | LLSLEVEL_LV1                  |0x004E0000| 5111808|
[ 79]| USERDATA_OBJECTTRACKING       | OBJECTTRACKING_OFF            |0x004F0000| 5177344|
[ 80]| USERDATA_PANORAMATYPE         | PANORAMATYPE_2D               |0x00500000| 5242880|
[ 81]| USERDATA_SMARTPROMODE         | SMARTPROMODE_RICHTONE         |0x00510002| 5308418|
[ 82]| USERDATA_SMARTPROLEVEL        | SMARTPROLEVEL_M2              |0x00520000| 5373952|
[ 83]| USERDATA_EFS                  | EFS_OFF                       |0x00530000| 5439488|
[ 84]| USERDATA_WIFIMODE             | WIFIMODE_OFF                  |0x00540000| 5505024|
[ 85]| USERDATA_VIDEOOUT             | VIDEOOUT_NTSC                 |0x00550000| 5570560|
[ 86]| USERDATA_SMARTAUTORUNSTATE    | SMARTAUTORUNSTATE_OFF         |0x00560000| 5636096|
[ 87]| USERDATA_3DFORMAT             | F3DFORMAT_SIDE_BY_SIDE        |0x00570001| 5701633|
[ 88]| USERDATA_FOCUSPEAKINGLEVEL    | FOCUSPEAKINGLEVEL_HIGH        |0x00580003| 5767171|
[ 89]| USERDATA_LENSMOUNT            | LENSMOUNT_NX_MOUNT            |0x00590000| 5832704|
[ 90]| USERDATA_OVEREXPGUIDE         | OVEREXPGUIDE_ON               |0x005A0001| 5898241|
[ 91]| USERDATA_SHUTTERMIN           | SHUTTERMIN_OFF                |0x005B0000| 5963776|
[ 92]| USERDATA_DEPTHBRKAREA         | DEPTHBRKAREA_1                |0x005C0003| 6029315|
[ 93]| USERDATA_INTERVAL             | INTERVAL_OFF                  |0x005D0000| 6094848|
[ 94]| USERDATA_TIMELAPSE            | TIMELAPSE_OFF                 |0x005E0000| 6160384|
[ 95]| USERDATA_VIDEOSTABILIZER      | VIDEOSTABILIZER_OFF           |0x005F0000| 6225920|
[ 96]| USERDATA_PANORAMATHUMBNAIL    | PANORAMATHUMBNAIL_OFF         |0x00600000| 6291456|
[ 97]| USERDATA_EXTERNALFLASHMODE    | EXTERNALFLASHMODE_ATTL        |0x00610000| 6356992|
[ 98]| USERDATA_WFLASHMODE           | WFLASHMODE_OFF                |0x00620000| 6422528|
[ 99]| USERDATA_WFLASHCH             | WFLASHCH_1                    |0x00630000| 6488064|
[100]| USERDATA_WFLASHMASTERMODE     | WFLASHMASTERMODE_OFF          |0x00640000| 6553600|
[101]| USERDATA_WFLASHGROUPAMODE     | WFLASHGROUPAMODE_OFF          |0x00650000| 6619136|
[102]| USERDATA_WFLASHGROUPBMODE     | WFLASHGROUPBMODE_OFF          |0x00660000| 6684672|
[103]| USERDATA_WFLASHGROUPCMODE     | WFLASHGROUPCMODE_OFF          |0x00670000| 6750208|
[104]| USERDATA_HDMI3DFORMAT         | HDMI3DFORMAT_SIDE_BY_SIDE     |0x00680001| 6815745|
[105]| USERDATA_LENSZOOMSPEED        | LENSZOOMSPEED_LOW             |0x00690000| 6881280|
[106]| USERDATA_LENSMFSENSITIVITY    | LENSMFSENSITIVITY_NORMAL      |0x006A0000| 6946816|
[107]| USERDATA_SEPARATEAEAREA       | SEPARATEAEAREA_OFF            |0x006B0000| 7012352|
[108]| USERDATA_OLEDCOLOR            | OLEDCOLOR_OFF                 |0x006C0000| 7077888|
[109]| USERDATA_BABYMONITOR          | BABYMONITOR_OFF               |0x006D0000| 7143424|
[110]| USERDATA_MULTIMOTION          | MULTIMOTION_NORMAL            |0x006E0002| 7208962|
[111]| USERDATA_SENSORTESTMODE       | SENSORTESTMODE_OFF            |0x006F0000| 7274496|
[112]| USERDATA_AUTOSHUTTERMODE      | AUTOSHUTTERMODE_BASEBALL      |0x00700000| 7340032|
[113]| USERDATA_RVFDPSIZE            | RVFDPSIZE_FHD                 |0x00710000| 7405568|
[114]| USERDATA_RVFDPFRAMRATE        | RVFDPFRAMRATE_FPS30           |0x00720004| 7471108|
[115]| USERDATA_SYSTEMFREQENCYSTATE  | SYSTEMFREQENCYSTATE_LIVEVIEW  |0x00730000| 7536640|
[116]| USERDATA_HDMI3D               | HDMI3D_OFF                    |0x00740000| 7602176|
[117]| USERDATA_ADJUSTSHUTTERTYPE    | ADJUSTSHUTTERTYPE_MECHA       |0x00750000| 7667712|
[118]| USERDATA_MOVIE_GAMMA_CONTROL  | MOVIE_GAMMA_CONTROL_STANDARD  |0x00760000| 7733248|
[119]| USERDATA_MOVIE_LUMINANCE_LEVEL| MOVIE_LUMINANCE_LEVEL_0_255   |0x00770000| 7798784|
[120]| USERDATA_MOVIE_AF_MODE        | MOVIE_AF_MODE_MANUAL          |0x00780002| 7864322|
[121]| USERDATA_GOLFREVERSE          | GOLFREVERSE_ORIGINAL          |0x00790000| 7929856|
[122]| USERDATA_RAWQUALITY           | RAWQUALITY_LOSSLESS           |0x007A0000| 7995392|
[123]| USERDATA_RAW_SAVE             | RAW_SAVE_OFF                  |0x007B0000| 8060928|
[124]| USERDATA_SSIF_RAW             | SSIF_RAW_OFF                  |0x007C0000| 8126464|
[125]| USERDATA_RAW_BIT              | RAW_BIT_14BIT                 |0x007D0000| 8192000|
[126]| USERDATA_RAW_PACK             | RAW_PACK_PACK                 |0x007E0000| 8257536|
[127]| USERDATA_FLIPSTATE            | FLIPSTATE_UP                  |0x007F0001| 8323073|
[128]| USERDATA_SAVEAS_FLIPPED       | SAVEAS_FLIPPED_OFF            |0x00800000| 8388608|
     +-------------------------------+-------------------------------+----------+--------+
``

## 附 B：varlist（66 个，NX500 1.12 实测）

``
     +-----------------------------------+-------+----------+----------+
     + Variable Data ID Name             + Length+ ID(Hex)  +ID(Dec)   +
     +-----------------------------------+-------+----------+----------+
[  0]| VARIABLE_AV                       |      4|0x00000060|        96|
[  1]| VARIABLE_TV                       |      4|0x0000FFB0|     65456|
[  2]| VARIABLE_SV                       |      4|0x00000000|         0|
[  3]| VARIABLE_ACTUAL_SV                |      4|0x00000000|         0|
[  4]| VARIABLE_EVC                      |      4|0x0000000A|        10|
[  5]| VARIABLE_WBCOLORTEMP              |      4|0x0000157C|      5500|
[  6]| VARIABLE_WBADJUST                 |      4|0x00070007|    458759|
[  7]| VARIABLE_FDDRAWRUNSTATE           |      4|0x00000001|         1|
[  8]| VARIABLE_FDDISPSTOPSTATE          |      4|0x00000000|         0|
[  9]| VARIABLE_BULBTIME                 |      4|0x00000000|         0|
[ 10]| VARIABLE_S2PUSHED                 |      4|0x00000000|         0|
[ 11]| VARIABLE_FTMFRUNNING              |      4|0x00000000|         0|
[ 12]| VARIABLE_OVEREXPOSURERUNSTATE     |      4|0x00000001|         1|
[ 13]| VARIABLE_FLASHEV                  |      4|0x0000000A|        10|
[ 14]| VARIABLE_PWCOLOR                  |      4|----------|----------|
[ 15]| VARIABLE_PWSATURATION             |      4|0x003FD80A|   4184074|
[ 16]| VARIABLE_PWSHARPNESS              |      4|0x000FD80A|   1038346|
[ 17]| VARIABLE_PWCONTRAST               |      4|0x002FD80A|   3135498|
[ 18]| VARIABLE_WAITLENSFINISH           |      1|0x00000000|         0|
[ 19]| VARIABLE_FOCUSLIMIT               |      4|0x00000000|         0|
[ 20]| VARIABLE_LENSINIT                 |      4|0x00000000|         0|
[ 21]| VARIABLE_SENSORFRAMERATE          |      4|0x0000003C|        60|
[ 22]| VARIABLE_OBJTRACKRUNSTATE         |      4|0x00000000|         0|
[ 23]| VARIABLE_OBJTRACKDISPSTATEONCONTI |      4|0x00000000|         0|
[ 24]| VARIABLE_TOUCH_POSX               |      4|0x00000000|         0|
[ 25]| VARIABLE_TOUCH_POSY               |      4|0x00000000|         0|
[ 26]| VARIABLE_IDEPTH_AV                |      4|0x00000000|         0|
[ 27]| VARIABLE_FACEINFO                 |    168|----------|----------|
[ 28]| VARIABLE_BESTFACEINFO             |     76|----------|----------|
[ 29]| VARIABLE_MULTIEXPOSUREMAXCOUNT    |      4|0x00000002|         2|
[ 30]| VARIABLE_MULTIEXPOSURECURCOUNT    |      4|0x00000000|         0|
[ 31]| VARIABLE_TV_MIN                   |      4|0x00000000|         0|
[ 32]| VARIABLE_EXTFLASHOUTPUT           |      4|0x00000000|         0|
[ 33]| VARIABLE_MULTIFLASHCOUNT          |      4|0x00000000|         0|
[ 34]| VARIABLE_MULTIFLASHOUTPUT         |      4|0x00000000|         0|
[ 35]| VARIABLE_MULTIFLASHFREQ           |      4|0x00000000|         0|
[ 36]| VARIABLE_WFLASHMASTEREV           |      4|0x00000000|         0|
[ 37]| VARIABLE_WFLASHMASTEROUTPUT       |      4|0x00000000|         0|
[ 38]| VARIABLE_WFLASHGROUPAEV           |      4|0x00000000|         0|
[ 39]| VARIABLE_WFLASHGROUPAOUTPUT       |      4|0x00000000|         0|
[ 40]| VARIABLE_WFLASHGROUPBEV           |      4|0x00000000|         0|
[ 41]| VARIABLE_WFLASHGROUPBOUTPUT       |      4|0x00000000|         0|
[ 42]| VARIABLE_WFLASHGROUPCEV           |      4|0x00000000|         0|
[ 43]| VARIABLE_WFLASHGROUPCOUTPUT       |      4|0x00000000|         0|
[ 44]| VARIABLE_CONTIRATE                |      4|0x00000000|         0|
[ 45]| VARIABLE_IGNORE_CAPTURE_CMD       |      4|0x00000000|         0|
[ 46]| VARIABLE_EXPOSURE_TIME            |      4|0x00000000|         0|
[ 47]| VARIABLE_FELSTATE                 |      4|0x00000000|         0|
[ 48]| VARIABLE_PWCOLOR_R                |      4|0x087000FF| 141558015|
[ 49]| VARIABLE_PWCOLOR_G                |      4|0x07B000FF| 128975103|
[ 50]| VARIABLE_PWCOLOR_B                |      4|0x088000FF| 142606591|
[ 51]| VARIABLE_PWHUE                    |      4|0x004FD80A|   5232650|
[ 52]| VARIABLE_PROSUGGEST_SAMPLE_CAPTURE|      4|0x00000000|         0|
[ 53]| VARIABLE_RVFRUNNING               |      4|0x00000000|         0|
[ 54]| VARIABLE_LIVEVIEWSTARTRATE        |      4|0x00000000|         0|
[ 55]| VARIABLE_TIMESTAMP                |      4|0x00000000|         0|
[ 56]| VARIABLE_FDLIBRUNSTATE            |      4|0x00000001|         1|
[ 57]| VARIABLE_SHUTTERKEYSTATE          |      4|0x00000000|         0|
[ 58]| VARIABLE_AFONKEYSTATE             |      4|0x00000000|         0|
[ 59]| VARIABLE_CONTINORMALLIVEVIEW      |      4|0x00000000|         0|
[ 60]| VARIABLE_MOVIE_MASTER_BLACK_LEVEL |      4|0x00000000|         0|
[ 61]| VARIABLE_MOVIE_AF_RESPONSIVENESS  |      4|0x00000000|         0|
[ 62]| VARIABLE_MOVIE_AF_SHIFT_SPEED     |      4|0x00000003|         3|
[ 63]| VARIABLE_MOVIE_FADER_RUNSTATE     |      4|0x00000000|         0|
     +-----------------------------------------------------------------+
     + Total Count :    64               | Total Size :   489          +
     +-----------------------------------------------------------------+
``
