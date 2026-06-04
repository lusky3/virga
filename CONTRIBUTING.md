# Contributing to Virga

Thanks for your interest! Virga is a Kotlin/Compose Android app that syncs local
storage to cloud providers via rclone.

## Getting set up

1. Install JDK 21 and the Android SDK (platform 36, build-tools 36). NDK
   `27.2.12479018` and Go 1.25+ are only needed to build the rclone binaries.
2. Build: `./gradlew assembleFossDebug`. The `:rclone-build` module
   cross-compiles `librclone.so` on demand when it's absent and skips when the
   binaries already exist.

## Ground rules

- **Kotlin only.** No new Java sources.
- **Match the module boundaries.** UI in `feature:*`, data in `core:*`, no
  Room/rclone types leaking through public APIs where a domain model fits.
- **Keep files under ~500 lines.**
- **Tests for logic.** ViewModels, repositories, and the rclone engine should
  have unit tests (`./gradlew testFossDebugUnitTest`). Use MockK + Turbine +
  Truth, JUnit 5.
- **Run lint** before opening a PR: `./gradlew lintFossDebug`.
- **No secrets** in commits. OAuth *client IDs* are public by design but stay
  out of git — set them in `local.properties` (`oauthClientId.*`) or via
  `VIRGA_OAUTH_CLIENT_ID_*` env vars, from where they reach BuildConfig. Client
  *secrets*, keystores, and tokens never get committed.

## Commit / PR

- Keep changes focused; one logical change per PR.
- Describe what and why. Link the relevant task in `specs/virga-android/tasks.md`.
- CI must pass (build + unit tests + lint).

## rclone version bumps

The pinned version lives in `scripts/build-rclone.sh` and the CI workflow env.
Bump both together, rebuild, and run the full test suite before releasing.
