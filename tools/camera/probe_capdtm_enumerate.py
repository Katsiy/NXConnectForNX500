#!/usr/bin/env python3
"""穷举相机端 capdtm 真正支持的用户态参数名。

## 为什么需要它

我们之前探参数是**猜名字**：试了 17 个 `ADJUST*`（ADJUSTEXPOSURE / ADJUSTISO /
ADJUSTWHITEBALANCE …），只有 `ADJUSTSHUTTERTYPE` 一个能读。
后来又试了 Metol 用的 16 个 `USERDATA_*`，**全部 Invalid argument**。

猜名字这条路走到头了。但更可靠的思路是：**把相机库里所有形如参数名的字符串
dump 出来，逐个试**。如果 capdtm 的实现里存在名字表，这样就能得到完整清单；
如果 dump 出来的候选也都失败，那"只有一个参数可读"就是确定结论，而不是猜测。

## 做什么

1. 找哪个文件实现了 capdtm（在 /usr/lib、/usr/bin、/opt 下按文件名与内容筛）
2. 从这些文件里 dump 出 `ADJUST*` / `USERDATA*` / `*MODE*` 形态的候选名
3. 逐个 `st cap capdtm getusr <候选>`，记录成功/失败
4. 打印成功清单 —— 那就是这台固件真正支持的参数

## 用法

    python tools/camera/probe_capdtm_enumerate.py 192.168.5.39

耗时较长（单核相机上要跑几十次 st），默认只测去重后的候选名，最多 120 个。
"""
from __future__ import annotations

import re
import socket
import sys
import time

IAC = 255
PROMPT = re.compile(r"(\[[^\]]*\]\s*)?#\s*$", re.M)
USERDATA_RE = re.compile(r"UserData\s+is\s+(\S+)")

# 候选名形态：全大写、下划线分隔，5~40 字符
CANDIDATE_RE = re.compile(r"^[A-Z][A-Z0-9_]{4,39}$")
# 只保留看起来像参数名的（避免 INFO / ERROR 这类噪音）
INTERESTING = re.compile(r"^(ADJUST|USERDATA|SETUSER|GETUSER)")

MAX_CANDIDATES = 120


class Shell:
    def __init__(self, ip: str, timeout: float = 10.0):
        self.sock = socket.create_connection((ip, 23), timeout=timeout)
        self.sock.settimeout(0.6)
        self.buf = ""

    def _pump(self) -> bool:
        try:
            data = self.sock.recv(16384)
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

    def run(self, cmd: str, ms: int = 60000) -> str:
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
        lines = out.replace("\r", "").split("\n")
        if lines and cmd[:24] in lines[0]:
            lines = lines[1:]
        return PROMPT.sub("", "\n".join(lines)).strip()

    def close(self) -> None:
        try:
            self.sock.close()
        except OSError:
            pass


def main() -> int:
    ip = sys.argv[1] if len(sys.argv) > 1 else "192.168.5.39"
    print(f"=== 目标相机: {ip} ===")

    try:
        shell = Shell(ip)
    except OSError as exc:
        print(f"  ❌ 连不上 {ip}:23（{exc.__class__.__name__}）")
        return 2

    try:
        try:
            shell.login()
        except (RuntimeError, OSError) as exc:
            print(f"  ❌ 登录失败：{exc}")
            return 2

        # ---- 0) 先看 st 自己怎么说 ----
        print("\n--- st cap capdtm 的参数提示 ---")
        print("  " + (shell.run("st cap capdtm getusr") or "(无输出)")[:300].replace("\n", "\n  "))

        # ---- 1) 找出实现 capdtm 的文件 ----
        print("\n--- 查找实现 capdtm 的文件（可能要几十秒）---")
        # 先用文件名匹配，再按内容匹配（内容匹配慢，限定目录与大小）
        find_cmd = (
            "for d in /usr/lib /usr/bin /usr/sbin /opt/usr; do "
            "  find $d -maxdepth 3 -type f -size -8M 2>/dev/null; "
            "done | head -n 400"
        )
        files = [f.strip() for f in shell.run(find_cmd, 120000).split("\n") if f.strip().startswith("/")]
        print(f"  枚举到 {len(files)} 个文件")

        # 按内容筛：含 capdtm 或 getusr 的文件
        hits = []
        # 分批，避免单条命令过长
        for i in range(0, len(files), 40):
            batch = files[i:i + 40]
            joined = " ".join(batch)
            out = shell.run(
                f"for f in {joined}; do "
                f"  if grep -l -a -m1 -E 'capdtm|getusr' \"$f\" 2>/dev/null; then :; fi; "
                f"done",
                180000,
            )
            hits += [x.strip() for x in out.split("\n") if x.strip().startswith("/")]
            if len(hits) >= 6:
                break
        hits = list(dict.fromkeys(hits))[:6]
        print(f"  含 capdtm/getusr 的文件: {hits if hits else '(未找到)'}")

        # ---- 2) dump 候选参数名 ----
        cands: list[str] = []
        if hits:
            joined = " ".join(hits)
            out = shell.run(
                f"for f in {joined}; do "
                f"  tr -c '[:print:]' '\\n' < \"$f\" 2>/dev/null; "
                f"done | sort -u | head -n 20000",
                300000,
            )
            for tok in out.split("\n"):
                tok = tok.strip()
                if CANDIDATE_RE.match(tok) and INTERESTING.match(tok):
                    cands.append(tok)
        cands = list(dict.fromkeys(cands))
        print(f"  候选参数名 {len(cands)} 个")
        for c in cands[:25]:
            print(f"    {c}")
        if len(cands) > 25:
            print(f"    …（共 {len(cands)} 个）")

        if not cands:
            print("\n  ⚠️ 没有 dump 到候选名（库里可能没有名字表）。")
            print("  → 退回到已知有效名 + Metol 的表，结论同 probe_capdtm_userdata.py。")
            return 0

        # ---- 3) 逐个测试 ----
        print(f"\n=== 逐个 getusr 测试（最多 {MAX_CANDIDATES} 个）===")
        ok: list[tuple[str, str]] = []
        for idx, name in enumerate(cands[:MAX_CANDIDATES], 1):
            raw = shell.run(f"st cap capdtm getusr {name}", 30000)
            first = (raw.split("\n")[0] if raw else "").strip()
            m = USERDATA_RE.search(first)
            if m:
                ok.append((name, first))
                print(f"  ✅ [{idx}/{min(len(cands), MAX_CANDIDATES)}] {name} -> {first[:70]}")
            elif "Invalid argument" not in first:
                # 非"非法参数"的其它输出也值得记录
                print(f"  ❔ [{idx}] {name} -> {first[:70]}")

        print("\n" + "=" * 66)
        print(f"=== 结果：{len(ok)} 个参数可读")
        print("=" * 66)
        for name, first in ok:
            print(f"  {name:<40} {first[:80]}")
        if not ok:
            print("  （无）—— 说明 dump 出的候选名都不是有效参数名")
        print("\n>>> 请把本段输出整段附到 issue 里。")
        return 0
    finally:
        shell.close()


if __name__ == "__main__":
    sys.exit(main())
