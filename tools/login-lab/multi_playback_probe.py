# SPDX-License-Identifier: GPL-3.0-only
"""Fetch several episode models in one official preload request.

The official 7.3.9.32 first-play flow does not call the single-item
``video_model`` endpoint once per episode. It calls
``multi_video_model/preload`` with all episode IDs and receives one model per
ID. This script reproduces that request with a fresh anonymous device and
checks every returned fallback URL with an HTTP Range request.
"""
from __future__ import annotations

import argparse
import json
import random
import secrets
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
from pathlib import Path

import hgsign
import playback_probe
import register_probe

BATCH_HOST = "https://api5-sinfnormal.fqnovel.com"
BATCH_PATH = "/novel/player/multi_video_model/preload/v1"


def fresh_state(path: Path) -> dict:
    # Keep this path HTTP-only. register_probe.make_identifiers() consults adb
    # when no Android ID is supplied, so generate the identifiers directly.
    openudid = secrets.token_hex(8)
    identifiers = {
        "android_id": "",
        "openudid": openudid,
        "openudid_source": "forced_new_16hex",
        "cdid": str(uuid.uuid4()),
        "clientudid": str(uuid.uuid4()),
    }
    state = {
        "install_time": register_probe.now_ms(),
        "identifiers": identifiers,
    }
    status, data, cookies, encrypted = register_probe.register_once(
        identifiers=identifiers,
        state=state,
        include_identity=False,
        send_cookies=False,
        ipv6_value="",
        timeout=20.0,
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
        raise RuntimeError(f"registration failed: HTTP {status}: {data}")
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
        "updated_at": register_probe.now_ms(),
    }
    register_probe.save_state(path, state)
    return state


def batch_body(video_ids: list[str]) -> dict:
    return {
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
        "dr_scene": "preload",
        "mixed_video_id_map": {"1004": video_ids},
    }


def request_batch(
    state: dict,
    video_ids: list[str],
    timeout: float,
) -> tuple[int, dict, dict, bytes]:
    body = batch_body(video_ids)
    raw = json.dumps(
        body, ensure_ascii=False, separators=(",", ":")
    ).encode("utf-8")
    payload = playback_probe.java_gzip(raw)
    signer = hgsign.Signer(device=playback_probe.device_profile(state))
    signed = signer.sign(
        BATCH_HOST + BATCH_PATH,
        playback_probe.query_values(state),
        body=payload,
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
                f"{headers.get('x-ss-req-ticket', playback_probe.now_ms())}-"
                f"{random.randint(10000000, 99999999)}"
            ),
            "Cookie": playback_probe.cookie_header(state),
            "x-tt-store-region": "cn-gd",
            "x-tt-store-region-src": "did",
        }
    )
    request = urllib.request.Request(
        signed["url"],
        data=payload,
        headers=headers,
        method="POST",
    )
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            status = response.status
            response_headers = response.headers
            encoded = response.read()
    except urllib.error.HTTPError as exc:
        status = exc.code
        response_headers = exc.headers
        encoded = exc.read()
    decoded = playback_probe.decode_response(
        encoded, response_headers.get("Content-Encoding", "")
    )
    if not decoded:
        raise RuntimeError(
            f"{BATCH_PATH} returned an empty body: "
            f"HTTP {status}, logid={response_headers.get('x-tt-logid', '')}"
        )
    data = json.loads(decoded.decode("utf-8"))
    return status, data, body, decoded


def model_of(item: dict) -> dict | None:
    value = item.get("video_model")
    if isinstance(value, str):
        value = json.loads(value)
    return value if isinstance(value, dict) else None


def check_model(video_id: str, model: dict | None, timeout: float) -> dict:
    result = {
        "video_id": video_id,
        "model_ok": bool(model),
        "duration": (model or {}).get("video_duration"),
        "url_checked": False,
        "url_ok": False,
    }
    if not model:
        return result
    fallback = playback_probe.fallback_url(model)
    if not fallback.startswith("http"):
        result["error"] = "fallback_api is empty"
        return result
    result["fallback_host"] = urllib.parse.urlparse(fallback).netloc
    info_root = playback_probe.fetch_json(fallback, timeout)
    info = ((info_root.get("video_info") or {}).get("data") or {})
    seed = str(info.get("key_seed") or "")
    attempts = []
    for row in playback_probe.video_rows(info):
        raw_url = str(row.get("main_url") or row.get("backup_url") or "")
        if not raw_url:
            continue
        real_url = playback_probe.decrypt_url(raw_url, seed)
        check = playback_probe.range_probe(real_url, timeout)
        check["quality"] = str(
            row.get("quality_desc")
            or row.get("height")
            or row.get("vheight")
            or len(attempts) + 1
        )
        attempts.append(check)
        if check.get("playable"):
            break
    result["url_checked"] = bool(attempts)
    result["url_ok"] = any(item.get("playable") for item in attempts)
    result["attempts"] = attempts
    return result


def main() -> None:
    parser = argparse.ArgumentParser(
        description="Official multi_video_model/preload playback probe"
    )
    parser.add_argument("--state", type=Path)
    parser.add_argument("--fresh", action="store_true")
    parser.add_argument("--video-ids", default="")
    parser.add_argument("--timeout", type=float, default=20.0)
    parser.add_argument(
        "--output",
        type=Path,
        default=Path(__file__).resolve().parent
        / ".runtime"
        / "multi-playback-probe.json",
    )
    args = parser.parse_args()

    state_path = args.state
    if args.fresh or state_path is None:
        state_path = args.state or (
            Path(__file__).resolve().parent
            / ".runtime"
            / f"multi-playback-{uuid.uuid4().hex[:8]}.json"
        )
        state = fresh_state(state_path)
    else:
        state = playback_probe.registration_state(state_path)

    video_ids = [
        item.strip()
        for item in args.video_ids.split(",")
        if item.strip()
    ] or list(playback_probe.DEFAULT_VIDEO_IDS)
    status, response, request_body, _ = request_batch(
        state, video_ids, args.timeout
    )
    code = int(response.get("code", -1))
    models = {
        video_id: model_of((response.get("data") or {}).get(video_id) or {})
        for video_id in video_ids
    }
    checks = [
        check_model(video_id, models[video_id], args.timeout)
        for video_id in video_ids
    ]
    result = {
        "checked_at": playback_probe.now_ms(),
        "state": str(state_path),
        "identifiers": state.get("identifiers"),
        "registration": {
            "device_id": state["registration"]["device_id"],
            "install_id": state["registration"]["install_id"],
            "new_user": state["registration"].get("new_user"),
        },
        "batch_status": status,
        "batch_code": code,
        "batch_message": response.get("message"),
        "model_count": sum(1 for model in models.values() if model),
        "request": request_body,
        "checks": checks,
    }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(
        json.dumps(result, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )
    print(
        f"device_id={state['registration']['device_id']} "
        f"iid={state['registration']['install_id']} "
        f"batch_code={code} models={result['model_count']}/{len(video_ids)}"
    )
    for check in checks:
        print(
            f"{check['video_id']} model={check['model_ok']} "
            f"url={check['url_ok']} duration={check.get('duration')}"
        )
    print("result:", args.output)
    if code != 0 or result["model_count"] != len(video_ids) or not all(
        check["url_ok"] for check in checks
    ):
        raise SystemExit(2)


if __name__ == "__main__":
    main()
