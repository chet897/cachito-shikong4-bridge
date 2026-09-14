#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""从官方 App 的 btsnoop 抓包里提取花样模式，输出 patterns.json 的 v2 格式。

用法：
    python3 extract_patterns.py <btsnoop 文件> 模式名1 模式名2 ...  [-o 输出.json]
    python3 extract_patterns.py xxx.btsnoop 玄妙 暗涌 旧梦
    加 -v 打印每段完整时间线，用来人工核对。

原理（命令格式见接口约定第 7 节）：
    抓包里 LE Set (Extended) Advertising Data 的 0x07 段就是 16 字节命令。
    类型 b4：51=吮吸 01=吮吸关 52=入体 02=总关 0F=休眠
    吮吸 level = (b13 - 50) * 2      入体 level = 102 - b11

分段：
    官方 App 换模式/停止时会播「总关」，所以**以总关作为一个模式的结束**。
    中途的「休眠(0F)」和超过 GAP_S 秒的空档，说明这段没播完（用户在瞎点），整段丢掉。
    丢掉的段不占模式名的顺序。

每通道取（档位, 持续秒数）：
    持续秒数 = 到该通道下一条**不同 level** 的间隔，四舍五入到 0.5 秒。
    最后一条的持续时间算到本通道结束（吮吸关 / 总关）。

找循环体：
    掐掉开头几条（用户手滑的零星调整）和最后一条（被总关截断，时长不准），
    在中间稳定段里找**最短重复周期**当循环体。
    注意：循环体是个圈，从哪一档起头都等价，脚本按抓包里实际先播的那一档起头。
"""
import argparse
import datetime
import json
import struct
import sys

BTSNOOP_EPOCH = 0x00dcddb30f2f8000   # btsnoop 时间戳原点 → unix

T_SUCK = 0x51
T_SUCK_OFF = 0x01
T_INVIB = 0x52
T_OFF = 0x02
T_SLEEP = 0x0F

GAP_S = 15.0        # 超过这么久没动静，认为上一段没播完
SKIP_HEAD = 1       # 每通道掐掉开头几条（用户手滑）
ROUND_TO = 0.5      # 时长量化到 0.5 秒


def round_half(x, unit=ROUND_TO):
    """四舍五入到 unit 的整数倍（0.5 一律进位，不用 python 的银行家舍入）。"""
    n = int(x / unit + 0.5)
    v = n * unit
    return round(v, 3)


def parse_btsnoop(path):
    """btsnoop → [(时间戳秒, 类型字节, 16字节命令)]，只留失控 4.0 的命令。"""
    data = open(path, "rb").read()
    if data[:8] != b"btsnoop\0":
        raise SystemExit("不是 btsnoop 文件：%s" % path)
    pos, out = 16, []
    while pos + 24 <= len(data):
        _ol, il, _fl, _dr, ts = struct.unpack(">IIIIq", data[pos:pos + 24])
        pos += 24
        d = data[pos:pos + il]
        pos += il
        if not d or d[0] != 1:            # 只要 HCI command
            continue
        op = d[1] | (d[2] << 8)
        if op not in (0x2008, 0x2037):    # LE Set (Extended) Advertising Data
            continue
        p = d[4:]
        p = p[4:] if op == 0x2037 else p[1:]
        i = 0
        while i < len(p):
            L = p[i]
            if L == 0:
                break
            t = p[i + 1]
            v = p[i + 2:i + 1 + L]
            if t == 0x07 and len(v) >= 16:
                u = v[:16][::-1]          # AD 是小端，反过来才是协议字节序
                if u[0] == 0x71 and u[2] == 0x17:
                    out.append(((ts - BTSNOOP_EPOCH) / 1e6, u[4], u))
            i += 1 + L
    return out


def decode(typ, b):
    """→ (通道, 档位) 或 (标记, None)。"""
    if typ == T_SUCK:
        return "suck", (b[13] - 50) * 2
    if typ == T_INVIB:
        return "invib", 102 - b[11]
    if typ == T_SUCK_OFF:
        return "suck_off", None
    if typ == T_OFF:
        return "off", None
    if typ == T_SLEEP:
        return "sleep", None
    return "unknown_%02X" % typ, None


def split_segments(events, gap_s=GAP_S):
    """按「总关」切段。只有以总关收尾的段才算一个完整模式。"""
    segs, buf, last_t = [], [], None
    for t, typ, b in events:
        kind, level = decode(typ, b)
        if last_t is not None and (t - last_t) > gap_s and buf:
            sys.stderr.write("  [跳过] %s 起的一段空了 %.1f 秒没收尾，丢掉\n"
                             % (stamp(buf[0][0]), t - last_t))
            buf = []
        last_t = t
        if kind == "off":
            if buf:
                segs.append({"events": buf, "end": t})
            buf = []
        elif kind == "sleep":
            if buf:
                sys.stderr.write("  [跳过] %s 起的一段以休眠收尾（没播完），丢掉\n" % stamp(buf[0][0]))
            buf = []
        elif kind in ("suck", "invib", "suck_off"):
            buf.append((t, kind, level))
    if buf:
        sys.stderr.write("  [跳过] %s 起的一段没等到总关，丢掉\n" % stamp(buf[0][0]))
    return segs


def channel_steps(seg, channel):
    """一段里某个通道的 [(档位, 持续秒数), ...]（原始顺序，含开头的手滑）。"""
    end = seg["end"]
    if channel == "suck":
        for t, kind, _ in seg["events"]:
            if kind == "suck_off":
                end = t                     # 吮吸端提前被「吮吸关」结束
                break
    hits = [(t, lv) for t, kind, lv in seg["events"] if kind == channel]
    merged = []
    for t, lv in hits:                      # 合并连着的同一档（重播）
        if merged and merged[-1][1] == lv:
            continue
        merged.append((t, lv))
    steps = []
    for i, (t, lv) in enumerate(merged):
        nxt = merged[i + 1][0] if i + 1 < len(merged) else end
        steps.append((lv, round_half(nxt - t)))
    return steps


def rotate_to_first(cycle, steps):
    """循环体是个圈，从哪一档起头都等价；统一转成「抓包里最先播的那一档」起头。

    先按 (档位, 秒数) 找，找不到就只按档位找；都找不到就原样返回。
    """
    if not cycle or not steps:
        return cycle
    head = steps[0]
    for i, it in enumerate(cycle):
        if it == head:
            return cycle[i:] + cycle[:i]
    for i, it in enumerate(cycle):
        if it[0] == head[0]:
            return cycle[i:] + cycle[:i]
    return cycle


def find_cycle(steps, skip_head=SKIP_HEAD):
    """在中间稳定段里找最短重复周期。返回 (循环体, 说明)。"""
    mid = steps[skip_head:-1] if len(steps) > skip_head + 2 else steps[:]
    if not mid:
        return [], "数据太少"
    n = len(mid)
    for p in range(1, n // 2 + 1):
        if all(mid[i] == mid[i + p] for i in range(n - p)):
            return rotate_to_first(mid[:p], steps), "周期 %d 步（中间 %d 步全对上）" % (p, n)
    return mid, "⚠️ 没找到重复周期，原样返回 %d 步" % n


def stamp(t):
    return datetime.datetime.fromtimestamp(t).strftime("%H:%M:%S")


def fmt(cycle):
    return " → ".join("%d×%gs" % (lv, s) for lv, s in cycle) if cycle else "(空)"


def num(x):
    """2.0 → 2，1.5 → 1.5（JSON 里别留一串 2.0）。"""
    x = round(float(x), 3)
    return int(x) if x == int(x) else x


def dumps_patterns(items):
    """按 patterns.json 的排版输出：每一步 [档位, 秒数] 挤在一行里，方便人看。"""
    blocks = []
    for it in items:
        lines = ['    "name": %s' % json.dumps(it["name"], ensure_ascii=False),
                 '    "desc": %s' % json.dumps(it["desc"], ensure_ascii=False),
                 '    "official": %s' % ("true" if it["official"] else "false")]
        for ch in ("suck", "invib"):
            steps = ", ".join("[%d, %s]" % (lv, num(s)) for lv, s in it[ch])
            lines.append('    "%s": [%s]' % (ch, steps))
        blocks.append("  {\n" + ",\n".join(lines) + "\n  }")
    return "[\n" + ",\n".join(blocks) + "\n]"


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("btsnoop")
    ap.add_argument("names", nargs="+", help="按抓包里出现的先后顺序给模式名")
    ap.add_argument("-o", "--out", help="写到这个 json 文件；不给就打到屏幕上")
    ap.add_argument("-v", "--verbose", action="store_true", help="打印每段完整时间线")
    a = ap.parse_args()

    events = parse_btsnoop(a.btsnoop)
    print("命令帧 %d 条" % len(events))
    segs = split_segments(events)
    print("以总关收尾的完整段：%d 个，给了 %d 个模式名" % (len(segs), len(a.names)))
    if len(segs) != len(a.names):
        print("⚠️ 段数和模式名数量对不上，按先后顺序尽量配对")

    out = []
    for i, seg in enumerate(segs):
        name = a.names[i] if i < len(a.names) else "未命名%d" % (i + 1)
        t0 = seg["events"][0][0]
        print("\n=== %s ===  %s → %s  共 %.1f 秒" % (
            name, stamp(t0), stamp(seg["end"]), seg["end"] - t0))
        if a.verbose:
            for t, kind, lv in seg["events"]:
                print("   %s  %-6s %s" % (
                    datetime.datetime.fromtimestamp(t).strftime("%H:%M:%S.%f")[:-3], kind,
                    "" if lv is None else lv))
        item = {"name": name, "desc": "", "official": True}
        for ch, label in (("suck", "吮吸"), ("invib", "入体")):
            steps = channel_steps(seg, ch)
            cycle, why = find_cycle(steps)
            print("  %s 原始 %d 步：%s" % (label, len(steps), fmt(steps)))
            print("  %s 循环体：%s   （%s，一圈 %gs）" % (
                label, fmt(cycle), why, round(sum(s for _, s in cycle), 3)))
            item[ch] = [[lv, num(s)] for lv, s in cycle]
        out.append(item)

    js = dumps_patterns(out)
    if a.out:
        open(a.out, "w", encoding="utf-8").write(js + "\n")
        print("\n已写 %s" % a.out)
    else:
        print("\n" + js)


if __name__ == "__main__":
    main()
