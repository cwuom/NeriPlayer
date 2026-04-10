#!/usr/bin/env python3
"""YouTube Music authenticated API probe.

用途：
- 用当前 Cookie 验证 YouTube Music 本地认证链路是否正常
- 用 HAR 中抓到的官方 WEB_REMIX player 请求模板回放 player
- 用 nodriver 验证 WebPoClient 是否可用并尝试 mint GVS poToken
- 用 yt-dlp + node + wpc provider 验证最终可下载音频 URL 是否稳定可访问
"""

from __future__ import annotations

import argparse
import asyncio
import base64
import contextlib
import copy
import gc
import gzip
import hashlib
import io
import json
import os
import re
import secrets
import shutil
import string
import subprocess
import sys
import tempfile
import time
import builtins
from dataclasses import dataclass
from pathlib import Path
from typing import Any
from urllib.parse import parse_qs, urlparse

import requests


MUSIC_ORIGIN = "https://music.youtube.com"
YOUTUBE_ORIGIN = "https://www.youtube.com"
YOUTUBEI_API_BASE = "https://youtubei.googleapis.com/youtubei/v1"
DEFAULT_COOKIE_FILE = r"E:\AndroidProject\NeriPlayer\.ck\youtube-cookie.txt"
DEFAULT_VIDEO_IDS = ["fbvvS8e1KgI", "o2x1DBRCZJg"]
DEFAULT_PYDEPS_PATH = Path(tempfile.gettempdir()) / "neri_pydeps"
DEFAULT_REPORT_PATH = Path(__file__).with_name("ytmusic_api_probe_report.json")
LIBRARY_PLAYLISTS_BROWSE_ID = "FEmusic_liked_playlists"
WEB_USER_AGENT = (
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
    "AppleWebKit/537.36 (KHTML, like Gecko) "
    "Chrome/146.0.0.0 Safari/537.36"
)
WEB_PAGE_ACCEPT = (
    "text/html,application/xhtml+xml,application/xml;q=0.9,"
    "image/avif,image/webp,image/apng,*/*;q=0.8,"
    "application/signed-exchange;v=b3;q=0.7"
)
WEB_REMIX_CLIENT_NAME = "WEB_REMIX"
WEB_REMIX_CLIENT_NAME_NUM = "67"
TVHTML5_CLIENT_NAME = "TVHTML5"
TVHTML5_CLIENT_NAME_NUM = "7"
IOS_CLIENT_NAME = "IOS"
IOS_CLIENT_NAME_NUM = "5"
YOUTUBE_PLAYER_API_FORMAT_VERSION = "2"
TVHTML5_CLIENT_VERSION = "7.20260114.12.00"
TVHTML5_USER_AGENT = (
    "Mozilla/5.0 (ChromiumStylePlatform) Cobalt/25.lts.30.1034943-gold "
    "(unlike Gecko), Unknown_TV_Unknown_0/Unknown (Unknown, Unknown)"
)
TVHTML5_DOWNGRADED_CLIENT_VERSION = "5.20260114"
TVHTML5_DOWNGRADED_USER_AGENT = "Mozilla/5.0 (ChromiumStylePlatform) Cobalt/Version"
IOS_CLIENT_VERSION = "21.03.2"
IOS_USER_AGENT = (
    "com.google.ios.youtube/21.03.2(iPhone16,2; U; CPU iOS 18_7_2 like Mac OS X; US)"
)
IOS_DEVICE_MODEL = "iPhone16,2"
IOS_OS_VERSION = "18.7.2.22H124"
YTDLP_REQUESTED_PLAYER_CLIENTS = ["tv_downgraded", "web_music"]
OPTIONAL_PLAYER_PROFILES = {"ios"}
STRICT_REQUIRED_PLAYER_PROFILES = {"web_remix_official"}
STRICT_REQUIRED_AUDIO_ITAGS = {"web_remix_official": 251}
WEBPO_BOOTSTRAP_URLS = [
    "https://www.youtube.com/?themeRefresh=1",
    "https://music.youtube.com/",
]
DOWNLOAD_RANGE_BYTES = 65_536
RANGE_PROBE_MIDPOINT = 1_048_576
HAR_DEFAULT_CANDIDATES = [
    Path(__file__).resolve().parent.parent / ".report" / "music.youtube.com.har",
    Path(__file__).with_name("music.youtube.com.har"),
    Path.home() / "Downloads" / "music.youtube.com.har",
    Path(r"E:\Users\cwuom\Downloads\music.youtube.com.har"),
]
SUPPRESSED_PRINT_PREFIXES = (
    "successfully removed temp profile ",
    "WARNING: [GetPOT] ",
    "WARNING: ffmpeg not found.",
)

WEBPO_SNAPSHOT_SCRIPT = r"""
JSON.stringify((() => {
  const topWindow = window.top;
  const ytcfg = topWindow?.ytcfg;
  const getConfig = (key) => {
    try {
      if (ytcfg?.get) {
        return ytcfg.get(key);
      }
      return ytcfg?.data_?.[key];
    } catch (error) {
      return null;
    }
  };
  const findFactory = () => {
    try {
      const direct = topWindow?.['havuokmhhs-0']?.bevasrs?.wpc;
      if (typeof direct === 'function') {
        return direct;
      }
      for (const key of Object.getOwnPropertyNames(topWindow || {})) {
        const candidate = topWindow?.[key]?.bevasrs?.wpc;
        if (typeof candidate === 'function') {
          return candidate;
        }
      }
    } catch (error) {}
    return null;
  };
  const webPlayerContexts = getConfig('WEB_PLAYER_CONTEXT_CONFIGS') || {};
  const bindsToVideoId = Object.values(webPlayerContexts).some((context) => {
    const flags = String(context?.serializedExperimentFlags || '');
    return flags.includes('html5_generate_content_po_token=true');
  });
  return {
    readyState: document.readyState || '',
    hasYtcfg: !!ytcfg,
    hasWebPoClient: !!findFactory(),
    visitorData: String(
      getConfig('VISITOR_DATA')
        || getConfig('INNERTUBE_CONTEXT')?.client?.visitorData
        || ''
    ),
    dataSyncId: String(
      getConfig('DATASYNC_ID')
        || getConfig('datasyncId')
        || ''
    ),
    bindsGvsTokenToVideoId: !!bindsToVideoId
  };
})())
""".strip()

WEBPO_MINT_SCRIPT_TEMPLATE = r"""
(async () => {
  const findFactory = () => {
    const topWindow = window.top;
    try {
      const direct = topWindow?.['havuokmhhs-0']?.bevasrs?.wpc;
      if (typeof direct === 'function') {
        return direct;
      }
      for (const key of Object.getOwnPropertyNames(topWindow || {})) {
        const candidate = topWindow?.[key]?.bevasrs?.wpc;
        if (typeof candidate === 'function') {
          return candidate;
        }
      }
    } catch (error) {}
    return null;
  };
  const factory = findFactory();
  if (!factory) {
    return JSON.stringify({ status: 'missing', error: 'WebPoClient unavailable' });
  }
  try {
    const client = await factory();
    const token = await client.mws({
      c: __CONTENT_BINDING__,
      mc: false,
      me: false
    });
    return JSON.stringify({ status: 'ok', token: String(token || '') });
  } catch (error) {
    const message = String(error || '');
    if (message.includes('SDF:notready')) {
      return JSON.stringify({ status: 'backoff', error: message });
    }
    return JSON.stringify({ status: 'error', error: message });
  }
})()
""".strip()


_ORIGINAL_PRINT = builtins.print


def filtered_print(*args: Any, **kwargs: Any) -> None:
    text = " ".join(str(arg) for arg in args).strip()
    if any(text.startswith(prefix) for prefix in SUPPRESSED_PRINT_PREFIXES):
        return
    _ORIGINAL_PRINT(*args, **kwargs)


builtins.print = filtered_print


@dataclass
class BootstrapData:
    home_status: int
    api_key: str
    visitor_data: str
    session_index: str
    web_remix_version: str
    remote_host: str
    player_js_url: str
    signature_timestamp: int | None
    app_install_data: str
    cold_config_data: str
    cold_hash_data: str
    hot_hash_data: str
    device_experiment_id: str
    rollout_token: str
    data_sync_id: str
    delegated_session_id: str
    user_session_id: str
    logged_in: bool
    user_agent: str


@dataclass
class HarRequestTemplate:
    path: str
    endpoint_path: str
    url: str
    headers: dict[str, str]
    body: dict[str, Any]
    template_gl: str
    template_hl: str


@dataclass
class HarPlayerTemplate(HarRequestTemplate):
    media_shape: dict[str, Any]


@dataclass(frozen=True)
class ProbePlayerProfile:
    key: str
    client_name: str
    client_id: str
    client_version: str
    user_agent: str
    origin: str
    platform: str
    client_screen: str = "WATCH"
    device_make: str | None = None
    device_model: str | None = None
    os_name: str | None = None
    os_version: str | None = None
    use_har_template: bool = False


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Probe YouTube Music API flow with Cookie / HAR / WebPo / yt-dlp"
    )
    parser.add_argument(
        "--cookie-file",
        default=DEFAULT_COOKIE_FILE,
        help="Path to raw Cookie header file",
    )
    parser.add_argument(
        "--har-template",
        default="",
        help="Path to music.youtube.com.har; if empty, try common default locations",
    )
    parser.add_argument(
        "--video-ids",
        default=",".join(DEFAULT_VIDEO_IDS),
        help="Comma-separated YouTube video IDs",
    )
    parser.add_argument(
        "--regions",
        default="",
        help="Comma-separated gl:hl pairs; if empty, reuse HAR template gl/hl",
    )
    parser.add_argument(
        "--timeout",
        type=int,
        default=20,
        help="HTTP timeout seconds",
    )
    parser.add_argument(
        "--pydeps-path",
        default=str(DEFAULT_PYDEPS_PATH),
        help="Path that contains nodriver / yt_dlp / yt_dlp_ejs / plugins",
    )
    parser.add_argument(
        "--node-runtime",
        default="node",
        help="Node executable used by yt-dlp js runtime",
    )
    parser.add_argument(
        "--skip-webpo",
        action="store_true",
        help="Skip nodriver WebPo probe",
    )
    parser.add_argument(
        "--require-webpo",
        action="store_true",
        help="Count WebPo probe failure as strict failure",
    )
    parser.add_argument(
        "--skip-download-probe",
        action="store_true",
        help="Skip yt-dlp final audio download probe",
    )
    parser.add_argument(
        "--full-download",
        action="store_true",
        help="Stream the full selected audio object after range probe",
    )
    parser.add_argument(
        "--full-download-max-mb",
        type=float,
        default=8.0,
        help="Skip full download if selected audio exceeds this size (MB)",
    )
    parser.add_argument(
        "--report",
        default=str(DEFAULT_REPORT_PATH),
        help="Optional path to write JSON report",
    )
    parser.add_argument(
        "--strict",
        action="store_true",
        help="Exit non-zero if required probes fail",
    )
    return parser.parse_args()


def load_cookie_header(path: str) -> str:
    return Path(path).read_text(encoding="utf-8", errors="ignore").strip()


def parse_cookie_pairs(cookie_header: str) -> list[tuple[str, str]]:
    pairs: list[tuple[str, str]] = []
    for chunk in cookie_header.split(";"):
        chunk = chunk.strip()
        if not chunk or "=" not in chunk:
            continue
        name, value = chunk.split("=", 1)
        pairs.append((name.strip(), value.strip()))
    return pairs


def cookie_value(cookie_header: str, name: str) -> str:
    prefix = f"{name}="
    for chunk in cookie_header.split(";"):
        chunk = chunk.strip()
        if chunk.startswith(prefix):
            return chunk[len(prefix):]
    return ""


def has_login_cookie(cookie_header: str) -> bool:
    return any(
        cookie_value(cookie_header, name)
        for name in ("SAPISID", "__Secure-3PAPISID", "LOGIN_INFO", "SID")
    )


def append_youtube_consent_cookie(cookie_header: str) -> str:
    if not cookie_header:
        return "SOCS=CAI"
    if "SOCS=" in cookie_header:
        return cookie_header
    return f"{cookie_header}; SOCS=CAI"


def parse_data_sync_id(value: str) -> tuple[str, str]:
    if not value:
        return "", ""
    first, second = (value.split("||", 1) + [""])[:2]
    if second:
        return first, second
    return "", first


def build_sid_authorization(
    scheme: str,
    sid: str,
    origin: str,
    now_epoch_seconds: int,
    user_session_id: str = "",
) -> str:
    extra_values: list[str] = []
    if user_session_id:
        extra_values.append(user_session_id)
    hash_parts = [*extra_values, str(now_epoch_seconds), sid, origin]
    digest = hashlib.sha1(" ".join(hash_parts).encode("utf-8")).hexdigest()
    header_parts = [str(now_epoch_seconds), digest]
    if user_session_id:
        header_parts.append(f"u={user_session_id}")
    return f"{scheme} {'_'.join(header_parts)}"


def build_authorization_header(
    cookie_header: str,
    origin: str,
    user_session_id: str = "",
) -> str:
    now_epoch_seconds = int(time.time())
    values = []
    sapisid = (
        cookie_value(cookie_header, "SAPISID")
        or cookie_value(cookie_header, "__Secure-3PAPISID")
        or cookie_value(cookie_header, "__Secure-1PAPISID")
        or cookie_value(cookie_header, "APISID")
    )
    sapisid_1p = cookie_value(cookie_header, "__Secure-1PAPISID")
    sapisid_3p = cookie_value(cookie_header, "__Secure-3PAPISID")
    if sapisid:
        values.append(
            build_sid_authorization(
                scheme="SAPISIDHASH",
                sid=sapisid,
                origin=origin,
                now_epoch_seconds=now_epoch_seconds,
                user_session_id=user_session_id,
            )
        )
    if sapisid_1p:
        values.append(
            build_sid_authorization(
                scheme="SAPISID1PHASH",
                sid=sapisid_1p,
                origin=origin,
                now_epoch_seconds=now_epoch_seconds,
                user_session_id=user_session_id,
            )
        )
    if sapisid_3p:
        values.append(
            build_sid_authorization(
                scheme="SAPISID3PHASH",
                sid=sapisid_3p,
                origin=origin,
                now_epoch_seconds=now_epoch_seconds,
                user_session_id=user_session_id,
            )
        )
    return " ".join(values)


def build_sapisidhash(cookie_header: str, origin: str) -> str:
    return build_authorization_header(cookie_header, origin)


def accept_language(hl: str, gl: str) -> str:
    return f"{hl},{gl.lower()};q=0.9,en;q=0.8"


def utc_offset_minutes() -> int:
    return -time.timezone // 60


def current_timezone_name() -> str:
    if time.daylight and time.localtime().tm_isdst:
        return time.tzname[1] or "UTC"
    return time.tzname[0] or "UTC"


def find_optional(text: str, *patterns: str) -> str:
    for pattern in patterns:
        match = re.search(pattern, text)
        if match:
            return match.group(1)
    return ""


def find_required(text: str, *patterns: str) -> str:
    value = find_optional(text, *patterns)
    if not value:
        raise ValueError(f"Pattern not found: {patterns[0]}")
    return value


def parse_regions(raw: str) -> list[tuple[str, str]]:
    pairs: list[tuple[str, str]] = []
    for item in raw.split(","):
        item = item.strip()
        if not item:
            continue
        if ":" not in item:
            raise ValueError(f"Invalid region pair: {item}")
        gl, hl = item.split(":", 1)
        pairs.append((gl.strip().upper(), hl.strip()))
    return pairs


def resolve_default_har_path(explicit: str) -> Path | None:
    if explicit:
        path = Path(explicit)
        return path if path.is_file() else None
    for candidate in HAR_DEFAULT_CANDIDATES:
        if candidate.is_file():
            return candidate
    return None


def random_nonce(length: int = 16) -> str:
    alphabet = string.ascii_letters + string.digits
    return "".join(secrets.choice(alphabet) for _ in range(length))


def resolve_browser_name(user_agent: str) -> str:
    if "Chrome/" in user_agent:
        return "Chrome"
    if "Firefox/" in user_agent:
        return "Firefox"
    if "Safari/" in user_agent and "Chrome/" not in user_agent:
        return "Safari"
    return "Unknown"


def resolve_browser_version(user_agent: str) -> str:
    for pattern in (r"Chrome/([0-9.]+)", r"Firefox/([0-9.]+)", r"Version/([0-9.]+)"):
        match = re.search(pattern, user_agent)
        if match:
            return match.group(1)
    return ""


def ensure_gfe_user_agent(user_agent: str) -> str:
    return user_agent if user_agent.endswith(",gzip(gfe)") else f"{user_agent},gzip(gfe)"


def resolve_player_js_url(raw_url: str) -> str:
    if raw_url.startswith("https://") or raw_url.startswith("http://"):
        return raw_url
    if raw_url.startswith("//"):
        return f"https:{raw_url}"
    if raw_url.startswith("/"):
        return f"{MUSIC_ORIGIN}{raw_url}"
    return f"{MUSIC_ORIGIN}/{raw_url}"


def fetch_signature_timestamp(
    session: requests.Session,
    player_js_url: str,
    user_agent: str,
    timeout: int,
) -> int | None:
    if not player_js_url:
        return None
    response = session.get(
        player_js_url,
        headers={"User-Agent": user_agent},
        timeout=timeout,
    )
    response.raise_for_status()
    match = re.search(r'(?:signatureTimestamp|sts)\s*:\s*(?P<sts>[0-9]{5})', response.text)
    return int(match.group("sts")) if match else None


def fetch_bootstrap(session: requests.Session, cookie_header: str, timeout: int) -> BootstrapData:
    normalized_cookie_header = append_youtube_consent_cookie(cookie_header)
    response = session.get(
        MUSIC_ORIGIN,
        headers={
            "Cookie": normalized_cookie_header,
            "User-Agent": WEB_USER_AGENT,
            "Accept": WEB_PAGE_ACCEPT,
            "Accept-Language": "en-US,en;q=0.9",
        },
        timeout=timeout,
    )
    response.raise_for_status()
    html = response.text
    player_js_url = resolve_player_js_url(find_required(html, r'"jsUrl":"([^"]+)"'))
    signature_timestamp = find_optional(html, r'"STS":(\d+)', r'"signatureTimestamp":(\d+)')
    if not signature_timestamp:
        signature_timestamp = str(
            fetch_signature_timestamp(
                session=session,
                player_js_url=player_js_url,
                user_agent=WEB_USER_AGENT,
                timeout=timeout,
            ) or ""
        )
    data_sync_id = find_optional(html, r'"DATASYNC_ID":"([^"]+)"', r'"datasyncId":"([^"]+)"')
    delegated_session_id, user_session_id = parse_data_sync_id(data_sync_id)
    return BootstrapData(
        home_status=response.status_code,
        api_key=find_required(html, r'"INNERTUBE_API_KEY":"([^"]+)"'),
        visitor_data=find_required(html, r'"VISITOR_DATA":"([^"]+)"'),
        session_index=(
            cookie_value(cookie_header, "SESSION_INDEX")
            or find_optional(html, r'"SESSION_INDEX":"?([0-9]+)"?')
            or "0"
        ),
        web_remix_version=find_required(
            html,
            r'"INNERTUBE_CONTEXT_CLIENT_NAME":67,"INNERTUBE_CONTEXT_CLIENT_VERSION":"([^"]+)"',
            r'"INNERTUBE_CLIENT_VERSION":"([^"]+)"',
        ),
        remote_host=find_optional(html, r'"remoteHost":"([^"]+)"'),
        player_js_url=player_js_url,
        signature_timestamp=int(signature_timestamp) if signature_timestamp else None,
        app_install_data=find_optional(html, r'"appInstallData":"([^"]+)"'),
        cold_config_data=find_optional(html, r'"coldConfigData":"([^"]+)"'),
        cold_hash_data=find_optional(
            html,
            r'"coldHashData":"([^"]+)"',
            r'"SERIALIZED_COLD_HASH_DATA":"([^"]+)"',
        ),
        hot_hash_data=find_optional(
            html,
            r'"hotHashData":"([^"]+)"',
            r'"SERIALIZED_HOT_HASH_DATA":"([^"]+)"',
        ),
        device_experiment_id=find_optional(html, r'"deviceExperimentId":"([^"]+)"'),
        rollout_token=find_optional(html, r'"rolloutToken":"([^"]+)"'),
        data_sync_id=data_sync_id,
        delegated_session_id=(
            find_optional(html, r'"DELEGATED_SESSION_ID":"([^"]+)"') or delegated_session_id
        ),
        user_session_id=find_optional(html, r'"USER_SESSION_ID":"([^"]+)"') or user_session_id,
        logged_in=find_optional(html, r'"LOGGED_IN":(true|false)').lower() == "true",
        user_agent=WEB_USER_AGENT,
    )


def build_browse_context(bootstrap: BootstrapData, gl: str, hl: str) -> dict[str, Any]:
    return {
        "client": {
            "clientName": WEB_REMIX_CLIENT_NAME,
            "clientVersion": bootstrap.web_remix_version,
            "hl": hl,
            "gl": gl,
            "visitorData": bootstrap.visitor_data,
            "utcOffsetMinutes": utc_offset_minutes(),
            "userAgent": WEB_USER_AGENT,
            "platform": "DESKTOP",
            "originalUrl": f"{MUSIC_ORIGIN}/",
        },
        "request": {
            "useSsl": True,
            "internalExperimentFlags": [],
            "consistencyTokenJars": [],
        },
        "user": {
            "lockedSafetyMode": False,
        },
    }


def build_browse_headers(
    cookie_header: str,
    bootstrap: BootstrapData,
    gl: str,
    hl: str,
) -> dict[str, str]:
    headers = {
        "Cookie": append_youtube_consent_cookie(cookie_header),
        "User-Agent": WEB_USER_AGENT,
        "Accept-Language": accept_language(hl, gl),
        "Content-Type": "application/json",
        "Origin": MUSIC_ORIGIN,
        "X-Origin": MUSIC_ORIGIN,
        "Referer": f"{MUSIC_ORIGIN}/",
        "X-Goog-AuthUser": bootstrap.session_index or "0",
        "X-Goog-Visitor-Id": bootstrap.visitor_data,
        "X-YouTube-Client-Name": WEB_REMIX_CLIENT_NAME_NUM,
        "X-YouTube-Client-Version": bootstrap.web_remix_version,
        "X-YouTube-Bootstrap-Logged-In": "true" if has_login_cookie(cookie_header) else "false",
    }
    authorization = build_authorization_header(
        cookie_header=append_youtube_consent_cookie(cookie_header),
        origin=MUSIC_ORIGIN,
        user_session_id=bootstrap.user_session_id if bootstrap.logged_in else "",
    )
    if authorization:
        headers["Authorization"] = authorization
    return headers


def run_browse_probe(
    session: requests.Session,
    cookie_header: str,
    bootstrap: BootstrapData,
    gl: str,
    hl: str,
    timeout: int,
) -> dict[str, Any]:
    headers = build_browse_headers(cookie_header, bootstrap, gl, hl)
    payload = {
        "context": build_browse_context(bootstrap, gl, hl),
        "browseId": LIBRARY_PLAYLISTS_BROWSE_ID,
    }
    response = session.post(
        f"{MUSIC_ORIGIN}/youtubei/v1/browse?prettyPrint=false&key={bootstrap.api_key}",
        headers=headers,
        json=payload,
        timeout=timeout,
    )
    result: dict[str, Any] = {
        "status": response.status_code,
        "ok": False,
    }
    if not response.ok:
        result["body_excerpt"] = response.text[:240]
        return result

    root = response.json()
    has_contents = any(key in root for key in ("contents", "continuationContents", "header"))
    result["has_contents"] = has_contents
    result["ok"] = response.status_code == 200 and has_contents
    return result


def decode_har_json_post_data(raw_text: str) -> dict[str, Any]:
    text = raw_text.strip()
    if not text:
        return {}
    if text.startswith("{") or text.startswith("["):
        return json.loads(text)

    decoded_bytes = base64.b64decode(text)
    with contextlib.suppress(OSError):
        decoded_bytes = gzip.decompress(decoded_bytes)
    decoded_text = decoded_bytes.decode("utf-8")
    return json.loads(decoded_text)


def load_har_request_template(path: Path, endpoint_path: str) -> HarRequestTemplate:
    root = json.loads(path.read_text(encoding="utf-8"))
    entries = ((root.get("log") or {}).get("entries") or [])
    request_entry = None
    endpoint_token = f"music.youtube.com/youtubei/v1/{endpoint_path}"
    for entry in entries:
        request = entry.get("request") or {}
        url = str(request.get("url") or "")
        if request.get("method") == "POST" and endpoint_token in url:
            request_entry = entry
            break
    if request_entry is None:
        raise ValueError(f"{endpoint_path} entry not found in HAR: {path}")

    request_payload = request_entry["request"]
    headers = {
        str(header.get("name") or ""): str(header.get("value") or "")
        for header in request_payload.get("headers") or []
        if header.get("name")
    }
    body = decode_har_json_post_data((request_payload.get("postData") or {}).get("text") or "")
    client = ((body.get("context") or {}).get("client") or {})
    request_url = str(request_payload.get("url") or "")
    return HarRequestTemplate(
        path=str(path),
        endpoint_path=endpoint_path,
        url=request_url,
        headers=headers,
        body=body,
        template_gl=str(client.get("gl") or "US"),
        template_hl=str(client.get("hl") or "en-US"),
    )


def load_har_player_template(path: Path) -> HarPlayerTemplate:
    base_template = load_har_request_template(path, endpoint_path="player")
    root = json.loads(path.read_text(encoding="utf-8"))
    entries = ((root.get("log") or {}).get("entries") or [])

    media_samples: list[dict[str, Any]] = []
    for entry in entries:
        request = entry.get("request") or {}
        url = str(request.get("url") or "")
        if "googlevideo.com/videoplayback" not in url:
            continue
        query = parse_qs(urlparse(url).query)
        mime = query.get("mime", [""])[0]
        if not mime.startswith("audio/"):
            continue
        sample_headers = {
            str(header.get("name") or "").lower(): str(header.get("value") or "")
            for header in request.get("headers") or []
            if header.get("name")
        }
        media_samples.append(
            {
                "method": request.get("method"),
                "mime": mime,
                "client": query.get("c", [""])[0],
                "has_pot": "pot" in query,
                "has_range_query": "range" in query,
                "has_rn": "rn" in query,
                "has_rbuf": "rbuf" in query,
                "has_cpn": "cpn" in query,
                "has_cver": "cver" in query,
                "ump": query.get("ump", [""])[0],
                "range": query.get("range", [""])[0],
                "content_type": sample_headers.get("content-type"),
                "body_repr": repr(((request.get("postData") or {}).get("text") or "")[:8]),
            }
        )
        if len(media_samples) >= 4:
            break

    media_shape = {
        "audio_sample_count": len(media_samples),
        "samples": media_samples,
        "summary": {
            "method": media_samples[0]["method"] if media_samples else "",
            "uses_post_body_x00": any(sample["body_repr"] == repr("x\x00") for sample in media_samples),
            "has_pot": any(sample["has_pot"] for sample in media_samples),
            "has_range_query": any(sample["has_range_query"] for sample in media_samples),
            "has_ump": any(sample["ump"] == "1" for sample in media_samples),
        },
    }
    return HarPlayerTemplate(
        path=base_template.path,
        endpoint_path=base_template.endpoint_path,
        url=base_template.url,
        headers=base_template.headers,
        body=base_template.body,
        template_gl=base_template.template_gl,
        template_hl=base_template.template_hl,
        media_shape=media_shape,
    )


def load_har_next_template(path: Path) -> HarRequestTemplate:
    return load_har_request_template(path, endpoint_path="next")


def probe_player_profiles(bootstrap: BootstrapData) -> list[ProbePlayerProfile]:
    return [
        ProbePlayerProfile(
            key="web_remix_official",
            client_name=WEB_REMIX_CLIENT_NAME,
            client_id=WEB_REMIX_CLIENT_NAME_NUM,
            client_version=bootstrap.web_remix_version,
            user_agent=WEB_USER_AGENT,
            origin=MUSIC_ORIGIN,
            platform="DESKTOP",
            client_screen="WATCH_FULL_SCREEN",
            use_har_template=True,
        ),
        ProbePlayerProfile(
            key="tvhtml5",
            client_name=TVHTML5_CLIENT_NAME,
            client_id=TVHTML5_CLIENT_NAME_NUM,
            client_version=TVHTML5_CLIENT_VERSION,
            user_agent=TVHTML5_USER_AGENT,
            origin=YOUTUBE_ORIGIN,
            platform="TV",
        ),
        ProbePlayerProfile(
            key="tvhtml5_downgraded",
            client_name=TVHTML5_CLIENT_NAME,
            client_id=TVHTML5_CLIENT_NAME_NUM,
            client_version=TVHTML5_DOWNGRADED_CLIENT_VERSION,
            user_agent=TVHTML5_DOWNGRADED_USER_AGENT,
            origin=YOUTUBE_ORIGIN,
            platform="TV",
        ),
        ProbePlayerProfile(
            key="ios",
            client_name=IOS_CLIENT_NAME,
            client_id=IOS_CLIENT_NAME_NUM,
            client_version=IOS_CLIENT_VERSION,
            user_agent=IOS_USER_AGENT,
            origin=YOUTUBE_ORIGIN,
            platform="MOBILE",
            device_make="Apple",
            device_model=IOS_DEVICE_MODEL,
            os_name="iOS",
            os_version=IOS_OS_VERSION,
        ),
    ]


def mutate_ad_signal(params: list[dict[str, Any]], key: str, value: str) -> None:
    for item in params:
        if str(item.get("key") or "") == key:
            item["value"] = value
            return


def resolve_har_template_user_agent(template: HarRequestTemplate) -> str:
    return str(template.headers.get("user-agent") or WEB_USER_AGENT)


def resolve_har_template_client_version(
    template: HarRequestTemplate,
    bootstrap: BootstrapData,
) -> str:
    client = ((template.body.get("context") or {}).get("client") or {})
    return str(
        client.get("clientVersion")
        or template.headers.get("x-youtube-client-version")
        or bootstrap.web_remix_version
        or ""
    )


def build_official_har_payload(
    template: HarRequestTemplate,
    bootstrap: BootstrapData,
    gl: str,
    hl: str,
) -> dict[str, Any]:
    payload = copy.deepcopy(template.body)
    context = payload.setdefault("context", {})
    client = context.setdefault("client", {})
    request_context = context.setdefault("request", {})

    user_agent = resolve_har_template_user_agent(template)
    browser_version = resolve_browser_version(user_agent)
    client_version = resolve_har_template_client_version(template, bootstrap)

    client["hl"] = hl
    client["gl"] = gl
    client["clientName"] = WEB_REMIX_CLIENT_NAME
    client["clientVersion"] = client_version
    client["visitorData"] = bootstrap.visitor_data or str(client.get("visitorData") or "")
    client["userAgent"] = ensure_gfe_user_agent(user_agent)
    client["browserName"] = resolve_browser_name(user_agent)
    if browser_version:
        client["browserVersion"] = browser_version
    client["platform"] = "DESKTOP"
    client["osName"] = str(client.get("osName") or "Windows")
    client["osVersion"] = str(client.get("osVersion") or "10.0")
    client["timeZone"] = current_timezone_name()
    client["utcOffsetMinutes"] = utc_offset_minutes()
    client["originalUrl"] = f"{MUSIC_ORIGIN}/"
    if bootstrap.remote_host:
        client["remoteHost"] = bootstrap.remote_host
    client.setdefault("acceptHeader", WEB_PAGE_ACCEPT)
    client.setdefault("clientFormFactor", "UNKNOWN_FORM_FACTOR")
    client.setdefault("playerType", "UNIPLAYER")
    client.setdefault("userInterfaceTheme", "USER_INTERFACE_THEME_LIGHT")
    client.setdefault("connectionType", "CONN_CELLULAR_4G")
    client.setdefault("screenPixelDensity", 1)
    client.setdefault("screenDensityFloat", 1.375)
    client.setdefault("screenWidthPoints", 771)
    client.setdefault("screenHeightPoints", 897)
    client.setdefault("deviceMake", "")
    client.setdefault("deviceModel", "")
    client.setdefault("tvAppInfo", {"livingRoomAppMode": "LIVING_ROOM_APP_MODE_UNSPECIFIED"})

    config_info = client.setdefault("configInfo", {})
    if bootstrap.app_install_data:
        config_info["appInstallData"] = bootstrap.app_install_data
    if bootstrap.cold_config_data:
        config_info["coldConfigData"] = bootstrap.cold_config_data
    if bootstrap.cold_hash_data:
        config_info["coldHashData"] = bootstrap.cold_hash_data
    if bootstrap.hot_hash_data:
        config_info["hotHashData"] = bootstrap.hot_hash_data
    if bootstrap.device_experiment_id:
        client["deviceExperimentId"] = bootstrap.device_experiment_id
    if bootstrap.rollout_token:
        client["rolloutToken"] = bootstrap.rollout_token

    request_context["useSsl"] = True
    request_context["internalExperimentFlags"] = list(request_context.get("internalExperimentFlags") or [])
    request_context["consistencyTokenJars"] = list(request_context.get("consistencyTokenJars") or [])
    if "clientScreenNonce" in context:
        context["clientScreenNonce"] = random_nonce(16)

    ad_params = ((context.get("adSignalsInfo") or {}).get("params") or [])
    if isinstance(ad_params, list):
        mutate_ad_signal(ad_params, "dt", str(int(time.time() * 1000)))
        mutate_ad_signal(ad_params, "u_tz", str(utc_offset_minutes()))

    return payload


def build_official_player_payload(
    template: HarPlayerTemplate,
    bootstrap: BootstrapData,
    video_id: str,
    gl: str,
    hl: str,
) -> dict[str, Any]:
    payload = build_official_har_payload(
        template=template,
        bootstrap=bootstrap,
        gl=gl,
        hl=hl,
    )

    playback = payload.setdefault("playbackContext", {})
    content_playback = playback.setdefault("contentPlaybackContext", {})
    content_playback.setdefault("html5Preference", "HTML5_PREF_WANTS")
    content_playback["referer"] = f"{MUSIC_ORIGIN}/"
    if bootstrap.signature_timestamp is not None:
        content_playback["signatureTimestamp"] = bootstrap.signature_timestamp

    payload["videoId"] = video_id
    payload["playlistId"] = f"RDAMVM{video_id}"
    payload["cpn"] = random_nonce(16)
    payload.setdefault("captionParams", {})
    return payload


def build_official_next_payload(
    template: HarRequestTemplate,
    bootstrap: BootstrapData,
    video_id: str,
    gl: str,
    hl: str,
) -> dict[str, Any]:
    payload = build_official_har_payload(
        template=template,
        bootstrap=bootstrap,
        gl=gl,
        hl=hl,
    )
    payload["videoId"] = video_id
    payload["playlistId"] = f"RDAMVM{video_id}"
    payload["isAudioOnly"] = True
    return payload


def build_official_player_headers(
    template: HarRequestTemplate,
    cookie_header: str,
    bootstrap: BootstrapData,
    gl: str,
    hl: str,
    video_id: str,
) -> dict[str, str]:
    headers: dict[str, str] = {}
    normalized_cookie_header = append_youtube_consent_cookie(cookie_header)
    for name, value in template.headers.items():
        lowered = name.lower()
        if lowered.startswith(":"):
            continue
        if lowered in {"content-length", "cookie", "authorization", "host", "accept-encoding"}:
            continue
        headers[name] = value

    headers["Cookie"] = normalized_cookie_header
    headers["User-Agent"] = resolve_har_template_user_agent(template)
    headers["Origin"] = MUSIC_ORIGIN
    headers["X-Origin"] = MUSIC_ORIGIN
    headers["Referer"] = f"{MUSIC_ORIGIN}/watch?v={video_id}&list=RDAMVM{video_id}"
    headers["Accept-Language"] = accept_language(hl, gl)
    headers["Content-Type"] = "application/json"
    headers["X-Goog-AuthUser"] = bootstrap.session_index or str(
        template.headers.get("x-goog-authuser") or "0"
    )
    headers["X-Goog-Visitor-Id"] = bootstrap.visitor_data or str(
        template.headers.get("x-goog-visitor-id") or ""
    )
    headers["X-YouTube-Client-Name"] = str(
        template.headers.get("x-youtube-client-name") or WEB_REMIX_CLIENT_NAME_NUM
    )
    headers["X-YouTube-Client-Version"] = resolve_har_template_client_version(template, bootstrap)
    headers["X-YouTube-Bootstrap-Logged-In"] = "true" if has_login_cookie(cookie_header) else "false"

    authorization = build_sapisidhash(normalized_cookie_header, MUSIC_ORIGIN)
    has_har_authorization = any(name.lower() == "authorization" for name in template.headers)
    if has_har_authorization and authorization:
        headers["Authorization"] = authorization
    return headers


def build_probe_player_payload(
    profile: ProbePlayerProfile,
    bootstrap: BootstrapData,
    video_id: str,
    gl: str,
    hl: str,
) -> dict[str, Any]:
    context: dict[str, Any] = {
        "client": {
            "clientName": profile.client_name,
            "clientVersion": profile.client_version,
            "platform": profile.platform,
            "hl": hl,
            "gl": gl,
            "userAgent": profile.user_agent,
            "timeZone": "UTC",
            "utcOffsetMinutes": utc_offset_minutes(),
            "clientScreen": profile.client_screen,
            "visitorData": bootstrap.visitor_data,
        },
        "request": {
            "useSsl": True,
            "internalExperimentFlags": [],
            "consistencyTokenJars": [],
        },
        "user": {"lockedSafetyMode": False},
    }
    client = context["client"]
    if profile.device_make is not None:
        client["deviceMake"] = profile.device_make
    if profile.device_model is not None:
        client["deviceModel"] = profile.device_model
    if profile.os_name is not None:
        client["osName"] = profile.os_name
    if profile.os_version is not None:
        client["osVersion"] = profile.os_version
    payload = {
        "context": context,
        "videoId": video_id,
        "contentCheckOk": True,
        "racyCheckOk": True,
    }
    content_playback_context: dict[str, Any] = {
        "html5Preference": "HTML5_PREF_WANTS",
    }
    if bootstrap.signature_timestamp is not None:
        content_playback_context["signatureTimestamp"] = bootstrap.signature_timestamp
    payload["playbackContext"] = {
        "contentPlaybackContext": content_playback_context,
    }
    return payload


def build_probe_player_headers(
    profile: ProbePlayerProfile,
    cookie_header: str,
    bootstrap: BootstrapData,
    gl: str,
    hl: str,
) -> dict[str, str]:
    normalized_cookie_header = append_youtube_consent_cookie(cookie_header)
    headers = {
        "Cookie": normalized_cookie_header,
        "User-Agent": profile.user_agent,
        "Accept-Language": accept_language(hl, gl),
        "Content-Type": "application/json",
        "X-Goog-AuthUser": bootstrap.session_index or "0",
        "X-Goog-Visitor-Id": bootstrap.visitor_data,
        "X-YouTube-Client-Name": profile.client_id,
        "X-YouTube-Client-Version": profile.client_version,
        "Origin": profile.origin,
        "Referer": f"{profile.origin}/",
    }
    if profile.client_name != WEB_REMIX_CLIENT_NAME:
        headers["X-Goog-Api-Format-Version"] = YOUTUBE_PLAYER_API_FORMAT_VERSION
    authorization = build_authorization_header(
        cookie_header=normalized_cookie_header,
        origin=profile.origin,
        user_session_id=bootstrap.user_session_id if bootstrap.logged_in else "",
    )
    if authorization:
        headers["Authorization"] = authorization
        headers["X-Origin"] = profile.origin
    if bootstrap.logged_in:
        headers["X-Youtube-Bootstrap-Logged-In"] = "true"
    if profile.client_name == TVHTML5_CLIENT_NAME and bootstrap.delegated_session_id:
        headers["X-Goog-PageId"] = bootstrap.delegated_session_id
    return headers


def resolve_probe_player_request_url(
    profile: ProbePlayerProfile,
    bootstrap: BootstrapData,
    video_id: str,
) -> str:
    if profile.use_har_template:
        return f"{MUSIC_ORIGIN}/youtubei/v1/player?prettyPrint=false&key={bootstrap.api_key}"
    return (
        f"{YOUTUBE_ORIGIN}/youtubei/v1/player?prettyPrint=false&id={video_id}&key={bootstrap.api_key}"
    )


def parse_content_length(value: Any) -> int | None:
    try:
        parsed = int(str(value))
        return parsed if parsed > 0 else None
    except (TypeError, ValueError):
        return None


def collect_audio_formats(player_root: dict[str, Any]) -> list[dict[str, Any]]:
    streaming_data = player_root.get("streamingData") or {}
    adaptive_formats = streaming_data.get("adaptiveFormats") or []
    audio_formats: list[dict[str, Any]] = []
    for fmt in adaptive_formats:
        mime_type = str(fmt.get("mimeType") or "")
        if "audio/" not in mime_type:
            continue
        audio_formats.append(
            {
                "itag": fmt.get("itag"),
                "mimeType": mime_type,
                "bitrate": int(fmt.get("bitrate") or 0),
                "audioQuality": fmt.get("audioQuality"),
                "contentLength": parse_content_length(fmt.get("contentLength")),
                "approxDurationMs": parse_content_length(fmt.get("approxDurationMs")),
                "hasUrl": bool(fmt.get("url")),
                "hasSignatureCipher": bool(fmt.get("signatureCipher") or fmt.get("cipher")),
                "url": fmt.get("url"),
            }
        )
    audio_formats.sort(key=lambda item: (item["bitrate"], item["contentLength"] or 0), reverse=True)
    return audio_formats


def summarize_request_shape(headers: dict[str, str], payload: dict[str, Any]) -> dict[str, Any]:
    context = payload.get("context") or {}
    client = context.get("client") or {}
    click_tracking = context.get("clickTracking") or {}
    ad_signals = context.get("adSignalsInfo") or {}
    playback = payload.get("playbackContext") or {}
    content_playback = playback.get("contentPlaybackContext") or {}
    return {
        "clientVersion": client.get("clientVersion"),
        "originalUrl": client.get("originalUrl"),
        "remoteHost": client.get("remoteHost"),
        "browserName": client.get("browserName"),
        "browserVersion": client.get("browserVersion"),
        "clientScreen": client.get("clientScreen"),
        "playlistId": payload.get("playlistId"),
        "params": payload.get("params"),
        "isAudioOnly": payload.get("isAudioOnly"),
        "refererHeader": headers.get("Referer") or headers.get("referer"),
        "payloadReferer": content_playback.get("referer"),
        "signatureTimestamp": content_playback.get("signatureTimestamp"),
        "headerHasAuthorization": any(name.lower() == "authorization" for name in headers),
        "clickTrackingParamsPresent": bool(click_tracking.get("clickTrackingParams")),
        "adSignalsCount": len(ad_signals.get("params") or []),
    }


def run_range_probe(
    session: requests.Session,
    url: str,
    user_agent: str,
    timeout: int,
    content_length: int | None,
) -> dict[str, Any]:
    headers = {"User-Agent": user_agent}
    total = content_length

    def request_range(label: str, start: int, end: int) -> dict[str, Any]:
        response = session.get(
            url,
            headers={**headers, "Range": f"bytes={start}-{end}"},
            timeout=timeout,
        )
        content_range = response.headers.get("Content-Range") or ""
        return {
            "label": label,
            "range": f"bytes={start}-{end}",
            "status": response.status_code,
            "content_range": content_range,
            "bytes_read": len(response.content),
            "ok": response.status_code == 206 and len(response.content) > 0,
        }

    results: list[dict[str, Any]] = []
    first_result = request_range("start", 0, DOWNLOAD_RANGE_BYTES - 1)
    results.append(first_result)
    if total is None:
        match = re.search(r"/(\d+)$", first_result["content_range"])
        if match:
            total = int(match.group(1))

    if total is not None and total > 0:
        mid_start = RANGE_PROBE_MIDPOINT
        if mid_start + DOWNLOAD_RANGE_BYTES > total:
            mid_start = max(total // 2 - DOWNLOAD_RANGE_BYTES // 2, 0)
        mid_end = min(mid_start + DOWNLOAD_RANGE_BYTES - 1, total - 1)
        tail_start = max(total - DOWNLOAD_RANGE_BYTES, 0)
        tail_end = total - 1
        planned = [
            ("mid", mid_start, mid_end),
            ("tail", tail_start, tail_end),
        ]
        for label, start, end in planned:
            if start == 0 and end == DOWNLOAD_RANGE_BYTES - 1:
                continue
            results.append(request_range(label, start, end))

    return {
        "total_bytes": total,
        "results": results,
        "ok": all(item["ok"] for item in results),
    }


def run_player_probe(
    session: requests.Session,
    cookie_header: str,
    bootstrap: BootstrapData,
    profile: ProbePlayerProfile,
    gl: str,
    hl: str,
    video_id: str,
    timeout: int,
    template: HarPlayerTemplate | None = None,
) -> dict[str, Any]:
    request_kind = "har_official" if profile.use_har_template else "android_like"
    request_url = ""
    request_summary: dict[str, Any] = {}
    try:
        if profile.use_har_template:
            if template is None:
                raise ValueError("HAR template required for WEB_REMIX official probe")
            payload = build_official_player_payload(template, bootstrap, video_id, gl, hl)
            headers = build_official_player_headers(
                template,
                cookie_header,
                bootstrap,
                gl,
                hl,
                video_id,
            )
            request_url = template.url
        else:
            payload = build_probe_player_payload(profile, bootstrap, video_id, gl, hl)
            headers = build_probe_player_headers(profile, cookie_header, bootstrap, gl, hl)
            request_url = resolve_probe_player_request_url(profile, bootstrap, video_id)
        request_summary = summarize_request_shape(headers, payload)
        response = session.post(
            request_url,
            headers=headers,
            json=payload,
            timeout=timeout,
        )
    except Exception as error:  # noqa: BLE001
        return {
            "profile": profile.key,
            "client_name": profile.client_name,
            "client_version": request_summary.get("clientVersion") or profile.client_version,
            "request_kind": request_kind,
            "request_url": request_url,
            "request_summary": request_summary,
            "ok": False,
            "error": str(error),
        }

    result: dict[str, Any] = {
        "profile": profile.key,
        "client_name": profile.client_name,
        "client_version": request_summary.get("clientVersion") or profile.client_version,
        "request_kind": request_kind,
        "status": response.status_code,
        "ok": False,
        "request_url": request_url,
        "request_summary": request_summary,
    }
    if not response.ok:
        result["body_excerpt"] = response.text[:400]
        return result

    root = response.json()
    playability = root.get("playabilityStatus") or {}
    audio_formats = collect_audio_formats(root)
    direct_formats = [item for item in audio_formats if item["hasUrl"]]
    cipher_formats = [item for item in audio_formats if item["hasSignatureCipher"]]

    result["playability"] = playability.get("status")
    result["reason"] = playability.get("reason") or ""
    result["audio_format_count"] = len(audio_formats)
    result["direct_url_count"] = len(direct_formats)
    result["signature_cipher_count"] = len(cipher_formats)
    result["best_audio"] = audio_formats[0] if audio_formats else None
    result["audio_samples"] = audio_formats[:4]
    result["ok"] = response.status_code == 200 and result["playability"] == "OK"
    result["effective_ok"] = result["ok"]
    result["effective_best_audio"] = result["best_audio"]

    if direct_formats:
        best_direct = direct_formats[0]
        best_direct_url = str(best_direct["url"] or "")
        result["best_direct_audio"] = {
            **best_direct,
            "client": parse_qs(urlparse(best_direct_url).query).get("c", [""])[0],
        }
        result["direct_range_probe"] = run_range_probe(
            session=session,
            url=best_direct_url,
            user_agent=headers["User-Agent"],
            timeout=timeout,
            content_length=best_direct["contentLength"],
        )
    if (
        profile.use_har_template
        and not result["ok"]
        and not request_summary.get("headerHasAuthorization")
    ):
        forced_auth_headers = dict(headers)
        authorization = build_sapisidhash(
            append_youtube_consent_cookie(cookie_header),
            MUSIC_ORIGIN,
        )
        if authorization:
            forced_auth_headers["Authorization"] = authorization
            forced_auth_headers["X-Origin"] = MUSIC_ORIGIN
            forced_summary = summarize_request_shape(forced_auth_headers, payload)
            retry_response = session.post(
                request_url,
                headers=forced_auth_headers,
                json=payload,
                timeout=timeout,
            )
            retry_result: dict[str, Any] = {
                "status": retry_response.status_code,
                "ok": False,
                "request_summary": forced_summary,
            }
            if retry_response.ok:
                retry_root = retry_response.json()
                retry_playability = retry_root.get("playabilityStatus") or {}
                retry_audio_formats = collect_audio_formats(retry_root)
                retry_result["playability"] = retry_playability.get("status")
                retry_result["reason"] = retry_playability.get("reason") or ""
                retry_result["best_audio"] = retry_audio_formats[0] if retry_audio_formats else None
                retry_result["audio_format_count"] = len(retry_audio_formats)
                retry_result["ok"] = (
                    retry_response.status_code == 200
                    and retry_result["playability"] == "OK"
                )
            else:
                retry_result["body_excerpt"] = retry_response.text[:400]
            result["auth_retry"] = retry_result
            if retry_result["ok"]:
                result["effective_ok"] = True
                result["effective_best_audio"] = retry_result.get("best_audio")
                result["resolved_by"] = "har_plus_sapisidhash"
    return result


def run_next_probe(
    session: requests.Session,
    cookie_header: str,
    bootstrap: BootstrapData,
    gl: str,
    hl: str,
    video_id: str,
    timeout: int,
    template: HarRequestTemplate,
) -> dict[str, Any]:
    request_url = template.url
    payload = build_official_next_payload(template, bootstrap, video_id, gl, hl)
    headers = build_official_player_headers(
        template,
        cookie_header,
        bootstrap,
        gl,
        hl,
        video_id,
    )
    request_summary = summarize_request_shape(headers, payload)
    try:
        response = session.post(
            request_url,
            headers=headers,
            json=payload,
            timeout=timeout,
        )
    except Exception as error:  # noqa: BLE001
        return {
            "endpoint": "next",
            "ok": False,
            "request_url": request_url,
            "request_summary": request_summary,
            "error": str(error),
        }

    result: dict[str, Any] = {
        "endpoint": "next",
        "status": response.status_code,
        "ok": False,
        "request_url": request_url,
        "request_summary": request_summary,
    }
    if not response.ok:
        result["body_excerpt"] = response.text[:400]
        return result

    root = response.json()
    result["has_current_video_endpoint"] = bool(root.get("currentVideoEndpoint"))
    result["has_contents"] = any(
        key in root for key in ("contents", "continuationContents", "playerOverlays")
    )
    result["root_keys"] = sorted(root.keys())[:12]
    result["ok"] = response.status_code == 200 and (
        result["has_current_video_endpoint"] or result["has_contents"] or bool(root)
    )
    return result


def ensure_pydeps_path(pydeps_path: Path) -> None:
    resolved = str(pydeps_path.resolve())
    if resolved not in sys.path:
        sys.path.insert(0, resolved)


@contextlib.contextmanager
def suppress_dependency_output() -> Any:
    stdout = io.StringIO()
    stderr = io.StringIO()
    with contextlib.redirect_stdout(stdout), contextlib.redirect_stderr(stderr):
        yield stdout, stderr


def build_temp_netscape_cookie_file(cookie_header: str) -> Path:
    pairs = parse_cookie_pairs(cookie_header)
    fd, raw_path = tempfile.mkstemp(prefix="neri_ytmusic_cookie_", suffix=".txt")
    os.close(fd)
    path = Path(raw_path)
    domains = [".youtube.com", ".google.com"]
    lines = ["# Netscape HTTP Cookie File"]
    for domain in domains:
        for name, value in pairs:
            secure = "TRUE" if name.startswith("__Secure-") else "FALSE"
            lines.append("\t".join([domain, "TRUE", "/", secure, "0", name, value]))
    path.write_text("\n".join(lines) + "\n", encoding="utf-8")
    return path


def probe_pydeps_environment(pydeps_path: Path, node_runtime: str) -> dict[str, Any]:
    result = {
        "pydeps_path": str(pydeps_path),
        "exists": pydeps_path.is_dir(),
        "modules": {
            "nodriver": (pydeps_path / "nodriver").exists(),
            "yt_dlp": (pydeps_path / "yt_dlp").exists(),
            "yt_dlp_ejs": (pydeps_path / "yt_dlp_ejs").exists(),
            "cipherdropx": (pydeps_path / "cipherdropx").exists(),
            "getpot_wpc": (pydeps_path / "yt_dlp_plugins" / "extractor" / "getpot_wpc.py").is_file(),
        },
        "node_runtime": node_runtime,
        "node_version": "",
        "node_ok": False,
    }
    try:
        completed = subprocess.run(
            [node_runtime, "--version"],
            capture_output=True,
            text=True,
            timeout=10,
            check=False,
        )
        version = (completed.stdout or completed.stderr).strip()
        result["node_version"] = version
        result["node_ok"] = completed.returncode == 0 and bool(version)
    except Exception as error:  # noqa: BLE001
        result["node_error"] = str(error)
    return result


def select_best_download_format(formats: list[dict[str, Any]]) -> dict[str, Any] | None:
    candidates: list[dict[str, Any]] = []
    for fmt in formats:
        if fmt.get("vcodec") != "none":
            continue
        url = str(fmt.get("url") or "")
        if not url.startswith("https://"):
            continue
        candidates.append(fmt)
    if not candidates:
        return None

    def sort_key(fmt: dict[str, Any]) -> tuple[float, int, int, int]:
        abr = float(fmt.get("abr") or 0.0)
        filesize = int(fmt.get("filesize") or fmt.get("filesize_approx") or 0)
        ext_score = 1 if str(fmt.get("ext") or "") in {"m4a", "webm"} else 0
        protocol_score = 1 if str(fmt.get("protocol") or "") == "https" else 0
        return (abr, filesize, ext_score, protocol_score)

    candidates.sort(key=sort_key, reverse=True)
    return candidates[0]


def summarize_audio_only_formats(formats: list[dict[str, Any]], limit: int = 8) -> list[dict[str, Any]]:
    candidates: list[dict[str, Any]] = []
    for fmt in formats:
        if fmt.get("vcodec") != "none":
            continue
        url = str(fmt.get("url") or "")
        query = parse_qs(urlparse(url).query) if url else {}
        candidates.append(
            {
                "format_id": fmt.get("format_id"),
                "ext": fmt.get("ext"),
                "acodec": fmt.get("acodec"),
                "protocol": fmt.get("protocol"),
                "abr": fmt.get("abr"),
                "filesize": fmt.get("filesize") or fmt.get("filesize_approx"),
                "client": query.get("c", [""])[0],
                "has_n": "n" in query,
                "has_sig": bool(query.get("sig") or query.get("lsig")),
            }
        )
    return candidates[:limit]


def resolve_selected_content_length(
    selected_format: dict[str, Any],
    url: str,
) -> int | None:
    direct = parse_content_length(selected_format.get("filesize"))
    if direct is not None:
        return direct
    direct = parse_content_length(selected_format.get("filesize_approx"))
    if direct is not None:
        return direct
    query = parse_qs(urlparse(url).query)
    return parse_content_length((query.get("clen") or [""])[0])


def run_full_download_probe(
    session: requests.Session,
    url: str,
    user_agent: str,
    timeout: int,
    expected_bytes: int | None,
    max_bytes: int,
) -> dict[str, Any]:
    if expected_bytes is not None and expected_bytes > max_bytes:
        return {
            "skipped": True,
            "reason": f"selected audio larger than {max_bytes} bytes",
            "ok": True,
        }

    total = 0
    attempts = 0
    statuses: list[int] = []
    errors: list[str] = []
    max_attempts = 4
    last_content_length_header = None

    while attempts < max_attempts:
        headers = {"User-Agent": user_agent}
        if total > 0:
            headers["Range"] = f"bytes={total}-"
        try:
            response = session.get(
                url,
                headers=headers,
                stream=True,
                timeout=timeout,
            )
            statuses.append(response.status_code)
            last_content_length_header = response.headers.get("Content-Length")
            if total == 0 and response.status_code != 200:
                return {
                    "status": response.status_code,
                    "statuses": statuses,
                    "content_length_header": last_content_length_header,
                    "downloaded_bytes": total,
                    "expected_bytes": expected_bytes,
                    "attempts": attempts + 1,
                    "errors": errors,
                    "ok": False,
                }
            if total > 0 and response.status_code not in (200, 206):
                return {
                    "status": response.status_code,
                    "statuses": statuses,
                    "content_length_header": last_content_length_header,
                    "downloaded_bytes": total,
                    "expected_bytes": expected_bytes,
                    "attempts": attempts + 1,
                    "errors": errors,
                    "ok": False,
                }
            for chunk in response.iter_content(DOWNLOAD_RANGE_BYTES):
                if chunk:
                    total += len(chunk)
            if expected_bytes is None or total >= expected_bytes:
                break
            errors.append(f"incomplete_after_attempt_{attempts + 1}")
        except Exception as error:  # noqa: BLE001
            errors.append(str(error))
        finally:
            attempts += 1
    ok = expected_bytes is None and total > 0 or expected_bytes is not None and total == expected_bytes
    return {
        "status": statuses[-1] if statuses else None,
        "statuses": statuses,
        "content_length_header": last_content_length_header,
        "downloaded_bytes": total,
        "expected_bytes": expected_bytes,
        "attempts": attempts,
        "errors": errors,
        "ok": ok,
    }


def run_ytdlp_download_probe(
    session: requests.Session,
    video_id: str,
    cookie_header: str,
    pydeps_path: Path,
    node_runtime: str,
    timeout: int,
    full_download: bool,
    full_download_max_mb: float,
) -> dict[str, Any]:
    if not pydeps_path.is_dir():
        return {
            "ok": False,
            "error": f"pydeps path missing: {pydeps_path}",
        }

    ensure_pydeps_path(pydeps_path)
    cookie_file = build_temp_netscape_cookie_file(cookie_header)
    original_path = os.environ.get("PATH", "")
    try:
        node_dir = (
            Path(node_runtime).expanduser().resolve().parent
            if any(sep in node_runtime for sep in ("/", "\\"))
            else None
        )
        if node_dir is not None:
            os.environ["PATH"] = str(node_dir) + os.pathsep + original_path

        with suppress_dependency_output():
            from yt_dlp import YoutubeDL  # type: ignore

            options = {
                "quiet": True,
                "no_warnings": True,
                "skip_download": True,
                "cookiefile": str(cookie_file),
                "http_headers": {"User-Agent": WEB_USER_AGENT},
                "extractor_args": {
                    "youtube": {"player_client": YTDLP_REQUESTED_PLAYER_CLIENTS},
                    "youtubepot": {"provider": ["wpc"]},
                },
                "remote_components": ["ejs:github"],
                "js_runtimes": {"node": {}},
            }
            with YoutubeDL(options) as ydl:
                info = ydl.extract_info(
                    f"{MUSIC_ORIGIN}/watch?v={video_id}",
                    download=False,
                )
            gc.collect()

        selected = select_best_download_format(list(info.get("formats") or []))
        if selected is None:
            return {
                "ok": False,
                "title": info.get("title"),
                "error": "No HTTPS audio-only format returned by yt-dlp",
            }

        selected_url = str(selected.get("url") or "")
        content_length = resolve_selected_content_length(selected, selected_url)
        range_probe = run_range_probe(
            session=session,
            url=selected_url,
            user_agent=WEB_USER_AGENT,
            timeout=timeout,
            content_length=content_length,
        )
        result: dict[str, Any] = {
            "title": info.get("title"),
            "requested_player_clients": list(YTDLP_REQUESTED_PLAYER_CLIENTS),
            "audio_candidates": summarize_audio_only_formats(list(info.get("formats") or [])),
            "selected_format": {
                "format_id": selected.get("format_id"),
                "ext": selected.get("ext"),
                "acodec": selected.get("acodec"),
                "protocol": selected.get("protocol"),
                "abr": selected.get("abr"),
                "filesize": selected.get("filesize"),
                "client": parse_qs(urlparse(selected_url).query).get("c", [""])[0],
                "url": selected_url,
            },
            "range_probe": range_probe,
            "ok": range_probe["ok"],
        }

        if full_download:
            max_bytes = int(full_download_max_mb * 1024 * 1024)
            result["full_download_probe"] = run_full_download_probe(
                session=session,
                url=selected_url,
                user_agent=WEB_USER_AGENT,
                timeout=timeout,
                expected_bytes=content_length,
                max_bytes=max_bytes,
            )
            result["ok"] = result["ok"] and result["full_download_probe"]["ok"]
        return result
    except Exception as error:  # noqa: BLE001
        return {
            "ok": False,
            "error": str(error),
        }
    finally:
        os.environ["PATH"] = original_path
        cookie_file.unlink(missing_ok=True)


def run_webpo_probe(
    cookie_header: str,
    video_id: str,
    pydeps_path: Path,
) -> dict[str, Any]:
    if not pydeps_path.is_dir():
        return {
            "ok": False,
            "error": f"pydeps path missing: {pydeps_path}",
        }

    ensure_pydeps_path(pydeps_path)
    temp_profile_dir = Path(tempfile.mkdtemp(prefix="neri_webpo_profile_"))
    cookies = parse_cookie_pairs(cookie_header)

    async def runner() -> dict[str, Any]:
        import nodriver  # type: ignore
        from nodriver import cdp  # type: ignore

        browser = await nodriver.start(
            headless=True,
            lang="en-US",
            user_data_dir=temp_profile_dir,
        )
        tab = await browser.get("about:blank")
        try:
            for name, value in cookies:
                await tab.send(
                    cdp.network.set_cookie(
                        name=name,
                        value=value,
                        domain=".youtube.com",
                        path="/",
                        secure=name.startswith("__Secure-"),
                        url=YOUTUBE_ORIGIN,
                    )
                )

            page = None
            snapshot: dict[str, Any] | None = None
            for bootstrap_url in WEBPO_BOOTSTRAP_URLS:
                page = await browser.get(bootstrap_url)
                for _ in range(10):
                    await page.sleep(1)
                    raw = await page.evaluate(WEBPO_SNAPSHOT_SCRIPT, return_by_value=True)
                    if not raw:
                        continue
                    snapshot = json.loads(raw)
                    if snapshot.get("hasYtcfg") and snapshot.get("hasWebPoClient"):
                        break
                if snapshot and snapshot.get("hasYtcfg") and snapshot.get("hasWebPoClient"):
                    break

            if not snapshot:
                return {
                    "ok": False,
                    "error": "Failed to evaluate WebPo page snapshot",
                }

            content_binding = (
                video_id
                if snapshot.get("bindsGvsTokenToVideoId")
                else str(snapshot.get("dataSyncId") or snapshot.get("visitorData") or "")
            )
            if not content_binding:
                return {
                    "ok": False,
                    "snapshot": snapshot,
                    "error": "No content binding available for WebPo mint",
                }

            mint_script = WEBPO_MINT_SCRIPT_TEMPLATE.replace(
                "__CONTENT_BINDING__",
                json.dumps(content_binding, ensure_ascii=False),
            )
            mint_result: dict[str, Any] | None = None
            for _ in range(6):
                raw = await page.evaluate(mint_script, await_promise=True, return_by_value=True)
                mint_result = json.loads(raw) if raw else None
                if mint_result and mint_result.get("status") == "ok":
                    break
                if mint_result and mint_result.get("status") == "backoff":
                    await page.sleep(1)
                    continue
                break

            return {
                "ok": bool(mint_result and mint_result.get("status") == "ok" and mint_result.get("token")),
                "snapshot": snapshot,
                "content_binding": content_binding,
                "mint_result": mint_result,
            }
        finally:
            with contextlib.suppress(Exception):
                browser.stop()

    try:
        with suppress_dependency_output():
            result = asyncio.run(runner())
            gc.collect()
        return result
    except Exception as error:  # noqa: BLE001
        return {
            "ok": False,
            "error": str(error),
        }
    finally:
        shutil.rmtree(temp_profile_dir, ignore_errors=True)


def summarize(report: dict[str, Any], args: argparse.Namespace) -> dict[str, Any]:
    failures: list[str] = []

    bootstrap = report.get("bootstrap") or {}
    if not bootstrap.get("ok"):
        failures.append(f"bootstrap={bootstrap.get('home_status')}")

    har_template = report.get("har_template") or {}
    if not har_template.get("ok"):
        failures.append("har_template=missing")
    next_template = har_template.get("next_template") or {}
    if har_template.get("ok") and not next_template.get("ok"):
        failures.append("har_next_template=missing")

    webpo = report.get("webpo") or {}
    if webpo.get("enabled") and args.require_webpo and not webpo.get("ok"):
        failures.append(f"webpo={webpo.get('error') or (webpo.get('mint_result') or {}).get('status')}")

    for region in report.get("regions") or []:
        region_id = f"{region['gl']}:{region['hl']}"
        browse = region.get("browse") or {}
        if not browse.get("ok"):
            failures.append(f"{region_id}:browse={browse.get('status')}")
        for player in region.get("players") or []:
            profile_key = player.get("profile") or ""
            if profile_key not in STRICT_REQUIRED_PLAYER_PROFILES:
                continue
            if not player.get("effective_ok", player.get("ok")):
                profile = profile_key or player.get("client_name") or "unknown"
                failures.append(
                    f"{region_id}:{player['videoId']}:{profile}:player={player.get('status')}/{player.get('playability')}"
                )
                continue
            expected_itag = STRICT_REQUIRED_AUDIO_ITAGS.get(profile_key)
            actual_itag = ((player.get("effective_best_audio") or {}).get("itag"))
            if expected_itag is not None and actual_itag != expected_itag:
                failures.append(
                    f"{region_id}:{player['videoId']}:{profile_key}:itag={actual_itag or 'missing'}"
                )
        for next_result in region.get("nexts") or []:
            if not next_result.get("ok"):
                failures.append(
                    f"{region_id}:{next_result['videoId']}:next={next_result.get('status') or next_result.get('error')}"
                )

    if not args.skip_download_probe:
        for item in report.get("downloads") or []:
            if not item.get("ok"):
                failures.append(f"download:{item['videoId']}={item.get('error') or 'probe_failed'}")

    return {
        "failure_count": len(failures),
        "failures": failures,
        "strict_requirements": [
            "bootstrap",
            "har_template",
            "har_next_template",
            "browse",
            "web_remix_official_player",
            *([] if args.skip_download_probe else ["yt_dlp_download"]),
            *([] if not args.require_webpo or args.skip_webpo else ["webpo"]),
        ],
        "passed": not failures,
    }


def main() -> int:
    args = parse_args()
    cookie_header = load_cookie_header(args.cookie_file)
    if not cookie_header:
        print("Cookie header is empty", file=sys.stderr)
        return 2

    video_ids = [item.strip() for item in args.video_ids.split(",") if item.strip()]
    if not video_ids:
        print("No video IDs provided", file=sys.stderr)
        return 2

    session = requests.Session()
    report: dict[str, Any] = {
        "generated_at": time.strftime("%Y-%m-%d %H:%M:%S"),
        "cookie_file": args.cookie_file,
        "cookie_names_present": [
            name
            for name in [
                "SAPISID",
                "__Secure-3PAPISID",
                "SID",
                "HSID",
                "SSID",
                "LOGIN_INFO",
            ]
            if cookie_value(cookie_header, name)
        ],
        "video_ids": video_ids,
        "environment": probe_pydeps_environment(Path(args.pydeps_path), args.node_runtime),
        "regions": [],
        "downloads": [],
    }

    try:
        bootstrap = fetch_bootstrap(session, cookie_header, args.timeout)
        report["bootstrap"] = {
            "ok": True,
            "home_status": bootstrap.home_status,
            "api_key_present": bool(bootstrap.api_key),
            "visitor_data_present": bool(bootstrap.visitor_data),
            "session_index": bootstrap.session_index,
            "web_remix_version": bootstrap.web_remix_version,
            "remote_host": bootstrap.remote_host,
            "player_js_url_present": bool(bootstrap.player_js_url),
            "signature_timestamp": bootstrap.signature_timestamp,
            "app_install_data_present": bool(bootstrap.app_install_data),
            "device_experiment_id_present": bool(bootstrap.device_experiment_id),
            "rollout_token_present": bool(bootstrap.rollout_token),
            "data_sync_id_present": bool(bootstrap.data_sync_id),
            "delegated_session_id_present": bool(bootstrap.delegated_session_id),
            "user_session_id_present": bool(bootstrap.user_session_id),
            "logged_in": bootstrap.logged_in,
        }
    except Exception as error:  # noqa: BLE001
        report["bootstrap"] = {
            "ok": False,
            "error": str(error),
        }
        bootstrap = None

    har_path = resolve_default_har_path(args.har_template)
    if har_path is None:
        report["har_template"] = {
            "ok": False,
            "error": "HAR template not found",
        }
        template = None
        next_template = None
    else:
        try:
            template = load_har_player_template(har_path)
            next_template = load_har_next_template(har_path)
            report["har_template"] = {
                "ok": True,
                "path": template.path,
                "template_gl": template.template_gl,
                "template_hl": template.template_hl,
                "media_shape": template.media_shape,
                "player_request": summarize_request_shape(template.headers, template.body),
                "next_template": {
                    "ok": True,
                    "request_url": next_template.url,
                    "request_summary": summarize_request_shape(
                        next_template.headers,
                        next_template.body,
                    ),
                },
            }
        except Exception as error:  # noqa: BLE001
            report["har_template"] = {
                "ok": False,
                "path": str(har_path),
                "error": str(error),
            }
            template = None
            next_template = None

    if args.skip_webpo:
        report["webpo"] = {
            "enabled": False,
            "skipped": True,
        }
    else:
        report["webpo"] = {
            "enabled": True,
            **run_webpo_probe(
                cookie_header=cookie_header,
                video_id=video_ids[0],
                pydeps_path=Path(args.pydeps_path),
            ),
        }

    explicit_regions = parse_regions(args.regions) if args.regions else []
    if explicit_regions:
        regions = explicit_regions
    elif template is not None:
        regions = [(template.template_gl, template.template_hl)]
    else:
        regions = [("US", "en-US")]

    if bootstrap is not None:
        profiles = [
            profile
            for profile in probe_player_profiles(bootstrap)
            if template is not None or not profile.use_har_template
        ]
        for gl, hl in regions:
            region_result: dict[str, Any] = {
                "gl": gl,
                "hl": hl,
                "browse": run_browse_probe(
                    session=session,
                    cookie_header=cookie_header,
                    bootstrap=bootstrap,
                    gl=gl,
                    hl=hl,
                    timeout=args.timeout,
                ),
                "players": [],
                "nexts": [],
            }
            for video_id in video_ids:
                for profile in profiles:
                    player_result = run_player_probe(
                        session=session,
                        cookie_header=cookie_header,
                        bootstrap=bootstrap,
                        profile=profile,
                        gl=gl,
                        hl=hl,
                        video_id=video_id,
                        timeout=args.timeout,
                        template=template,
                    )
                    player_result["videoId"] = video_id
                    region_result["players"].append(player_result)
                if next_template is not None:
                    next_result = run_next_probe(
                        session=session,
                        cookie_header=cookie_header,
                        bootstrap=bootstrap,
                        gl=gl,
                        hl=hl,
                        video_id=video_id,
                        timeout=args.timeout,
                        template=next_template,
                    )
                    next_result["videoId"] = video_id
                    region_result["nexts"].append(next_result)
            report["regions"].append(region_result)

    if not args.skip_download_probe:
        for video_id in video_ids:
            probe_result = run_ytdlp_download_probe(
                session=session,
                video_id=video_id,
                cookie_header=cookie_header,
                pydeps_path=Path(args.pydeps_path),
                node_runtime=args.node_runtime,
                timeout=args.timeout,
                full_download=args.full_download,
                full_download_max_mb=args.full_download_max_mb,
            )
            probe_result["videoId"] = video_id
            report["downloads"].append(probe_result)

    report["summary"] = summarize(report, args)
    with suppress_dependency_output():
        gc.collect()
    output = json.dumps(report, ensure_ascii=False, indent=2)
    print(output)

    if args.report:
        report_path = Path(args.report)
        report_path.parent.mkdir(parents=True, exist_ok=True)
        report_path.write_text(output, encoding="utf-8")

    if args.strict and not report["summary"]["passed"]:
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
