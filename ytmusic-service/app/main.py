"""Nao Music backend service (ytmusicapi + yt-dlp).

Dipakai oleh aplikasi Android Nao MD sebagai pengganti InnerTube/Piped:
- metadata & pustaka akun  -> ytmusicapi
- URL pemutaran (audio/video, tanpa iklan) -> yt-dlp dengan cookie akun

Amankan dengan API_TOKEN: setiap request harus mengirim header
`X-Nao-Token: <token>`.
"""

from __future__ import annotations

import os

from fastapi import FastAPI, Header, HTTPException, Query
from fastapi.responses import JSONResponse

from . import streams, ytm

app = FastAPI(title="Nao Music Service", version="1.0.0")


def guard(token: str | None) -> None:
    expected = os.environ.get("API_TOKEN", "").strip()
    if not expected:
        return
    if token != expected:
        raise HTTPException(status_code=401, detail="Invalid token")


@app.middleware("http")
async def auth_middleware(request, call_next):
    if request.url.path not in ("/health", "/docs", "/openapi.json"):
        try:
            guard(request.headers.get("x-nao-token"))
        except HTTPException as exc:
            return JSONResponse({"detail": exc.detail}, status_code=exc.status_code)
    return await call_next(request)


@app.get("/health")
def health():
    return {
        "ok": True,
        "authenticated": ytm.is_authenticated(),
        "cookies": os.path.exists(os.environ.get("COOKIES_FILE", "/data/cookies.txt")),
    }


@app.get("/search")
def search(q: str = Query(min_length=1), filter: str | None = None, limit: int = 40):
    return {"items": ytm.search(q, filter, limit)}


@app.get("/suggestions")
def suggestions(q: str = Query(min_length=1)):
    return {"items": ytm.suggestions(q)}


@app.get("/home")
def home(limit: int = 6):
    return {"shelves": ytm.home(limit)}


@app.get("/charts")
def charts(country: str = "ID"):
    return {"shelves": ytm.charts(country)}


@app.get("/moods")
def moods():
    return {"items": ytm.moods()}


@app.get("/moods/playlists")
def mood_playlists(params: str):
    return {"items": ytm.mood_playlists(params)}


@app.get("/radio/{video_id}")
def radio(video_id: str, limit: int = 30):
    return {"items": ytm.radio(video_id, limit)}


@app.get("/related/{video_id}")
def related(video_id: str):
    return {"items": ytm.related(video_id)}


@app.get("/playlist/{playlist_id}")
def playlist(playlist_id: str, limit: int = 200):
    return ytm.playlist(playlist_id, limit)


@app.get("/album/{browse_id}")
def album(browse_id: str):
    return ytm.album(browse_id)


@app.get("/library/playlists")
def library_playlists(limit: int = 50):
    return {"items": ytm.library_playlists(limit)}


@app.get("/library/liked")
def liked(limit: int = 200):
    return {"items": ytm.liked_songs(limit)}


@app.get("/library/history")
def history():
    return {"items": ytm.history()}


@app.get("/lyrics/{video_id}")
def lyrics(video_id: str):
    return ytm.lyrics(video_id)


@app.get("/stream/{video_id}")
def stream(video_id: str):
    audio = streams.audio(video_id)
    if not audio:
        raise HTTPException(status_code=502, detail="Tidak ada stream audio")
    return {"audio": audio, "details": streams.details(video_id)}


@app.get("/stream/{video_id}/video")
def stream_video(video_id: str):
    return {"video": streams.video(video_id)}


@app.post("/prefetch/{video_id}")
def prefetch(video_id: str):
    streams.prefetch(video_id)
    return {"ok": True}
