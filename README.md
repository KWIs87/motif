# Motif

A personal Android front end and home screen widget for Spotify. Minimal, dark, no clutter. Single-user, not distributed.

- Spec: [docs/spec.md](docs/spec.md)
- Wireframes: https://claude.ai/artifact/9Ub5hBHd5zAiG92wTZwdSu
- Landing page: https://kwis87.github.io/motif/

## Install on the phone
Every push to `main` builds a signed APK and attaches it to the **latest** release:
https://github.com/KWIs87/motif/releases/tag/latest — download `motif-latest.apk` on the phone and open it.

## Build locally
Requires JDK 17 and an Android SDK (platform 35, build-tools 35). Put `sdk.dir=/path/to/sdk` in `local.properties`, then:

```
./gradlew assembleDebug
```

Spotify's App Remote AARs are not on Maven: download them from https://github.com/spotify/android-sdk/releases into `app/libs/`. The project compiles without them, but playback needs them.

## Data credits
Spotify (playback and library), Last.fm, Genius, LRCLIB, GetSongBPM, Ticketmaster Discovery, MusicBrainz, Wikidata, Discogs.
