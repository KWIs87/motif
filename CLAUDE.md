# Motif — working notes for Claude Code

Motif is Kirk Isham's personal Android front end for his Spotify Premium library, plus a
home screen widget. Single user, not distributed. The official Spotify app stays installed
and does the audio; Motif drives it.

Read `docs/spec.md` before changing anything. The wireframes are the source of truth for
layout and behavior: https://claude.ai/artifact/9Ub5hBHd5zAiG92wTZwdSu

## Stack
- Kotlin, Jetpack Compose, Material 3 with the Motif palette in `ui/Theme.kt`. minSdk 26, target 35.
- Spotify Web API over OkHttp + kotlinx.serialization (`auth/SpotifyApi.kt`), PKCE login in `auth/SpotifyAuth.kt`.
- Use the Feb 2026 endpoints: /playlists/{id}/items, /me/playlists, /me/library; playlist rows are items.item.
- Spotify App Remote SDK for playback. The AARs are fetched into `app/libs/` by CI (see
  `.github/workflows/build.yml`); locally, download them from github.com/spotify/android-sdk/releases.
- Builds run on GitHub Actions. Every push to `main` produces a signed APK attached to the
  rolling `latest` release. There is no Play Store step.

## Rules
- Never commit keys, tokens or the keystore. API keys arrive as `BuildConfig` fields from env
  vars / `local.properties` (see `app/build.gradle.kts`). The Spotify client ID is public (PKCE).
- Dark, minimal, understated. No pop-ups, no promos, no social features. If a screen is not in
  the wireframes, ask before inventing it.
- Keep `MainActivity` thin. Screens go under `ui/<screen>/`, data under `data/`.
- Build must stay green: run `./gradlew assembleDebug` before pushing when an SDK is available.
- Bump `versionCode`/`versionName` in `app/build.gradle.kts` on every user-visible change; the
  in-app update check compares `versionCode`.
- Logging: never log tokens, keys or the user's library contents.

## Build order (Build 1)
1. Playback through App Remote: connect, play/pause, skip, play a playlist or track URI, state stream.
2. Home, All playlists, Playlist (sort chips, pinned, last-played-to-top), Now playing with the art ↔ lyrics toggle (LRCLIB).
3. Alarm (own song list, fade in seconds, play-for minutes, volume buttons snooze).
4. Widget 4x2, resizable: title, artist, back, play/pause, next.
Everything else is Build 2+ (see spec).
