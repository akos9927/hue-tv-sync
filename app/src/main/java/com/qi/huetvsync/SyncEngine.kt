package com.qi.huetvsync

import android.graphics.RectF
import android.util.Log
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.pow

/**
 * Turns captured frames into one color per Hue channel and streams them to the bridge.
 *
 * Capture thread -> [onFrame] -> queue of timestamped colors.
 * Sender thread  -> picks the frame that is [Tuning.delayMs] old, smooths it, applies gamma
 *                   and brightness, and sends it 50 times a second.
 */
class SyncEngine(
    private val ip: String,
    private val appKey: String,
    private val clientKey: String,
    val area: EntertainmentArea,
    initial: Tuning,
) {
    companion object {
        private const val TAG = "SyncEngine"
        private const val SEND_INTERVAL_MS = 20L // 50 updates per second to the bridge
        private const val HALF_BOX = 0.17f // each light reads a box about a third of the screen wide
        private const val SCREEN_HEIGHT_RATIO = 0.5625f // 16:9
        private const val DARK_LEVEL = 18 // out of 255: darker than this counts as a black bar
        private const val TEST_STEP_NS = 1_500_000_000L
    }

    private val bridge = HueBridge(ip, appKey)
    private val channelIds = area.channels.map { it.id }.toIntArray()
    private val n = channelIds.size

    @Volatile private var tuning = initial

    /** One box per channel, as fractions (0..1) of the picture area without black bars. */
    @Volatile var regions: List<RectF> = computeRegions(initial)
        private set

    private class Frame(val timeNs: Long, val colors: FloatArray)

    private val frames = ArrayDeque<Frame>()
    private val lock = Any()
    private var target: FloatArray? = null

    @Volatile private var running = false
    @Volatile private var paused = false
    @Volatile private var needsReconnect = false
    @Volatile private var testStartNs = 0L
    @Volatile private var testUntilNs = 0L
    private var sender: Thread? = null
    private var watchdog: Thread? = null

    private val capturedFrames = AtomicInteger()
    private val sentFrames = AtomicInteger()
    private var statsStart = System.nanoTime()

    // Black bar detection state (capture thread only)
    private var cropTop = 0f
    private var cropLeft = 0f
    private var topCandidate = -1
    private var topSince = 0L
    private var leftCandidate = -1
    private var leftSince = 0L

    fun updateTuning(t: Tuning) {
        tuning = t
        regions = computeRegions(t)
        if (!t.blackBars) {
            SyncState.cropTop = 0f
            SyncState.cropLeft = 0f
        }
    }

    fun start() {
        running = true
        sender = Thread({ sendLoop() }, "HueSender").apply { start() }
        watchdog = Thread({ watchLoop() }, "HueWatchdog").apply { isDaemon = true; start() }
    }

    /** Blocks briefly; call off the main thread. */
    fun stop() {
        running = false
        watchdog?.interrupt()
        sender?.interrupt()
        sender?.join(4000)
        sender = null
        watchdog = null
    }

    fun setPaused(p: Boolean) {
        paused = p
    }

    /** Lights each segment in turn for 1.5 s, in the order of the on-screen preview. */
    fun startLayoutTest() {
        val now = System.nanoTime()
        testStartNs = now
        testUntilNs = now + n * TEST_STEP_NS
    }

    // ---------------------------------------------------------------- capture side

    fun onFrame(rgb: ByteArray, w: Int, h: Int, timeNs: Long) {
        if (w < 2 || h < 2 || n == 0) return
        val t = tuning
        if (t.blackBars) detectBars(rgb, w, h) else {
            cropTop = 0f
            cropLeft = 0f
        }
        val regs = regions
        val sat = t.saturation / 100f
        val colors = FloatArray(n * 3)
        val cw = 1f - 2f * cropLeft
        val ch = 1f - 2f * cropTop
        for (i in 0 until n) {
            val r = regs[i]
            val x0 = ((cropLeft + r.left * cw) * w).toInt().coerceIn(0, w - 1)
            val x1 = ceil((cropLeft + r.right * cw) * w).toInt().coerceIn(x0 + 1, w)
            val y0 = ((cropTop + r.top * ch) * h).toInt().coerceIn(0, h - 1)
            val y1 = ceil((cropTop + r.bottom * ch) * h).toInt().coerceIn(y0 + 1, h)
            var sr = 0L
            var sg = 0L
            var sb = 0L
            var count = 0
            for (y in y0 until y1) {
                var p = (y * w + x0) * 3
                for (x in x0 until x1) {
                    sr += rgb[p].toInt() and 0xFF
                    sg += rgb[p + 1].toInt() and 0xFF
                    sb += rgb[p + 2].toInt() and 0xFF
                    p += 3
                    count++
                }
            }
            if (count == 0) continue
            var red = sr / (255f * count)
            var green = sg / (255f * count)
            var blue = sb / (255f * count)
            if (sat != 1f) {
                val l = 0.2126f * red + 0.7152f * green + 0.0722f * blue
                red = (l + (red - l) * sat).coerceIn(0f, 1f)
                green = (l + (green - l) * sat).coerceIn(0f, 1f)
                blue = (l + (blue - l) * sat).coerceIn(0f, 1f)
            }
            colors[i * 3] = red
            colors[i * 3 + 1] = green
            colors[i * 3 + 2] = blue
        }
        synchronized(lock) {
            frames.addLast(Frame(timeNs, colors))
            while (frames.size > 120) frames.removeFirst()
        }
        SyncState.previewColors = colors
        SyncState.cropTop = cropTop
        SyncState.cropLeft = cropLeft
        capturedFrames.incrementAndGet()
    }

    /** Letterbox and pillarbox detection. Bars shrink at once, but only grow after 1.5 s of the same reading. */
    private fun detectBars(rgb: ByteArray, w: Int, h: Int) {
        fun rowDark(y: Int): Boolean {
            var p = y * w * 3
            for (x in 0 until w) {
                if ((rgb[p].toInt() and 0xFF) > DARK_LEVEL || (rgb[p + 1].toInt() and 0xFF) > DARK_LEVEL ||
                    (rgb[p + 2].toInt() and 0xFF) > DARK_LEVEL
                ) return false
                p += 3
            }
            return true
        }

        fun colDark(x: Int): Boolean {
            for (y in 0 until h) {
                val p = (y * w + x) * 3
                if ((rgb[p].toInt() and 0xFF) > DARK_LEVEL || (rgb[p + 1].toInt() and 0xFF) > DARK_LEVEL ||
                    (rgb[p + 2].toInt() and 0xFF) > DARK_LEVEL
                ) return false
            }
            return true
        }

        val now = System.nanoTime()
        var top = 0
        while (top < h / 2 && rowDark(top)) top++
        // A completely dark frame says nothing about bars: keep what we have.
        if (top < h / 2 && top <= (h * 0.3f).toInt()) {
            val frac = top.toFloat() / h
            if (frac < cropTop) {
                cropTop = frac
                topCandidate = -1
            } else if (frac > cropTop) {
                if (top != topCandidate) {
                    topCandidate = top
                    topSince = now
                } else if (now - topSince > 1_500_000_000L) {
                    cropTop = frac
                }
            } else {
                topCandidate = -1
            }
        }
        if (top >= h / 2) return
        var left = 0
        while (left < w / 2 && colDark(left)) left++
        if (left <= (w * 0.3f).toInt()) {
            val frac = left.toFloat() / w
            if (frac < cropLeft) {
                cropLeft = frac
                leftCandidate = -1
            } else if (frac > cropLeft) {
                if (left != leftCandidate) {
                    leftCandidate = left
                    leftSince = now
                } else if (now - leftSince > 1_500_000_000L) {
                    cropLeft = frac
                }
            } else {
                leftCandidate = -1
            }
        }
    }

    // ---------------------------------------------------------------- sender side

    private fun pickTarget(cutoffNs: Long): FloatArray? {
        synchronized(lock) {
            while (frames.isNotEmpty() && frames.first().timeNs <= cutoffNs) {
                target = frames.removeFirst().colors
            }
            return target
        }
    }

    private fun sendLoop() {
        val streamer = HueStreamer(ip, appKey, clientKey, area.id)
        var connected = false
        var everConnected = false
        val current = FloatArray(n * 3)
        val out = FloatArray(n * 3)
        var lastNs = System.nanoTime()
        var backoffMs = 1000L
        while (running) {
            try {
                if (paused) {
                    if (connected) {
                        streamer.close()
                        stopBridgeStream()
                        connected = false
                    }
                    SyncState.status = "Paused while the TV screen is off"
                    Thread.sleep(250)
                    continue
                }
                if (!connected || needsReconnect) {
                    needsReconnect = false
                    streamer.close()
                    SyncState.status = "Connecting to the Hue Bridge…"
                    bridge.setStreaming(area.id, true)
                    streamer.connect()
                    connected = true
                    everConnected = true
                    backoffMs = 1000L
                    SyncState.error = null
                    SyncState.status = "Syncing \"${area.name}\""
                    lastNs = System.nanoTime()
                }

                val now = System.nanoTime()
                val dt = (now - lastNs) / 1_000_000_000f
                lastNs = now
                val t = tuning
                val tgt = pickTarget(now - t.delayMs * 1_000_000L)
                if (tgt != null) {
                    val alpha = if (t.smoothingMs <= 0) 1f else 1f - exp(-dt / (t.smoothingMs / 3000f))
                    for (i in current.indices) current[i] += (tgt[i] - current[i]) * alpha
                }
                if (now < testUntilNs) {
                    val lit = ((now - testStartNs) / TEST_STEP_NS).toInt().coerceIn(0, n - 1)
                    SyncState.testChannel = lit
                    for (i in 0 until n) {
                        val v = if (i == lit) 1f else 0f
                        out[i * 3] = v
                        out[i * 3 + 1] = v
                        out[i * 3 + 2] = v
                    }
                } else {
                    SyncState.testChannel = -1
                    val gamma = t.gammaTenths / 10f
                    val bri = t.brightness / 100f
                    for (i in current.indices) out[i] = current[i].coerceIn(0f, 1f).pow(gamma) * bri
                }
                streamer.send(channelIds, out)
                sentFrames.incrementAndGet()
                updateStats(now)

                val sleep = SEND_INTERVAL_MS - (System.nanoTime() - now) / 1_000_000L
                if (sleep > 0) Thread.sleep(sleep)
            } catch (e: InterruptedException) {
                break
            } catch (e: Exception) {
                Log.w(TAG, "Stream error", e)
                SyncState.error = describe(e)
                SyncState.status = "Reconnecting in ${backoffMs / 1000} s…"
                streamer.close()
                connected = false
                try {
                    Thread.sleep(backoffMs)
                } catch (_: InterruptedException) {
                    break
                }
                backoffMs = min(backoffMs * 2, 15_000L)
            }
        }
        streamer.close()
        if (everConnected) stopBridgeStream()
        SyncState.captureFps = 0f
        SyncState.sendFps = 0f
        SyncState.previewColors = null
        SyncState.testChannel = -1
    }

    /** Every 10 s, checks the bridge still has our stream (another app may have taken over). */
    private fun watchLoop() {
        while (running) {
            try {
                Thread.sleep(10_000)
                if (!running || paused) continue
                if (!bridge.isStreaming(area.id)) needsReconnect = true
            } catch (_: InterruptedException) {
                return
            } catch (e: Exception) {
                Log.w(TAG, "Watchdog check failed", e)
            }
        }
    }

    private fun stopBridgeStream() {
        try {
            bridge.setStreaming(area.id, false)
        } catch (e: Exception) {
            Log.w(TAG, "Could not stop the stream on the bridge", e)
        }
    }

    private fun updateStats(now: Long) {
        val elapsed = now - statsStart
        if (elapsed >= 1_000_000_000L) {
            val secs = elapsed / 1_000_000_000f
            SyncState.captureFps = capturedFrames.getAndSet(0) / secs
            SyncState.sendFps = sentFrames.getAndSet(0) / secs
            statsStart = now
        }
    }

    // ---------------------------------------------------------------- layout

    /**
     * Places each channel on the strip's path around the TV (up the left side, across the top,
     * down the right side) and gives it a box of screen to read.
     */
    private fun computeRegions(t: Tuning): List<RectF> {
        if (n == 0) return emptyList()
        val path = stripPoints(n)
        val order: List<Int> = when (t.layoutMode) {
            1 -> (0 until n).toList()
            2 -> (n - 1 downTo 0).toList()
            else -> autoOrder() ?: (0 until n).toList()
        }
        val points = Array(n) { 0.5f to 0f }
        order.forEachIndexed { k, channelIndex -> points[channelIndex] = path[k] }
        return points.map { (u0, v) ->
            val u = if (t.mirror) 1f - u0 else u0
            RectF(
                (u - HALF_BOX).coerceAtLeast(0f),
                (v - HALF_BOX).coerceAtLeast(0f),
                (u + HALF_BOX).coerceAtMost(1f),
                (v + HALF_BOX).coerceAtMost(1f),
            )
        }
    }

    /** n evenly spaced points along the left, top and right edges, starting at the bottom left. */
    private fun stripPoints(count: Int): List<Pair<Float, Float>> {
        val hr = SCREEN_HEIGHT_RATIO
        val total = 2f * hr + 1f
        return (0 until count).map { k ->
            val s = (k + 0.5f) / count * total
            when {
                s < hr -> 0f to (1f - s / hr)
                s < hr + 1f -> (s - hr) to 0f
                else -> 1f to ((s - hr - 1f) / hr)
            }
        }
    }

    /** Orders channels along the strip path using the positions stored in the Hue entertainment area. */
    private fun autoOrder(): List<Int>? {
        val xs = area.channels.map { it.x }
        val zs = area.channels.map { it.z }
        val minX = xs.minOrNull() ?: return null
        val maxX = xs.maxOrNull() ?: return null
        val minZ = zs.minOrNull() ?: return null
        val maxZ = zs.maxOrNull() ?: return null
        val xr = maxX - minX
        val zr = maxZ - minZ
        if (xr < 0.05) return null
        val hr = SCREEN_HEIGHT_RATIO
        return area.channels.mapIndexed { i, c ->
            val u = ((c.x - minX) / xr).toFloat()
            val v = if (zr < 0.05) 0f else ((maxZ - c.z) / zr).toFloat()
            val dl = u
            val dr = 1f - u
            val dt = v
            val s = when {
                dl <= dt && dl <= dr -> (1f - v) * hr
                dr < dl && dr <= dt -> hr + 1f + v * hr
                else -> hr + u
            }
            i to s
        }.sortedBy { it.second }.map { it.first }
    }
}
