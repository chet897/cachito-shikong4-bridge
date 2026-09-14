#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""失控 4.0 广播命令编码器（接口约定第 7 节）。

一条命令 = 16 字节 = 一个 128 位 UUID：
    [0]71 [1]00 [2]17 [3]nonce [4-5]类型 [6]9E [7]D5 [8-9]01 00 [10-14]载荷 [15]校验和
    校验和 = 前 15 字节之和 mod 256；nonce 每条随机 0-255

UUID 字符串按 8-4-4-4-12 分组，字节顺序就是从左到右。
"""
import random

HEAD = (0x71, 0x00, 0x17)          # b0-b2 固定头
MID = (0x9E, 0xD5, 0x01, 0x00)     # b6-b9 固定中段

TYPE_SUCK = (0x51, 0x00)           # 吮吸
TYPE_SUCK_OFF = (0x01, 0x00)       # 吮吸关
TYPE_INVIB = (0x52, 0x00)          # 入体震动
TYPE_OFF = (0x02, 0x00)            # 总关


def _build(type_bytes, payload, nonce=None):
    """拼 16 字节并算校验和，返回 bytes。"""
    if nonce is None:
        nonce = random.randint(0, 255)
    if not 0 <= nonce <= 255:
        raise ValueError("nonce 必须 0-255")
    payload = list(payload)
    if len(payload) != 5:
        raise ValueError("载荷必须 5 字节")
    b = list(HEAD) + [nonce] + list(type_bytes) + list(MID) + payload
    if len(b) != 15:
        raise ValueError("帧长度错误")
    b.append(sum(b) % 256)
    return bytes(b)


def to_uuid(b):
    """16 字节 -> 128 位 UUID 字符串（大写，8-4-4-4-12）。"""
    if len(b) != 16:
        raise ValueError("必须 16 字节")
    h = b.hex().upper()
    return "%s-%s-%s-%s-%s" % (h[0:8], h[8:12], h[12:16], h[16:20], h[20:32])


def suck(level, nonce=None):
    """吮吸档位命令。level 1-100（App 滑块刻度）。载荷 64 00 00 [b13] 02，b13 = 50 + level//2。"""
    level = int(level)
    if not 1 <= level <= 100:
        raise ValueError("level 必须 1-100，0 请用 suck_off()")
    b13 = 50 + level // 2
    return _build(TYPE_SUCK, (0x64, 0x00, 0x00, b13, 0x02), nonce)


def suck_off(nonce=None):
    """吮吸关。载荷 64 00 00 00 02。"""
    return _build(TYPE_SUCK_OFF, (0x64, 0x00, 0x00, 0x00, 0x02), nonce)


def invib(level, nonce=None):
    """入体震动档位命令。level 1-100。载荷 2D [b11] 15 00 02，b11 = 102 - level（越小越猛）。"""
    level = int(level)
    if not 1 <= level <= 100:
        raise ValueError("level 必须 1-100，0 请用 off()")
    b11 = 102 - level
    return _build(TYPE_INVIB, (0x2D, b11, 0x15, 0x00, 0x02), nonce)


def off(nonce=None):
    """总关。载荷 00 00 00 00 00。入体端没有单独关命令，用这条。"""
    return _build(TYPE_OFF, (0x00, 0x00, 0x00, 0x00, 0x00), nonce)


# 便捷：直接拿 UUID 字符串
def suck_uuid(level, nonce=None):
    return to_uuid(suck(level, nonce))


def suck_off_uuid(nonce=None):
    return to_uuid(suck_off(nonce))


def invib_uuid(level, nonce=None):
    return to_uuid(invib(level, nonce))


def off_uuid(nonce=None):
    return to_uuid(off(nonce))


if __name__ == "__main__":
    # 接口约定第 7 节自检样本（同 nonce 必须逐字节一致）
    cases = [
        ("suck(25,  nonce=0x63)", suck_uuid(25, 0x63), "71001763-5100-9ED5-0100-6400003E0254"),
        ("suck(100, nonce=0x6F)", suck_uuid(100, 0x6F), "7100176F-5100-9ED5-0100-640000640286"),
        ("suck_off(nonce=0x1F) ", suck_off_uuid(0x1F), "7100171F-0100-9ED5-0100-640000000282"),
        ("invib(50, nonce=0x66)", invib_uuid(50, 0x66), "71001766-5200-9ED5-0100-2D341500022C"),
        ("invib(100,nonce=0x3F)", invib_uuid(100, 0x3F), "7100173F-5200-9ED5-0100-2D02150002D3"),
        ("off(nonce=0x77)      ", off_uuid(0x77), "71001777-0200-9ED5-0100-000000000075"),
    ]
    bad = 0
    for name, got, want in cases:
        ok = got == want
        if not ok:
            bad += 1
        print("%s  %s  got=%s want=%s" % ("MATCH " if ok else "FAIL  ", name, got, want))
    print("---")
    print("全部通过" if bad == 0 else "%d 条不匹配" % bad)
    raise SystemExit(1 if bad else 0)
