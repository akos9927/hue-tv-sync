package com.qi.huetvsync

import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.os.SystemClock
import android.util.Log
import kotlin.math.max

/**
 * Mirrors the screen into a tiny off-screen image (32, 64 or 128 px wide) and reads it
 * at the chosen rate. Same approach as the Hyperion grabber that ran smoothly on this TV.
 */
class ScreenCapturer(
    private val projection: MediaProjection,
    private val screenW: Int,
    private val screenH: Int,
    private val dpi: Int,
    private val sink: (rgb: ByteArray, w: Int, h: Int, timeNs: Long) -> Unit,
) {
    private val thread = HandlerThread("HueCapture", Process.THREAD_PRIORITY_DISPLAY).apply { start() }
    private val handler = Handler(thread.looper)
    private var reader: ImageReader? = null
    private var display: VirtualDisplay? = null
    @Volatile private var fps = 15
    @Volatile private var running = false
    private var width = 0
    private var height = 0
    private var rgb = ByteArray(0)

    fun start(maxDim: Int, fps: Int) {
        this.fps = fps.coerceIn(1, 60)
        handler.post {
            try {
                setupSize(maxDim)
                running = true
                handler.post(tick)
            } catch (e: Exception) {
                Log.e("ScreenCapturer", "Could not start capture", e)
                SyncState.error = "Screen capture failed: ${describe(e)}"
            }
        }
    }

    fun setFps(f: Int) {
        fps = f.coerceIn(1, 60)
    }

    fun setSize(maxDim: Int) {
        handler.post {
            try {
                setupSize(maxDim)
            } catch (e: Exception) {
                Log.e("ScreenCapturer", "Could not resize capture", e)
            }
        }
    }

    private fun setupSize(maxDim: Int) {
        val landscape = screenW >= screenH
        var w = if (landscape) maxDim else max(2, maxDim * screenW / screenH)
        var h = if (landscape) max(2, maxDim * screenH / screenW) else maxDim
        w = max(2, w and 1.inv())
        h = max(2, h and 1.inv())
        if (w == width && h == height && display != null) return

        val newReader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
        val d = display
        if (d == null) {
            display = projection.createVirtualDisplay(
                "HueTVSync", w, h, dpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC,
                newReader.surface, null, handler,
            )
        } else {
            d.resize(w, h, dpi)
            d.surface = newReader.surface
        }
        reader?.close()
        reader = newReader
        width = w
        height = h
        rgb = ByteArray(w * h * 3)
    }

    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            val start = SystemClock.uptimeMillis()
            grab()
            val wait = 1000L / fps - (SystemClock.uptimeMillis() - start)
            handler.postDelayed(this, max(1L, wait))
        }
    }

    private fun grab() {
        val r = reader ?: return
        val img = try {
            r.acquireLatestImage()
        } catch (e: Exception) {
            null
        } ?: return
        try {
            val plane = img.planes[0]
            val buf = plane.buffer
            val ps = plane.pixelStride
            val rs = plane.rowStride
            val w = minOf(img.width, width)
            val h = minOf(img.height, height)
            if (w * h * 3 > rgb.size) return
            var o = 0
            for (y in 0 until h) {
                var p = y * rs
                for (x in 0 until w) {
                    rgb[o++] = buf.get(p)
                    rgb[o++] = buf.get(p + 1)
                    rgb[o++] = buf.get(p + 2)
                    p += ps
                }
            }
            sink(rgb, w, h, System.nanoTime())
        } catch (e: Exception) {
            Log.w("ScreenCapturer", "Frame read failed", e)
        } finally {
            img.close()
        }
    }

    fun stop() {
        running = false
        handler.post {
            try {
                display?.release()
            } catch (_: Exception) {
            }
            display = null
            try {
                reader?.close()
            } catch (_: Exception) {
            }
            reader = null
            thread.quitSafely()
        }
    }
}
