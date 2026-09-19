"""ytmusicapi wrapper.

Semua metadata musik (pencarian, saran, beranda, radio, playlist milik akun)
diambil lewat ytmusicapi. Kredensial akun dibaca dari file yang dipasang di
server, bukan dari aplikasi Android.
"""

from __future__ import annotations

import json
import os
import threading
from typing import Any

from ytmusicapi import YTMusic

_lock = threading.Lock()
_client: YTMusic | None = None
_client_authed: bool = False


def _auth_source() -> str | None:
    """Return an auth payload/path accepted by YTMusic(auth=...) or None."""
    inline = os.environ.get("YTMUSIC_AUTH_JSON", "").strip()
    if inline:
        # Validate early so a broken paste fails loudly at startup.
        json.loads(inline)
        return inline
    path = os.environ.get("YTMUSIC_AUTH_FILE", "/data/auth.json")
    return path if os.path.exists(path) else None


def client() -> YTMusic:
    global _client, _client_authed
    with _lock:
        if _client is None:
            auth = _auth_source()
            _client = YTMusic(auth) if auth else YTMusic()
            _client_authed = auth is not None
        return _client


def is_authenticated() -> bool:
    client()
    return _client_authed


def _thumb(item: dict[str, Any]) -> str:
    thumbs = item.get("thumbnails") or []
    if not thumbs:
        return ""
    return thumbs[-1].get("url", "")


def _artist(item: dict[str, Any]) -> str:
    artists = item.get("artists") or []
    names = [a.get("name", "") for a in artists if a.get("name")]
    if names:
        return ", ".join(names)
    return item.get("author") or ""


def _duration(item: dict[str, Any]) -> str:
    if item.get("duration"):
        return str(item["duration"])
    seconds = item.get("duration_seconds")
    if isinstance(seconds, int) and seconds > 0:
        return f"{seconds // 60}:{seconds % 60:02d}"
    return ""


def as_track(item: dict[str, Any]) -> dict[str, Any] | None:
    video_id = item.get("videoId")
    title = item.get("title")
    if not video_id or not title:
        return None
    album = item.get("album")
    if isinstance(album, dict):
        album = album.get("name", "")
    return {
        "videoId": video_id,
        "title": title,
        "artist": _artist(item),
        "album": album or "",
        "duration": _duration(item),
        "thumbnail": _thumb(item),
    }


def tracks(items: list[dict[str, Any]]) -> list[dict[str, Any]]:
    out: list[dict[str, Any]] = []
    seen: set[str] = set()
    for item in items or []:
        track = as_track(item)
        if track and track["videoId"] not in seen:
            seen.add(track["videoId"])
            out.append(track)
    return out


def search(query: str, filter: str | None = None, limit: int = 40) -> list[dict[str, Any]]:
    allowed = {"songs", "videos", "albums", "artists", "playlists", "community_playlists", "featured_playlists", "uploads"}
    kwargs: dict[str, Any] = {"limit": limit}
    if filter in allowed:
        kwargs["filter"] = filter
    return tracks(client().search(query, **kwargs))


def suggestions(query: str) -> list[str]:
    try:
        return [s for s in client().get_search_suggestions(query) if isinstance(s, str)]
    except Exception:
        return []


def home(limit: int = 6) -> list[dict[str, Any]]:
    shelves = []
    for shelf in client().get_home(limit=limit):
        items = tracks(shelf.get("contents") or [])
        if items:
            shelves.append({"title": shelf.get("title", ""), "items": items})
    return shelves


def charts(country: str = "ID") -> list[dict[str, Any]]:
    data = client().get_charts(country)
    shelves = []
    for key in ("trending", "videos", "songs"):
        block = data.get(key)
        items = block.get("items") if isinstance(block, dict) else block
        parsed = tracks(items or [])
        if parsed:
            shelves.append({"title": key.title(), "items": parsed})
    return shelves


def moods() -> list[dict[str, Any]]:
    out = []
    for group, entries in (client().get_mood_categories() or {}).items():
        for entry in entries:
            out.append({
                "title": entry.get("title", ""),
                "params": entry.get("params", ""),
                "group": group,
            })
    return out


def mood_playlists(params: str) -> list[dict[str, Any]]:
    data = client().get_mood_playlists(params)
    return [
        {
            "title": p.get("title", ""),
            "playlistId": p.get("playlistId", ""),
            "thumbnail": _thumb(p),
        }
        for p in data or []
        if p.get("playlistId")
    ]


def radio(video_id: str, limit: int = 30) -> list[dict[str, Any]]:
    data = client().get_watch_playlist(videoId=video_id, limit=limit, radio=True)
    return [t for t in tracks(data.get("tracks") or []) if t["videoId"] != video_id]


def related(video_id: str) -> list[dict[str, Any]]:
    return radio(video_id)


def playlist(playlist_id: str, limit: int = 200) -> dict[str, Any]:
    data = client().get_playlist(playlist_id, limit=limit)
    return {
        "title": data.get("title", ""),
        "thumbnail": _thumb(data),
        "items": tracks(data.get("tracks") or []),
    }


def album(browse_id: str) -> dict[str, Any]:
    data = client().get_album(browse_id)
    return {
        "title": data.get("title", ""),
        "thumbnail": _thumb(data),
        "items": tracks(data.get("tracks") or []),
    }


def library_playlists(limit: int = 50) -> list[dict[str, Any]]:
    return [
        {
            "title": p.get("title", ""),
            "playlistId": p.get("playlistId", ""),
            "thumbnail": _thumb(p),
            "count": p.get("count"),
        }
        for p in client().get_library_playlists(limit=limit) or []
        if p.get("playlistId")
    ]


def liked_songs(limit: int = 200) -> list[dict[str, Any]]:
    data = client().get_liked_songs(limit=limit)
    return tracks(data.get("tracks") or [])


def history() -> list[dict[str, Any]]:
    return tracks(client().get_history())


def lyrics(video_id: str) -> dict[str, Any]:
    watch = client().get_watch_playlist(videoId=video_id, limit=1)
    browse_id = watch.get("lyrics")
    if not browse_id:
        return {"lyrics": "", "source": ""}
    data = client().get_lyrics(browse_id)
    return {"lyrics": data.get("lyrics") or "", "source": data.get("source") or ""}
