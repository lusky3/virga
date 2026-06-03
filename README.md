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
| `core:data` | Repositories tying the data sources together |
| `sync-worker` | WorkManager worker, foreground service, scheduler, boot receiver |
| `feature:sync` | Sync task list + editor, sync history, conflict resolution |
| `feature:remotes` | Remote management (OAuth + manual config + config import) |
| `feature:explorer` | File browser for remote contents |
| `feature:settings` | App settings |

The full design rationale lives in [`specs/virga-android/spec.md`](specs/virga-android/spec.md).

## Building

Prerequisites:

- JDK 21
- Android SDK (platform 36, build-tools 36) + NDK `27.2.12479018`
- Go 1.25+ (only needed if you don't have prebuilt rclone binaries in
  `core/rclone/src/main/jniLibs/`)

```bash
# Build the FOSS debug APKs (per-ABI + universal). The :rclone-build module
# cross-compiles librclone.so on demand if the binaries are missing; if they
# are already on disk (e.g. checked in for contributor convenience) the
# Exec task is a no-op.
./gradlew assembleFossDebug

# Force a rebuild of the rclone binaries:
rm -rf core/rclone/src/main/jniLibs && ./gradlew :rclone-build:buildRcloneBinaries

# Tests / lint
./gradlew test :app:lintFossDebug

# Instrumented + end-to-end sync tests (require a running emulator/device)
./gradlew :app:connectedFossDebugAndroidTest
```

Outputs land in `app/build/outputs/apk/foss/debug/`. Release APKs go to
`app/build/outputs/apk/foss/release/` and are unsigned by default — see the
[Release workflow](#release) for the keystore-signing CI path.

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

Virga ships an in-app OAuth 2.0 + PKCE flow for **Google Drive, OneDrive, and
Dropbox**: tap a provider chip in the Add-remote dialog → Custom Tabs opens the
provider's sign-in page → the redirect comes back to `OAuthRedirectActivity` →
the code is exchanged for tokens and a remote is created via rclone's RC API.
State is validated, PKCE binds the code to a verifier kept in-process, and no
client secret ships in the APK.

The redirect URI differs per provider because Google enforces a reverse-DNS
scheme on Android OAuth clients:

| Provider | Redirect URI |
|---|---|
| Google Drive | `com.googleusercontent.apps.<client-id-prefix>:/oauth2redirect` (auto-derived) |
| OneDrive | `virga://oauth/callback` |
| Dropbox | `virga://oauth/callback` |

### Registering your own client IDs

OAuth client IDs are public-by-design for PKCE mobile clients but they bind
the redirect to your specific developer account. They are **not** checked
into git. To provide your own:

1. Register a client with the provider:
   - **Google:** Cloud Console → APIs & Services → Credentials → Create
     credentials → **OAuth client ID** → **Android**. Package name:
     `app.lusk.virga` (or `app.lusk.virga.debug` for debug builds). SHA-1:
     `keytool -list -v -keystore <your.jks> -alias <alias>`. Under
     **Advanced settings**, **enable Custom URI scheme**. Enable the **Google
     Drive API** for the project, and add your test Google account under
     **OAuth consent screen → Test users**.
   - **Microsoft:** Azure Portal → App registrations → New registration →
     Mobile/Desktop → register `virga://oauth/callback` as the redirect URI.
   - **Dropbox:** developers.dropbox.com → My apps → Create app → Scoped
     access → Full Dropbox → add `virga://oauth/callback` redirect.

2. Add the client IDs to `local.properties` (already gitignored):

   ```properties
   oauthClientId.gdrive=123-abc.apps.googleusercontent.com
   oauthClientId.onedrive=…
   oauthClientId.dropbox=…
   ```

   CI can use env vars instead: `VIRGA_OAUTH_CLIENT_ID_GDRIVE`,
   `VIRGA_OAUTH_CLIENT_ID_ONEDRIVE`, `VIRGA_OAUTH_CLIENT_ID_DROPBOX`.

3. Rebuild. The matching Google reverse-DNS redirect-URI scheme is wired into
   `OAuthRedirectActivity` via a manifest placeholder at build time.

### Alternatives if you don't want to register a client

- **Import an `rclone.conf`** authorized on desktop — Remotes screen →
  **Import**. Works for any backend, including pre-authorized OAuth remotes.
- **Manual config** for credential-based backends (S3, WebDAV, SFTP, …):
  Remotes → **+** → fill in type and `key=value` parameters.

## Release

Tagging a commit with `vX.Y.Z` (or running the `Release` workflow manually)
builds the four FOSS release APKs (`arm64-v8a`, `armeabi-v7a`, `x86_64`,
universal), signs them with a keystore stored in repo secrets, generates a
`SHA256SUMS.txt`, and publishes them as a GitHub Release with auto-generated
notes. See [`.github/workflows/release.yml`](.github/workflows/release.yml).

Required secrets:

| Secret | Purpose |
|---|---|
| `RELEASE_KEYSTORE_BASE64` | `base64 -w0 your.jks` |
| `RELEASE_KEYSTORE_PASSWORD` | keystore password |
| `RELEASE_KEY_ALIAS` | key alias inside the store |
| `RELEASE_KEY_PASSWORD` | key password |
| `OAUTH_CLIENT_ID_{GDRIVE,ONEDRIVE,DROPBOX}` | OAuth client IDs (optional; empty values disable the corresponding OAuth chips) |

If the keystore secret is unset, the workflow still builds, just skips signing.
Release tags must match `vMAJOR.MINOR(.PATCH)(-suffix)?` — the workflow refuses
anything else as a defense against shell injection via a malicious tag.

Per-release notes can be written ahead of time at `docs/release/<tag>.md`; if
present they become the GitHub Release body (otherwise the body is auto-
generated from commits since the previous tag).

## Project status

Implemented and verified:

- ✅ Multi-module scaffold → installable APKs (all ABIs)
- ✅ rclone cross-compile pipeline as a Gradle module (`:rclone-build`)
- ✅ Data layer: Room, DataStore, Keystore-encrypted rclone.conf
- ✅ RcloneEngine: daemon lifecycle, RC API client, sync/bisync/list/config
- ✅ Storage access (MANAGE_EXTERNAL_STORAGE + volume enumeration)
- ✅ Background sync: WorkManager + foreground `dataSync` worker + scheduler + boot receiver
- ✅ UI: sync task list/editor, remotes (OAuth + manual + import + delete),
  file browser, sync history, conflict resolution, settings, 4-step onboarding
- ✅ OAuth 2.0 + PKCE browser flow (Custom Tabs + provider-specific redirect)
  for Google Drive, OneDrive, and Dropbox — see [OAuth / BYO credentials](#oauth--byo-credentials)
- ✅ Unit tests across engine, RC client, OAuth, ViewModels, scheduler, etc.
- ✅ Instrumented tests including an **end-to-end real-rclone sync** that
  proves the bundled binary, daemon, RC API, and sync engine all work on device
- ✅ R8-minified release build; cold-start ~2 s
- ✅ CI: build + test on every PR; tag-driven release workflow
- ✅ Distribution flavors (`foss` allows BYO OAuth + advertises SD-card sync;
  `play` softens SD-card messaging)

Outstanding (mostly distribution work):

- ⏳ Register OAuth clients for OneDrive and Dropbox (Google Drive is done)
- ⏳ Play Store / F-Droid listing assets (screenshots, descriptions)
- ⏳ Roborazzi screenshot tests; baseline profiles for further startup wins

See [`specs/virga-android/tasks.md`](specs/virga-android/tasks.md) for the full task breakdown.

## License

Apache 2.0 — see [LICENSE](LICENSE). Clean-room rewrite; no GPL code copied.
rclone is distributed under its own MIT license.
