package com.qi.huetvsync

import org.bouncycastle.tls.BasicTlsPSKIdentity
import org.bouncycastle.tls.CipherSuite
import org.bouncycastle.tls.DTLSClientProtocol
import org.bouncycastle.tls.DTLSTransport
import org.bouncycastle.tls.PSKTlsClient
import org.bouncycastle.tls.ProtocolVersion
import org.bouncycastle.tls.UDPTransport
import org.bouncycastle.tls.crypto.impl.bc.BcTlsCrypto
import java.io.IOException
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.security.SecureRandom

/**
 * Sends colors to the bridge with the Hue Entertainment API v2:
 * DTLS 1.2, pre-shared key (identity = application key, key = client key), UDP port 2100,
 * "HueStream" packets with 16-bit RGB per channel.
 */
class HueStreamer(
    private val ip: String,
    private val appKey: String,
    private val clientKeyHex: String,
    private val areaId: String,
) {
    private var socket: DatagramSocket? = null
    private var transport: DTLSTransport? = null
    private var sequence = 0
    private var buffer = ByteArray(0)
    private val header = "HueStream".toByteArray(Charsets.US_ASCII)
    private val areaBytes = areaId.toByteArray(Charsets.US_ASCII)

    fun connect() {
        close()
        val s = DatagramSocket()
        s.connect(InetSocketAddress(ip, 2100))
        socket = s
        val identity = BasicTlsPSKIdentity(appKey, hexToBytes(clientKeyHex))
        val client = object : PSKTlsClient(BcTlsCrypto(SecureRandom()), identity) {
            override fun getSupportedVersions(): Array<ProtocolVersion> = ProtocolVersion.DTLSv12.only()
            override fun getSupportedCipherSuites(): IntArray =
                intArrayOf(CipherSuite.TLS_PSK_WITH_AES_128_GCM_SHA256)
            override fun getHandshakeTimeoutMillis(): Int = 10_000
        }
        transport = DTLSClientProtocol().connect(client, UDPTransport(s, 1400))
    }

    /** [rgb] holds 3 values from 0 to 1 per channel, in the same order as [channelIds]. */
    fun send(channelIds: IntArray, rgb: FloatArray) {
        val t = transport ?: throw IOException("Not connected to the bridge.")
        val size = 52 + 7 * channelIds.size
        if (buffer.size != size) buffer = ByteArray(size)
        val b = buffer
        System.arraycopy(header, 0, b, 0, 9)
        b[9] = 2 // API version 2.0
        b[10] = 0
        b[11] = (sequence and 0xFF).toByte()
        sequence++
        b[12] = 0
        b[13] = 0
        b[14] = 0 // color space: RGB
        b[15] = 0
        System.arraycopy(areaBytes, 0, b, 16, minOf(36, areaBytes.size))
        var o = 52
        for (i in channelIds.indices) {
            b[o++] = channelIds[i].toByte()
            for (c in 0 until 3) {
                val v = (rgb[i * 3 + c].coerceIn(0f, 1f) * 65535f + 0.5f).toInt()
                b[o++] = (v shr 8).toByte()
                b[o++] = v.toByte()
            }
        }
        t.send(b, 0, size)
    }

    fun close() {
        try {
            transport?.close()
        } catch (_: Exception) {
        }
        transport = null
        try {
            socket?.close()
        } catch (_: Exception) {
        }
        socket = null
    }

    private fun hexToBytes(hex: String): ByteArray =
        ByteArray(hex.length / 2) { i -> hex.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
}
