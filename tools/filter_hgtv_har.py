#!/usr/bin/env python3
"""Filter and deduplicate a Reqable HAR down to HGTV-relevant samples.

Only whole entries are removed. Every retained HAR entry is copied verbatim,
including URLs, query parameters, headers, cookies, request bodies, response
bodies and Reqable-specific metadata. Near-identical samples of the same
endpoint are reduced to one information-rich representative; the companion
CSV files still cover every HGTV-relevant source entry so parameter names and
values from dropped duplicates remain discoverable.
"""

from __future__ import annotations

import argparse
import base64
import copy
import csv
import hashlib
import json
import re
import sys
from collections import Counter, defaultdict
from dataclasses import dataclass
from pathlib import Path
from urllib.parse import parse_qsl, urlsplit

csv.field_size_limit(sys.maxsize)


@dataclass(frozen=True)
class Rule:
    category: str
    pattern: str
    reason: str


RULES = [
    Rule("activation", r"^/service/2/device_register/?$", "设备注册，HGTV 内容接口准入前提"),
    Rule("activation", r"^/service/2/log_settings/?$", "启动日志配置，未来激活链路复放可用"),
    Rule("activation", r"^/service/2/app_log/?$", "启动日志，会话保活与激活排查可用"),
    Rule("activation", r"^/service/settings/v3/?$", "TTNet / SDK 启动设置，现有复放脚本已使用"),
    Rule("activation", r"^/service/2/app_alert_check/?$", "App 版本/安全提示，未来激活排查可用"),
    Rule("activation", r"^/passport/account/info/v2/?$", "账号会话状态，SessionKeeper 已使用"),
    Rule("activation", r"^/passport/token/beat/v2/?$", "会话心跳，SessionKeeper 已使用"),
    Rule("activation", r"^/reading/user/event/report/?$", "启动/新用户事件上报"),
    Rule("activation", r"^/reading/user/recommend_feature/report/v/?$", "会话计数，激活链路排查可用"),
    Rule("activation", r"^/reading/crypt/registerkey/?$", "设备注册密钥协商，未来重放可用"),
    Rule("activation", r"^/privacy/(setting|teen_mode)/query/?$", "隐私/青少年模式设置，TV 端未来可用"),
    Rule("login", r"^/passport/mobile/send_code/v1/?$", "手机号登录发码"),
    Rule("login", r"^/passport/mobile/sms_login/?$", "手机号验证码登录"),
    Rule("login", r"^/passport/upsms/(verify|safe_mobile/verify|chain_mobile/verify|gen_verify_ticket)/?$", "上行短信二次验证"),
    Rule("login", r"^/passport/(web|open)/qrcode/(get|check)/?$", "扫码登录"),
    Rule("login", r"^/passport/web/(get_qrcode|check_qrconnect)/?$", "扫码登录"),
    Rule("search", r"^/reading/bookapi/search/(tab/v|tab/v1|suggest/v|rank/v|uncover_subscribe/v1)/?$", "搜索、联想词、榜单或预约"),
    Rule("search", r"^/reading/ai_search_stream/conversation_info/v/?$", "AI 搜索会话入口，未来搜索可用"),
    Rule("detail", r"^/novel/player/video_detail/v1/?$", "剧集详情与分集，HGTV 当前使用"),
    Rule("detail", r"^/novel/player/multi_video_detail/(preload|player)/v1/?$", "批量分集/详情，HGTV 当前使用"),
    Rule("detail", r"^/reading/bookapi/multi-detail/v/?$", "账号侧作品详情，未来作品页可用"),
    Rule("playback", r"^/novel/player/video_model/v1/?$", "单集播放地址解析，HGTV 当前使用"),
    Rule("playback", r"^/novel/player/multi_video_model/(?:v1|(?:player|preload|default|detail)(?:/v1)?)/?$", "批量播放地址解析，HGTV 当前使用"),
    Rule("playback", r"^/reading/bookapi/video_tab/multi_video_model/v1/?$", "视频 Tab 播放模型，备用链路"),
    Rule("playback", r"^/reading/media/play/strategy/?$", "播放策略/清晰度/倍速，未来播放器设置可用"),
    Rule("discovery", r"^/reading/bookapi/bookmall/tab/v/?$", "首页/推荐 Tab，HGTV 当前使用"),
    Rule("discovery", r"^/reading/bookapi/bookmall/cell/change/v(?::version)?/?$", "首页/推荐卡片流，HGTV 当前使用"),
    Rule("discovery", r"^/reading/distribution/category/landpage/v/?$", "分类/榜单，HGTV 当前使用"),
    Rule("discovery", r"^/reading/distribution/recommend_stat/report/v1/?$", "推荐行为上报，HGTV 当前使用"),
    Rule("discovery", r"^/reading/bookapi/plan/v(?::version)?/?$", "相关作品/页面编排，HGTV 当前使用"),
    Rule("discovery", r"^/reading/distribution/(home/sidebar|book_pack_fields/.+|content_extra_infos/.+|video_pack_fields/.+)/v1/?$", "分发包字段，推荐/详情/播放上下文"),
    Rule("discovery", r"^/novel/articleapi/post_data/multi_get/v1/?$", "推荐内容批量取数"),
    Rule("discovery", r"^/novel/articleapi/do_action/v1/?$", "不感兴趣等推荐反馈，未来可用"),
    Rule("discovery", r"^/reading/playlet/shortbuy_api/(mget_tag_info|get_aigc_init_data)/v1/?$", "短剧标签/AI 剧初始化，未来卡片信息可用"),
    Rule("discovery", r"^/reading/bookapi/(activity/main_page|my_tab/plan|book_update/query|read_progress/recommend|video_tab/continue_watch_ab|biz_static_config/get)/v(?::version)?/?$", "首页/我的页/继续观看/静态配置"),
    Rule("comments", r"^/novel/commentapi/comment/list/[^/]+/v1/?$", "评论/弹幕窗口，HGTV 当前使用"),
    Rule("comments", r"^/novel/commentapi/user_config/get/v1/?$", "评论/弹幕用户配置"),
    Rule("publisher", r"^/novel/commentapi/user/profile/\d+/v1/?$", "发行方/作者作品列表，HGTV 当前使用"),
    Rule("library", r"^/reading/bookapi/read_history/(update|list)/v1?/?$", "观看历史读写，HGTV 当前使用"),
    Rule("library", r"^/reading/bookapi/read_progress/(upload|get|list|sync|recommend)/v1?/?$", "观看进度读写/同步，HGTV 当前使用"),
    Rule("library", r"^/reading/bookapi/bookshelf/(list|tab|banner|recommend|video/list|video/info|video/update)/v(?::version)?/?$", "书架/收藏/短剧列表，HGTV 当前使用"),
    Rule("library", r"^/reading/user/subscribe/(list|delete)/v1/?$", "预约/订阅，未来剧集提醒可用"),
    Rule("library", r"^/reading/ugc/action/mget/v(?::version)?/?$", "点赞/互动状态，未来账号数据可用"),
    Rule("library", r"^/reading/reader/bookmark/list/?$", "书签列表，未来阅读/剧集扩展可用"),
    Rule("account", r"^/reading/user/(info/v|info/v1|basic_info/get/v1|profile/get/v|profile/get/v:version)/?$", "账号资料/用户主页，HGTV 账号同步可用"),
    Rule("account", r"^/reading/user/profile/get/v1/?$", "账号资料，登录态校验可用"),
    Rule("share", r"^/reading/user/(share/short_url|share/channel_info|share/entrance_info|multi_share_info)/?$", "分享短链/渠道，未来 TV 分享可用"),
    Rule("share", r"^/reading/user/(share/channel_info|multi_share_info)/v:version/?$", "分享渠道/分享信息，未来 TV 分享可用"),
]

COMPILED_RULES = [(rule, re.compile(rule.pattern, re.I)) for rule in RULES]

CATEGORY_LABEL = {
    "activation": "启动/注册/会话",
    "login": "登录",
    "search": "搜索/AI 搜索",
    "detail": "详情/分集",
    "playback": "播放地址/策略",
    "discovery": "首页/推荐/分发",
    "comments": "评论/弹幕",
    "publisher": "发行方作品",
    "library": "历史/进度/书架",
    "account": "账号资料",
    "share": "分享",
}

DROP_LABEL = {
    "duplicate": "近似重复样本",
    "tunnel": "CONNECT/SOCKS 隧道",
    "websocket": "WebSocket/非 HTTP 数据",
    "image": "图片/CDN/静态二进制",
    "host": "非 HGTV 接口域名/其他应用流量",
    "commercial": "广告/激励/钱包/VIP/电商",
    "config": "插件/Gecko/AB/监控/遥测配置",
    "telemetry": "监控/埋点/模型遥测",
    "social": "消息/UGC 通知等弱相关",
    "other": "未映射的官方业务 API",
}

RELEVANT_HOSTS = {
    "api5-normal-sinfonlinea.fqnovel.com",
    "api5-normal-sinfonlineb.fqnovel.com",
    "api5-normal-sinfonlinec.fqnovel.com",
    "api5-normal-hl.fqnovel.com",
    "api5-normal-lf.fqnovel.com",
    "api5-normal-lq.fqnovel.com",
    "log5-applog-hl.fqnovel.com",
    "log5-applog-lf.fqnovel.com",
    "rtlog5-applog-hl.fqnovel.com",
    "applog.snssdk.com",
    "log.snssdk.com",
    "is.snssdk.com",
    "is-hl.snssdk.com",
    "i.snssdk.com",
    "i-hl.snssdk.com",
    "reading.snssdk.com",
    "reading-hl.snssdk.com",
    "security.snssdk.com",
    "ichannel.snssdk.com",
}

IMAGE_HOST_PARTS = (
    "fqnovelpic.com", "byteimg.com", "douyinpic.com", "bytegecko.com",
    "byteacctimg.com", "bytetos.com", "zijieapi.com", "douyin.com",
)

COMMERCIAL_PATH_PARTS = (
    "luckycat", "commerce", "vip", "wallet", "cash_balance", "tradeapi",
    "order/", "coupon", "shop", "privilege", "task/", "resource_plan",
    "material/data", "push_switch", "/api/ad/", "ads/", "ad/", "pay_detail",
)

CONFIG_PATH_PARTS = (
    "gkx/", "api/plugin/config", "abtest", "ab/config", "ab_result",
    "gecko.config", "monitor/", "ri/report", "telemetry", "src/server",
    "api/neptune", "/weasel/", "effect.snssdk.com", "vcs.zijieapi.com",
    "common_ab_result", "sstimor", "video_tab/ab", "red_dot",
    "reader_settings", "font/get_font_types", "game_history", "editor/recommend",
)

SOCIAL_PATH_PARTS = (
    "msgapi/", "get_unread_count", "writer_center", "follow/", "booklist/get",
    "syncAll", "pack/user_data", "share_require_infos", "ugc/ab",
)

RESERVED_MISSING_CHECKS = {
    "设备注册": "/service/2/device_register/",
    "手机号发码": "/passport/mobile/send_code/v1/",
    "验证码登录": "/passport/mobile/sms_login/",
    "扫码登录": "/passport/web/get_qrcode/",
    "会话心跳": "/passport/token/beat/v2/",
    "分类/榜单": "/reading/distribution/category/landpage/v/",
    "App 搜索": "/reading/bookapi/search/tab/v",
    "搜索联想词": "/reading/bookapi/search/suggest/v",
}


def host_relevant(host: str) -> bool:
    return host in RELEVANT_HOSTS or (
        host.startswith("api5-normal-") and host.endswith(".fqnovel.com")
    )


def classify(method: str, host: str, path: str) -> tuple[str, str] | None:
    if method.upper() == "CONNECT" or not host_relevant(host):
        return None
    for rule, pattern in COMPILED_RULES:
        if pattern.search(path):
            return rule.category, rule.reason
    return None


def drop_reason(entry: dict, host: str, path: str, mime: str) -> str:
    method = str(entry["request"].get("method", "")).upper()
    url = str(entry["request"].get("url", ""))
    scheme = urlsplit(url).scheme.lower()
    if method == "CONNECT" or scheme == "socks5":
        return "tunnel"
    if scheme in ("ws", "wss") or entry.get("_webSocketMessages"):
        return "websocket"
    if mime.startswith("image/") or any(part in host for part in IMAGE_HOST_PARTS):
        return "image"
    if not host_relevant(host):
        return "host"
    combined = (host + path).lower()
    if any(part in combined for part in COMMERCIAL_PATH_PARTS):
        return "commercial"
    if any(part in combined for part in CONFIG_PATH_PARTS):
        return "config"
    if any(part in path.lower() for part in ("/monitor/", "/ri/report", "/telemetry", "/model/api/")):
        return "telemetry"
    if any(part in path.lower() for part in SOCIAL_PATH_PARTS):
        return "social"
    return "other"


def row_for(entry: dict, index: int, kept: bool, bucket: str, reason: str) -> dict:
    url = str(entry["request"].get("url", ""))
    parts = urlsplit(url)
    content = entry["response"].get("content") or {}
    return {
        "sourceIndex": index,
        "kept": "yes" if kept else "no",
        "bucket": bucket,
        "reason": reason,
        "method": entry["request"].get("method", ""),
        "host": parts.netloc,
        "path": parts.path,
        "url": url,
        "status": entry["response"].get("status", ""),
        "mime": content.get("mimeType", "") or "",
        "bytes": content.get("size", 0) or 0,
        "dedupGroup": "",
        "dedupRole": "",
        "representativeSourceIndex": "",
        "dedupSimilarity": "",
    }


def decode_content_text(content: dict) -> str:
    text = str(content.get("text", "") or "")
    if content.get("encoding") == "base64" and text:
        try:
            return base64.b64decode(text).decode("utf-8")
        except Exception:
            return text
    return text


def json_paths(value, prefix: str = "", depth: int = 0, max_depth: int = 4):
    if depth > max_depth:
        return []
    if isinstance(value, dict):
        result = []
        for key, item in value.items():
            path = f"{prefix}.{key}" if prefix else str(key)
            result.append((path, "" if isinstance(item, (dict, list)) else item))
            result.extend(json_paths(item, path, depth + 1, max_depth))
        return result
    if isinstance(value, list):
        result = []
        for index, item in enumerate(value[:20]):
            path = f"{prefix}[{index}]"
            result.append((path, "" if isinstance(item, (dict, list)) else item))
            result.extend(json_paths(item, path, depth + 1, max_depth))
        return result
    return []


def parse_json(text: str):
    try:
        return json.loads(text)
    except Exception:
        return None


def param_rows(entry: dict, source_index: int, category: str) -> list[dict]:
    rows = []
    request = entry.get("request") or {}
    response = entry.get("response") or {}
    url = str(request.get("url", ""))
    parts = urlsplit(url)

    def add(scope: str, name: str, value) -> None:
        rows.append({
            "sourceIndex": source_index,
            "category": category,
            "scope": scope,
            "name": str(name),
            "value": "" if value is None else value,
        })

    query = request.get("queryString") or []
    if not query:
        query = [
            {"name": name, "value": value}
            for name, value in parse_qsl(parts.query, keep_blank_values=True)
        ]
    for item in query:
        add("request.query", item.get("name", ""), item.get("value", ""))
    for item in request.get("headers") or []:
        add("request.header", item.get("name", ""), item.get("value", ""))
    for item in request.get("cookies") or []:
        add("request.cookie", item.get("name", ""), item.get("value", ""))
    post = request.get("postData") or {}
    if post.get("text") is not None:
        text = str(post.get("text", "") or "")
        parsed = parse_json(text)
        if parsed is not None:
            for path, value in json_paths(parsed, max_depth=5):
                add("request.postData.json", path, value)
    for item in post.get("params") or []:
        add("request.postData.param", item.get("name", ""), item.get("value", ""))
    for item in response.get("headers") or []:
        add("response.header", item.get("name", ""), item.get("value", ""))
    for item in response.get("cookies") or []:
        add("response.cookie", item.get("name", ""), item.get("value", ""))
    content = response.get("content") or {}
    body = decode_content_text(content)
    if body:
        parsed = parse_json(body)
        if parsed is not None:
            for path, value in json_paths(parsed, max_depth=2):
                add("response.body.json", path, value)
    return rows


def normalized_path(path: str) -> str:
    return re.sub(r"/\d{8,}(?=/|$)", "/{id}", path)


VOLATILE_QUERY_KEYS = {
    "_rticket", "ts", "cdid", "openudid", "clientudid", "device_id", "iid",
    "install_id", "device_token", "klink_egdi", "ttreq", "odin_tt", "req_id",
    "session_id", "session_uuid", "normal_session_id", "cold_start_session_id",
    "x-ss-req-ticket", "x-ss-stub", "x-khronos", "x-gorgon", "x-medusa",
    "x-helios", "x-ladon", "x-argus", "x-tt-trace-id", "x-ss-dp",
    "passport_csrf_token", "signature", "x-signature", "lk3s", "x-expires",
    "tt_info", "log_id", "request_id", "recommend_info", "search_id",
    "passback", "next_offset", "offset", "page", "cursor", "last_mtime",
    "normal_session_cnt_in_day", "normal_session_cnt_in_life",
    "cold_start_session_cnt_in_day", "cold_start_session_cnt_in_life",
    "screen_brightness", "battery_pct", "current_volume", "down_speed",
    "apk_first_install_time", "rom_version",
}

VOLATILE_HEADER_NAMES = {
    ":path", "content-length", "cookie", "set-cookie", "x-ss-stub",
    "x-ss-req-ticket", "x-khronos", "x-gorgon", "x-medusa", "x-helios",
    "x-ladon", "x-argus", "x-tt-trace-id", "x-tt-store-region",
    "x-tt-store-region-src", "x-vc-bdturing-sdk-version", "x-tt-token",
    "date", "server-timing", "x-tt-logid", "x-tt-tag", "tt-idc-switch",
    "x-tt-trace-host", "x-tt-trace-tag",
}

VOLATILE_PATH_PARTS = (
    "log_id", "request_id", "req_id", "session_id", "session_uuid",
    "server_time", "server_timestamp", "timestamp", "ts", "cursor",
    "next_offset", "offset", "expire_time", "url_expire", "x-expires",
    "create_time", "update_time", "read_timestamp", "total_time",
    "playlet_consume_duration_ms", "consumption_duration",
)

IDENTIFIER_PATTERN = re.compile(r"^\d{8,}$")


def is_volatile_path(path: str) -> bool:
    lower = path.lower()
    return any(part in lower for part in VOLATILE_PATH_PARTS)


def json_shape(value, prefix: str = "", depth: int = 0, max_depth: int = 3):
    """Return field paths without values, including a few array element shapes."""
    if depth > max_depth:
        return set()
    if isinstance(value, dict):
        result = set()
        for key, item in value.items():
            path = f"{prefix}.{key}" if prefix else str(key)
            if is_volatile_path(path):
                continue
            result.add(path)
            result.update(json_shape(item, path, depth + 1, max_depth))
        return result
    if isinstance(value, list):
        result = set()
        for item in value[:3]:
            result.update(json_shape(item, f"{prefix}[]", depth + 1, max_depth))
        return result
    return set()


def query_signature(entry: dict) -> frozenset[str]:
    request = entry.get("request") or {}
    url = str(request.get("url", ""))
    parts = urlsplit(url)
    source = request.get("queryString") or [
        {"name": name, "value": value}
        for name, value in parse_qsl(parts.query, keep_blank_values=True)
    ]
    return frozenset(
        str(item.get("name", "")).lower()
        for item in source
        if str(item.get("name", "")).lower() not in VOLATILE_QUERY_KEYS
    )


def header_signature(entry: dict, part: str) -> frozenset[str]:
    return frozenset(
        str(item.get("name", "")).lower()
        for item in (entry.get(part) or {}).get("headers") or []
        if str(item.get("name", "")).lower() not in VOLATILE_HEADER_NAMES
    )


def body_shape(entry: dict, part: str) -> frozenset[str]:
    if part == "request":
        text = str(((entry.get("request") or {}).get("postData") or {}).get("text", "") or "")
    else:
        text = decode_content_text((entry.get("response") or {}).get("content") or {})
    parsed = parse_json(text)
    if parsed is None:
        return frozenset()
    return frozenset(json_shape(parsed))


def body_values(entry: dict, part: str) -> frozenset[str]:
    """Stable scalar values from shallow JSON fields, for near-duplicate scoring."""
    if part == "request":
        text = str(((entry.get("request") or {}).get("postData") or {}).get("text", "") or "")
    else:
        text = decode_content_text((entry.get("response") or {}).get("content") or {})
    parsed = parse_json(text)
    if parsed is None:
        return frozenset()
    values = set()
    for path, value in json_paths(parsed, max_depth=2):
        if is_volatile_path(path) or isinstance(value, (dict, list)) or value is None:
            continue
        text_value = str(value)
        if IDENTIFIER_PATTERN.fullmatch(text_value) or len(text_value) > 80:
            continue
        values.add(f"{path}={text_value}")
    return frozenset(values)


def response_code(entry: dict) -> str:
    parsed = parse_json(decode_content_text((entry.get("response") or {}).get("content") or {}))
    if not isinstance(parsed, dict):
        return ""
    for key in ("code", "Code", "err_no", "errcode", "StatusCode"):
        if key in parsed:
            return str(parsed[key])
    base = parsed.get("BaseResp")
    if isinstance(base, dict):
        return str(base.get("StatusCode", ""))
    return ""


def dedup_key(entry: dict, category: str) -> tuple:
    request = entry.get("request") or {}
    parts = urlsplit(str(request.get("url", "")))
    content = (entry.get("response") or {}).get("content") or {}
    return (
        category,
        request.get("method", ""),
        parts.netloc,
        normalized_path(parts.path or "/"),
        str((entry.get("response") or {}).get("status", "")),
        str(content.get("mimeType", "") or ""),
        response_code(entry),
        query_signature(entry),
        header_signature(entry, "request"),
        body_shape(entry, "request"),
        header_signature(entry, "response"),
        body_shape(entry, "response"),
    )


def information_score(entry: dict) -> tuple:
    content = (entry.get("response") or {}).get("content") or {}
    body = decode_content_text(content)
    return (
        len(query_signature(entry)),
        len(header_signature(entry, "request")),
        len(body_shape(entry, "request")),
        len(body_shape(entry, "response")),
        len(body_values(entry, "response")),
        int(content.get("size", 0) or 0),
        len(body),
    )


def jaccard(left: frozenset[str], right: frozenset[str]) -> float:
    if not left and not right:
        return 1.0
    union = left | right
    return len(left & right) / len(union) if union else 1.0


def deduplicate(candidates: list[dict]) -> tuple[list[dict], dict[int, dict]]:
    """Collapse near-identical endpoint samples into one representative each."""
    groups = defaultdict(list)
    for candidate in candidates:
        groups[candidate["key"]].append(candidate)

    representatives = []
    metadata = {}
    for group_index, (key, group) in enumerate(
        sorted(groups.items(), key=lambda item: repr(item[0]))
    ):
        group.sort(key=lambda item: (-information_score(item["entry"])[5], -len(item["key"]), item["index"]))
        representative = group[0]
        representative_values = body_values(representative["entry"], "response")
        representatives.append(representative)
        group_id = f"g{group_index:03d}"
        metadata[representative["index"]] = {
            "group": group_id,
            "role": "representative",
            "representative": representative["index"],
            "similarity": "1.000",
            "note": "同组信息量最大的完整样本",
        }
        for candidate in group[1:]:
            similarity = jaccard(representative_values, body_values(candidate["entry"], "response"))
            metadata[candidate["index"]] = {
                "group": group_id,
                "role": "duplicate",
                "representative": representative["index"],
                "similarity": f"{similarity:.3f}",
                "note": "同端点、同字段形状、响应参数近似一致",
            }
    representatives.sort(key=lambda item: item["index"])
    return representatives, metadata


def write_catalog(path: Path, kept_entries: list[dict], categories: list[str]) -> None:
    groups = defaultdict(lambda: {
        "count": 0,
        "indices": [],
        "query": set(),
        "post": set(),
        "headers": set(),
        "response": set(),
    })
    for entry, category, source_index in zip(
        kept_entries,
        categories,
        [item["_hgtvSourceIndex"] for item in kept_entries],
    ):
        request = entry.get("request") or {}
        response = entry.get("response") or {}
        url = str(request.get("url", ""))
        parts = urlsplit(url)
        key = (category, request.get("method", ""), normalized_path(parts.path or "/"))
        group = groups[key]
        group["count"] += 1
        group["indices"].append(source_index)
        query = request.get("queryString") or [
            {"name": name, "value": value}
            for name, value in parse_qsl(parts.query, keep_blank_values=True)
        ]
        group["query"].update(str(item.get("name", "")) for item in query)
        group["headers"].update(str(item.get("name", "")) for item in request.get("headers") or [])
        post = request.get("postData") or {}
        if post.get("text"):
            parsed = parse_json(str(post.get("text", "")))
            if parsed is not None:
                group["post"].update(name for name, _ in json_paths(parsed, max_depth=3))
            else:
                group["post"].add("postData.text")
        for item in post.get("params") or []:
            group["post"].add(str(item.get("name", "")))
        parsed_response = parse_json(decode_content_text(response.get("content") or {}))
        if parsed_response is not None:
            group["response"].update(name for name, _ in json_paths(parsed_response, max_depth=1))

    lines = [
        "# 7.4.3.32 HGTV 抓包参数清单",
        "",
        "本清单按端点聚合保留条目的请求参数名、请求头名与响应顶层字段。所有参数值、请求体、响应体与 Cookie 的完整内容都在无损 HAR 和逐参数 CSV 中。",
        "",
    ]
    for (category, method, endpoint), group in sorted(
        groups.items(), key=lambda item: (item[0][0], item[0][2], item[0][1])
    ):
        lines += [
            f"## {CATEGORY_LABEL.get(category, category)} · `{method} {endpoint}`",
            "",
            f"- 样本：{group['count']} 条，原始索引 {', '.join(str(i) for i in group['indices'][:12])}{' …' if len(group['indices']) > 12 else ''}",
            f"- Query 参数：{', '.join(f'`{name}`' for name in sorted(group['query'])) or '无'}",
            f"- Body 字段：{', '.join(f'`{name}`' for name in sorted(group['post'])) or '无'}",
            f"- 请求头：{', '.join(f'`{name}`' for name in sorted(group['headers'])) or '无'}",
            f"- 响应顶层字段：{', '.join(f'`{name}`' for name in sorted(group['response'])) or '无'}",
            "",
        ]
    path.write_text("\n".join(lines), encoding="utf-8")


def write_report(
    path: Path,
    source: Path,
    source_sha: str,
    total: int,
    rows: list[dict],
    output: Path,
    index: Path,
    params: Path,
    catalog: Path,
    manifest: Path,
    samples: Path,
    output_size: int,
    verification: bool,
    relevant_total: int,
    duplicate_count: int,
) -> None:
    kept = [row for row in rows if row["kept"] == "yes"]
    dropped = [row for row in rows if row["kept"] == "no"]
    category_counts = Counter(row["bucket"] for row in kept)
    category_bytes = defaultdict(int)
    for row in kept:
        category_bytes[row["bucket"]] += int(row["bytes"])
    drop_counts = Counter(row["bucket"] for row in dropped)
    host_counts = Counter(row["host"] for row in dropped)
    path_counts = Counter((row["method"], normalized_path(row["path"])) for row in kept)
    other_paths = Counter(
        (row["method"], row["path"])
        for row in dropped
        if row["bucket"] == "other"
    )
    all_paths = set(row["path"] for row in rows)
    missing = [
        (label, endpoint)
        for label, endpoint in RESERVED_MISSING_CHECKS.items()
        if endpoint not in all_paths
    ]

    lines = [
        "# 7.4.3.32 抓包筛选说明",
        "",
        "## 结论",
        "",
        f"原始 HAR `{source.name}` 共 {total} 条，SHA-256 `{source_sha}`。",
        f"HGTV 相关样本 {relevant_total} 条；按同端点、同请求/响应字段形状与近似响应参数去重后，保留 {len(kept)} 条，额外剔除近似重复样本 {duplicate_count} 条。原始 HAR 共 {total} 条，最终从原始条目中剔除 {len(dropped)} 条。",
        f"筛出文件 `{output.name}`（{output_size / 1048576:.1f} MiB），逐条索引 `{index.name}`，全量相关样本参数表 `{params.name}`，去重清单 `{samples.name}`，端点参数清单 `{catalog.name}`，校验清单 `{manifest.name}`。",
        "",
        "**保留条目无损**：HAR 里只做整条删除，保留条目的 URL、完整 query 参数、headers、Cookie、postData、响应体、请求耗时及 Reqable 私有字段均逐字段保持原值；不做脱敏、截断或压缩。原始 HAR 未改动。",
        "近似重复项按端点、请求参数名、请求头名、请求体字段形状、响应头名、响应字段形状、状态码与业务码分组；每组保留响应信息量最大的完整样本。`dedupGroup`、`dedupRole`、`representativeSourceIndex`、`dedupSimilarity` 写在逐条索引里，可以追查每个重复样本被谁代表。",
        f"参数表覆盖全部 {relevant_total} 条 HGTV 相关样本（含被去重的样本），因此重复请求里的参数名和值仍能查到；完整原始请求体/响应体仍在原始 HAR 中。用 Python 读取大字段 CSV 时，先设置 `csv.field_size_limit(sys.maxsize)`。",
        f"生成后逐条与原始条目做结构相等校验：{'通过' if verification else '失败'}。",
        "",
        "## 保留范围",
        "",
        "| 分类 | 条数 | 响应体合计 | 用途 |",
        "| --- | ---: | ---: | --- |",
    ]
    for category, label in CATEGORY_LABEL.items():
        if category_counts[category]:
            lines.append(
                f"| {label} | {category_counts[category]} | {category_bytes[category] / 1048576:.2f} MiB | `{category}` |"
            )
    lines += [
        "",
        "当前 HGTV 直接相关：首页/推荐与卡片流、详情与分集、播放地址模型、评论/弹幕、观看历史与进度、书架/收藏、账号资料、推荐行为上报、发行方作品。",
        "未来可能使用：启动/注册/会话配置、登录与扫码链路、AI 搜索入口、分享短链与渠道、隐私/青少年模式、播放策略、不感兴趣反馈、分发包字段。",
        "",
        "## 本次 HAR 的缺口",
        "",
    ]
    if missing:
        lines.append("筛选规则已经预留以下 HGTV 已知链路，但本次抓包未出现对应请求，不能从本份数据补齐参数：")
        lines.append("")
        for label, endpoint in missing:
            lines.append(f"- {label}：`{endpoint}`")
    else:
        lines.append("预留的 HGTV 已知链路在本次抓包中均有样本。")
    lines += [
        "",
        "本次抓包有 `/novel/player/video_model/v1/` 单集播放地址样本；不要把它与缺失的旧备用批量链路混为一谈。",
        "",
        "## 剔除范围",
        "",
        "| 剔除原因 | 条数 |",
        "| --- | ---: |",
    ]
    for reason, label in DROP_LABEL.items():
        if drop_counts[reason]:
            lines.append(f"| {label} | {drop_counts[reason]} |")
    lines += [
        "",
        f"典型剔除对象包括图片 CDN、{drop_counts['tunnel']} 条 CONNECT/SOCKS 隧道、Reqable/其他应用与小米/360/OpenAI/腾讯文档等无关域名、广告与激励/钱包/VIP/电商、Gecko/插件/AB/监控/遥测配置、消息通知与 UGC 噪声。",
        "",
        "## 高频保留端点",
        "",
        "| 方法 | 路径 | 条数 |",
        "| --- | --- | ---: |",
    ]
    for (method, endpoint), count in path_counts.most_common(20):
        lines.append(f"| {method} | `{endpoint}` | {count} |")
    lines += [
        "",
        "## 高频剔除域名",
        "",
        "| 域名 | 条数 |",
        "| --- | ---: |",
    ]
    for host, count in host_counts.most_common(12):
        lines.append(f"| `{host}` | {count} |")
    if other_paths:
        lines += [
            "",
            "## 未映射而剔除的官方端点",
            "",
            "这些端点都在目标 API 域名上，但与当前 HGTV 或预留链路弱相关；如需复查，逐条索引里保留了原始 URL 和状态码。",
            "",
            "| 方法 | 路径 | 条数 |",
            "| --- | --- | ---: |",
        ]
        for (method, endpoint), count in other_paths.most_common(20):
            lines.append(f"| {method} | `{endpoint}` | {count} |")
    lines += [
        "",
        "## 以后怎么查参数",
        "",
        f"1. 打开 `{catalog.name}`，按端点查看 Query 参数名、Body 字段、请求头与响应字段。",
        f"2. 打开 `{params.name}`，按 `sourceIndex` / `scope` / `name` 筛选；它覆盖全部相关样本，取参数值不受去重影响。",
        f"3. 打开 `{samples.name}`，按 `dedupGroup` / `dedupRole` 查看每组保留和剔除的样本及近似度。",
        f"4. 打开 `{output.name}`，按 `sourceIndex` 对照 `{index.name}` 找到保留样本，复放时直接用该条目的完整请求。",
        "",
        "## 复现",
        "",
        "```bash",
        "python3 tools/filter_hgtv_har.py",
        "```",
        "",
        "默认读取 `tools/官方应用参考/7.4.3.32反编译代码库/7.4.3.32抓包数据.har`，所有筛选产物写在同目录。可用 `--input` / `--output` / `--index` / `--params` / `--catalog` / `--manifest` / `--report` 覆盖路径。",
        "",
    ]
    path.write_text("\n".join(lines), encoding="utf-8")


def main() -> None:
    root = Path(__file__).resolve().parent.parent
    source_dir = root / "tools/官方应用参考/7.4.3.32反编译代码库"
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input", type=Path, default=source_dir / "7.4.3.32抓包数据.har")
    parser.add_argument("--output", type=Path, default=source_dir / "7.4.3.32抓包数据_HGTV.har")
    parser.add_argument("--index", type=Path, default=source_dir / "7.4.3.32抓包数据_HGTV_index.csv")
    parser.add_argument("--params", type=Path, default=source_dir / "7.4.3.32抓包数据_HGTV_params.csv")
    parser.add_argument("--catalog", type=Path, default=source_dir / "7.4.3.32抓包数据_HGTV_参数清单.md")
    parser.add_argument("--manifest", type=Path, default=source_dir / "7.4.3.32抓包数据_HGTV_manifest.json")
    parser.add_argument("--samples", type=Path, default=source_dir / "7.4.3.32抓包数据_HGTV_samples.csv")
    parser.add_argument("--report", type=Path, default=root / "docs/7.4.3.32抓包筛选说明.md")
    args = parser.parse_args()

    source_bytes = args.input.read_bytes()
    source_sha = hashlib.sha256(source_bytes).hexdigest()
    har = json.loads(source_bytes)
    entries = har["log"]["entries"]

    candidates = []
    rows = []
    all_params = []
    relevant_rows = {}
    relevant_total = 0
    for index, entry in enumerate(entries):
        url = str(entry["request"].get("url", ""))
        parts = urlsplit(url)
        host = parts.netloc
        path = parts.path or "/"
        mime = str((entry["response"].get("content") or {}).get("mimeType", "") or "")
        method = str(entry["request"].get("method", ""))
        found = classify(method, host, path)
        if found:
            category, reason = found
            relevant_total += 1
            candidates.append({
                "index": index,
                "entry": entry,
                "category": category,
                "reason": reason,
                "key": dedup_key(entry, category),
            })
            row = row_for(entry, index, True, category, reason)
            relevant_rows[index] = row
            rows.append(row)
            all_params.extend(param_rows(entry, index, category))
        else:
            reason_key = drop_reason(entry, host, path, mime)
            rows.append(row_for(entry, index, False, reason_key, DROP_LABEL[reason_key]))

    representative_candidates, dedup_metadata = deduplicate(candidates)
    representative_indices = {candidate["index"] for candidate in representative_candidates}
    duplicate_count = relevant_total - len(representative_indices)

    kept_entries = []
    kept_categories = []
    kept_indices = []
    sample_rows = []
    for candidate in candidates:
        index = candidate["index"]
        entry = candidate["entry"]
        meta = dedup_metadata[index]
        row = relevant_rows[index]
        row["dedupGroup"] = meta["group"]
        row["dedupRole"] = meta["role"]
        row["representativeSourceIndex"] = meta["representative"]
        row["dedupSimilarity"] = meta["similarity"]
        if index not in representative_indices:
            row["kept"] = "no"
            row["bucket"] = "duplicate"
            row["reason"] = (
                f"{meta['note']}；代表条目 {meta['representative']}；"
                f"字段近似度 {meta['similarity']}"
            )
        else:
            row["reason"] = f"{candidate['reason']}；{meta['note']}"
        sample_rows.append({
            "sourceIndex": index,
            "kept": row["kept"],
            "category": candidate["category"],
            "dedupGroup": meta["group"],
            "dedupRole": meta["role"],
            "representativeSourceIndex": meta["representative"],
            "dedupSimilarity": meta["similarity"],
            "method": row["method"],
            "host": row["host"],
            "path": row["path"],
            "url": row["url"],
            "status": row["status"],
            "bytes": row["bytes"],
            "reason": row["reason"],
        })
        if index in representative_indices:
            kept = copy.deepcopy(entry)
            kept["_hgtvSourceIndex"] = index
            kept_entries.append(kept)
            kept_categories.append(candidate["category"])
            kept_indices.append(index)

    verification = all(
        {key: value for key, value in kept.items() if key != "_hgtvSourceIndex"} == entries[source_index]
        for kept, source_index in zip(kept_entries, kept_indices)
    )
    if not verification:
        raise SystemExit("retained entry differs from source; refusing to write lossless HAR")

    output_log = copy.deepcopy(har["log"])
    output_log["entries"] = [
        {key: value for key, value in kept.items() if key != "_hgtvSourceIndex"}
        for kept in kept_entries
    ]
    output_log["comment"] = (
        "Lossless HGTV subset: irrelevant entries removed; retained entries are "
        "byte-for-byte equivalent JSON values from the source HAR."
    )
    output_log["_hgtvFilter"] = {
        "sourceSha256": source_sha,
        "sourceEntries": len(entries),
        "keptEntries": len(kept_entries),
        "keptSourceIndices": kept_indices,
        "deduplicated": True,
        "relevantEntries": relevant_total,
        "duplicateEntriesRemoved": duplicate_count,
        "lossless": True,
    }
    output_har = {"log": output_log}

    for output in (
        args.output, args.index, args.params, args.catalog,
        args.manifest, args.samples, args.report,
    ):
        output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(
        json.dumps(output_har, ensure_ascii=False, separators=(",", ":")),
        encoding="utf-8",
    )
    with args.index.open("w", encoding="utf-8", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=list(rows[0].keys()))
        writer.writeheader()
        writer.writerows(rows)
    with args.params.open("w", encoding="utf-8", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=["sourceIndex", "category", "scope", "name", "value"])
        writer.writeheader()
        writer.writerows(all_params)
    with args.samples.open("w", encoding="utf-8", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=list(sample_rows[0].keys()))
        writer.writeheader()
        writer.writerows(sample_rows)
    write_catalog(args.catalog, kept_entries, kept_categories)
    output_sha = hashlib.sha256(args.output.read_bytes()).hexdigest()
    manifest = {
        "source": str(args.input),
        "sourceSha256": source_sha,
        "sourceEntries": len(entries),
        "output": str(args.output),
        "outputSha256": output_sha,
        "outputEntries": len(kept_entries),
        "removedEntries": len(entries) - len(kept_entries),
        "relevantEntries": relevant_total,
        "duplicateEntriesRemoved": duplicate_count,
        "losslessEntryVerification": verification,
        "index": str(args.index),
        "params": str(args.params),
        "catalog": str(args.catalog),
        "samples": str(args.samples),
        "report": str(args.report),
        "paramRows": len(all_params),
    }
    args.manifest.write_text(
        json.dumps(manifest, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )
    write_report(
        args.report,
        args.input,
        source_sha,
        len(entries),
        rows,
        args.output,
        args.index,
        args.params,
        args.catalog,
        args.manifest,
        args.samples,
        args.output.stat().st_size,
        verification,
        relevant_total,
        duplicate_count,
    )

    print(f"source_entries={len(entries)}")
    print(f"relevant_entries={relevant_total}")
    print(f"kept_entries={len(kept_entries)}")
    print(f"duplicate_entries_removed={duplicate_count}")
    print(f"removed_entries={len(entries) - len(kept_entries)}")
    print(f"param_rows={len(all_params)}")
    print(f"lossless_entry_verification={verification}")
    print(f"output={args.output} ({args.output.stat().st_size} bytes)")
    print(f"index={args.index}")
    print(f"params={args.params}")
    print(f"catalog={args.catalog}")
    print(f"samples={args.samples}")
    print(f"manifest={args.manifest}")
    print(f"report={args.report}")


if __name__ == "__main__":
    main()
