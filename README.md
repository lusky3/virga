# Virga

A modern Android app that syncs local storage — including the microSD root — to
cloud providers via [rclone](https://rclone.org). One-way and two-way (bisync)
sync, scheduled in the background, with bandwidth limits and per-task control.

**Package:** `app.lusk.virga` · **License:** Apache 2.0 · **minSdk:** 26 (Android 8.0)

> Status: early development. The engine, data layer, background sync, and core
> UI are implemented and build to an installable APK. See
> [Project status](#project-status) for what is and isn't done yet.

## How it works

Virga ships a per-ABI rclone binary inside the APK (as `lib/<abi>/librclone.so`,
which Android extracts as an executable). On demand it launches rclone in
remote-control daemon mode bound to a random localhost port with random
per-session credentials, and drives all operations over rclone's authenticated
JSON RC API. rclone owns per-file delta detection; Virga owns task definitions,
scheduling, and run history (in an encrypted-config + Room setup).

```
WorkManager (periodic) ──► SyncWorker (foreground dataSync) ──► rclone RC daemon
                                   │                                  ▲
                                   └── progress ──► notification      └── localhost HTTP+JSON
```

## Architecture

Multi-module, Kotlin-only, Jetpack Compose + Material 3, Hilt DI, Room,
DataStore, WorkManager, Coroutines/Flow.

| Module | Responsibility |
|--------|----------------|
| `app` | Application, single Activity, Compose navigation, DI aggregation |
| `core:common` | Shared domain models, errors, dispatchers |
| `core:database` | Room entities/DAOs (remotes, tasks, runs) |
| `core:datastore` | App preferences (DataStore) |
| `core:rclone` | rclone binary mgmt, RC daemon lifecycle, RC API client, engine, encrypted config |
| `core:storage` | Storage volume enumeration, MANAGE_EXTERNAL_STORAGE |
| `core:data` | Repositories tying the data sources together |
| `sync-worker` | WorkManager worker, foreground service, scheduler, boot receiver |
| `feature:sync` | Sync task list + editor |
| `feature:remotes` | Remote management (manual config + config import) |
| `feature:settings` | App settings |

The full design rationale lives in [`specs/virga-android/spec.md`](specs/virga-android/spec.md).

## Building

Prerequisites:

- JDK 21
- Android SDK (platform 36, build-tools 36) + NDK `27.2.12479018`
- Go 1.25+ (to cross-compile rclone)

```bash
# 1. Cross-compile the rclone binaries for all ABIs into core/rclone/src/main/jniLibs
./scripts/build-rclone.sh            # or: ./gradlew :core:rclone:buildRclone

# 2. Build the FOSS debug APKs (per-ABI + universal)
./gradlew assembleFossDebug

# 3. Tests / lint
./gradlew testFossDebugUnitTest lintFossDebug
```

Outputs land in `app/build/outputs/apk/foss/debug/`. The rclone binaries are
build artifacts and are **not** checked in (see `.gitignore`); CI and the build
script regenerate them.

### Flavors & distribution

- `foss` — F-Droid and GitHub releases, no proprietary dependencies.
- `play` — Google Play.

ABI splits produce `arm64-v8a`, `armeabi-v7a`, and `x86_64` APKs plus a
universal APK for sideloading. The Play build uses an AAB so Google delivers a
single matching ABI per device.

## Storage permissions

rclone needs real filesystem paths, which `content://` SAF URIs cannot provide.
SD-card sync therefore requires `MANAGE_EXTERNAL_STORAGE`:

- **F-Droid / GitHub:** granted directly, no restrictions.
- **Play Store:** requires the declaration form; if rejected, the Play build is
  limited to internal storage and SD-card sync needs the F-Droid/GitHub APK.

## OAuth / BYO credentials

Virga is designed to ship default OAuth client IDs for major providers (public
by design for mobile apps), with a BYO override for power users. Until those are
registered, configure remotes by:

- **Importing an existing `rclone.conf`** (e.g. authorized on desktop) — works
  for any backend including pre-authorized OAuth remotes, or
- **Manual config** for credential-based backends (S3, WebDAV, SFTP, …).

## Project status

Implemented and building:

- ✅ Multi-module scaffold → installable APKs (all ABIs)
- ✅ rclone cross-compile pipeline (3 ABIs) + packaging
- ✅ Data layer: Room, DataStore, Keystore-encrypted rclone.conf
- ✅ RcloneEngine: daemon lifecycle, RC API client, sync/bisync/list/config
- ✅ Storage access (MANAGE_EXTERNAL_STORAGE + volume enumeration)
- ✅ Background sync: WorkManager + foreground `dataSync` worker + scheduler + boot receiver
- ✅ UI: sync task list/editor, remotes (add/import/delete), settings
- ✅ Unit tests for engine, RC client, executor

Not yet done (tracked for follow-up):

- ⏳ In-app OAuth browser flow (CustomTabs token capture)
- ⏳ Sync history & conflict-resolution screens (data layer exists)
- ⏳ Onboarding flow & file browser
- ⏳ Instrumented/UI/screenshot tests; baseline profiles
- ⏳ Play/F-Droid store listings, signing, release automation

See [`specs/virga-android/tasks.md`](specs/virga-android/tasks.md) for the full task breakdown.

## License

Apache 2.0 — see [LICENSE](LICENSE). Clean-room rewrite; no GPL code copied.
rclone is distributed under its own MIT license.
