# Changelog

All notable changes to this project are documented here. The format is based on
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project
adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added
- File browser screen with breadcrumb navigation and remote picker
  (`:feature:explorer`).
- Sync history screen joining `SyncRunEntity` with task names; reachable from
  the Sync tab's app bar.
- Conflict resolution: `ConflictEntity` + DAO, `ConflictRepository` that
  detects rclone bisync `.conflict1`/`.conflict2` pairs on the destination
  after a bisync run, and a Compose screen offering keep-1 / keep-2 / keep-both
  (carried out via rclone `operations/movefile` + `operations/deletefile`).
- 4-step onboarding pager (welcome, storage permission, battery hint, get
  started); `MainActivity` gates on the persisted flag and keeps the system
  splash up until the gate resolves.
- OAuth 2.0 + PKCE browser flow for Google Drive, OneDrive, and Dropbox:
  Custom Tabs launch, custom-scheme redirect activity (`virga://oauth/callback`)
  with state validation, code→token exchange producing the JSON shape rclone
  stores in `[remote].token`, then `config/create` on the engine. Default
  client IDs come from `OAuthConfig` (currently placeholders).
- Multi-module Gradle project (Kotlin, Compose, Material 3, Hilt, Room,
  DataStore, WorkManager) building to per-ABI + universal APKs.
- rclone cross-compilation pipeline for `arm64-v8a`, `armeabi-v7a`, `x86_64`
  (pinned rclone v1.69.1), packaged in-APK and run as an RC daemon.
- `RcloneEngine`: daemon lifecycle, authenticated RC JSON API client, and
  `listRemotes`/`createRemote`/`deleteRemote`/`listDir`/`sync`/`bisync`.
- Keystore-encrypted `rclone.conf` at rest, decrypted only for the daemon.
- Room schema for remotes, sync tasks, and run history; DataStore preferences.
- Storage volume enumeration with `MANAGE_EXTERNAL_STORAGE`.
- Background sync via a foreground `dataSync` WorkManager worker, periodic
  scheduling with constraints, and a boot receiver to re-register schedules.
- UI: sync task list + editor, remote management (manual config + `rclone.conf`
  import + delete), and settings (theme, dynamic color, sync defaults, battery).
- Unit tests for the RC API client, rclone engine, and sync executor.
- GitHub Actions CI (build + unit tests + lint).
