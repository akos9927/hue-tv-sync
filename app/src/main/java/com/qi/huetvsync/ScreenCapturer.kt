package com.qi.huetvsync

import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Mirrors the screen into a small off-screen copy and reads it at the chosen rate.
 *
 * Standard: the copy is a tiny image (32/64/128 px wide) read straight from memory.
 * Codec:    the copy goes to the TV's hardware H.264 encoder (the path used for screen casting),
 *           and a software decoder turns it back into pixels. Some TVs handle this path with far
 *           less work for the graphics chip. Idea taken from Hyperion Android Reborn (MIT).
 *
 * One virtual display is kept for the whole session; switching method or size only swaps
 * the surface it draws into.
 */
class ScreenCapturer(
    private val projection: MediaProjection,
    private val screenW: Int,
    private val screenH: Int,
    private val dpi: Int,
    private val sink: (rgb: ByteArray, w: Int, h: Int, timeNs: Long) -> Unit,
) {
    companion object {
        private const val TAG = "ScreenCapturer"
        private const val AVC = MediaFormat.MIMETYPE_VIDEO_AVC
        const val METHOD_STANDARD = 0
        const val METHOD_CODEC = 1

        /** Encoder picture width per quality step, for the Codec method. */
        val CODEC_SIZES = intArrayOf(320, 640, 1280)
    }

    private val thread = HandlerThread("HueCapture", Process.THREAD_PRIORITY_BACKGROUND).apply { start() }
    private val handler = Handler(thread.looper)

    private var display: VirtualDisplay? = null
    @Volatile private var running = false
    @Volatile private var fps = 15

    // What is currently built
    private var mode = -1
    private var sizeIndex = -1
    private var builtFps = -1

    // Output grid handed to the engine
    private var outW = 0
    private var outH = 0
    private var rgb = ByteArray(0)

    // Standard method
    private var reader: ImageReader? = null

    // Codec method
    private var encoder: MediaCodec? = null
    private var inputSurface: Surface? = null
    private var decoder: MediaCodec? = null
    private val encInfo = MediaCodec.BufferInfo()
    private val decInfo = MediaCodec.BufferInfo()

    fun start(method: Int, sizeIndex: Int, fps: Int) {
        this.fps = fps.coerceIn(1, 60)
        handler.post {
            running = true
            build(method, sizeIndex)
            handler.post(tick)
        }
    }

    /** Applies new settings while running; rebuilds the copy only when something structural changed. */
    fun configure(method: Int, sizeIndex: Int, fps: Int) {
        this.fps = fps.coerceIn(1, 60)
        handler.post {
            if (!running) return@post
            val fpsMatters = method == METHOD_CODEC && fps != builtFps
            if (method != mode || sizeIndex != this.sizeIndex || fpsMatters) build(method, sizeIndex)
        }
    }

    fun stop() {
        running = false
        handler.post {
            releaseSink()
            try {
                display?.release()
            } catch (_: Exception) {
            }
            display = null
            SyncState.captureInfo = ""
            thread.quitSafely()
        }
    }

    // ------------------------------------------------------------------ building

    private fun build(method: Int, size: Int) {
        releaseSink()
        val idx = size.coerceIn(0, Prefs.CAPTURE_SIZES.size - 1)
        val (gw, gh) = fit(Prefs.CAPTURE_SIZES[idx])
        outW = gw
        outH = gh
        rgb = ByteArray(gw * gh * 3)
        sizeIndex = idx
        try {
            if (method == METHOD_CODEC) {
                buildCodec(idx)
                mode = METHOD_CODEC
            } else {
                buildStandard()
                mode = METHOD_STANDARD
            }
        } catch (e: Exception) {
            Log.e(TAG, "Capture setup failed (method $method)", e)
            releaseSink()
            if (method == METHOD_CODEC) {
                SyncState.error = "Codec capture isn't available on this TV (${describe(e)}). Using Standard."
                try {
                    buildStandard()
                    mode = METHOD_STANDARD
                } catch (e2: Exception) {
                    Log.e(TAG, "Standard capture failed too", e2)
                    SyncState.error = "Screen capture failed: ${describe(e2)}"
                    mode = -1
                }
            } else {
                SyncState.error = "Screen capture failed: ${describe(e)}"
                mode = -1
            }
        }
    }

    private fun buildStandard() {
        val r = ImageReader.newInstance(outW, outH, PixelFormat.RGBA_8888, 2)
        reader = r
        attach(r.surface, outW, outH)
        SyncState.captureInfo = "Standard, ${outW}×$outH"
    }

    private fun buildCodec(idx: Int) {
        val (cw, ch) = fit(CODEC_SIZES[idx])
        val enc = createEncoder(cw, ch)
        encoder = enc
        val surface = enc.createInputSurface()
        inputSurface = surface
        attach(surface, cw, ch)
        enc.start()
        builtFps = fps
        SyncState.captureInfo = "Codec, ${cw}×$ch"
    }

    private fun attach(surface: Surface, w: Int, h: Int) {
        val d = display
        if (d == null) {
            display = projection.createVirtualDisplay(
                "HueTVSync", w, h, dpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC,
                surface, null, handler,
            )
        } else {
            d.resize(w, h, dpi)
            d.surface = surface
        }
    }

    private fun releaseSink() {
        try {
            display?.surface = null
        } catch (_: Exception) {
        }
        try {
            reader?.close()
        } catch (_: Exception) {
        }
        reader = null
        try {
            encoder?.stop()
        } catch (_: Exception) {
        }
        try {
            encoder?.release()
        } catch (_: Exception) {
        }
        encoder = null
        try {
            inputSurface?.release()
        } catch (_: Exception) {
        }
        inputSurface = null
        try {
            decoder?.stop()
        } catch (_: Exception) {
        }
        try {
            decoder?.release()
        } catch (_: Exception) {
        }
        decoder = null
    }

    /** Width x height at the screen's aspect ratio, both even. */
    private fun fit(longSide: Int): Pair<Int, Int> {
        val landscape = screenW >= screenH
        val shortSide = (longSide.toFloat() * minOf(screenW, screenH) / max(1, maxOf(screenW, screenH))).roundToInt()
        val a = max(2, longSide and 1.inv())
        val b = max(2, shortSide and 1.inv())
        return if (landscape) a to b else b to a
    }

    private fun createEncoder(w: Int, h: Int): MediaCodec {
        fun format(extras: Boolean) = MediaFormat.createVideoFormat(AVC, w, h).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, 1_000_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, 30)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            if (extras) {
                // Only encode as many frames as we read, and let the TV's own video come first.
                if (Build.VERSION.SDK_INT >= 29) setFloat(MediaFormat.KEY_MAX_FPS_TO_ENCODER, fps.toFloat())
                setInteger(MediaFormat.KEY_PRIORITY, 1)
            }
        }
        val enc = MediaCodec.createEncoderByType(AVC)
        try {
            enc.configure(format(true), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        } catch (e: Exception) {
            Log.w(TAG, "Encoder rejected extra settings, retrying plain", e)
            enc.reset()
            enc.configure(format(false), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        }
        return enc
    }

    /** A software decoder, so the tiny stream never competes with the 4K video for the hardware one. */
    private fun createDecoder(encoderFormat: MediaFormat): MediaCodec {
        val w = if (encoderFormat.containsKey(MediaFormat.KEY_WIDTH)) encoderFormat.getInteger(MediaFormat.KEY_WIDTH) else 320
        val h = if (encoderFormat.containsKey(MediaFormat.KEY_HEIGHT)) encoderFormat.getInteger(MediaFormat.KEY_HEIGHT) else 180
        val format = MediaFormat.createVideoFormat(AVC, w, h).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            encoderFormat.getByteBuffer("csd-0")?.let { setByteBuffer("csd-0", it) }
            encoderFormat.getByteBuffer("csd-1")?.let { setByteBuffer("csd-1", it) }
        }
        val software = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.firstOrNull { info ->
            !info.isEncoder &&
                info.supportedTypes.any { it.equals(AVC, ignoreCase = true) } &&
                (info.name.startsWith("c2.android.") || info.name.startsWith("OMX.google."))
        }
        val dec = if (software != null) MediaCodec.createByCodecName(software.name) else MediaCodec.createDecoderByType(AVC)
        dec.configure(format, null, null, 0)
        dec.start()
        SyncState.captureInfo = (SyncState.captureInfo.substringBefore(" ·")) + " · " + (software?.name ?: "hardware decoder")
        return dec
    }

    // ------------------------------------------------------------------ reading

    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            val start = SystemClock.uptimeMillis()
            try {
                when (mode) {
                    METHOD_STANDARD -> readStandard()
                    METHOD_CODEC -> readCodec()
                }
            } catch (e: Exception) {
                Log.w(TAG, "Frame read failed", e)
            }
            val wait = 1000L / fps - (SystemClock.uptimeMillis() - start)
            handler.postDelayed(this, max(1L, wait))
        }
    }

    private fun readStandard() {
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
            val w = minOf(img.width, outW)
            val h = minOf(img.height, outH)
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
        } finally {
            img.close()
        }
    }

    private fun readCodec() {
        val enc = encoder ?: return
        // 1. Move every finished encoded frame into the decoder.
        while (true) {
            val idx = enc.dequeueOutputBuffer(encInfo, 0)
            if (idx == MediaCodec.INFO_TRY_AGAIN_LATER) break
            if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                if (decoder == null) decoder = createDecoder(enc.outputFormat)
                continue
            }
            if (idx < 0) continue
            val isConfig = (encInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
            val dec = decoder
            if (dec != null && !isConfig && encInfo.size > 0) {
                val inIdx = dec.dequeueInputBuffer(10_000)
                if (inIdx >= 0) {
                    val src = enc.getOutputBuffer(idx)
                    val dst = dec.getInputBuffer(inIdx)
                    if (src != null && dst != null) {
                        src.position(encInfo.offset)
                        src.limit(encInfo.offset + encInfo.size)
                        dst.clear()
                        dst.put(src)
                        dec.queueInputBuffer(inIdx, 0, encInfo.size, encInfo.presentationTimeUs, encInfo.flags)
                    } else {
                        dec.queueInputBuffer(inIdx, 0, 0, encInfo.presentationTimeUs, 0)
                    }
                }
            }
            enc.releaseOutputBuffer(idx, false)
        }

        // 2. Keep only the newest decoded picture.
        val dec = decoder ?: return
        var newest = -1
        while (true) {
            val idx = dec.dequeueOutputBuffer(decInfo, 0)
            if (idx == MediaCodec.INFO_TRY_AGAIN_LATER) break
            if (idx < 0) continue
            if (newest >= 0) dec.releaseOutputBuffer(newest, false)
            if (decInfo.size > 0) {
                newest = idx
            } else {
                dec.releaseOutputBuffer(idx, false)
                newest = -1
            }
        }
        if (newest < 0) return
        val img = try {
            dec.getOutputImage(newest)
        } catch (e: Exception) {
            null
        }
        try {
            if (img != null && yuvToRgb(img)) sink(rgb, outW, outH, System.nanoTime())
        } finally {
            img?.close()
            dec.releaseOutputBuffer(newest, false)
        }
    }

    /** Averages 2x2 samples per output pixel from the decoded YUV picture (BT.601, video range). */
    private fun yuvToRgb(img: Image): Boolean {
        val planes = img.planes
        if (planes.size < 3) return false
        val crop = img.cropRect
        val cw = crop.width()
        val ch = crop.height()
        if (cw < 2 || ch < 2) return false
        val yB = planes[0].buffer
        val uB = planes[1].buffer
        val vB = planes[2].buffer
        val yRs = planes[0].rowStride
        val yPs = planes[0].pixelStride
        val uRs = planes[1].rowStride
        val uPs = planes[1].pixelStride
        val vRs = planes[2].rowStride
        val vPs = planes[2].pixelStride
        val yMax = yB.limit() - 1
        val uMax = uB.limit() - 1
        val vMax = vB.limit() - 1

        var o = 0
        for (oy in 0 until outH) {
            for (ox in 0 until outW) {
                var r = 0
                var g = 0
                var b = 0
                for (sj in 0 until 2) {
                    val sy = crop.top + ((oy * 4 + sj * 2 + 1) * ch) / (outH * 4)
                    for (si in 0 until 2) {
                        val sx = crop.left + ((ox * 4 + si * 2 + 1) * cw) / (outW * 4)
                        val y = (yB.get(minOf(sy * yRs + sx * yPs, yMax)).toInt() and 0xFF) - 16
                        val u = (uB.get(minOf((sy / 2) * uRs + (sx / 2) * uPs, uMax)).toInt() and 0xFF) - 128
                        val v = (vB.get(minOf((sy / 2) * vRs + (sx / 2) * vPs, vMax)).toInt() and 0xFF) - 128
                        val c = 298 * y
                        r += ((c + 409 * v + 128) shr 8).coerceIn(0, 255)
                        g += ((c - 100 * u - 208 * v + 128) shr 8).coerceIn(0, 255)
                        b += ((c + 516 * u + 128) shr 8).coerceIn(0, 255)
                    }
                }
                rgb[o++] = (r / 4).toByte()
                rgb[o++] = (g / 4).toByte()
                rgb[o++] = (b / 4).toByte()
            }
        }
        return true
    }
}
