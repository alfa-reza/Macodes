package dev.macodes.data

import kotlin.random.Random
import kotlinx.serialization.Serializable

/** Host authentication methods. */
@Serializable
enum class HostAuthMethod {
    PASSWORD,
    PRIVATE_KEY,
    KEY_OR_PASSWORD,
}

/** Connection method: SSH terminal (default) or Mosh (requires mosh-server to be installed on the remote machine). */
@Serializable
enum class ConnectionMode {
    SSH,
    MOSH,
}

@Serializable
data class Host(
    val id: String,
    val name: String,
    val hostname: String,
    val port: Int = 22,
    val username: String = "root",
    val authMethod: HostAuthMethod = HostAuthMethod.PASSWORD,
    val tags: List<String> = emptyList(),
    /**
     * Remote system identifiers (to show in avatars): such as ubuntu / debian / macOS / windows.
     * Avatar icons and colors are automatically mapped based on keywords; if not recognized, a generic icon is shown.
     */
    val system: String = "",
    val colorIndex: Int = 0,
    val createdAt: Long = 0L,
    val lastConnectedAt: Long = 0L,
    val knownHostFingerprint: String? = null,
    /** SSH terminal or Mosh. */
    val connectionMode: ConnectionMode = ConnectionMode.SSH,
    /** Commands that run automatically after a successful connection (such as `tmux new -A -s main` to resume the session). */
    val startupCommand: String = "",
    /**
     * After the Mosh connection is established, inject the phone terminal theme (foreground/background/16-color palette)
     * as OSC replies into the remote input stream. mosh-server swallows the theme queries of the remote TUI
     * (e.g. herdr), so the remote would otherwise render with the host machine's terminal theme instead of the phone's;
     * with this enabled, apps like herdr behave as if they had received the terminal's OSC reply
     * and adopt the phone color scheme.
     */
    val moshThemeSync: Boolean = false,
    /**
     * Mosh pins the UDP port; 0 indicates that mosh-server automatically performs the selection (60000–61000).
     * When using port forwarding on a NAS or router (to allow only specific ports), you can specify a single port,
     * And perform port forwarding on that UDP port to the remote host.
     */
    val moshUdpPort: Int = 0,
)

@Serializable
enum class ThemeMode { DARK, LIGHT, SYSTEM }

@Serializable
data class AppSettings(
    val theme: ThemeMode = ThemeMode.DARK,
    /** UI language: empty = follow system; "zh" / "en" = user’s explicit selection. */
    val language: String = "",
    val terminalThemeIndex: Int = 0,
    val fontSize: Int = 14,
    val terminalFontSize: Int = 12,
    /** Avatar letters/background color: randomly generated on first entry to the settings page, then persisted; no longer change on switching. */
    val avatarLetter: String = "",
    val avatarColorIndex: Int = -1,
    /** Target terminal column count: When >0, ignore `terminalFontSize` and automatically calculate the font size based on screen width (for terminal alignment with desktop terminals like 120×30). */
    val terminalTargetCols: Int = 0,
    /** PTY terminal type ($TERM): xterm-256color by default; xterm/vt100/linux compatible alternatives. */
    val terminalType: String = "xterm-256color",
    /** Terminal font ID (see TerminalFont): jetbrains (default). */
    val terminalFontId: String = "jetbrains",
    /** Automatically reconnect upon unexpected disconnection (exponential backoff, up to 3 attempts). */
    val autoReconnect: Boolean = true,
    val keepaliveSeconds: Int = 30,
    val cursorBlink: Boolean = true,
    val hapticFeedback: Boolean = true,
    /** Prompt for confirmation when connecting to an unknown host for the first time (TOFU). */
    val verifyHostKeyOnFirstUse: Boolean = true,
    /** OSC 52: Allows remote programs to write to the system clipboard (copying in nvim/tmux will sync to the local machine). */
    val osc52Clipboard: Boolean = true,
    /** General notification switch (background event notifications, such as disconnection or failure to reconnect). */
    val notificationEnabled: Boolean = false,
    /** IDs of disabled notification events (see NotificationEvent); empty = all enabled. */
    val notificationDisabledEvents: Set<String> = emptySet(),
    /** Does Android display instructions on background connection protection after establishing an SSH connection for the first time? */
    val backgroundProtectionPrompted: Boolean = false,
    /** Global voice input switch (terminal toolbar and Agent input field microphone button). */
    val voiceInputEnabled: Boolean = false,
    /**
     * List of speech recognition services (plug-and-play: Volcano Engine, etc.; Alibaba Cloud, iFlytek, and others may be added in the future);
     * Each item is an independent key/parameter (keys are stored in SecretStore). Old field [asrResourceId]
     * Replaced by a list; automatic migration upon reading (see AppRoot).
     */
    val asrProviders: List<AsrProvider> = emptyList(),
    /** Agent model provider configuration; keys are stored separately in SecretStore by provider ID. */
    val agentProviders: List<AgentProvider> = emptyList(),
    /** @deprecated Legacy single-instance configuration: Replaced by [asrProviders] (for migration purposes). */
    val asrResourceId: String = "",
)

/** Agent workspace preferences are saved independently on each host to avoid overwriting global terminal settings. */
@Serializable
data class AgentWorkspacePreferences(
    val defaultAgent: String = "",
    val defaultModel: String = "",
    val defaultDirectory: String = "",
    val showThinking: Boolean = true,
    /** Legacy “default expand tool” settings are retained solely for deserialization; the timeline is now always collapsed by default. */
    val expandTools: Boolean = false,
    /** Agent ID -> provider ID; a missing or empty string means the CLI's built-in login is used. */
    val providerByAgent: Map<String, String> = emptyMap(),
)

/** Agent model provider type: wire protocol classification (OpenAI-compatible / Anthropic-compatible).
 *  [DEEPSEEK] Legacy single-vendor enumeration values, used solely for migration compatibility (equivalent OpenAI types +
 *  (Anthropic-compatible endpoints); all new configurations must use [OPENAI] / [ANTHROPIC].
 */
@Serializable
enum class AgentProviderType {
    /** Legacy DeepSeek single vendor (migration-compatible). */
    DEEPSEEK,

    /** OpenAI-compatible protocols (chat/completions; claude requires a separate [AgentProvider.anthropicBaseUrl]). */
    OPENAI,

    /** Anthropic Compatibility Protocol (/v1/messages). */
    ANTHROPIC,
}

/** Non-sensitive configurations for the Agent model provider; API keys are not included in the Settings serialization (stored in SecretStore).
 *  Refer to the Provider model from tuiniverse: type = wire protocol, baseUrl + anthropicBaseUrl
 *  Dual endpoints (Claude uses the Anthropic-compatible endpoint; all others use the OpenAI-compatible endpoint) and a list of models.
 */
@Serializable
data class AgentProvider(
    val id: String,
    val type: AgentProviderType = AgentProviderType.OPENAI,
    val name: String = "DeepSeek",
    val baseUrl: String = "https://api.deepseek.com",
    /**
     * Use Anthropic-compatible endpoints specifically for Claude (e.g., DeepSeek → https://api.deepseek.com/anthropic).
     * By default, Claude is only available when type=ANTHROPIC (using [baseUrl]);
     * If an OpenAI-type provider does not support this field, Claude cannot use it.
     */
    val anthropicBaseUrl: String = "",
    /** Provider identifiers used by the Pi CLI; protocol types and Pi provider IDs must not be used interchangeably. */
    val piProvider: String = "",
    /** List of preset or manually entered models (quickly select when creating a new session; can be pulled from /models). */
    val models: List<String> = emptyList(),
    val enabled: Boolean = true,
)

/** Speech recognition service types (plug-and-play providers). */
@Serializable
enum class AsrProviderType {
    /** Volcano Engine · Large Model Streamed Speech Recognition (bigmodel_async WebSocket). */
    VOLC_STREAMING,
}

/** Configuration for a speech recognition service instance (the key is stored separately in SecretStore: asr.<ID>.apiKey). */
@Serializable
data class AsrProvider(
    val id: String,
    /** Service type (determines which engine to use for implementation). */
    val type: AsrProviderType = AsrProviderType.VOLC_STREAMING,
    /** Show names (e.g., “Volcano Engine · Family Edition”). */
    val name: String = "",
    /** Type-specific parameters: Volcano Stream uses resource IDs (model versions; see VolcAsrProtocol.RESOURCE_IDS). */
    val resourceId: String = "",
    /** Whether enabled (the terminal prioritizes the first enabled service). */
    val enabled: Boolean = true,
)

/** Generate a random ID (UUID v4 style). */
internal fun newId(): String {
    val bytes = ByteArray(16) { Random.nextBytes(1)[0] }
    // version 4, variant 10
    bytes[6] = ((bytes[6].toInt() and 0x0f) or 0x40).toByte()
    bytes[8] = ((bytes[8].toInt() and 0x3f) or 0x80).toByte()
    val hex = "0123456789abcdef"
    val sb = StringBuilder(36)
    for (i in bytes.indices) {
        val b = bytes[i].toInt() and 0xff
        sb.append(hex[b ushr 4]).append(hex[b and 0xf])
        if (i == 3 || i == 5 || i == 7 || i == 9) sb.append('-')
    }
    return sb.toString()
}
