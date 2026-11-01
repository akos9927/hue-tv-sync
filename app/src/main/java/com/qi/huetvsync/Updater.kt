package com.qi.huetvsync

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Checks the GitHub repository for a newer build and installs it. */
object Updater {
    data class Release(val build: Int, val url: String)

    fun latest(): Release? {
        val (code, text) = Http.request(
            "https://api.github.com/repos/${BuildConfig.UPDATE_REPO}/releases/latest",
            headers = mapOf("Accept" to "application/vnd.github+json"),
        )
        if (code == 404) return null
        if (code != 200) throw IOException("GitHub answered HTTP $code.")
        val o = JSONObject(text)
        val build = o.optString("tag_name").removePrefix("build-").toIntOrNull() ?: return null
        val assets = o.optJSONArray("assets") ?: return null
        for (i in 0 until assets.length()) {
            val a = assets.getJSONObject(i)
            if (a.optString("name").endsWith(".apk")) return Release(build, a.getString("browser_download_url"))
        }
        return null
    }

    fun install(context: Context, release: Release, progress: (String) -> Unit) {
        val file = File(context.cacheDir, "update.apk")
        download(release.url, file, progress)
        progress("Installing…")
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            session.openWrite("HueTVSync.apk", 0, file.length()).use { out ->
                file.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            val callback = PendingIntent.getBroadcast(
                context, sessionId, Intent(context, InstallResultReceiver::class.java), flags
            )
            session.commit(callback.intentSender)
        }
    }

    private fun download(url: String, dest: File, progress: (String) -> Unit) {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15000
        conn.readTimeout = 30000
        conn.instanceFollowRedirects = true
        try {
            if (conn.responseCode != 200) throw IOException("Download failed (HTTP ${conn.responseCode}).")
            val total = conn.contentLengthLong
            conn.inputStream.use { input ->
                dest.outputStream().use { output ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    var lastPct = -1
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        output.write(buf, 0, n)
                        done += n
                        if (total > 0) {
                            val pct = (done * 100 / total).toInt()
                            if (pct != lastPct && pct % 10 == 0) {
                                lastPct = pct
                                progress("Downloading… $pct%")
                            }
                        }
                    }
                }
            }
        } finally {
            conn.disconnect()
        }
    }
}

/** Receives the installer's answer; asks the user to confirm when Android needs it. */
class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = intent.intentExtra(Intent.EXTRA_INTENT)
                if (confirm != null) {
                    confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(confirm)
                }
            }
            PackageInstaller.STATUS_SUCCESS -> SyncState.updateStatus = "Updated."
            else -> SyncState.updateStatus = "Update failed: " +
                (intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "status $status")
        }
    }
}
