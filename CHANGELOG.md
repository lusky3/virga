# Changelog

All notable changes to this project are documented here. The format is based on
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project
adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added
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
