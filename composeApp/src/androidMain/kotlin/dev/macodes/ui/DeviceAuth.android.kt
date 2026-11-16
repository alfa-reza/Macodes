package dev.macodes.ui

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.CancellationSignal
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat

@Composable
actual fun rememberDeviceAuthenticator(): (
    title: String,
    subtitle: String,
    onResult: (Boolean) -> Unit,
) -> Unit {
    val context = LocalContext.current
    var pendingCallback by remember { mutableStateOf<((Boolean) -> Unit)?>(null) }

    val legacyLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val cb = pendingCallback
            pendingCallback = null
            cb?.invoke(result.resultCode == Activity.RESULT_OK)
        }

    return { title, subtitle, onResult ->
        val keyguard = context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
        if (keyguard == null || !keyguard.isDeviceSecure) {
            onResult(true)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val executor = ContextCompat.getMainExecutor(context)
            val prompt =
                BiometricPrompt
                    .Builder(context)
                    .setTitle(title)
                    .setSubtitle(subtitle)
                    .setAllowedAuthenticators(
                        BiometricManager.Authenticators.BIOMETRIC_STRONG or
                            BiometricManager.Authenticators.BIOMETRIC_WEAK or
                            BiometricManager.Authenticators.DEVICE_CREDENTIAL,
                    ).build()
            prompt.authenticate(
                CancellationSignal(),
                executor,
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult?) {
                        onResult(true)
                    }

                    override fun onAuthenticationError(
                        errorCode: Int,
                        errString: CharSequence?,
                    ) {
                        onResult(false)
                    }
                },
            )
        } else {
            @Suppress("DEPRECATION")
            val intent = keyguard.createConfirmDeviceCredentialIntent(title, subtitle)
            if (intent == null) {
                onResult(true)
            } else {
                pendingCallback = onResult
                legacyLauncher.launch(intent)
            }
        }
    }
}
