package dev.macodes.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Power
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.macodes.data.ConnectionMode
import dev.macodes.data.QuickConnectParseError
import dev.macodes.data.QuickConnectParser
import dev.macodes.data.QuickConnectStep
import dev.macodes.data.QuickConnectTarget
import dev.macodes.ssh.SshErrorCode
import dev.macodes.ui.theme.Corners
import dev.macodes.ui.theme.Sizes
import dev.macodes.ui.theme.Spacing

enum class QuickConnectFailureKind {
    AUTH_FAILED,
    PASSWORD_UNSUPPORTED,
    REFUSED,
    TIMEOUT,
    UNREACHABLE,
    CONNECTION_RESET,
    NEGOTIATION_FAILED,
    HOST_KEY_REJECTED,
    MOSH_BOOTSTRAP,
    MOSH_UDP,
}

data class QuickConnectFailureSummary(
    val kind: QuickConnectFailureKind,
    val headline: String,
    val hint: String,
    val rawDetail: String? = null,
)

/** Mutable wizard session state displayed inside the single dynamic Quick Connect modal. */
data class QuickConnectWizardState(
    val target: QuickConnectTarget,
    val steps: List<QuickConnectStep>,
    val stepIndex: Int = 0,
    val protocol: ConnectionMode = target.protocol ?: ConnectionMode.SSH,
    val portInput: String = target.sshPort.toString(),
    val moshUdpPortInput: String =
        target.moshUdpPort
            .takeIf { it > 0 }
            ?.toString()
            .orEmpty(),
    val moshCommandInput: String = target.moshServerCommand,
    val usernameInput: String = target.username.orEmpty(),
    val passwordInput: String = "",
    val connecting: Boolean = false,
    val statusText: String? = null,
    val errorMessage: String? = null,
    val failureSummary: QuickConnectFailureSummary? = null,
) {
    val currentStep: QuickConnectStep
        get() = steps[stepIndex.coerceIn(0, steps.lastIndex)]
    val canGoBack: Boolean
        get() = !connecting && stepIndex > 0
}

internal fun createWizardState(
    target: QuickConnectTarget,
    forceProtocolStep: Boolean = false,
): QuickConnectWizardState {
    val baseSteps = QuickConnectParser.stepsFor(target)
    val steps =
        if (forceProtocolStep && QuickConnectStep.PROTOCOL !in baseSteps) {
            listOf(QuickConnectStep.PROTOCOL) + baseSteps
        } else {
            baseSteps
        }
    return QuickConnectWizardState(
        target = target,
        steps = steps,
    )
}

internal fun formatQuickConnectParseError(
    error: QuickConnectParseError,
    s: QuickConnectStrings,
): String =
    when (error) {
        QuickConnectParseError.EmptyInput -> s.errorEmpty
        QuickConnectParseError.ShellMetacharacters -> s.errorShellSyntax
        is QuickConnectParseError.MissingFlagValue -> s.errorMissingFlagValue(error.flag)
        is QuickConnectParseError.UnsupportedFlag -> s.errorUnsupportedFlag(error.flag)
        is QuickConnectParseError.InvalidPort -> s.errorInvalidPort(error.value)
        QuickConnectParseError.InvalidHost -> s.errorInvalidHost
        QuickConnectParseError.UnexpectedArguments -> s.errorUnexpectedArgs
    }

internal fun buildQuickConnectFailureSummary(
    rawMessage: String?,
    protocol: ConnectionMode,
    s: QuickConnectStrings,
    errorCode: SshErrorCode? = null,
): QuickConnectFailureSummary {
    val msg = rawMessage.orEmpty().trim()
    val lower = msg.lowercase()
    val detail = msg.takeIf { it.isNotEmpty() }?.take(180)
    val kind =
        when (errorCode) {
            SshErrorCode.PASSWORD_AUTH_UNSUPPORTED -> QuickConnectFailureKind.PASSWORD_UNSUPPORTED
            SshErrorCode.AUTH_FAILED,
            SshErrorCode.KEY_PASSPHRASE_CANCELLED,
            SshErrorCode.KEY_LOAD_FAILED,
            -> QuickConnectFailureKind.AUTH_FAILED
            null ->
                when {
                    "password authentication is not supported" in lower -> QuickConnectFailureKind.PASSWORD_UNSUPPORTED
                    "hostkey" in lower || "host key" in lower -> QuickConnectFailureKind.HOST_KEY_REJECTED
                    "algorithm" in lower ||
                        "negotiate" in lower ||
                        "no matching" in lower ||
                        "could not agree" in lower ||
                        "kex" in lower -> QuickConnectFailureKind.NEGOTIATION_FAILED
                    "connection reset" in lower ||
                        "closed by remote" in lower ||
                        "unexpectedly closed" in lower ||
                        "broken pipe" in lower ||
                        "eof" in lower -> QuickConnectFailureKind.CONNECTION_RESET
                    "authentication" in lower ||
                        ("auth" in lower && "exhausted" in lower) ||
                        "no more authentication methods" in lower ||
                        "认证失败" in msg -> QuickConnectFailureKind.AUTH_FAILED
                    protocol == ConnectionMode.MOSH && "udp" in lower -> QuickConnectFailureKind.MOSH_UDP
                    "timed out" in lower || "timeout" in lower -> QuickConnectFailureKind.TIMEOUT
                    "connection refused" in lower || "econnrefused" in lower -> QuickConnectFailureKind.REFUSED
                    "unknownhost" in lower ||
                        "unreachable" in lower ||
                        "no route to host" in lower ||
                        "nodename nor servname" in lower ||
                        "temporary failure in name resolution" in lower -> QuickConnectFailureKind.UNREACHABLE
                    protocol == ConnectionMode.MOSH && msg.isNotEmpty() -> QuickConnectFailureKind.MOSH_BOOTSTRAP
                    else -> QuickConnectFailureKind.AUTH_FAILED
                }
        }
    return when (kind) {
        QuickConnectFailureKind.AUTH_FAILED ->
            QuickConnectFailureSummary(kind, s.errorAuthFailed, s.hintAuthFailed, detail)
        QuickConnectFailureKind.PASSWORD_UNSUPPORTED ->
            QuickConnectFailureSummary(kind, s.errorPasswordNotSupported, s.hintPasswordUnsupported, detail)
        QuickConnectFailureKind.REFUSED ->
            QuickConnectFailureSummary(kind, s.errorRefused, s.hintRefused, detail)
        QuickConnectFailureKind.TIMEOUT ->
            QuickConnectFailureSummary(kind, s.errorTimeout, s.hintTimeout, detail)
        QuickConnectFailureKind.UNREACHABLE ->
            QuickConnectFailureSummary(kind, s.errorUnreachable, s.hintUnreachable, detail)
        QuickConnectFailureKind.CONNECTION_RESET ->
            QuickConnectFailureSummary(kind, s.errorConnectionReset, s.hintConnectionReset, detail)
        QuickConnectFailureKind.NEGOTIATION_FAILED ->
            QuickConnectFailureSummary(kind, s.errorNegotiationFailed, s.hintNegotiationFailed, detail)
        QuickConnectFailureKind.HOST_KEY_REJECTED ->
            QuickConnectFailureSummary(kind, s.errorHostKeyRejected, s.hintHostKeyRejected, detail)
        QuickConnectFailureKind.MOSH_BOOTSTRAP ->
            QuickConnectFailureSummary(
                kind,
                if (msg.isNotEmpty()) s.errorMoshBootstrap(msg) else s.errorMoshUdp,
                s.hintMoshBootstrap,
                detail,
            )
        QuickConnectFailureKind.MOSH_UDP ->
            QuickConnectFailureSummary(kind, s.errorMoshUdp, s.hintMoshUdp, detail)
    }
}

internal fun formatQuickConnectConnectionError(
    rawMessage: String?,
    protocol: ConnectionMode,
    s: QuickConnectStrings,
    errorCode: SshErrorCode? = null,
): String = buildQuickConnectFailureSummary(rawMessage, protocol, s, errorCode).headline

/** Pure step validation & advancement logic (returns null error on Continue, or triggers connect). */
internal fun advanceQuickConnectWizard(
    state: QuickConnectWizardState,
    s: QuickConnectStrings,
): Pair<QuickConnectWizardState, Boolean> =
    when (state.currentStep) {
        QuickConnectStep.PROTOCOL -> {
            val port = state.portInput.trim().toIntOrNull()
            if (port == null || port !in 1..65535) {
                state.copy(errorMessage = s.errorInvalidPort(state.portInput.trim())) to false
            } else if (state.stepIndex < state.steps.lastIndex) {
                state.copy(stepIndex = state.stepIndex + 1, errorMessage = null) to false
            } else {
                state.copy(errorMessage = null) to true
            }
        }
        QuickConnectStep.USERNAME -> {
            val user = state.usernameInput.trim()
            if (user.isEmpty()) {
                state.copy(errorMessage = s.errorMissingUsername) to false
            } else if (state.stepIndex < state.steps.lastIndex) {
                state.copy(stepIndex = state.stepIndex + 1, errorMessage = null) to false
            } else {
                state to true
            }
        }
        QuickConnectStep.PASSWORD -> {
            if (state.passwordInput.isEmpty()) {
                state.copy(errorMessage = s.errorMissingPassword) to false
            } else {
                state.copy(errorMessage = null) to true
            }
        }
    }

internal fun retreatQuickConnectWizard(state: QuickConnectWizardState): QuickConnectWizardState =
    if (state.stepIndex > 0 && !state.connecting) {
        state.copy(stepIndex = state.stepIndex - 1, errorMessage = null)
    } else {
        state
    }

@Composable
fun QuickConnectWizardDialog(
    state: QuickConnectWizardState,
    onStateChange: (QuickConnectWizardState) -> Unit,
    onConnect: (QuickConnectWizardState) -> Unit,
    onCancel: () -> Unit,
) {
    Dialog(
        onDismissRequest = onCancel,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.55f))
                    .clickable(onClick = onCancel),
            contentAlignment = Alignment.BottomCenter,
        ) {
            QuickConnectWizardSheet(
                state = state,
                onStateChange = onStateChange,
                onConnect = onConnect,
                onCancel = onCancel,
                modifier =
                    Modifier
                        .clickable(enabled = false, onClick = {})
                        .navigationBarsPadding(),
            )
        }
    }
}

@Composable
internal fun QuickConnectWizardSheet(
    state: QuickConnectWizardState,
    onStateChange: (QuickConnectWizardState) -> Unit,
    onConnect: (QuickConnectWizardState) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    initialEditingPortFor: ConnectionMode? = null,
) {
    val s = LocalAppStrings.current.quickConnect
    val stepFocusRequester = remember { FocusRequester() }
    var editingPortFor by remember(state.currentStep, initialEditingPortFor) {
        mutableStateOf<ConnectionMode?>(initialEditingPortFor)
    }

    LaunchedEffect(state.currentStep) {
        if (state.currentStep != QuickConnectStep.PROTOCOL) {
            runCatching { stepFocusRequester.requestFocus() }
        }
    }

    fun submitState(targetState: QuickConnectWizardState = state) {
        if (targetState.connecting) return
        val (nextState, shouldConnect) = advanceQuickConnectWizard(targetState, s)
        onStateChange(nextState)
        if (shouldConnect) onConnect(nextState)
    }

    Card(
        modifier =
            modifier
                .fillMaxWidth()
                .widthIn(max = 520.dp)
                .onPreviewKeyEvent { event ->
                    if (event.type == KeyEventType.KeyDown) {
                        when (event.key) {
                            Key.Escape -> {
                                onCancel()
                                true
                            }
                            Key.Enter, Key.NumPadEnter -> {
                                submitState()
                                true
                            }
                            else -> false
                        }
                    } else {
                        false
                    }
                },
        shape = RoundedCornerShape(topStart = Corners.Xl, topEnd = Corners.Xl),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        border = BorderStroke(Sizes.BorderThin, MaterialTheme.colorScheme.outlineVariant),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.Lg, vertical = Spacing.Md),
            verticalArrangement = Arrangement.spacedBy(Spacing.Md),
        ) {
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier =
                        Modifier
                            .width(36.dp)
                            .height(4.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.outlineVariant),
                )
            }
            val failure = state.failureSummary
            val headerTitle =
                when {
                    failure != null -> s.connectionFailedTitle
                    state.currentStep == QuickConnectStep.PROTOCOL -> state.target.host
                    state.currentStep == QuickConnectStep.USERNAME -> s.stepUsernameTitle
                    else -> s.stepPasswordTitle
                }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = headerTitle,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (failure == null && state.canGoBack) {
                        TextButton(onClick = { onStateChange(retreatQuickConnectWizard(state)) }) {
                            Text(s.backAction)
                        }
                    }
                    TextButton(onClick = onCancel) {
                        Text(s.cancelAction)
                    }
                }
            }

            WizardStepRail(
                steps = state.steps,
                stepIndex = if (failure != null) state.steps.lastIndex else state.stepIndex,
                failed = failure != null,
            )

            val resolvedPort = state.portInput.trim().toIntOrNull() ?: state.target.sshPort
            val endpointLabel = "${state.target.host}:$resolvedPort"

            if (failure != null) {
                val userPrefix =
                    state.usernameInput
                        .trim()
                        .takeIf { it.isNotEmpty() }
                        ?.let { "$it@" }
                        .orEmpty()
                val targetLabel = "${state.protocol.name.lowercase()} · $userPrefix$endpointLabel"
                ConnectionFailedContent(
                    lead = s.connectionFailedLead,
                    targetLabel = targetLabel,
                    summary = failure,
                    tryAgainLabel = s.tryAgainAction,
                    onTryAgain = {
                        onStateChange(
                            state.copy(
                                failureSummary = null,
                                errorMessage = null,
                            ),
                        )
                    },
                )
            } else {
                AnimatedContent(
                    targetState = state.currentStep,
                    transitionSpec = {
                        (fadeIn(tween(150)) + slideInHorizontally(tween(150)) { it / 8 })
                            .togetherWith(fadeOut(tween(110)) + slideOutHorizontally(tween(110)) { -it / 8 })
                    },
                    label = "quick-connect-step",
                ) { step ->
                    Column(verticalArrangement = Arrangement.spacedBy(Spacing.Sm)) {
                        when (step) {
                            QuickConnectStep.PROTOCOL -> {
                                Text(
                                    text = s.stepProtocolSubtitle,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                val displayPort = state.portInput.ifBlank { "22" }
                                ProtocolOptionCard(
                                    label = "SSH",
                                    detail = "${s.portLabel.lowercase()} $displayPort",
                                    badgeColor = MaterialTheme.colorScheme.primaryContainer,
                                    badgeTint = MaterialTheme.colorScheme.primary,
                                    selected = state.protocol == ConnectionMode.SSH,
                                    enabled = !state.connecting,
                                    editingConfig = editingPortFor == ConnectionMode.SSH,
                                    isMosh = false,
                                    portInput = state.portInput,
                                    portLabel = s.sshPortLabel,
                                    moshUdpPortInput = state.moshUdpPortInput,
                                    moshUdpPortLabel = s.moshUdpPortLabel,
                                    moshUdpPortPlaceholder = s.moshUdpPortPlaceholder,
                                    moshCommandInput = state.moshCommandInput,
                                    moshCommandLabel = s.moshCommandLabel,
                                    moshCommandPlaceholder = s.moshCommandPlaceholder,
                                    onToggleEditConfig = {
                                        editingPortFor =
                                            if (editingPortFor == ConnectionMode.SSH) null else ConnectionMode.SSH
                                    },
                                    onPortChange = { v ->
                                        onStateChange(
                                            state.copy(
                                                portInput = v.filter(Char::isDigit).take(5),
                                                errorMessage = null,
                                            ),
                                        )
                                    },
                                    onMoshUdpPortChange = {},
                                    onMoshCommandChange = {},
                                    onConfirmConfig = { editingPortFor = null },
                                    onClick = {
                                        editingPortFor = null
                                        submitState(state.copy(protocol = ConnectionMode.SSH, errorMessage = null))
                                    },
                                )
                                val udpPort = state.moshUdpPortInput.trim().toIntOrNull()
                                val moshCmd = state.moshCommandInput.trim()
                                val moshDetail =
                                    buildString {
                                        append(if (udpPort != null && udpPort > 0) "UDP $udpPort" else "UDP auto")
                                        append(" · SSH $displayPort")
                                        if (moshCmd.isNotEmpty()) {
                                            append(" · $moshCmd")
                                        }
                                    }
                                ProtocolOptionCard(
                                    label = "Mosh",
                                    detail = moshDetail,
                                    badgeColor = MaterialTheme.colorScheme.secondaryContainer,
                                    badgeTint = MaterialTheme.colorScheme.secondary,
                                    selected = state.protocol == ConnectionMode.MOSH,
                                    enabled = !state.connecting,
                                    editingConfig = editingPortFor == ConnectionMode.MOSH,
                                    isMosh = true,
                                    portInput = state.portInput,
                                    portLabel = s.sshPortLabel,
                                    moshUdpPortInput = state.moshUdpPortInput,
                                    moshUdpPortLabel = s.moshUdpPortLabel,
                                    moshUdpPortPlaceholder = s.moshUdpPortPlaceholder,
                                    moshCommandInput = state.moshCommandInput,
                                    moshCommandLabel = s.moshCommandLabel,
                                    moshCommandPlaceholder = s.moshCommandPlaceholder,
                                    onToggleEditConfig = {
                                        editingPortFor =
                                            if (editingPortFor == ConnectionMode.MOSH) null else ConnectionMode.MOSH
                                    },
                                    onPortChange = { v ->
                                        onStateChange(
                                            state.copy(
                                                portInput = v.filter(Char::isDigit).take(5),
                                                errorMessage = null,
                                            ),
                                        )
                                    },
                                    onMoshUdpPortChange = { v ->
                                        onStateChange(
                                            state.copy(
                                                moshUdpPortInput = v.filter(Char::isDigit).take(5),
                                                errorMessage = null,
                                            ),
                                        )
                                    },
                                    onMoshCommandChange = { v ->
                                        onStateChange(
                                            state.copy(
                                                moshCommandInput = v,
                                                errorMessage = null,
                                            ),
                                        )
                                    },
                                    onConfirmConfig = { editingPortFor = null },
                                    onClick = {
                                        editingPortFor = null
                                        submitState(state.copy(protocol = ConnectionMode.MOSH, errorMessage = null))
                                    },
                                )
                            }

                            QuickConnectStep.USERNAME -> {
                                Text(
                                    text = "$endpointLabel · ${s.stepUsernameSubtitle}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                OutlinedTextField(
                                    value = state.usernameInput,
                                    onValueChange = { v ->
                                        onStateChange(state.copy(usernameInput = v, errorMessage = null))
                                    },
                                    label = { Text(s.usernameLabel) },
                                    singleLine = true,
                                    enabled = !state.connecting,
                                    shape = RoundedCornerShape(Corners.Md),
                                    trailingIcon = {
                                        Icon(
                                            Icons.Filled.Person,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    },
                                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                                    keyboardActions = KeyboardActions(onNext = { submitState() }),
                                    modifier = Modifier.fillMaxWidth().focusRequester(stepFocusRequester),
                                )
                            }

                            QuickConnectStep.PASSWORD -> {
                                var passwordVisible by remember { mutableStateOf(false) }
                                val userPrefix =
                                    state.usernameInput
                                        .trim()
                                        .takeIf { it.isNotEmpty() }
                                        ?.let { "$it@" }
                                        .orEmpty()
                                val modeLabel =
                                    if (state.target.openSftp) {
                                        "sftp"
                                    } else {
                                        state.protocol.name.lowercase()
                                    }
                                Text(
                                    text = "$modeLabel · $userPrefix$endpointLabel",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                OutlinedTextField(
                                    value = state.passwordInput,
                                    onValueChange = { v ->
                                        onStateChange(state.copy(passwordInput = v, errorMessage = null))
                                    },
                                    label = { Text(s.passwordLabel) },
                                    singleLine = true,
                                    enabled = !state.connecting,
                                    shape = RoundedCornerShape(Corners.Md),
                                    visualTransformation =
                                        if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                                    trailingIcon = {
                                        IconButton(onClick = { passwordVisible = !passwordVisible }) {
                                            Icon(
                                                if (passwordVisible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                                contentDescription = null,
                                            )
                                        }
                                    },
                                    keyboardOptions =
                                        KeyboardOptions(
                                            keyboardType = KeyboardType.Password,
                                            imeAction = ImeAction.Done,
                                        ),
                                    keyboardActions = KeyboardActions(onDone = { submitState() }),
                                    modifier = Modifier.fillMaxWidth().focusRequester(stepFocusRequester),
                                )
                            }
                        }
                    }
                }

                if (!state.errorMessage.isNullOrBlank()) {
                    Row(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(Corners.Md))
                                .background(MaterialTheme.colorScheme.error.copy(alpha = 0.14f))
                                .padding(horizontal = Spacing.Md, vertical = Spacing.Sm),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Spacing.Sm),
                    ) {
                        Icon(
                            Icons.Filled.ErrorOutline,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(Sizes.IconSmall),
                        )
                        Text(
                            text = state.errorMessage,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }

                if (state.connecting && !state.statusText.isNullOrBlank()) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Spacing.Sm),
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Text(
                            text = state.statusText,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                if (state.currentStep != QuickConnectStep.PROTOCOL) {
                    val isFinalStep = state.currentStep == QuickConnectStep.PASSWORD
                    Button(
                        onClick = { submitState() },
                        enabled = !state.connecting,
                        shape = RoundedCornerShape(Corners.Lg),
                        modifier = Modifier.fillMaxWidth().height(Sizes.TouchTarget),
                    ) {
                        if (state.connecting) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary,
                            )
                        } else {
                            val label =
                                when {
                                    isFinalStep && state.errorMessage != null -> s.retryAction
                                    isFinalStep -> s.connectAction
                                    else -> s.continueAction
                                }
                            Text(label, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ConnectionFailedContent(
    lead: String,
    targetLabel: String,
    summary: QuickConnectFailureSummary,
    tryAgainLabel: String,
    onTryAgain: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.Sm)) {
        Text(
            text = lead,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        DiagnosticLine(
            icon = Icons.Filled.Terminal,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            text = targetLabel,
            monospace = true,
        )
        DiagnosticLine(
            icon = Icons.Filled.ErrorOutline,
            tint = MaterialTheme.colorScheme.error,
            text = summary.headline,
            textColor = MaterialTheme.colorScheme.error,
        )
        DiagnosticLine(
            icon = Icons.AutoMirrored.Filled.HelpOutline,
            tint = MaterialTheme.colorScheme.primary,
            text = summary.hint,
        )
        val raw = summary.rawDetail
        if (!raw.isNullOrBlank() && raw != summary.headline) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(Corners.Md),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                border = BorderStroke(Sizes.BorderThin, MaterialTheme.colorScheme.outlineVariant),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = Spacing.Md, vertical = Spacing.Sm),
                    verticalAlignment = Alignment.Top,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.Sm),
                ) {
                    Icon(
                        Icons.Filled.Code,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp).padding(top = 2.dp),
                    )
                    Text(
                        text = raw,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        Button(
            onClick = onTryAgain,
            shape = RoundedCornerShape(Corners.Lg),
            colors =
                ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                ),
            modifier = Modifier.fillMaxWidth().padding(top = Spacing.Xs).height(Sizes.TouchTarget),
        ) {
            Text(tryAgainLabel, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun DiagnosticLine(
    icon: ImageVector,
    tint: Color,
    text: String,
    textColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    monospace: Boolean = false,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(Spacing.Sm),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(18.dp).padding(top = 1.dp),
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = if (monospace) FontFamily.Monospace else FontFamily.Default,
            color = textColor,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun WizardStepRail(
    steps: List<QuickConnectStep>,
    stepIndex: Int,
    failed: Boolean = false,
) {
    val activeColor = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    val activeContentColor = if (failed) MaterialTheme.colorScheme.onError else MaterialTheme.colorScheme.onPrimary
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = Spacing.Xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        steps.forEachIndexed { idx, step ->
            StepNode(
                icon = stepIcon(step),
                reached = failed || idx <= stepIndex,
                activeColor = activeColor,
                activeContentColor = activeContentColor,
            )
            Box(
                modifier =
                    Modifier
                        .weight(1f)
                        .height(2.dp)
                        .background(
                            if (failed || idx < stepIndex) {
                                activeColor
                            } else {
                                MaterialTheme.colorScheme.surfaceContainerHighest
                            },
                        ),
            )
        }
        StepNode(
            icon = Icons.Filled.Terminal,
            reached = failed,
            activeColor = activeColor,
            activeContentColor = activeContentColor,
        )
    }
}

@Composable
private fun StepNode(
    icon: ImageVector,
    reached: Boolean,
    activeColor: Color,
    activeContentColor: Color,
) {
    Surface(
        modifier = Modifier.size(26.dp),
        shape = CircleShape,
        color =
            if (reached) {
                activeColor
            } else {
                MaterialTheme.colorScheme.surfaceContainerHighest
            },
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint =
                    if (reached) {
                        activeContentColor
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                modifier = Modifier.size(14.dp),
            )
        }
    }
}

private fun stepIcon(step: QuickConnectStep): ImageVector =
    when (step) {
        QuickConnectStep.PROTOCOL -> Icons.Filled.Power
        QuickConnectStep.USERNAME -> Icons.Filled.Person
        QuickConnectStep.PASSWORD -> Icons.Filled.Lock
    }

@Composable
private fun ProtocolOptionCard(
    label: String,
    detail: String,
    badgeColor: Color,
    badgeTint: Color,
    selected: Boolean,
    enabled: Boolean,
    editingConfig: Boolean,
    isMosh: Boolean,
    portInput: String,
    portLabel: String,
    moshUdpPortInput: String,
    moshUdpPortLabel: String,
    moshUdpPortPlaceholder: String,
    moshCommandInput: String,
    moshCommandLabel: String,
    moshCommandPlaceholder: String,
    onToggleEditConfig: () -> Unit,
    onPortChange: (String) -> Unit,
    onMoshUdpPortChange: (String) -> Unit,
    onMoshCommandChange: (String) -> Unit,
    onConfirmConfig: () -> Unit,
    onClick: () -> Unit,
) {
    Surface(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(Corners.Lg))
                .clickable(enabled = enabled, onClick = onClick),
        shape = RoundedCornerShape(Corners.Lg),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border =
            BorderStroke(
                width = if (selected) 1.5.dp else Sizes.BorderThin,
                color =
                    if (selected) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.outlineVariant
                    },
            ),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(start = Spacing.Md, end = Spacing.Sm, top = Spacing.Sm, bottom = Spacing.Sm),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.Md),
            ) {
                Surface(
                    modifier = Modifier.size(40.dp),
                    shape = RoundedCornerShape(Corners.Md),
                    color = badgeColor,
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Filled.Terminal,
                            contentDescription = null,
                            tint = badgeTint,
                            modifier = Modifier.size(Sizes.IconMedium),
                        )
                    }
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = detail,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Surface(
                    modifier =
                        Modifier
                            .size(38.dp)
                            .clip(RoundedCornerShape(Corners.Sm))
                            .clickable(enabled = enabled, onClick = onToggleEditConfig),
                    shape = RoundedCornerShape(Corners.Sm),
                    color = MaterialTheme.colorScheme.surfaceContainerHighest,
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            if (editingConfig) Icons.Filled.Check else Icons.Filled.Edit,
                            contentDescription = portLabel,
                            tint =
                                if (editingConfig) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            modifier = Modifier.size(Sizes.IconSmall),
                        )
                    }
                }
            }
            AnimatedVisibility(visible = editingConfig) {
                Column(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(start = Spacing.Md, end = Spacing.Md, bottom = Spacing.Md),
                    verticalArrangement = Arrangement.spacedBy(Spacing.Sm),
                ) {
                    if (isMosh) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(Spacing.Sm),
                        ) {
                            OutlinedTextField(
                                value = portInput,
                                onValueChange = onPortChange,
                                label = { Text(portLabel) },
                                singleLine = true,
                                enabled = enabled,
                                shape = RoundedCornerShape(Corners.Md),
                                keyboardOptions =
                                    KeyboardOptions(
                                        keyboardType = KeyboardType.Number,
                                        imeAction = ImeAction.Next,
                                    ),
                                modifier = Modifier.weight(1f),
                            )
                            OutlinedTextField(
                                value = moshUdpPortInput,
                                onValueChange = onMoshUdpPortChange,
                                label = { Text(moshUdpPortLabel) },
                                placeholder = { Text(moshUdpPortPlaceholder) },
                                singleLine = true,
                                enabled = enabled,
                                shape = RoundedCornerShape(Corners.Md),
                                keyboardOptions =
                                    KeyboardOptions(
                                        keyboardType = KeyboardType.Number,
                                        imeAction = ImeAction.Next,
                                    ),
                                modifier = Modifier.weight(1.3f),
                            )
                        }
                        OutlinedTextField(
                            value = moshCommandInput,
                            onValueChange = onMoshCommandChange,
                            label = { Text(moshCommandLabel) },
                            placeholder = { Text(moshCommandPlaceholder) },
                            singleLine = true,
                            enabled = enabled,
                            textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                            shape = RoundedCornerShape(Corners.Md),
                            keyboardOptions =
                                KeyboardOptions(
                                    imeAction = ImeAction.Done,
                                    autoCorrectEnabled = false,
                                ),
                            keyboardActions = KeyboardActions(onDone = { onConfirmConfig() }),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        OutlinedTextField(
                            value = portInput,
                            onValueChange = onPortChange,
                            label = { Text(portLabel) },
                            singleLine = true,
                            enabled = enabled,
                            shape = RoundedCornerShape(Corners.Md),
                            keyboardOptions =
                                KeyboardOptions(
                                    keyboardType = KeyboardType.Number,
                                    imeAction = ImeAction.Done,
                                ),
                            keyboardActions = KeyboardActions(onDone = { onConfirmConfig() }),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        }
    }
}
