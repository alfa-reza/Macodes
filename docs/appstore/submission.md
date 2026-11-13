# App Store Connect submission material (iOS)

Submission copy and preparation notes for the MIT-licensed Termish iPhone app.

Verification date: 2026-09-05. The copy is based on iOS 1.7.1 in the repository;
the build actually uploaded is authoritative. This page provides store copy and
filling instructions; it does not mean App Store Connect has been filled in or
submitted.

Termish returned to MIT open source on 2026-09-06; see
https://github.com/ttermish/termish for the source code. The source license and
store pricing are maintained separately. The current source contains no iOS
In-App Purchase implementation; this version of the copy does not promise that
In-App Purchases are available, nor does it claim the app is permanently free;
the download price is still to be determined.

## Store information: Simplified Chinese (zh-Hans)

- **App name** (30 characters max): `Termish 终端`
- **Subtitle** (30 characters max): `SSH、Mosh 与远程工作台`
- **Keywords** (100 UTF-8 bytes max, comma-separated):
  `ssh,mosh,sftp,linux,server,shell,tmux,vim,远程,服务器,开发,运维`
- **Promotional text** (170 characters max):
  `随时连接服务器，处理终端任务、管理文件和切换远程工作区。为 iPhone 打造的 SSH、Mosh 与 SFTP 客户端。`
- **Description** (4000 characters max):

```text
把远程工作台带在身边。

Termish 是为 iPhone 打造的 SSH、Mosh 与 SFTP 客户端。无论是查看服务器状态、处理文件，还是继续远程开发任务，都可以从手机开始。

连接你的服务器
使用密码或私钥连接 SSH 主机，保存常用主机并管理多个会话。支持主机指纹确认，帮助你核对连接目标。

适合手机的终端操作
使用 Ctrl、Alt、Esc 和方向键操作终端程序，配合中文输入、多种终端配色与字体，让日常命令和交互式工具更易使用。

应对网络变化
通过 Mosh 连接，在网络恢复后继续可恢复的远程会话。Mosh 需要服务器安装 mosh-server，并允许相应 UDP 通信；恢复效果取决于网络、服务器状态和系统限制。

随手管理文件
使用 SFTP 浏览远程目录、上传和下载文件，在终端操作与文件管理之间切换。

继续远程开发
连接服务器上的 Herdr 工作区和已配置的 AI Agent 工具，查看任务进展、切换工作区并继续操作。相关工具和运行环境需要在服务器上安装或配置。

可选语音输入
配置兼容的语音识别服务后，可以按住麦克风按钮输入文字。启用该功能需要麦克风权限，录音会发送至你配置的识别服务商。

无需注册 Termish 账号
主机配置保存在设备上，密码、私钥和服务商密钥使用系统安全存储。Termish 不提供服务器或模型服务；使用远程功能需要你有权访问的服务器，语音和 AI 服务可能需要单独的服务商账号及费用。

iOS 可能暂停后台连接。需要持续运行的远程任务，建议在服务器上配合 tmux 等会话管理工具使用。

开源代码（MIT）：https://github.com/ttermish/termish
隐私政策：https://termish.dev/privacy/
支持与反馈：https://termish.dev/
```

## Store information: English (en-US)

- **App name**: `Termish`
- **Subtitle**: `SSH, Mosh & SFTP Client`
- **Keywords**: `terminal,linux,server,shell,remote,developer,files,tmux,vim,console`
- **Promotional text**:
  `Connect to your servers, manage files, and continue remote development from your iPhone with SSH, Mosh, and SFTP.`
- **Description**:

```text
Keep your remote workspace close.

Termish brings SSH, Mosh, and SFTP to your iPhone. Check a server, manage files, or pick up a remote development task wherever you work.

Connect to your servers
Sign in with a password or private key, save frequently used hosts, and manage multiple sessions. Verify host fingerprints when connecting to a server.

Terminal controls for your phone
Use Ctrl, Alt, Esc, and arrow keys with interactive terminal tools. Chinese text input, terminal color themes, and font options help you work comfortably on a small screen.

Handle changing networks
Use Mosh to resume a recoverable remote session when connectivity returns. Mosh requires mosh-server on the destination host and the appropriate UDP access. Recovery depends on network conditions, server state, and operating system limits.

Manage files over SFTP
Browse remote folders, upload and download files, and switch between terminal work and file management.

Continue remote development
Connect to Herdr workspaces and configured AI Agent tools on your server to follow tasks, switch workspaces, and keep working. These tools and their runtime environments must be installed or configured on the server.

Optional voice input
Configure a compatible speech-recognition service and hold the microphone button to enter text. This feature requires microphone permission and sends recordings to your selected speech provider.

No Termish account required
Host settings stay on your device. Passwords, private keys, and provider keys use system-protected storage. Termish does not supply server hosting or model services. Remote features require a server you are authorized to access; speech and AI services may require separate provider accounts and charges.

iOS may suspend background connections. For long-running remote tasks, use a server-side session manager such as tmux.

Source code (MIT): https://github.com/ttermish/termish
Privacy policy: https://termish.dev/privacy/
Support: https://termish.dev/
```

The copy describes only current mobile functionality; it includes no entry
points for source code, desktop, or browser versions, and exposes no
implementation details. Field limits follow the [Apple version information
reference](https://developer.apple.com/help/app-store-connect/reference/app-information/platform-version-information/).
Store names still need an availability check in App Store Connect.

## Other form fields

| Field | Filling instructions |
| --- | --- |
| Platform and version | iOS, `1.7.1`; the version field does not include the `v` from the Git tag |
| Bundle ID | `dev.termish.app` |
| Primary language | Simplified Chinese; add an English store localization |
| Category | Suggested primary category Developer Tools, secondary category Utilities; to be confirmed by the publisher |
| Download price | To be determined; set separately from In-App Purchase product prices |
| In-App Purchases | Not integrated in the current code; if you charge to unlock features at launch, complete the IAP implementation, product configuration, and testing first |
| Privacy policy URL | `https://termish.dev/privacy/`; returned HTTP 200 on this visit, see the check results for content revisions |
| Support URL | `https://termish.dev/`; returned HTTP 200 on this visit; the site has a contact email entry |
| Marketing URL | `https://termish.dev/` |
| Support email | `ttermish@gmail.com` |
| Age rating | Answer the current questionnaire according to actual functionality; do not prefill "None / 4+" |
| Copyright | `2026 <name or company of the actual copyright holder>`; replace before submitting |
| Review contact | Enter a real name, a contactable email, and a phone number with country code; do not write these into the repository |
| App account login | There is currently no Termish account system; SSH / provider access details for review must still be provided |
| Encryption compliance | Complete the questionnaire based on the real implementation and distribution regions; you cannot judge exemption from the NO value in Info.plist alone |
| Release method | The publisher chooses manual or automatic release; this material does not replace that choice |

Category recommendations reference the [Apple category definitions](https://developer.apple.com/app-store/categories/).

## Monetization and distribution regions

In-app purchases are part of the product plan; when filling in the material you
must distinguish the current build from later plans.
If paid unlocking is offered in this release, you need a usable purchase entry
point, entitlement restore, product names and prices, review screenshots, and a
clear review path, plus the required agreements, tax, and payout information.
The first product of each IAP type must be submitted together with the new app
version; subscriptions must also be added to a subscription group as required.
See the [Apple IAP submission instructions](https://developer.apple.com/help/app-store-connect/manage-submissions-to-app-review/submit-an-in-app-purchase/).
When IAP is not integrated, do not write "subscriptions supported" or "unlock
Pro" in the copy, and do not upload products that cannot be verified in the app.

Select the actual distribution regions under "Pricing and Availability" in App
Store Connect; the region of the developer account does not mean the app can
only be sold there. Availability in mainland China must be checked against the
applicable App filing requirements; you cannot judge it by whether the website
is hosted on Cloudflare. See
[availability](https://developer.apple.com/help/app-store-connect/manage-your-apps-availability/manage-availability-for-your-app-on-the-app-store/)
and [information required for mainland China](https://developer.apple.com/help/app-store-connect/reference/app-information/app-information/).
Declare trader status as required by App Store Connect; if distributing in the EU
as a trader, you must also verify public contact information, see the
[DSA information requirements](https://developer.apple.com/help/app-store-connect/manage-compliance-information/manage-european-union-digital-services-act-trader-requirements/).

## Review environment and notes

Reviewers need a dedicated test host reachable from the public internet. The
local Docker container, `127.0.0.1`, `10.0.2.2`, and LAN addresses used when
producing the assets cannot serve as the review connection address.
The test host should include SSH, SFTP, Mosh, and the Herdr demo workspace shown
in the screenshots; also prepare a reproducible Agent / remote-screen path, and
the test configuration needed for optional voice input.
Provide access according to the functionality actually enabled; do not rely on
reviewers signing up for paid services themselves.

Enter dedicated accounts, passwords, and keys only in the private review
information in App Store Connect; do not commit them to Git or put them in the
public store description. Keep the test environment available during review and
re-review.
The English notes below are a template; **replace all angle-bracket content and
verify the steps before pasting**.
The notes limit is 4000 UTF-8 bytes; re-check the length after filling them in.

```text
Termish is an iPhone client for SSH, Mosh, SFTP, remote workspaces, and server-side Agent tools. It does not require a Termish account. Commands and development tools run on the selected remote server.

Dedicated review environment:
Host: <PUBLIC_HOST>
SSH/SFTP port: <PORT>
Username: <REVIEW_USERNAME>
Password: <REVIEW_PASSWORD>
Expected SSH host fingerprint: <SHA256_FINGERPRINT>
Mosh UDP access: <ALLOWED_UDP_PORTS>
Demo directory: <DEMO_DIRECTORY>
Herdr workspace and launch steps: <VERIFIED_HERDR_STEPS>

Review steps:
1. In Hosts, add the connection above. Connect and verify its host fingerprint.
2. Run ls, htop, or vim on the test host. Test the Ctrl, Esc, and arrow-key controls.
3. Open the host's SFTP file browser. Browse the demo directory and upload or download a test file.
4. Open Connections to switch between sessions. Open Settings to change the interface language and terminal theme.
5. Follow the Herdr launch steps above to open the prepared editor and switch workspace tabs.
6. Agent review setup and a sample prompt: <VERIFIED_AGENT_STEPS_AND_TEST_ACCESS>
7. Remote-screen review setup: <VERIFIED_REMOTE_SCREEN_STEPS>
8. Optional voice input: <VERIFIED_SPEECH_PROVIDER_SETUP_AND_TEST_ACCESS>. Enable voice input in Settings, review the audio disclosure, grant microphone permission, and hold the microphone button in a terminal.

iOS may suspend background network activity. Mosh recovery requires the server-side session to remain available. Long-running remote tasks can use a server-side session manager such as tmux.

Speech recognition sends audio to the selected provider. Server-side Agent tools may forward prompts, context, and attachments to their configured model provider. These services do not use a Termish-operated relay.

Purchases in this build: <ACTUAL_PURCHASE_STATUS_AND_REVIEW_STEPS>
Review contact: <NAME_EMAIL_AND_PHONE>
```

This code review found no In-App Purchases. If the final submitted build still
has none, the purchase status can read:
`This build does not include In-App Purchases. Server hosting, speech recognition, and model services are supplied separately by the user's chosen providers.`
If IAP is added, change it to the actual entry points and test steps; this
sentence can no longer be used.

Review resources and accurate feature descriptions follow the [Apple review requirements](https://developer.apple.com/app-store/review/guidelines/#app-completeness).

## Screenshots and App previews

- [6.5-inch assets](ios/README.md) are available: 8 Chinese and 7 English, all 1242 × 2688 JPEG with no alpha channel, including the Herdr editor and workspace.
- Upload to the **iPhone 6.5-inch** slot for the corresponding language; do not mix other device sizes into that slot.
- Both preview videos are 886 × 1920, 27 seconds for Chinese and 29 seconds for English, and include Herdr interactions.
- Currently `TARGETED_DEVICE_FAMILY=1`, so no separate iPad assets are provided.
- The current assets correspond to 1.7.1 (35); if the final build changes purchase entry points, dialogs, or layout, re-check the affected screenshots and videos.
- After uploading, wait for Apple to process the assets and check the preview posters and playback.

See the [screenshot requirements](https://developer.apple.com/help/app-store-connect/reference/app-information/screenshot-specifications)
and [preview requirements](https://developer.apple.com/help/app-store-connect/reference/app-information/app-preview-specifications/) for specifications.

## Xcode Cloud build and final submission check

1. Verify the commit, Bundle ID, and signing team for the Xcode Cloud build; the workflow must include an Archive for distribution and complete the upload-to-App-Store-Connect step.
2. After the build succeeds, confirm that App Store Connect has finished processing the uploaded package; a green success status in Xcode Cloud alone is not enough to prove the version is selectable.
3. Verify that the store version matches the marketing version of the uploaded package (1.7.1 this time). The repository build number is `35`; the `45` / `46` on the Xcode Cloud page are its build sequence numbers; the version and build number actually parsed by App Store Connect are authoritative.
4. Confirm the package is not a distribution artifact limited to internal testing; a package marked TestFlight Internal Only cannot be used for App Store submission.
5. Complete the encryption compliance information for the build, then select the build to submit on the version page. The specific reason for the earlier "unable to add build" has not yet been confirmed.
6. Install the actual candidate package via TestFlight and verify SSH, Mosh, SFTP, Herdr, Agent, optional voice, and privacy entry points; if there are In-App Purchases, verify purchase, cancellation, and entitlement restore.
7. Fill in the Chinese and English metadata, upload media, complete the review contact and public test environment; check copyright, price, distribution regions, age rating, and App privacy.
8. Clear all placeholder content, check the items above, and then have the publisher submit.

See [uploading and processing builds](https://developer.apple.com/help/app-store-connect/manage-builds/upload-builds/),
[choosing a build to submit](https://developer.apple.com/help/app-store-connect/manage-builds/choose-a-build-to-submit),
and the [Apple internal testing distribution instructions](https://developer.apple.com/tutorials/develop-in-swift/test-your-beta-app).
