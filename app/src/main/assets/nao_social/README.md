# Nao Music Discord Integration Assets

Rich Presence asset intent:
- Activity name: `Nao Music`
- Details: current song title
- State: current artist
- Large image: current song artwork URL
- Small image: `nao_music_discord_branding`
- Button URL: `https://duck-tys.vercel.app/`

The official Discord Social SDK for Android is distributed as `discord_partner_sdk.aar`
through the Discord Developer Portal. This project keeps the Android build dependency-free
until that AAR is supplied, while the UI, account-link state, payload builder and asset
folder are prepared for the SDK bridge.
