package dev.termish.ui

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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
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
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import dev.termish.APP_VERSION
import dev.termish.data.AppSettings
import dev.termish.data.HostRepository
import dev.termish.data.SECRET_SERVICE
import dev.termish.data.SecretStore
import dev.termish.data.ThemeMode
import dev.termish.data.asrKeyAccount
import dev.termish.notify.NotificationPermissionController
import dev.termish.notify.NotificationPermissionState
import dev.termish.ui.theme.TerminalThemes
import dev.termish.util.BackgroundProtectionState
import dev.termish.util.TermLog
import dev.termish.util.openApplicationSettings

// ---- About External Links (Official Website / Contact Email) ----
private const val WEBSITE_URL = "https://termish.dev"
private const val WEBSITE_HOST = "termish.dev"
private const val CONTACT_EMAIL = "ttermish@gmail.com"
internal const val PRIVACY_POLICY_URL = "https://termish.dev/privacy"

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
            ),
        )

    Scaffold(
        topBar = { TermishLargeHeader(title = s.settingsTitle) },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()),
        ) {
            // General Group (Original Appearance + Original General Merged): App Theme / Language / Haptic Feedback
            SettingsGroup(s.settingsGroupGeneral) {
                SettingsOptionItem(s.settingsAppTheme, themeModeLabel(theme, s)) { showThemeDialog = true }
                HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                SettingsOptionItem(s.settingsLanguage, languageLabel(language, s)) { showLanguageDialog = true }
                HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
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
                ) { onOpenSub(SettingsSubPage.NOTIFICATION) }
                HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                SettingsOptionItem(
                    s.settingsDiagnostics,
                    if (TermLog.diagnosticsEnabled) s.settingsOn else s.settingsOff,
                ) { onOpenSub(SettingsSubPage.DIAGNOSTICS) }
                HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                SettingsOptionItem(
                    s.settingsSnippets,
                    "",
                ) { onOpenSub(SettingsSubPage.SNIPPETS) }
                HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                SettingsOptionItem(
                    s.voice.settingsVoice,
                    if (voiceInputEnabled) {
                        s.voice.providerCount(asrProviders.count { it.enabled })
                    } else {
                        s.settingsOff
                    },
                ) { onOpenSub(SettingsSubPage.VOICE) }
                HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                SettingsSwitchItem(s.settingsHaptics, haptics) {
                    haptics = it
                    persist()
                }
            }

            SettingsGroup(s.settingsGroupTerminal) {
                SettingsOptionItem(
                    s.settingsTerminalPalette,
                    TerminalThemes.ALL.getOrElse(terminalThemeIndex) { TerminalThemes.ALL[0] }.name,
                ) { onOpenSub(SettingsSubPage.TERMINAL) }
                HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                SettingsOptionItem(s.settingsTerminalType, terminalType) {
                    showTerminalTypeDialog = true
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
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
                HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
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
                HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                SettingsSwitchItem(s.settingsOsc52Clipboard, osc52Clipboard) {
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
                HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                SettingsSwitchItem(s.settingsAutoReconnect, autoReconnect) {
                    autoReconnect = it
                    persist()
                }
                if (backgroundProtectionState != BackgroundProtectionState.NOT_APPLICABLE) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                    SettingsOptionItem(
                        s.permissions.backgroundProtection,
                        if (backgroundProtectionState == BackgroundProtectionState.SYSTEM_EXEMPT) {
                            s.permissions.backgroundProtectionExempt
                        } else {
                            s.permissions.backgroundProtectionManaged
                        },
                        onClick = ::openApplicationSettings,
                    )
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                SettingsSwitchItem(s.settingsVerifyHostKey, verifyHostKey) {
                    verifyHostKey = it
                    persist()
                }
            }

            // About: Set a fixed position at the bottom of the settings page (industry convention: Termius, Blink, and others all place this at the bottom)
            // Version + official website/documentation/repository links). Tap to open in a browser; uses LocalUriHandler.
            SettingsGroup(s.settingsGroupAbout) {
                SettingsOptionItem(s.settingsVersion, APP_VERSION) {}
                HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                SettingsOptionItem(s.settingsWebsite, WEBSITE_HOST) { uriHandler.openUri(WEBSITE_URL) }
                HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                SettingsOptionItem(s.permissions.privacyPolicy, "") { onOpenSub(SettingsSubPage.PRIVACY) }
                HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                SettingsOptionItem(s.settingsContact, CONTACT_EMAIL) { uriHandler.openUri("mailto:$CONTACT_EMAIL") }
            }

            Spacer(Modifier.height(24.dp))
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

/** Card grouping: outer spacing + corner radius + group title. */
@Composable
private fun SettingsGroup(
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Text(
        title,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
        modifier = Modifier.padding(start = 28.dp, top = 16.dp, bottom = 6.dp),
    )
    Card(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)),
    ) {
        Column(content = content)
    }
}

/** Rows containing option settings: Header + current value + right arrow. */
@Composable
private fun SettingsOptionItem(
    title: String,
    value: String,
    onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f),
            modifier = Modifier.weight(1f),
        )
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
        )
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
        )
    }
}

/** For numeric settings rows: Header + current value, followed by a slider. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsSliderItem(
    title: String,
    valueText: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    enabled: Boolean = true,
) {
    // top 14dp aligns with other rows; bottom 7dp compensates for the space below the thumb inside the Slider
    // (Center the 18dp thumb within the 32dp area, with its bottom edge approximately 7dp from the bottom of the Slider) ——
    // Visually, the spacing from the bottom edge of “thumb” to the next row should match the spacing from the bottom of text on other rows to the next row.
    Column(Modifier.fillMaxWidth().padding(start = 16.dp, top = 14.dp, end = 16.dp, bottom = 7.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                title,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 0.85f else 0.4f),
            )
            Text(
                valueText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 0.5f else 0.3f),
            )
        }
        // Rounded-end thin-track slider (the default vertical thumb looks too out of place in the list);
        // height(32.dp): the default 48dp touch target would make rows ~96dp tall, far taller than other rows—
        // Set the height of the slider on the settings page to be close to the line height of the OptionItem (while still allowing it to be dragged).
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
                            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
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
                            inactiveTrackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f),
                        ),
                )
            },
        )
    }
}

/** Switch-type settings rows. */
@Composable
private fun SettingsSwitchItem(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    // Vertical 8dp: Line height = Switch 32dp + 16 = 48dp, consistent with OptionItem (text 20 + 28)——
    // Otherwise, the row spacing for "Switch" is 12dp larger than other rows (user feedback indicates excessive vertical spacing).
    Row(
        Modifier.fillMaxWidth().clickable { onCheckedChange(!checked) }.padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f),
            modifier = Modifier.weight(1f),
        )
        Switch(checked, onCheckedChange)
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
