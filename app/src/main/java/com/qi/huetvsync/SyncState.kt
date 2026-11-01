package com.qi.huetvsync

import android.content.Intent
import android.os.Build

/** Shared, thread-safe status that the settings screen reads several times a second. */
object SyncState {
    @Volatile var running = false
    @Volatile var status = "Stopped"
    @Volatile var error: String? = null
    @Volatile var captureFps = 0f
    @Volatile var sendFps = 0f
    @Volatile var previewColors: FloatArray? = null
    @Volatile var cropTop = 0f
    @Volatile var cropLeft = 0f
    @Volatile var engine: SyncEngine? = null
    @Volatile var capturer: ScreenCapturer? = null
    @Volatile var updateStatus: String? = null
    /** Index of the segment lit by the layout test, or -1. */
    @Volatile var testChannel = -1
}

/** A short, readable message for an exception. */
fun describe(e: Throwable): String {
    val msg = e.message?.takeIf { it.isNotBlank() }
    return when {
        e is java.net.SocketTimeoutException -> "The Hue Bridge didn't answer in time. Check its IP address."
        e is java.net.ConnectException -> "Couldn't reach the Hue Bridge. Check its IP address."
        e is java.net.UnknownHostException -> "No internet connection or unknown address."
        e.javaClass.simpleName == "TlsTimeoutException" -> "The secure light connection timed out. Is another app syncing these lights?"
        msg != null -> msg
        else -> e.javaClass.simpleName
    }
}

@Suppress("DEPRECATION")
fun Intent.intentExtra(key: String): Intent? =
    if (Build.VERSION.SDK_INT >= 33) getParcelableExtra(key, Intent::class.java) else getParcelableExtra(key)
