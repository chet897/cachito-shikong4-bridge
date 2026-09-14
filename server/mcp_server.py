#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""失控 4.0 手机广播控制 · MCP 服务（挂给 AI 用）

FastMCP streamable-http，监听 0.0.0.0:8896，路径 /mcp。
本进程不碰蓝牙也不存状态：所有动作都转发给本机 relay（127.0.0.1:8895），
relay 才是状态唯一真身，再由 relay 经 WebSocket 推给安卓 App 发蓝牙广播。
"""
import asyncio
import json
import sys
from pathlib import Path

import aiohttp
from mcp.server.fastmcp import FastMCP

RELAY = "http://127.0.0.1:8895"
TOKEN_FILE = Path("/opt/shikong-bridge/token")
TIMEOUT = aiohttp.ClientTimeout(total=8)

try:
    TOKEN = TOKEN_FILE.read_text(encoding="utf-8").strip()
except Exception as e:
    print("[FATAL] 读不到口令文件 %s: %s" % (TOKEN_FILE, e), flush=True)
    sys.exit(1)

HEADERS = {"Authorization": "Bearer " + TOKEN, "Content-Type": "application/json"}

mcp = FastMCP("Shikong 4.0 Toy Control", host="0.0.0.0", port=8896)


async def _call(method, path, body=None):
    """调 relay，返回 (ok, 数据或错误串)。"""
    url = RELAY + path
    try:
        async with aiohttp.ClientSession(timeout=TIMEOUT) as s:
            if method == "GET":
                async with s.get(url, headers=HEADERS) as r:
                    txt = await r.text()
                    status = r.status
            else:
                async with s.post(url, headers=HEADERS, data=json.dumps(body or {})) as r:
                    txt = await r.text()
                    status = r.status
    except asyncio.TimeoutError:
        return False, "中转服务超时（VPS 上 shikong-relay 可能卡了）"
    except Exception as e:
        return False, "连不上中转服务：%s（systemctl status shikong-relay）" % e
    try:
        data = json.loads(txt)
    except Exception:
        data = txt
    if status != 200:
        msg = data.get("error") if isinstance(data, dict) else str(data)[:200]
        return False, "中转服务返回 %d：%s" % (status, msg)
    return True, data


def _phone_note(st):
    """手机不在线要明确说清楚。"""
    if not isinstance(st, dict):
        return ""
    if st.get("phone_online"):
        return ""
    return "\n⚠️ 手机 App 没连上，命令发不出去（已记在中转服务里排队，手机一上线立刻生效）。"


def _fmt(st):
    if not isinstance(st, dict):
        return str(st)
    def one(ch, label):
        d = st.get(ch) or {}
        lv = d.get("level", 0)
        pt = d.get("pattern")
        return "%s %d%s" % (label, lv, ("（模式：%s）" % pt) if pt else "")
    return "%s｜%s｜手机%s" % (
        one("suck", "吮吸端"), one("invib", "入体端"),
        "在线" if st.get("phone_online") else "离线")


@mcp.tool()
async def shikong_suck(level: int) -> str:
    """失控 4.0 吮吸端（体外那一头）手动设档。

    level：0-100，就是手机 App 上滑块的刻度，0 = 关掉吮吸端。
    设了档会清掉吮吸端正在跑的模式。
    注意：玩具是记忆命令型 —— 发一次就一直执行下去，不会自己停，
    想停必须显式调 shikong_suck(0) 或 shikong_stop()。
    手机 App 不在线时命令只会存在中转服务里，等手机上线后才真正播出去。
    """
    if not isinstance(level, int) or not 0 <= level <= 100:
        return "参数错：level 要 0-100 的整数"
    ok, data = await _call("POST", "/api/set", {"suck": level})
    if not ok:
        return "失败：" + str(data)
    st = data.get("state") if isinstance(data, dict) else None
    return ("吮吸端 → %d\n%s" % (level, _fmt(st))) + _phone_note(st)


@mcp.tool()
async def shikong_invib(level: int) -> str:
    """失控 4.0 入体端（插入那一头的震动）手动设档。

    level：0-100，手机 App 滑块刻度，0 = 关掉入体端。
    设了档会清掉入体端正在跑的模式。两个通道完全独立，可以各跑各的。
    注意：玩具是记忆命令型 —— 发一次就一直震下去，必须显式调
    shikong_invib(0) 或 shikong_stop() 才会停。
    手机 App 不在线时命令只会存在中转服务里，等手机上线后才真正播出去。
    """
    if not isinstance(level, int) or not 0 <= level <= 100:
        return "参数错：level 要 0-100 的整数"
    ok, data = await _call("POST", "/api/set", {"invib": level})
    if not ok:
        return "失败：" + str(data)
    st = data.get("state") if isinstance(data, dict) else None
    return ("入体端 → %d\n%s" % (level, _fmt(st))) + _phone_note(st)


@mcp.tool()
async def shikong_pattern(name: str, part: str = "both") -> str:
    """跑一个官方花样模式（档位会按序列自动起伏，一直循环到你停它）。

    name：模式名，先用 shikong_pattern_list() 看有哪些。
    part："suck" 只套吮吸端 / "invib" 只套入体端 / "both" 两端都套（默认）。
          两端可以分别跑不同模式：先 part="suck" 跑一个，再 part="invib" 跑另一个。
    模式跑起来后档位是中转服务每隔几秒自动改的（level 仍是 App 滑块刻度 0-100）。
    注意：模式不会自己停 —— 停用 shikong_stop()（全停、档位归零），
    或者直接用 shikong_suck/shikong_invib 手动设档把该通道的模式顶掉。
    手机 App 不在线时命令只会存在中转服务里，等手机上线后才真正播出去。
    """
    if part not in ("suck", "invib", "both"):
        return "参数错：part 只能是 suck / invib / both"
    ok, data = await _call("POST", "/api/pattern", {"name": name, "part": part})
    if not ok:
        return "失败：" + str(data)
    st = data.get("state") if isinstance(data, dict) else None
    where = {"suck": "吮吸端", "invib": "入体端", "both": "两端"}[part]
    return ("模式「%s」→ %s，已开始循环\n%s" % (name, where, _fmt(st))) + _phone_note(st)


@mcp.tool()
async def shikong_pattern_list() -> str:
    """列出现在能用的花样模式：名字、说明、两端各自「几档播几秒」的完整一圈。

    每一步写成「档位×秒数」，档位就是 App 滑块刻度 0-100，秒数是这一档要保持多久；
    一圈走完自动从头再来。两端的圈长可以不一样（比如暗涌吮吸 5.5 秒一圈、入体 8 秒一圈），
    各走各的，所以听起来会一直错开。
    模式表在 VPS 的 /opt/shikong-bridge/patterns.json，以后加模式不用改代码。
    """
    ok, data = await _call("GET", "/api/patterns")
    if not ok:
        return "失败：" + str(data)
    if not isinstance(data, list) or not data:
        return "模式表是空的（检查 /opt/shikong-bridge/patterns.json）"

    def cyc(p, ch):
        txt = p.get(ch + "_text")
        if not txt:
            txt = " → ".join("%s档×%s秒" % (s[0], s[1]) for s in (p.get(ch) or [])) or "（无）"
        total = p.get(ch + "_cycle_s")
        return txt + ("（一圈 %g 秒）" % total if total else "")

    lines = []
    for p in data:
        desc = p.get("desc", "") or ""
        # desc 里通常已经写了「官方原版」，别重复标一遍
        tag = "" if (p.get("official") and desc.startswith("官方")) else \
              ("（官方原版）" if p.get("official") else "（自制）")
        lines.append("・%s%s —— %s\n  吮吸端：%s\n  入体端：%s" % (
            p.get("name"), tag, desc, cyc(p, "suck"), cyc(p, "invib")))
    return "共 %d 个模式：\n%s" % (len(data), "\n".join(lines))


@mcp.tool()
async def shikong_stop() -> str:
    """全停：两端档位都归 0、两端模式都清掉。

    玩具是记忆命令型，不主动停就会一直执行下去，所以结束时一定要调这个。
    """
    ok, data = await _call("POST", "/api/stop", {})
    if not ok:
        return "失败：" + str(data)
    st = data.get("state") if isinstance(data, dict) else None
    return ("已全停（两端归零、模式清空）\n%s" % _fmt(st)) + _phone_note(st)


@mcp.tool()
async def shikong_status() -> str:
    """看现在的实际状态：两端各自的档位、各自在跑什么模式、以及手机 App 在不在线。

    手机 App 不在线 = 命令播不出去，玩具收不到；这时候要明确告诉用户去开 App。
    level 是 App 滑块刻度 0-100。
    """
    ok, st = await _call("GET", "/api/state")
    if not ok:
        return "失败：" + str(st)
    body = _fmt(st)
    if not st.get("phone_online"):
        seen = st.get("phone_last_seen_ms") or 0
        extra = "（从没连上过）" if not seen else ""
        return body + "\n⚠️ 手机 App 没连上，命令发不出去%s。请打开安卓 App、确认它在后台常驻、网络通。" % extra
    return body + "\n手机 App 在线，命令能正常播出去。"


if __name__ == "__main__":
    print("shikong-mcp 启动，relay=%s" % RELAY, flush=True)
    mcp.run(transport="streamable-http")
