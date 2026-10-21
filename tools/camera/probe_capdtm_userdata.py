#!/usr/bin/env python3
"""验证 USERDATA_* 这一组 capdtm 参数名到底能不能读/写。

## 背景

`docs/protocol.md` 里记着一条实测结果：capdtm-api 返回

    {"ok":true,"params":{"USERDATA_ISO":"","USERDATA_WB":"", ... 全空 ...}}

我们据此长期认为「capdtm 只能读到 1 个参数（ADJUSTSHUTTERTYPE），
没法读曝光参数，只能靠 OSD 镜像」。

**但那个结论可能站不住**：Metol（旧称 NX-WA）里的 capdtm CGI 是这样解析的：

    cur=$(st cap capdtm getusr "$cname" | head -1)
    val=$(printf '%s' "$cur" | awk -F'|' '{print $3}')

即它假设 `getusr` 输出**竖线分隔、值在第 3 列**。而相机实际输出是：

    UserData is ADJUSTSHUTTERTYPE_MECHA (0x750000)

没有竖线 —— `awk -F'|' '{print $3}'` 自然取到空字符串。
**所以"全空"很可能只是那个脚本的解析 bug。**

## 本脚本做什么

对 Metol 列出的 10 个 `USERDATA_*` 参数名，逐个执行 `st cap capdtm getusr`，
**打印原始输出**（不做任何假设性解析），并尝试解析出 `UserData is <值> (0x<hex>)`。
另外对 1~2 个参数尝试 `setusr` 并用 `getusr` 回读验证是否真的生效。

## 用法

    python tools/camera/probe_capdtm_userdata.py 192.168.5.39

需要相机端 telnet（23）可用，root 免密。
"""
from __future__ import annotations

import re
import socket
import sys
import time

# Metol / NX-WA 的 capdtm.conf 里列出的参数名（见 scripts/nx-rc/capdtm/capdtm.conf）
USERDATA_NAMES = [
    "USERDATA_ISO",
    "USERDATA_METERING",
    "USERDATA_WB",
    "USERDATA_IMAGEQUALITY",
    "USERDATA_IMAGESIZE",
    "USERDATA_IMAGEASPECTRATIO",
    "USERDATA_PW",
    "USERDATA_COLORSPACE",
    "USERDATA_AFMODE",
    "USERDATA_DRIVE",
]

# 额外试探：曝光相关（Metol 没列，但我们最关心快门/光圈/补偿）
EXTRA_NAMES = [
    "USERDATA_SHUTTERSPEED",
    "USERDATA_APERTURE",
    "USERDATA_EV",
    "USERDATA_EXPOSURE",
    "USERDATA_EXPOSURECOMPENSATION",
    "USERDATA_ADJUSTSHUTTERTYPE",   # 已知可读的那个，用作对照
]

IAC = 255
PROMPT = re.compile(r"(\[[^\]]*\]\s*)?#\s*$", re.M)
# 相机真实输出形如：UserData is ADJUSTSHUTTERTYPE_MECHA (0x750000)
USERDATA_RE = re.compile(r"UserData\s+is\s+(\S+)(?:\s+\((0x[0-9A-Fa-f]+)\))?")


class Shell:
    """最小 telnet 客户端：过滤 IAC 协商，用行尾正则匹配提示符。"""

    def __init__(self, ip: str, timeout: float = 8.0):
        self.sock = socket.create_connection((ip, 23), timeout=timeout)
        self.sock.settimeout(0.6)
        self.buf = ""

    def _pump(self) -> bool:
        try:
            data = self.sock.recv(8192)
        except socket.timeout:
            return True
        except OSError:
            return False
        if not data:
            return False
        i, n = 0, len(data)
        while i < n:
            b = data[i]
            if b == IAC:
                if i + 1 >= n:
                    break
                i += 3 if 0xFB <= data[i + 1] <= 0xFE else 2
                continue
            self.buf += chr(b)
            i += 1
        return True

    def wait_for(self, text: str, ms: int) -> bool:
        end = time.time() + ms / 1000.0
        while time.time() < end:
            if text in self.buf:
                return True
            if not self._pump():
                return False
        return text in self.buf

    def login(self) -> None:
        if not self.wait_for("login:", 8000):
            raise RuntimeError("没看到 login 提示")
        self.buf = ""
        self.sock.sendall(b"root\n")
        if not self.wait_for("#", 8000):
            raise RuntimeError("没能进入 shell")
        self.buf = ""

    def run(self, cmd: str, ms: int = 12000) -> str:
        """执行命令并返回输出（去掉回显行与提示符）。"""
        self.buf = ""
        self.sock.sendall((cmd + "\n").encode())
        end = time.time() + ms / 1000.0
        while time.time() < end:
            if PROMPT.search(self.buf):
                break
            if not self._pump():
                break
        out = self.buf
        self.buf = ""
        # 去掉第一行回显与末尾提示符
        lines = out.replace("\r", "").split("\n")
        if lines and cmd[:20] in lines[0]:
            lines = lines[1:]
        text = "\n".join(lines)
        text = PROMPT.sub("", text)
        return text.strip()

    def close(self) -> None:
        try:
            self.sock.close()
        except OSError:
            pass


def parse_userdata(text: str) -> tuple[str | None, str | None]:
    """从 `UserData is <名> (0x<hex>)` 里取值与 hex。"""
    m = USERDATA_RE.search(text)
    if not m:
        return None, None
    return m.group(1), (m.group(2) or None)


def probe(shell: Shell, names: list[str], label: str) -> dict[str, tuple[str | None, str | None]]:
    print(f"\n{'=' * 66}")
    print(f"=== {label}（{len(names)} 个参数）")
    print("=" * 66)
    found: dict[str, tuple[str | None, str | None]] = {}
    for name in names:
        raw = shell.run(f"st cap capdtm getusr {name}")
        val, hexv = parse_userdata(raw)
        # 只打印第一行，避免刷屏
        first = raw.split("\n")[0].strip() if raw else "(无输出)"
        mark = "✅" if val else ("⚠️" if raw else "❌")
        print(f"  {mark} {name:<34} {first[:80]}")
        if val:
            found[name] = (val, hexv)
    return found


def main() -> int:
    ip = sys.argv[1] if len(sys.argv) > 1 else "192.168.5.39"
    print(f"=== 目标相机: {ip} ===")

    try:
        shell = Shell(ip)
    except OSError as exc:
        print(f"  ❌ 连不上 {ip}:23（{exc.__class__.__name__}）。")
        print("     请确认相机开机、手机/电脑与相机在同一网络、相机端 telnet 已启用。")
        return 2
    try:
        try:
            shell.login()
        except (RuntimeError, OSError) as exc:
            print(f"  ❌ 登录失败：{exc}")
            print("     相机端 telnet 可能未启用（NX-KS 里在相机菜单开关）。")
            return 2
        print("  已登录 shell")

        print("\n--- 先确认 st 与 capdtm 子命令存在 ---")
        print("  which st        :", shell.run("which st") or "(空)")
        print("  capdtm 帮助     :", (shell.run("st cap capdtm") or "(空)").split("\n")[0][:100])

        main_found = probe(shell, USERDATA_NAMES, "Metol 列出的 USERDATA_* 参数")
        extra_found = probe(shell, EXTRA_NAMES, "额外试探：曝光相关")

        allfound = {**main_found, **extra_found}
        print(f"\n{'=' * 66}")
        print(f"=== 汇总：{len(allfound)} / {len(USERDATA_NAMES) + len(EXTRA_NAMES)} 个参数读到了值")
        print("=" * 66)
        for name, (val, hexv) in allfound.items():
            print(f"  {name:<34} {val}  {hexv or ''}")

        # ---- 写测试：挑一个读到的参数，改成另一个合法值再回读 ----
        print(f"\n{'=' * 66}")
        print("=== 写入测试（用第一个读到的参数）")
        print("=" * 66)
        candidate = None
        for name in ("USERDATA_ISO", "USERDATA_METERING", "USERDATA_WB"):
            if name in allfound:
                candidate = name
                break

        if not candidate:
            print("  ⏭ 跳过：没有读到任何可写参数")
        else:
            cur, _ = allfound[candidate]
            # 选一个与当前值不同的候选值
            alt = None
            if candidate == "USERDATA_ISO":
                alt = "ISO_400" if cur != "ISO_400" else "ISO_100"
            elif candidate == "USERDATA_METERING":
                alt = "METERING_SPOT" if cur != "METERING_SPOT" else "METERING_MULTI"
            elif candidate == "USERDATA_WB":
                alt = "WB_DAYLIGHT" if cur != "WB_DAYLIGHT" else "WB_AUTO"

            print(f"  参数 {candidate} 当前 = {cur}，尝试写入 {alt}")
            print("  写入原始输出:", (shell.run(f"st cap capdtm setusr {candidate} {alt}") or "(无输出)")[:120])
            time.sleep(0.5)
            back = shell.run(f"st cap capdtm getusr {candidate}")
            val2, _ = parse_userdata(back)
            print(f"  回读 = {val2}  原始: {back.split(chr(10))[0][:80]}")
            if val2 and val2 != cur:
                print("  ✅ 写入生效（值已变化）")
                # 还原
                shell.run(f"st cap capdtm setusr {candidate} {cur}")
                print(f"  已还原为 {cur}")
            elif val2 == cur:
                print("  ⚠️ 回读值未变 —— 可能写入无效，或该参数在拍摄界面被相机锁定")
            else:
                print("  ❓ 回读解析失败，看原始输出判断")

        print("\n=== 结论提示 ===")
        if allfound:
            print("  ✅ USERDATA_* 能读到值 —— 『capdtm 只能读 1 个参数』的旧结论应被推翻。")
            print("     下一步：把这些参数接进 App，就不必靠 OSD 镜像才知道当前 ISO / 白平衡了。")
        else:
            print("  ❌ USERDATA_* 全部读不到 —— 说明该命名空间在这台固件上无效，")
            print("     旧结论（只能读 ADJUSTSHUTTERTYPE）成立。请把本脚本输出附到 issue 里。")
        return 0
    finally:
        shell.close()


if __name__ == "__main__":
    sys.exit(main())
