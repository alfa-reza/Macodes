# Termish Privacy Policy

Effective date: 2026-09-03

Contact: ttermish@gmail.com

## English

Termish is a local-first SSH, Mosh, SFTP, remote-screen, and remote Agent client.
It does not require a Termish account and does not include advertising, analytics,
or telemetry services.

### Data stored on your device

Host details, app preferences, snippets, and recent session metadata are stored on
your device. Passwords, private keys, and API keys are stored using the protected
credential storage supplied by the operating system. Termish does not upload these
credentials to a server operated by the Termish project.

Android backups are disabled for Termish. You can delete local data by removing the
corresponding host or provider, clearing the app's data in system settings, or
uninstalling the app.

### SSH, Mosh, SFTP, and remote-screen connections

Termish connects directly to servers that you configure. Terminal traffic, files,
remote-screen frames, and control events are transmitted between your device and
the selected server. They are not proxied, stored, or inspected by the Termish
project. The owner or operator of the destination server may process this data
under their own terms and privacy policy.

### Optional voice recognition

Voice input is disabled by default. When you enable it, Termish shows a disclosure
before requesting microphone permission. Audio is recorded only while you hold the
microphone button and is sent over an encrypted connection to the speech-recognition
provider that you configured. The currently supported provider is Volcano Engine.

Termish uses the audio only to obtain a transcription and does not intentionally
persist the recording. The provider may process or retain data according to its
own terms, account settings, and legal obligations. You can stop this processing
by disabling voice input or revoking microphone permission.

### Optional Agent providers

Agent tools run on the server you select. Depending on your configuration, prompts,
terminal context, working-directory information, and attachments may be sent from
that server to OpenAI, Anthropic, Volcano Engine, or another compatible provider.
This traffic does not pass through a server operated by the Termish project. Each
provider's terms and privacy policy apply.

### Notifications and background connections

Notifications are optional and are used for connection, transfer, and Agent-task
status. When you keep an active SSH session, Android may show a persistent
notification while a foreground service maintains the connection. Termish does not
use these capabilities for advertising or tracking.

### Diagnostics

Diagnostic logging is disabled by default. Logs stay on your device unless you
explicitly export and share them. Logs may contain technical context such as host
names, network addresses, file paths, commands, or error details. Review an exported
log before sharing it.

### Data sharing, retention, and deletion

The Termish project does not operate a central user account, analytics database, or
session relay and therefore does not retain user content on its own servers. Data
sent to a server or provider chosen by you is controlled by that party. Contact the
relevant provider or server operator to request deletion of data held by them.

### Children

Termish is a professional developer tool and is not directed to children under 13.

### Changes

This policy may be updated when Termish adds or changes data-handling features. The
effective date above will be revised, and material changes will be communicated in
the app or release notes where appropriate.
