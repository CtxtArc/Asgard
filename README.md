# Asgard 🎬

**Asgard** is a native Android app for downloading YouTube videos and audio directly to your device — no server, no API key, no ads. It uses [NewPipeExtractor](https://github.com/TeamNewPipe/NewPipeExtractor) to resolve stream URLs and Android's own `DownloadManager` to handle the actual transfer, wrapped in a small dark-themed Jetpack Compose UI.

> "YGGDRASIL DOWNLOADER" — because it pulls video down from the branches of YouTube. 🌳

## Features

- **Paste a link or share into the app** — Asgard registers as a share target, so you can hit "Share" on a YouTube video/playlist from the YouTube app or a browser and send it straight to Asgard.
- **MP4 or MP3** — download the best available video stream, or extract just the audio.
- **Playlist support** — pasting a playlist URL expands it into individual queued downloads, saved into a subfolder named after the playlist.
- **Download queue** — add several links in a row; they're processed sequentially with live progress and status per item.
- **Custom download folder** — pick any folder on-device (via the Storage Access Framework) instead of the default `Downloads` folder.
- **Foreground service + notification** — downloads keep running and are visible in the notification shade while active.

## How it works

Asgard doesn't talk to any Asgard-owned backend — everything happens on-device:

1. **`NewPipeExtractor`** parses the YouTube URL/playlist and resolves available audio/video streams, using a custom `Downloader` (`AsgardApp.kt`) backed by **OkHttp** to make the actual HTTP calls.
2. **`DownloaderViewModel`** owns the in-memory download queue, picks the best stream (highest bitrate for MP3, highest resolution muxed stream for MP4), and hands the resulting direct URL to Android's `DownloadManager`.
3. **`DownloadService`** is a foreground `Service` that observes the queue and keeps a persistent notification alive while at least one item is downloading/extracting/moving, so the OS doesn't kill the app mid-download.
4. If a custom download folder is set, the finished file is copied there via `DocumentFile`/SAF and the original copy in the public `Downloads` folder is deleted.

## Tech stack

| Layer | Choice |
|---|---|
| Language | Kotlin |
| UI | Jetpack Compose + Material 3 |
| Extraction | [NewPipeExtractor](https://github.com/TeamNewPipe/NewPipeExtractor) `v0.26.3` |
| Networking | OkHttp |
| Downloads | Android `DownloadManager` + Storage Access Framework |
| Min SDK / Target SDK | 33 / 36 |

## Project structure

```
app/src/main/java/com/example/asgard/
├── AsgardApp.kt              # Application class — initializes NewPipe with an OkHttp-backed Downloader
├── MainActivity.kt           # Compose UI: downloader screen, settings screen, queue list
├── DownloaderViewModel.kt    # Queue state, extraction, DownloadManager orchestration
├── DownloadService.kt        # Foreground service + notification while downloads are active
└── ui/theme/                 # Compose color scheme, typography, theming
```

## Building form source

```bash
git clone <repo-url>
cd Asgard
./gradlew assembleDebug      # debug APK
./gradlew assembleRelease    # release APK -> Asgard_Android13.apk
```

Requires Android Studio (or the Gradle/JDK toolchain it bundles) with SDK 36 installed.


## Permissions

| Permission | Why |
|---|---|
| `INTERNET` / `ACCESS_NETWORK_STATE` | fetch video info & stream data |
| `FOREGROUND_SERVICE` / `FOREGROUND_SERVICE_DATA_SYNC` | keep downloads running with a visible notification |
| `POST_NOTIFICATIONS` | show download progress notification (Android 13+) |

## Legal note

Asgard is a general-purpose downloading tool built on an open-source extraction library. Downloading copyrighted content without permission may violate YouTube's Terms of Service and/or local law. You're responsible for how you use this app.
## License

No license file is currently included in this repository — add one (MIT/GPL/etc.) before distributing or accepting contributions.
