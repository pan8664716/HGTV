# SPDX-License-Identifier: GPL-3.0-only
"""Reproduce the official first-launch warm-up sequence with pure HTTP.

The script consumes a state file produced by register_probe.py, sends the
startup endpoints in order, and probes three video_model responses after each
stage. It never invokes adb or reads device storage.
"""
from __future__ import annotations

import argparse
import json
import random
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
from pathlib import Path

import hgsign
import playback_probe

GET_DOMAINS_HOST = "https://tnc0-aliec2.zijieapi.com"
SETTINGS_HOST = "https://is.snssdk.com"
THANOS_HOST = "https://thanos.zijieapi.com"


def now_ms() -> int:
    return int(time.time() * 1000)


def trace_id() -> str:
    value = f"{random.getrandbits(128):032x}"
    return f"00-{value}-{value[:16]}-01"


def cookie_header(state: dict) -> str:
    cookies = (state.get("registration") or {}).get("cookies") or {}
    return "; ".join(f"{name}={value}" for name, value in cookies.items())


def common_query(state: dict) -> dict[str, str]:
    return playback_probe.query_values(state)


def http_get(
    state: dict,
    host: str,
    path: str,
    params: dict[str, str],
    timeout: float,
    signed: bool,
) -> dict:
    if signed:
        signer = hgsign.Signer(device=playback_probe.device_profile(state))
        signed_request = signer.sign(
            host + path,
            params,
            body=None,
            mode="hg",
            extra_headers={
                "Cookie": cookie_header(state),
                "x-tt-store-region": "cn-gd",
                "x-tt-store-region-src": "did",
                "x-ss-dp": "8662",
            },
        )
        url = signed_request["url"]
        headers = dict(signed_request["headers"])
    else:
        url = host + path + "?" + urllib.parse.urlencode(params)
        headers = {
            "User-Agent": playback_probe.VIDEO_UA,
            "Accept": "application/json, text/plain, */*",
            "Accept-Encoding": "gzip, deflate, br",
            "Cookie": cookie_header(state),
            "x-tt-trace-id": trace_id(),
        }
    headers.pop("x-tt-dt", None)
    request = urllib.request.Request(url, headers=headers, method="GET")
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            status = response.status
            response_headers = response.headers
            raw = response.read()
    except urllib.error.HTTPError as exc:
        status = exc.code
        response_headers = exc.headers
        raw = exc.read()
    decoded = playback_probe.decode_response(
        raw, response_headers.get("Content-Encoding", "")
    )
    preview = decoded[:500].decode("utf-8", "replace")
    code = None
    if decoded:
        try:
            code = json.loads(decoded.decode("utf-8")).get("code")
        except Exception:
            pass
    return {
        "status": status,
        "bytes": len(decoded),
        "code": code,
        "logid": response_headers.get("x-tt-logid", ""),
        "preview": preview,
    }


def get_domains(state: dict, timeout: float) -> dict:
    device = playback_probe.device_profile(state)
    params = {
        "aid": "8662",
        "device_id": device["device_id"],
        "device_platform": "android",
        "version_code": "73932",
        "channel": "69310258a",
        "update_version_code": "73932",
        "user_id": "0",
        "ac": "wifi",
        "device_type": device["device_type"],
        "app_name": "novelread",
        "os_version": device["os_version"],
        "os_api": device["os_api"],
        "device_brand": device["device_brand"],
        "version_name": "7.3.9.32",
        "manifest_version_code": "73932",
        "host_abi": "arm64-v8a",
        "cronet_version": "8d40f833_2026-03-03",
        "ttnet_version": "4.2.243.31-douyin",
        "use_store_region_cookie": "1",
        "tnc_src": "7",
    }
    return http_get(state, GET_DOMAINS_HOST, "/get_domains/v5/", params, timeout, False)


def settings_v3(state: dict, timeout: float) -> dict:
    params = {
        "caller_name": "lynx",
        "os_type": "android",
        "aid": "8662",
        "sdk_version": "4.1.1-rc.12",
        "app_version": "73932",
        "device_id": playback_probe.device_profile(state)["device_id"],
        "channel": "69310258a",
        "settings_time": "0",
    }
    return http_get(state, SETTINGS_HOST, "/service/settings/v3/", params, timeout, False)


def thanos(state: dict, timeout: float) -> dict:
    device = playback_probe.device_profile(state)
    params = {
        "country": "CN",
        "wifi": "1",
        "base_update_version_code": "73932",
        "device_id": device["device_id"],
        "channel": "69310258a",
        "new_package_md5": "",
        "device_type": device["device_type"],
        "language": "zh",
        "update_version_code": "73932",
        "os_api": device["os_api"],
        "cpu_abi": "64",
        "device_brand": device["device_brand"],
        "current_identity": uuid.uuid4().hex,
        "device_platform": "android",
        "diff_sdk_version": "3",
        "alg": "wp",
        "aid": "8662",
        "new_update_version_code": "0",
    }
    return http_get(state, THANOS_HOST, "/thanos/update/v1", params, timeout, False)


def bookmall_feed(state: dict, timeout: float, ordinal: int) -> dict:
    device = playback_probe.device_profile(state)
    session_uuid = str(uuid.uuid4())
    normal_session_id = f"{uuid.uuid4()}#0"
    cold_start_session_id = str(uuid.uuid4())
    params = common_query(state)
    params.update(
        {
            "normal_session_cnt_in_day": str(ordinal),
            "cold_start_session_cnt_in_day": str(max(1, ordinal)),
            "normal_session_id": normal_session_id,
            "cold_start_session_id": cold_start_session_id,
            "cold_start_session_cnt_in_life": str(max(1, ordinal)),
            "normal_session_cnt_in_life": str(ordinal),
            "session_uuid": session_uuid,
            "screen_width_px": "2160",
            "network_type": "4",
            "charging": "0",
            "is_power_save_mode": "0",
            "battery_pct": "50",
            "down_speed": "60000",
            "device_level": "3",
            "client_req_type": "1028",
            "tab_type": "16",
            "tab_index": "0",
            "offset": "0",
            "extra": str(ordinal + 1),
            "last_tab_type": "16",
            "landing_bottom_tab_type": "7",
            "bottom_tab_type": "7",
            "bottom_tab_type_list": "7,0,1,5,4",
            "is_video_feed_tab_first_request_cold_start": "true"
            if ordinal == 1
            else "false",
            "has_video_cache": "false",
            "ranklist_in_video_tab": "has_video_cache",
            "disable_digg_stat": "false",
            "need_personal_recommend": "1",
            "player_so_load": "1",
        }
    )
    return http_get(
        state,
        playback_probe.API_HOST,
        "/reading/bookapi/bookmall/tab/v",
        params,
        timeout,
        True,
    )


def playback_snapshot(state: dict, timeout: float, interval: float) -> list[dict]:
    rows = []
    for index, video_id in enumerate(playback_probe.DEFAULT_VIDEO_IDS):
        try:
            rows.append(playback_probe.probe_episode(state, video_id, timeout))
        except Exception as exc:
            rows.append(
                {
                    "video_id": video_id,
                    "model_ok": False,
                    "url_ok": False,
                    "error": str(exc),
                }
            )
        if index + 1 < len(playback_probe.DEFAULT_VIDEO_IDS):
            time.sleep(max(0.0, interval))
    return rows


def main() -> None:
    parser = argparse.ArgumentParser(description="Official first-launch warm-up probe")
    parser.add_argument("--state", type=Path, required=True)
    parser.add_argument("--timeout", type=float, default=15.0)
    parser.add_argument("--interval", type=float, default=2.0)
    parser.add_argument("--skip-baseline", action="store_true")
    parser.add_argument(
        "--output",
        type=Path,
        default=Path(__file__).resolve().parent
        / ".runtime"
        / "warmup-probe.json",
    )
    args = parser.parse_args()

    state = playback_probe.registration_state(args.state)
    stages = []

    def record(name: str, response: dict | None = None) -> None:
        stage = {"name": name, "at": now_ms()}
        if response is not None:
            stage["response"] = response
        if not (args.skip_baseline and name == "baseline"):
            stage["playback"] = playback_snapshot(state, args.timeout, args.interval)
        stages.append(stage)
        print(name, json.dumps(stage, ensure_ascii=False)[:1200])

    record("baseline")
    record("get_domains", get_domains(state, args.timeout))
    record("settings_v3", settings_v3(state, args.timeout))
    record("bookmall_feed_1", bookmall_feed(state, args.timeout, 1))
    record("bookmall_feed_2", bookmall_feed(state, args.timeout, 2))
    record("thanos", thanos(state, args.timeout))

    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(
        json.dumps(
            {
                "checked_at": now_ms(),
                "state": str(args.state),
                "stages": stages,
            },
            ensure_ascii=False,
            indent=2,
        )
        + "\n",
        encoding="utf-8",
    )
    print("result:", args.output)


if __name__ == "__main__":
    main()
