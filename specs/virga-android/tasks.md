# Virga — Implementation Tasks

## Phase 1: Foundation (Weeks 1-2)

### Group 1A: Project Scaffolding
- [ ] Initialize Android project with Gradle KTS, version catalog (`libs.versions.toml`)
- [ ] Configure module structure (app, core/*, feature/*, sync-worker, rclone-build)
- [ ] Set up Hilt DI in app module
- [ ] Configure build flavors (foss, play) and ABI splits (arm64-v8a, armeabi-v7a, x86_64)
- [ ] Configure AAB output for Play Store, per-ABI APKs for F-Droid/GitHub
- [ ] Set up GitHub Actions CI (build + lint + test on every PR)
- [ ] Configure R8 full mode with keep rules for rclone JNI/exec
- [ ] Set up notification channels (sync_progress, sync_complete, sync_error)
- [ ] Add `data_extraction_rules.xml` (exclude rclone.conf from backup)
- [ ] Write unit tests for build configuration (flavor-specific BuildConfig values)

### Group 1B: Rclone Build Pipeline
- [ ] Create `rclone-build/` Gradle module with cross-compilation task
- [ ] Pin rclone version (git tag) and Go version in version catalog
- [ ] Build rclone binary for arm64-v8a, armeabi-v7a, x86_64 (named `librclone.so`)
- [ ] Package binaries in `lib/<abi>/librclone.so` for APK extraction
- [ ] Verify binary executes on emulator (smoke test: `librclone.so version`)
- [ ] Set up CI cron job to check for new rclone releases weekly
- [ ] Document build prerequisites (Go version, NDK version, disk space)

### Group 1C: Core Data Layer
- [ ] Define Room database schema: `Remote`, `SyncTask`, `SyncRun`
- [ ] Implement DAOs with Flow-returning queries
- [ ] Set up DataStore for app preferences (theme, WiFi-only, bandwidth limits)
- [ ] Implement `RcloneConfigManager` with EncryptedFile (Android Keystore-backed)
- [ ] Write database migration tests
- [ ] Write unit tests for config encryption/decryption

---

## Phase 2: Core Functionality (Weeks 3-5)

### Group 2A: Rclone Engine
- [ ] Implement `RcloneEngine` interface
- [ ] Implement RC daemon lifecycle (start with random port + auth, stop, health check)
- [ ] Implement stale process detection and cleanup on startup
- [ ] Implement daemon crash monitoring (`Process.waitFor()` coroutine)
- [ ] Implement `listRemotes()` via RC API (`config/listremotes`)
- [ ] Implement `createRemote()` / `deleteRemote()` via RC API (`config/create`, `config/delete`)
- [ ] Implement `listDir()` via RC API (`operations/list`)
- [ ] Implement `sync()` with progress Flow via `/core/stats` polling
- [ ] Implement `bisync()` via RC API (`sync/bisync`)
- [ ] Handle rclone config file lifecycle (decrypt → start daemon → cleanup on stop)
- [ ] Unit tests for RC API client (MockWebServer for HTTP responses)
- [ ] Integration test: start daemon, list remotes, stop daemon (real binary on emulator)

### Group 2B: Storage Access
- [ ] Implement `StorageAccessor` interface
- [ ] MANAGE_EXTERNAL_STORAGE permission request flow with rationale UI
- [ ] Enumerate storage volumes (StorageManager API) — internal, SD card, USB
- [ ] Map volumes to filesystem paths
- [ ] Handle permission denied gracefully (show what's available without MES)
- [ ] Integration test: enumerate storage roots on emulator

### Group 2C: OAuth & Remote Configuration
- [ ] Register OAuth apps with Google, Microsoft, Dropbox, pCloud
- [ ] Implement OAuth browser flow (CustomTabs → redirect URI → token capture)
- [ ] Store default client IDs in BuildConfig (per-flavor: foss vs play)
- [ ] BYO OAuth override in advanced settings
- [ ] Remote list screen (Compose)
- [ ] Add remote flow: provider picker → config fields → OAuth → save via RC API
- [ ] Edit remote screen
- [ ] Delete remote with confirmation
- [ ] Rclone config import (file picker → validate → encrypt → store)
- [ ] Rclone config export (decrypt → share intent with security warning)
- [ ] ViewModel + state management for remote config
- [ ] UI tests for remote list and add flow

---

## Phase 3: Sync Engine (Weeks 5-8)

### Group 3A: Sync Task Management
- [ ] Sync task data model (source path, remote dest, direction, schedule, filters, bwLimit)
- [ ] Create/edit sync task UI (Compose)
- [ ] Sync direction picker: upload-only, download-only, bisync
- [ ] Bandwidth throttle setting (no limit, 1/5/10 MB/s, custom; separate WiFi/metered)
- [ ] Filter patterns (include/exclude globs)
- [ ] Schedule picker (interval: 15min/30min/1h/6h/12h/24h, or manual-only)
- [ ] Sync task list screen with status indicators (idle, syncing, error, last sync time)
- [ ] Manual "sync now" trigger
- [ ] ViewModel for sync task CRUD
- [ ] Unit tests for sync task validation logic

### Group 3B: Background Sync Execution
- [ ] WorkManager `PeriodicWorkRequest` for scheduled sync (with constraints)
- [ ] `SyncForegroundService` with `dataSync` type
- [ ] Progress notification with: progress %, file count, cancel button, speed
- [ ] Implement chunked/resumable sync (rclone handles this natively with `--retries`)
- [ ] Handle 6h FGS timeout: save state, stop gracefully, WorkManager reschedules
- [ ] Retry with exponential backoff on transient failures
- [ ] Hilt injection in Worker and Service
- [ ] Boot receiver to re-register WorkManager tasks after reboot
- [ ] Resource limits: `--buffer-size 16M`, `--transfers 4`, configurable
- [ ] Unit tests for Worker logic (mock RcloneEngine)
- [ ] Integration test: schedule task → verify WorkManager enqueues

### Group 3C: Sync State & History
- [ ] Sync run recording (start/end time, files transferred, bytes, errors)
- [ ] Sync history list screen (Compose) with filtering
- [ ] Sync log viewer (rclone verbose output captured to file)
- [ ] Error categorization (network, auth expired, storage full, permission denied, conflict)
- [ ] Conflict handling strategy: newest-wins default, log conflicts for user review
- [ ] Conflict review screen (list conflicts, allow manual resolution)
- [ ] Unit tests for conflict detection logic

---

## Phase 4: Polish (Weeks 8-10)

### Group 4A: Settings & Onboarding
- [ ] Settings screen: WiFi-only, charge-only, bandwidth limits, notification prefs, theme
- [ ] Onboarding flow: welcome → storage permission → first remote → first sync task
- [ ] Battery optimization explanation + REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
- [ ] Link to dontkillmyapp.com for OEM-specific instructions
- [ ] Play Store vs F-Droid feature messaging (SD card access availability)

### Group 4B: UX Polish
- [ ] Material 3 dynamic color theming
- [ ] Dark/light/system mode support
- [ ] File browser screen (browse remote contents)
- [ ] Pull-to-refresh on file browser and sync task list
- [ ] Empty states and error states (illustrations + actionable messages)
- [ ] Accessibility (content descriptions, minimum touch targets 48dp)
- [ ] Loading states for rclone daemon startup (1-3s)
- [ ] Localization setup (English base, strings extracted)

### Group 4C: Error Handling & Resilience
- [ ] Global error handling (sealed class hierarchy: NetworkError, AuthError, StorageError, RcloneError)
- [ ] User-facing error messages mapped from rclone exit codes
- [ ] Auth expiry detection → prompt re-authentication
- [ ] Storage full detection → pause sync, notify user
- [ ] Rclone daemon crash → auto-restart with backoff, notify if persistent

---

## Phase 5: Release (Weeks 10-12)

### Group 5A: Testing & Quality
- [ ] Unit test coverage for ViewModels (target 80%+)
- [ ] Unit test coverage for repositories and RcloneEngine
- [ ] Integration tests for full sync flow (real rclone binary, local "remote")
- [ ] UI tests for critical flows (add remote, create sync task, run sync)
- [ ] Screenshot tests for key screens (Roborazzi)
- [ ] WorkManager integration tests
- [ ] Security review: config encryption, daemon auth, no token leaks in logs

### Group 5B: Performance
- [ ] Generate baseline profiles for Compose
- [ ] Profile startup time (target <2s cold start including daemon)
- [ ] Profile memory usage during sync (verify buffer limits work)
- [ ] Verify R8 shrinking doesn't break rclone exec or serialization

### Group 5C: Distribution
- [ ] F-Droid metadata (fastlane structure: descriptions, screenshots, changelogs)
- [ ] F-Droid reproducible build verification (pinned tools, no timestamps)
- [ ] GitHub Release automation (tag → build → publish per-ABI APKs)
- [ ] Play Store listing (screenshots, description, privacy policy)
- [ ] Play Store MANAGE_EXTERNAL_STORAGE declaration form
- [ ] Play Asset Delivery setup (if base APK exceeds 150MB)
- [ ] README with: screenshots, install instructions, build instructions, BYO OAuth guide
- [ ] CONTRIBUTING.md
- [ ] CHANGELOG.md
- [ ] Privacy policy (required for Play Store)

---

## Deferred to v1.1+

- [ ] rclone crypt overlay (encrypted remotes) — significant UX complexity
- [ ] Tasker/automation integration (intent-based sync triggers)
- [ ] Selective sync (per-folder include/exclude with UI tree picker)
- [ ] Transfer queue with priority ordering
- [ ] Wear OS companion (sync status glance)
- [ ] Large screen / foldable adaptive layouts
- [ ] App hibernation handling (permission re-grant flow after Android revokes)
