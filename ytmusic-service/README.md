# Nao Music Service (ytmusicapi)

Layanan kecil pengganti InnerTube/Piped di aplikasi Nao MD.

- Pencarian, beranda, chart, mood, radio, playlist, lagu disukai, riwayat, lirik → **ytmusicapi**
- URL audio/video untuk diputar → **yt-dlp** memakai cookie akun Anda, sehingga tanpa iklan dan bisa kualitas premium

## 1. Jalankan

```bash
cp .env.example .env      # isi API_TOKEN
docker build -t nao-ytmusic .
docker run -d -p 8080:8080 --env-file .env -v $PWD/data:/data nao-ytmusic
```

Tanpa Docker:

```bash
pip install -r requirements.txt
API_TOKEN=xxx uvicorn app.main:app --host 0.0.0.0 --port 8080
```

Cek: `curl -H "X-Nao-Token: xxx" http://localhost:8080/health`

## 2. Masuk dengan akun YouTube Music

Untuk playlist dan rekomendasi pribadi:

```bash
pip install ytmusicapi
ytmusicapi oauth --file data/auth.json      # ikuti kode yang muncul di layar
```

Alternatif (tanpa OAuth): `ytmusicapi browser --file data/auth.json`, lalu tempel header request
`/browse` dari music.youtube.com sesuai panduan https://ytmusicapi.readthedocs.io/en/stable/setup/

## 3. Cookie untuk pemutaran tanpa iklan

Ekspor cookie youtube.com dalam format Netscape (mis. ekstensi "Get cookies.txt LOCALLY")
dan simpan ke `data/cookies.txt`. Pakai akun yang berlangganan Premium bila ingin
bitrate tertinggi. Tanpa cookie, layanan tetap jalan sebagai anonim.

Cookie itu setara kata sandi — simpan hanya di server Anda sendiri.

## 4. Hubungkan ke aplikasi

Di Nao MD, isi alamat layanan dan token pada `NaoMusicBackend`
(`app/src/main/java/com/nao/md/project/music/NaoMusicBackend.kt`) atau lewat
SharedPreferences `nao_music_backend` (`base_url`, `token`).

## Endpoint

| Metode | Path | Keterangan |
| --- | --- | --- |
| GET | `/search?q=&filter=&limit=` | pencarian lagu/video |
| GET | `/suggestions?q=` | saran pencarian |
| GET | `/home?limit=` | beranda |
| GET | `/charts?country=ID` | tangga lagu |
| GET | `/moods`, `/moods/playlists?params=` | mood & genre |
| GET | `/radio/{videoId}`, `/related/{videoId}` | radio otomatis |
| GET | `/playlist/{id}`, `/album/{browseId}` | isi playlist/album |
| GET | `/library/playlists`, `/library/liked`, `/library/history` | pustaka akun |
| GET | `/lyrics/{videoId}` | lirik |
| GET | `/stream/{videoId}` | URL audio langsung |
| GET | `/stream/{videoId}/video` | URL video langsung |
| POST | `/prefetch/{videoId}` | panaskan cache stream |
