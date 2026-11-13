# Security Policy

Macodes is an SSH client that handles credentials, so security issues are handled
with priority.

## Supported versions

Security fixes are only provided for the latest release version.

## Reporting a vulnerability

Please report security vulnerabilities privately through
[research@alfareza.my.id](mailto:research@alfareza.my.id); do not publicly
disclose details of unfixed vulnerabilities.

Please include as much as possible: affected version/platform, reproduction
steps, and potential impact. We will acknowledge receipt within 72 hours and
follow up on remediation progress by email.

## The project's own security boundaries

Understanding our threat model helps determine whether an issue counts as a
security vulnerability:

- Passwords/private keys are stored in the Android Keystore (AES-GCM) / iOS Keychain
- Host keys use TOFU (trust on first use + fingerprint confirmation)
- The pure-Kotlin implementations under `crypto/` are **unaudited**, as stated in
  the README; the production path only uses Sha256 from them
- No telemetry, no analytics; network traffic is limited to user-configured
  SSH/Mosh hosts, the speech recognition service, and the remote Agent model
  service
