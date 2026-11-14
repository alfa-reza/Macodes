# Google Play submission material

> Package name: `dev.macodes.app`<br>
> Category: Tools<br>
> Default language: English (United States)<br>
> Privacy policy URL: `https://github.com/alfa-reza/Macodes/blob/main/docs/appstore/privacy-policy.md`<br>
> Support URL: `https://github.com/alfa-reza/Macodes`

Before the first upload, confirm that `compileSdk` / `targetSdk` are both 36 and
use the existing Macodes production signing key as the Play App Signing app
signing key, so the website APK and the Play version keep the same signature.
Creating a separate upload key is recommended.

## Store listing (English)

### App name

`Macodes SSH Terminal`

### Short description

`Local-first SSH, Mosh, SFTP, and AI Agent terminal`

### Full description

```text
Macodes is a mobile SSH and Mosh terminal and a pocket workspace for remote
development environments and AI Agents. No Macodes account, ads, analytics, or
telemetry are required. Credentials stay in platform-protected storage, and
connections go directly to servers you choose.

Resilient remote sessions
• SSH and a pure-Kotlin Mosh client
• Connection recovery across Wi-Fi and cellular changes
• Background keep-alive, health checks, and automatic reconnect
• Works with persistent server-side sessions such as tmux

A real mobile terminal
• Pure-Kotlin terminal emulator, not a WebView
• VT100/xterm sequences, true color, CJK wide characters, and OSC 8 links
• IME composition stays local until text is committed
• CTRL, ALT, ESC, arrow keys, and TUI mouse input
• Multiple session tabs, terminal themes, fonts, and column controls

File management
• Browse, upload, download, and manage files with SFTP
• Transfer progress and completion notifications

Remote AI Agents
• Connect to Agent tools running on your own server
• Supports Codex, Claude Code, Gemini CLI, OpenCode, and Pi
• Session history, attachments, approvals, and task status
• You choose the Agent provider; traffic does not pass through Macodes servers

Security and privacy
• Passwords, private keys, and API keys use protected credential storage
• Host fingerprint confirmation on first connection
• Cleartext network traffic is disabled
• Voice input is off by default and sends audio to the recognition provider you
  configure only while you hold the microphone button
• No ads, analytics, or telemetry

Website: https://github.com/alfa-reza/Macodes
Privacy policy: https://github.com/alfa-reza/Macodes/blob/main/docs/appstore/privacy-policy.md
Source code (Apache-2.0): https://github.com/alfa-reza/Macodes
```

## App content form recommendations

Final answers must match the AAB you are about to upload, the third-party
service terms, and the live privacy policy.

### Ads

`No, my app does not contain ads.`

### App access

Choose "Some functionality is restricted" because the core SSH/SFTP
capabilities require server credentials.

```text
Macodes does not have an application account. To review its core functionality,
use the reusable SSH test server below.

Host: <REVIEW_HOST>
Port: <REVIEW_PORT>
Username: <REVIEW_USER>
Password: <REVIEW_PASSWORD>

Steps:
1. Open Macodes and tap Add host.
2. Enter the host, port, username, and password above.
3. Save and tap the host card.
4. Accept the displayed host fingerprint.
5. Run `help`, `ls`, or `htop` to verify terminal input and output.
6. Open the session menu to access SFTP functions.

The credentials are reusable, do not require MFA, and remain available throughout
the review period. The account is isolated and contains only disposable test data.
```

Before submitting, replace every placeholder with the public review host
details; do not write the review password into the repository or public store copy.

### Target audience

Only select `18 and over` when the product genuinely targets professional
development/operations users, and declare that the app is not intended for children.

### Content rating

Complete the IARC questionnaire truthfully. The app itself contains no violence,
sexual content, gambling, drugs, or public user interaction content; content on
users' own servers is not provided by Macodes.

### Data safety

Recommend declaring as per the current implementation:

| Data type | Collected | Shared | Necessity | Purpose | Notes |
|---|---|---|---|---|---|
| Audio files → Voice or sound recordings | Yes | Confirm per the Volcano Engine role | Optional | App functionality | Sent to Volcano Engine for transcription while the user holds the microphone |

General question recommendations:

- Data encrypted in transit: `Yes`
- Users can request deletion: Macodes has no central account or its own server
  data; third-party data deletion follows Volcano Engine's terms — confirm before selecting
- Credentials, SSH content, SFTP files: not received by the Macodes project;
  traffic goes directly to the server the user specifies
- Agent prompts and attachments: sent from the user's server to the provider they
  configured themselves; confirm against Google Play's definitions of
  user-initiated transfer and service providers whether this qualifies for the
  sharing exemption, and do not simply enter "no data"

### Foreground service declaration

Type: `connectedDevice`<br>
Closest purpose: `Continuous data transfer to an external device`

Function description:

```text
Macodes is an interactive SSH and Mosh client. After the user explicitly connects
to a remote computer, a connected-device foreground service maintains that active
encrypted network session while the app is backgrounded. A persistent notification
makes the activity visible and lets the user return to or stop the session.
```

Impact of delaying or interrupting:

```text
Deferring the task prevents an explicitly requested interactive shell from becoming
usable. Interrupting it disconnects the user's active terminal, stops incoming
output and file transfers, and may lose unsaved remote work unless the user also
configured a persistent server-side session.
```

The demo video should record the full flow: user-initiated connection → terminal
output → switch to background → persistent notification → still connected when
returning to foreground → notification disappears after the user disconnects.
Use an unlisted YouTube link for the video and make sure reviewers can access it.

## Release notes

For the first internal test, use:

```text
First Google Play test release with SSH, Mosh, SFTP, multiple sessions, voice input, and remote Agent workflows.
```

## Graphic assets

- Play icon: `google-play/play-icon.png` (512×512)
- Feature graphic: `google-play/feature-graphic.png` (1024×500)
- English phone screenshots: `google-play/en/`

Upload order and accessibility notes:

| Order | File | English alt text |
|---|---|---|
| 1 | `hosts.png` | Host list with active connection status |
| 2 | `terminal-menu.png` | Terminal session with Git, upload, SFTP, and voice actions |
| 3 | `sftp.png` | SFTP file browser and directory actions |
| 4 | `settings.png` | Theme, language, notification, and terminal preferences |

## Console execution order

1. Complete developer identity, contact details, real-device, and payment profile verification.
2. Create the app and confirm the package name `dev.macodes.app`.
3. Configure Play App Signing: import the existing Macodes app signing key and create a separate upload key.
4. Upload the API 36 AAB to Internal testing.
5. Complete Store listing, Privacy policy, Ads, App access, Target audience,
   Content rating, Data safety, and Foreground service declarations.
6. Check the Pre-launch report and fix crashes, ANRs, accessibility, and compatibility issues.
7. New personal accounts must run Closed testing with 12 testers for 14 consecutive days, and save the tester feedback.
8. Apply for Production access; after approval, release to a small percentage first, then expand gradually.

## Pre-submission checklist

- [ ] `compileSdk` / `targetSdk` are 36
- [ ] `versionCode` is higher than the version already uploaded to Play Console
- [ ] The AAB is signed with the correct upload key
- [ ] The live privacy policy is anonymously reachable over HTTPS
- [ ] The in-app privacy policy matches the live version and Data safety
- [ ] Volcano Engine audio retention, deletion, and provider roles have been verified
- [ ] The FGS demo video is watchable without signing in
- [ ] The review SSH account is publicly reachable, has no MFA, and remains valid long-term
- [ ] The 512 icon, 1024×500 feature graphic, and at least four phone screenshots have been uploaded
- [ ] The Chinese and English store copy matches current functionality
- [ ] Android vitals, Pre-launch report, and policy status have been checked
