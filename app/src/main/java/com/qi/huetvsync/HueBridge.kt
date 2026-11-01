package com.qi.huetvsync

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

data class HueChannel(val id: Int, val x: Double, val y: Double, val z: Double)

data class EntertainmentArea(val id: String, val name: String, val channels: List<HueChannel>)

class LinkButtonNotPressedException :
    IOException("Press the round button on top of the Hue Bridge, then choose Pair again within 30 seconds.")

object Http {
    /** The bridge uses a certificate signed by Signify, not a public authority, so it is accepted for local bridge calls only. */
    private val bridgeSsl: SSLContext by lazy {
        val trustBridge = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        }
        SSLContext.getInstance("TLS").apply { init(null, arrayOf<TrustManager>(trustBridge), SecureRandom()) }
    }

    fun request(
        url: String,
        method: String = "GET",
        body: String? = null,
        headers: Map<String, String> = emptyMap(),
        localBridge: Boolean = false,
        timeoutMs: Int = 8000,
    ): Pair<Int, String> {
        val conn = URL(url).openConnection() as HttpURLConnection
        if (localBridge && conn is HttpsURLConnection) {
            conn.sslSocketFactory = bridgeSsl.socketFactory
            conn.hostnameVerifier = HostnameVerifier { _, _ -> true }
        }
        try {
            conn.requestMethod = method
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
            conn.setRequestProperty("User-Agent", "HueTVSync")
            for ((k, v) in headers) conn.setRequestProperty(k, v)
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.outputStream.use { it.write(body.toByteArray()) }
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
            return code to text
        } finally {
            conn.disconnect()
        }
    }
}

class HueBridge(private val ip: String, private val appKey: String) {

    companion object {
        /** Asks Philips' discovery service which bridges are on this network. */
        fun discover(): List<String> {
            val (code, text) = Http.request("https://discovery.meethue.com/")
            if (code != 200) throw IOException("Bridge search failed (HTTP $code). Enter the IP address instead.")
            val arr = JSONArray(text)
            return (0 until arr.length())
                .mapNotNull { arr.getJSONObject(it).optString("internalipaddress").takeIf { ip -> ip.isNotEmpty() } }
                .distinct()
        }

        /** Needs the bridge's round button pressed shortly before. Returns (application key, client key). */
        fun pair(ip: String): Pair<String, String> {
            val body = JSONObject()
                .put("devicetype", "huetvsync#androidtv")
                .put("generateclientkey", true)
                .toString()
            val (code, text) = Http.request("https://$ip/api", "POST", body, localBridge = true)
            if (code != 200) throw IOException("The bridge answered HTTP $code.")
            val first = JSONArray(text).getJSONObject(0)
            val err = first.optJSONObject("error")
            if (err != null) {
                if (err.optInt("type") == 101) throw LinkButtonNotPressedException()
                throw IOException(err.optString("description", "Pairing failed."))
            }
            val ok = first.getJSONObject("success")
            return ok.getString("username") to ok.getString("clientkey")
        }
    }

    private fun clip(path: String, method: String = "GET", body: String? = null): JSONObject {
        val (code, text) = Http.request(
            "https://$ip/clip/v2/resource/$path", method, body,
            mapOf("hue-application-key" to appKey), localBridge = true,
        )
        if (code == 401 || code == 403) throw IOException("The bridge no longer accepts this app. Pair again.")
        if (code !in 200..299) throw IOException("The bridge answered HTTP $code: ${text.take(160)}")
        return JSONObject(text)
    }

    fun areas(): List<EntertainmentArea> {
        val data = clip("entertainment_configuration").optJSONArray("data") ?: JSONArray()
        return (0 until data.length()).map { i ->
            val o = data.getJSONObject(i)
            val chans = o.optJSONArray("channels") ?: JSONArray()
            val name = o.optJSONObject("metadata")?.optString("name")?.takeIf { it.isNotEmpty() } ?: "Area ${i + 1}"
            EntertainmentArea(
                id = o.getString("id"),
                name = name,
                channels = (0 until chans.length()).map { j ->
                    val c = chans.getJSONObject(j)
                    val p = c.optJSONObject("position") ?: JSONObject()
                    HueChannel(
                        id = c.getInt("channel_id"),
                        x = p.optDouble("x", 0.0),
                        y = p.optDouble("y", 0.0),
                        z = p.optDouble("z", 0.0),
                    )
                }.sortedBy { it.id },
            )
        }
    }

    fun setStreaming(areaId: String, on: Boolean) {
        val body = JSONObject().put("action", if (on) "start" else "stop").toString()
        clip("entertainment_configuration/$areaId", "PUT", body)
    }

    /** True while the bridge considers this area's stream active. */
    fun isStreaming(areaId: String): Boolean {
        val data = clip("entertainment_configuration/$areaId").optJSONArray("data") ?: return false
        if (data.length() == 0) return false
        return data.getJSONObject(0).optString("status") == "active"
    }
}
