# Changelog

All notable changes to this project are documented here. The format is based on
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project
adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

Nothing yet.

## [0.1.0] - 2026-06-04

First pre-release. Everything below is what the 0.1.0 build ships; since there's
no prior version, it's all new.

### App

- Backs up and syncs phone storage to cloud storage through a bundled rclone
  engine. Pick a local source, a remote destination, and a direction: upload,
  download, or two-way bisync.
- Sync tasks carry a schedule (15 min up to daily, or manual-only), Wi-Fi-only
  and requires-charging constraints, separate Wi-Fi/metered bandwidth caps, and
  include/exclude filter globs.
- Background execution runs in a foreground `dataSync` WorkManager worker with a
  progress notification; a boot receiver re-registers schedules after reboot, and
  an opt-in persistent watchdog keeps long syncs alive on aggressive OEMs.
- Two storage paths depending on flavor: the `foss` build takes
  `MANAGE_EXTERNAL_STORAGE` for direct filesystem access (SD card and storage
  root), while the `play` build stages through SAF so it works under
  scoped storage.
- Screens: a Home tab with lifetime transfer/run stats, the sync task list and
  editor, remote management, a remote file browser, sync history, bisync
  conflict resolution, settings, and a four-step onboarding pager. A Glance
  home-screen widget and a Quick Settings tile surface sync status outside the
  app.

### Remotes and OAuth

- In-app OAuth 2.0 + PKCE for Google Drive, OneDrive, and Dropbox via Custom
  Tabs, with `state` validation and the verifier kept in-process, so no client
  secret ships in the APK. Google Drive uses the reverse-DNS redirect its Android
  OAuth client requires; OneDrive and Dropbox use a verified HTTPS App Link
  (`https://lusk.app/virga/oauth/callback`, backed by a hosted
  `assetlinks.json`).
- For anything else, import an existing `rclone.conf` or configure a remote by
  hand (S3, WebDAV, SFTP, and the rest of rclone's backends). Config export is
  available too.

### Engine and data

- rclone v1.74.2, cross-compiled 16 KB-page-aligned for `arm64-v8a`,
  `armeabi-v7a`, and `x86_64`, shipped in-APK as `librclone.so` and run as a
  remote-control daemon. The daemon binds to loopback only, on a random port,
  authenticated with a per-session bcrypt htpasswd credential.
- `RcloneEngine` drives the daemon over its JSON RC API: daemon lifecycle,
  `listRemotes` / `createRemote` / `deleteRemote` / `listDir` / `sync` /
  `bisync`, with sync progress polled back as a `Flow`.
- The `rclone.conf` (OAuth tokens, credentials) is encrypted at rest with an
  Android Keystore-backed key and decrypted only for the running daemon.
- Room stores remotes, sync tasks, run history, and bisync conflicts; DataStore
  holds preferences. Conflict detection pairs rclone's `.conflict1`/`.conflict2`
  files and offers keep-first / keep-second / keep-both.

### Privacy

- No analytics or trackers. Crash reporting is opt-in (Sentry), off until the
  user turns it on, and redacts file paths, remote names, and tokens.

### Build, CI, and distribution

- Multi-module Kotlin + Jetpack Compose (Material 3 Expressive) + Navigation 3 +
  Hilt + Coroutines/Flow, on a shared `core:designsystem`.
- `foss` and `play` flavors; ABI splits plus a universal APK; R8-minified,
  baseline-profiled, signed release builds.
- CI runs build, unit + Roborazzi screenshot tests, lint, and CodeQL on every
  PR, with coverage (Kover → Codecov + SonarCloud) and secret/dependency scans
  (GitGuardian, Snyk, Semgrep, Socket). Tagging `vX.Y.Z` builds, signs, and
  publishes the FOSS APKs with checksums.
- Showcase site at <https://lusky3.github.io/virga/>, deployed from `gh-pages/`
  on `main` via Actions.

[Unreleased]: https://github.com/lusky3/virga/compare/v0.1.0...HEAD
[0.1.0]: https://github.com/lusky3/virga/releases/tag/v0.1.0
