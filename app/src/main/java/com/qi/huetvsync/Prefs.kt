package com.qi.huetvsync

import android.content.Context

/** Everything the sync loop needs to know about how to process colors. */
data class Tuning(
    val fps: Int,
    val sizeIndex: Int,
    val smoothingMs: Int,
    val delayMs: Int,
    val brightness: Int,
    val saturation: Int,
    val gammaTenths: Int,
    val layoutMode: Int,
    val mirror: Boolean,
    val blackBars: Boolean,
)

class Prefs(context: Context) {
    companion object {
        /** Longest side of the captured picture, in pixels: Small, Medium, Large. */
        val CAPTURE_SIZES = intArrayOf(32, 64, 128)
        val CAPTURE_SIZE_NAMES = arrayOf("Small", "Medium", "Large")
        val LAYOUT_NAMES = arrayOf("Auto (from Hue area)", "Strip order", "Strip order, reversed")
    }

    private val sp = context.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private fun str(key: String) = sp.getString(key, "") ?: ""
    private fun putStr(key: String, value: String) = sp.edit().putString(key, value).apply()
    private fun putInt(key: String, value: Int) = sp.edit().putInt(key, value).apply()
    private fun putBool(key: String, value: Boolean) = sp.edit().putBoolean(key, value).apply()

    var bridgeIp: String
        get() = str("bridgeIp")
        set(v) = putStr("bridgeIp", v.trim())
    var appKey: String
        get() = str("appKey")
        set(v) = putStr("appKey", v)
    var clientKey: String
        get() = str("clientKey")
        set(v) = putStr("clientKey", v)
    var areaId: String
        get() = str("areaId")
        set(v) = putStr("areaId", v)
    var areaName: String
        get() = str("areaName")
        set(v) = putStr("areaName", v)

    var fps: Int
        get() = sp.getInt("fps", 15)
        set(v) = putInt("fps", v.coerceIn(5, 30))
    var sizeIndex: Int
        get() = sp.getInt("sizeIndex", 0)
        set(v) = putInt("sizeIndex", v.coerceIn(0, CAPTURE_SIZES.size - 1))
    var smoothingMs: Int
        get() = sp.getInt("smoothingMs", 100)
        set(v) = putInt("smoothingMs", v.coerceIn(0, 500))
    var delayMs: Int
        get() = sp.getInt("delayMs", 0)
        set(v) = putInt("delayMs", v.coerceIn(0, 500))
    var brightness: Int
        get() = sp.getInt("brightness", 100)
        set(v) = putInt("brightness", v.coerceIn(10, 100))
    var saturation: Int
        get() = sp.getInt("saturation", 110)
        set(v) = putInt("saturation", v.coerceIn(50, 200))
    var gammaTenths: Int
        get() = sp.getInt("gammaTenths", 20)
        set(v) = putInt("gammaTenths", v.coerceIn(10, 30))
    var layoutMode: Int
        get() = sp.getInt("layoutMode", 0)
        set(v) = putInt("layoutMode", v.coerceIn(0, LAYOUT_NAMES.size - 1))
    var mirror: Boolean
        get() = sp.getBoolean("mirror", false)
        set(v) = putBool("mirror", v)
    var blackBars: Boolean
        get() = sp.getBoolean("blackBars", true)
        set(v) = putBool("blackBars", v)
    var startOnBoot: Boolean
        get() = sp.getBoolean("startOnBoot", false)
        set(v) = putBool("startOnBoot", v)

    val isPaired: Boolean
        get() = bridgeIp.isNotEmpty() && appKey.isNotEmpty() && clientKey.isNotEmpty()
    val isReady: Boolean
        get() = isPaired && areaId.isNotEmpty()

    fun tuning() = Tuning(
        fps = fps,
        sizeIndex = sizeIndex,
        smoothingMs = smoothingMs,
        delayMs = delayMs,
        brightness = brightness,
        saturation = saturation,
        gammaTenths = gammaTenths,
        layoutMode = layoutMode,
        mirror = mirror,
        blackBars = blackBars,
    )
}
