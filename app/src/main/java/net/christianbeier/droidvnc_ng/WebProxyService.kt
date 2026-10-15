package net.christianbeier.droidvnc_ng

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.widget.Toast
import androidx.preference.PreferenceManager
import java.net.HttpURLConnection
import java.net.URL

object WebProxyService {
    private const val TAG = "WebProxyService"

    /** Called only after a successful VNC start. A failed publication never stops VNC. */
    @JvmStatic
    fun publish(context: Context) {
        val app = context.applicationContext
        Log.i(TAG, "VNC started; scheduling Web Proxy Service publication")
        Utils.runOnIoThread {
            val started = SystemClock.elapsedRealtime()
            var stage = "loading preferences"
            var password = ""
            fun redact(value: String): String {
                var safe = value.replace(Regex("(?i)(password=)[^&\\s]*"), "$1[REDACTED]")
                if (password.isNotEmpty()) {
                    safe = safe.replace(Uri.encode(password), "[REDACTED]")
                        .replace(password, "[REDACTED]")
                }
                return safe
            }
            try {
                val prefs = PreferenceManager.getDefaultSharedPreferences(app)
                val endpoint = prefs.getString(Constants.PREFS_KEY_SETTINGS_WEB_PROXY_ENDPOINT, "").orEmpty().trim()
                if (endpoint.isEmpty()) {
                    Log.i(TAG, "Publication skipped: endpoint is empty")
                    return@runOnIoThread
                }
                password = prefs.getString(Constants.PREFS_KEY_SETTINGS_WEB_PROXY_PASSWORD, "").orEmpty()
                stage = "validating endpoint and password"
                val uri = Uri.parse(endpoint)
                require(uri.scheme in listOf("https", "http") && !uri.host.isNullOrBlank()) { "Endpoint must be an HTTP(S) URL with a host" }
                require(uri.fragment == null && uri.userInfo == null) { "Endpoint must not contain a fragment or user info" }
                require("password" !in uri.queryParameterNames && "redirect-url" !in uri.queryParameterNames) { "Endpoint must not already contain password or redirect-url parameters" }
                Log.i(TAG, "Endpoint: ${uri.buildUpon().clearQuery().build()}; password configured=${password.isNotEmpty()}")
                require(password.isNotEmpty()) { "Web Proxy Service password is empty" }

                stage = "selecting LAN browser URL"
                val port = MainService.getPort()
                require(port in 101..65535) { "Cannot generate browser URL for VNC port $port" }
                val hosts = MainService.getIPv4s()
                Log.d(TAG, "VNC port=$port; available IPv4 addresses=$hosts")
                val host = hosts.firstOrNull(Utils::isShareableIPv4)
                    ?: error("No LAN IPv4 address available")
                val redirectUrl = Utils.browserAccessUrl(host, port)
                Log.i(TAG, "Publishing redirect URL: $redirectUrl")
                val request = uri.buildUpon()
                    .appendQueryParameter("password", password)
                    .appendQueryParameter("redirect-url", redirectUrl)
                    .build()
                stage = "opening HTTP connection"
                val connection = URL(request.toString()).openConnection() as HttpURLConnection
                try {
                    connection.requestMethod = "GET"
                    connection.connectTimeout = 10000
                    connection.readTimeout = 10000
                    connection.instanceFollowRedirects = false
                    connection.useCaches = false
                    connection.setRequestProperty("Accept", "text/plain")
                    connection.setRequestProperty("Cache-Control", "no-store")
                    stage = "waiting for HTTP response"
                    val status = connection.responseCode
                    Log.i(TAG, "HTTP $status; content-type=${connection.contentType}; elapsed=${SystemClock.elapsedRealtime() - started}ms")
                    stage = "reading HTTP response"
                    val stream = if (status >= 400) connection.errorStream else connection.inputStream
                    val body = stream?.bufferedReader()?.use { reader ->
                        val buffer = CharArray(2049)
                        var count = 0
                        while (count < buffer.size) {
                            val read = reader.read(buffer, count, buffer.size - count)
                            if (read < 0) break
                            count += read
                        }
                        String(buffer, 0, count)
                    }.orEmpty()
                    Log.d(TAG, "Response body (up to 2048 chars): ${redact(body.take(2048))}${if (body.length > 2048) " [truncated]" else ""}")
                    check(status == HttpURLConnection.HTTP_OK) { "Backend returned HTTP $status" }
                    // The PHP endpoint acknowledges a persisted update with exactly "OK".
                    check(body == "OK") { "Expected exact OK acknowledgment; inspect the response body" }
                    Log.i(TAG, "Publication succeeded in ${SystemClock.elapsedRealtime() - started}ms")
                } finally {
                    connection.disconnect()
                }
            } catch (error: Exception) {
                Log.e(TAG, "Publication failed while $stage after ${SystemClock.elapsedRealtime() - started}ms: ${redact(Log.getStackTraceString(error))}")
                Handler(Looper.getMainLooper()).post {
                    Toast.makeText(app, R.string.ui_web_proxy_failed, Toast.LENGTH_LONG).show()
                }
            }
        }
    }
}
