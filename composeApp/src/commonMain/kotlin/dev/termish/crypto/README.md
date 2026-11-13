# crypto/ — in-house cryptography (unaudited)

> **Summary:** threat model of the pure-Kotlin crypto primitives.
> Only `Sha256` is used in production (host fingerprints, credential
> signatures). The AES-128-OCB used by the mosh wire protocol is an explicit,
> documented exception. Everything else is research/test-only — do not wire
> it into SSH/KEX, data encryption or key derivation until audited.

The algorithm implementations in this directory are for research and test reference only and have
**not been through third-party cryptographic audit**.

## Primitive inventory and production status

| File | Implementation | Used in production | Notes |
|------|----------------|--------------------|-------|
| `Sha256.kt` | SHA-256 | ✅ **yes** | host fingerprints (libssh2 blob SHA-256), session credential digest (`credentialSignature`) |
| `Aes.kt` + `Ocb.kt` | AES-128-OCB | ✅ **explicit exception** | mosh transport encryption, see below |
| `Sha512.kt` | SHA-512 | ❌ tests only | RFC vector verification |
| `Hmac.kt` | HMAC-SHA256/512 | ❌ tests only | |
| `Ed25519.kt` | Ed25519 signatures | ❌ tests only | once used while exploring SSH public-key schemes |
| `Field.kt` | X25519 curve field arithmetic | ❌ tests only | |
| `ChaCha20.kt` / `Poly1305.kt` | ChaCha20-Poly1305 AEAD | ❌ tests only | once used while evaluating mosh encryption schemes |

**Policy**: until the audit is complete, these implementations must not be wired into SSH/KEX, data
encryption or key-derivation paths.
When curve or ChaCha capability is needed, prefer the platform provider:

- Android: the full BouncyCastle bundled with the app (`installFullBouncyCastle` in
  `TermishApplication.kt` — the cut-down BouncyCastle that ships with Android lacks X25519 /
  Ed25519)
- iOS: the system security frameworks

## Exception: mosh transport encryption (`dev.termish.mosh`)

The mosh SSP protocol mandates AES-128-OCB as its AEAD, and the mosh key is delivered over the SSH
bootstrap channel (`MOSH CONNECT <port> <key>`); UDP-layer encryption guards against a link observer
tampering with or replaying terminal traffic, while the key's own security is provided by SSH. To keep
"pure Kotlin, zero native dependencies" (an in-house implementation replacing the GPLv3 native
client), `mosh/Aes.kt` + `mosh/Ocb.kt` are in-house implementations running on the production path,
which is an **explicit exception** to this directory's policy:

- Correctness: `commonTest/mosh/OcbTest` includes independent standard decryption
  vectors (not a self-consistent round-trip), so an algorithm error surfaces in the unit tests
- Limitation: table-driven software AES has **no constant-time guarantee** and implies a theoretical
  timing side channel; under the threat model (the attacker must already be able to observe the
  device's CPU caches at close range) the risk is acceptable, but it is recorded here
- Direction: if a platform AEAD (JVM BouncyCastle / iOS CommonCrypto) is adopted later and the cost of
  per-platform source variants is acceptable, the in-house OCB should be replaced

## Testing

`commonTest/crypto/CryptoTest` covers the RFC standard test vectors (RFC 4231 HMAC,
FIPS 180-4 SHA, RFC 8032 Ed25519 and others) — these implementations stay vector-correct even when
unused in production, so they remain auditable at any time.
