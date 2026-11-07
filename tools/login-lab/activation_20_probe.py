# SPDX-License-Identifier: GPL-3.0-only
"""注册、激活同一台设备，然后按 1 秒间隔连续取 20 集播放地址。

这个脚本只走 HTTP，不读取 App 私有存储。它把以下阶段写进同一份结果：

* device_register
* app_log(launch) + log_settings
* settings/v3 + get_domains
* 同一设备连续 20 集 video_model + fallback_api + Range 读取

示例：

    python3 tools/login-lab/activation_20_probe.py
    python3 tools/login-lab/activation_20_probe.py --count 20 --interval 1
"""
from __future__ import annotations

import argparse
import base64
import gzip
import hashlib
import json
import secrets
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
from datetime import datetime, timedelta, timezone
from pathlib import Path

import hgsign
import playback_probe
import register_probe
import warmup_probe

HERE = Path(__file__).resolve().parent
RUNTIME = HERE / ".runtime"
DEFAULT_SERIES_ID = "7702020975402961945"
PLAYER_HOST = "https://api5-normal-sinfonlinea.fqnovel.com"
PLAYER_BATCH_PATH = "/novel/player/multi_video_model/player/v1"
VIDEO_DETAIL_PRELOAD_PATH = "/novel/player/multi_video_detail/preload/v1"
AB_SDK_VERSION = "18281636,17820223,17330565"
CHINA_TZ = timezone(timedelta(hours=8))


def now_ms() -> int:
    return register_probe.now_ms()


def fresh_identifiers(tag: str, android_id: str) -> dict:
    openudid = android_id.strip() or secrets.token_hex(8)
    identifiers = {
        "android_id": android_id.strip(),
        "openudid": openudid,
        "openudid_source": "android_id" if android_id.strip() else tag,
        "cdid": str(uuid.uuid4()),
        "clientudid": str(uuid.uuid4()),
    }
    return {"install_time": now_ms(), "identifiers": identifiers}


def register_fresh(
    state: dict,
    path: Path,
    timeout: float,
    ipv6_value: str,
) -> dict:
    identifiers = state["identifiers"]
    status, data, cookies, encrypted = register_probe.register_once(
        identifiers=identifiers,
        state=state,
        include_identity=False,
        send_cookies=False,
        ipv6_value=ipv6_value,
        timeout=timeout,
    )
    device_id = str(data.get("device_id_str") or data.get("device_id") or "")
    install_id = str(data.get("install_id_str") or data.get("install_id") or "")
    if (
        status != 200
        or not device_id
        or not install_id
        or device_id == "0"
        or install_id == "0"
    ):
        raise RuntimeError(f"注册失败 HTTP {status}: {data}")
    state["registration"] = {
        "status": status,
        "device_id": device_id,
        "install_id": install_id,
        "device_token": str(data.get("device_token") or ""),
        "klink_egdi": str(data.get("klink_egdi") or ""),
        "new_user": data.get("new_user"),
        "server_time": data.get("server_time"),
        "cookies": cookies,
        "last_body_bytes": len(encrypted),
        "updated_at": now_ms(),
    }
    register_probe.save_state(path, state)
    return state


def fresh_state(
    path: Path,
    tag: str,
    ipv6_value: str,
    android_id: str,
) -> dict:
    return register_fresh(
        fresh_identifiers(tag, android_id),
        path,
        20.0,
        ipv6_value,
    )


def confirm_registration(
    state: dict,
    path: Path,
    timeout: float,
) -> dict:
    status, data, cookies, encrypted = register_probe.register_once(
        identifiers=state["identifiers"],
        state=state,
        include_identity=True,
        send_cookies=True,
        ipv6_value="",
        timeout=timeout,
    )
    device_id = str(data.get("device_id_str") or data.get("device_id") or "")
    install_id = str(data.get("install_id_str") or data.get("install_id") or "")
    if (
        status != 200
        or not device_id
        or not install_id
        or device_id == "0"
        or install_id == "0"
    ):
        raise RuntimeError(f"二次注册确认失败 HTTP {status}: {data}")
    state["registration"].update(
        {
            "status": status,
            "device_id": device_id,
            "install_id": install_id,
            "device_token": str(data.get("device_token") or ""),
            "klink_egdi": str(data.get("klink_egdi") or ""),
            "new_user": data.get("new_user"),
            "server_time": data.get("server_time"),
            "cookies": {
                **state["registration"].get("cookies", {}),
                **cookies,
            },
            "last_body_bytes": len(encrypted),
            "updated_at": now_ms(),
        }
    )
    register_probe.save_state(path, state)
    return state["registration"]


def tt_info(state: dict) -> str:
    now = now_ms()
    identifiers = state["identifiers"]
    session = str(uuid.uuid4())
    cold = str(uuid.uuid4())
    fields = [
        ("timezone", "8.0"),
        ("carrier", "CHINA MOBILE"),
        ("mcc_mnc", "46000"),
        ("sim_region", "cn"),
        ("custom_bt", str(state.get("install_time") or now)),
        ("app_version_minor", "7.3.9.32"),
        ("is_first_req_after_install", "1"),
        ("ac", "wifi"),
        ("channel", "69310258a"),
        ("aid", "8662"),
        ("app_name", "novelread"),
        ("version_code", "73932"),
        ("version_name", "7.3.9.32"),
        ("device_platform", "android"),
        ("os", "android"),
        ("ssmix", "a"),
        ("device_type", "2304FPN6DC"),
        ("device_brand", "Xiaomi"),
        ("language", "zh"),
        ("os_api", "32"),
        ("os_version", "12"),
        ("openudid", identifiers["openudid"]),
        ("manifest_version_code", "73932"),
        ("resolution", "1920*1080"),
        ("dpi", "280"),
        ("update_version_code", "73932"),
        ("_rticket", str(now)),
        ("normal_session_cnt_in_day", "1"),
        ("gender", "2"),
        ("cold_start_session_cnt_in_day", "1"),
        ("host_abi", "arm64-v8a"),
        ("dragon_device_type", "phone"),
        ("sys_mini_window", "0"),
        ("pv_player", "73932"),
        ("app_mini_window", "0"),
        ("normal_session_id", session + "#0"),
        ("compliance_status", "0"),
        ("har_status", "0"),
        ("cold_start_session_id", cold),
        ("cold_start_session_cnt_in_life", "1"),
        ("charging", "0"),
        ("normal_session_cnt_in_life", "1"),
        ("is_power_save_mode", "0"),
        ("app_dark_mode", "1"),
        ("screen_brightness", "1"),
        ("battery_pct", "0"),
        ("down_speed", "60000"),
        ("sys_dark_mode", "0"),
        ("need_personal_recommend", "1"),
        ("player_so_load", "1"),
        ("font_scale", "100"),
        ("is_android_pad_screen", "1"),
        ("network_type", "4"),
        ("rom_version", "W528JS release-keys"),
        ("current_volume", "1"),
        ("cdid", identifiers["cdid"]),
        ("req_id", str(uuid.uuid4())),
    ]
    plain = urllib.parse.urlencode(fields).encode()
    encrypted = hgsign.tt_encrypt(gzip.compress(plain, mtime=0))
    return base64.urlsafe_b64encode(encrypted).decode().rstrip("=")


def activation_status(state: dict, timeout: float) -> dict:
    params = {"aid": "8662", "tt_info": tt_info(state)}
    signed = hgsign.Signer(device=playback_probe.device_profile(state)).sign(
        "https://ichannel.snssdk.com/service/2/app_alert_check/",
        params,
        body=None,
        mode="hg",
        extra_headers={
            "Cookie": playback_probe.cookie_header(state),
            "x-tt-store-region": "cn-gd",
            "x-tt-store-region-src": "did",
            "x-ss-dp": "8662",
        },
    )
    headers = dict(signed["headers"])
    headers.pop("x-tt-dt", None)
    headers.update(
        {
            "User-Agent": playback_probe.VIDEO_UA,
            "Accept": "application/json",
            "Accept-Encoding": "gzip, deflate, br",
            "x-tt-trace-id": playback_probe.trace_id(),
        }
    )
    request = urllib.request.Request(signed["url"], headers=headers)
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            status = response.status
            raw = response.read()
            response_headers = response.headers
    except urllib.error.HTTPError as exc:
        status = exc.code
        raw = exc.read()
        response_headers = exc.headers
    raw = playback_probe.decode_response(
        raw, response_headers.get("Content-Encoding", "")
    )
    body = json.loads(raw.decode("utf-8"))
    return {
        "status": status,
        "logid": response_headers.get("x-tt-logid", ""),
        "body": body,
        "is_activated": (body.get("data") or {}).get("is_activated"),
    }


def app_header(state: dict) -> dict:
    identifiers = state["identifiers"]
    registration = state["registration"]
    return {
        "openudid": identifiers["openudid"],
        "sdk_version": "2.14.0",
        "package": "com.phoenix.read",
        "channel": "69310258a",
        "display_name": "红果免费短剧",
        "app_version": "7.3.9.32",
        "version_code": 73932,
        "timezone": 8,
        "access": "wifi",
        "os": "Android",
        "os_version": "12",
        "os_api": 32,
        "device_model": "2304FPN6DC",
        "device_brand": "Xiaomi",
        "device_manufacturer": "Xiaomi",
        "language": "zh",
        "resolution": "2160x3840",
        "display_density": "mdpi",
        "density_dpi": 540,
        "carrier": "CHINA MOBILE",
        "mcc_mnc": "46000",
        "clientudid": identifiers["clientudid"],
        "device_id": registration["device_id"],
        "install_id": registration["install_id"],
        "sig_hash": "56a962410c494bbaf0b58dba20cae56f",
        "aid": 8662,
        "push_sdk": [1, 2, 7, 8, 9],
        "rom": "1135",
        "release_build": "b5e187f",
        "update_version_code": 73932,
        "manifest_version_code": 73932,
        "app_version_minor": "7.3.9.32",
        "cpu_abi": "arm64-v8a",
        "not_request_sender": 0,
        "rom_version": "W528JS release-keys",
        "region": "CN",
        "tz_name": "Asia/Shanghai",
        "tz_offset": 28800,
        "sim_region": "cn",
        "custom": {
            "account_region_src": "fallback",
            "host_bit": 64,
            "account_region": "cn",
            "dragon_device_type": "phone",
        },
        "platform": "Android",
        "cdid": identifiers["cdid"],
        "sim_serial_number": [],
        "version": 1785490561,
    }


def post_encrypted(
    state: dict,
    path: str,
    obj: dict,
    timeout: float,
) -> dict:
    now = now_ms()
    raw = json.dumps(obj, ensure_ascii=False, separators=(",", ":")).encode()
    body = hgsign.tt_encrypt(gzip.compress(raw, mtime=0))
    registration = state["registration"]
    query = urllib.parse.urlencode(
        {
            "version_code": "73932",
            "device_platform": "android",
            "device_id": registration["device_id"],
            "aid": "8662",
            "iid": registration["install_id"],
            "tt_data": "a",
        }
    )
    headers = {
        "User-Agent": register_probe.USER_AGENT,
        "Content-Type": "application/octet-stream;tt-data=a",
        "log-encode-type": "gzip",
        "x-ss-dp": "8662",
        "x-ss-req-ticket": str(now),
        "x-vc-bdturing-sdk-version": "4.0.3.cn",
        "x-tt-trace-id": playback_probe.trace_id(),
        "x-ss-stub": hashlib.md5(body).hexdigest().upper(),
        "Accept-Encoding": "gzip, deflate, br",
        "Cookie": playback_probe.cookie_header(state),
    }
    request = urllib.request.Request(
        "https://log5-applog.fqnovel.com" + path + "?" + query,
        data=body,
        headers=headers,
        method="POST",
    )
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            status = response.status
            raw_response = response.read()
            response_headers = response.headers
    except urllib.error.HTTPError as exc:
        status = exc.code
        raw_response = exc.read()
        response_headers = exc.headers
    raw_response = playback_probe.decode_response(
        raw_response, response_headers.get("Content-Encoding", "")
    )
    try:
        parsed = json.loads(raw_response.decode("utf-8"))
    except Exception:
        parsed = {"raw": raw_response[:500].decode("utf-8", "replace")}
    return {
        "status": status,
        "logid": response_headers.get("x-tt-logid", ""),
        "body": parsed,
    }


def send_app_log(state: dict, timeout: float) -> dict:
    now = now_ms()
    session = str(uuid.uuid4())
    cold = str(uuid.uuid4())
    datetime_text = datetime.fromtimestamp(now / 1000, CHINA_TZ).strftime(
        "%Y-%m-%d %H:%M:%S"
    )
    obj = {
        "event_v3": [
            {
                "nt": 4,
                "ab_sdk_version": AB_SDK_VERSION,
                "event": "sdk_session_launch",
                "params": {
                    "sdk_aid": 71769,
                    "sdk_version": "0.5.5-rc.14.2-bugfix",
                    "passport-sdk-version": 5051452,
                    "params_for_special": "uc_login",
                    "cold_start_session_id": cold,
                },
                "event_id": 1,
                "tea_event_index": 1,
                "local_time_ms": now,
                "session_id": session + "#0",
                "datetime": datetime_text,
            },
            {
                "nt": 4,
                "ab_sdk_version": AB_SDK_VERSION,
                "event": "app_coldstart",
                "params": {
                    "cold_start_session_id": cold,
                    "normal_session_id": session + "#0",
                    "local_time_ms": now,
                    "network_type": "online_wifi",
                },
                "event_id": 2,
                "tea_event_index": 2,
                "local_time_ms": now,
                "session_id": session + "#0",
                "datetime": datetime_text,
            },
        ],
        "launch": [
            {
                "datetime": datetime_text,
                "session_id": session + "#0",
                "local_time_ms": now,
                "tea_event_index": 0,
                "is_background": True,
                "ab_sdk_version": AB_SDK_VERSION,
            }
        ],
        "magic_tag": "ss_app_log",
        "header": app_header(state),
        "_gen_time": now,
    }
    return post_encrypted(state, "/service/2/app_log/", obj, timeout)


def send_log_settings(state: dict, timeout: float) -> dict:
    return post_encrypted(
        state,
        "/service/2/log_settings/",
        {
            "magic_tag": "ss_app_log",
            "header": app_header(state),
            "_gen_time": now_ms(),
        },
        timeout,
    )


def settings_post_nul(
    state: dict,
    timeout: float,
    settings_time: int | str = 0,
    ctx_infos: str = "",
) -> dict:
    """官方 Bullet 配置拉取：query 带 device_id=0，body 为单个 NUL。"""
    now = now_ms()
    session = str(uuid.uuid4())
    cold = str(uuid.uuid4())
    params = {
        "app": "1",
        "device_platform": "android",
        "aid": "8662",
        "channel": "69310258a",
        "device_brand": "Xiaomi",
        "device_id": "0",
        "device_type": "2304FPN6DC",
        "os_api": "32",
        "os_version": "12",
        "update_version_code": "73932",
        "version_code": "73932",
        "settings_time": str(settings_time),
        "ctx_infos": ctx_infos,
        "ac": "wifi",
        "app_name": "novelread",
        "version_name": "7.3.9.32",
        "os": "android",
        "ssmix": "a",
        "language": "zh",
        "manifest_version_code": "73932",
        "resolution": "1920*1080",
        "dpi": "280",
        "_rticket": str(now),
        "normal_session_cnt_in_day": "2",
        "cold_start_session_cnt_in_day": "1",
        "host_abi": "arm64-v8a",
        "dragon_device_type": "phone",
        "sys_mini_window": "0",
        "pv_player": "73932",
        "app_mini_window": "0",
        "normal_session_id": session + "#1",
        "compliance_status": "0",
        "har_status": "0",
        "cold_start_session_id": cold,
        "cold_start_session_cnt_in_life": "1",
        "charging": "0",
        "normal_session_cnt_in_life": "2",
        "is_power_save_mode": "0",
        "app_dark_mode": "1",
        "screen_brightness": "1",
        "battery_pct": "0",
        "down_speed": "60000",
        "sys_dark_mode": "0",
        "need_personal_recommend": "1",
        "player_so_load": "1",
        "font_scale": "100",
        "is_android_pad_screen": "1",
        "network_type": "4",
        "rom_version": "W528JS release-keys",
        "current_volume": "1",
        "cdid": state["identifiers"]["cdid"],
        "account_region": "cn",
    }
    body = b"\x00"
    signer = hgsign.Signer(device=playback_probe.device_profile(state))
    signed = signer.sign(
        "https://is.snssdk.com/service/settings/v3/",
        params,
        body=body,
        mode="hg",
        extra_headers={
            "Cookie": playback_probe.cookie_header(state),
            "x-tt-store-region": "cn-gd",
            "x-tt-store-region-src": "did",
            "x-ss-dp": "8662",
        },
    )
    headers = dict(signed["headers"])
    headers.pop("x-tt-dt", None)
    headers.pop("x-tt-request-tag", None)
    headers.update(
        {
            "User-Agent": playback_probe.VIDEO_UA,
            "Content-Type": "application/json",
            "x-vc-bdturing-sdk-version": "4.0.3.cn",
            "passport-sdk-version": "5051452",
            "sdk-version": "2",
            "x-tt-trace-id": playback_probe.trace_id(),
        }
    )
    request = urllib.request.Request(
        signed["url"],
        data=body,
        headers=headers,
        method="POST",
    )
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            status = response.status
            raw = response.read()
            response_headers = response.headers
    except urllib.error.HTTPError as exc:
        status = exc.code
        raw = exc.read()
        response_headers = exc.headers
    raw = playback_probe.decode_response(
        raw, response_headers.get("Content-Encoding", "")
    )
    try:
        parsed = json.loads(raw.decode("utf-8"))
    except Exception:
        parsed = {"raw": raw[:500].decode("utf-8", "replace")}
    return {
        "status": status,
        "bytes": len(raw),
        "message": parsed.get("message"),
        "settings_time": (parsed.get("data") or {}).get("settings_time"),
        "ctx_infos": (parsed.get("data") or {}).get("ctx_infos"),
    }


def signed_json_request(
    state: dict,
    host: str,
    path: str,
    body_obj: dict,
    timeout: float,
) -> tuple[int, dict]:
    raw_body = json.dumps(
        body_obj, ensure_ascii=False, separators=(",", ":")
    ).encode("utf-8")
    gzip_body = playback_probe.java_gzip(raw_body)
    signer = hgsign.Signer(device=playback_probe.device_profile(state))
    signed = signer.sign(
        host + path,
        playback_probe.query_values(state),
        body=gzip_body,
        mode="hg",
    )
    headers = dict(signed["headers"])
    headers.pop("x-tt-dt", None)
    headers.pop("x-tt-request-tag", None)
    headers.update(
        {
            "User-Agent": playback_probe.VIDEO_UA,
            "Content-Type": "application/json; charset=utf-8",
            "Content-Encoding": "gzip",
            "Accept": "application/json; charset=utf-8,application/x-protobuf",
            "Accept-Encoding": "gzip, deflate, br",
            "x-ss-dp": "8662",
            "x-vc-bdturing-sdk-version": "4.0.3.cn",
            "passport-sdk-version": "5051452",
            "lc": "101",
            "sdk-version": "2",
            "x-tt-trace-id": playback_probe.trace_id(),
            "x-reading-request": (
                f"{headers.get('x-ss-req-ticket', now_ms())}-"
                f"{secrets.randbelow(90_000_000) + 10_000_000}"
            ),
            "Cookie": playback_probe.cookie_header(state),
            "x-tt-store-region": "cn-gd",
            "x-tt-store-region-src": "did",
        }
    )
    request = urllib.request.Request(
        signed["url"],
        data=gzip_body,
        headers=headers,
        method="POST",
    )
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            status = response.status
            raw = response.read()
            response_headers = response.headers
    except urllib.error.HTTPError as exc:
        status = exc.code
        raw = exc.read()
        response_headers = exc.headers
    raw = playback_probe.decode_response(
        raw, response_headers.get("Content-Encoding", "")
    )
    if not raw:
        return status, {}
    return status, json.loads(raw.decode("utf-8"))


def preload_video_detail(state: dict, series_id: str, timeout: float) -> dict:
    status, data = signed_json_request(
        state,
        PLAYER_HOST,
        VIDEO_DETAIL_PRELOAD_PATH,
        {
            "biz_param": {
                "detail_page_version": 0,
                "disable_digg_stat": False,
                "disable_video_relate_book": False,
                "image_shrink_datas_str": (
                    "W3siaW1hZ2VfdHlwZSI6MywiaW1hZ2Vfd2lkdGgiOjEwODAsInNocmlua190eXBlIjozfSx7"
                    "ImltYWdlX3R5cGUiOjQsImltYWdlX3dpZHRoIjo2Mywic2hyaW5rX3R5cGUiOjR9XQ=="
                ),
                "need_all_video_definition": False,
                "need_mp4_align": False,
                "screen_width_px": "1080",
                "source": 7,
                "use_os_player": False,
                "use_server_dns": False,
            },
            "dr_scene": "preload",
            "series_id": series_id,
        },
        timeout,
    )
    return {
        "status": status,
        "code": playback_probe.response_code(data),
        "series_count": len((data.get("data") or {}).keys()),
    }


def request_player_batch(
    state: dict,
    video_ids: list[str],
    timeout: float,
) -> dict:
    status, data = signed_json_request(
        state,
        PLAYER_HOST,
        PLAYER_BATCH_PATH,
        {
            "biz_param": {
                "detail_page_version": 0,
                "device_level": 3,
                "disable_digg_stat": False,
                "disable_video_relate_book": False,
                "need_all_video_definition": True,
                "need_mp4_align": False,
                "use_os_player": False,
                "use_server_dns": True,
                "use_server_dns_scene": "cold_start_stage",
                "video_platform": 1024,
            },
            "dr_scene": "player",
            "mixed_video_id_map": {"1004": video_ids},
        },
        timeout,
    )
    root = data.get("data") or {}
    models = sum(
        1
        for video_id in video_ids
        if isinstance(root.get(video_id), dict)
        and root[video_id].get("video_model")
    )
    return {
        "status": status,
        "code": playback_probe.response_code(data),
        "requested": len(video_ids),
        "models": models,
    }


def load_video_ids(
    state: dict,
    series_id: str,
    timeout: float,
    count: int,
) -> tuple[list[str], dict]:
    status, data, _ = playback_probe.signed_json_request(
        state,
        playback_probe.VIDEO_DETAIL_PATH,
        playback_probe.detail_body(series_id),
        timeout,
    )
    video_data = (data.get("data") or {}).get("video_data") or {}
    video_list = video_data.get("video_list") or []
    ids = [
        str(item.get("vid") or item.get("video_id") or "")
        for item in video_list
        if isinstance(item, dict)
    ]
    ids = [video_id for video_id in ids if video_id]
    source = "video_detail"
    if len(ids) < count:
        fallback = RUNTIME / "multi-chain-30-20261107.json"
        if fallback.exists():
            cached = json.loads(fallback.read_text(encoding="utf-8"))
            if cached.get("series_id") == series_id:
                ids = [
                    str(item.get("video_id") or "")
                    for item in cached.get("checks") or []
                    if isinstance(item, dict)
                ]
                ids = [video_id for video_id in ids if video_id]
                source = "multi-chain-30-cache"
    return ids[:count], {
        "status": status,
        "code": playback_probe.response_code(data),
        "count": len(ids),
        "source": source,
    }


def probe_videos(
    state: dict,
    video_ids: list[str],
    timeout: float,
    interval: float,
) -> list[dict]:
    results = []
    started = time.monotonic()
    for index, video_id in enumerate(video_ids):
        target = started + index * interval
        delay = target - time.monotonic()
        if delay > 0:
            time.sleep(delay)
        row_started = now_ms()
        try:
            result = playback_probe.probe_episode(state, video_id, timeout)
        except Exception as exc:
            result = {
                "video_id": video_id,
                "model_ok": False,
                "url_ok": False,
                "error": str(exc),
            }
        result["index"] = index + 1
        result["started_at"] = row_started
        result["elapsed_ms"] = now_ms() - row_started
        results.append(result)
        print(
            f"[{index + 1:02d}/{len(video_ids)}] "
            f"model={bool(result.get('model_ok'))} "
            f"url={bool(result.get('url_ok'))} "
            f"code={result.get('model_code')} "
            f"error={result.get('error') or '-'}"
        )
    return results


def main() -> None:
    parser = argparse.ArgumentParser(
        description="注册、激活并连续取 20 集播放地址"
    )
    parser.add_argument("--series-id", default=DEFAULT_SERIES_ID)
    parser.add_argument("--count", type=int, default=20)
    parser.add_argument("--interval", type=float, default=1.0)
    parser.add_argument("--timeout", type=float, default=20.0)
    parser.add_argument("--state", type=Path)
    parser.add_argument("--output", type=Path)
    parser.add_argument(
        "--ipv6",
        default="",
        help="注册请求中的 client_anpi IPv6；官方 HAR 默认非空",
    )
    parser.add_argument(
        "--android-id",
        default="",
        help="有效 Android ID；传入后 openudid 使用同一个值",
    )
    parser.add_argument(
        "--confirm-register",
        action="store_true",
        help="首次注册后再带身份和 Cookie 注册确认一次",
    )
    parser.add_argument(
        "--official-prelude",
        action="store_true",
        help="先按官方顺序请求 multi_video_detail/preload 和 player 批量模型",
    )
    parser.add_argument(
        "--settings-post",
        action="store_true",
        help="发送官方 Bullet settings/v3 POST（body 为单个 NUL）",
    )
    args = parser.parse_args()
    if args.count < 1:
        raise SystemExit("--count 必须 >= 1")

    stamp = time.strftime("%Y%m%d-%H%M%S")
    state_path = args.state or RUNTIME / f"activation-20-{stamp}.state.json"
    output_path = args.output or RUNTIME / f"activation-20-{stamp}.json"
    state = fresh_state(
        state_path,
        f"activation_20_{stamp}",
        args.ipv6,
        args.android_id,
    )
    registration = state["registration"]
    print(
        "registered",
        f"device_id={registration['device_id']}",
        f"install_id={registration['install_id']}",
        f"new_user={registration.get('new_user')}",
    )
    if args.confirm_register:
        registration = confirm_registration(state, state_path, args.timeout)
        print(
            "registration confirmed",
            f"device_id={registration['device_id']}",
            f"install_id={registration['install_id']}",
            f"new_user={registration.get('new_user')}",
        )

    stages = []
    stages.append(
        {
            "stage": "register_only",
            "at": now_ms(),
            "activation": activation_status(state, args.timeout),
        }
    )
    app_log_response = send_app_log(state, args.timeout)
    stages.append(
        {
            "stage": "app_log",
            "at": now_ms(),
            "response": app_log_response,
            "activation": activation_status(state, args.timeout),
        }
    )
    log_settings_response = send_log_settings(state, args.timeout)
    stages.append(
        {
            "stage": "log_settings",
            "at": now_ms(),
            "response": log_settings_response,
            "activation": activation_status(state, args.timeout),
        }
    )
    settings_response = warmup_probe.settings_v3(state, args.timeout)
    stages.append(
        {
            "stage": "settings_v3",
            "at": now_ms(),
            "response": settings_response,
            "activation": activation_status(state, args.timeout),
        }
    )
    domains_response = warmup_probe.get_domains(state, args.timeout)
    stages.append(
        {
            "stage": "get_domains",
            "at": now_ms(),
            "response": domains_response,
            "activation": activation_status(state, args.timeout),
        }
    )
    if args.settings_post:
        settings_post = settings_post_nul(state, args.timeout)
        stages.append(
            {
                "stage": "settings_v3_post_bullet",
                "at": now_ms(),
                "response": settings_post,
                "activation": activation_status(state, args.timeout),
            }
        )
        print(
            "settings_v3_post_bullet",
            f"status={settings_post['status']}",
            f"bytes={settings_post['bytes']}",
            f"message={settings_post['message']}",
        )
    for stage in stages:
        activation = stage.get("activation") or {}
        print(
            stage["stage"],
            f"activated={activation.get('is_activated')}",
            f"activation_status={activation.get('status')}",
        )

    video_ids, detail = load_video_ids(
        state, args.series_id, args.timeout, args.count
    )
    print(
        "video_detail",
        f"status={detail['status']}",
        f"code={detail['code']}",
        f"ids={len(video_ids)}",
        f"source={detail['source']}",
    )
    if len(video_ids) < args.count:
        raise RuntimeError(
            f"只取到 {len(video_ids)} 个 video_id，无法测试 {args.count} 集"
        )

    if args.official_prelude:
        prelude = {
            "multi_video_detail_preload": preload_video_detail(
                state, args.series_id, args.timeout
            ),
            "player_batch": request_player_batch(
                state, video_ids[:15], args.timeout
            ),
        }
        stages.append({"stage": "official_playback_prelude", "at": now_ms(), **prelude})
        print(
            "official_playback_prelude",
            f"detail_code={prelude['multi_video_detail_preload']['code']}",
            f"batch_code={prelude['player_batch']['code']}",
            f"batch_models={prelude['player_batch']['models']}/15",
        )

    time.sleep(max(0.0, args.interval))
    results = probe_videos(
        state, video_ids[: args.count], args.timeout, args.interval
    )
    summary = {
        "model_ok": sum(1 for item in results if item.get("model_ok")),
        "url_ok": sum(1 for item in results if item.get("url_ok")),
        "failed": sum(1 for item in results if not item.get("url_ok")),
        "requested": len(results),
        "interval_seconds": args.interval,
    }
    result = {
        "checked_at": now_ms(),
        "state": str(state_path),
        "identifiers": state["identifiers"],
        "registration": {
            key: registration.get(key)
            for key in (
                "device_id",
                "install_id",
                "new_user",
                "server_time",
                "cookies",
            )
        },
        "series_id": args.series_id,
        "stages": stages,
        "video_detail": detail,
        "summary": summary,
        "results": results,
    }
    output_path.parent.mkdir(parents=True, exist_ok=True)
    output_path.write_text(
        json.dumps(result, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )
    print("summary:", json.dumps(summary, ensure_ascii=False))
    print("result:", output_path)
    if summary["url_ok"] < summary["requested"]:
        raise SystemExit(2)


if __name__ == "__main__":
    main()
