# Discord Social SDK (Android)

Nao MD keeps the build dependency-free until the official Discord Social SDK
package is supplied.

When you have access to Discord's official `discord_partner_sdk.aar`:
1. Put it in `app/libs/discord_partner_sdk.aar`.
2. Add `implementation(files("libs/discord_partner_sdk.aar"))` to `app/build.gradle.kts`.
3. Enable Prefab/native CMake as required by the SDK.
4. Set the real Discord Application ID.
5. Register `discord-<APPLICATION_ID>:/authorize/callback` in the Discord Developer Portal.
6. Wire the native `discordpp::Client::UpdateRichPresence` bridge to
   `NaoDiscordManager.updateForTrack()`.

Do not download random third-party copies of the AAR.
