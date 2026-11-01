package com.qi.huetvsync

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.view.Display
import java.io.IOException

/** Foreground service that owns the screen capture and the light stream while syncing. */
class SyncService : Service() {

    companion object {
        const val ACTION_START = "com.qi.huetvsync.START"
        const val ACTION_STOP = "com.qi.huetvsync.STOP"
        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_RESULT_DATA = "resultData"
        private const val CHANNEL_ID = "sync"
        private const val NOTIFICATION_ID = 1
    }

    private val main = Handler(Looper.getMainLooper())
    private var projection: MediaProjection? = null
    private var capturer: ScreenCapturer? = null
    private var engine: SyncEngine? = null
    private var screenReceiverOn = false

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> engine?.setPaused(true)
                Intent.ACTION_SCREEN_ON -> engine?.setPaused(false)
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                goForeground()
                val code = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
                val data = intent.intentExtra(EXTRA_RESULT_DATA)
                if (data == null) {
                    finishWithError("Screen capture permission is missing. Press Start again.")
                } else {
                    startSync(code, data)
                }
            }
            ACTION_STOP -> {
                stopSync()
                shutDown()
            }
            else -> shutDown()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        stopSync()
        super.onDestroy()
    }

    private fun goForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Light sync", NotificationManager.IMPORTANCE_LOW)
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText("Syncing your Hue lights with the screen")
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun startSync(resultCode: Int, data: Intent) {
        stopSync()
        val prefs = Prefs(this)
        if (!prefs.isReady) {
            finishWithError("Pair with the Hue Bridge and choose an entertainment area first.")
            return
        }
        val proj = try {
            getSystemService(MediaProjectionManager::class.java).getMediaProjection(resultCode, data)
        } catch (e: Exception) {
            null
        }
        if (proj == null) {
            finishWithError("Screen capture was not allowed.")
            return
        }
        projection = proj
        proj.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                main.post {
                    if (projection === proj) {
                        stopSync()
                        shutDown()
                    }
                }
            }
        }, main)

        SyncState.running = true
        SyncState.error = null
        SyncState.status = "Starting…"
        val metrics = screenMetrics()

        Thread {
            try {
                val area = HueBridge(prefs.bridgeIp, prefs.appKey).areas().firstOrNull { it.id == prefs.areaId }
                    ?: throw IOException("The chosen entertainment area no longer exists. Choose it again.")
                if (area.channels.isEmpty()) throw IOException("This entertainment area has no lights in it.")
                val tuning = prefs.tuning()
                val eng = SyncEngine(prefs.bridgeIp, prefs.appKey, prefs.clientKey, area, tuning)
                main.post {
                    if (projection !== proj) return@post // stopped in the meantime
                    engine = eng
                    SyncState.engine = eng
                    eng.start()
                    val cap = ScreenCapturer(proj, metrics.widthPixels, metrics.heightPixels, metrics.densityDpi) { rgb, w, h, ts ->
                        eng.onFrame(rgb, w, h, ts)
                    }
                    capturer = cap
                    SyncState.capturer = cap
                    cap.start(tuning.captureMethod, tuning.sizeIndex, tuning.fps, tuning.burst)
                    registerScreenReceiver()
                }
            } catch (e: Exception) {
                main.post {
                    if (projection === proj) finishWithError(describe(e))
                }
            }
        }.start()
    }

    private fun stopSync() {
        unregisterScreenReceiver()
        capturer?.stop()
        capturer = null
        SyncState.capturer = null
        val eng = engine
        engine = null
        SyncState.engine = null
        if (eng != null) Thread { eng.stop() }.start()
        val p = projection
        projection = null
        try {
            p?.stop()
        } catch (_: Exception) {
        }
        SyncState.running = false
        if (SyncState.error == null) SyncState.status = "Stopped"
    }

    private fun finishWithError(message: String) {
        stopSync()
        SyncState.error = message
        SyncState.status = "Stopped"
        shutDown()
    }

    private fun shutDown() {
        stopForeground(Service.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun registerScreenReceiver() {
        if (screenReceiverOn) return
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(screenReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(screenReceiver, filter)
        }
        screenReceiverOn = true
    }

    private fun unregisterScreenReceiver() {
        if (!screenReceiverOn) return
        try {
            unregisterReceiver(screenReceiver)
        } catch (_: Exception) {
        }
        screenReceiverOn = false
    }

    @Suppress("DEPRECATION")
    private fun screenMetrics(): DisplayMetrics {
        val dm = DisplayMetrics()
        val display = getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
        display.getRealMetrics(dm)
        return dm
    }
}
