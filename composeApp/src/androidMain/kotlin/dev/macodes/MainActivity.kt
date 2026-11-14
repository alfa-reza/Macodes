package dev.macodes

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // Explicitly set the system bar style to dark (with light-colored icons); otherwise, icons will appear dark in the system’s light mode,
        // Completely invisible against a dark window background (“pitch black”).
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        // Notification for the “reconnect” action (singleTop: onNewIntent is called while the app is alive)
        dev.macodes.notify.handleNotificationIntent(intent)
        setContent {
            App()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        dev.macodes.notify.handleNotificationIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        AppContext.setCurrentActivity(this)
    }

    override fun onPause() {
        if (AppContext.currentActivity === this) AppContext.setCurrentActivity(null)
        super.onPause()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (AppContext.currentActivity === this) AppContext.setCurrentActivity(null)
    }
}
