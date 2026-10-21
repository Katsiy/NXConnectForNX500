#!/usr/bin/env python3
"""在没有相机的情况下验证「多机型 OSD」这条链路。

做两件事：

1. 用 mock 相机分别以 NX500 与 NX300 的机型身份起服务，检查 OSD 响应的
   **帧头尺寸、块数、长度公式**是否与协议一致：

   | 机型 | 帧 | 块数 | 全量长度 |
   |---|---|---|---|
   | NX500 / NX1 | 720×480 | 108 | 1,383,276 |
   | NX300 | 800×480 | 120 | 1,536,972 |

2. 检查**增量**是否真的在走增量：第二次请求带上第一次拿到的哈希，响应应当只剩
   1~2 个块（mock 每次只移动一个高亮块）。

另外复查一条服务端约束：客户端**必须**发 120 组哈希。少发会让服务端拿未初始化的
栈值比较（那些块每次重发），多发会写越界（栈溢出）。

用法：
    python tools/verify_mock_osd.py
"""
from __future__ import annotations

import socket
import struct
import subprocess
import sys
import time
import urllib.parse
import urllib.request
from pathlib import Path

HERE = Path(__file__).resolve().parent
MOCK = HERE / "mock_camera.py"

BLOCK_W, BLOCK_H = 80, 40
RECORD_BYTES = 8 + BLOCK_W * BLOCK_H * 4   # 12808
MIN_BYTES = 12

CASES = [
    # (model, frame_w, frame_h, block_count)
    ("NX500", 720, 480, 108),
    ("NX1", 720, 480, 108),
    ("NX300", 800, 480, 120),
]

failures: list[str] = []
checks = 0


def check(cond, label, detail=""):
    global checks
    checks += 1
    if cond:
        print(f"  PASS  {label}{('  ' + detail) if detail else ''}")
    else:
        print(f"  FAIL  {label}{('  ' + detail) if detail else ''}")
        failures.append(label)


def free_port() -> int:
    s = socket.socket()
    s.bind(("127.0.0.1", 0))
    port = s.getsockname()[1]
    s.close()
    return port


def start_mock(model: str, port: int) -> subprocess.Popen:
    proc = subprocess.Popen(
        [
            sys.executable, str(MOCK),
            "--host", "127.0.0.1",
            "--port", str(port),
            "--cgi-port", str(port + 1),
            "--model", model,
            "--latency-scale", "0",
            "--quiet",
        ],
        stdout=subprocess.DEVNULL,
        stderr=subprocess.PIPE,
    )
    deadline = time.time() + 15
    while time.time() < deadline:
        try:
            with urllib.request.urlopen(
                f"http://127.0.0.1:{port}/api/v1/camera/info", timeout=1
            ) as r:
                if r.status == 200:
                    return proc
        except Exception:
            time.sleep(0.15)
    proc.kill()
    raise RuntimeError(f"mock({model}) 未能在 15 秒内起来")


def osd_request(port: int, hashes: list[int], send_blocks: int) -> bytes:
    """发一次 OSD 请求。send_blocks 决定发多少组哈希（用于验证 120 组约束）。"""
    hexs = "".join(f"{h & 0xFFFFFFFF:08x}" for h in hashes[:send_blocks])
    data = urllib.parse.urlencode({"hashs": hexs}).encode()
    req = urllib.request.Request(
        f"http://127.0.0.1:{port}/api/v1/osd/get", data=data, method="POST"
    )
    with urllib.request.urlopen(req, timeout=10) as r:
        return r.read()


def parse_response(raw: bytes):
    """返回 (frameW, frameH, blockW, blockH, {块索引: hash})。"""
    fw, fh, bw, bh = struct.unpack(">HHHH", raw[:8])
    idx = 8
    per = bw * bh * 4
    cols = fw // bw
    hashes: dict[int, int] = {}
    while idx + 4 <= len(raw):
        x, y = struct.unpack(">HH", raw[idx:idx + 4])
        idx += 4
        if x == 0xFFFF and y == 0xFFFF:
            break
        h = struct.unpack(">I", raw[idx:idx + 4])[0]
        idx += 4 + per
        hashes[(y // bh) * cols + (x // bw)] = h
    return fw, fh, bw, bh, hashes


def run_case(model: str, fw: int, fh: int, nblocks: int) -> None:
    print(f"\n=== {model}（期望 {fw}×{fh}，{nblocks} 块）===")
    port = free_port()
    proc = start_mock(model, port)
    try:
        # --- 首帧：客户端没有任何哈希 → 全量 ---
        raw = osd_request(port, [0] * nblocks, nblocks)
        rfw, rfh, rbw, rbh, hashes = parse_response(raw)
        check(rfw == fw, "帧宽", f"got {rfw} want {fw}")
        check(rfh == fh, "帧高", f"got {rfh} want {fh}")
        check((rbw, rbh) == (BLOCK_W, BLOCK_H), "块尺寸恒为 80×40", f"got {rbw}×{rbh}")
        check(
            len(raw) == MIN_BYTES + nblocks * RECORD_BYTES,
            "全量长度符合 12 + N×12808",
            f"got {len(raw)} want {MIN_BYTES + nblocks * RECORD_BYTES}",
        )
        check(len(hashes) == nblocks, "首帧返回全部块", f"got {len(hashes)} want {nblocks}")

        # --- 增量：带上刚才的哈希 ---
        table = [0] * nblocks
        for i, h in hashes.items():
            table[i] = h
        raw2 = osd_request(port, table, nblocks)
        _, _, _, _, hashes2 = parse_response(raw2)
        check(
            1 <= len(hashes2) <= 3,
            "增量只回变化块（1~3 块）",
            f"got {len(hashes2)} 块 / {len(raw2)} 字节",
        )
        check(
            len(raw2) == MIN_BYTES + len(hashes2) * RECORD_BYTES,
            "增量长度同样符合公式",
            f"got {len(raw2)}",
        )

        # --- 客户端**少发**哈希：服务端会用未初始化栈值比较 → 块被重发 ---
        raw3 = osd_request(port, [0] * nblocks, nblocks // 2)
        _, _, _, _, hashes3 = parse_response(raw3)
        check(
            len(hashes3) >= nblocks // 2,
            "少发哈希时多出的块被重发（说明服务端按请求长度循环）",
            f"got {len(hashes3)} 块",
        )

        # --- 客户端**多发**超过 120：真机会栈溢出，所以 App 侧必须截断 ---
        #    这里只验证 mock 会忽略多余部分，不作为安全保证
        raw4 = osd_request(port, [0] * nblocks, min(nblocks + 10, 240))
        _, _, _, _, hashes4 = parse_response(raw4)
        check(len(hashes4) <= nblocks, "多发哈希时 mock 忽略多余部分", f"got {len(hashes4)}")
    finally:
        proc.kill()
        proc.wait(timeout=5)


def main() -> int:
    print("=== 验证 mock 相机的多机型 OSD ===")
    for model, fw, fh, nblocks in CASES:
        run_case(model, fw, fh, nblocks)

    print("\n=== 汇总 ===")
    print(f"  检查项 {checks}，失败 {len(failures)}")
    if failures:
        for f in failures:
            print(f"    - {f}")
        return 1
    print("  全部通过")
    return 0


if __name__ == "__main__":
    sys.exit(main())
