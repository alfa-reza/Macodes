# SFTP (ssh/SftpSession)

> **Summary:** the platform-neutral `SftpSession` contract (list / mkdir /
> home / upload / download) and the sshj implementation for JVM — the download
> progress callback, and how the UI layer does recursive cross-directory
> search, streaming download (Android straight to Downloads), recursive folder
> download, and **text preview**
> (streams first 512 KB via the download chunk callback, NUL-byte binary
> detection, truncation hint), static image/SVG preview with bounded raster sizes,
> plus native video playback backed by independent
> SFTP range readers (64-bit seeking, bounded reads, lifecycle cleanup).

## Design

SFTP is an **independent SSH connection + SFTP channel**: it does not reuse the
terminal session (disconnecting the terminal does not affect SFTP), but
authentication / host key confirmation reuse the same `SshCallbacks` — dialogs
are handled globally by the UI layer, transparently to the user.

```kotlin
interface SftpSession {
    fun list(path: String): List<SftpEntry>   // throws on failure, surfaced by the UI layer
    fun mkdir(path: String)
    fun home(): String                        // realpath(".") resolves the user's home directory
    fun upload(remotePath: String, content: ByteArray)
    fun download(
        remotePath: String,
        onProgress: (loaded: Long, total: Long) -> Unit = { _, _ -> },  // progress
        onChunk: (ByteArray) -> Unit,                                   // blocking chunk callback
    )
    fun close()
}
```

`SftpEntry` is a platform-neutral directory entry (name / directory flag /
permission string / size / time / hidden), and the permission string is
uniformly formatted as `drwxr-xr-x` for display in the UI.

## JVM Implementation (SftpSessionSshj)

Reuses the `SshSessionSshj` auth chain (password / private key / encrypted key
passphrase / KBI / TOFU); `openSftp()` opens an `SFTPClient` on the authenticated
connection (lazily initialized, `clientOrThrow`).

- **list**: `c.ls(path)` maps the attributes (`FileMode.Type.DIRECTORY` decides
  directories, the permissions set → a `drwxr-xr-x` string)
- **home**: `canonicalize(".")` — more general than guessing `/home/xxx` (covers
  macOS / BSD / custom homes fully)
- **upload**: wraps a `LocalSourceFile` (in-memory `ByteArrayInputStream`,
  permissions 0644) and transfers it via sshj's `put`
- **download**: `open(remotePath)` + 64KB chunks read by offset via callback —
  **streaming**, the whole file is never held in memory; `onProgress` reports the
  total size via `remote.length()`; the UI layer writes the local file and closes
  the save channel

## UI Layer (SftpScreen / SftpHostPickerOverlay)

- Connection overlay → file browser tab; **breadcrumb navigation** (two-level
  collapsing + back = history back); **recursive search** (cross-directory, file
  name + relative path shown flat, clicking jumps to the containing directory;
  depth limited to 8 levels / 500 results); switching tabs keeps the path / list
  state (`SftpUiState` lives with `SftpSessionEntry`)
- Upload: system file picker (Android SAF)
- **Text preview** (file menu → preview): `readSftpPreview` reuses the download
  chunk callback and reads only the first 512 KB (beyond that it throws
  `SftpPreviewTooLargeException` to abort the download stream; the download
  `finally` closes the channel); if the first 4 KB contain NUL
  it is judged binary and throws `SftpPreviewBinaryException`; UTF-8 decoding
  (invalid sequences are replaced rather than crashing).
  UI: full-screen modal overlay (file name + size + close), monospace rendering via
  LazyColumn, a hint bar at the top when truncated; state lives in `SftpUiState`
  (not lost when switching tabs)
- Download: **streams chunks to a local file** (the whole file is never held in
  memory) + a top progress banner (file name / percentage / progress bar) + a
  system notification on completion (tapping opens the file on Android); on
  Android 10+ it writes straight to the public Download directory (MediaStore,
  same-name dedup automatically, no save-as dialog); Android 9- falls back to
  SAF
- **Recursive directory download**: `DirectorySaver` expands file by file /
  subdirectory by subdirectory; directory detection depends on the accuracy of
  `isDirectory` (see above)
- Sessions are managed at the same level as terminal sessions
  (`SessionManager.sftpSessions`): visible on the connections page, closable from
  the card's Close, released together when the host is deleted

## Testing

- `commonTest/ui/SftpLogicTest`: UI-layer pure logic (path joining / recursive
  expansion / recursive search)
- `androidUnitTest/ssh/SftpIntegrationTest`: upload / download / recursive
  directory round-trip against a real sshd (self-detect, SKIP when sshd is absent)
## SVG Image Preview

Clicking a `.svg` in the file list (extension matching is case-insensitive) opens
the image preview, reusing the fit-to-screen / original-size toggle and the
download menu. As with other images, the read limit is 8 MiB; beyond that the
existing image-too-large hint is shown. Android uses AndroidSVG for static
rendering, preserving transparent backgrounds; scripts are not
executed, and no resource resolvers are configured for external images, fonts or
styles.

Document dimensions are preferred; missing dimensions are derived from the
`viewBox`, and when both are missing a 300 × 150 canvas is used. The document
coordinate system is preserved and the output bitmap's longest edge is limited to
2048 pixels, avoiding unbounded bitmap allocation from large SVGs. Corrupt files
reuse the image decode failure hint and can still be downloaded. The preview is a
static raster image; SVG animation, interaction or infinite zoom are not provided;
which SVG features work depends on the renderer. When complete text appearance
is required, use an SVG with the text converted to paths, or download it and
open it in another viewer.

Regression tests: `SvgPreviewSizeTest` covers the dimension fallback and memory
bounds; Android's `ImageDecodeTest` exercises the actual rasterization path,
transparent backgrounds, percentage dimensions, viewBox-only and oversized
viewBox-less canvases, verifying that content is not cropped when scaled down,
that corrupt files fail, and that existing PNG decoding works.

## Streaming Video Preview

Clicking a video in the file list (mp4 / m4v / mov / mkv / webm etc.) opens the
native player; play, pause and seeking all use platform controls; the complete
file is not downloaded first. Whether the container and audio/video codecs are
playable depends on the device's native decoding capability. Unsupported codecs or connection errors show a localized
error, and the file can still be downloaded from the preview page's more menu and
opened in another player.

`SftpSession.openRead` returns `SftpReadHandle`, which owns a dedicated SSH/SFTP
connection, avoiding contention with file browsing and downloads on the same
channel. The file size and requested offset are both `Long`; each read is at most
64 KB, and short reads and an empty array at EOF are allowed. The handle is called
serially on a background thread; closing the parent file session does not close
the playback handle. The playback connection reuses authentication and host key
verification, but does not propagate close/trace events to the parent session.

- Media3 ExoPlayer / PlayerView, with `DataSpec.position/length` mapped to
  SFTP random reads; the file handle is reused during a seek and closed only after
  the player is destroyed. Forward buffer target 16 MB. The playback URL carries
  no remote address or credentials, and no HTTP server is started.
- Switching tabs destroys the player and releases the connection; `SftpUiState`
  records the remote path and playback position, and reopens from that position on
  return; entering the background pauses playback, and native controls can resume
  it on return. Closing the preview leaves no cached file.

Regression tests: `SftpVideoDataSourceTest` verifies ranges, short reads, EOF,
corruption/truncation, release and >4 GB offsets;
`SftpIntegrationTest.randomReadUsesIndependentConnectionAndLongOffsets` uses a
local sshd and a sparse file to verify real random reads, Unicode paths, parent
session closing and idempotent release.
