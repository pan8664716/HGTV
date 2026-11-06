# SPDX-License-Identifier: GPL-3.0-only
"""注册成功后连续探测三集视频。

读取 register_probe.py 生成的 state，使用同一组：

* device_id / install_id / klink_egdi
* cdid / openudid / clientudid
* 注册下发的 Cookie

先请求 video_detail 拿同一部剧的前三集，再逐集请求 video_model，最后对
fallback_api 返回的 main_url 做 Range 读取，确认 CDN 返回媒体字节。

示例：

    python3 tools/login-lab/playback_probe.py --count 3
    python3 tools/login-lab/playback_probe.py --video-ids ID1,ID2,ID3
"""
from __future__ import annotations

import argparse
import base64
import gzip
import hashlib
import json
import os
import random
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import zlib
from pathlib import Path

import hgsign
from register_probe import DEFAULT_STATE, decode_response, load_state, now_ms

API_HOST = "https://api5-normal-sinfonlinec.fqnovel.com"
VIDEO_MODEL_PATH = "/novel/player/video_model/v1/"
VIDEO_DETAIL_PATH = "/novel/player/video_detail/v1/"
DEFAULT_SERIES_ID = "7671966197985856536"
DEFAULT_VIDEO_IDS = (
    "7671977925326949400",
    "7671977933363235864",
    "7671977930024553496",
)
VIDEO_UA = (
    "com.phoenix.read/73932 "
    "(Linux; U; Android 12; zh_CN; 2304FPN6DC; Build/W528JS; "
    "Cronet/TTNetVersion:8d40f833 2026-03-03 "
    "QuicVersion:21ac1950 2025-11-18)"
)
URL_SALT = bytes.fromhex(
    "4dd4c2e6b83162090e52b3c7a6733ba41cb2462b829ab58a196b39db57177524"
    "f49baf7f08e8d68d26a72e37c1a95a2f1f05a51892aef2949732b62a38aadd58"
)
DETAIL_IMAGE_SHRINK = (
    "W3siaW1hZ2VfdHlwZSI6MywiaW1hZ2Vfd2lkdGgiOjIxNjAsInNocmlua190eXBlIjozfSx7"
    "ImltYWdlX3R5cGUiOjQsImltYWdlX3dpZHRoIjoxMjEsInNocmlua190eXBlIjo0fV0="
)


def registration_state(path: Path) -> dict:
    state = load_state(path)
    identifiers = state.get("identifiers") or {}
    registration = state.get("registration") or {}
    if not identifiers.get("cdid") or not identifiers.get("openudid"):
        raise SystemExit(f"state 里没有设备标识: {path}")
    if not registration.get("device_id") or not registration.get("install_id"):
        raise SystemExit(f"state 里没有有效注册结果: {path}")
    return state


def device_profile(state: dict) -> dict:
    identifiers = state["identifiers"]
    registration = state["registration"]
    return {
        "device_id": str(registration["device_id"]),
        "uuid": str(registration["device_id"]),
        "iid": str(registration["install_id"]),
        "install_id": str(registration["install_id"]),
        "klink_egdi": str(registration.get("klink_egdi") or ""),
        "cdid": str(identifiers["cdid"]),
        "openudid": str(identifiers["openudid"]),
        "clientudid": str(identifiers.get("clientudid") or ""),
        "device_type": "2304FPN6DC",
        "device_brand": "Xiaomi",
        "device_manufacturer": "Xiaomi",
        "os": "android",
        "os_version": "12",
        "os_api": "32",
        "resolution": "2160*3840",
        "dpi": "540",
        "rom_version": "W528JS release-keys",
        "version_code": "73932",
        "manifest_version_code": "73932",
        "update_version_code": "73932",
        "pv_player": "73932",
        "version_name": "7.3.9.32",
    }


def query_values(state: dict) -> dict:
    device = device_profile(state)
    values = {
        "klink_egdi": device["klink_egdi"],
        "iid": device["iid"],
        "device_id": device["device_id"],
        "ac": "wifi",
        "channel": "69310258a",
        "aid": "8662",
        "app_name": "novelread",
        "version_code": "73932",
        "version_name": "7.3.9.32",
        "device_platform": "android",
        "os": "android",
        "ssmix": "a",
        "device_type": "2304FPN6DC",
        "device_brand": "Xiaomi",
        "language": "zh",
        "os_api": "32",
        "os_version": "12",
        "manifest_version_code": "73932",
        "resolution": "2160*3840",
        "dpi": "540",
        "update_version_code": "73932",
        "host_abi": "arm64-v8a",
        "dragon_device_type": "phone",
        "pv_player": "73932",
        "compliance_status": "0",
        "need_personal_recommend": "1",
        "player_so_load": "1",
        "is_android_pad_screen": "1",
        "rom_version": "W528JS release-keys",
        "cdid": device["cdid"],
    }
    return {key: value for key, value in values.items() if value}


def cookie_header(state: dict) -> str:
    cookies = (state.get("registration") or {}).get("cookies") or {}
    return "; ".join(f"{name}={value}" for name, value in cookies.items())


def java_gzip(data: bytes) -> bytes:
    """与 Java GZIPOutputStream 默认 level=6 / OS=0 对齐。"""
    compressor = zlib.compressobj(6, zlib.DEFLATED, -15)
    deflate = compressor.compress(data) + compressor.flush()
    return (
        b"\x1f\x8b\x08\x00\x00\x00\x00\x00\x00\x00"
        + deflate
        + (zlib.crc32(data) & 0xFFFFFFFF).to_bytes(4, "little")
        + (len(data) & 0xFFFFFFFF).to_bytes(4, "little")
    )


def signed_json_request(
    state: dict,
    path: str,
    body_obj: dict,
    timeout: float,
) -> tuple[int, dict, bytes]:
    raw_body = json.dumps(body_obj, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
    gzip_body = java_gzip(raw_body)
    device = device_profile(state)
    signer = hgsign.Signer(device=device)
    signed = signer.sign(
        API_HOST + path,
        query_values(state),
        body=gzip_body,
        mode="hg",
    )
    headers = dict(signed["headers"])
    # 官方播放抓包没有这两个空/额外头；Cookie 只带注册下发的设备 Cookie。
    headers.pop("x-tt-dt", None)
    headers.pop("x-tt-request-tag", None)
    headers.update(
        {
            "User-Agent": VIDEO_UA,
            "Content-Type": "application/json; charset=utf-8",
            "Content-Encoding": "gzip",
            "Accept": "application/json; charset=utf-8,application/x-protobuf",
            "Accept-Encoding": "gzip, deflate, br",
            "x-ss-dp": "8662",
            "x-vc-bdturing-sdk-version": "4.0.3.cn",
            "passport-sdk-version": "5051452",
            "lc": "101",
            "sdk-version": "2",
            "x-tt-trace-id": trace_id(),
            "x-reading-request": f"{headers.get('x-ss-req-ticket', now_ms())}-{random.randint(10000000, 99999999)}",
        }
    )
    cookie = cookie_header(state)
    if cookie:
        headers["Cookie"] = cookie
        headers["x-tt-store-region"] = "cn-gd"
        headers["x-tt-store-region-src"] = "did"
    request = urllib.request.Request(
        signed["url"],
        data=gzip_body,
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
    except (urllib.error.URLError, TimeoutError) as exc:
        raise RuntimeError(f"请求失败: {exc}") from exc

    decoded = decode_response(raw, response_headers.get("Content-Encoding", ""))
    if not decoded:
        raise RuntimeError(f"{path} 返回空响应")
    try:
        data = json.loads(decoded.decode("utf-8"))
    except Exception as exc:
        raise RuntimeError(f"{path} 响应不是 JSON: {exc}") from exc
    return status, data, decoded


def trace_id() -> str:
    value = f"{random.getrandbits(128):032x}"
    return f"00-{value}-{value[:16]}-01"


def detail_body(series_id: str) -> dict:
    return {
        "biz_param": {
            "detail_page_version": 1,
            "disable_digg_stat": False,
            "disable_video_relate_book": False,
            "image_shrink_datas_str": DETAIL_IMAGE_SHRINK,
            "need_all_video_definition": False,
            "need_mp4_align": False,
            "screen_width_px": "2160",
            "source": 5,
            "use_os_player": False,
            "use_server_dns": False,
            "video_id_type": 1,
        },
        "series_id": series_id,
    }


def model_body(video_id: str) -> dict:
    return {
        "biz_param": {
            "detail_page_version": 0,
            "device_level": 3,
            "disable_digg_stat": False,
            "disable_video_relate_book": False,
            "from_video_id": "",
            "need_all_video_definition": True,
            "need_mp4_align": False,
            "source": 4,
            "use_os_player": False,
            "use_server_dns": False,
            "video_platform": 3,
        },
        "content_type": 1004,
        "video_id": video_id,
    }


def response_code(data: dict) -> int:
    try:
        return int(data.get("code", data.get("Code", 0)) or 0)
    except Exception:
        return -1


def video_model(data: dict) -> dict | None:
    root = data.get("data") or {}
    value = root.get("video_model")
    if isinstance(value, str):
        value = json.loads(value)
    if isinstance(value, dict):
        return value
    for item in root.values():
        if isinstance(item, dict):
            nested = item.get("video_model")
            if isinstance(nested, str):
                nested = json.loads(nested)
            if isinstance(nested, dict):
                return nested
    return None


def fallback_url(model: dict) -> str:
    value = model.get("fallback_api")
    if isinstance(value, dict):
        value = value.get("fallback_api")
    elif isinstance(value, list):
        value = value[0] if value else ""
    elif isinstance(value, str):
        try:
            parsed = json.loads(value)
            if isinstance(parsed, dict):
                value = parsed.get("fallback_api", value)
        except Exception:
            pass
    return str(value or "")


def fetch_json(url: str, timeout: float) -> dict:
    request = urllib.request.Request(
        url,
        headers={
            "User-Agent": VIDEO_UA,
            "Accept": "*/*",
            "Accept-Encoding": "gzip, deflate, br",
        },
        method="GET",
    )
    with urllib.request.urlopen(request, timeout=timeout) as response:
        raw = response.read()
        raw = decode_response(raw, response.headers.get("Content-Encoding", ""))
    return json.loads(raw.decode("utf-8"))


def decrypt_url(value: str, seed_b64: str) -> str:
    if value.startswith("http://") or value.startswith("https://"):
        return value
    if not seed_b64:
        return value
    raw = base64.b64decode(value)
    if len(raw) < 20 or raw[0] != 0xA8 or raw[2] != 1 or raw[3] != 0:
        return value
    seed = base64.b64decode(seed_b64)
    derived = hashlib.sha512(hashlib.sha512(seed).digest() + URL_SALT).digest()
    try:
        from Crypto.Cipher import AES

        cipher = AES.new(derived[:16], AES.MODE_CBC, derived[16:32])
        usable = len(raw) - 4
        usable -= usable % 16
        plain = cipher.decrypt(raw[4:4 + usable])
    except Exception:
        return value
    if plain:
        padding = plain[-1]
        if 1 <= padding <= 16:
            plain = plain[:-padding]
    plain = plain.rstrip(b"\x00")
    return plain.decode("utf-8", errors="replace")


def video_rows(info: dict) -> list[dict]:
    video_list = info.get("video_list") or {}
    if isinstance(video_list, dict):
        return [row for row in video_list.values() if isinstance(row, dict)]
    if isinstance(video_list, list):
        return [row for row in video_list if isinstance(row, dict)]
    return []


def range_probe(url: str, timeout: float) -> dict:
    request = urllib.request.Request(
        url,
        headers={
            "User-Agent": VIDEO_UA,
            "Accept": "*/*",
            "Accept-Encoding": "identity",
            "Range": "bytes=0-65535",
        },
        method="GET",
    )
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            status = response.status
            content_type = response.headers.get("Content-Type", "")
            data = response.read(65536)
            return {
                "status": status,
                "content_type": content_type,
                "bytes": len(data),
                "prefix": data[:16].hex(),
                "playable": status in (200, 206) and len(data) > 1024,
            }
    except urllib.error.HTTPError as exc:
        return {"status": exc.code, "bytes": 0, "playable": False, "error": str(exc)}
    except Exception as exc:
        return {"status": 0, "bytes": 0, "playable": False, "error": str(exc)}


def probe_episode(state: dict, video_id: str, timeout: float) -> dict:
    status, data, _ = signed_json_request(
        state,
        VIDEO_MODEL_PATH,
        model_body(video_id),
        timeout,
    )
    code = response_code(data)
    model = video_model(data) if code == 0 else None
    result = {
        "video_id": video_id,
        "model_status": status,
        "model_code": code,
        "model_ok": bool(model),
        "duration": (model or {}).get("video_duration"),
        "url_checked": False,
        "url_ok": False,
    }
    if not model:
        result["error"] = "没有 video_model"
        return result

    fallback = fallback_url(model)
    if not fallback.startswith("http"):
        result["error"] = "fallback_api 为空"
        return result
    result["fallback_host"] = urllib.parse.urlparse(fallback).netloc
    info_root = fetch_json(fallback, timeout)
    info = ((info_root.get("video_info") or {}).get("data") or {})
    seed = str(info.get("key_seed") or "")
    rows = video_rows(info)
    if not rows:
        result["error"] = "fallback_api 没有 video_list"
        return result

    attempts = []
    for index, row in enumerate(rows):
        raw_url = str(row.get("main_url") or row.get("backup_url") or "")
        if not raw_url:
            continue
        try:
            real_url = decrypt_url(raw_url, seed)
        except Exception:
            real_url = raw_url
        check = range_probe(real_url, timeout)
        check["quality"] = str(row.get("quality_desc") or row.get("height") or row.get("vheight") or index + 1)
        attempts.append(check)
        if check.get("playable"):
            break

    result["url_checked"] = bool(attempts)
    result["url_ok"] = any(item.get("playable") for item in attempts)
    result["attempts"] = attempts
    return result


def main() -> None:
    parser = argparse.ArgumentParser(description="连续探测三集视频")
    parser.add_argument("--state", type=Path, default=DEFAULT_STATE)
    parser.add_argument("--series-id", default=DEFAULT_SERIES_ID)
    parser.add_argument("--count", type=int, default=3)
    parser.add_argument("--video-ids", default="", help="逗号分隔；不传则从 video_detail 取")
    parser.add_argument("--timeout", type=float, default=15.0)
    parser.add_argument("--interval", type=float, default=3.0, help="每集取地址间隔秒数")
    parser.add_argument(
        "--output",
        type=Path,
        default=Path(__file__).resolve().parent / ".runtime" / "playback-probe.json",
    )
    args = parser.parse_args()

    state = registration_state(args.state)
    print("state:", args.state)
    print("device_id:", state["registration"]["device_id"])
    print("install_id:", state["registration"]["install_id"])

    if args.video_ids:
        video_ids = [item.strip() for item in args.video_ids.split(",") if item.strip()]
    else:
        status, data, _ = signed_json_request(
            state,
            VIDEO_DETAIL_PATH,
            detail_body(args.series_id),
            args.timeout,
        )
        video_data = (data.get("data") or {}).get("video_data") or {}
        video_list = video_data.get("video_list") or []
        video_ids = [
            str(item.get("vid") or item.get("video_id") or "")
            for item in video_list
            if isinstance(item, dict)
        ]
        video_ids = [item for item in video_ids if item]
        if not video_ids:
            video_ids = list(DEFAULT_VIDEO_IDS)
            print(f"video_detail 没取到分集（status={status}, code={response_code(data)}），使用抓包分集")
    if not video_ids:
        raise SystemExit("没有可探测的 video_id")

    results = []
    for index, video_id in enumerate(video_ids[:args.count], 1):
        print(f"\n[{index}] video_id={video_id}")
        try:
            result = probe_episode(state, video_id, args.timeout)
        except Exception as exc:
            result = {"video_id": video_id, "model_ok": False, "url_ok": False, "error": str(exc)}
        results.append(result)
        if result.get("url_ok"):
            attempts = result.get("attempts") or []
            playable = next(item for item in attempts if item.get("playable"))
            print(
                f"  播放成功: model_code={result.get('model_code')} "
                f"quality={playable.get('quality')} range={playable.get('status')} "
                f"bytes={playable.get('bytes')} type={playable.get('content_type')}"
            )
        elif result.get("model_ok"):
            print(f"  拿到模型，但 CDN 读取失败: {result.get('error') or 'main_url 不可用'}")
        else:
            print(f"  失败: {result.get('error') or '没有模型'}")
        if index < len(video_ids[:args.count]):
            time.sleep(max(0.0, args.interval))

    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(
        json.dumps(
            {
                "checked_at": now_ms(),
                "state": str(args.state),
                "series_id": args.series_id,
                "results": results,
            },
            ensure_ascii=False,
            indent=2,
        )
        + "\n",
        encoding="utf-8",
    )
    print("\n结果:", args.output)
    if not all(item.get("url_ok") for item in results):
        raise SystemExit(2)


if __name__ == "__main__":
    main()
