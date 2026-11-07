# SPDX-License-Identifier: GPL-3.0-only
"""按官方 App 默认链路探测 device_register。

官方设备标识的生成规则（来自 7.3.9.32 APK）：

* cdid: UUID.randomUUID()
* clientudid: UUID.randomUUID()
* openudid: 优先使用有效的 Settings.Secure.ANDROID_ID；
  没有可用 Android ID 时，生成 BigInteger(80, SecureRandom).toString(16)，
  长度不足 13 位时左侧补 F。

脚本会把三项标识、服务端下发的 DID/IID/token/egdi 和 Cookie 写入 state 文件。
后续运行复用同一组标识；第二次起把已有身份发给服务端。注册请求默认不携带旧 Cookie：
旧 Cookie 应留给后续业务请求。已有设备短时间连续注册会回 device_id=0，默认间隔 5 秒。

示例：

    python3 tools/login-lab/register_probe.py --repeat 2
    python3 tools/login-lab/register_probe.py --fresh
    python3 tools/login-lab/register_probe.py --android-id 0123456789abcdef
"""
from __future__ import annotations

import argparse
import gzip
import hashlib
import http.client
import json
import os
import secrets
import subprocess
import sys
import time
import urllib.error
import urllib.request
import uuid
import zlib
from pathlib import Path

import hgsign

HERE = Path(__file__).resolve().parent
DEFAULT_STATE = HERE / ".runtime" / "official-register.json"

HOST = "https://log5-applog.fqnovel.com"
PATH = "/service/2/device_register/"
QUERY = (
    "?tt_data=a"
    "&cronet_version=8d40f833_2026-03-03"
    "&ttnet_version=4.2.243.31-douyin"
    "&use_store_region_cookie=1"
)

ANDROID_ID_PLACEHOLDER = "9774d56d682e549c"
USER_AGENT = (
    "com.phoenix.read/73932 "
    "(Linux; U; Android 12; zh_CN; 2304FPN6DC; Build/W528JS; "
    "Cronet/TTNetVersion:8d40f833 2026-03-03 "
    "QuicVersion:21ac1950 2025-11-18)"
)


def now_ms() -> int:
    return int(time.time() * 1000)


def valid_android_id(value: str | None) -> bool:
    if not value:
        return False
    value = value.strip()
    return (
        len(value) >= 13
        and value.lower() != ANDROID_ID_PLACEHOLDER
        and all(ch in "0123456789abcdefABCDEF" for ch in value)
    )


def adb_android_id() -> str | None:
    """有且仅有一台 adb 设备时，读取它的 Android ID。"""
    try:
        devices = subprocess.run(
            ["adb", "devices"],
            capture_output=True,
            text=True,
            timeout=5,
            check=False,
        )
        serials = [
            line.split()[0]
            for line in devices.stdout.splitlines()[1:]
            if line.strip().endswith("device")
        ]
        if len(serials) != 1:
            return None
        result = subprocess.run(
            ["adb", "-s", serials[0], "shell", "settings", "get", "secure", "android_id"],
            capture_output=True,
            text=True,
            timeout=8,
            check=False,
        )
        value = result.stdout.strip()
        return value if valid_android_id(value) else None
    except Exception:
        return None


def random_openudid_fallback() -> str:
    value = format(secrets.randbits(80), "x")
    if len(value) < 13:
        value = "F" * (13 - len(value)) + value
    return value


def make_identifiers(args: argparse.Namespace) -> dict:
    android_id = (args.android_id or "").strip()
    if not android_id:
        android_id = adb_android_id() or ""

    if args.openudid:
        openudid = args.openudid.strip()
        source = "override"
    elif valid_android_id(android_id):
        openudid = android_id
        source = "android_id"
    else:
        openudid = random_openudid_fallback()
        source = "secure_random_fallback"

    return {
        "android_id": android_id,
        "openudid": openudid,
        "openudid_source": source,
        "cdid": args.cdid or str(uuid.uuid4()),
        "clientudid": args.clientudid or str(uuid.uuid4()),
    }


def load_state(path: Path) -> dict:
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except FileNotFoundError:
        return {}
    except Exception as exc:
        raise SystemExit(f"state 文件读取失败: {path}: {exc}") from exc


def save_state(path: Path, state: dict) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(
        json.dumps(state, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )


def registration_body(
    identifiers: dict,
    state: dict,
    include_identity: bool,
    ipv6_value: str,
) -> dict:
    install_time = int(state.get("install_time") or now_ms())
    header = {
        "display_name": "红果免费短剧",
        "update_version_code": 73932,
        "manifest_version_code": 73932,
        "app_version_minor": "7.3.9.32",
        "aid": 8662,
        "channel": "69310258a",
        "package": "com.phoenix.read",
        "app_version": "7.3.9.32",
        "version_code": 73932,
        "sdk_version": "3.7.0-rc.34-fanqie-xiaoshuo",
        "sdk_target_version": 29,
        "git_hash": "3a88f4e",
        "os": "Android",
        "os_version": "12",
        "os_api": 32,
        "device_model": "2304FPN6DC",
        "device_brand": "Xiaomi",
        "device_manufacturer": "Xiaomi",
        "cpu_abi": "arm64-v8a",
        "release_build": "b5e187f",
        "density_dpi": 540,
        "display_density": "mdpi",
        "resolution": "2160x3840",
        "language": "zh",
        "timezone": 8,
        "access": "wifi",
        "not_request_sender": 0,
        "carrier": "CHINA MOBILE",
        "mcc_mnc": "46000",
        "rom": "1135",
        "rom_version": "W528JS release-keys",
        "cdid": identifiers["cdid"],
        "sig_hash": "56a962410c494bbaf0b58dba20cae56f",
        "openudid": identifiers["openudid"],
        "clientudid": identifiers["clientudid"],
        "ipv6_list": [],
        "region": "CN",
        "tz_name": "Asia/Shanghai",
        "tz_offset": 28800,
        "sim_region": "cn",
        "sim_serial_number": [],
        "oaid_may_support": False,
        "req_id": str(uuid.uuid4()),
        "device_platform": "android",
        "custom": {
            "account_region_src": "fallback",
            "host_bit": 64,
            "account_region": "cn",
            "dragon_device_type": "phone",
        },
        "pre_installed_channel": "",
        "apk_first_install_time": install_time,
        "is_system_app": 0,
        "sdk_flavor": "china",
        "guest_mode": 0,
    }

    if ipv6_value:
        header["ipv6_list"] = [{"type": "client_anpi", "value": ipv6_value.upper()}]

    registration = state.get("registration") or {}
    if include_identity and registration.get("device_id") and registration.get("install_id"):
        header["device_id"] = str(registration["device_id"])
        header["install_id"] = str(registration["install_id"])
        if registration.get("klink_egdi"):
            header["klink_egdi"] = str(registration["klink_egdi"])

    return {
        "magic_tag": "ss_app_log",
        "header": header,
        "_gen_time": now_ms(),
    }


def request_headers(body: bytes, cookies: dict) -> dict:
    headers = {
        "User-Agent": USER_AGENT,
        "Content-Type": "application/octet-stream;tt-data=a",
        "log-encode-type": "gzip",
        "x-ss-dp": "8662",
        "x-ss-req-ticket": str(now_ms()),
        "x-vc-bdturing-sdk-version": "4.0.3.cn",
        "x-tt-trace-id": trace_id(),
        "x-neptune": "-11|50:51:59",
        "x-ss-stub": hashlib.md5(body).hexdigest().upper(),
        "Accept-Encoding": "gzip, deflate, br",
    }
    if cookies:
        headers["Cookie"] = "; ".join(f"{key}={value}" for key, value in cookies.items())
    return headers


def trace_id() -> str:
    value = uuid.uuid4().hex
    return f"00-{value}-{value[:16]}-01"


def decode_response(raw: bytes, content_encoding: str) -> bytes:
    encoding = (content_encoding or "").lower()
    if "br" in encoding:
        import brotli

        return brotli.decompress(raw)
    if "gzip" in encoding:
        return gzip.decompress(raw)
    if "deflate" in encoding:
        try:
            return zlib.decompress(raw)
        except zlib.error:
            return zlib.decompress(raw, -zlib.MAX_WBITS)
    return raw


def response_cookies(headers) -> dict:
    result = {}
    for header in headers.get_all("Set-Cookie") or []:
        pair = header.split(";", 1)[0].strip()
        if "=" in pair:
            name, value = pair.split("=", 1)
            if name.strip() and value.strip():
                result[name.strip()] = value.strip()
    return result


def register_once(
    identifiers: dict,
    state: dict,
    include_identity: bool,
    send_cookies: bool,
    ipv6_value: str,
    timeout: float,
) -> tuple[int, dict, dict, bytes]:
    payload = registration_body(identifiers, state, include_identity, ipv6_value)
    plain = json.dumps(payload, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
    compressed = gzip.compress(plain, mtime=0)
    encrypted = hgsign.tt_encrypt(compressed)

    old_cookies = (state.get("registration") or {}).get("cookies") or {}
    headers = request_headers(encrypted, old_cookies if send_cookies else {})
    request = urllib.request.Request(
        HOST + PATH + QUERY,
        data=encrypted,
        headers=headers,
        method="POST",
    )

    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            status = response.status
            response_headers = response.headers
            raw = response.read()
    except urllib.error.HTTPError as exc:
        status = exc.code
        response_headers = exc.headers
        raw = exc.read()
    except (urllib.error.URLError, TimeoutError, http.client.HTTPException) as exc:
        raise SystemExit(f"请求失败: {exc}") from exc

    decoded = decode_response(raw, response_headers.get("Content-Encoding", ""))
    try:
        data = json.loads(decoded.decode("utf-8"))
    except Exception as exc:
        preview = decoded[:500].decode("utf-8", errors="replace")
        raise SystemExit(f"响应不是 JSON: {exc}\n{preview}") from exc
    return status, data, response_cookies(response_headers), encrypted


def main() -> None:
    parser = argparse.ArgumentParser(description="按官方 App 逻辑运行 device_register")
    parser.add_argument("--state", type=Path, default=DEFAULT_STATE)
    parser.add_argument("--fresh", action="store_true", help="忽略已有身份，生成一套新标识")
    parser.add_argument("--repeat", type=int, default=1, help="连续注册次数，默认 1")
    parser.add_argument("--android-id", default="", help="有效的 Android ID；不传时先尝试 adb")
    parser.add_argument("--openudid", default="", help="直接覆盖 openudid")
    parser.add_argument("--cdid", default="", help="直接覆盖 cdid")
    parser.add_argument("--clientudid", default="", help="直接覆盖 clientudid")
    parser.add_argument("--ipv6", default="", help="可选的 client_anpi IPv6 值")
    parser.add_argument("--timeout", type=float, default=15.0)
    parser.add_argument("--interval", type=float, default=5.0, help="重复注册间隔秒数")
    parser.add_argument(
        "--send-cookies",
        action="store_true",
        help="重复注册时携带上一轮下发的 Cookie（实测可能导致 device_id=0）",
    )
    args = parser.parse_args()

    if args.repeat < 1:
        raise SystemExit("--repeat 必须 >= 1")

    state = {} if args.fresh else load_state(args.state)
    identifiers = state.get("identifiers") or make_identifiers(args)
    if args.fresh:
        identifiers = make_identifiers(args)
        state = {"install_time": now_ms(), "identifiers": identifiers}
    elif any((args.android_id, args.openudid, args.cdid, args.clientudid)):
        raise SystemExit("已有 state；要覆盖标识请同时传 --fresh")

    state["identifiers"] = identifiers
    save_state(args.state, state)

    print("state:", args.state)
    print("openudid:", identifiers["openudid"], f"({identifiers['openudid_source']})")
    print("cdid:", identifiers["cdid"])
    print("clientudid:", identifiers["clientudid"])
    print()

    for attempt in range(1, args.repeat + 1):
        has_identity = bool((state.get("registration") or {}).get("device_id"))
        status, data, cookies, encrypted = register_once(
            identifiers=identifiers,
            state=state,
            include_identity=has_identity,
            send_cookies=has_identity and args.send_cookies,
            ipv6_value=args.ipv6,
            timeout=args.timeout,
        )
        device_id = str(data.get("device_id_str") or data.get("device_id") or "")
        install_id = str(data.get("install_id_str") or data.get("install_id") or "")
        rejected = status != 200 or not device_id or not install_id or device_id == "0" or install_id == "0"

        registration = dict(state.get("registration") or {})
        if not rejected:
            registration.update(
                {
                    "status": status,
                    "device_id": device_id,
                    "install_id": install_id,
                    "device_token": str(data.get("device_token") or ""),
                    "klink_egdi": str(data.get("klink_egdi") or ""),
                    "new_user": data.get("new_user"),
                    "server_time": data.get("server_time"),
                    "cookies": {**registration.get("cookies", {}), **cookies},
                    "last_body_bytes": len(encrypted),
                    "updated_at": now_ms(),
                }
            )
            state["registration"] = registration
            state.pop("last_rejected", None)
        else:
            state["last_rejected"] = {
                "status": status,
                "response": data,
                "at": now_ms(),
            }
        save_state(args.state, state)

        print(
            f"[{attempt}] status={status} new_user={data.get('new_user')} "
            f"device_id={device_id} install_id={install_id} body={len(encrypted)}B"
        )
        print("  device_token:", registration["device_token"] or "(empty)")
        print("  klink_egdi:", registration["klink_egdi"] or "(empty)")
        print("  cookies:", json.dumps(registration["cookies"], ensure_ascii=False, sort_keys=True))
        if rejected:
            print("  注册被拒绝:", json.dumps(data, ensure_ascii=False))
            raise SystemExit(2)
        if attempt < args.repeat:
            time.sleep(max(0.0, args.interval))

    print("\n完整响应字段已写入 state；后续播放应使用同一组 device_id/iid/cdid/openudid/klink_egdi 和 Cookie。")


if __name__ == "__main__":
    main()
