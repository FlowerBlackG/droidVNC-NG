/* DroidVNC-NG main activity.
 * Copyright (C) 2020 Kitchen Armor. Author: Christian Beier <info@christianbeier.net>
 * SPDX-License-Identifier: GPL-2.0-only
 */
package net.christianbeier.droidvnc_ng.ui

import android.Manifest
import android.content.*
import android.content.pm.PackageManager
import android.net.*
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.*
import androidx.core.content.ContextCompat
import androidx.preference.PreferenceManager
import net.christianbeier.droidvnc_ng.Constants
import net.christianbeier.droidvnc_ng.Defaults
import net.christianbeier.droidvnc_ng.InputService
import net.christianbeier.droidvnc_ng.MainService
import net.christianbeier.droidvnc_ng.R
import net.christianbeier.droidvnc_ng.Utils
import net.christianbeier.droidvnc_ng.capture.mediaprojection.MediaProjectionRequestActivity
import net.christianbeier.droidvnc_ng.capture.mediaprojection.MediaProjectionService
import net.christianbeier.droidvnc_ng.input.InputRequestActivity
import net.christianbeier.droidvnc_ng.server.ClientList
import java.net.NetworkInterface
import java.util.UUID

/** Android/service integration. Compose renders snapshots and sends user actions back here. */
class MainActivity : ComponentActivity() {
    internal lateinit var prefs: SharedPreferences
    internal lateinit var defaults: Defaults
    internal var running by mutableStateOf(false)
    internal var busy by mutableStateOf(false)
    internal var hosts by mutableStateOf<List<String>>(emptyList())
    internal var interfaces by mutableStateOf<List<String>>(emptyList())
    internal var clients by mutableStateOf<List<ClientList.Client>>(emptyList())
    internal var inputGranted by mutableStateOf(false)
    internal var notificationsGranted by mutableStateOf(false)
    internal var captureGranted by mutableStateOf(false)
    internal var fallback by mutableStateOf(false)
    internal var preferenceRevision by mutableIntStateOf(0)
    internal var pending by mutableStateOf<OutgoingRequest?>(null)
    private var resumed = false
    private val handler = Handler(Looper.getMainLooper())
    private val networks = mutableMapOf<Long, String>()
    private val connectivity by lazy { getSystemService(ConnectivityManager::class.java) }
    private val preferenceListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> preferenceRevision++ }

    internal data class OutgoingRequest(val id: String, val host: String, val port: Int, val repeaterId: String?)

    private val poll = object : Runnable {
        override fun run() {
            if (!resumed || !running) return
            send(serviceIntent(MainService.ACTION_GET_CLIENTS).putExtra(MainService.EXTRA_RECEIVER, packageName))
            handler.postDelayed(this, 1000)
        }
    }
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                MainService.ACTION_START, MainService.ACTION_STOP -> {
                    busy = false
                    refresh()
                    if (!running && intent.action == MainService.ACTION_STOP) pending = null
                    restartPolling()
                }
                MainService.ACTION_GET_CLIENTS -> {
                    if (running) clients = ClientList.fromJson(intent.getStringExtra(MainService.EXTRA_CLIENTS)).getClients()
                }
                MainService.ACTION_CONNECT_REVERSE, MainService.ACTION_CONNECT_REPEATER -> {
                    val request = pending ?: return
                    if (intent.getStringExtra(MainService.EXTRA_REQUEST_ID) != request.id) return
                    val success = intent.getBooleanExtra(MainService.EXTRA_REQUEST_SUCCESS, false)
                    val endpoint = formatEndpoint(request.host, request.port)
                    val message = if (request.repeaterId == null) {
                        getString(if (success) R.string.main_activity_reverse_vnc_success else R.string.main_activity_reverse_vnc_fail, endpoint)
                    } else {
                        getString(if (success) R.string.main_activity_repeater_vnc_success else R.string.main_activity_repeater_vnc_fail, endpoint, request.repeaterId)
                    }
                    if (success) prefs.edit().apply {
                        putString(if (request.repeaterId == null) "reverse_vnc_last_host" else "repeater_vnc_last_host", endpoint)
                        request.repeaterId?.let { putString("repeater_vnc_last_id", it) }
                    }.apply()
                    pending = null
                    Toast.makeText(this@MainActivity, message, Toast.LENGTH_LONG).show()
                }
            }
        }
    }
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onLinkPropertiesChanged(network: Network, properties: LinkProperties) {
            runOnUiThread {
                networks[network.networkHandle] = properties.interfaceName.orEmpty()
                refreshNetwork()
            }
        }
        override fun onLost(network: Network) {
            runOnUiThread { networks.remove(network.networkHandle); refreshNetwork() }
        }
    }
    private val hotspotReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.getIntExtra("wifi_state", 14)) {
                11 -> networks.remove(-1L)
                13 -> intent.getStringExtra("android.net.wifi.extra.WIFI_AP_INTERFACE_NAME")?.let { networks[-1L] = it }
            }
            refreshNetwork()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        prefs = PreferenceManager.getDefaultSharedPreferences(this)
        defaults = Defaults(this)
        prefs.registerOnSharedPreferenceChangeListener(preferenceListener)
        savedInstanceState?.let {
            busy = it.getBoolean("busy")
            it.getString("requestId")?.let { id ->
                pending = OutgoingRequest(id, it.getString("requestHost").orEmpty(), it.getInt("requestPort"), it.getString("repeaterId"))
            }
        }
        ContextCompat.registerReceiver(this, receiver, IntentFilter().apply {
            addAction(MainService.ACTION_START)
            addAction(MainService.ACTION_STOP)
            addAction(MainService.ACTION_GET_CLIENTS)
            addAction(MainService.ACTION_CONNECT_REVERSE)
            addAction(MainService.ACTION_CONNECT_REPEATER)
        }, ContextCompat.RECEIVER_NOT_EXPORTED)
        connectivity.registerNetworkCallback(NetworkRequest.Builder().removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN).build(), networkCallback)
        ContextCompat.registerReceiver(this, hotspotReceiver, IntentFilter("android.net.wifi.WIFI_AP_STATE_CHANGED"), ContextCompat.RECEIVER_EXPORTED)
        refresh()
        setContent { VncTheme { MainScreen(this) } }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean("busy", busy)
        pending?.let {
            outState.putString("requestId", it.id)
            outState.putString("requestHost", it.host)
            outState.putInt("requestPort", it.port)
            outState.putString("repeaterId", it.repeaterId)
        }
    }
    override fun onResume() {
        super.onResume()
        resumed = true
        refresh()
        restartPolling()
    }
    override fun onPause() {
        resumed = false
        handler.removeCallbacks(poll)
        super.onPause()
    }
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && ::prefs.isInitialized) refresh()
    }
    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        prefs.unregisterOnSharedPreferenceChangeListener(preferenceListener)
        unregisterReceiver(receiver)
        unregisterReceiver(hotspotReceiver)
        connectivity.unregisterNetworkCallback(networkCallback)
        super.onDestroy()
    }
    private fun restartPolling() {
        handler.removeCallbacks(poll)
        if (resumed && running) handler.post(poll)
    }
    private fun refresh() {
        val active = MainService.isServerActive()
        val changed = active != running
        if (changed) busy = false
        running = active
        if (!running) clients = emptyList()
        inputGranted = InputService.isConnected()
        notificationsGranted = checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        captureGranted = MediaProjectionService.isMediaProjectionEnabled()
        fallback = InputService.isTakingScreenShots()
        refreshNetwork()
        if (changed) restartPolling()
    }
    private fun refreshNetwork() {
        if (isDestroyed) return
        hosts = if (running) MainService.getIPv4s().let { addresses ->
            if (addresses.size > 1) addresses.filter(Utils::isShareableIPv4) else addresses.toList()
        } else emptyList()
        if (!running) {
            val loopback = runCatching { NetworkInterface.getNetworkInterfaces().toList().filter { it.isLoopback }.map { it.name } }.getOrDefault(emptyList())
            // Keep a saved, temporarily unavailable interface visible instead of silently changing it.
            interfaces = (listOf("") + loopback + networks.values + prefs.getString(Constants.PREFS_KEY_SETTINGS_INTERFACE, "").orEmpty()).distinct()
        }
    }
    private fun serviceIntent(action: String) = Intent(this, MainService::class.java).setAction(action)
        .putExtra(MainService.EXTRA_ACCESS_KEY, prefs.getString(Constants.PREFS_KEY_SETTINGS_ACCESS_KEY, defaults.accessKey))

    private fun send(intent: Intent): Boolean = try {
        ContextCompat.startForegroundService(this, intent)
        true
    } catch (exception: RuntimeException) {
        Toast.makeText(this, exception.localizedMessage ?: getString(R.string.error), Toast.LENGTH_LONG).show()
        false
    }
    internal fun toggleServer() {
        val intent = serviceIntent(if (running) MainService.ACTION_STOP else MainService.ACTION_START)
        if (!running) intent.apply {
            putExtra(MainService.EXTRA_INTERFACE, prefs.getString(Constants.PREFS_KEY_SETTINGS_INTERFACE, ""))
            putExtra(MainService.EXTRA_PORT, prefs.getInt(Constants.PREFS_KEY_SETTINGS_PORT, defaults.port))
            putExtra(MainService.EXTRA_PASSWORD, prefs.getString(Constants.PREFS_KEY_SETTINGS_PASSWORD, defaults.password))
            putExtra(MainService.EXTRA_FILE_TRANSFER, prefs.getBoolean(Constants.PREFS_KEY_SETTINGS_FILE_TRANSFER, defaults.fileTransfer))
            putExtra(MainService.EXTRA_VIEW_ONLY, prefs.getBoolean(Constants.PREFS_KEY_SETTINGS_VIEW_ONLY, defaults.viewOnly))
            putExtra(MainService.EXTRA_SHOW_POINTERS, prefs.getBoolean(Constants.PREFS_KEY_SETTINGS_SHOW_POINTERS, defaults.showPointers))
            putExtra(MainService.EXTRA_SCALING, prefs.getFloat(Constants.PREFS_KEY_SETTINGS_SCALING, defaults.scaling))
        }
        busy = true
        if (!send(intent)) busy = false
    }
    internal fun connect(host: String, port: Int, repeaterId: String?, retries: Int?) {
        val request = OutgoingRequest(UUID.randomUUID().toString(), host, port, repeaterId)
        pending = request
        val intent = serviceIntent(if (repeaterId == null) MainService.ACTION_CONNECT_REVERSE else MainService.ACTION_CONNECT_REPEATER)
            .putExtra(MainService.EXTRA_HOST, host).putExtra(MainService.EXTRA_PORT, port)
            .putExtra(MainService.EXTRA_REQUEST_ID, request.id)
        repeaterId?.let { intent.putExtra(MainService.EXTRA_REPEATER_ID, it) }
        retries?.let { intent.putExtra(MainService.EXTRA_RECONNECT_TRIES, it) }
        if (!send(intent)) pending = null
    }
    internal fun disconnect(client: ClientList.Client) {
        send(serviceIntent(MainService.ACTION_DISCONNECT).apply {
            client.connectionId?.let { putExtra(MainService.EXTRA_CLIENT_CONNECTION_ID, it) }
            client.requestId?.let { putExtra(MainService.EXTRA_CLIENT_REQUEST_ID, it) }
        })
    }
    internal fun requestInput() = InputRequestActivity.requestIfNeededAndPostResult(this, true,
        prefs.getBoolean(Constants.PREFS_KEY_SETTINGS_START_ON_BOOT, defaults.startOnBoot), true)

    internal fun upgradeCapture() {
        startActivity(Intent(this, MediaProjectionRequestActivity::class.java)
            .putExtra(MediaProjectionRequestActivity.EXTRA_UPGRADING_FROM_NO_OR_FALLBACK_SCREEN_CAPTURE, true)
            .putExtra(MediaProjectionRequestActivity.EXTRA_OMIT_FALLBACK_SCREEN_CAPTURE_DIALOG, true))
    }
    internal fun notificationSettings() = startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
    internal fun share(host: String) {
        val port = MainService.getPort()
        val url = Utils.browserAccessUrl(host, port)
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, url), null))
    }
    internal fun openWeb(url: String, title: String) {
        startActivity(Intent(this, WebViewActivity::class.java).putExtra(WebViewActivity.EXTRA_URL, url).putExtra(WebViewActivity.EXTRA_TITLE, title))
    }
}

internal fun formatEndpoint(host: String, port: Int) = if (':' in host) "[$host]:$port" else "$host:$port"
