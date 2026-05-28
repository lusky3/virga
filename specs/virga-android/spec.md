# Virga — Complete Rewrite Spec

**App Name:** Virga
**Package ID:** `app.lusk.virga`
**License:** Apache 2.0
**Date:** 2026-05-27
**Status:** Draft
**Goal:** Build from scratch a modern, maintainable Android app that syncs local storage (including microSD root) to cloud providers via rclone. Fully functional with bisync support. Distributed via Play Store, GitHub, and F-Droid. Ships default OAuth client IDs for major providers; advanced users can override with their own credentials (BYO).

---

## 1. Technology Stack

### Core Language & Compiler

| Component | Version | Notes |
|-----------|---------|-------|
| Kotlin | 2.1.20 | Latest stable; Compose compiler bundled since Kotlin 2.0 |
| Kotlin Coroutines | 1.10.x | Structured concurrency, Flow |
| KSP | 2.1.20-1.0.x | Replaces KAPT entirely — faster annotation processing |

### UI Layer

| Component | Version | Notes |
|-----------|---------|-------|
| Compose BOM | 2025.04.01 (or latest 2025.05.xx) | Single BOM pins all Compose artifacts |
| Material 3 | via BOM | Fully stable, dynamic color, adaptive layouts |
| Compose Navigation | 2.9.x (Navigation 3 if stable) | Type-safe routes with `@Serializable` |
| Accompanist | Deprecated — use platform APIs | Most features merged into Compose core |

**Decision: Jetpack Compose over Views**
- 60-70% less UI code than XML + ViewBinding
- Declarative state management eliminates entire classes of bugs
- Material 3 is Compose-first; View-based M3 lags behind
- The old codebase's UI is the "unmaintainable" part — Compose fixes this structurally

### Architecture & DI

| Component | Version | Notes |
|-----------|---------|-------|
| Hilt | 2.54+ | Compile-time DI, Google-maintained |
| ViewModel | 2.9.x (via lifecycle BOM) | Survives config changes |
| StateFlow | via Coroutines | Observable state for ViewModels |
| DataStore | 1.1.x | Replaces SharedPreferences |
| Room | 2.7.x | SQLite abstraction with KSP support |

**Decision: Hilt over Koin**
- Compile-time verification catches DI errors at build time, not runtime
- Google's official recommendation for Android
- Better integration with ViewModel, WorkManager, Navigation
- Koin is simpler but runtime-only — errors surface in production

**Decision: StateFlow over LiveData**
- LiveData is in maintenance mode
- StateFlow is Kotlin-native, works in non-UI layers
- Better testing story (Turbine library)
- Compose collects Flow natively via `collectAsStateWithLifecycle()`

### Data Layer

| Component | Version | Notes |
|-----------|---------|-------|
| Room | 2.7.x | Task definitions, sync history, remote configs |
| DataStore Preferences | 1.1.x | App settings, UI preferences |
| OkHttp | 4.12.x or 5.x | HTTP client for rclone RC API |
| Kotlinx Serialization | 1.7.x | JSON parsing (replaces Jackson) |

**Decision: Kotlinx Serialization over Jackson/Gson/Moshi**
- Kotlin-native, multiplatform-ready
- Compile-time code generation (no reflection)
- Smaller binary size
- First-class support for Kotlin data classes, sealed classes, default values

### Background Work

| Component | Version | Notes |
|-----------|---------|-------|
| WorkManager | 2.10.x | Scheduled sync, constraints (WiFi, charging) |
| Foreground Service | Platform API | Long-running active syncs |

### Testing

| Component | Version | Notes |
|-----------|---------|-------|
| JUnit 5 | 5.11.x | Modern test framework |
| Turbine | 1.2.x | Flow testing |
| MockK | 1.13.x | Kotlin-first mocking |
| Compose UI Test | via BOM | Semantic tree testing |
| Robolectric | 4.14.x | Local JVM Android tests |
| Roborazzi | 1.30.x | Screenshot testing |

**Decision: MockK over Mockito-Kotlin**
- Native Kotlin support (coroutines, extension functions, top-level functions)
- No need for `open` classes or interfaces-for-testing
- Cleaner DSL: `every { }`, `coEvery { }`, `verify { }`

### Build Tooling

| Component | Version | Notes |
|-----------|---------|-------|
| AGP | 8.8.x (or 8.9.x) | Latest stable |
| Gradle | 8.12.x | Compatible with AGP 8.8+ |
| Gradle KTS | Standard | Type-safe build scripts |
| Version Catalog | `libs.versions.toml` | Single source of truth for deps |
| R8 | Bundled with AGP | Full mode (not compat) |

---

## 2. Architecture Design

### Layer Diagram

```
┌─────────────────────────────────────────────────┐
│                    UI Layer                       │
│  Compose Screens → ViewModels → UI State         │
├─────────────────────────────────────────────────┤
│                 Domain Layer                      │
│  Use Cases (optional) → Repository Interfaces    │
├─────────────────────────────────────────────────┤
│                  Data Layer                       │
│  Repositories → DataSources (Room, DataStore,    │
│                  RcloneService, StorageAccess)    │
├─────────────────────────────────────────────────┤
│               Platform Layer                     │
│  WorkManager Workers, ForegroundService,          │
│  SAF Integration, Rclone Binary/AAR              │
└─────────────────────────────────────────────────┘
```

### Module Structure

```
cloudsync/
├── app/                          # Application module, DI wiring, navigation
├── core/
│   ├── common/                   # Shared utilities, extensions
│   ├── data/                     # Repository implementations
│   ├── database/                 # Room entities, DAOs, migrations
│   ├── datastore/                # DataStore preferences
│   ├── rclone/                   # Rclone binary management, RC API client
│   └── storage/                  # SAF helpers, permission management
├── feature/
│   ├── remotes/                  # Remote configuration UI
│   ├── explorer/                 # File browser UI
│   ├── sync/                     # Sync task management UI
│   └── settings/                 # App settings UI
├── sync-worker/                  # WorkManager workers, ForegroundService
└── rclone-build/                 # Gradle module for building rclone binary
```

### Rclone Integration Strategy

**Approach: Standalone rclone binary executed as `rclone rcd` (RC daemon mode)**

The app ships a pre-compiled rclone binary per ABI, packaged in the APK's `lib/` directory (named `librclone.so` to satisfy Android's native library extraction, but it's a standalone ELF executable). On startup, the app exec's it as a child process in RC daemon mode and communicates via authenticated HTTP JSON API on localhost.

**Binary packaging trick:** Android only extracts files from `lib/<abi>/` that match `lib*.so`. We name the binary `librclone.so` — Android extracts it, and we exec it directly. This is the same approach Round-Sync uses.

**Daemon lifecycle:**
```
App starts → exec `librclone.so rcd --rc-addr=127.0.0.1:0 --rc-user=<random> --rc-pass=<random>`
           → rclone prints actual bound port to stdout
           → app connects via OkHttp to http://127.0.0.1:<port>
           → all operations via RC JSON API
           → on app/service stop → send SIGTERM to child process
```

**Security:** Each daemon session uses:
- Random port binding (`--rc-addr=127.0.0.1:0`) — OS assigns available port
- Random per-session credentials (`--rc-user`/`--rc-pass`) — prevents other apps from accessing the daemon
- Localhost only — no network exposure

**Why RC daemon over raw CLI exec:**
- Structured JSON responses instead of parsing stdout text
- Progress callbacks via `/core/stats` endpoint (polled or server-sent events)
- Concurrent operations without spawning multiple processes
- rclone RC is stable and well-documented
- Single long-lived process is more efficient than repeated exec

**Why NOT gomobile/librclone AAR (in-process JNI):**
- gomobile produces massive AAR files with CGo complications
- In-process crashes take down the entire app
- Harder to debug (no separate process logs)
- Can't easily kill/restart a stuck operation
- Binary approach is proven (Round-Sync, RCX both use it successfully)

**Daemon crash/death handling:**
- Monitor child process with `Process.waitFor()` in a coroutine
- If daemon dies unexpectedly: log error, notify user, attempt restart with backoff
- If app process dies: orphaned rclone process is cleaned up by Android (child process inherits parent's cgroup)
- Stale process detection: on startup, check for leftover rclone processes and kill them

**Startup latency:** rclone daemon takes 1-3 seconds to initialize on mobile hardware. UI shows loading state during this window. Daemon is started eagerly when the app opens or when a sync Worker fires.

```kotlin
// Core interface
interface RcloneEngine {
    suspend fun startDaemon(): RcloneDaemon
    suspend fun stopDaemon()
    suspend fun isDaemonHealthy(): Boolean
    suspend fun listRemotes(): List<Remote>
    suspend fun sync(source: String, dest: String, options: SyncOptions): Flow<SyncProgress>
    suspend fun bisync(path1: String, path2: String, options: BisyncOptions): Flow<SyncProgress>
    suspend fun copyFile(source: String, dest: String): Flow<TransferProgress>
    suspend fun listDir(remote: String, path: String): List<FileItem>
    suspend fun createRemote(name: String, type: String, params: Map<String, String>): Result<Unit>
    suspend fun deleteRemote(name: String): Result<Unit>
    suspend fun getConfig(): RcloneConfig
    suspend fun importConfig(confContent: String): Result<Unit>
}

data class RcloneDaemon(
    val process: Process,
    val port: Int,
    val user: String,
    val pass: String,
)

data class SyncOptions(
    val direction: SyncDirection,  // UPLOAD, DOWNLOAD, BISYNC
    val bwLimit: String? = null,   // e.g. "1M" for 1MB/s
    val transfers: Int = 4,
    val checkers: Int = 8,
    val bufferSize: String = "16M",
    val filters: List<String> = emptyList(),
    val dryRun: Boolean = false,
)
```

### Storage Access Architecture

**The fundamental constraint:** rclone operates on filesystem paths (`/storage/XXXX-XXXX/...`). It cannot work with Android's `content://` URIs from SAF. This means:

- **MANAGE_EXTERNAL_STORAGE is required** for rclone to access SD card files directly.
- **SAF cannot be a transparent fallback** — it provides URIs, not paths.

**Strategy: MANAGE_EXTERNAL_STORAGE as the only viable path for SD card sync**

For F-Droid and GitHub releases: request MANAGE_EXTERNAL_STORAGE directly. No restrictions.

For Play Store: submit the declaration form. "File sync/backup utility" is an accepted use case. If rejected, the Play Store version can only sync internal storage paths that are accessible without MES (e.g., app-specific directories, Downloads, etc.) — SD card sync would be F-Droid/GitHub only.

**SAF role (limited):** SAF is used ONLY for:
1. Letting the user pick which SD card/storage root to sync (discovery UI)
2. Verifying the SD card is mounted and accessible
3. NOT for actual file I/O during sync — rclone handles that via filesystem paths

**Fallback for Play Store rejection (contingency):**
If Play Store rejects MES, the Play Store build offers reduced functionality:
- Sync only from internal storage paths rclone can access (`/storage/emulated/0/`)
- SD card sync requires sideloading the FOSS APK from F-Droid/GitHub
- Clear messaging in-app: "For SD card sync, install from F-Droid"

**No WebDAV bridge:** Round-Sync's `safdav` module (SAF→WebDAV→rclone) is clever but fragile, slow, and adds a failure mode. We don't replicate it.

```kotlin
interface StorageAccessor {
    // Enumerate available storage volumes
    suspend fun getStorageRoots(): List<StorageRoot>
    // Check if we have filesystem-level access (MES granted)
    fun hasFullAccess(): Boolean
    // Get the filesystem path for a storage root (only works with MES)
    fun getFilesystemPath(root: StorageRoot): String?
}

data class StorageRoot(
    val id: String,
    val displayName: String,       // "SD Card", "Internal Storage"
    val type: StorageType,         // INTERNAL, SD_CARD, USB
    val filesystemPath: String?,   // e.g. "/storage/ABCD-1234" — null if no MES access
    val totalBytes: Long,
    val availableBytes: Long,
)

enum class StorageType { INTERNAL, SD_CARD, USB }
```

**File change detection:** Android provides no reliable filesystem change notifications for SD cards. `FileObserver` is unreliable on external storage. `ContentObserver` only catches MediaStore-indexed changes. **Sync triggers are polling-based only:**
- Scheduled via WorkManager (user-configured interval, minimum 15 min)
- Manual trigger by user
- On-boot trigger (optional)

---

## 3. Key Design Decisions

### 3.1 Single Activity + Compose Navigation

**Choice:** One `MainActivity`, all screens are Compose destinations.

**Rationale:**
- Eliminates fragment transaction bugs (the #1 crash source in old Android apps)
- Type-safe navigation with compile-time route verification
- Shared element transitions work natively in Compose
- Deep linking is declarative

**Trade-off:** Slightly more complex back stack management for nested flows (remote config wizard).

### 3.2 Unidirectional Data Flow (UDF)

```
User Action → ViewModel → Repository → DataSource
                ↓
         UI State (StateFlow)
                ↓
         Compose UI recomposes
```

**Rationale:** Predictable state, easy to test, easy to debug. The old codebase had state scattered across Activities, Services, BroadcastReceivers, and static fields.

### 3.3 WorkManager for Scheduled Sync + ForegroundService for Active Sync

**Choice:**
- `PeriodicWorkRequest` for scheduled background syncs (minimum 15 min interval)
- `ForegroundService` (type `dataSync`) for user-initiated long-running syncs

**Rationale:**
- WorkManager respects Doze, battery optimization, and network constraints automatically
- ForegroundService with persistent notification keeps the process alive during active sync
- Android 14+ requires declaring foreground service type in manifest
- Android 15 imposes 6-hour timeout on `dataSync` FGS — design sync operations to be resumable/chunked

**Trade-off:** 15-minute minimum for WorkManager periodic means we can't do "every 5 minutes" sync. For near-real-time, we'd need a persistent FGS (battery-hostile) or content observers.

### 3.4 Storage Permission Strategy

**Choice:** `MANAGE_EXTERNAL_STORAGE` is required for SD card sync. No SAF fallback for rclone operations.

**Rationale:**
- rclone requires filesystem paths — SAF `content://` URIs are incompatible
- F-Droid has no restrictions on MES
- Play Store accepts MES for "file management/sync utilities" with a declaration form
- If Play Store rejects: Play version syncs internal storage only; SD card sync is F-Droid/GitHub exclusive
- Clear in-app messaging guides users to the right distribution channel

**Trade-off:** Play Store rejection risk. Mitigated by F-Droid as primary distribution and honest feature gating.

### 3.5 OAuth Strategy

**Choice:** Ship default OAuth client IDs for Google Drive, OneDrive, Dropbox, pCloud. Allow BYO override in advanced settings.

**Rationale:**
- BYO-only kills adoption — creating OAuth apps requires developer-level knowledge
- Google shows "unverified app" warnings for user-created OAuth projects
- rclone itself ships default client IDs for the same reason
- Default IDs registered under the Virga project, verified with each provider
- BYO override for power users who want their own rate limits or enterprise tenants
- Client IDs stored in BuildConfig (not secrets — OAuth client IDs are public by design for mobile apps)

**Provider registration needed before launch:**
- Google Cloud Console → OAuth 2.0 Client ID (Android type)
- Microsoft Azure → App Registration (mobile/desktop)
- Dropbox App Console → OAuth 2 app
- pCloud → OAuth app registration

### 3.6 Kotlin-first, Zero Java

**Choice:** 100% Kotlin, no Java files.

**Rationale:**
- Coroutines for async (no callbacks, no RxJava)
- Sealed classes for state modeling
- Extension functions for clean APIs
- Null safety eliminates NPE crashes
- The old codebase is majority Java — this is a rewrite, not a migration

### 3.7 Offline-first with Sync Metadata

**Choice:** Room database stores sync task definitions, run history, and high-level state. Rclone handles per-file state internally.

**Rationale:**
- rclone already tracks file modification times, checksums, and sync state via its own mechanisms (`--update`, `--checksum`, `--use-server-modtime`)
- Duplicating per-file state in Room for 100K+ files would cause DB bloat and slow queries
- Room stores: task configs, last run timestamp, run results (success/fail/files transferred/errors), user-facing conflict decisions
- rclone handles: which files need syncing, delta detection, transfer state

**What Room tracks:**
- `SyncTask` — source path, remote destination, schedule, options
- `SyncRun` — start time, end time, status, files transferred, bytes transferred, errors
- `Remote` — cached remote metadata (name, type, display info)
- NOT per-file sync state (rclone owns this)

### 3.8 Rclone Config Security

**Choice:** Encrypt `rclone.conf` at rest using Android Keystore-backed encryption.

**Rationale:**
- `rclone.conf` stores OAuth tokens and credentials in plaintext by default
- App sandbox provides baseline protection, but doesn't protect against:
  - Rooted devices
  - ADB backup extraction
  - Device theft with USB debugging enabled
- Use `EncryptedFile` (from `androidx.security.crypto`) backed by Android Keystore
- Decrypt to a temporary file only when starting the rclone daemon, delete after daemon stops
- Alternative: use rclone's built-in `--config-pass` with a Keystore-derived password

```kotlin
// Config lifecycle
class RcloneConfigManager @Inject constructor(
    private val encryptedFileFactory: EncryptedFileFactory,
    private val context: Context,
) {
    private val encryptedConfPath = File(context.filesDir, "rclone.conf.enc")
    private val tempConfPath = File(context.noBackupFilesDir, "rclone.conf")

    suspend fun decryptForDaemon(): File {
        // Decrypt to noBackupFilesDir (excluded from Android backup)
        encryptedFileFactory.decrypt(encryptedConfPath, tempConfPath)
        return tempConfPath
    }

    suspend fun cleanupAfterDaemon() {
        tempConfPath.delete()
    }
}
```

---

## 4. Android Platform Considerations

### Target API Levels

| Level | Value | Rationale |
|-------|-------|-----------|
| compileSdk | 36 | Latest platform APIs available |
| targetSdk | 35 | Google Play requires targetSdk ≥ 34 for new apps in 2025; 35 for updates by Aug 2025 |
| minSdk | 26 (Android 8.0) | Drops ~2% of devices; gains: native Java 8 desugaring, notification channels, background limits already handled |

**Why not minSdk 23 (like Round-Sync)?**
- Android 6-7 is <3% of active devices in 2026
- minSdk 26 eliminates need for `android-retrostreams` and `android-retrofuture` (Java 8 APIs native)
- Notification channels are mandatory anyway
- Simplifies background work code significantly

### Foreground Service Configuration

```xml
<service
    android:name=".sync.SyncForegroundService"
    android:foregroundServiceType="dataSync"
    android:exported="false" />
```

**Android 14+ requirements:**
- Must declare `foregroundServiceType` in manifest
- Must request `FOREGROUND_SERVICE_DATA_SYNC` permission
- User can stop FGS from notification

**Android 15 6-hour timeout:**
- `dataSync` FGS is terminated after 6 hours
- Mitigation: Design sync operations as resumable chunks
- Track progress in Room DB; WorkManager restarts if interrupted
- For very large syncs (>6h), chain multiple FGS sessions with WorkManager coordination

### Background Execution Strategy

```
┌─────────────────────────────────────────────┐
│           Sync Execution Pipeline            │
├─────────────────────────────────────────────┤
│ 1. WorkManager fires PeriodicWorkRequest     │
│ 2. Worker checks: pending sync tasks?        │
│ 3. If yes → promote to ForegroundService     │
│ 4. FGS starts rclone RC daemon               │
│ 5. Execute sync operations via RC API        │
│ 6. Track progress in Room DB                 │
│ 7. On completion/timeout → stop FGS          │
│ 8. Report result back to WorkManager         │
└─────────────────────────────────────────────┘
```

### Battery Optimization

- Request `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` during onboarding (with explanation)
- Use WorkManager constraints: `NetworkType.CONNECTED`, `BatteryNotLow`, `StorageNotLow`
- Respect user's "WiFi only" preference via `NetworkType.UNMETERED`
- Implement exponential backoff on repeated failures
- Show clear notification during sync with progress + cancel button

### Permissions Manifest

```xml
<!-- Storage -->
<uses-permission android:name="android.permission.MANAGE_EXTERNAL_STORAGE"
    tools:ignore="ScopedStorage" />
<uses-permission android:name="android.permission.READ_EXTERNAL_STORAGE"
    android:maxSdkVersion="32" />

<!-- Network -->
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />

<!-- Background work -->
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_DATA_SYNC" />
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
<uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />
<uses-permission android:name="android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS" />
<uses-permission android:name="android.permission.WAKE_LOCK" />
```

**Note:** No exact alarm permissions needed. WorkManager handles scheduling without them. `SCHEDULE_EXACT_ALARM` / `USE_EXACT_ALARM` are unnecessary overhead and trigger additional Play Store scrutiny.

### Backup & Data Extraction Rules

```xml
<!-- AndroidManifest.xml -->
<application
    android:dataExtractionRules="@xml/data_extraction_rules"
    android:fullBackupContent="@xml/backup_rules">
```

```xml
<!-- res/xml/data_extraction_rules.xml (Android 12+) -->
<data-extraction-rules>
    <cloud-backup>
        <exclude domain="root" path="rclone.conf" />
        <exclude domain="file" path="rclone.conf.enc" />
        <include domain="database" path="virga.db" />
        <include domain="sharedpref" path="." />
    </cloud-backup>
</data-extraction-rules>
```

Exclude rclone config (contains tokens) from Android backup. Include task definitions and preferences.

---

## 5. Build & Distribution

### CI/CD Pipeline (GitHub Actions)

```yaml
# .github/workflows/build.yml
jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          java-version: '21'
          distribution: 'temurin'
      - name: Build rclone
        run: ./scripts/build-rclone.sh
      - name: Build APK
        run: ./gradlew assembleFossRelease
      - name: Run tests
        run: ./gradlew testFossReleaseUnitTest
      - name: Lint
        run: ./gradlew lintFossRelease
      - name: Upload artifacts
        uses: actions/upload-artifact@v4
        with:
          name: apks
          path: app/build/outputs/apk/foss/release/*.apk
```

### Build Flavors

```kotlin
// app/build.gradle.kts
flavorDimensions += "distribution"
productFlavors {
    create("foss") {
        dimension = "distribution"
        // No proprietary dependencies
        // F-Droid and GitHub releases
    }
    create("play") {
        dimension = "distribution"
        // Could include Play Services for OAuth if needed
        // Google Play distribution
    }
}
```

### ABI Splits & Play Store Size Limits

**Problem:** The rclone binary is ~50-70MB per ABI. Play Store has a 150MB AAB limit.

**Solution:** Use Android App Bundle (AAB) with ABI splits for Play Store. Google Play delivers only the matching ABI to each device, so users download ~70MB (app + one rclone binary), not the universal package.

For F-Droid/GitHub: per-ABI APKs (each ~70MB) plus a universal APK for sideloading.

If the per-ABI AAB still exceeds 150MB after R8 shrinking:
- Use Play Asset Delivery (install-time asset pack) to deliver the rclone binary separately
- The binary goes in an asset pack, downloaded at install time (transparent to user)
- This raises the effective limit to 150MB (base) + 1GB (asset packs)

```kotlin
splits {
    abi {
        isEnable = true
        reset()
        include("arm64-v8a", "armeabi-v7a", "x86_64")
        // x86 (32-bit) dropped — no real devices ship x86-only in 2026
        isUniversalApk = true  // For GitHub/F-Droid only
    }
}
```

**Play Store build:** AAB format (mandatory for Play Store since 2021). Google handles ABI delivery.
**F-Droid/GitHub build:** Per-ABI APKs with versionCode suffix per ABI.

### F-Droid Compatibility

Requirements for F-Droid reproducible builds:
- No proprietary dependencies in `foss` flavor
- Pinned tool versions (Gradle, JDK, NDK, Go)
- No timestamps in build output
- Deterministic rclone build (pinned Go version, pinned rclone tag)
- `fdroid` metadata in `fastlane/` or `metadata/` directory
- Build recipe in `.fdroid.yml` or F-Droid server-side recipe

### Signing Strategy

- **F-Droid/GitHub:** Self-signed key, stored in CI secrets
- **Play Store:** Play App Signing (Google manages upload key rotation)
- Separate `applicationId` per flavor if needed for side-by-side install

---

## 6. Migration Strategy

### What Can Be Reused from Round-Sync

| Component | Reuse? | Notes |
|-----------|--------|-------|
| rclone build scripts | ✅ Reference | Adapt `rclone/` module's Makefile/build.sh for our Gradle task |
| OAuth flow logic | ✅ Reference | The browser-based OAuth dance for GDrive/OneDrive/Dropbox |
| Remote type definitions | ✅ Reference | List of supported providers and their config fields |
| SAF WebDAV bridge | ❌ Rewrite | The `safdav` module is clever but fragile; replace with direct SAF access |
| UI code | ❌ Rewrite | All Java Activities/Fragments → Compose |
| Rclone.java (62KB) | ❌ Rewrite | Replace with clean RcloneEngine interface + RC API |
| Database | ❌ Rewrite | No existing schema worth preserving |

### Phased Implementation Plan

#### Phase 1: Foundation (Weeks 1-2)
- Project scaffolding (modules, Gradle, version catalog)
- Rclone binary build pipeline (cross-compile for 3 ABIs)
- RcloneEngine interface + RC API client
- Basic DI setup (Hilt)
- Room database schema (remotes, sync tasks, sync history)

#### Phase 2: Core Functionality (Weeks 3-5)
- Remote configuration (create/edit/delete remotes via rclone RC)
- File browser (list remote directories)
- Storage access (MANAGE_EXTERNAL_STORAGE + SAF fallback)
- Single manual sync operation (one folder pair)

#### Phase 3: Sync Engine (Weeks 5-8)
- Sync task definitions (source, dest, direction, filters)
- WorkManager scheduled sync
- ForegroundService for active sync
- Progress tracking and notifications
- Conflict detection and resolution UI
- Resume/retry on failure

#### Phase 4: Polish (Weeks 8-10)
- Settings screen (WiFi-only, battery, notifications)
- Onboarding flow (permissions, first remote setup)
- Material 3 theming (dynamic color)
- Error handling and user-facing messages
- Sync history/logs viewer

#### Phase 5: Release (Weeks 10-12)
- Testing (unit, integration, UI)
- Performance optimization (baseline profiles)
- F-Droid metadata and build recipe
- GitHub Actions CI/CD
- Documentation
- Beta release

---

## 7. Risk Register

| # | Risk | Likelihood | Impact | Mitigation |
|---|------|-----------|--------|------------|
| 1 | rclone binary size bloats APK (~50-70MB/ABI) | Certain | Medium | AAB with ABI splits for Play Store; per-ABI APKs for F-Droid; Play Asset Delivery if needed |
| 2 | Android 15 6h FGS timeout kills large syncs | Medium | High | Chunked resumable sync; track progress in Room; WorkManager restarts; rclone's `--retries` |
| 3 | Play Store rejects MANAGE_EXTERNAL_STORAGE | Low-Medium | High | Declaration form; Play version limited to internal storage if rejected; F-Droid as primary for SD card sync |
| 4 | OEM battery optimization kills background sync | High | Medium | User education in onboarding; dontkillmyapp.com links; REQUEST_IGNORE_BATTERY_OPTIMIZATIONS |
| 5 | rclone RC API changes between versions | Low | Medium | Pin rclone version; integration tests against RC API |
| 6 | rclone daemon process death during sync | Medium | Medium | Monitor child process; auto-restart with backoff; save progress state before restart |
| 7 | OAuth token refresh fails in background | Medium | Medium | Retry with exponential backoff; notify user if re-auth needed |
| 8 | Conflict resolution edge cases (partial uploads, renames) | High | Medium | Start with simple policy (newest wins); add manual resolution in v1.1; log all conflicts |
| 9 | Go cross-compilation breaks with new NDK | Low | Medium | Pin NDK version; test in CI; cache built binaries |
| 10 | Scoped storage changes in Android 16+ | Medium | Medium | Abstract storage access behind interface; adapt without UI changes |
| 11 | Default OAuth client ID rate-limited by provider | Low-Medium | Medium | Monitor usage; BYO override available; register for higher quotas |
| 12 | rclone security vulnerability requires urgent update | Low | High | CI pipeline can rebuild + release quickly; pin to latest stable at build time |

---

## 8. Resolved Questions

| Question | Decision |
|----------|----------|
| App name & package ID | **Virga** / `app.lusk.virga` |
| License | **Apache 2.0** (clean-room rewrite, no GPL code copied) |
| OAuth | Ship default client IDs; BYO as advanced override |
| rclone version | Pin to latest stable at project init; update via PR + CI rebuild |
| MVP scope | Fully functional: file browser, sync tasks, bisync, scheduled sync, conflict handling |
| bisync | Yes, in v1 (rclone bisync, with clear "experimental" labeling) |
| Encryption (rclone crypt) | Defer to v1.1 — adds significant UX complexity |

## 9. Additional Implementation Notes

### Bandwidth Throttling
Expose rclone's `--bwlimit` in sync task options. Essential for mobile data users.
- UI: slider or text input in sync task settings
- Options: "No limit", "1 MB/s", "5 MB/s", "10 MB/s", custom
- Separate limits for WiFi vs metered connections

### Rclone Config Import/Export
Power users migrating from desktop rclone expect to import their existing `rclone.conf`.
- Import: file picker → validate → encrypt → store
- Export: decrypt → share intent (with security warning)

### Rclone Resource Limits
Prevent OOM kills on constrained devices:
- Default `--buffer-size 16M` (not rclone's default 256M)
- Default `--transfers 4` (not rclone's default 4, but configurable down to 1)
- Default `--checkers 8`
- Expose in advanced settings for power users

### Notification Channels (required on minSdk 26)
- `sync_progress` — ongoing sync notifications (low importance)
- `sync_complete` — sync finished/failed (default importance)
- `sync_error` — auth failures, permission issues (high importance)

### Rclone Update Strategy
The rclone binary is baked into the APK. Every rclone update requires an app release.
- CI checks for new rclone releases weekly (GitHub Actions cron)
- Automated PR with updated binary if tests pass
- Security patches: expedited release

---

## Appendix A: Version Catalog

```toml
# gradle/libs.versions.toml

[versions]
kotlin = "2.1.20"
agp = "8.8.2"
compose-bom = "2025.04.01"
hilt = "2.54"
room = "2.7.0"
datastore = "1.1.2"
lifecycle = "2.9.0"
navigation = "2.9.0"
work = "2.10.0"
coroutines = "1.10.1"
serialization = "1.7.3"
okhttp = "4.12.0"
ksp = "2.1.20-1.0.29"
junit5 = "5.11.4"
mockk = "1.13.14"
turbine = "1.2.0"
robolectric = "4.14.1"

[libraries]
# Compose
compose-bom = { group = "androidx.compose", name = "compose-bom", version.ref = "compose-bom" }
compose-ui = { group = "androidx.compose.ui", name = "ui" }
compose-material3 = { group = "androidx.compose.material3", name = "material3" }
compose-ui-tooling = { group = "androidx.compose.ui", name = "ui-tooling" }
compose-ui-test = { group = "androidx.compose.ui", name = "ui-test-junit4" }

# Architecture
lifecycle-viewmodel = { group = "androidx.lifecycle", name = "lifecycle-viewmodel-compose", version.ref = "lifecycle" }
lifecycle-runtime = { group = "androidx.lifecycle", name = "lifecycle-runtime-compose", version.ref = "lifecycle" }
navigation-compose = { group = "androidx.navigation", name = "navigation-compose", version.ref = "navigation" }

# DI
hilt-android = { group = "com.google.dagger", name = "hilt-android", version.ref = "hilt" }
hilt-compiler = { group = "com.google.dagger", name = "hilt-compiler", version.ref = "hilt" }
hilt-navigation = { group = "androidx.hilt", name = "hilt-navigation-compose", version = "1.2.0" }
hilt-work = { group = "androidx.hilt", name = "hilt-work", version = "1.2.0" }

# Data
room-runtime = { group = "androidx.room", name = "room-runtime", version.ref = "room" }
room-ktx = { group = "androidx.room", name = "room-ktx", version.ref = "room" }
room-compiler = { group = "androidx.room", name = "room-compiler", version.ref = "room" }
datastore-preferences = { group = "androidx.datastore", name = "datastore-preferences", version.ref = "datastore" }

# Network
okhttp = { group = "com.squareup.okhttp3", name = "okhttp", version.ref = "okhttp" }
serialization-json = { group = "org.jetbrains.kotlinx", name = "kotlinx-serialization-json", version.ref = "serialization" }

# Background
work-runtime = { group = "androidx.work", name = "work-runtime-ktx", version.ref = "work" }

# Coroutines
coroutines-core = { group = "org.jetbrains.kotlinx", name = "kotlinx-coroutines-core", version.ref = "coroutines" }
coroutines-android = { group = "org.jetbrains.kotlinx", name = "kotlinx-coroutines-android", version.ref = "coroutines" }
coroutines-test = { group = "org.jetbrains.kotlinx", name = "kotlinx-coroutines-test", version.ref = "coroutines" }

# Testing
junit5 = { group = "org.junit.jupiter", name = "junit-jupiter", version.ref = "junit5" }
mockk = { group = "io.mockk", name = "mockk", version.ref = "mockk" }
turbine = { group = "app.cash.turbine", name = "turbine", version.ref = "turbine" }
robolectric = { group = "org.robolectric", name = "robolectric", version.ref = "robolectric" }

[plugins]
android-application = { id = "com.android.application", version.ref = "agp" }
android-library = { id = "com.android.library", version.ref = "agp" }
kotlin-android = { id = "org.jetbrains.kotlin.android", version.ref = "kotlin" }
kotlin-compose = { id = "org.jetbrains.kotlin.plugin.compose", version.ref = "kotlin" }
kotlin-serialization = { id = "org.jetbrains.kotlin.plugin.serialization", version.ref = "kotlin" }
hilt = { id = "com.google.dagger.hilt.android", version.ref = "hilt" }
ksp = { id = "com.google.devtools.ksp", version.ref = "ksp" }
room = { id = "androidx.room", version.ref = "room" }
```

---

## Appendix B: Key References

- [Android Developer Guides — Compose](https://developer.android.com/develop/ui/compose)
- [rclone RC API Documentation](https://rclone.org/rc/)
- [Android Foreground Service Types](https://developer.android.com/develop/background-work/services/fg-service-types)
- [WorkManager Advanced](https://developer.android.com/develop/background-work/background-tasks/persistent/getting-started)
- [Don't Kill My App](https://dontkillmyapp.com/) — OEM battery optimization reference
- [F-Droid Inclusion Policy](https://f-droid.org/docs/Inclusion_Policy/)
- [Round-Sync Source (reference)](https://github.com/newhinton/Round-Sync)
