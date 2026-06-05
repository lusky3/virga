# Security

## Reporting Vulnerabilities

Please report security issues via GitHub private vulnerability reporting on this repository.

## OAuth & Embedded Secrets

### Box client_secret

Box's OAuth implementation does not support PKCE for mobile/installed-app clients.
This means the `client_secret` must be included in the token exchange request.
The secret is embedded in the APK — this is industry standard for Box mobile apps
(Box's own SDKs do the same).

Mitigations:

- PKCE (used for Drive, Dropbox, OneDrive) is not available; the secret is the
  only viable option for mobile OAuth with Box.
- The secret alone cannot access user data — a valid authorization code (bound to
  the redirect URI) is still required.
- Abuse is monitored via the Box Developer Console (token grants, anomalous usage).
- If the secret is compromised, it can be rotated without a data breach.

### Other providers

Google Drive, Dropbox, and OneDrive use PKCE (RFC 7636) with no embedded secret.
PKCE protects against authorization code interception attacks.
