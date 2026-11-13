# Cloudflare R2 release sync

> **Summary:** Node.js 22 validates published release assets, uploads them to R2 using curl SigV4, verifies anonymous downloads and updates the latest version pointer last.

After the `Build` workflow creates the official GitHub Release, it calls `Publish Release to R2` to
sync the signed APK, AAB, SHA256SUMS and version JSON to R2. The website downloads the official
installer straight from R2.

## Cloudflare configuration

1. Enable R2 and create a bucket that holds only public release files, with storage class **Standard**. The workflow below uses the S3 endpoint of the default jurisdiction.
2. In the bucket's **Settings → Custom Domains**, bind the download domain, for example `download.termish.dev`, and wait for the status to become Active. The domain must be in the same Cloudflare account.
3. During testing you can also enable **Public Development URL** and use the `https://pub-….r2.dev` address the console provides; that address is rate-limited and not CDN-cached, so official distribution uses the custom domain.
4. In R2's **Manage API Tokens**, create credentials with **Object Read & Write** scoped to this bucket, and save the **Access Key ID** and **Secret Access Key**. These two values are used for the S3 API and differ from a regular Cloudflare API token.

If the website reads the version JSON cross-origin, configure the actual website origins in the
bucket's **CORS Policy**. For example:

```json
[
  {
    "AllowedOrigins": ["https://termish.dev", "https://www.termish.dev"],
    "AllowedMethods": ["GET", "HEAD"],
    "ExposeHeaders": ["Content-Length", "ETag"],
    "MaxAgeSeconds": 3600
  }
]
```

Public downloads do not need write operations allowed. The fixed APK/AAB, version JSON and fixed
checksums use `Cache-Control: no-store, max-age=0`, so that the custom domain's browser cache TTL
setting does not make the latest download hit an old package. Per-version archived installers and
checksums are cached long-term. Cloudflare cache rules should respect these response headers and
must not force caching on the fixed entry points.

## GitHub Actions repository configuration

In the repository's **Settings → Secrets and variables → Actions**, add the following on the
**Secrets** and **Variables** tabs respectively:

| Type | Name | Content |
| --- | --- | --- |
| Secret | `R2_ACCESS_KEY_ID` | R2 S3 Access Key ID |
| Secret | `R2_SECRET_ACCESS_KEY` | R2 S3 Secret Access Key |
| Variable | `R2_ACCOUNT_ID` | the 32-character Cloudflare Account ID |
| Variable | `R2_BUCKET` | R2 bucket name, for example `termish-releases` |
| Variable | `R2_PUBLIC_BASE_URL` | HTTPS root domain for public downloads, for example `https://download.termish.dev`, without `/downloads` |
| Variable | `R2_PREFIX` | optional, defaults to `downloads` |

`R2_PUBLIC_BASE_URL` must not be `*.r2.cloudflarestorage.com`, which is an S3 API address that
requires authentication, not a public download address. The workflow generates the upload endpoint
automatically from the Account ID.

GitHub Release downloads use the automatically provided `GITHUB_TOKEN`, with no extra PAT. The
workflow reads repository-level configuration directly, with no GitHub Environment to create.

The local Android signing configuration is stored in `.env` at the project root (already
Git-ignored). Its secrets can be written into separate repository Secrets in bulk:

```bash
gh secret set --repo ttermish/termish --env-file .env
```

That command uploads only the entries already in the file and does not delete other repository
secrets. Regular R2 configuration still goes in Variables; do not import a secrets file into
Variables, and there is no need to create a combined `ENV_FILE`. CI injects process environment
variables from the repository configuration, and the R2 publish script reads them.

## Publishing and backfilling

Publish a new version with the project's existing flow; R2 is synced automatically once the GitHub
Release succeeds.

To backfill an existing version: **Actions → Publish Release to R2 → Run workflow**, select the
`main` branch and enter tag `v1.7.1`; leave it empty to sync the latest official version. No rebuild
of the app is needed.

The upload script first checks the GitHub Release's SHA-256, file size and the digest GitHub
provides, then uploads and verifies each object by anonymous download. The latest entry is updated
only after the installer and archive downloads verify, and `version.json` is written last and read
back for verification. A public download returning 403/404 or mismatched content fails the workflow,
so a merely successful upload is not mistaken for successful distribution.

Backfilling an older version updates only the archive and does not overwrite the latest entry.
Workflow runs are serialised so concurrent uploads do not overwrite each other. The script does not
create buckets, delete old archives, or change the domain, CORS or public-access configuration; the
anonymous check is no substitute for a browser CORS check.

## Files and download URLs

```text
downloads/
  version.json
  release.json
  termish.apk
  termish.aab
  SHA256SUMS
  releases/v1.7.1/
    Termish-1.7.1-release.apk
    Termish-1.7.1-release.aab
    SHA256SUMS
    release.json
```

The website reads `download_url` from `version.json` to download the signed official APK from R2:

```json
{
  "version": "v1.7.1",
  "version_name": "1.7.1",
  "download_url": "https://download.termish.dev/downloads/releases/v1.7.1/Termish-1.7.1-release.apk",
  "github_download_url": "https://github.com/ttermish/termish/releases/download/v1.7.1/Termish-1.7.1-release.apk",
  "sha256": "the file's SHA-256",
  "assets": {"apk": {}, "aab": {}}
}
```

`assets.apk.url` and `assets.aab.url` are both R2 download addresses; `github_download_url` points to
the corresponding GitHub Release file. The fixed download object `/downloads/termish.apk` also comes
from the same official Release.

## Local verification

Tests that need no credentials and never touch the cloud:

```bash
node --test scripts/tests/*.test.mjs
```

References: [R2 S3 credentials](https://developers.cloudflare.com/r2/get-started/s3/), [public access and custom domains](https://developers.cloudflare.com/r2/buckets/public-buckets/), [CORS](https://developers.cloudflare.com/r2/buckets/cors/), [S3 API compatibility](https://developers.cloudflare.com/r2/api/s3/api/).
