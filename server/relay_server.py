#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""失控 4.0 手机广播控制 · 中转服务（relay）

接口约定：见仓库 docs/接口约定.md
本进程是状态唯一真身：状态机 + 花样引擎 + WebSocket（推给安卓 App）+ HTTP + 手动网页。
监听 0.0.0.0:8895。隧道过来的路径带 /shikong 前缀，直连不带，两种都认。
日志走 stdout，journald 收（journalctl -u shikong-relay）。
"""
import asyncio
import hmac
import json
import os
import random
import sys
import time
from pathlib import Path

from aiohttp import web, WSMsgType

HERE = Path(__file__).resolve().parent
TOKEN_FILE = Path("/opt/shikong-bridge/token")
PATTERN_FILE = HERE / "patterns.json"
HTML_FILE = HERE / "relay.html"
APK_DIR = HERE / "apk"
APK_PATH_FILE = HERE / "apk-path.txt"

PORT = 8895
PREFIX = "/shikong"
PING_INTERVAL = 5.0        # 秒，心跳
PHONE_TIMEOUT_MS = 15000   # 15 秒没收到手机任何消息 = 离线

CORS = {
    "Access-Control-Allow-Origin": "*",
    "Access-Control-Allow-Methods": "GET, POST, OPTIONS",
    "Access-Control-Allow-Headers": "Content-Type, Authorization",
}


def log(*a):
    print(time.strftime("%Y-%m-%d %H:%M:%S"), *a, flush=True)


def now_ms():
    return int(time.time() * 1000)


# ---------------- 口令 ----------------

def load_token():
    try:
        t = TOKEN_FILE.read_text(encoding="utf-8").strip()
    except Exception as e:
        log("[FATAL] 读不到口令文件", TOKEN_FILE, e)
        sys.exit(1)
    if not t:
        log("[FATAL] 口令文件是空的", TOKEN_FILE)
        sys.exit(1)
    return t


TOKEN = load_token()


def token_of(request):
    """从 Authorization: Bearer 或 query ?token= 取口令。"""
    auth = request.headers.get("Authorization", "")
    if auth.startswith("Bearer "):
        v = auth[7:].strip()
        if v:
            return v
    return request.query.get("token", "").strip()


def token_ok(request):
    got = token_of(request)
    return bool(got) and hmac.compare_digest(got, TOKEN)


def client_ip(request):
    return request.headers.get("CF-Connecting-IP") or (request.remote or "?")


def log_401(request):
    """口令不对就留一行线索，不然手机连不上只能靠猜。只记前 2 位，不泄露口令。"""
    got = token_of(request)
    shown = (got[:2] + "***") if got else "(没带)"
    log("[AUTH] 401 path=%s from=%s token=%s" % (request.rel_url, client_ip(request), shown))


def need_token(handler):
    async def wrapper(request):
        if not token_ok(request):
            log_401(request)
            return web.json_response({"ok": False, "error": "unauthorized"}, status=401, headers=CORS)
        return await handler(request)
    wrapper.__name__ = getattr(handler, "__name__", "wrapped")
    return wrapper


# ---------------- 模式表 ----------------

def load_patterns():
    try:
        data = json.loads(PATTERN_FILE.read_text(encoding="utf-8"))
        if isinstance(data, list):
            return data
        log("[WARN] patterns.json 不是数组，忽略")
    except Exception as e:
        log("[WARN] 读 patterns.json 失败:", e)
    return []


PATTERNS = load_patterns()


def find_pattern(name):
    for p in PATTERNS:
        if p.get("name") == name:
            return p
    return None


MIN_STEP_S = 0.3   # 每步最短秒数，防止模式表写了 0 把 CPU 转死


def pattern_steps(pat, channel):
    """把一个通道的序列统一成 [(档位, 本步秒数), ...]。

    v2 格式（现在用的）："suck": [[20, 2], [90, 2]] —— 每步自带秒数，两通道各走各的。
    v1 旧格式（兼容）：  "suck": [20, 90] + "step_s": 2.0 —— 所有步共用一个步长。
    """
    raw = pat.get(channel) or []
    default_s = 2.0
    try:
        if pat.get("step_s") is not None:
            default_s = float(pat["step_s"])
    except Exception:
        pass
    if default_s <= 0:
        default_s = 2.0
    out = []
    for it in raw:
        if isinstance(it, (list, tuple)):
            if not it:
                continue
            lv = it[0]
            sec = it[1] if len(it) > 1 else default_s
        elif isinstance(it, dict):
            lv = it.get("level")
            sec = it.get("s", it.get("seconds", default_s))
        else:
            lv, sec = it, default_s
        try:
            lv = max(0, min(100, int(lv)))
            sec = float(sec)
        except Exception:
            log("[PATTERN] 模式 %s 的 %s 有一步看不懂，跳过：%r" % (pat.get("name"), channel, it))
            continue
        if sec <= 0:
            sec = default_s
        out.append((lv, max(MIN_STEP_S, sec)))
    return out


def fmt_secs(s):
    """2.0 → '2'，1.5 → '1.5'（别让页面上出现 2.0 秒）。"""
    s = round(float(s), 3)
    return ("%g" % s)


def steps_text(steps):
    """[(20,2),(90,2)] → '20档×2秒 → 90档×2秒'。"""
    if not steps:
        return "（无）"
    return " → ".join("%d档×%s秒" % (lv, fmt_secs(sec)) for lv, sec in steps)


def pattern_view(pat):
    """给 /api/patterns 用：统一成 v2，并带上人话版「档位×秒」。"""
    view = {
        "name": pat.get("name"),
        "desc": pat.get("desc", ""),
        "official": bool(pat.get("official")),
    }
    for ch, label in (("suck", "suck"), ("invib", "invib")):
        steps = pattern_steps(pat, ch)
        view[label] = [[lv, round(sec, 3)] for lv, sec in steps]
        view[label + "_text"] = steps_text(steps)
        view[label + "_cycle_s"] = round(sum(sec for _, sec in steps), 3)
    return view


# ---------------- 状态（唯一真身） ----------------

STATE = {
    "suck": {"level": 0, "pattern": None},
    "invib": {"level": 0, "pattern": None},
    "seq": 0,
}

# device：最近一次 hello 报上来的机型，跨断线保留（只被下一次 hello 覆盖）
# last_pushed：本条连接上最后推出去的 seq，防止同一个 seq 推两遍（App 会重复 ack）
WS = {"conn": None, "last_seen": 0, "info": {}, "device": None, "last_pushed": None}
PATTERN_TASKS = {"suck": None, "invib": None}
CHANNELS = ("suck", "invib")


def phone_online():
    c = WS["conn"]
    if c is None or c.closed:
        return False
    return (now_ms() - WS["last_seen"]) < PHONE_TIMEOUT_MS


def snapshot():
    return {
        "suck": dict(STATE["suck"]),
        "invib": dict(STATE["invib"]),
        "seq": STATE["seq"],
        "phone_online": phone_online(),
        "phone_last_seen_ms": WS["last_seen"],
        "phone_device": WS["device"],
    }


async def push_state(force=False):
    """状态变化后立即推给手机。

    同一条连接上同一个 seq 只推一次（force=True 例外，用于新连接的首条完整 state）；
    不然 App 会对同一个 seq 回两条 ack。
    """
    c = WS["conn"]
    if c is None or c.closed:
        return
    if not force and WS["last_pushed"] == STATE["seq"]:
        return
    msg = {
        "type": "state",
        "seq": STATE["seq"],
        "suck": STATE["suck"]["level"],
        "invib": STATE["invib"]["level"],
    }
    try:
        await c.send_json(msg)
        WS["last_pushed"] = STATE["seq"]
    except Exception as e:
        log("[WS] 推送失败:", e)


async def bump_and_push(reason=""):
    STATE["seq"] += 1
    log("[STATE] seq=%d suck=%d(%s) invib=%d(%s) phone=%s %s" % (
        STATE["seq"], STATE["suck"]["level"], STATE["suck"]["pattern"],
        STATE["invib"]["level"], STATE["invib"]["pattern"],
        "在线" if phone_online() else "离线", reason))
    await push_state()


# ---------------- 花样引擎 ----------------

async def _pattern_runner(channel, pat):
    """每通道一个 task：按**每步自己的秒数**把序列值写进该通道 level 并推给手机。

    两个通道各跑各的 task，步长不同也互不干扰（暗涌就是吮吸 5.5 秒一圈、入体 8 秒一圈）。
    """
    steps = pattern_steps(pat, channel)
    if not steps:
        log("[PATTERN] %s 模式 %s 没有该通道序列，退出" % (channel, pat.get("name")))
        STATE[channel]["pattern"] = None
        return
    log("[PATTERN] %s/%s 序列：%s（一圈 %s 秒）" % (
        pat.get("name"), channel, steps_text(steps),
        fmt_secs(sum(s for _, s in steps))))
    i = 0
    try:
        while True:
            lvl, sec = steps[i % len(steps)]
            STATE[channel]["level"] = lvl
            await bump_and_push("pattern %s/%s step%d(%d档×%s秒)" % (
                pat.get("name"), channel, i, lvl, fmt_secs(sec)))
            i += 1
            await asyncio.sleep(sec)
    except asyncio.CancelledError:
        raise
    except Exception as e:
        log("[PATTERN] %s 异常:" % channel, e)
        STATE[channel]["pattern"] = None


async def cancel_pattern(channel):
    t = PATTERN_TASKS.get(channel)
    PATTERN_TASKS[channel] = None
    if t is not None and not t.done():
        t.cancel()
        try:
            await t
        except asyncio.CancelledError:
            pass
        except Exception:
            pass


def parts_of(part):
    if part == "both":
        return list(CHANNELS)
    if part in CHANNELS:
        return [part]
    return []


async def start_pattern(part, name):
    pat = find_pattern(name)
    if pat is None:
        return False, "没有这个模式：%s" % name
    chans = parts_of(part)
    if not chans:
        return False, "part 只能是 suck / invib / both"
    for ch in chans:
        await cancel_pattern(ch)
        STATE[ch]["pattern"] = name
        PATTERN_TASKS[ch] = asyncio.create_task(_pattern_runner(ch, pat))
    log("[PATTERN] 启动 %s -> %s" % (name, ",".join(chans)))
    return True, "ok"


async def stop_pattern(part):
    chans = parts_of(part)
    if not chans:
        return False, "part 只能是 suck / invib / both"
    for ch in chans:
        await cancel_pattern(ch)
        STATE[ch]["pattern"] = None
    await bump_and_push("pattern stop %s" % ",".join(chans))
    return True, "ok"


async def set_level(channel, level):
    """手动设档：清掉该通道 pattern。"""
    await cancel_pattern(channel)
    STATE[channel]["pattern"] = None
    STATE[channel]["level"] = max(0, min(100, int(level)))


async def stop_all():
    for ch in CHANNELS:
        await cancel_pattern(ch)
        STATE[ch]["pattern"] = None
        STATE[ch]["level"] = 0
    await bump_and_push("stop all")


# ---------------- HTTP 接口 ----------------

@need_token
async def api_state(request):
    return web.json_response(snapshot(), headers=CORS)


@need_token
async def api_patterns(request):
    return web.json_response([pattern_view(p) for p in PATTERNS], headers=CORS)


@need_token
async def api_set(request):
    try:
        body = await request.json()
    except Exception:
        return web.json_response({"ok": False, "error": "body 必须是 JSON"}, status=400, headers=CORS)
    if not isinstance(body, dict):
        return web.json_response({"ok": False, "error": "body 必须是对象"}, status=400, headers=CORS)
    touched = []
    for ch in CHANNELS:
        if ch in body and body[ch] is not None:
            try:
                lvl = int(body[ch])
            except Exception:
                return web.json_response({"ok": False, "error": "%s 必须是 0-100 整数" % ch}, status=400, headers=CORS)
            if not 0 <= lvl <= 100:
                return web.json_response({"ok": False, "error": "%s 必须 0-100" % ch}, status=400, headers=CORS)
            await set_level(ch, lvl)
            touched.append(ch)
    if not touched:
        return web.json_response({"ok": False, "error": "至少给一个 suck / invib"}, status=400, headers=CORS)
    await bump_and_push("set %s" % ",".join(touched))
    return web.json_response({"ok": True, "state": snapshot()}, headers=CORS)


@need_token
async def api_pattern(request):
    try:
        body = await request.json()
    except Exception:
        return web.json_response({"ok": False, "error": "body 必须是 JSON"}, status=400, headers=CORS)
    name = (body or {}).get("name")
    part = (body or {}).get("part", "both")
    if not name:
        return web.json_response({"ok": False, "error": "缺 name"}, status=400, headers=CORS)
    ok, msg = await start_pattern(part, name)
    if not ok:
        return web.json_response({"ok": False, "error": msg}, status=400, headers=CORS)
    return web.json_response({"ok": True, "state": snapshot()}, headers=CORS)


@need_token
async def api_pattern_stop(request):
    try:
        body = await request.json()
    except Exception:
        body = {}
    part = (body or {}).get("part", "both")
    ok, msg = await stop_pattern(part)
    if not ok:
        return web.json_response({"ok": False, "error": msg}, status=400, headers=CORS)
    return web.json_response({"ok": True, "state": snapshot()}, headers=CORS)


@need_token
async def api_stop(request):
    await stop_all()
    return web.json_response({"ok": True, "state": snapshot()}, headers=CORS)


async def handle_options(request):
    return web.Response(headers=CORS)


# ---------------- WebSocket（手机 App） ----------------

async def _kick_old(old):
    """被新连接顶掉时：先推一条 kicked 说明原因，再关。
    App 收到 kicked 就知道是另一台手机抢了连接，会提示用户并把重连间隔拉长，
    避免两台手机每 3 秒互相顶。"""
    try:
        await asyncio.wait_for(old.send_json({"type": "kicked", "reason": "another_phone"}), timeout=2)
    except Exception as e:
        log("[WS] kicked 通知发不出去:", e)
    try:
        await asyncio.wait_for(old.close(code=4000, message=b"replaced"), timeout=3)
    except Exception:
        pass


async def phone_ws(request):
    if not token_ok(request):
        # 握手都没成就被挡掉，这行日志是手机连不上时唯一的线索
        log_401(request)
        return web.json_response({"ok": False, "error": "unauthorized"}, status=401, headers=CORS)

    ws = web.WebSocketResponse(heartbeat=None, autoping=False)
    await ws.prepare(request)

    old = WS["conn"]
    if old is not None and not old.closed:
        log("[WS] 新连接顶掉旧连接（旧机型 %s）" % WS["device"])
        # 后台去踢：close() 要等对方回 close 帧，两台手机互顶时不能让它卡住新连接
        asyncio.create_task(_kick_old(old))
    WS["conn"] = ws
    WS["last_seen"] = now_ms()
    WS["info"] = {}
    WS["last_pushed"] = None
    peer = request.headers.get("CF-Connecting-IP") or (request.remote or "?")
    log("[WS] 手机连上了 from %s" % peer)

    # 断线重连后先给一条完整 state（这条必推，约定要求）
    await push_state(force=True)

    ping_task = asyncio.create_task(_ping_loop(ws))
    try:
        async for msg in ws:
            if msg.type == WSMsgType.TEXT:
                WS["last_seen"] = now_ms()
                try:
                    data = json.loads(msg.data)
                except Exception:
                    log("[WS] 收到非 JSON:", msg.data[:200])
                    continue
                t = data.get("type")
                if t == "hello":
                    WS["info"] = data
                    dev = data.get("device")
                    if dev:
                        WS["device"] = str(dev)
                    log("[WS] hello app=%s device=%s connectable=%s" % (
                        data.get("app_version"), data.get("device"), data.get("connectable")))
                    # 连上时已经推过一条完整 state 了，这里只在 seq 变过才补推，
                    # 否则同一个 seq 会被推两遍（App 回两条 ack）。
                    await push_state()
                elif t == "ack":
                    log("[WS] ack seq=%s" % data.get("seq"))
                elif t == "pong":
                    pass
                elif t == "error":
                    log("[WS] App 报错: %s" % data.get("message"))
                else:
                    log("[WS] 未知消息类型: %s" % t)
            elif msg.type == WSMsgType.BINARY:
                WS["last_seen"] = now_ms()
            elif msg.type == WSMsgType.PING:
                WS["last_seen"] = now_ms()
                await ws.pong(msg.data)
            elif msg.type == WSMsgType.PONG:
                WS["last_seen"] = now_ms()
            elif msg.type == WSMsgType.ERROR:
                log("[WS] 连接异常:", ws.exception())
    except asyncio.CancelledError:
        raise
    except Exception as e:
        log("[WS] 循环异常:", e)
    finally:
        ping_task.cancel()
        try:
            await ping_task
        except BaseException:
            pass
        if WS["conn"] is ws:
            WS["conn"] = None
            log("[WS] 手机断开")
        else:
            log("[WS] 旧连接收尾")
    return ws


async def _ping_loop(ws):
    """每 5 秒一条心跳。"""
    try:
        while not ws.closed:
            await asyncio.sleep(PING_INTERVAL)
            if ws.closed:
                break
            try:
                await ws.send_json({"type": "ping", "ts": now_ms()})
            except Exception:
                break
    except asyncio.CancelledError:
        pass


# ---------------- 网页 / APK ----------------

async def serve_page(request):
    """手动控制网页。页面本身不含秘密，口令由页面从 ?token= 取；
    但如果 URL 上带了错的口令就直接 401，免得用户以为能用。"""
    got = token_of(request)
    if got and not hmac.compare_digest(got, TOKEN):
        log_401(request)
        return web.json_response({"ok": False, "error": "unauthorized"}, status=401, headers=CORS)
    try:
        html = HTML_FILE.read_text(encoding="utf-8")
    except Exception as e:
        log("[HTTP] 读网页失败:", e)
        return web.Response(text="relay.html not found", status=404)
    return web.Response(text=html, content_type="text/html", charset="utf-8",
                        headers={"Cache-Control": "no-store"})


def apk_name():
    try:
        return APK_PATH_FILE.read_text(encoding="utf-8").strip()
    except Exception:
        return ""


async def serve_apk(request):
    """无口令，但路径带随机段（随机段见 apk-path.txt）。"""
    want = apk_name()
    asked = "app-%s.apk" % request.match_info.get("rand", "")
    if not want or asked != want:
        return web.Response(text="not found", status=404)
    f = APK_DIR / want
    if not f.is_file():
        log("[APK] 文件还没生成: %s" % f)
        return web.Response(text="not found", status=404)
    log("[APK] 下载 %s" % want)
    return web.FileResponse(f, headers={"Content-Type": "application/vnd.android.package-archive"})


async def health(request):
    return web.json_response({"ok": True, "service": "shikong-relay", "phone_online": phone_online()},
                             headers=CORS)


# ---------------- 路由（裸路径 + /shikong 前缀 两套） ----------------

ROUTES = [
    ("GET", "/api/state", api_state),
    ("GET", "/api/patterns", api_patterns),
    ("POST", "/api/set", api_set),
    ("POST", "/api/pattern", api_pattern),
    ("POST", "/api/pattern/stop", api_pattern_stop),
    ("POST", "/api/stop", api_stop),
    ("OPTIONS", "/api/set", handle_options),
    ("OPTIONS", "/api/pattern", handle_options),
    ("OPTIONS", "/api/pattern/stop", handle_options),
    ("OPTIONS", "/api/stop", handle_options),
    ("OPTIONS", "/api/state", handle_options),
    ("OPTIONS", "/api/patterns", handle_options),
    ("GET", "/healthz", health),
    ("GET", "/phone/ws", phone_ws),
    ("GET", r"/app-{rand:[0-9a-zA-Z]+}.apk", serve_apk),
    ("GET", "/", serve_page),
]


def build_app():
    app = web.Application()
    for method, path, handler in ROUTES:
        for base in ("", PREFIX):
            p = base + path
            if base and path == "/":
                # /shikong 和 /shikong/ 都要能打开
                app.router.add_route(method, base, handler)
                p = base + "/"
            app.router.add_route(method, p, handler)
    return app


def ensure_apk_path():
    if not APK_PATH_FILE.exists() or not apk_name():
        rand = "".join(random.choice("0123456789abcdef") for _ in range(8))
        APK_PATH_FILE.write_text("app-%s.apk\n" % rand, encoding="utf-8")
        log("[APK] 生成随机下载路径 /app-%s.apk（写进 apk-path.txt）" % rand)


if __name__ == "__main__":
    APK_DIR.mkdir(parents=True, exist_ok=True)
    ensure_apk_path()
    log("shikong-relay 启动 port=%d 模式数=%d apk=%s" % (PORT, len(PATTERNS), apk_name() or "(无)"))
    web.run_app(build_app(), host="0.0.0.0", port=PORT, print=None, access_log=None)
