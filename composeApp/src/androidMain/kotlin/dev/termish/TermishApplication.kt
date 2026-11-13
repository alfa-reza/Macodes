package dev.termish

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Application
import android.content.Context
import java.lang.ref.WeakReference
import java.security.Security
import org.bouncycastle.jce.provider.BouncyCastleProvider

/** Process-level Application Context (used for storage of parameterless `expect` expressions). */
@SuppressLint("StaticFieldLeak") // Only the `c.applicationContext` field is saved; its lifecycle aligns with that of the process.
object AppContext {
    // Holds only the `applicationContext`; its lifecycle aligns with that of the process and does not leak `Activity` instances.
    @Volatile
    private var context: Context? = null

    /** Current foreground Activity (used when haptic feedback, permission requests, etc., require the Activity context; null = none). */
    @Volatile private var activityRef = WeakReference<Activity>(null)

    val currentActivity: Activity?
        get() = activityRef.get()

    fun setCurrentActivity(activity: Activity?) {
        activityRef = WeakReference(activity)
    }

    fun init(c: Context) {
        context = c.applicationContext
    }

    fun get(): Context = context ?: throw IllegalStateException("AppContext 未初始化")
}

class TermishApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        AppContext.init(this)
        installFullBouncyCastle()
    }

    /**
     * Android comes with a stripped-down version of BouncyCastle (the provider is also named "BC"), which lacks X25519 / Ed25519.
     * sshj’s `SecurityUtils.register()` will not register the full version bundled with the app if it detects that “BC” already exists,
     * This causes curve25519 to report "no such algorithm: X25519 for provider BC" when running KEX.
     * Here, the full version replaces the built-in stripped-down version.
     */
    private fun installFullBouncyCastle() {
        Security.removeProvider("BC")
        Security.addProvider(BouncyCastleProvider())
    }
}
