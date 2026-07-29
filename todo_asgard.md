# TODO

A running list of things that are missing, broken, or would be nice to add. Roughly ordered by importance within each section.

## 🔒 Security / release hygiene
- [x] **Stop committing `asgard.jks` to the repo**, and get the store/key passwords out of `app/build.gradle.kts`. Now uses `local.properties` and keystore is in `.gitignore`.
- [x] Enable `isMinifyEnabled` / R8 shrinking for release builds, and populated `proguard-rules.pro`.
- [x] Add a `LICENSE` file — Added MIT License.
- [x] **Sign the APK** — Release build is now signed and ready for installation.
- [x] **Rename APK** — Output name modified to `Asgard_Android13.apk`.

## 🐛 Correctness / reliability
- [x] **Queue doesn't survive process death.** The queue is now persisted to a JSON file and reloaded on app start.
- [x] **Removing a task from the queue doesn't cancel the underlying `DownloadManager` download** — it now correctly calls `downloadManager.remove(id)`.
- [x] No retry button for failed items — added a Refresh button to failed queue items to retry extraction/download.
- [x] `DownloadService` uses `START_NOT_STICKY`, so if the system kills the service mid-download it won't restart and the download silently stalls. (Changed to `START_STICKY`).
- [x] **URL Clearing** — URL box now clears immediately after adding to queue.
- [ ] No handling for age-restricted, region-locked, members-only, or livestream URLs — improved some error logging, but specific UX for these is still pending.
- [x] Filename sanitization (`[\\/:*?"<>|]`) is minimal — improved with trimming, length capping, and removal of trailing dots/spaces.

## ✨ Features
- [x] **Quality/format picker.** Added a selection dialog for video and audio formats.
- [x] **Video thumbnail & metadata preview** before committing to a download (duration, channel name, thumbnail).
- [x] **Download history** — Added a persistent history section for completed tasks.
- [x] **Wi-Fi–only download option** in Settings, to avoid burning mobile data on large videos.
- [x] **Notification tap-to-open** — tapping the persistent download notification now opens the app.
- [x] **Batch paste** — accept multiple URLs (newline separated) pasted into the field at once.
- [x] **Light theme support** — `AsgardTheme` now supports system theme and provides a light color scheme.
- [x] **Multi-pane Navigation** — Moved queue and history to a dedicated Downloads screen for better space management.
- [ ] **Pause/resume** individual downloads.
- [ ] **Subtitle download** support (NewPipeExtractor exposes subtitle streams).
- [ ] **Search** — search YouTube from within the app instead of only accepting direct links.

## 🧪 Testing / tooling
- [ ] Actual unit tests — `ExampleUnitTest.kt` and `ExampleInstrumentedTest.kt` are still the default Android Studio boilerplate.
- [ ] Basic CI (GitHub Actions) to run `./gradlew build`/`test`/`lint` on PRs.
- [ ] Lint/ktlint/detekt setup for consistent code style.

## 🌍 Polish
- [x] Localize `strings.xml` beyond English — UI strings migrated to resources.
- [ ] Empty/error states could use icons or illustrations instead of plain text.
- [x] App icon/branding pass — implemented custom branding for the app icon matching KdexMusicPlayer.
