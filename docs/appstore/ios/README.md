# iOS App Store 6.5-inch assets

Native iOS screenshots and app previews captured from Termish 1.7.1 (35).

This asset set corresponds to the **iPhone 6.5-inch** slot in App Store Connect, fixing the
issue where the previous screenshots did not meet that slot's size requirements.

| Asset | Chinese `zh/` | English `en/` | Spec |
| --- | --- | --- | --- |
| Screenshots | 8 images | 7 images | 1242 × 2688, JPEG, no alpha channel |
| App preview | `app-preview.mp4` | `app-preview.mp4` | 886 × 1920, H.264 High / Level 4.0, 30 fps |

The Chinese video is 27 seconds and the English video is 29 seconds; both include clips of
Herdr code editing and workspace switching. The videos target a 10 Mbps bitrate and include
a 48 kHz, 256 kbps stereo AAC silent track. There is no voiceover or background music.

## How to upload

1. In App Store Connect, select the corresponding version and language, then expand the preview
   and screenshot slots for **iPhone 6.5-inch**; open "View Media Manager" if necessary.
2. Remove the old images with incorrect dimensions from this slot, then upload the `.jpg` files
   from the corresponding language directory by file number.
3. Upload this directory's `app-preview.mp4` to the App Preview area. Screenshots and the preview
   video use different pixel dimensions.
4. Wait for Apple to finish video processing, then check the preview poster frame and screenshot
   order. Putting the terminal screenshot first is recommended, but you may also keep the current
   "Hosts → Terminal → Files" order.

Upload the exported original files; thumbnails displayed in a browser or chat window are not used
for upload.

## Content and sources

- `01-hosts.jpg`: host list.
- `02-terminal.jpg`: a real htop terminal running after connecting to the local demo SSH server.
- `03-sftp.jpg`: remote project directory.
- `04-preview.jpg`: Markdown file preview, provided only in the Chinese set.
- `05-settings.jpg`: settings.
- `06-theme.jpg`: terminal color scheme and font.
- `07-herdr-editor.jpg`: Vim code editor inside a Herdr workspace.
- `08-herdr-workspace.jpg`: Herdr workspace and tab switching.

The screenshots come directly from the iPhone 11 Pro Max simulator, iOS 26.5, app version 1.7.1 (35).
Demo data is isolated from real host information; the interface was not redrawn, cropped, or stretched.
The videos were edited from actual screen recordings and converted to the dimensions Apple requires,
keeping a reading pause at the end of static pages.

The Markdown preview page still uses the two Chinese button labels 预览 / 源码 (Preview /
Source), so the English set does not include this page. The app code was not
modified during the production of these assets.

The Herdr screenshots come from a real running demo workspace and include two tabs, Editor and
Health Check. Herdr's own interface is in English, so the Herdr menus in both asset sets are
displayed in English.

Check each file's dimensions, format, duration, and encoding before uploading, then confirm the
processing result in App Store Connect after uploading.

## Apple specifications

- [Screenshot specifications](https://developer.apple.com/help/app-store-connect/reference/app-information/screenshot-specifications): 6.5-inch supports 1242 × 2688 or 1284 × 2778 portrait screenshots.
- [App preview specifications](https://developer.apple.com/help/app-store-connect/reference/app-information/app-preview-specifications/): 6.5-inch portrait video is 886 × 1920, 15–30 seconds, up to 30 fps.

Specifications checked on: 2026-09-05.
