package dev.macodes.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalInspectionMode
import com.russhwolf.settings.MapSettings
import dev.macodes.MacodesApplication
import dev.macodes.data.ConnectionLogEntry
import dev.macodes.data.ConnectionLogEvent
import dev.macodes.data.ConnectionLogProtocol
import dev.macodes.data.CredentialKind
import dev.macodes.data.Host
import dev.macodes.data.HostRepository
import dev.macodes.data.KeychainCredential
import dev.macodes.data.ThemeMode
import dev.macodes.ui.theme.MacodesTheme
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.datetime.Clock
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.compose.resources.PreviewContextConfigurationEffect
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = MacodesApplication::class, sdk = [34], qualifiers = "w412dp-h915dp-420dpi")
class WorkspaceScreenshotTest {
    @Test
    fun renderWorkspaceScreensAndVerifyLayout() {
        val repo = HostRepository(MapSettings())
        val prodHost =
            Host(
                id = "h1",
                name = "Prod API",
                hostname = "10.0.1.20",
                port = 22,
                username = "ubuntu",
                credentialId = "k1",
                knownHostFingerprint = "SHA256:u7vY8xK2sL9aP1dQ4rT6wZ0eN3mB5cV8gH2jK4lM",
            )
        repo.upsertHost(prodHost)
        repo.recordQuickConnectFingerprint("192.168.0.10", 22, "SHA256:9fA1cB3dE5fG7hI9jK1lM3nO5pQ7rS9tU1vW3xY")
        repo.upsertCredential(
            KeychainCredential(
                id = "k1",
                name = "Prod Ed25519",
                kind = CredentialKind.PRIVATE_KEY,
                usernameHint = "ubuntu",
                updatedAt = 1_731_600_000_000L,
            ),
        )
        repo.upsertCredential(
            KeychainCredential(
                id = "k2",
                name = "Staging Password",
                kind = CredentialKind.PASSWORD,
                usernameHint = "deploy",
                updatedAt = 1_731_590_000_000L,
            ),
        )
        val tz = TimeZone.currentSystemDefault()
        val today =
            Clock.System
                .now()
                .toLocalDateTime(tz)
                .date
        val yesterday = today.minus(DatePeriod(days = 1))
        val threeDaysAgo = today.minus(DatePeriod(days = 3))

        fun at(
            date: LocalDate,
            hour: Int,
            minute: Int,
        ): Long = LocalDateTime(date, LocalTime(hour, minute)).toInstant(tz).toEpochMilliseconds()

        repo.appendConnectionLog(
            ConnectionLogEntry(
                id = "l1",
                hostLabel = "Prod API",
                endpoint = "ubuntu@10.0.1.20:22",
                protocol = ConnectionLogProtocol.SSH,
                event = ConnectionLogEvent.CONNECTED,
                timestamp = at(today, 16, 20),
            ),
        )
        repo.appendConnectionLog(
            ConnectionLogEntry(
                id = "l2",
                hostLabel = "dev@10.0.0.5",
                endpoint = "dev@10.0.0.5:22",
                protocol = ConnectionLogProtocol.MOSH,
                event = ConnectionLogEvent.FAILED,
                detail = "Mosh UDP handshake timed out",
                timestamp = at(yesterday, 10, 26),
            ),
        )
        repo.appendConnectionLog(
            ConnectionLogEntry(
                id = "l3",
                hostLabel = "Backup Node",
                endpoint = "backup@files.internal:22",
                protocol = ConnectionLogProtocol.SFTP,
                event = ConnectionLogEvent.DISCONNECTED,
                timestamp = at(threeDaysAgo, 8, 15),
            ),
        )

        renderAndSave("01-workspace-hub.png") {
            Scaffold(
                contentWindowInsets = WindowInsets(0, 0, 0, 0),
                bottomBar = {
                    HomeBottomBar(
                        selectedTab = HomeTab.WORKSPACE,
                        activeSessionCount = 1,
                        onSelectTab = {},
                    )
                },
            ) { padding ->
                Box(Modifier.padding(padding)) {
                    WorkspaceScreen(
                        hostsCount = 3,
                        credentialsCount = 2,
                        snippetsCount = 4,
                        knownHostsCount = 6,
                        logsCount = 5,
                        onOpenSub = {},
                    )
                }
            }
        }
        renderAndSave("02-keychain.png") {
            KeychainScreen(
                repository = repo,
                hosts = listOf(prodHost),
                onCredentialsChanged = {},
                onBack = {},
            )
        }
        renderAndSave("03-known-hosts.png") {
            KnownHostsScreen(
                repository = repo,
                hosts = listOf(prodHost),
                onKnownHostsChanged = {},
                onBack = {},
            )
        }
        renderAndSave("04-connection-logs.png") {
            ConnectionLogsScreen(
                repository = repo,
                onLogsChanged = {},
                onBack = {},
            )
        }
    }

    private fun renderAndSave(
        fileName: String,
        content: @Composable () -> Unit,
    ) {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val activity = controller.get()
        val composeView =
            ComposeView(activity).apply {
                setContent {
                    CompositionLocalProvider(
                        LocalInspectionMode provides true,
                        LocalAppStrings provides appStringsFor("en"),
                    ) {
                        PreviewContextConfigurationEffect()
                        MacodesTheme(ThemeMode.DARK) {
                            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                                content()
                            }
                        }
                    }
                }
            }
        activity.setContentView(composeView)
        shadowOf(Looper.getMainLooper()).idle()

        val width = activity.resources.displayMetrics.widthPixels
        val height = activity.resources.displayMetrics.heightPixels
        val widthSpec = View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY)
        val heightSpec = View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY)
        composeView.measure(widthSpec, heightSpec)
        composeView.layout(0, 0, width, height)
        shadowOf(Looper.getMainLooper()).idle()

        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        composeView.draw(Canvas(bitmap))

        val outDir = File("hasil/workspace").also { it.mkdirs() }
        val outFile = File(outDir, fileName)
        outFile.outputStream().use { stream ->
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream))
        }
        controller.pause().stop().destroy()
    }
}
