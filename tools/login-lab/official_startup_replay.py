# SPDX-License-Identifier: GPL-3.0-only
"""按官方 7.3.9.32 HAR 的启动顺序复放关键链路。

顺序：
1. device_register
2. settings/v3 GET（lynx）
3. get_domains
4. settings/v3 POST（Bullet，单 NUL body）
5. app_alert_check / app_log / log_settings
6. bookmall 推荐页
7. video_detail / multi_video_detail preload
8. multi_video_model/player 批量
9. multi_video_model/preload 补批
10. 单集 video_model 对照

脚本会把每一步的请求状态、响应大小、从响应中带出的
settings_time / ctx_infos / is_activated / video_id 记录到结果文件。
"""
from __future__ import annotations

import argparse
import hashlib
import json
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
from pathlib import Path

import hgsign
import playback_probe
import register_probe
import warmup_probe
from activation_20_probe import (
    activation_status,
    fresh_identifiers,
    load_video_ids,
    now_ms,
    preload_video_detail,
    probe_videos,
    register_fresh,
    request_player_batch,
    send_app_log,
    send_log_settings,
    settings_post_nul,
)

HERE = Path(__file__).resolve().parent
RUNTIME = HERE / ".runtime"
SETTINGS_HOST = "https://is.snssdk.com"
SETTINGS_PATH = "/service/settings/v3/"
SECURITY_HOST = "https://security.snssdk.com"
ACCOUNT_INFO_PATH = "/passport/account/info/v2/"
DEFAULT_SERIES_ID = "7702020975402961945"
EVENT_REPORT_HOST = "https://reading.snssdk.com"
EVENT_REPORT_PATH = "/reading/user/event/report"


def merge_response_cookies(state: dict, headers) -> dict[str, str]:
    merged = {}
    for header in headers.get_all("Set-Cookie") or []:
        pair = header.split(";", 1)[0].strip()
        if "=" not in pair:
            continue
        name, value = pair.split("=", 1)
        name = name.strip()
        value = value.strip()
        if name and value:
            merged[name] = value
    if merged:
        cookies = state["registration"].setdefault("cookies", {})
        cookies.update(merged)
    return merged


def privacy_event_report(state: dict, timeout: float) -> dict:
    now = now_ms()
    session_id = f"{now}_{uuid.uuid4()}"
    extra = {
        "privacy_after_home_config": '{"config":-1}',
        "privacy_popup_compact_config": '{"enable":true,"style":1}',
    }
    events = [
        {
            "event_name": "launch",
            "event_id": f"{now}_{uuid.uuid4()}",
            "session_id": session_id,
            "timestamp": now,
            "extra": extra,
        },
        {
            "event_name": "privacy_dialog_show",
            "event_id": f"{now}_{uuid.uuid4()}",
            "session_id": session_id,
            "timestamp": now,
            "extra": extra,
        },
        {
            "event_name": "privacy_dialog_click",
            "event_id": f"{now}_{uuid.uuid4()}",
            "session_id": session_id,
            "timestamp": now,
            "action": "agree",
            "extra": extra,
        },
    ]
    body = json.dumps(
        {"report_type": 44, "new_user_event_report_data": events},
        ensure_ascii=False,
        separators=(",", ":"),
    ).encode("utf-8")
    params = {
        "aid": "8662",
        "app_id": "8662",
        "platform": "android",
        "device_platform": "android",
        "version_code": "73932",
        "update_version_code": "73932",
        "manifest_version_code": "73932",
        "version_name": "7.3.9.32",
        "app_version": "7.3.9.32",
        "app_name": "novelread",
        "channel": "69310258a",
        "os_version": "12",
        "os_api": "32",
        "device_type": "2304FPN6DC",
        "device_brand": "Xiaomi",
        "language": "zh",
        "cdid": state["identifiers"]["cdid"],
    }
    request = urllib.request.Request(
        EVENT_REPORT_HOST + EVENT_REPORT_PATH + "?" + urllib.parse.urlencode(params),
        data=body,
        headers={
            "User-Agent": "com.phoenix.read/73932",
            "x-xs-from-web": "0",
            "x-ss-req-ticket": str(now),
            "x-ss-stub": hashlib.md5(body).hexdigest().upper(),
            "sdk-version": "2",
            "Content-Type": "application/json; charset=utf-8",
            "Accept-Encoding": "gzip",
        },
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
    parsed = json.loads(raw.decode("utf-8")) if raw else {}
    return {
        "status": status,
        "bytes": len(raw),
        "code": parsed.get("code"),
        "message": parsed.get("message"),
    }


def signed_get_json(
    state: dict,
    host: str,
    path: str,
    params: dict[str, str],
    timeout: float,
) -> dict:
    signer = hgsign.Signer(device=playback_probe.device_profile(state))
    signed = signer.sign(
        host + path,
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
    headers.pop("x-tt-request-tag", None)
    headers.update(
        {
            "User-Agent": playback_probe.VIDEO_UA,
            "Accept": "application/json, text/plain, */*",
            "Accept-Encoding": "gzip, deflate, br",
            "x-tt-trace-id": playback_probe.trace_id(),
        }
    )
    request = urllib.request.Request(signed["url"], headers=headers, method="GET")
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
    set_cookies = merge_response_cookies(state, response_headers)
    body = json.loads(raw.decode("utf-8")) if raw else {}
    return {
        "status": status,
        "bytes": len(raw),
        "set_cookies": set_cookies,
        "body": body,
    }


def settings_get_lynx(state: dict, timeout: float) -> dict:
    response = signed_get_json(
        state,
        SETTINGS_HOST,
        SETTINGS_PATH,
        {
            "caller_name": "lynx",
            "os_type": "android",
            "aid": "8662",
            "sdk_version": "4.1.1-rc.12",
            "app_version": "73932",
            "device_id": "0",
            "channel": "69310258a",
            "settings_time": "0",
        },
        timeout,
    )
    data = response["body"].get("data") or {}
    return {
        "status": response["status"],
        "bytes": response["bytes"],
        "settings_time": data.get("settings_time"),
        "ctx_infos": data.get("ctx_infos"),
        "settings_count": len((data.get("settings") or {}).keys()),
    }


def account_info(state: dict, timeout: float) -> dict:
    params = playback_probe.query_values(state)
    params.update(
        {
            "passport-sdk-version": "5051452",
            "scene": "normal",
            "account_sdk_source": "app",
            "passport_support_flow": "captcha,verify",
            "cronet_version": "8d40f833_2026-03-03",
            "ttnet_version": "4.2.243.31-douyin",
            "use_store_region_cookie": "1",
        }
    )
    response = signed_get_json(
        state,
        SECURITY_HOST,
        ACCOUNT_INFO_PATH,
        params,
        timeout,
    )
    data = response["body"].get("data") or {}
    return {
        "status": response["status"],
        "bytes": response["bytes"],
        "error_code": data.get("error_code"),
        "message": response["body"].get("message"),
        "set_cookies": response["set_cookies"],
    }


def main() -> None:
    parser = argparse.ArgumentParser(
        description="按官方 HAR 顺序复放注册、配置、激活和播放请求"
    )
    parser.add_argument("--series-id", default=DEFAULT_SERIES_ID)
    parser.add_argument("--count", type=int, default=20)
    parser.add_argument("--interval", type=float, default=1.0)
    parser.add_argument("--timeout", type=float, default=20.0)
    parser.add_argument("--ipv6", default="")
    parser.add_argument("--android-id", default="")
    parser.add_argument("--state", type=Path)
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()

    stamp = time.strftime("%Y%m%d-%H%M%S")
    state_path = args.state or RUNTIME / f"official-startup-{stamp}.state.json"
    output_path = args.output or RUNTIME / f"official-startup-{stamp}.json"
    state = fresh_identifiers(f"official_startup_{stamp}", args.android_id)
    stages = []

    def record(stage: str, payload: dict) -> None:
        row = {"stage": stage, "at": now_ms(), **payload}
        stages.append(row)
        print(stage, json.dumps(payload, ensure_ascii=False)[:1200])

    privacy = privacy_event_report(state, args.timeout)
    record("privacy_event_report", privacy)

    state = register_fresh(state, state_path, args.timeout, args.ipv6)
    registration = state["registration"]
    record(
        "device_register",
        {
            "status": registration["status"],
            "device_id": registration["device_id"],
            "install_id": registration["install_id"],
            "new_user": registration.get("new_user"),
        },
    )

    settings = settings_get_lynx(state, args.timeout)
    record("settings_v3_get_lynx", settings)
    settings_time = settings.get("settings_time") or 0
    ctx_infos = settings.get("ctx_infos") or ""

    domains = warmup_probe.get_domains(state, args.timeout)
    record("get_domains", domains)

    settings_post = settings_post_nul(
        state,
        args.timeout,
        settings_time=settings_time,
        ctx_infos=ctx_infos,
    )
    record("settings_v3_post_bullet", settings_post)

    feed = warmup_probe.bookmall_feed(state, args.timeout, 1)
    record("bookmall_feed", feed)

    account = account_info(state, args.timeout)
    record("passport_account_info", account)
    register_probe.save_state(state_path, state)

    activation = activation_status(state, args.timeout)
    record("app_alert_check", activation)

    app_log = send_app_log(state, args.timeout)
    record("app_log", app_log)
    activation_after_app_log = activation_status(state, args.timeout)
    record("app_alert_check_after_app_log", activation_after_app_log)

    log_settings = send_log_settings(state, args.timeout)
    record("log_settings", log_settings)
    activation_after_log_settings = activation_status(state, args.timeout)
    record("app_alert_check_after_log_settings", activation_after_log_settings)

    video_ids, detail = load_video_ids(
        state,
        args.series_id,
        args.timeout,
        args.count,
    )
    record(
        "video_detail",
        {
            **detail,
            "video_ids": video_ids,
        },
    )
    if len(video_ids) < args.count:
        raise RuntimeError(
            f"只取到 {len(video_ids)} 个 video_id，无法复放 {args.count} 集"
        )

    detail_preload = preload_video_detail(state, args.series_id, args.timeout)
    record("multi_video_detail_preload", detail_preload)

    player_batch = request_player_batch(
        state,
        video_ids[: min(15, len(video_ids))],
        args.timeout,
    )
    record("multi_video_model_player", player_batch)

    if len(video_ids) > 15:
        preload_batch = request_player_batch(
            state,
            video_ids[15:args.count],
            args.timeout,
        )
        record("multi_video_model_second_batch", preload_batch)

    singles = probe_videos(
        state,
        video_ids[: args.count],
        args.timeout,
        args.interval,
    )
    summary = {
        "activated": activation_after_log_settings.get("is_activated"),
        "settings_time": settings_time,
        "ctx_infos_present": bool(ctx_infos),
        "video_ids": len(video_ids),
        "single_model_ok": sum(1 for row in singles if row.get("model_ok")),
        "single_url_ok": sum(1 for row in singles if row.get("url_ok")),
        "single_failed": sum(1 for row in singles if not row.get("url_ok")),
    }
    result = {
        "checked_at": now_ms(),
        "state": str(state_path),
        "series_id": args.series_id,
        "identifiers": state["identifiers"],
        "registration": registration,
        "stages": stages,
        "singles": singles,
        "summary": summary,
    }
    output_path.parent.mkdir(parents=True, exist_ok=True)
    output_path.write_text(
        json.dumps(result, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )
    print("summary:", json.dumps(summary, ensure_ascii=False))
    print("result:", output_path)


if __name__ == "__main__":
    main()
