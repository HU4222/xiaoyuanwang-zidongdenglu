#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
深澜 srun 加密的独立参考实现（Python），用于与 Java 侧实现做交叉验证。

刻意不复用任何 Java 代码，按公开的 srun 算法独立写成：
  xencode = XXTEA(32-bit, delta=0x9E3779B9) + 自定义 base64 字母表
若两侧对同一输入产出相同密文，说明 Java 实现的位级行为与参考实现一致。
"""
import base64
import hashlib
import hmac
import struct

SRUN_B64 = "LVoJPiCN2R8G90yg+hmFHuacZ1OWMnrsSTXkYpUq/3dlbfKwv6xztjI7DeBE45QA"
STD_B64 = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
M = 0xFFFFFFFF


def sencode(msg, key):
    l = len(msg)
    pwd = []
    for i in range(0, l, 4):
        def at(j):
            return msg[j] if j < l else 0
        pwd.append(at(i) | at(i + 1) << 8 | at(i + 2) << 16 | at(i + 3) << 24)
    if key:
        pwd.append(l)
    return pwd


def lencode(msg, key):
    l = len(msg)
    ll = (l - 1) << 2
    if key:
        m = msg[l - 1]
        if m < ll - 3 or m > ll:
            return b""
        ll = m
    out = b"".join(struct.pack("<I", w & M) for w in msg)
    return out[:ll] if key else out


def xencode(msg, key):
    if msg == "":
        return b""
    pwd = sencode(msg.encode("utf-8"), True)
    pwdk = sencode(key.encode("utf-8"), False)
    if len(pwdk) < 4:
        pwdk += [0] * (4 - len(pwdk))

    n = len(pwd) - 1
    z = pwd[n]
    y = pwd[0]
    delta = 0x9E3779B9
    d = 0
    q = 6 + 52 // (n + 1)

    while q > 0:
        d = (d + delta) & M
        e = (d >> 2) & 3
        p = 0
        while p < n:
            y = pwd[p + 1]
            mx = ((z >> 5 ^ y << 2) + ((y >> 3 ^ z << 4) ^ (d ^ y)) + (pwdk[(p & 3) ^ e] ^ z)) & M
            pwd[p] = (pwd[p] + mx) & M
            z = pwd[p]
            p += 1
        y = pwd[0]
        mx = ((z >> 5 ^ y << 2) + ((y >> 3 ^ z << 4) ^ (d ^ y)) + (pwdk[(p & 3) ^ e] ^ z)) & M
        pwd[n] = (pwd[n] + mx) & M
        z = pwd[n]
        q -= 1
    return lencode(pwd, False)


def srun_b64(data):
    std = base64.b64encode(data).decode()
    table = str.maketrans(STD_B64, SRUN_B64)
    return std.translate(table)


def build_info(username, password, ip, acid, enc_ver):
    payload = ('{"username":"%s","password":"%s","ip":"%s","acid":"%s","enc_ver":"%s"}'
               % (username, password, ip, acid, enc_ver))
    return "{SRUN}" + srun_b64(xencode(payload, enc_ver))


def main():
    msgs = [
        "a",
        "abcd",
        "hello world",
        '{"username":"2023110101","password":"test123","ip":"10.0.0.2","acid":"1","enc_ver":"srun_bx1"}',
    ]
    for m in msgs:
        print("XXTEAHEX\t%s\t%s" % (m, xencode(m, "srun_bx1").hex()))

    print("INFO\t%s" % build_info("u1", "p1", "10.0.0.2", "1", "srun_bx1"))

    # 参考实现的自我校验
    print("CHECK-hmacmd5-rfc2202\t%s" % hmac.new(b"Jefe",
          b"what do ya want for nothing?", hashlib.md5).hexdigest())


if __name__ == "__main__":
    main()
