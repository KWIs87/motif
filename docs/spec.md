# Motif — product spec

Owner: Kirk Isham. Personal use, one Spotify Premium account, Android only.
Wireframes (source of truth for layout): https://claude.ai/artifact/9Ub5hBHd5zAiG92wTZwdSu
Last revised: 2026-11-01.

## 1. Architecture

| Piece | Role |
|---|---|
| Motif app | All UI. Reads the library through the Spotify Web API; drives playback through the Spotify App Remote SDK. |
| Spotify app | Must stay installed. Decodes and plays audio; Motif never touches the audio stream. |
| Home screen widget | Standard Android app widget, 4x2 default, resizable. Sends the same playback commands. |
| GitHub Actions | Builds and signs every push to `main`; APK lands on the rolling `latest` release. |

Spotify app registration: Development mode, client ID `9343a80fdf824a4e8c1b896312207ff0`, redirect `motif://callback`, package `com.kirkisham.motif`. The release key SHA-1 is registered under Android packages in the Spotify dashboard.

Outside data (all free keys, supplied as BuildConfig fields):

| Need | Source |
|---|---|
| Genre tags, similar artists, artist bios | Last.fm |
| Song credits | Genius (plus Discogs, no key) |
| Synced lyrics | LRCLIB (no key) |
| BPM / key | GetSongBPM (key pending; BPM sort is Build 2) |
| Artist facts | Wikidata, MusicBrainz (no key) |
| Concerts near the user | Ticketmaster Discovery API |

Known constraint: Spotify removed audio-features (BPM), recommendations and offline-download endpoints for new developer apps in Nov 2024, and limited developer mode further in Feb 2026. Nothing in Build 1 depends on those endpoints. Verify any new endpoint against the current developer-mode list before building on it.

## 2. Design rules

- Dark only. Ground `#121212`, surface `#1A1A1A`, hairline `#1E1E1E`, text `#E8E6E1`, muted `#8A8882`, accent `#C9A96E`.
- Typeface: Inter (system fallback). Weights 300/400/500 only.
- Minimal, understated. No gradients, no promos, no pop-ups, no video, no social features.
- Touch targets ≥ 44 dp. Section labels 11 sp uppercase muted. Row height 52–64 dp with hairline dividers.
- Icons: thin stroke (1.6 px), no filled icons.
- Appearance settings (text color, background color, text size, border glow color and intensity) apply app-wide; the widget follows them unless its own toggle is off.

## 3. Build 1 scope

### 3.1 Login
PKCE flow (done in skeleton). Custom Tab to Spotify, back via `motif://callback`. Tokens in encrypted prefs. Silent refresh.

### 3.2 Playback (App Remote)
- Connect on app start if Spotify is installed; show a one-line prompt if it is not.
- Commands: play URI (track, playlist, episode), pause, resume, next, previous, seek, shuffle, repeat, add to queue.
- Player state stream drives Now playing and the widget.
- Podcasts: back/next are 15-second skips, not episode skips.

### 3.3 Home
- Header: Motif wordmark, star icon (Claude sheet — Build 2, show nothing yet), clock icon (Alarm), gear (Settings — Build 2, basic appearance only in Build 1).
- Pinned playlists at top. Below them, the playlist played most recently moves to the top.
- "All playlists" row. "Podcasts" row. Most recent podcast episode row with resume.
- Mini player at the bottom: art, title, artist, play/pause; swipe left skips.
- Long press a playlist: Pin/Unpin, Play, Shuffle, Rename, Delete (Delete confirms).

### 3.4 All playlists
Every playlist, pinned first. Sort by Recent, Name, Song count. Same long press menu.

### 3.5 Playlist
- Header: cover, name, count and total length, Play and Shuffle.
- Sort chips: Name, Artist, Length, Custom, BPM (BPM chip hidden until Build 2).
- Custom is the user's own order: long press a song, drag; dropping switches the chip to Custom and writes the order back to Spotify.
- Swipe right on a song: add to playlist (same sheet as the plus button).
- Long press a song: Play next, Add to playlist, Go to artist, Remove from this playlist.
- 3-dot menu: Mass edit (Build 2), Rename, Delete.

### 3.6 Now playing (song)
- Art fills the top. Tap art → Song and artist info (Build 2; no-op in Build 1).
- Title, artist. Scrubber with elapsed/remaining.
- Controls: previous, play/pause, next. Plus button beside them: short press shows the 3 playlists most recently added to; long press opens a new-playlist name field.
- Lyrics button: swaps the art for synced lyrics from LRCLIB, current line highlighted, auto-scroll; tap again returns the art. Hidden when LRCLIB has no match. (This is the sing-along feature; full karaoke mode is parked.)
- Share: WhatsApp and SMS only, via Android share sheet filtered to those two.

### 3.7 Now playing (podcast)
Same layout; back/next are 15 s skips; plus button hidden; speed chip cycles 1x / 1.25x / 1.5x / 2x remembered per show; sleep chip takes minutes and counts down.

### 3.8 Alarm
Reached from the clock icon on Home. Each alarm is its own object:
- Name (required), time, repeat: One time (a date, defaults to next occurrence) or Recurring (days of week).
- Songs: the alarm's own short list, not linked to a playlist. Plus adds from search or from any playlist. Drag to reorder, x to remove. Header shows count and total length.
- Order: Start to end / Repeat first song / Random start.
- Fade in: slider in seconds, 0 to the length of the first song. Ramps the media stream volume linearly from Start volume to End volume (both 0–100 %).
- Play for: 5 / 10 / 20 min / Until dismissed; default 10 min; long press a chip to type minutes. The song list loops if shorter than the window. At the end: stop and go silent.
- Snooze length: 5 / 10 / 15 min.
- Saved alarms listed by name with an on/off switch; tap loads into the editor; long press deletes.

Ringing screen: full-screen over the lock screen, screen stays on. Shows time, date, alarm name, fade progress, current song x of y, time left, previous/next within the list, large Snooze, small Dismiss. **Either volume button = snooze** while the ringing screen is in front (intercept key events; do not change volume). Dismiss is on screen only. Power button is not used.

Implementation notes: `AlarmManager.setAlarmClock` (exact, survives Doze); a foreground service owns the ramp and the stop timer; request the battery-optimization exemption once; if App Remote has not reported playback within 10 s of alarm time, play a bundled tone through the alarm stream at End volume so the alarm still wakes the user.

### 3.9 Widget
4x2 default, resizable. Shows title, artist (or show and episode), back, play/pause, next. Nothing else. Tapping the text opens Now playing. Widget settings: text color, background color, background opacity, text size, border glow color and intensity, and the switch "Widget uses app settings".

### 3.10 Settings (Build 1 subset)
Appearance section only (see design rules). The rest of the Settings board is Build 2/3.

## 4. Build 2 and later (not now)

- Search screen (box + results). Needed by Alarm's plus button in Build 1 only as a picker; the full screen is Build 2.
- Song and artist info: credits (Genius, Discogs), songs like this (Last.fm → Spotify match), artist facts (Wikidata, MusicBrainz), "Lately" paragraph written by Claude weekly, concerts near you (Ticketmaster). Hide empty sections.
- Mass edit on playlists: checkboxes, drag handles, bulk remove.
- BPM sort (GetSongBPM) once the key arrives.
- Claude sheet: in-app chat with Spotify tools over the user's library.
- Settings: Improvement report (text + screenshot + log → Claude Code session on this repo → new build), Updates row with Install.
- Karaoke mode: projector/cast lyrics screen, queue, karaoke-version swap. Vocal removal from Spotify audio is not possible and is out of scope.
- Offline downloads: not available to developer-mode apps; parked.

## 5. Out of scope, permanently
Social features, video, ads/promos, anything that bypasses the Spotify app for audio.

## Decisions

Choices made while building where the boards or the text left room. Append; do not rewrite.

### Build 1, stage 1 (0.2)
- App Remote connection is one shared object with reference-counted holds: the activity holds it while in front, the alarm service and widget take their own. It reconnects with exponential backoff (1 s → 30 s) while anyone holds it.
- Album art comes through App Remote's ImagesApi (no Web API image round-trips); playlist covers come from the Web API JSON.
- Stage 1 only: tapping a playlist on Home plays it. Stage 2 moves Play into the long-press menu and tapping opens the playlist.

### Build 1, stage 2 (0.3)
- Home header: star (Claude sheet, no-op until Build 2), clock (Alarm), gear (Settings). The board's search icon is Build 2 and is not shown.
- Home lists pinned playlists, then the 6 most recently played of the rest ("Recent"); the full list is one tap away under All playlists.
- "All podcasts" row opens a sheet listing the saved shows (GET /me/shows); tapping a show plays it and Spotify resumes where that show left off. No separate podcasts screen exists in the boards, so none was added.
- "Last episode" on Home is the last episode App Remote reported playing (stored locally). Spotify's recently-played endpoint does not return episodes.
- Playlist screen: long press and release without moving opens the song menu; long press and drag reorders. A drop while sorted by Name/Artist/Length places the song after its new neighbour in Spotify's order and switches the chip to Custom.
- Shuffle on the Playlist header is a thin icon beside the Play circle (the board shows Play only; the text asks for both).
- Song menu has Play next, Add to playlist, Remove from this playlist. "Go to artist" waits for the Song and artist info screen (Build 2).
- Delete (unfollow) confirms in a bottom sheet, not a dialog.
- Now playing scrubber shows elapsed on the left and remaining ("-2:35") on the right, following the text rather than the board's total.
- Share uses the stock Android share sheet with "Title — Artist" and the open.spotify.com link. Filtering the sheet to WhatsApp and SMS only would mean hard-coding package names; left for later.
- Podcast speed uses App Remote's setPodcastPlaybackSpeed, which has fixed steps (0.5/0.8/1/1.2/1.5/2/3). The 1.25x chip sends 1.2x. Speed is remembered per show (the episode's "album" URI from App Remote is the show).
- Sleep timer lives in PlaybackController so it keeps counting when the screen is left; the chip shows minutes left; long press the chip cancels.
- Settings color pickers are a sheet with 16 presets plus R/G/B sliders. Muted/hairline tones are derived from the chosen text and background so contrast steps stay the same.
