package com.qi.huetvsync

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Starts syncing after the TV restarts, if that option is on. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val prefs = Prefs(context)
        if (!prefs.startOnBoot || !prefs.isReady) return
        try {
            context.startActivity(
                Intent(context, StartCaptureActivity::class.java).addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS or
                        Intent.FLAG_ACTIVITY_NO_ANIMATION
                )
            )
        } catch (_: Exception) {
        }
    }
}
