package dev.termish

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import java.util.concurrent.ConcurrentHashMap

/**
 * SSH session foreground service: Prevents the connection from being disconnected when the process is recycled by the system after being moved to the background.
 * Multiple sessions are idempotently registered by sessionId and share a single foreground service.
 */
class SessionService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private val handler = Handler(Looper.getMainLooper())

    /**
     * Next renewal time (elapsedRealtime monotonically increasing clock). Advanced only by [scheduleRenew] at the scheduled time;
     * For subsequent sessions, `start` should only reschedule the session and not reset the timer—otherwise, a new session would delay the renewal of the old session,
     * Allow the wakelock that was launched first to timeout after 12 hours without being renewed (keep-alive gap).
     */
    private var nextRenewAt = 0L

    /** Wakelock fallback timeout renewal: When there is an active session, refresh the 12-hour timeout based on [nextRenewAt]. */
    private val renewWakeLock =
        object : Runnable {
            override fun run() {
                if (desiredSessionIds.isNotEmpty()) scheduleRenew()
            }
        }

    /** Schedule renewal based on [nextRenewAt]; if the time has arrived, renew immediately and advance the timestamp (idempotent; can be called at any time). */
    private fun scheduleRenew() {
        val now = SystemClock.elapsedRealtime()
        if (nextRenewAt <= now) {
            releaseWakeLock()
            acquireWakeLock()
            nextRenewAt = now + RENEW_INTERVAL_MS
        }
        handler.removeCallbacks(renewWakeLock)
        handler.postDelayed(renewWakeLock, (nextRenewAt - now).coerceAtLeast(1))
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        val sessionId = intent?.getStringExtra(EXTRA_SESSION_ID)
        Log.i(TAG, "sync: action=${intent?.action} session=$sessionId activeSessions=${desiredSessionIds.size}")
        // Use the target set that the caller has already updated as the basis, rather than incrementing or decrementing the count based on the order in which Intents arrive.
        // When reconnecting during the same session, `stop` and `start` are sent in quick succession; even if the old `stop` is handled last,
        // The service must never be shut down as long as the new connection is still in the target set.
        if (desiredSessionIds.isEmpty()) {
            releaseKeepAliveLocks()
            handler.removeCallbacks(renewWakeLock)
            stopSelfResult(startId)
            return START_NOT_STICKY
        }
        if (intent == null) {
            Log.i(TAG, "restarted by system, activeSessions=${desiredSessionIds.size}")
        }
        startForegroundCompat()
        isRunning = true
        acquireWakeLock()
        acquireWifiLock()
        scheduleRenew()
        return START_STICKY
    }

    override fun onTimeout(
        startId: Int,
        fgsType: Int,
    ) {
        // Defensive handling: If the system triggers a timeout for the current foreground service type, immediately release the resources.
        // When a user returns to the foreground, sessions are re-registered and SSH health is verified first.
        Log.w(TAG, "foreground service timed out type=$fgsType")
        releaseKeepAliveLocks()
        handler.removeCallbacks(renewWakeLock)
        stopSelf()
    }

    override fun onDestroy() {
        handler.removeCallbacks(renewWakeLock)
        releaseKeepAliveLocks()
        nextRenewAt = 0
        isRunning = false
        Log.i(TAG, "destroyed")
        super.onDestroy()
    }

    private fun startForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(NOTIF_ID, buildNotification())
        }
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock =
            pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "termish:session").apply {
                setReferenceCounted(false)
                // Fallback for 12 hours; released normally via the service’s `onDestroy` method.
                acquire(12 * 60 * 60 * 1000L)
            }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    /**
     * SSH is a persistent, low-traffic long-running connection: wakelocks cannot prevent certain ROMs from running in the background after the app is closed
     * Put the Wi-Fi RF into sleep mode. Hold `WifiLock` for the whole session so the underlying network cannot close the socket
     * within seconds of the app going to the background; the foreground service notification makes this battery cost visible to the user.
     */
    @Suppress("DEPRECATION")
    private fun acquireWifiLock() {
        if (wifiLock?.isHeld == true) return
        val wifiManager = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
        wifiLock =
            wifiManager.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "termish:ssh-wifi").apply {
                setReferenceCounted(false)
                acquire()
            }
    }

    private fun releaseWifiLock() {
        wifiLock?.let { if (it.isHeld) it.release() }
        wifiLock = null
    }

    private fun releaseKeepAliveLocks() {
        releaseWakeLock()
        releaseWifiLock()
    }

    private fun buildNotification(): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "SSH 会话", NotificationManager.IMPORTANCE_LOW),
        )
        val openIntent =
            PendingIntent.getActivity(
                this,
                0,
                packageManager.getLaunchIntentForPackage(packageName),
                PendingIntent.FLAG_IMMUTABLE,
            )
        return Notification
            .Builder(this, CHANNEL_ID)
            .setContentTitle("Termish 会话进行中")
            .setContentText("保持 SSH 连接在后台存活")
            .setSmallIcon(applicationInfo.icon)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val TAG = "Termish-Service"
        private const val CHANNEL_ID = "session"
        private const val NOTIF_ID = 1
        private const val ACTION_STOP = "dev.termish.SESSION_STOP"
        private const val EXTRA_SESSION_ID = "session_id"
        private const val RENEW_INTERVAL_MS = 8 * 60 * 60 * 1000L

        /**
         * Active session registration: Idempotently add or remove based on sessionId to prevent duplicate `start` calls in the same session, which would cause reference counting issues.
         * Leakage, or interleaved start/stop commands during reconnect causing the service to exit prematurely.
         */
        private val desiredSessionIds = ConcurrentHashMap.newKeySet<String>()

        /**
         * Is the foreground service actually running? This returns `false` if the service has been stopped by the system or vendor’s power-saving policy,
         * The upper layer re-registers active sessions based on this.
         */
        @Volatile
        var isRunning = false
            private set

        fun start(
            ctx: Context,
            sessionId: String,
        ) {
            if (!desiredSessionIds.add(sessionId) && isRunning) return
            try {
                ctx.startForegroundService(
                    Intent(ctx, SessionService::class.java).putExtra(EXTRA_SESSION_ID, sessionId),
                )
            } catch (e: Exception) {
                // Retain target registration: The current background restriction may be temporary and will be synced again when returning to the foreground.
                Log.e(TAG, "startForegroundService failed", e)
            }
        }

        fun stop(
            ctx: Context,
            sessionId: String,
        ) {
            if (!desiredSessionIds.remove(sessionId)) return
            try {
                ctx.startService(
                    Intent(ctx, SessionService::class.java)
                        .setAction(ACTION_STOP)
                        .putExtra(EXTRA_SESSION_ID, sessionId),
                )
            } catch (e: Exception) {
                Log.e(TAG, "stop via startService failed", e)
            }
        }
    }
}
