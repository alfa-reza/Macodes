package dev.macodes.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalInspectionMode
import com.russhwolf.settings.MapSettings
import dev.macodes.MacodesApplication
import dev.macodes.data.ConnectionMode
import dev.macodes.data.Host
import dev.macodes.data.HostRepository
import dev.macodes.data.QuickConnectHistoryEntry
import dev.macodes.data.QuickConnectParseResult
import dev.macodes.data.QuickConnectParser
import dev.macodes.data.ThemeMode
import dev.macodes.ssh.SshErrorCode
import dev.macodes.ui.theme.MacodesTheme
import dev.macodes.ui.theme.Spacing
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import org.jetbrains.compose.resources.PreviewContextConfigurationEffect
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = MacodesApplication::class, sdk = [34], qualifiers = "w390dp-h844dp-xxhdpi")
class QuickConnectScreenshotTest {
    @Test
    fun renderQuickConnectScreensAndVerifyLayout() {
        val sampleHistory =
            listOf(
                QuickConnectHistoryEntry(
                    protocol = ConnectionMode.SSH,
                    host = "192.168.0.10",
                    port = 22,
                    username = "ubuntu",
                    lastConnectedAt = 1_731_580_000_000L,
                    system = "ubuntu",
                ),
                QuickConnectHistoryEntry(
                    protocol = ConnectionMode.MOSH,
                    host = "10.0.0.25",
                    port = 2222,
                    moshUdpPort = 60001,
                    moshServerCommand = "/opt/homebrew/bin/mosh-server",
                    username = "deploy",
                    lastConnectedAt = 1_731_575_000_000L,
                    system = "debian",
                ),
                QuickConnectHistoryEntry(
                    protocol = ConnectionMode.SSH,
                    host = "files.prod.internal",
                    port = 22,
                    username = "backup",
                    lastConnectedAt = 1_731_570_000_000L,
                    system = "alpine",
                    openSftp = true,
                ),
            )
        val bareIp = (QuickConnectParser.parse("192.168.0.1") as QuickConnectParseResult.Success).target
        val protocolState = createWizardState(bareIp)
        val usernameState = protocolState.copy(stepIndex = 1, protocol = ConnectionMode.SSH)
        val passwordState = usernameState.copy(stepIndex = 2, usernameInput = "root")

        renderAndSave("01-connections-bar.png") {
            ConnectionsPreviewBackdrop(history = sampleHistory, overlay = null)
        }
        renderAndSave("02-wizard-protocol.png") {
            ConnectionsPreviewBackdrop(history = sampleHistory, overlay = protocolState)
        }
        renderAndSave("03-wizard-protocol-edit-port.png") {
            ConnectionsPreviewBackdrop(
                history = sampleHistory,
                overlay =
                    protocolState.copy(
                        protocol = ConnectionMode.MOSH,
                        portInput = "2222",
                        moshUdpPortInput = "60001",
                        moshCommandInput = "/opt/homebrew/bin/mosh-server",
                    ),
                initialEditingPortFor = ConnectionMode.MOSH,
            )
        }
        renderAndSave("04-wizard-username.png") {
            ConnectionsPreviewBackdrop(history = sampleHistory, overlay = usernameState)
        }
        renderAndSave("05-wizard-password.png") {
            ConnectionsPreviewBackdrop(history = sampleHistory, overlay = passwordState)
        }
        val failedSummary =
            buildQuickConnectFailureSummary(
                rawMessage = "Exhausted available authentication methods",
                protocol = ConnectionMode.SSH,
                s = appStringsFor("en").quickConnect,
                errorCode = SshErrorCode.AUTH_FAILED,
            )
        renderAndSave("06-wizard-connection-failed.png") {
            ConnectionsPreviewBackdrop(
                history = sampleHistory,
                overlay = passwordState.copy(failureSummary = failedSummary),
            )
        }

        val activeCtrl =
            TerminalController(
                host =
                    Host(
                        id = "preview-1",
                        name = "ubuntu@VM-0-12-ubuntu",
                        hostname = "192.168.0.10",
                        port = 22,
                        username = "ubuntu",
                        system = "ubuntu",
                    ),
                password = null,
                privateKeyPem = null,
                repository = HostRepository(settings = MapSettings()),
            ).apply {
                status = ConnStatus.CONNECTED
                emulator.write(
                    (
                        "185 updates can be applied immediately.\r\n" +
                            "Last login: Sun Nov 15 10:21:21 2026 from 103.18.35.25\r\n" +
                            "ubuntu@VM-0-12-ubuntu:~$ docker ps\r\n" +
                            "CONTAINER ID   IMAGE     STATUS\r\n" +
                            "a1b2c3d4e5f6   nginx     Up 3 hours"
                    ).encodeToByteArray(),
                )
            }
        val closedCtrl =
            TerminalController(
                host =
                    Host(
                        id = "preview-2",
                        name = "deploy@edge-gw",
                        hostname = "10.0.0.25",
                        port = 2222,
                        username = "deploy",
                        connectionMode = ConnectionMode.MOSH,
                        system = "debian",
                    ),
                password = null,
                privateKeyPem = null,
                repository = HostRepository(settings = MapSettings()),
            ).apply {
                status = ConnStatus.CLOSED
            }
        renderAndSave("07-connections-active-preview.png") {
            ConnectionsPreviewBackdrop(
                history = sampleHistory,
                sessions = listOf(HostSessionItem.Terminal(activeCtrl), HostSessionItem.Terminal(closedCtrl)),
                overlay = null,
            )
        }
    }

    @Composable
    private fun ConnectionsPreviewBackdrop(
        history: List<QuickConnectHistoryEntry>,
        overlay: QuickConnectWizardState?,
        sessions: List<HostSessionItem> = emptyList(),
        initialEditingPortFor: ConnectionMode? = null,
    ) {
        MacodesTheme(mode = ThemeMode.DARK) {
            CompositionLocalProvider(
                LocalInspectionMode provides true,
                LocalAppStrings provides appStringsFor("en"),
            ) {
                PreviewContextConfigurationEffect()
                Box(modifier = Modifier.fillMaxSize()) {
                    ConnectionsScreen(
                        sessions = sessions,
                        history = history,
                        onOpen = {},
                        onClose = {},
                    )
                    if (overlay != null) {
                        Box(
                            modifier =
                                Modifier
                                    .fillMaxSize()
                                    .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.55f))
                                    .padding(top = Spacing.Lg),
                            contentAlignment = Alignment.BottomCenter,
                        ) {
                            QuickConnectWizardSheet(
                                state = overlay,
                                onStateChange = {},
                                onConnect = {},
                                onCancel = {},
                                initialEditingPortFor = initialEditingPortFor,
                            )
                        }
                    }
                }
            }
        }
    }

    private fun renderAndSave(
        fileName: String,
        content: @Composable () -> Unit,
    ) {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val activity = controller.get()
        val view =
            ComposeView(activity).apply {
                setContent(content)
            }
        activity.setContentView(view)
        shadowOf(Looper.getMainLooper()).idle()

        val widthPx = 1170
        val heightPx = 2532
        val widthSpec = View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY)
        val heightSpec = View.MeasureSpec.makeMeasureSpec(heightPx, View.MeasureSpec.EXACTLY)
        view.measure(widthSpec, heightSpec)
        view.layout(0, 0, widthPx, heightPx)
        shadowOf(Looper.getMainLooper()).idle()

        val bitmap = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        assertTrue(bitmap.width == widthPx && bitmap.height == heightPx)

        val outDirPath =
            System.getProperty("macodes.screenshotDir")
                ?: System.getenv("MACODES_SCREENSHOT_DIR")
        if (!outDirPath.isNullOrBlank()) {
            val outDir = File(outDirPath).apply { mkdirs() }
            File(outDir, fileName).outputStream().use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
        }
        bitmap.recycle()
    }
}
