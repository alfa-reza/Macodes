package dev.macodes.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Autorenew
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Vibration
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchColors
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.macodes.APP_VERSION
import dev.macodes.data.AppSettings
import dev.macodes.data.HostRepository
import dev.macodes.data.SECRET_SERVICE
import dev.macodes.data.SecretStore
import dev.macodes.data.ThemeMode
import dev.macodes.data.asrKeyAccount
import dev.macodes.notify.NotificationPermissionController
import dev.macodes.notify.NotificationPermissionState
import dev.macodes.ui.theme.Corners
import dev.macodes.ui.theme.Sizes
import dev.macodes.ui.theme.Spacing
import dev.macodes.ui.theme.TerminalThemes
import dev.macodes.util.BackgroundProtectionState
import dev.macodes.util.TermLog
import dev.macodes.util.openApplicationSettings

// ---- About External Links (Official Website / Contact Email) ----
private const val WEBSITE_URL = "https://github.com/alfa-reza/Macodes"
private const val WEBSITE_HOST = "github.com/alfa-reza/Macodes"
private const val CONTACT_EMAIL = "research@alfareza.my.id"
internal const val PRIVACY_POLICY_URL = "https://github.com/alfa-reza/Macodes/blob/main/docs/appstore/privacy-policy.md"

private fun themeModeLabel(
    mode: ThemeMode,
    s: AppStrings,
): String =
    when (mode) {
        ThemeMode.DARK -> s.settingsThemeDark
        ThemeMode.LIGHT -> s.settingsThemeLight
        ThemeMode.SYSTEM -> s.settingsThemeSystem
    }

private fun languageLabel(
    code: String,
    s: AppStrings,
): String =
    when (code) {
        "zh" -> s.languageZh
        "en" -> s.languageEn
        else -> s.settingsLanguageSystem
    }

@Composable
fun SettingsScreen(
    settings: AppSettings,
    /** Any modifications are called back immediately (i.e., modifications are saved as soon as they are made; there is no "Save" button). */
    onChange: (AppSettings) -> Unit,
    /** The Snippets/Tags management tab reads from and writes to the library. */
    repository: HostRepository = HostRepository(),
    notificationPermissionState: NotificationPermissionState,
    notificationPermissionController: NotificationPermissionController,
    onNotificationPermissionStateChange: (NotificationPermissionState) -> Unit,
    backgroundProtectionState: BackgroundProtectionState,
    /** Currently open subpages (centralized management by AppRoot: backlink + hidden tab in fullscreen mode). */
    subPage: SettingsSubPage? = null,
    /** Toggle the secondary panel (null = off). */
    onOpenSub: (SettingsSubPage?) -> Unit = {},
    /** Do not show the back arrow in tab-indented mode. */
    showBack: Boolean = false,
    onBack: (() -> Unit)? = null,
) {
    val s = LocalAppStrings.current
    val uriHandler = LocalUriHandler.current
    var theme by remember { mutableStateOf(settings.theme) }
    var terminalThemeIndex by remember { mutableStateOf(settings.terminalThemeIndex) }
    var fontSize by remember { mutableStateOf(settings.terminalFontSize.toFloat()) }
    var targetCols by remember { mutableStateOf(settings.terminalTargetCols.toFloat()) }
    var terminalType by remember { mutableStateOf(settings.terminalType) }
    var haptics by remember { mutableStateOf(settings.hapticFeedback) }
    var autoReconnect by remember { mutableStateOf(settings.autoReconnect) }
    var keepalive by remember { mutableStateOf(settings.keepaliveSeconds.toFloat()) }
    var verifyHostKey by remember { mutableStateOf(settings.verifyHostKeyOnFirstUse) }
    var osc52Clipboard by remember { mutableStateOf(settings.osc52Clipboard) }
    var language by remember { mutableStateOf(settings.language) }

    var showThemeDialog by remember { mutableStateOf(false) }
    var showLanguageDialog by remember { mutableStateOf(false) }
    var showTerminalTypeDialog by remember { mutableStateOf(false) }
    var notificationEnabled by remember { mutableStateOf(settings.notificationEnabled) }
    var notificationDisabledEvents by remember { mutableStateOf(settings.notificationDisabledEvents) }
    var voiceInputEnabled by remember { mutableStateOf(settings.voiceInputEnabled) }
    // Recognition service list (pluggable providers); keys stored per provider in SecretStore
    var asrProviders by remember { mutableStateOf(settings.asrProviders) }
    var showConnectionsSessionPreview by remember { mutableStateOf(settings.showConnectionsSessionPreview) }
    var showQuickConnectHistory by remember { mutableStateOf(settings.showQuickConnectHistory) }
    var showConnectionsSftp by remember { mutableStateOf(settings.showConnectionsSftp) }

    fun persist() =
        onChange(
            settings.copy(
                theme = theme,
                terminalThemeIndex = terminalThemeIndex,
                terminalFontSize = fontSize.toInt(),
                terminalTargetCols = targetCols.toInt(),
                terminalType = terminalType,
                hapticFeedback = haptics,
                autoReconnect = autoReconnect,
                keepaliveSeconds = keepalive.toInt(),
                verifyHostKeyOnFirstUse = verifyHostKey,
                osc52Clipboard = osc52Clipboard,
                notificationEnabled = notificationEnabled,
                notificationDisabledEvents = notificationDisabledEvents,
                voiceInputEnabled = voiceInputEnabled,
                asrProviders = asrProviders,
                language = language,
                showConnectionsSessionPreview = showConnectionsSessionPreview,
                showQuickConnectHistory = showQuickConnectHistory,
                showConnectionsSftp = showConnectionsSftp,
            ),
        )

    Scaffold(
        topBar = { MacodesLargeHeader(title = s.settingsTitle) },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()),
        ) {
            // General Group: App Theme / Language / Notifications / Diagnostics / Snippets / Voice / Haptics
            SettingsGroup(s.settingsGroupGeneral) {
                SettingsOptionItem(s.settingsAppTheme, themeModeLabel(theme, s), icon = Icons.Filled.Palette) {
                    showThemeDialog = true
                }
                SettingsOptionItem(s.settingsLanguage, languageLabel(language, s), icon = Icons.Filled.Language) {
                    showLanguageDialog = true
                }
                SettingsOptionItem(
                    s.settingsNotifications,
                    if (
                        notificationEnabled &&
                        notificationPermissionState == NotificationPermissionState.GRANTED
                    ) {
                        s.settingsOn
                    } else {
                        s.settingsOff
                    },
                    icon = Icons.Filled.Notifications,
                ) { onOpenSub(SettingsSubPage.NOTIFICATION) }
                SettingsOptionItem(
                    s.settingsDiagnostics,
                    if (TermLog.diagnosticsEnabled) s.settingsOn else s.settingsOff,
                    icon = Icons.Filled.BugReport,
                ) { onOpenSub(SettingsSubPage.DIAGNOSTICS) }
                SettingsOptionItem(
                    s.settingsSnippets,
                    "",
                    icon = Icons.Filled.Code,
                ) { onOpenSub(SettingsSubPage.SNIPPETS) }
                SettingsOptionItem(
                    s.voice.settingsVoice,
                    if (voiceInputEnabled) {
                        s.voice.providerCount(asrProviders.count { it.enabled })
                    } else {
                        s.settingsOff
                    },
                    icon = Icons.Filled.Mic,
                ) { onOpenSub(SettingsSubPage.VOICE) }
                SettingsSwitchItem(s.settingsHaptics, haptics, icon = Icons.Filled.Vibration) {
                    haptics = it
                    persist()
                }
            }

            SettingsGroup(s.settingsGroupTerminal) {
                SettingsOptionItem(
                    s.settingsTerminalPalette,
                    TerminalThemes.ALL.getOrElse(terminalThemeIndex) { TerminalThemes.ALL[0] }.name,
                    icon = Icons.Filled.Palette,
                ) { onOpenSub(SettingsSubPage.TERMINAL) }
                SettingsOptionItem(s.settingsTerminalType, terminalType, icon = Icons.Filled.Terminal) {
                    showTerminalTypeDialog = true
                }
                SettingsSliderItem(
                    title = s.settingsTerminalCols,
                    valueText = if (targetCols < 1f) s.settingsManualFontSize else targetCols.toInt().toString(),
                    value = targetCols,
                    onValueChange = {
                        targetCols = it
                        persist()
                    },
                    valueRange = 0f..160f,
                )
                SettingsSliderItem(
                    title = s.settingsTerminalFontSize,
                    valueText = "${fontSize.toInt()}sp",
                    value = fontSize,
                    onValueChange = {
                        fontSize = it
                        persist()
                    },
                    valueRange = 6f..32f,
                    enabled = targetCols < 1f,
                )
                SettingsSwitchItem(s.settingsOsc52Clipboard, osc52Clipboard, icon = Icons.Filled.ContentPaste) {
                    osc52Clipboard = it
                    persist()
                }
            }

            SettingsGroup(s.settingsGroupSession) {
                SettingsSliderItem(
                    title = s.settingsKeepalive,
                    valueText = if (keepalive < 1f) s.settingsKeepaliveOff else "${keepalive.toInt()}s",
                    value = keepalive,
                    onValueChange = {
                        keepalive = it
                        persist()
                    },
                    valueRange = 0f..300f,
                )
                SettingsSwitchItem(s.settingsAutoReconnect, autoReconnect, icon = Icons.Filled.Autorenew) {
                    autoReconnect = it
                    persist()
                }
                if (backgroundProtectionState != BackgroundProtectionState.NOT_APPLICABLE) {
                    SettingsOptionItem(
                        s.permissions.backgroundProtection,
                        if (backgroundProtectionState == BackgroundProtectionState.SYSTEM_EXEMPT) {
                            s.permissions.backgroundProtectionExempt
                        } else {
                            s.permissions.backgroundProtectionManaged
                        },
                        icon = Icons.Filled.BatteryChargingFull,
                        onClick = ::openApplicationSettings,
                    )
                }
                SettingsSwitchItem(s.settingsVerifyHostKey, verifyHostKey, icon = Icons.Filled.Security) {
                    verifyHostKey = it
                    persist()
                }
            }

            SettingsGroup(s.quickConnect.settingsConnectionsGroup) {
                SettingsSwitchItem(
                    s.quickConnect.settingsShowSessionPreview,
                    showConnectionsSessionPreview,
                ) {
                    showConnectionsSessionPreview = it
                    persist()
                }
                SettingsSwitchItem(
                    s.quickConnect.settingsShowQuickHistory,
                    showQuickConnectHistory,
                ) {
                    showQuickConnectHistory = it
                    persist()
                }
                SettingsSwitchItem(
                    s.quickConnect.settingsShowSftpShortcut,
                    showConnectionsSftp,
                ) {
                    showConnectionsSftp = it
                    persist()
                }
            }

            SettingsGroup(s.settingsGroupAbout) {
                SettingsOptionItem(s.settingsVersion, APP_VERSION, showArrow = false, icon = Icons.Filled.Info) {}
                SettingsOptionItem(s.settingsWebsite, WEBSITE_HOST, icon = Icons.Filled.Public) {
                    uriHandler.openUri(WEBSITE_URL)
                }
                SettingsOptionItem(s.permissions.privacyPolicy, "", icon = Icons.Filled.Lock) {
                    onOpenSub(SettingsSubPage.PRIVACY)
                }
                SettingsOptionItem(s.settingsContact, CONTACT_EMAIL, icon = Icons.Filled.Email) {
                    uriHandler.openUri("mailto:$CONTACT_EMAIL")
                }
            }

            Spacer(Modifier.height(Spacing.Xl))
        }
    }

    if (showThemeDialog) {
        SettingsChoiceDialog(
            title = s.settingsAppTheme,
            options = ThemeMode.entries.map { themeModeLabel(it, s) },
            selected = ThemeMode.entries.indexOf(theme),
            onSelect = {
                theme = ThemeMode.entries[it]
                persist()
            },
            onDismiss = { showThemeDialog = false },
        )
    }
    // Secondary screen overlay: iOS-style swipe right to enter/exit (same animation as Termius’s secondary screens);
    // Switch states are centrally managed by AppRoot (back chain + fullscreen tab that is hidden)
    AnimatedVisibility(
        visible = subPage == SettingsSubPage.TERMINAL,
        enter = slideInHorizontally(initialOffsetX = { it }) + fadeIn(animationSpec = tween(240)),
        exit = slideOutHorizontally(targetOffsetX = { it }) + fadeOut(animationSpec = tween(200)),
    ) {
        SettingsTerminalScreen(
            currentThemeIndex = terminalThemeIndex,
            currentFontId = settings.terminalFontId,
            onChangeTheme = {
                terminalThemeIndex = it
                persist()
            },
            onChangeFont = { fontId -> onChange(settings.copy(terminalFontId = fontId)) },
            onBack = { onOpenSub(null) },
        )
    }
    // Notification settings subpage (animation same as terminal theme page: swipe right to enter/exit)
    AnimatedVisibility(
        visible = subPage == SettingsSubPage.NOTIFICATION,
        enter = slideInHorizontally(initialOffsetX = { it }) + fadeIn(animationSpec = tween(240)),
        exit = slideOutHorizontally(targetOffsetX = { it }) + fadeOut(animationSpec = tween(200)),
    ) {
        SettingsNotificationScreen(
            enabled = notificationEnabled,
            disabledEvents = notificationDisabledEvents,
            permissionState = notificationPermissionState,
            permissionController = notificationPermissionController,
            onPermissionStateChange = onNotificationPermissionStateChange,
            onChangeEnabled = {
                notificationEnabled = it
                persist()
            },
            onChangeEvent = { id, disable ->
                notificationDisabledEvents =
                    if (disable) notificationDisabledEvents + id else notificationDisabledEvents - id
                persist()
            },
            onBack = { onOpenSub(null) },
        )
    }
    AnimatedVisibility(
        visible = subPage == SettingsSubPage.DIAGNOSTICS,
        enter = slideInHorizontally(initialOffsetX = { it }) + fadeIn(animationSpec = tween(240)),
        exit = slideOutHorizontally(targetOffsetX = { it }) + fadeOut(animationSpec = tween(200)),
    ) {
        SettingsDiagnosticsScreen(
            enabled = TermLog.diagnosticsEnabled,
            onChangeEnabled = { TermLog.diagnosticsEnabled = it },
            onBack = { onOpenSub(null) },
        )
    }
    // Command Snippet Management Subpage (Global Library + Tag Group Management Entry Point)
    AnimatedVisibility(
        visible = subPage == SettingsSubPage.SNIPPETS,
        enter = slideInHorizontally(initialOffsetX = { it }) + fadeIn(animationSpec = tween(240)),
        exit = slideOutHorizontally(targetOffsetX = { it }) + fadeOut(animationSpec = tween(200)),
    ) {
        SnippetManageScreen(
            repository = repository,
            onBack = { onOpenSub(null) },
        )
    }
    // Voice Input Settings Subpage (Volcano Engine Streamed Speech Recognition: Switch / API Key / Resource ID)
    AnimatedVisibility(
        visible = subPage == SettingsSubPage.VOICE,
        enter = slideInHorizontally(initialOffsetX = { it }) + fadeIn(animationSpec = tween(240)),
        exit = slideOutHorizontally(targetOffsetX = { it }) + fadeOut(animationSpec = tween(200)),
    ) {
        SettingsVoiceScreen(
            enabled = voiceInputEnabled,
            providers = asrProviders,
            apiKeyOf = { p -> SecretStore.get(SECRET_SERVICE, asrKeyAccount(p.id)) ?: "" },
            onChangeEnabled = {
                voiceInputEnabled = it
                persist()
            },
            onAddProvider = { p, key ->
                asrProviders = asrProviders + p
                SecretStore.set(SECRET_SERVICE, asrKeyAccount(p.id), key)
                persist()
            },
            onUpdateProvider = { p, key ->
                asrProviders = asrProviders.map { if (it.id == p.id) p else it }
                // If the key is empty, leave it unchanged; if it is not empty, overwrite it.
                if (key.isNotBlank()) {
                    SecretStore.set(SECRET_SERVICE, asrKeyAccount(p.id), key)
                }
                persist()
            },
            onDeleteProvider = { p ->
                asrProviders = asrProviders.filter { it.id != p.id }
                SecretStore.delete(SECRET_SERVICE, asrKeyAccount(p.id))
                persist()
            },
            onBack = { onOpenSub(null) },
        )
    }
    AnimatedVisibility(
        visible = subPage == SettingsSubPage.PRIVACY,
        enter = slideInHorizontally(initialOffsetX = { it }) + fadeIn(animationSpec = tween(240)),
        exit = slideOutHorizontally(targetOffsetX = { it }) + fadeOut(animationSpec = tween(200)),
    ) {
        SettingsPrivacyScreen(onBack = { onOpenSub(null) })
    }
    if (showLanguageDialog) {
        val codes = listOf("", "zh", "en")
        val options =
            listOf(
                s.settingsLanguageSystem,
                s.languageZh,
                s.languageEn,
            )
        SettingsChoiceDialog(
            title = s.settingsLanguage,
            options = options,
            selected = codes.indexOf(language),
            onSelect = {
                language = codes[it]
                persist()
            },
            onDismiss = { showLanguageDialog = false },
        )
    }
    if (showTerminalTypeDialog) {
        // TERM option: xterm-256color (default; matches the emulator’s 256-color capability);
        // The rest are compatibility alternatives (the server will fallback according to terminfo; no new rendering capabilities are required).
        val options = listOf("xterm-256color", "xterm", "vt100", "linux")
        SettingsChoiceDialog(
            title = s.settingsTerminalType,
            options = options,
            selected = options.indexOf(terminalType).coerceAtLeast(0),
            onSelect = {
                terminalType = options[it]
                persist()
            },
            onDismiss = { showTerminalTypeDialog = false },
        )
    }
}

/** Termius-style Switch colors: electric blue active track with crisp white thumb. */
@Composable
internal fun macodesSwitchColors(): SwitchColors =
    SwitchDefaults.colors(
        checkedThumbColor = Color.White,
        checkedTrackColor = MaterialTheme.colorScheme.primary,
        checkedBorderColor = Color.Transparent,
        uncheckedThumbColor = Color.White,
        uncheckedTrackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        uncheckedBorderColor = Color.Transparent,
    )

/** Bordered tonal section card grouping with crisp section label. */
@Composable
internal fun SettingsGroup(
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Text(
        title,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = Spacing.Xl, top = Spacing.Lg, bottom = Spacing.Sm),
    )
    Card(
        Modifier.fillMaxWidth().padding(horizontal = Spacing.Lg),
        shape = RoundedCornerShape(Corners.Lg),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        border = BorderStroke(Sizes.BorderThin, MaterialTheme.colorScheme.outlineVariant),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(content = content)
    }
}

@Composable
private fun SettingsLeadingIconBadge(icon: ImageVector) {
    Box(
        modifier =
            Modifier
                .size(32.dp)
                .clip(RoundedCornerShape(Corners.Sm))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(18.dp),
        )
    }
}

/** Stacked settings row with optional leading tinted icon badge, subtitle/value, and trailing chevron. */
@Composable
internal fun SettingsOptionItem(
    title: String,
    value: String,
    showArrow: Boolean = true,
    icon: ImageVector? = null,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = Spacing.Lg, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.Md),
    ) {
        if (icon != null) {
            SettingsLeadingIconBadge(icon)
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (value.isNotEmpty()) {
                Text(
                    value,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (showArrow) {
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Numeric settings row: Title + current value, followed by a slim slider. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsSliderItem(
    title: String,
    valueText: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    enabled: Boolean = true,
) {
    Column(Modifier.fillMaxWidth().padding(start = Spacing.Lg, top = 14.dp, end = Spacing.Lg, bottom = 7.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f),
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 1f else 0.4f),
            )
            Text(
                valueText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (enabled) 1f else 0.4f),
            )
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            enabled = enabled,
            modifier = Modifier.fillMaxWidth().requiredHeight(32.dp),
            thumb = {
                Box(
                    Modifier.size(18.dp).clip(CircleShape).background(
                        if (enabled) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
                        },
                    ),
                )
            },
            track = { state ->
                SliderDefaults.Track(
                    sliderState = state,
                    modifier = Modifier.height(4.dp),
                    colors =
                        SliderDefaults.colors(
                            activeTrackColor =
                                MaterialTheme.colorScheme.primary.copy(
                                    alpha = if (enabled) 1f else 0.3f,
                                ),
                            inactiveTrackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                        ),
                )
            },
        )
    }
}

/** Switch settings row with optional leading tinted icon badge. */
@Composable
internal fun SettingsSwitchItem(
    title: String,
    checked: Boolean,
    subtitle: String? = null,
    icon: ImageVector? = null,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = Spacing.Lg, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.Md),
    ) {
        if (icon != null) {
            SettingsLeadingIconBadge(icon)
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (!subtitle.isNullOrEmpty()) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = macodesSwitchColors(),
        )
    }
}

/** Single-selection dialog: Click to apply the selection and close the dialog. */
@Composable
private fun SettingsChoiceDialog(
    title: String,
    options: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                options.forEachIndexed { i, label ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable {
                                onSelect(i)
                                onDismiss()
                            }.padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        RadioButton(selected = i == selected, onClick = null)
                        Text(label, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(LocalAppStrings.current.settingsClose) } },
    )
}
