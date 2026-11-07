# Melodify

A client-side Fabric mod for **Minecraft Java 1.21.11** that shows **synchronized lyrics** for the song
playing in **Spotify** directly on your screen, with a polished ClickGUI and a fully customizable HUD.

- Spotify: official Web API, "Authorization Code with PKCE" login in your browser (no client secret, no hardcoded credentials)
- Lyrics: [LRCLIB](https://lrclib.net), a free open lyrics database with a public API (no API key needed), incl. time-synced lyrics
- Windows 10/11, Java 21, Fabric Loader, Fabric API

## Install (players)

1. Install Fabric Loader for 1.21.11 and [Fabric API](https://modrinth.com/mod/fabric-api).
2. Put `melodify-1.0.0.jar` in `%appdata%\.minecraft\mods`.
3. Do the one-time Spotify setup below, then start Minecraft and press **Right Shift**.

## One-time Spotify setup (about 3 minutes)

Spotify requires every app that uses its API to be registered. You register your own (it is free) so that
no shared credentials ship inside the mod.

1. Open <https://developer.spotify.com/dashboard> and sign in with your Spotify account.
2. Click **Create app**. Name/description can be anything.
3. Under **Redirect URIs** add exactly: `http://127.0.0.1:8888/callback` (Spotify no longer accepts `localhost`; use the IP).
4. Under **Which API/SDKs are you planning to use?** tick **Web API**. Save.
5. Open the app's **Settings** and copy the **Client ID** (32 characters).
6. If the app is in *Development mode* (the default), open **User Management** and add the email of the
   Spotify account you listen with. Spotify has also changed its rules for developer-mode apps over time
   (for example, requiring a Premium account for the app owner), so if the dashboard shows a notice, follow it.
7. In Minecraft: **Right Shift -> Spotify -> Paste from clipboard -> Connect**.
   Your browser opens; approve access; the tab says "Melodify is connected". Done - it reconnects automatically on later launches.

The mod reads the *currently playing track* of your Spotify account, so it works with the **Spotify desktop app**
(and phone/web player) as long as you are logged into the same account. It only requests two read-only scopes:
`user-read-currently-playing` and `user-read-playback-state`. It never controls playback.

If the browser doesn't open, click **Copy login link** and paste it into a browser yourself.
If port 8888 is taken, change `redirectPort` in `config/melodify.json` **and** the redirect URI in the dashboard.

## Using it

| Where | What |
|---|---|
| **Right Shift** (rebindable in Controls) | Open/close the Melodify menu |
| **Lyrics HUD** tab | Enable HUD, song info, previous/next lyric, animated transitions, unsynced fallback, sync offset, **Test HUD**, **Move HUD** |
| **Spotify** tab | Client ID, Connect / Reconnect, Disconnect/Cancel, Copy login link, status, current song + artist, playback progress, lyrics status |
| **Appearance** tab | Text size, text colour (RGB), background on/off, background opacity, animation speed, alignment, HUD width, Move HUD, Reset position |

- **Test HUD** plays a clearly-labelled 30-second sample so you can tune the look without Spotify.
- **Move HUD** opens an editor: drag the HUD anywhere, `Esc`/`Enter` saves, `R` resets.
- **Sync offset** shifts lyrics earlier/later if they feel slightly off (e.g. Bluetooth latency).
- All settings are saved automatically to `config/melodify.json`.

## How lyrics are chosen

Lyrics come from LRCLIB only. Nothing is scraped from Spotify or anywhere else.

- Exact lookup by title + artist + album + duration first, then a filtered search.
- A result is only used if the **artist matches**, the **duration is within 3 s**, and the **title matches**
  (a different/translated title is accepted only if artist matches and duration is within 0.5 s).
  If nothing passes, you get "No lyrics found" rather than the wrong song.
- Synced (timestamped) lyrics are preferred. If only plain lyrics exist, they are shown with **estimated**
  timing spread across the song (toggle "Use unsynced lyrics as fallback"; the GUI says "timing estimated").
- Instrumentals show "Instrumental". Podcasts are skipped.
- Multi-language lyrics work (UTF-8). Glyph coverage is limited by Minecraft's built-in fonts.
- Lyrics are cached in memory and on disk (`config/melodify/lyrics-cache`), so each song is fetched once.

## Performance and safety

- No network call ever runs on the game thread. Spotify is polled every 2-3 s on a background thread (slower when idle),
  with exponential back-off on errors and `Retry-After` handling; playback position is interpolated locally between polls.
- Pause, resume, skip and seek are detected from each poll.
- All failures (Spotify offline, network down, bad responses) are caught: the HUD just shows a message and retries.
- Only the Spotify **refresh token** is stored, in `config/melodify/spotify-tokens.json` on your PC. Access tokens live in memory.
  Tokens are never logged or shown. **Disconnect** deletes the file. Nothing secret is in this repository; the Client ID in
  `melodify.json` is not a secret (PKCE has no client secret) but don't commit your config folder anyway.

## Build from source

Requirements: JDK 21, internet access (Gradle downloads Minecraft, Fabric Loom and Fabric API).

```bat
gradlew.bat build
```

The mod jar is `build/libs/melodify-1.0.0.jar` (ignore `-sources`). Copy it to your `mods` folder.
To run a dev client: `gradlew.bat runClient`.

Versions live in `gradle.properties`. If Gradle can't resolve one, copy the current 1.21.11 values (Loader, Fabric API,
Loom) from <https://fabricmc.net/develop/>.

## Code layout

```
net.melodify
  MelodifyCore          singletons; no Minecraft imports
  MelodifyClient        Fabric entrypoint: keybind, HUD layer, tick hook   (Minecraft-specific)
  config/               MelodifyConfig (JSON persistence)
  spotify/              SpotifyAuth (PKCE), SpotifyClient (poller), PlaybackState
  lyrics/               LyricsService (LRCLIB + matching + cache), LrcParser, LyricsManager
  hud/                  HudData, HudRenderer                                (renderer is Minecraft-specific)
  gui/                  MelodifyScreen, HudEditorScreen, Ui widgets         (Minecraft-specific)
  util/                 Http (HttpURLConnection), Json
```

Everything outside `MelodifyClient`, `hud/HudRenderer` and `gui/*` is independent of the Minecraft version, so
supporting a future Minecraft release means updating just those few files plus `gradle.properties` / `fabric.mod.json`.

## Troubleshooting

| Symptom | Fix |
|---|---|
| "Paste your Spotify Client ID first" | Copy the Client ID from the dashboard, then use *Paste from clipboard* |
| `INVALID_CLIENT: Invalid redirect URI` in the browser | Redirect URI in the dashboard must be exactly `http://127.0.0.1:8888/callback` |
| "Spotify refused access" (403) | Add your account under *User Management* in the dashboard |
| "Port 8888 is already in use" | Change `redirectPort` in `melodify.json` and in the dashboard |
| HUD shows nothing | Make sure the HUD is enabled and a song is actually playing; try **Test HUD** |
| "No lyrics found" | LRCLIB doesn't have that song (or only has a different recording); try another track |
| Lyrics a bit early/late | Adjust **Lyrics sync offset** |
