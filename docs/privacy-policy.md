# Virga Privacy Policy

_Last updated: 2026-05-27_

Virga is an open-source file-sync utility. It is designed so that your data and
credentials stay on your device and go only to the cloud providers **you**
configure.

## What Virga accesses

- **Files you choose to sync.** Virga reads and writes the local folders and
  remote destinations you configure in a sync task. It does not scan or upload
  anything you have not configured.
- **Storage permission.** `MANAGE_EXTERNAL_STORAGE` (or legacy read permission
  on older Android) is used solely to let rclone read/write the folders you
  select, including SD-card storage.
- **Network.** Used only to transfer your files to/from the cloud providers you
  configure, and for the OAuth sign-in you initiate.

## What Virga stores

- **Remote credentials / OAuth tokens** are stored in an `rclone.conf` that is
  **encrypted at rest** using an Android Keystore-backed key, and excluded from
  cloud backup.
- **Task definitions and run history** (paths, schedules, transfer counts,
  errors) are stored locally in an app database. Backups exclude credentials.

## What Virga does NOT do

- No analytics, telemetry, ads, or third-party tracking SDKs.
- No transmission of your data to the developer or any server other than the
  cloud providers you configure.
- No account or sign-up with the developer.

## Data sharing

Virga shares your files only with the cloud providers you explicitly configure,
governed by **their** privacy policies. The developer receives nothing.

## Your control

You can delete remotes, tasks, and history in-app at any time. Uninstalling the
app removes all locally stored data, including the encrypted config.

## Contact

File issues or questions on the project's repository.
