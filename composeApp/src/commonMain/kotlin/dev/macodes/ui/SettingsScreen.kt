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
import androidx.compose.foundation.layout.navigationBarsPadding
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
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.FormatSize
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.ScreenshotMonitor
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Vibration
import androidx.compose.material.icons.filled.ViewColumn
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchColors
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
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
import dev.macodes.data.ScreenProtectionMode
import dev.macodes.data.SecretStore
import dev.macodes.data.ThemeMode
import dev.macodes.data.asrKeyAccount
import dev.macodes.notify.NotificationPermissionController
import dev.macodes.notify.NotificationPermissionState
import dev.macodes.ui.theme.Alphas
import dev.macodes.ui.theme.Corners
import dev.macodes.ui.theme.Sizes
import dev.macodes.ui.theme.Spacing
import dev.macodes.ui.theme.TerminalThemes
import dev.macodes.util.BackgroundProtectionState
import dev.macodes.util.TermLog
import dev.macodes.util.formatByteSize
import dev.macodes.util.getAppCacheStats
import dev.macodes.util.openApplicationSettings

private const val WEBSITE_URL = "https://github.com/alfa-reza/Macodes"
private const val WEBSITE_HOST = "github.com/alfa-reza/Macodes"
private const val CONTACT_EMAIL = "research@alfareza.my.id"
internal const val PRIVACY_POLICY_URL = "https://github.com/alfa-reza/Macodes/blob/main/docs/appstore/privacy-policy.md"

private data class SettingsSheetChoice(
    val label: String,
    val description: String? = null,
)

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

private fun screenProtectionLabel(
    mode: ScreenProtectionMode,
    extra: SettingsExtraStrings,
): String =
    when (mode) {
        ScreenProtectionMode.FULL -> extra.screenProtectionFull
        ScreenProtectionMode.PARTIAL -> extra.screenProtectionPartial
        ScreenProtectionMode.OFF -> extra.screenProtectionOff
    }

@Composable
fun SettingsScreen(
    settings: AppSettings,
    /** Any modifications are called back immediately (saved on change; no Save button). */
    onChange: (AppSettings) -> Unit,
    repository: HostRepository = HostRepository(),
    notificationPermissionState: NotificationPermissionState,
    notificationPermissionController: NotificationPermissionController,
    onNotificationPermissionStateChange: (NotificationPermissionState) -> Unit,
    backgroundProtectionState: BackgroundProtectionState,
    /** Currently open subpage (managed by AppRoot: back link + bottom tabs hidden). */
    subPage: SettingsSubPage? = null,
    onOpenSub: (SettingsSubPage?) -> Unit = {},
    onBack: (() -> Unit)? = null,
) {
    val s = LocalAppStrings.current
    val extra = s.settingsExtra
    val uriHandler = LocalUriHandler.current

    var showThemeSheet by remember { mutableStateOf(false) }
    var showLanguageSheet by remember { mutableStateOf(false) }
    var showTerminalTypeSheet by remember { mutableStateOf(false) }
    var showScreenProtectionSheet by remember { mutableStateOf(false) }

    val cacheStats = remember(subPage) { getAppCacheStats() }
    val historyCount =
        remember(subPage) {
            repository.listQuickConnectHistory().size + repository.listSessionLogs().size
        }

    Scaffold(
        topBar = { MacodesLargeHeader(title = s.settingsTitle, onBack = onBack) },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
        ) {
            // 1. General
            SettingsGroup(s.settingsGroupGeneral) {
                SettingsOptionItem(
                    title = s.settingsAppTheme,
                    value = themeModeLabel(settings.theme, s),
                    icon = Icons.Filled.Palette,
                ) { showThemeSheet = true }
                SettingsDivider()
                SettingsOptionItem(
                    title = s.settingsLanguage,
                    value = languageLabel(settings.language, s),
                    icon = Icons.Filled.Language,
                ) { showLanguageSheet = true }
                SettingsDivider()
                SettingsOptionItem(
                    title = s.settingsNotifications,
                    value =
                        if (
                            settings.notificationEnabled &&
                            notificationPermissionState == NotificationPermissionState.GRANTED
                        ) {
                            s.settingsOn
                        } else {
                            s.settingsOff
                        },
                    icon = Icons.Filled.Notifications,
                ) { onOpenSub(SettingsSubPage.NOTIFICATION) }
                SettingsDivider()
                SettingsOptionItem(
                    title = s.voice.settingsVoice,
                    value =
                        if (settings.voiceInputEnabled) {
                            s.voice.providerCount(settings.asrProviders.count { it.enabled })
                        } else {
                            s.settingsOff
                        },
                    icon = Icons.Filled.Mic,
                ) { onOpenSub(SettingsSubPage.VOICE) }
            }

            // 2. Terminal
            SettingsGroup(s.settingsGroupTerminal) {
                SettingsOptionItem(
                    title = s.settingsTerminalPalette,
                    value = TerminalThemes.ALL.getOrElse(settings.terminalThemeIndex) { TerminalThemes.ALL[0] }.name,
                    icon = Icons.Filled.Palette,
                ) { onOpenSub(SettingsSubPage.TERMINAL) }
                SettingsDivider()
                SettingsOptionItem(
                    title = s.settingsTerminalType,
                    value = settings.terminalType,
                    icon = Icons.Filled.Terminal,
                ) { showTerminalTypeSheet = true }
                SettingsDivider()
                SettingsSliderItem(
                    title = s.settingsTerminalCols,
                    valueText =
                        if (settings.terminalTargetCols < 1) {
                            s.settingsManualFontSize
                        } else {
                            settings.terminalTargetCols.toString()
                        },
                    value = settings.terminalTargetCols.toFloat(),
                    onValueChange = { onChange(settings.copy(terminalTargetCols = it.toInt())) },
                    valueRange = 0f..160f,
                    icon = Icons.Filled.ViewColumn,
                )
                SettingsDivider()
                val fontEnabled = settings.terminalTargetCols < 1
                SettingsSliderItem(
                    title = s.settingsTerminalFontSize,
                    valueText = "${settings.terminalFontSize}sp",
                    subtitle = if (!fontEnabled) extra.fontSizeDisabledByCols else null,
                    value = settings.terminalFontSize.toFloat(),
                    onValueChange = { onChange(settings.copy(terminalFontSize = it.toInt())) },
                    valueRange = 6f..32f,
                    enabled = fontEnabled,
                    icon = Icons.Filled.FormatSize,
                )
                SettingsDivider()
                SettingsSwitchItem(
                    title = extra.cursorBlinkTitle,
                    subtitle = extra.cursorBlinkHint,
                    checked = settings.cursorBlink,
                    icon = Icons.Filled.Terminal,
                ) { onChange(settings.copy(cursorBlink = it)) }
                SettingsDivider()
                SettingsSwitchItem(
                    title = s.settingsHaptics,
                    checked = settings.hapticFeedback,
                    icon = Icons.Filled.Vibration,
                ) { onChange(settings.copy(hapticFeedback = it)) }
                SettingsDivider()
                SettingsSwitchItem(
                    title = s.settingsOsc52Clipboard,
                    checked = settings.osc52Clipboard,
                    icon = Icons.Filled.ContentPaste,
                ) { onChange(settings.copy(osc52Clipboard = it)) }
            }

            // 3. Security & Privacy
            SettingsGroup(extra.groupSecurity) {
                SettingsOptionItem(
                    title = extra.screenProtectionTitle,
                    value = screenProtectionLabel(settings.screenProtectionMode, extra),
                    icon = Icons.Filled.ScreenshotMonitor,
                ) { showScreenProtectionSheet = true }
                SettingsDivider()
                SettingsSwitchItem(
                    title = s.settingsVerifyHostKey,
                    checked = settings.verifyHostKeyOnFirstUse,
                    icon = Icons.Filled.Security,
                ) { onChange(settings.copy(verifyHostKeyOnFirstUse = it)) }
                SettingsDivider()
                SettingsOptionItem(
                    title = s.permissions.privacyPolicy,
                    value = "",
                    icon = Icons.Filled.Lock,
                ) { onOpenSub(SettingsSubPage.PRIVACY) }
            }

            // 4. Session & Connections
            SettingsGroup(s.settingsGroupSession) {
                SettingsSliderItem(
                    title = s.settingsKeepalive,
                    valueText =
                        if (settings.keepaliveSeconds < 1) {
                            s.settingsKeepaliveOff
                        } else {
                            "${settings.keepaliveSeconds}s"
                        },
                    value = settings.keepaliveSeconds.toFloat(),
                    onValueChange = { onChange(settings.copy(keepaliveSeconds = it.toInt())) },
                    valueRange = 0f..300f,
                    icon = Icons.Filled.Timer,
                )
                SettingsDivider()
                SettingsSwitchItem(
                    title = s.settingsAutoReconnect,
                    checked = settings.autoReconnect,
                    icon = Icons.Filled.Autorenew,
                ) { onChange(settings.copy(autoReconnect = it)) }
                if (backgroundProtectionState != BackgroundProtectionState.NOT_APPLICABLE) {
                    SettingsDivider()
                    SettingsOptionItem(
                        title = s.permissions.backgroundProtection,
                        value =
                            if (backgroundProtectionState == BackgroundProtectionState.SYSTEM_EXEMPT) {
                                s.permissions.backgroundProtectionExempt
                            } else {
                                s.permissions.backgroundProtectionManaged
                            },
                        icon = Icons.Filled.BatteryChargingFull,
                        onClick = ::openApplicationSettings,
                    )
                }
                SettingsDivider()
                SettingsSwitchItem(
                    title = extra.aiAgentTitle,
                    subtitle = extra.aiAgentHint,
                    checked = settings.aiAgentEnabled,
                    icon = Icons.Filled.SmartToy,
                ) { onChange(settings.copy(aiAgentEnabled = it)) }
                SettingsDivider()
                SettingsSwitchItem(
                    title = s.quickConnect.settingsShowSessionPreview,
                    checked = settings.showConnectionsSessionPreview,
                    icon = Icons.Filled.Visibility,
                ) { onChange(settings.copy(showConnectionsSessionPreview = it)) }
                SettingsDivider()
                SettingsSwitchItem(
                    title = s.quickConnect.settingsShowQuickHistory,
                    checked = settings.showQuickConnectHistory,
                    icon = Icons.Filled.History,
                ) { onChange(settings.copy(showQuickConnectHistory = it)) }
                SettingsDivider()
                SettingsSwitchItem(
                    title = s.quickConnect.settingsShowSftpShortcut,
                    checked = settings.showConnectionsSftp,
                    icon = Icons.Filled.FolderOpen,
                ) { onChange(settings.copy(showConnectionsSftp = it)) }
            }

            // 5. Storage & Diagnostics
            SettingsGroup(extra.groupStorage) {
                SettingsOptionItem(
                    title = extra.storageAndHistoryTitle,
                    value = extra.storageAndHistorySubtitle(formatByteSize(cacheStats.totalBytes), historyCount),
                    icon = Icons.Filled.CleaningServices,
                ) { onOpenSub(SettingsSubPage.STORAGE) }
                SettingsDivider()
                SettingsOptionItem(
                    title = s.settingsDiagnostics,
                    value = if (TermLog.diagnosticsEnabled) s.settingsOn else s.settingsOff,
                    icon = Icons.Filled.BugReport,
                ) { onOpenSub(SettingsSubPage.DIAGNOSTICS) }
            }

            // 6. About
            SettingsGroup(extra.groupAbout) {
                SettingsOptionItem(
                    title = extra.version,
                    value = APP_VERSION,
                    showArrow = false,
                    icon = Icons.Filled.Info,
                ) {}
                SettingsDivider()
                SettingsOptionItem(
                    title = extra.website,
                    value = WEBSITE_HOST,
                    icon = Icons.Filled.Public,
                ) { uriHandler.openUri(WEBSITE_URL) }
                SettingsDivider()
                SettingsOptionItem(
                    title = extra.contact,
                    value = CONTACT_EMAIL,
                    icon = Icons.Filled.Email,
                ) { uriHandler.openUri("mailto:$CONTACT_EMAIL") }
            }

            Spacer(Modifier.height(Spacing.Xl))
        }
    }

    if (showThemeSheet) {
        SettingsChoiceBottomSheet(
            title = s.settingsAppTheme,
            options = ThemeMode.entries.map { SettingsSheetChoice(themeModeLabel(it, s)) },
            selected = ThemeMode.entries.indexOf(settings.theme),
            onSelect = { onChange(settings.copy(theme = ThemeMode.entries[it])) },
            onDismiss = { showThemeSheet = false },
        )
    }

    if (showLanguageSheet) {
        val codes = listOf("", "zh", "en")
        val options =
            listOf(
                SettingsSheetChoice(s.settingsLanguageSystem),
                SettingsSheetChoice(s.languageZh),
                SettingsSheetChoice(s.languageEn),
            )
        SettingsChoiceBottomSheet(
            title = s.settingsLanguage,
            options = options,
            selected = codes.indexOf(settings.language).coerceAtLeast(0),
            onSelect = { onChange(settings.copy(language = codes[it])) },
            onDismiss = { showLanguageSheet = false },
        )
    }

    if (showTerminalTypeSheet) {
        val options = listOf("xterm-256color", "xterm", "vt100", "linux")
        SettingsChoiceBottomSheet(
            title = s.settingsTerminalType,
            options = options.map { SettingsSheetChoice(it) },
            selected = options.indexOf(settings.terminalType).coerceAtLeast(0),
            onSelect = { onChange(settings.copy(terminalType = options[it])) },
            onDismiss = { showTerminalTypeSheet = false },
        )
    }

    if (showScreenProtectionSheet) {
        val options =
            listOf(
                SettingsSheetChoice(extra.screenProtectionFull, extra.screenProtectionFullDesc),
                SettingsSheetChoice(extra.screenProtectionPartial, extra.screenProtectionPartialDesc),
                SettingsSheetChoice(extra.screenProtectionOff, extra.screenProtectionOffDesc),
            )
        SettingsChoiceBottomSheet(
            title = extra.screenProtectionTitle,
            options = options,
            selected = ScreenProtectionMode.entries.indexOf(settings.screenProtectionMode),
            onSelect = {
                onChange(settings.copy(screenProtectionMode = ScreenProtectionMode.entries[it]))
            },
            onDismiss = { showScreenProtectionSheet = false },
        )
    }

    // Secondary screen overlays
    AnimatedVisibility(
        visible = subPage == SettingsSubPage.TERMINAL,
        enter = slideInHorizontally(initialOffsetX = { it }) + fadeIn(animationSpec = tween(240)),
        exit = slideOutHorizontally(targetOffsetX = { it }) + fadeOut(animationSpec = tween(200)),
    ) {
        SettingsTerminalScreen(
            currentThemeIndex = settings.terminalThemeIndex,
            currentFontId = settings.terminalFontId,
            fontSizeSp = settings.terminalFontSize,
            cursorBlink = settings.cursorBlink,
            onChangeTheme = { onChange(settings.copy(terminalThemeIndex = it)) },
            onChangeFont = { fontId -> onChange(settings.copy(terminalFontId = fontId)) },
            onBack = { onOpenSub(null) },
        )
    }

    AnimatedVisibility(
        visible = subPage == SettingsSubPage.NOTIFICATION,
        enter = slideInHorizontally(initialOffsetX = { it }) + fadeIn(animationSpec = tween(240)),
        exit = slideOutHorizontally(targetOffsetX = { it }) + fadeOut(animationSpec = tween(200)),
    ) {
        SettingsNotificationScreen(
            enabled = settings.notificationEnabled,
            disabledEvents = settings.notificationDisabledEvents,
            permissionState = notificationPermissionState,
            permissionController = notificationPermissionController,
            onPermissionStateChange = onNotificationPermissionStateChange,
            onChangeEnabled = { onChange(settings.copy(notificationEnabled = it)) },
            onChangeEvent = { id, disable ->
                val updated =
                    if (disable) {
                        settings.notificationDisabledEvents + id
                    } else {
                        settings.notificationDisabledEvents - id
                    }
                onChange(settings.copy(notificationDisabledEvents = updated))
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

    AnimatedVisibility(
        visible = subPage == SettingsSubPage.STORAGE,
        enter = slideInHorizontally(initialOffsetX = { it }) + fadeIn(animationSpec = tween(240)),
        exit = slideOutHorizontally(targetOffsetX = { it }) + fadeOut(animationSpec = tween(200)),
    ) {
        SettingsStorageScreen(
            repository = repository,
            onBack = { onOpenSub(null) },
        )
    }

    AnimatedVisibility(
        visible = subPage == SettingsSubPage.VOICE,
        enter = slideInHorizontally(initialOffsetX = { it }) + fadeIn(animationSpec = tween(240)),
        exit = slideOutHorizontally(targetOffsetX = { it }) + fadeOut(animationSpec = tween(200)),
    ) {
        SettingsVoiceScreen(
            enabled = settings.voiceInputEnabled,
            providers = settings.asrProviders,
            apiKeyOf = { p -> SecretStore.get(SECRET_SERVICE, asrKeyAccount(p.id)) ?: "" },
            onChangeEnabled = { onChange(settings.copy(voiceInputEnabled = it)) },
            onAddProvider = { p, key ->
                SecretStore.set(SECRET_SERVICE, asrKeyAccount(p.id), key)
                onChange(settings.copy(asrProviders = settings.asrProviders + p))
            },
            onUpdateProvider = { p, key ->
                if (key.isNotBlank()) {
                    SecretStore.set(SECRET_SERVICE, asrKeyAccount(p.id), key)
                }
                onChange(
                    settings.copy(
                        asrProviders = settings.asrProviders.map { if (it.id == p.id) p else it },
                    ),
                )
            },
            onDeleteProvider = { p ->
                SecretStore.delete(SECRET_SERVICE, asrKeyAccount(p.id))
                onChange(settings.copy(asrProviders = settings.asrProviders.filter { it.id != p.id }))
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
}

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
internal fun SettingsDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(horizontal = Spacing.Lg),
        thickness = Sizes.BorderThin,
        color = MaterialTheme.colorScheme.outlineVariant,
    )
}

@Composable
private fun SettingsLeadingIconBadge(
    icon: ImageVector,
    enabled: Boolean = true,
) {
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
            tint =
                if (enabled) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                },
            modifier = Modifier.size(Sizes.IconSmall),
        )
    }
}

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
            .padding(horizontal = Spacing.Lg, vertical = Spacing.Md),
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsSliderItem(
    title: String,
    valueText: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    subtitle: String? = null,
    enabled: Boolean = true,
    icon: ImageVector? = null,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(
                start = Spacing.Lg,
                top = Spacing.Md,
                end = Spacing.Lg,
                bottom = Spacing.Sm,
            ),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.Md),
        ) {
            if (icon != null) {
                SettingsLeadingIconBadge(icon, enabled = enabled)
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 1f else 0.45f),
                )
                if (!subtitle.isNullOrEmpty()) {
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (enabled) 1f else 0.65f),
                    )
                }
            }
            Text(
                valueText,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (enabled) 1f else 0.45f),
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
                    Modifier
                        .size(Sizes.IconSmall)
                        .clip(CircleShape)
                        .background(
                            if (enabled) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = Alphas.BORDER_ACCENT)
                            },
                        ),
                )
            },
            track = { state ->
                SliderDefaults.Track(
                    sliderState = state,
                    modifier = Modifier.height(Spacing.Xs),
                    colors =
                        SliderDefaults.colors(
                            activeTrackColor =
                                MaterialTheme.colorScheme.primary.copy(
                                    alpha = if (enabled) 1f else Alphas.BORDER_ACCENT,
                                ),
                            inactiveTrackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                        ),
                )
            },
        )
    }
}

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
            .padding(horizontal = Spacing.Lg, vertical = Spacing.Sm),
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsChoiceBottomSheet(
    title: String,
    options: List<SettingsSheetChoice>,
    selected: Int,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = Spacing.Lg, vertical = Spacing.Sm),
        ) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(bottom = Spacing.Md),
            )
            options.forEachIndexed { i, item ->
                val isSelected = i == selected
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(Corners.Md))
                        .background(
                            if (isSelected) {
                                MaterialTheme.colorScheme.primary.copy(alpha = Alphas.SUBTLE)
                            } else {
                                Color.Transparent
                            },
                        ).clickable {
                            onSelect(i)
                            onDismiss()
                        }.padding(horizontal = Spacing.Md, vertical = Spacing.Md),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.Md),
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Text(
                            item.label,
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                            color =
                                if (isSelected) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                },
                        )
                        if (!item.description.isNullOrBlank()) {
                            Text(
                                item.description,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    if (isSelected) {
                        Icon(
                            Icons.Filled.Check,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(Sizes.IconSmall),
                        )
                    }
                }
            }
            Spacer(Modifier.height(Spacing.Lg))
        }
    }
}
