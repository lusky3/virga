# Security

## Reporting Vulnerabilities

Please report security issues via GitHub private vulnerability reporting on this repository.

## OAuth & Embedded Secrets

### Box

Virga does not bundle a Box `client_id` or `client_secret`. Box is set up through
the same flow as the other non-bundled (long-tail) OAuth backends: the auth runs
in the rclone daemon, which uses rclone's own default Box client, or credentials
the user supplies on-device (bring-your-own-keys). No Box secret ships in the APK.

### Other providers

Google Drive, Dropbox, and OneDrive use PKCE (RFC 7636) with no embedded secret.
PKCE protects against authorization code interception attacks.
