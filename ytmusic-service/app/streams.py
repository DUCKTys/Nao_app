"""Resolusi URL audio/video langsung.

ytmusicapi hanya menyediakan metadata, jadi URL pemutaran diambil dengan
yt-dlp. Kalau file cookies akun dipasang (COOKIES_FILE), stream yang keluar
adalah stream akun tersebut: tanpa iklan dan bisa bitrate premium.
"""

from __future__ import annotations

import os
import time
import threading
from typing import Any

from yt_dlp import YoutubeDL

_CACHE: dict[str, tuple[float, dict[str, Any]]] = {}
_CACHE_TTL = 60 * 20  # URL googlevideo umumnya berlaku beberapa jam
_lock = threading.Lock()


def _ydl_opts() -> dict[str, Any]:
    opts: dict[str, Any] = {
        "quiet": True,
        "no_warnings": True,
        "skip_download": True,
        "noplaylist": True,
        # Sumber metadata pemutaran; klien android/web dicoba berurutan.
        "extractor_args": {"youtube": {"player_client": ["android_vr", "web_safari", "web"]}},
    }
    cookies = os.environ.get("COOKIES_FILE", "/data/cookies.txt")
    if os.path.exists(cookies):
        opts["cookiefile"] = cookies
    browser = os.environ.get("COOKIES_FROM_BROWSER", "").strip()
    if browser and "cookiefile" not in opts:
        opts["cookiesfrombrowser"] = (browser,)
    proxy = os.environ.get("YTDLP_PROXY", "").strip()
    if proxy:
        opts["proxy"] = proxy
    return opts


def _extract(video_id: str) -> dict[str, Any]:
    with _lock:
        hit = _CACHE.get(video_id)
        if hit and time.time() - hit[0] < _CACHE_TTL:
            return hit[1]
    with YoutubeDL(_ydl_opts()) as ydl:
        info = ydl.extract_info(f"https://music.youtube.com/watch?v={video_id}", download=False)
    with _lock:
        _CACHE[video_id] = (time.time(), info)
    return info


def audio(video_id: str) -> list[dict[str, Any]]:
    info = _extract(video_id)
    out = []
    for f in info.get("formats") or []:
        if f.get("acodec") in (None, "none") or f.get("vcodec") not in (None, "none"):
            continue
        url = f.get("url")
        if not url:
            continue
        out.append({
            "url": url,
            "mimeType": f"audio/{f.get('ext') or 'mp4'}",
            "bitrate": int(f.get("abr") or 0) * 1000,
            "codec": f.get("acodec") or "",
        })
    out.sort(key=lambda s: s["bitrate"], reverse=True)
    return out


def video(video_id: str) -> list[dict[str, Any]]:
    info = _extract(video_id)
    out = []
    for f in info.get("formats") or []:
        if f.get("vcodec") in (None, "none") or f.get("acodec") in (None, "none"):
            continue
        url = f.get("url")
        if not url:
            continue
        out.append({
            "url": url,
            "mimeType": f"video/{f.get('ext') or 'mp4'}",
            "width": int(f.get("width") or 0),
            "height": int(f.get("height") or 0),
        })
    out.sort(key=lambda s: s["height"], reverse=True)
    return out


def details(video_id: str) -> dict[str, Any]:
    info = _extract(video_id)
    return {
        "videoId": video_id,
        "title": info.get("title") or "",
        "artist": info.get("artist") or info.get("uploader") or "",
        "album": info.get("album") or "",
        "durationMs": int((info.get("duration") or 0) * 1000),
        "thumbnail": info.get("thumbnail") or "",
    }


def prefetch(video_id: str) -> None:
    try:
        _extract(video_id)
    except Exception:
        pass
