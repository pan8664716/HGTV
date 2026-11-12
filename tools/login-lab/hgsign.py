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
import secrets
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

    def expand(k: bytes) -> bytes:
        words = [list(k[i:i + 4]) for i in range(0, 16, 4)]
        for i in range(4, 44):
            temp = list(words[i - 1])
            if i % 4 == 0:
                temp = [sbox[temp[1]], sbox[temp[2]], sbox[temp[3]], sbox[temp[0]]]
                temp[0] ^= rcon[i // 4 - 1]
            words.append([words[i - 4][j] ^ temp[j] for j in range(4)])
        return bytes(value for word in words for value in word)

    def shift_rows(state: list[int]) -> None:
        state[1], state[5], state[9], state[13] = (
            state[5], state[9], state[13], state[1],
        )
        state[2], state[6], state[10], state[14] = (
            state[10], state[14], state[2], state[6],
        )
        state[3], state[7], state[11], state[15] = (
            state[15], state[3], state[7], state[11],
        )

    def enc_block(block: bytes, round_keys: bytes) -> bytes:
        state = list(block)
        for i in range(16):
            state[i] ^= round_keys[i]
        for round_index in range(1, 11):
            state = [sbox[value] for value in state]
            shift_rows(state)
            if round_index != 10:
                for column in range(4):
                    at = column * 4
                    a0, a1, a2, a3 = state[at:at + 4]
                    state[at] = xt(a0) ^ xt(a1) ^ a1 ^ a2 ^ a3
                    state[at + 1] = a0 ^ xt(a1) ^ xt(a2) ^ a2 ^ a3
                    state[at + 2] = a0 ^ a1 ^ xt(a2) ^ xt(a3) ^ a3
                    state[at + 3] = xt(a0) ^ a0 ^ a1 ^ a2 ^ xt(a3)
            offset = round_index * 16
            for i in range(16):
                state[i] ^= round_keys[offset + i]
        return bytes(state)

    round_keys = expand(key)
    out = bytearray()
    prev = iv
    for i in range(0, len(data), 16):
        block = bytes(a ^ b for a, b in zip(data[i:i + 16], prev))
        prev = enc_block(block, round_keys)
        out += prev
    return bytes(out)


def tt_encrypt(data: bytes, rnd: bytes | None = None) -> bytes:
    """``libEncryptor.so`` 的 ttEncrypt：

    ``74 63 05 10 00 00 || rnd[32] || AES-128-CBC(key, iv, PKCS7(SHA512(data) || data))``
    其中 ``key||iv = SHA512(SHA512(rnd) || K)``，K 为 .rodata 常量（见 hgvendor）。
    """
    rnd = rnd if rnd is not None else secrets.token_bytes(32)
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


def fyworld_branch(query: str, body: bytes | None, ts: int) -> int:
    """Exact fyworld Signer.branch() selection rule."""
    low = _iv(sm3(query.encode()), digest("md5", body) if body else bytes(16), ts) & 15
    return low - ((low * 171) >> 9) * 3


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


_BRANCH2_SV = [
    0xA7AEFE20, 0x7149F1D6, 0x47E4CA07, 0xE9B58F67, 0x93B924DE, 0xC614D0F5, 0x38AFE0EF, 0xB2BBAD73,
    0xE24444C3, 0x9D3AEC9B, 0xDF7B37E4, 0xD8B16D40, 0xF8AC31B8, 0x76B9A90B, 0x31D833EE, 0x953FCE64,
    0x353595A4, 0x4609C13B, 0x36925008, 0x8C6D0925, 0x5DF5C177, 0x1CFBF52B, 0x8A4FA7F0, 0x114CA35E,
    0x8193F984, 0x7A7A8733, 0x316AB4D5, 0x3C20CFC9, 0xA6D84453, 0x3A18500C, 0x798EC47A, 0x97A76B28,
    0x66C4FF96, 0x51716443, 0x0DD2FC3B, 0xB5696DA7, 0xBBEB3AC5, 0x5C53D204, 0xD32608CE, 0x7279B9EC,
    0xF4188ECF, 0xF7D793DB, 0x332CC491, 0xAB76AE15, 0x9BEBE727, 0x18A01384, 0x5BE9F8A7, 0x5F90A754,
    0x39B663C0, 0x36673C83, 0x7C92F514, 0x9D7D94D7, 0xE2E8D9AA, 0x5F7E9EA9, 0x7ABD4551, 0x569E05DA,
    0x40A25632, 0x3DF5A9A5, 0xBAB37D80, 0x454286DC, 0x3F5D4E78, 0x03D7B75D, 0xB1FE4AF7, 0xA5AB26A3,
]
_BRANCH2_N1 = [0x8980F29B, 0xEB549C7F, 0xB08726DB, 0xD40CB5E6, 0xE8F559E4]
_BRANCH2_SEED = bytes.fromhex("8496779dd4150bf8")
_BRANCH2_ORDERS = bytes.fromhex(
    "0f0704000908030a060b050d0e010c02"
    "0f05080c0009020103070e060b0a0d04"
    "060500070c000a04080f010b0d09020e"
    "060b02050403080101070a000d0c090e"
    "0d070e0f0b0208030c0509010004060a"
    "0d0902060f0b0a040807000c0503010e"
    "0c090f07060f030e020d0405010b0a00"
    "0c050a090e0802040407030f01060b00"
    "0b0f0408020a0700090d06010e03050c"
    "0b060a0508020c03070f0e090d000104"
    "0906080f050800040a0b030d01020c0e"
    "090d0c0604070a03030f00080105020e"
    "01000d0f090a0b0e0402080703060c05"
    "01080a0c0f0905060b000304020e070d"
    "04080f000c0f0e0d0a01060207090503"
    "040205080d0b0a0606000e0f070c0903"
    "0a08040f000b01060d0c0709030e0502"
    "0a070b050f00020e0108030d0c060904"
    "0d070f08050f06040b0a0e0c09000203"
    "0d0c020704010b0e0e08060f09050003"
)


def _md5_sum_v3(message: bytes, count_v2: int, orders: bytes, count_v1: int) -> bytes:
    sv = [ror(value, count_v1) for value in _BRANCH2_SV]
    start = [ror(value, count_v2) for value in (0x79E0F2FB, 0xC8B52570, 0xEBC2F8CD, 0x7C104D93)]
    end_count = (count_v2 + 6) & 255
    end = [ror(value, end_count) for value in (0x19BE4866, 0xE85986B4, 0xE19B326E, 0x71D1D7D4)]
    words = [i32at(message, i * 4, True) for i in range(16)]
    a, b, c, d = start
    shifts = (
        (7, 12, 17, 22, 7, 12, 17, 22, 7, 12, 17, 22, 7, 12, 17, 22),
        (5, 9, 14, 20, 5, 9, 14, 20, 5, 9, 14, 20, 5, 9, 14, 20),
        (4, 11, 16, 23, 4, 11, 16, 23, 4, 11, 16, 23, 4, 11, 16, 23),
        (6, 10, 15, 21, 6, 10, 15, 21, 6, 10, 15, 21, 6, 10, 15, 21),
    )
    for kind in range(4):
        for i in range(16):
            phase = i & 3
            order = orders[kind * 16 + i] if kind * 16 + i < len(orders) else 0
            mm = words[order & 255]
            cc = sv[kind * 16 + i]
            f = (
                (b & c) | (_inv32(b) & d) if kind == 0 else
                (b & d) | (c & _inv32(d)) if kind == 1 else
                b ^ c ^ d if kind == 2 else
                c ^ (b | _inv32(d))
            )
            step = i32(b + rol(i32(a + f + mm + cc), shifts[kind][i]))
            if phase == 0:
                a = step
            elif phase == 1:
                d = step
            elif phase == 2:
                c = step
            else:
                b = step
    ret = b"".join(le32(i32((start[i] + value) ^ end[i])) for i, value in enumerate((a, b, c, d)))
    return ret + le32(_sum_md5(ret))


def _fyworld_branch2_f13(iv_value: int, query: bytes, body: bytes, ts: int) -> bytes:
    v = (iv_value & 13) * 86
    group = ((v >> 15) & 255) + ((v >> 8) & 255)
    group = min(group, len(_BRANCH2_N1) - 1)
    n1 = _BRANCH2_N1[group]
    count_v1 = (n1 + ts + 1) & 255
    count_v2 = i32(n1 + ts)
    shift = (count_v2 + 5) & 7
    pad = bytes(((value | (value << 8)) >> shift) & 255 for value in _BRANCH2_SEED)
    message = query + body + le32(ts) + pad + bytes.fromhex("a0010000")
    start = (group << 6)
    orders = _BRANCH2_ORDERS[start:start + 64]
    return _md5_sum_v3(message, count_v2, orders, count_v1)


def _fyworld_hash_f13(query: bytes, body: bytes, ts: int) -> bytes:
    raw = _iv(query, body, ts)
    low = raw & 15
    group = (low * 171) >> 9
    branch_value = low - group * 3
    if branch_value == 2:
        return _fyworld_branch2_f13(raw, query, body, ts)
    if branch_value != 0:
        return query[:16] + body + le32(_sum_md5(query + body))
    return _hash_f13(query, body, ts)


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


def gorgon_aligned(query: str, body: bytes | None, ts: int, rnd16: int) -> str:
    """官方 74332 的 x-gorgon 前缀形态。

    真机观测：``8404 + rnd16 + detection flag``。稳定态 flag = ``00 81``
    （detection 掩码 0x8100 的低 16 位，与 x-medusa field 10 / Prefix 同源）；
    冷启动阶段还出现过 ``0000`` / ``0405``。仓库旧实现固定 ``4001``。

    负载层的 native 变换尚未复现（2026-11-11 Frida 对拍：两套已知逆变换与
    0..255 常量扫描均无解），但消融实验证明服务端不校验该负载，因此沿用
    已验证可用的实现，仅对齐前缀。
    """
    raw = bytearray(bytes.fromhex(gorgon(query, body, ts, rnd16)))
    raw[4], raw[5] = 0x00, 0x81
    return bytes(raw).hex()


def real_ladon() -> str:
    """官方 74332 的 x-ladon：4 字节随机值（不是 be32(ts)，也不是 36 字节完整 Ladon）。"""
    return base64.b64encode(secrets.token_bytes(4)).decode()


def real_argus(ts: int) -> str:
    """官方 74332 的 x-argus：base64(le32(ts))。"""
    return base64.b64encode(le32(ts)).decode()


# --------------------------------------------------------------------------- Argus / Ladon


_ARGUS_KEY = bytes.fromhex(
    "ac1adaae95a7af94a5114ab3b3a97dd8"
    "0050aa0a39314c40528caec95256c28c"
)
_ARGUS_ROUND_CONST = 0x3DC94C3A046D678B


def _pb_varint_field(field: int, value: int) -> bytes:
    return _varint(field << 3) + _varint(value)


def _pb_bytes_field(field: int, value: bytes) -> bytes:
    return _varint((field << 3) | 2) + _varint(len(value)) + value


def _pad16(data: bytes) -> bytes:
    padding = 16 - len(data) % 16
    return data + bytes([padding]) * padding


def _rol64(value: int, count: int) -> int:
    count &= 63
    value &= 0xFFFFFFFFFFFFFFFF
    return ((value << count) | (value >> (64 - count))) & 0xFFFFFFFFFFFFFFFF if count else value


def _argus_rounds() -> list[int]:
    seed = _ARGUS_KEY + bytes.fromhex("3ccc567b") + _ARGUS_KEY
    derived = sm3(seed)
    rounds = [struct.unpack_from("<Q", derived, i * 8)[0] for i in range(4)]
    for i in range(4, 72):
        mixed = ror64(rounds[i - 1], 3) ^ rounds[i - 3]
        mixed2 = mixed ^ ror64(mixed, 1)
        bit = (_ARGUS_ROUND_CONST >> ((i - 4) % 62)) & 1
        rounds.append(
            (~rounds[i - 4] ^ mixed2 ^ bit ^ 3) & 0xFFFFFFFFFFFFFFFF
        )
    return rounds


def _argus_key_stream(data: bytes) -> bytes:
    out = bytearray(data)
    rounds = _argus_rounds()
    for offset in range(0, len(out), 16):
        left = struct.unpack_from("<Q", out, offset)[0]
        right = struct.unpack_from("<Q", out, offset + 8)[0]
        for round_key in rounds:
            left, right = (
                right,
                left
                ^ (_rol64(right, 1) & _rol64(right, 8))
                ^ _rol64(right, 2)
                ^ round_key,
            )
            left &= 0xFFFFFFFFFFFFFFFF
            right &= 0xFFFFFFFFFFFFFFFF
        struct.pack_into("<QQ", out, offset, left, right)
    return bytes(out)


def argus(
    query: str,
    body: bytes | None,
    ts: int,
    device: dict,
    nonce: bytes,
) -> str:
    body_hash = sm3(digest("md5", body)) if body else sm3(bytes(16))
    query_hash = sm3(query.encode())
    counters = (
        _pb_varint_field(1, 1)
        + _pb_varint_field(2, 1)
        + _pb_varint_field(3, 1)
        + _pb_varint_field(7, 3348294860)
    )
    env = (
        _pb_bytes_field(1, str(device.get("device_type", "25053RT47C")).encode())
        + _pb_bytes_field(2, str(device.get("os_version", "16")).encode())
        + _pb_bytes_field(3, str(device.get("channel", "update_64")).encode())
        + _pb_varint_field(4, 2147483648)
    )
    message = (
        _pb_varint_field(1, 0x20200929 * 2)
        + _pb_varint_field(2, 2)
        + _pb_varint_field(
            3, struct.unpack_from("<I", nonce, 6)[0] & 0x7FFFFFFF
        )
        + _pb_bytes_field(4, b"8662")
        + _pb_bytes_field(5, str(device.get("device_id", "")).encode())
        + _pb_bytes_field(6, b"1611921764")
        + _pb_bytes_field(7, str(device.get("version_name", "7.3.5.32")).encode())
        + _pb_bytes_field(8, b"v04.07.01-ml-android")
        + _pb_varint_field(9, 135135744)
        + _pb_bytes_field(10, bytes(8))
        + _pb_varint_field(11, 0)
        + _pb_varint_field(12, ts * 2)
        + _pb_bytes_field(13, body_hash[:6])
        + _pb_bytes_field(14, query_hash[:6])
        + _pb_bytes_field(15, counters)
        + _pb_bytes_field(16, b"")
        + _pb_bytes_field(20, b"none")
        + _pb_varint_field(21, 738)
        + _pb_bytes_field(23, env)
        + _pb_varint_field(25, 2)
    )
    stream = bytearray(_argus_key_stream(_pad16(message)))
    mask = bytes.fromhex("d04ffdffd04ffdff")
    for i in range(len(stream)):
        stream[i] ^= mask[i % 8]
    payload = (mask + bytes(stream))[::-1]
    container = (
        bytes.fromhex("a6e783ee7001100918")
        + payload
        + bytes.fromhex("567b")
    )
    encrypted = _aes_cbc_encrypt(
        digest("md5", _ARGUS_KEY[:16]),
        digest("md5", _ARGUS_KEY[16:]),
        _pad16(container),
    )
    return base64.b64encode(b"\x3c\xcc" + encrypted).decode()


def ladon(aid: str, ts: int, prefix: bytes) -> str:
    digest_hex = digest("md5", prefix + aid.encode()).hex().encode()
    left = struct.unpack_from("<Q", digest_hex, 0)[0]
    right = struct.unpack_from("<Q", digest_hex, 8)[0]
    queue = [
        struct.unpack_from("<Q", digest_hex, 16)[0],
        struct.unpack_from("<Q", digest_hex, 24)[0],
    ]
    keys = []
    for i in range(34):
        keys.append(left)
        mixed = ((ror64(right, 8) + left) & 0xFFFFFFFFFFFFFFFF) ^ i
        queue.append(mixed)
        left = mixed ^ _rol64(left, 3)
        right = queue.pop(0)
    out = bytearray(
        _pad16(("%d-1611921764-%s" % (ts, aid)).encode())
    )
    for offset in range(0, len(out), 16):
        lv, rv = struct.unpack_from("<QQ", out, offset)
        for key in keys:
            rv = key ^ ((lv + ror64(rv, 8)) & 0xFFFFFFFFFFFFFFFF)
            lv = rv ^ _rol64(lv, 3)
        struct.pack_into("<QQ", out, offset, lv, rv)
    return base64.b64encode(prefix + bytes(out)).decode()


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


def _fyworld_device_proto(device: dict, process_nonce: str, process_started: int) -> bytes:
    """Fyworld's process-identity fixes, while keeping the real device profile."""
    f = lambda tag, value, kind="string": (tag, value, kind)
    os_api = int(device.get("os_api") or 36)
    return _proto([
        f(1, 1, "sint"), f(2, 2, "sint"), f(3, "8662"),
        f(4, device.get("device_id", "")), f(5, process_nonce),
        f(6, "!noperm!"), f(7, -888888, "sint"), f(8, -888888, "sint"),
        f(9, 3, "sint"), f(10, -888888, "sint"), f(11, "!notset!"),
        f(12, "Asia/Shanghai,8"), f(13, "zh_CN"), f(14, 4, "sint"),
        f(16, 0.0, "float"), f(17, 0.0, "float"), f(18, 0.0, "float"),
        f(19, 0.0, "float"), f(20, 0.0, "float"), f(21, 0.0, "float"),
        f(22, str(device.get("os_version") or "16")), f(23, 41, "sint"),
        f(24, os_api, "sint"),
        f(25, process_started, "sint"), f(26, process_started, "sint"),
        f(27, process_started, "sint"), f(28, process_started, "sint"),
        f(29, -1, "sint"),
        f(30, str(device.get("device_type") or "25053RT47C")),
        f(31, str(device.get("device_brand") or "Redmi")),
        f(32, str(device.get("device_type") or "25053RT47C")),
        f(33, str(device.get("device_type") or "25053RT47C")),
        f(34, str(device.get("device_manufacturer") or device.get("device_brand") or "Xiaomi")),
        f(35, str(device.get("device_brand") or "Redmi")),
        f(36, str(device.get("device_brand") or "Redmi")),
        f(38, 31, "sint"),
    ])


def _fyworld_exact_device_proto(device_id: str, process_nonce: str, process_started: int) -> bytes:
    """Exact fyworld deviceProto: official fixed fields, process identity only."""
    f = lambda tag, value, kind="string": (tag, value, kind)
    return _proto([
        f(1, 1, "sint"), f(2, 2, "sint"), f(3, "8662"), f(4, device_id),
        f(5, process_nonce), f(6, "!noperm!"),
        f(7, -888888, "sint"), f(8, -888888, "sint"), f(9, 3, "sint"),
        f(10, -888888, "sint"), f(11, "!notset!"),
        f(12, "Asia/Shanghai,8"), f(13, "zh_CN"), f(14, 4, "sint"),
        f(16, 0.0, "float"), f(17, 0.0, "float"), f(18, 0.0, "float"),
        f(19, 0.0, "float"), f(20, 0.0, "float"), f(21, 0.0, "float"),
        f(22, "16"), f(23, 41, "sint"), f(24, 36, "sint"),
        f(25, process_started, "sint"), f(26, process_started, "sint"),
        f(27, process_started, "sint"), f(28, process_started, "sint"),
        f(29, -1, "sint"), f(30, "25053RT47C"), f(31, "Redmi"),
        f(32, "25053RT47C"), f(33, "25053RT47C"), f(34, "Xiaomi"),
        f(35, "Redmi"), f(36, "Redmi"), f(38, 31, "sint"),
    ])


class Signer:
    def __init__(
        self,
        device: dict | None = None,
        clock=None,
        rng=None,
        fingerprint: str = "legacy",
        medusa_backend=None,
    ):
        self.device = dict(device or hgvendor.DEVICE)
        self.clock = clock or (lambda: int(time.time() * 1000))
        self.rng = rng or random.random
        self.fingerprint = fingerprint
        self.medusa_backend = medusa_backend
        self.process_started = int(self.clock())
        self.process_nonce = (
            base64.urlsafe_b64encode(secrets.token_bytes(18)).rstrip(b"=").decode("ascii")
            if fingerprint == "fyworld"
            else "AXYQOS6n2m60x1fVZHIrH3iol"
        )

    def rand(self) -> int:
        return i32(int(self.rng() * 4294967296.0))

    def medusa(
        self,
        url: str,
        body: bytes | None,
        ts: int,
        device_id: str | None = None,
        version_name: str | None = None,
        time_ms: int | None = None,
    ) -> str:
        device_id = device_id or self.device.get("device_id", "")
        version_name = version_name or self.device.get("version_name", "7.4.3.32")
        if self.medusa_backend is not None:
            return str(
                self.medusa_backend(
                    url,
                    body,
                    ts,
                    device_id,
                    version_name,
                    time_ms,
                )
            )
        body_md5 = digest("md5", body) if body else bytes(16)
        query = url.split("?", 1)[1].encode() if "?" in url else b""
        query_sm3 = sm3(query)
        query_body_ts = (
            _fyworld_hash_f13(query_sm3, body_md5, ts)
            if self.fingerprint == "fyworld"
            else _hash_f13(query_sm3, body_md5, ts)
        )
        nested = _proto([(1, 111, "sint"), (2, 10, "sint"), (3, 694367, "sint"), (5, 586952199, "sint")])
        message_rand = u32(self.rand())
        env_launch = int(self.rng() * 21) + 100
        env_pid = int(self.rng() * 2000) + 10001
        device_proto = (
            _fyworld_exact_device_proto(device_id, self.process_nonce, self.process_started)
            if self.fingerprint == "fyworld"
            else _device_proto(self.device)
        )
        env = _proto([
            (1, env_launch, "sint"), (2, 146331399, "sint"), (3, 146331396, "sint"), (5, 7, "sint"),
            (6, "v04.06.04.03-bugfix"), (7, env_pid, "sint"), (12, device_proto),
            (13, _proto([(1, self.clock() // 1000, "sint"), (2, -2, "sint"), (4, 200, "sint")])),
            (14, version_name),
        ])
        query_hash = sm3(query + body_md5 + b"none")
        message = _proto([
            (1, bytes.fromhex("2d4b4fca49750d433fb5ae2c226dcc56")), (2, 3, "sint"), (3, message_rand, "sint"),
            (4, "8662"), (5, device_id), (6, "646637794"), (7, version_name),
            (8, "v04.06.04-ml-android"), (9, 67503104, "sint"), (10, bytes.fromhex("4001000000000000")),
            (12, ts, "sint"), (13, query_body_ts), (14, query_sm3[:6]), (15, nested),
            (16, self.process_nonce), (17, ts, "sint"), (19, query_hash), (20, "none"),
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
        # 官方 74332 协议版本 5；2026-11-11 Frida 反推自真机 x-medusa 前 20 字节。
        version_bytes = bytes.fromhex("050000002d4b4fca49750d433fb5ae2c226dcc56")
        prefix = b"".join(le32(i32at(version_bytes, i, True) ^ ts) for i in range(0, 20, 4))
        return base64.b64encode(prefix + bytes([r & 255, (r >> 8) & 255, 0, 1]) + encrypted).decode()

    def helios(self, ts: int, license_id: str = "646637794") -> str:
        mask = 0xFFFFFFFFFFFFFFFF
        seed = u32(self.rand()) & 0x7FFFFFFF
        ascii_hex = digest("md5", le32(seed) + b"8662").hex().encode()
        material = [int.from_bytes(ascii_hex[i:i + 8], "little") for i in range(0, 32, 8)]
        keys = [0] * 34
        left = [0] * 36
        keys[0] = material[0]
        left[:3] = material[1:4]
        for i in range(33):
            left[i + 3] = (((left[i] >> 8) | (left[i] << 56)) + keys[i]) & mask
            left[i + 3] ^= i
            keys[i + 1] = (((keys[i] << 3) | (keys[i] >> 61)) ^ left[i + 3]) & mask
        text = f"{ts}-{license_id}-8662".encode()
        padding = 16 - len(text) % 16
        data = text + bytes([padding]) * padding
        output = bytearray(le32(seed))
        for at in range(0, len(data), 16):
            y = int.from_bytes(data[at:at + 8], "little")
            x = int.from_bytes(data[at + 8:at + 16], "little")
            for key in keys:
                x = ((((x >> 8) | (x << 56)) + y) & mask) ^ key
                y = (((y << 3) | (y >> 61)) ^ x) & mask
            output += le64(y) + le64(x)
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
             extra_headers: dict | None = None, include_medusa: bool = False):
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
            "x-ladon": real_ladon(),
            "x-argus": real_argus(ts),
            "x-gorgon": gorgon_aligned(query, body, ts, rnd16),
            "x-helios": self.helios(
                ts,
                "646637794",
            ),
            "x-tt-dt": "",
        })
        if include_medusa:
            headers["x-medusa"] = self.medusa(
                base + "?" + query,
                body,
                ts,
                self.device.get("device_id", ""),
                self.device.get("version_name", "7.2.2.32"),
                ticket,
            )
        if body is not None:
            headers["Content-Type"] = "application/json; charset=UTF-8"
            headers["x-vc-bdturing-sdk-version"] = "3.7.2.cn"
            headers["x-ss-stub"] = digest("md5", body).hex().upper()
        if extra_headers:
            headers.update(extra_headers)
        return {"method": "POST" if body is not None else "GET", "url": base + "?" + query,
                "headers": headers, "body": body}

    def sign_playback(self, base: str, values: dict, body: bytes | None = None,
                      user_agent: str | None = None, include_medusa: bool = True,
                      include_ts: bool = False):
        """Exact fyworld `signPlayback` behavior: no ts in query."""
        now = self.clock()
        ts = i32(now // 1000)
        query = ""
        ticket = now
        for offset in range(128):
            ticket = now + offset
            candidate = dict(values)
            if include_ts:
                candidate["ts"] = str(ts)
            candidate["_rticket"] = str(ticket)
            query = build_query(candidate)
            if fyworld_branch(query, body, ts) == 0:
                break
        else:
            raise RuntimeError("128 次试探都没有命中 playback 签名分支")
        url = base + "?" + query
        rnd16 = int(self.rng() * 65536)
        helios_value = self.helios(ts, "646637794")
        device_id = str(values.get("device_id") or "0")
        version_name = str(values.get("version_name") or "7.4.3.32")
        medusa_value = (
            self.medusa(url, body, ts, device_id, version_name, ticket)
            if include_medusa
            else None
        )
        headers = {
            "User-Agent": user_agent or hgvendor.VIDEO_UA,
            "Accept": "application/json; charset=utf-8,application/x-protobuf",
            "x-ss-req-ticket": str(ticket),
            "sdk-version": "2",
            "x-xs-from-web": "0",
            "x-khronos": str(ts),
            "x-ladon": real_ladon(),
            "x-argus": real_argus(ts),
            "x-gorgon": gorgon_aligned(query, body, ts, rnd16),
            "x-helios": helios_value,
        }
        if medusa_value is not None:
            headers["x-medusa"] = medusa_value
        if body is not None:
            headers["Content-Type"] = "application/json; charset=utf-8"
            headers["x-ss-stub"] = digest("md5", body).hex().upper()
        return {
            "method": "POST" if body is not None else "GET",
            "url": url,
            "headers": headers,
            "body": body,
        }


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
    print("argus len:", len(req["headers"]["x-argus"]))
    print("ladon len:", len(req["headers"]["x-ladon"]))
    print("medusa:", "x-medusa" in req["headers"])
