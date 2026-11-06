# SPDX-License-Identifier: GPL-3.0-only
"""红果 / 抖音系请求签名复现（纯 Python，无 native 依赖）。

两份来源，两套头，都实现了，可切换：

1. ``gorgon`` 模式 —— 本仓库 ``android/core/.../Signer.kt`` 的逐行移植
   （x-gorgon / x-khronos / x-ladon / x-argus / x-helios / x-medusa，
   ``(branch & 15) % 3 == 0`` 的 ticket 试探），也就是当前 APK 在线上能打通的那套。

2. ``ss_queries`` 模式 —— ``hgApi`` 交付包的模型（SIGNATURE.md §3）：
   ``ttEncrypt(device_id=..&device_type=..&device_brand=..&uuid=..&openudid=..)``
   → Base64 → query 里的 ``ss_queries``（放最前）+ 头 ``X-SS-QUERIES``，
   并把这些设备参数从 query 里摘掉。

两套的通用参数、UA、``X-SS-REQ-TICKET``、``passport-sdk-version`` 都一致。
"""
from __future__ import annotations

import base64
import hashlib
import random
import struct
import time
import urllib.parse

import hgvendor

# --------------------------------------------------------------------------- 基础原语


def u32(v: int) -> int:
    return v & 0xFFFFFFFF


def i32(v: int) -> int:
    v &= 0xFFFFFFFF
    return v - 0x100000000 if v >= 0x80000000 else v


def le32(v: int) -> bytes:
    return struct.pack("<I", v & 0xFFFFFFFF)


def be32(v: int) -> bytes:
    return struct.pack(">I", v & 0xFFFFFFFF)


def le64(v: int) -> bytes:
    return struct.pack("<Q", v & 0xFFFFFFFFFFFFFFFF)


def i32at(data: bytes, at: int, little: bool = False) -> int:
    return struct.unpack_from("<i" if little else ">i", data, at)[0]


def i64at(data: bytes, at: int, little: bool = False) -> int:
    return struct.unpack_from("<q" if little else ">q", data, at)[0]


def digest(name: str, data: bytes) -> bytes:
    return hashlib.new(name, data).digest()


def sm3(data: bytes) -> bytes:
    return hashlib.new("sm3", data).digest()


def rol(v: int, n: int) -> int:
    """对应 Integer.rotateLeft：位移量按 32 取模。"""
    n &= 31
    v &= 0xFFFFFFFF
    return ((v << n) | (v >> (32 - n))) & 0xFFFFFFFF if n else v


def ror(v: int, n: int) -> int:
    return i32(rol(v, -(n & 31)))


def _inv32(v: int) -> int:
    return i32(~u32(v))


def encode(value) -> str:
    """与 Signer.encode 一致：URLEncoder + '+'→%20 + '%7E'→~。"""
    return urllib.parse.quote(str(value), safe="").replace("+", "%20").replace("%7E", "~")


def build_query(values) -> str:
    return "&".join("%s=%s" % (encode(k), encode(v)) for k, v in values.items())


# --------------------------------------------------------------------------- ttEncrypt

_MAGIC = bytes((0x74, 0x63, 0x05, 0x10, 0x00, 0x00))
_KDF_SALT = bytes.fromhex(
    "4dd4c2e6b83162090e52b3c7a6733ba41cb2462b829ab58a196b39db57177524"
    "f49baf7f08e8d68d26a72e37c1a95a2f1f05a51892aef2949732b62a38aadd58"
)


def _aes_cbc_encrypt(key: bytes, iv: bytes, data: bytes) -> bytes:
    try:
        from Crypto.Cipher import AES  # pycryptodome
        return AES.new(key, AES.MODE_CBC, iv).encrypt(data)
    except Exception:  # pragma: no cover - 兜底：纯 Python AES-128-CBC
        return _pure_aes_cbc(key, iv, data)


def _pure_aes_cbc(key: bytes, iv: bytes, data: bytes) -> bytes:
    sbox = [0] * 256
    p = q = 1
    while True:
        p = p ^ ((p << 1) & 0xFF) ^ (0x1B if p & 0x80 else 0)
        q ^= (q << 1) & 0xFF
        q ^= (q << 2) & 0xFF
        q ^= (q << 4) & 0xFF
        if q & 0x80:
            q ^= 0x09
        x = q ^ ((q << 1) | (q >> 7)) ^ ((q << 2) | (q >> 6)) ^ ((q << 3) | (q >> 5)) ^ ((q << 4) | (q >> 4))
        sbox[p] = (x ^ 0x63) & 0xFF
        if p == 1:
            break
    sbox[0] = 0x63
    rcon = [0x01, 0x02, 0x04, 0x08, 0x10, 0x20, 0x40, 0x80, 0x1B, 0x36]

    def xt(a: int) -> int:
        return ((a << 1) ^ (0x1B if a & 0x80 else 0)) & 0xFF

    def expand(k: bytes):
        w = [list(k[i * 4:i * 4 + 4]) for i in range(4)]
        for i in range(4, 44):
            t = list(w[i - 1])
            if i % 4 == 0:
                t = t[1:] + t[:1]
                t = [sbox[b] for b in t]
                t[0] ^= rcon[i // 4 - 1]
            w.append([w[i - 4][j] ^ t[j] for j in range(4)])
        return w

    def enc_block(block: bytes, w) -> bytes:
        s = [list(block[i * 4:i * 4 + 4]) for i in range(4)]
        for r in range(4):
            for c in range(4):
                s[r][c] ^= w[r][c]
        for rnd in range(1, 11):
            s = [[sbox[s[r][c]] for c in range(4)] for r in range(4)]
            s = [[s[r][(c + r) % 4] for c in range(4)] for r in range(4)]
            if rnd != 10:
                for c in range(4):
                    a = [s[r][c] for r in range(4)]
                    t = a[0] ^ a[1] ^ a[2] ^ a[3]
                    s[0][c] = a[0] ^ t ^ xt(a[0] ^ a[1])
                    s[1][c] = a[1] ^ t ^ xt(a[1] ^ a[2])
                    s[2][c] = a[2] ^ t ^ xt(a[2] ^ a[3])
                    s[3][c] = a[3] ^ t ^ xt(a[3] ^ a[0])
            w2 = w[rnd * 4:rnd * 4 + 4]
            for r in range(4):
                for c in range(4):
                    s[r][c] ^= w2[r][c]
        return bytes(b for row in s for b in row)

    w = expand(key)
    out = bytearray()
    prev = iv
    for i in range(0, len(data), 16):
        block = bytes(a ^ b for a, b in zip(data[i:i + 16], prev))
        prev = enc_block(block, w)
        out += prev
    return bytes(out)


def tt_encrypt(data: bytes, rnd: bytes | None = None) -> bytes:
    """``libEncryptor.so`` 的 ttEncrypt：

    ``74 63 05 10 00 00 || rnd[32] || AES-128-CBC(key, iv, PKCS7(SHA512(data) || data))``
    其中 ``key||iv = SHA512(SHA512(rnd) || K)``，K 为 .rodata 常量（见 hgvendor）。
    """
    rnd = rnd if rnd is not None else bytes(random.getrandbits(8) for _ in range(32))
    if len(rnd) != 32:
        raise ValueError("rnd 必须是 32 字节")
    d1 = digest("sha512", rnd)
    d2 = digest("sha512", d1 + _KDF_SALT)
    key, iv = d2[:16], d2[16:32]
    body = digest("sha512", data) + data
    pad = 16 - (len(body) % 16)
    body += bytes([pad]) * pad
    return _MAGIC + rnd + _aes_cbc_encrypt(key, iv, body)


def ss_queries_plain(query: dict) -> str | None:
    parts = ["%s=%s" % (k, query[k]) for k in SS_QUERY_KEYS if query.get(k)]
    return "&".join(parts) if parts else None


SS_QUERY_KEYS = ["device_id", "device_type", "device_brand", "uuid", "openudid"]


# --------------------------------------------------------------------------- gorgon 分支


def _get_iv(initial: int, data: bytes) -> int:
    value = u32(initial)
    for i, byte in enumerate(data):
        if i & 1 == 0:
            value = u32((value >> 4) ^ value ^ (value << 6) ^ byte)
        else:
            value = u32(_inv32(u32((value >> 7) ^ value ^ (byte | (value << 12)))))
    return value


def _iv(query: bytes, body: bytes, ts: int) -> int:
    return _get_iv(_get_iv(_get_iv(0x20230928, query), body), le32(ts))


def branch(query: str, body: bytes | None, ts: int) -> int:
    return (_iv(sm3(query.encode()), digest("md5", body) if body else bytes(16), ts) & 15) % 3


def _sum_md5(data: bytes) -> int:
    check = 0x20220420
    for i in range(12):
        temp = (check >> (3 if i & 1 == 0 else 5)) ^ check
        check = (data[i] ^ (check << 7)) if i & 1 == 0 else _inv32(data[i] | (check << 11))
        check = u32(check ^ temp)
    return i32(u32(u32(check | 4) ^ 0x1000000))


def _hash_f13(query: bytes, body: bytes, ts: int) -> bytes:
    raw = _iv(query, body, ts)
    index = (raw & 15) // 3
    if (raw & 15) % 3 != 0:
        raise ValueError("hash 分支不满足 (iv & 15) % 3 == 0")
    iv_v1 = [0xc4a78580, 0xb3c0fd39, 0xc58c5686, 0xc9aa3ba7, 0xf5a7adf2, 0x963c2ed1][index]
    count = i32(iv_v1 + ts)
    tt = [ror(v, (count + 1) & 255) for v in hgvendor.HASH_TABLE]
    shift = (count + 2) & 7
    pad = bytes(((v | (v << 8)) >> shift) & 0xFF for v in (0xFA, 0x45, 0x61, 0xD7))
    data = query + body + le32(ts) + pad + bytes.fromhex("00000000000001a0")
    di = [i32at(data, i) for i in range(0, len(data), 4)]
    for i in range(112):
        x, y = di[i + 1], di[i + 14]
        di.append(i32(di[i] + di[i + 9] + (rol(x, 14) ^ rol(x, 25) ^ (u32(x) >> 3))
                      + (rol(y, 13) ^ rol(y, 15) ^ (u32(y) >> 10))))
    initial = [ror(v, count) for v in (0x7aba4fc8, 0x67166507, 0x6403fa00, 0x340f512f, 984304912, 3005047866, 2874125293, 2152413264)]
    chosen = [
        [101, 5, 7, 6, 3, 2, 1, 0, 5, 4, 3],
        [96, 0, 6, 7, 5, 3, 2, 1, 5, 4, 4],
        [96, 7, 6, 2, 1, 4, 0, 5, 4, 3, 5],
        [99, 3, 6, 2, 4, 5, 1, 0, 0, 7, 6],
        [96, 0, 5, 6, 7, 3, 1, 2, 5, 4, 4],
        [100, 2, 0, 3, 5, 4, 6, 7, 2, 1, 5],
    ][index]
    d = list(initial)
    for i in range(chosen[0]):
        base = iv_v1 + i
        n1 = ((d[chosen[3]] ^ d[chosen[4]]) & d[chosen[1]]) ^ d[chosen[3]]
        n2 = rol(d[chosen[1]], 26) ^ rol(d[chosen[1]], 21) ^ rol(d[chosen[1]], 7)
        n4 = i32(di[base & 127] + n1 + n2 + tt[base & 63] + d[chosen[5]])
        n5 = rol(d[chosen[2]], 30) ^ rol(d[chosen[2]], 19) ^ rol(d[chosen[2]], 10)
        n6 = (d[chosen[2]] & d[chosen[6]]) | ((d[chosen[2]] | d[chosen[6]]) & d[chosen[7]])
        old = d[chosen[9]]
        d.insert(0, d.pop(7))
        d[chosen[10]] = i32(n5 + n6 + n4)
        d[chosen[8]] = i32(old + n4)
    ret = b"".join(be32(i32(d[i] + initial[i])) for i in range(8))
    folded = bytes(a ^ b for a, b in zip(ret[:16], ret[16:32]))
    return folded + le32(_sum_md5(folded))


def gorgon(query: str, body: bytes | None, ts: int, rnd16: int) -> str:
    head = digest("md5", query.encode())[:4] + (digest("md5", body)[:4] if body else bytes(4)) + bytes(4)
    payload = head + le32(67503104) + be32(ts)
    key = [0x4A, 0x40, 0x16, (rnd16 >> 8) & 0xFF, 0x47, 0x6C, 1, rnd16 & 0xFF]
    s = list(range(256))
    j = 0
    for i in range(256):
        j = (j + s[i] + key[i % 8]) & 255
        # Kotlin 实现只把 s[j] 写回 s[i]，没有把旧 s[i] 写回 s[j]。
        s[i] = s[j]
    out = bytearray(len(payload))
    j = 0
    for n in range(len(payload)):
        i = n + 1
        j = (j + s[i]) & 255
        y = s[j & 255]
        s[i] = y
        out[n] = payload[n] ^ s[(y + y) & 255]
    for i in range(len(out)):
        value = ((out[i] >> 4) | (out[i] << 4)) & 255
        nxt = out[i + 1] if i + 1 < len(out) else out[0]
        reversed_bits = int(format((nxt ^ value) & 255, "08b")[::-1], 2)
        out[i] = _inv32(reversed_bits ^ 20) & 255
    return (bytes.fromhex("8404") + bytes([rnd16 & 255, (rnd16 >> 8) & 255, 0x40, 1]) + bytes(out)).hex()


# --------------------------------------------------------------------------- 其它头


def _xmxor(data: bytes, key: bytes) -> bytes:
    encoded = [0] * len(data)
    for i in range(len(data)):
        at = (i * 4) & 28
        d0, d1, v = key[at], key[at + 1], data[i]
        d2 = ((((v << 4) | (v >> 4)) & 255) + d0) & 255
        d2 = (_inv32(d2) ^ d1) & 255
        d2 = (((d2 & 255) << 3) | ((d2 & 255) >> 5)) & 255
        d2 = (d2 + d1) & 255
        d2 = (d2 ^ d0) & 255
        encoded[len(data) - i - 1] = _inv32(d2) & 255
    last = encoded[-1] ^ encoded[-2]
    first = encoded[0]
    encoded[0] = (_inv32(last) + first) & 255
    encoded[1] = ((encoded[0] ^ encoded[-1] ^ 254) + encoded[1]) & 255
    encoded[2] = (encoded[2] + ((last - first) ^ (((encoded[1] << 3) | (encoded[1] >> 5)) & 255) ^ 2)) & 255
    for i in range(len(encoded) - 4):
        temp = ((((encoded[i + 2] << 3) | (encoded[i + 2] >> 5)) & 255) ^ encoded[i + 1] ^ (i + 3)) & 255
        encoded[i + 3] = (_inv32(temp) + encoded[i + 3]) & 255
    encoded[-1] = encoded[-1] ^ encoded[-2]
    encoded[0] = ((encoded[0] ^ encoded[1]) + sum(encoded[1:])) & 255
    return bytes(b & 255 for b in encoded)


class AesV3:
    """红果自定义分组变换（不是标准 AES），AesV3.kt 的移植。"""

    def __init__(self, key: bytes, timestamp: int):
        self.word = timestamp & 3
        self.box = [b for b in hgvendor.SBOX_BYTES[self.word * 256:(self.word + 1) * 256]]
        self.con = [[1, 0, 2, 3], [2, 0, 3, 1], [0, 1, 3, 2], [1, 0, 2, 3]][self.word]
        self.order = [
            [0, 9, 14, 11, 4, 13, 2, 7, 8, 1, 6, 15, 12, 5, 10, 3],
            [0, 9, 14, 15, 4, 13, 2, 7, 8, 1, 6, 3, 12, 5, 10, 11],
            [0, 9, 14, 7, 4, 13, 2, 11, 8, 1, 6, 3, 12, 5, 10, 15],
            [0, 9, 14, 11, 4, 13, 2, 7, 8, 1, 6, 15, 12, 5, 10, 3],
        ][self.word]
        initial = [0xCA025DDC, 0x823DC546, 0xC9420583, 0xC298225F][self.word]
        seed = le32(initial) * 4
        expanded = [b for b in bytes(a ^ b for a, b in zip(seed, key)) + bytes(32)]
        rounds = 8
        for i in range(4, 12):
            at = 4 * (i - 1)
            a, b, c, d = expanded[at], expanded[at + 1], expanded[at + 2], expanded[at + 3]
            if i & 3 == 0:
                t = ((initial >> (rounds & 24)) ^ self.box[b]) & 255
                b, c, d, a = self.box[c], self.box[d], self.box[a], t
            rounds += 2
            expanded[at + 4] = a ^ expanded[at - 12]
            expanded[at + 5] = b ^ expanded[at - 11]
            expanded[at + 6] = c ^ expanded[at - 10]
            expanded[at + 7] = d ^ expanded[at - 9]
        self.keys = [expanded[i * 4:i * 4 + 4] for i in range(12)]

    def _block(self, value: bytes) -> bytes:
        rows = [[value[i * 4 + j] for j in range(4)] for i in range(4)]

        def add_con(offset: int):
            for i in range(4):
                for j in range(4):
                    rows[i][j] ^= self.keys[offset + i][self.con[j]]

        add_con(0)
        for rnd in range(1, 3):
            for i in range(4):
                for j in range(4):
                    rows[i][j] = self.box[rows[i][j]]
            before = [r[:] for r in rows]
            for i in range(4):
                rows[i] = before[self.con[i]][:]
            flat = [v for row in rows for v in row]
            for i in range(16):
                rows[i // 4][i % 4] = flat[self.order[i]]
            if rnd == 1:
                for i in range(4):
                    old = rows[i][:]
                    rows[i] = [old[self.con[j]] for j in range(4)]

                def xt(x: int) -> int:
                    return ((x << 1) ^ (0x1B if x & 0x80 else 0)) & 255

                for i in range(4):
                    t = rows[0][i] ^ rows[1][i] ^ rows[2][i] ^ rows[3][i]
                    u = rows[0][i]
                    rows[0][i] ^= t ^ xt(rows[0][i] ^ rows[1][i])
                    rows[1][i] ^= t ^ xt(rows[1][i] ^ rows[2][i])
                    rows[2][i] ^= t ^ xt(rows[2][i] ^ rows[3][i])
                    rows[3][i] ^= t ^ xt(rows[3][i] ^ u)
            add_con(rnd * 4)
        for i in range(4):
            for j in range(4):
                rows[i][j] ^= self.keys[4 + i][j]
        return bytes(v & 255 for row in rows for v in row)

    def encrypt(self, source: bytes, iv: bytes) -> bytes:
        if len(source) < 248:
            raise ValueError("AesV3 明文至少 248 字节（当前 %d）" % len(source))
        plain = bytearray(32)
        for i in range(31):
            at = i * 8
            plain[i] = (((source[at] >> 4) & 2) | (source[at + 1] & 64) | ((source[at + 2] >> 2) & 1)
                        | ((source[at + 3] << 3) & 128) | ((source[at + 4] >> 1) & 4) | ((source[at + 5] << 3) & 16)
                        | ((source[at + 6] << 5) & 32) | ((source[at + 7] >> 4) & 8)) & 255
        plain[31] = 1
        first = self._block(bytes(a ^ b for a, b in zip(plain[:16], iv)))
        key = first + self._block(bytes(a ^ b for a, b in zip(plain[16:32], first)))
        out = bytearray(source)
        for i in range(31):
            at = i * 8
            k = key[i]
            out[at] = (out[at] & 0xDF) | ((k << 4) & 32)
            out[at + 1] = (out[at + 1] & 0xBF) | (k & 64)
            out[at + 2] = (out[at + 2] & 0xFB) | ((k << 2) & 4)
            out[at + 3] = (out[at + 3] & 0xEF) | ((k >> 3) & 16)
            out[at + 4] = (out[at + 4] & 0xF7) | ((k + k) & 8)
            out[at + 5] = (out[at + 5] & 0xFD) | ((k >> 3) & 2)
            out[at + 6] = (out[at + 6] & 0xFE) | ((k >> 5) & 1)
            out[at + 7] = (out[at + 7] & 0x7F) | ((k << 4) & 128)
        return bytes([key[-1]]) + bytes(out)


def _varint(value: int) -> bytes:
    n = value
    out = bytearray()
    while n > 127:
        out.append((n & 127) | 128)
        n >>= 7
    out.append(n)
    return bytes(out)


def _proto(fields) -> bytes:
    out = bytearray()
    for field in fields:
        tag, value = field[0], field[1]
        kind = field[2] if len(field) > 2 else "string"
        if kind == "sint":
            n = int(value)
            out += _varint(tag << 3)
            out += _varint((-n * 2 - 1) if n < 0 else n * 2)
        elif kind == "float":
            out += _varint((tag << 3) | 5)
            out += struct.pack("<f", float(value))
        else:
            raw = value if isinstance(value, bytes) else str(value).encode()
            if raw:
                out += _varint((tag << 3) | 2)
                out += _varint(len(raw))
                out += raw
    return bytes(out)


def _device_proto(device: dict) -> bytes:
    f = lambda tag, value, kind="string": (tag, value, kind)
    return _proto([
        f(1, 1, "sint"), f(2, 2, "sint"), f(3, "8662"), f(4, device.get("device_id", "")),
        f(5, "Ai6svO3PyrwDOUSmO6ZcResxu"), f(6, "!noperm!"), f(7, -888888, "sint"), f(8, -888888, "sint"),
        f(9, 3, "sint"), f(10, -888888, "sint"), f(11, "!notset!"), f(12, "Asia/Shanghai,8"), f(13, "zh_CN"),
        f(14, 4, "sint"), f(16, 255.24993896484375, "float"), f(17, 35.58599090576172, "float"),
        f(18, 3.467449188232422, "float"), f(19, 3.467449188232422, "float"), f(20, 255.1754913330078, "float"),
        f(21, 42.17544174194336, "float"), f(22, "16"), f(23, 41, "sint"), f(24, 36, "sint"),
        f(25, 1728388016635, "sint"), f(26, 1728388016635, "sint"), f(27, 1728388016635, "sint"),
        f(28, 1728388016637, "sint"), f(29, -1, "sint"), f(30, "25053RT47C"), f(31, "Redmi"),
        f(32, "25053RT47C"), f(33, "25053RT47C"), f(34, "Xiaomi"), f(35, "Redmi"), f(36, "Redmi"),
        f(38, 31, "sint"),
    ])


class Signer:
    def __init__(self, device: dict | None = None, clock=None, rng=None):
        self.device = dict(device or hgvendor.DEVICE)
        self.clock = clock or (lambda: int(time.time() * 1000))
        self.rng = rng or random.random

    def rand(self) -> int:
        return i32(int(self.rng() * 4294967296.0))

    def medusa(self, url: str, body: bytes | None, ts: int) -> str:
        body_md5 = digest("md5", body) if body else bytes(16)
        query = url.split("?", 1)[1].encode() if "?" in url else b""
        query_sm3 = sm3(query)
        query_body_ts = _hash_f13(query_sm3, body_md5, ts)
        nested = _proto([(1, 111, "sint"), (2, 10, "sint"), (3, 694367, "sint"), (5, 586952199, "sint")])
        message_rand = u32(self.rand())
        env_launch = int(self.rng() * 21) + 100
        env_pid = int(self.rng() * 2000) + 10001
        version = self.device.get("version_name", "7.2.2.32")
        env = _proto([
            (1, env_launch, "sint"), (2, 146331399, "sint"), (3, 146331396, "sint"), (5, 7, "sint"),
            (6, "v04.06.04.03-bugfix"), (7, env_pid, "sint"), (12, _device_proto(self.device)),
            (13, _proto([(1, self.clock() // 1000, "sint"), (2, -2, "sint"), (4, 200, "sint")])),
            (14, version),
        ])
        query_hash = sm3(query + body_md5 + b"none")
        message = _proto([
            (1, bytes.fromhex("f7e85ffad7d7dc3bd62ac87057cf6118")), (2, 3, "sint"), (3, message_rand, "sint"),
            (4, "8662"), (5, self.device.get("device_id", "")), (6, "1588093228"), (7, version),
            (8, "v04.06.04-ml-android"), (9, 67503104, "sint"), (10, bytes.fromhex("4001000000000000")),
            (12, ts, "sint"), (13, query_body_ts), (14, query_sm3[:6]), (15, nested),
            (16, "AXYQOS6n2m60x1fVZHIrH3iol"), (17, ts, "sint"), (19, query_hash), (20, "none"),
            (21, 312, "sint"), (23, env),
            (24, '{"cmr":16777216,"cmr2":16777216,"un_h":1879194040,"vpn":0,"kd":0,"fkd":3672518972,'
                 '"pd":-1872573247,"dyn":"","do":0,"tk":true}'),
        ])
        r = self.rand()
        sign_key = bytes.fromhex("8ebdfa3806ecc5cee79423e6029ed82540bc2218bb7eaef71cb691f7aa8aa2f5")
        h = sm3(sign_key + le32(r) + sign_key)
        d1 = (r >> 16) & 255
        seed = le32(_inv32(u32(((d1 << 11) | (u32(r) >> 24)) ^ (d1 >> 5) ^ d1)))
        transformed = bytearray((bytes.fromhex("4001000000000000") + _xmxor(message, h))[::-1])
        for i in range(len(transformed)):
            transformed[i] ^= seed[3 - (i & 3)]
        check = ((query_sm3[0] & 63) << 14) | 0x18000001 | ((query_body_ts[0] & 63) << 8)
        packed = (b"\x35" + le32(self.rand()) + le32(check) + bytes(transformed)
                  + bytes([(r >> 16) & 255, (r >> 24) & 255]))
        encrypted = AesV3(bytes.fromhex("f1593376766ea98d34f31b057a9d5be4"), ts).encrypt(
            packed, bytes.fromhex("1fe109a4125283f418de9e051a969e12"))
        version_bytes = bytes.fromhex("03000000f7e85ffad7d7dc3bd62ac87057cf6118")
        prefix = b"".join(le32(i32at(version_bytes, i, True) ^ ts) for i in range(0, 20, 4))
        return base64.b64encode(prefix + bytes([r & 255, (r >> 8) & 255, 0, 1]) + encrypted).decode()

    def helios(self, ts: int) -> str:
        rnd = self.rand()
        ascii_hex = digest("md5", le32(rnd) + b"8662").hex().encode()
        words = [i64at(ascii_hex, i * 8, True) for i in range(4)]
        table = [words[0]]
        b0 = words.pop(0)
        b8 = words.pop(0)
        for i in range(34):
            x8 = i64(ror64(b8, 8) + b0) ^ i
            words.append(x8)
            x8 = x8 ^ ror64(b0, 61)
            table.append(x8)
            b0 = x8
            b8 = words.pop(0)
        text = ("%d-1588093228-8662" % ts).encode()
        padding = 16 - len(text) % 16
        data = text + bytes([padding]) * padding
        output = bytearray(le32(rnd))
        for at in range(0, len(data), 16):
            a = i64at(data, at, True)
            b = i64at(data, at + 8, True)
            for i in range(34):
                b = table[i] ^ (a + ror64(b, 8))
                a = b ^ ror64(a, 61)
            output += struct.pack("<q", i64(a)) + struct.pack("<q", i64(b))
        return base64.b64encode(bytes(output)).decode()

    def common_values(self, extra=None, app_version: bool = True) -> dict:
        values = dict(self.device)
        if app_version:
            for key in ("version_code", "manifest_version_code", "update_version_code", "pv_player"):
                values[key] = "72232"
            values["version_name"] = "7.2.2.32"
        values.setdefault("openudid", "0123456789abcdef")
        values.setdefault("uuid", values.get("device_id", ""))
        values["_rticket"] = str(self.clock())
        if extra:
            values.update({k: v for k, v in extra.items() if v is not None})
        return values

    def sign(self, base: str, values: dict, body: bytes | None = None, mode: str = "hg",
             extra_headers: dict | None = None):
        """返回 dict(url, headers, body)。

        mode: ``hg`` = gorgon 分支模型（当前 APK 在线上可用的那套）；
              ``ss`` = hgApi 的 ss_queries 模型。
        """
        now = self.clock()
        ts = i32(now // 1000)
        values = dict(values)
        headers = {}
        if mode == "ss":
            plain = ss_queries_plain(values)
            if plain:
                blob = base64.b64encode(tt_encrypt(plain.encode())).decode()
                headers["X-SS-QUERIES"] = urllib.parse.quote(blob, safe="")
                values = {k: v for k, v in values.items() if k not in SS_QUERY_KEYS}
                values = {"ss_queries": blob, **values}
        ticket = now
        query = ""
        for offset in range(128):
            ticket = now + offset
            candidate = dict(values)
            candidate["ts"] = str(ts)
            candidate["_rticket"] = str(ticket)
            query = build_query(candidate)
            if branch(query, body, ts) == 0:
                break
        else:
            raise RuntimeError("128 次试探都没有命中签名分支")
        rnd16 = int(self.rng() * 65536)
        headers.update({
            "User-Agent": hgvendor.VIDEO_UA if body is not None else hgvendor.APP_UA,
            "Accept": "application/json; charset=utf-8,application/x-protobuf",
            "x-ss-req-ticket": str(ticket),
            "x-tt-request-tag": "t=0;n=0",
            "sdk-version": "2",
            "passport-sdk-version": "50561",
            "x-xs-from-web": "0",
            "x-khronos": str(ts),
            "x-ladon": base64.b64encode(be32(ts)).decode(),
            "x-argus": base64.b64encode(le32(ts)).decode(),
            "x-gorgon": gorgon(query, body, ts, rnd16),
            "x-helios": self.helios(ts),
            "x-medusa": self.medusa(base + "?" + query, body, ts),
            "x-tt-dt": "",
        })
        if body is not None:
            headers["Content-Type"] = "application/json; charset=UTF-8"
            headers["x-vc-bdturing-sdk-version"] = "3.7.2.cn"
            headers["x-ss-stub"] = digest("md5", body).hex().upper()
        if extra_headers:
            headers.update(extra_headers)
        return {"method": "POST" if body is not None else "GET", "url": base + "?" + query,
                "headers": headers, "body": body}


def ror64(v: int, n: int) -> int:
    v &= 0xFFFFFFFFFFFFFFFF
    return ((v >> n) | (v << (64 - n))) & 0xFFFFFFFFFFFFFFFF


def i64(v: int) -> int:
    v &= 0xFFFFFFFFFFFFFFFF
    return v - 0x10000000000000000 if v >= 0x8000000000000000 else v


if __name__ == "__main__":  # 自检：与 Kotlin 侧的固定输入对照
    s = Signer(clock=lambda: 1728388016637, rng=lambda: 0.5)
    print("branch:", branch("a=1&ts=1&_rticket=1", None, 1))
    print("gorgon:", gorgon("a=1&ts=1&_rticket=1", None, 1728388016, 0x1234))
    print("helios:", s.helios(1728388016))
    print("tt_encrypt:", tt_encrypt(b"device_id=1&device_type=2", bytes(range(32))).hex()[:64], "...")
    req = s.sign("https://api5-normal-sinfonlinea.fqnovel.com/reading/bookapi/search/tab/v1/",
                 s.common_values({"query": "斗罗大陆", "count": "20", "offset": "0", "user_is_login": "0"}))
    print("url:", req["url"][:180])
    print("medusa len:", len(req["headers"]["x-medusa"]))
