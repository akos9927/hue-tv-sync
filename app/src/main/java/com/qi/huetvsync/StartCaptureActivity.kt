package com.qi.huetvsync

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle

/** Invisible screen that asks Android for screen-capture permission, then starts the sync service. */
class StartCaptureActivity : Activity() {

    companion object {
        private const val REQUEST_CAPTURE = 1
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) return
        val mpm = getSystemService(MediaProjectionManager::class.java)
        @Suppress("DEPRECATION")
        startActivityForResult(mpm.createScreenCaptureIntent(), REQUEST_CAPTURE)
    }

    @Deprecated("Deprecated in Java")
    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_CAPTURE) return
        if (resultCode == RESULT_OK && data != null) {
            val start = Intent(this, SyncService::class.java)
                .setAction(SyncService.ACTION_START)
                .putExtra(SyncService.EXTRA_RESULT_CODE, resultCode)
                .putExtra(SyncService.EXTRA_RESULT_DATA, data)
            startForegroundService(start)
        } else {
            SyncState.error = "Screen capture was not allowed."
        }
        finish()
    }
}
