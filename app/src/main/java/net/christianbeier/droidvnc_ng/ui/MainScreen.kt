package net.christianbeier.droidvnc_ng.ui

import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import net.christianbeier.droidvnc_ng.BuildConfig
import net.christianbeier.droidvnc_ng.Constants
import net.christianbeier.droidvnc_ng.MainService
import net.christianbeier.droidvnc_ng.R
import net.christianbeier.droidvnc_ng.Utils
import net.christianbeier.droidvnc_ng.input.InputKeyShortcut
import net.christianbeier.droidvnc_ng.input.InputKeyShortcutSetupActivity
import net.christianbeier.droidvnc_ng.input.InputKeysyms
import net.christianbeier.droidvnc_ng.server.ClientList
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MainScreen(activity: MainActivity) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var outgoing by rememberSaveable { mutableStateOf<String?>(null) }
    var terminate by remember { mutableStateOf<ClientList.Client?>(null) }
    var dismissedRequest by rememberSaveable { mutableStateOf<String?>(null) }
    // Preferences can also change outside this screen (e.g. managed settings / shortcut editor).
    val revision = activity.preferenceRevision
    Scaffold(topBar = {
        TopAppBar(title = { Text(stringResource(R.string.app_name)) }, actions = {
            Text("v${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(end = 20.dp))
        })
    }) { padding ->
        Column(Modifier.padding(padding).imePadding().fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(Modifier.widthIn(max = 840.dp).fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(R.string.ui_dashboard, R.string.main_activity_settings).forEachIndexed { index, label ->
                    FilterChip(selected = tab == index, onClick = { tab = index }, label = { Text(stringResource(label)) })
                }
            }
            LazyColumn(
                modifier = Modifier.widthIn(max = 840.dp).fillMaxSize(),
                contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                item(key = "status") { ServerCard(activity) }
                if (tab == 0) {
                    if (activity.running) item(key = "connections") {
                        SettingsCard(stringResource(R.string.main_activity_connections)) {
                            if (activity.clients.isEmpty()) Text(stringResource(R.string.ui_no_clients), color = MaterialTheme.colorScheme.onSurfaceVariant)
                            activity.clients.forEach { client ->
                                key(client.requestId ?: client.connectionId) {
                                    Column {
                                        Text(clientDescription(client))
                                        TextButton(onClick = { terminate = client }) { Text(stringResource(R.string.ui_disconnect)) }
                                    }
                                }
                            }
                            HorizontalDivider()
                            OutlinedButton(onClick = { outgoing = "reverse" }, enabled = activity.pending == null, modifier = Modifier.fillMaxWidth()) {
                                Text(stringResource(R.string.main_activity_reverse_vnc_button))
                            }
                            OutlinedButton(onClick = { outgoing = "repeater" }, enabled = activity.pending == null, modifier = Modifier.fillMaxWidth()) {
                                Text(stringResource(R.string.main_activity_repeater_vnc_button))
                            }
                        }
                    }
                    item(key = "permissions") { PermissionsCard(activity, revision) }
                    item(key = "shortcuts") { ShortcutsCard(activity, revision) }
                    item(key = "about") {
                        SettingsCard(stringResource(R.string.ui_about)) {
                            Text(stringResource(R.string.main_activity_about, BuildConfig.VERSION_NAME), style = MaterialTheme.typography.bodyMedium)
                            TextButton(onClick = { activity.openWeb("https://github.com/bk138/droidVNC-NG/releases", activity.getString(R.string.ui_releases)) }) {
                                Text(stringResource(R.string.ui_releases))
                            }
                            TextButton(onClick = { activity.openWeb("https://github.com/bk138/droidVNC-NG/issues", activity.getString(R.string.ui_report_issue)) }) {
                                Text(stringResource(R.string.ui_report_issue))
                            }
                            val language = LocalConfiguration.current.locales[0].language
                            TextButton(onClick = {
                                var url = activity.getString(R.string.survey_url)
                                if (language != "en") url = "https://translate.google.com/translate?sl=en&u=$url"
                                activity.openWeb(url, activity.getString(R.string.main_activity_survey_button))
                            }) { Text(stringResource(R.string.main_activity_survey_button)) }
                        }
                    }
                } else {
                    item(key = "network") { NetworkSettings(activity, revision) }
                    item(key = "display") { DisplaySettings(activity, revision) }
                    item(key = "boot") { BootSettings(activity, revision) }
                }
            }
        }
    }
    outgoing?.let { type -> OutgoingDialog(activity, type == "repeater") { outgoing = null } }
    terminate?.let { client ->
        AlertDialog(onDismissRequest = { terminate = null },
            title = { Text(stringResource(R.string.main_activity_connection_terminate_title)) },
            text = { Text(stringResource(R.string.main_activity_connection_terminate_message)) },
            confirmButton = { TextButton(onClick = { activity.disconnect(client); terminate = null }) { Text(stringResource(R.string.yes)) } },
            dismissButton = { TextButton(onClick = { terminate = null }) { Text(stringResource(R.string.no)) } })
    }
    activity.pending?.takeIf { it.id != dismissedRequest }?.let { request ->
        AlertDialog(onDismissRequest = {},
            title = { Text(stringResource(R.string.main_activity_connecting_to, formatEndpoint(request.host, request.port))) },
            text = { LinearProgressIndicator(Modifier.fillMaxWidth()) },
            confirmButton = {
                // Dismissing the progress UI does not pretend to cancel the service request.
                TextButton(onClick = { dismissedRequest = request.id }) { Text(stringResource(R.string.ui_dismiss)) }
            })
    }
}

@Composable
private fun ServerCard(activity: MainActivity) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(if (activity.running) R.string.ui_server_running else R.string.ui_server_stopped), style = MaterialTheme.typography.headlineMedium)
            Text(stringResource(if (activity.running) R.string.ui_running_hint else R.string.ui_stopped_hint), style = MaterialTheme.typography.bodyMedium)
            if (activity.running) {
                if (MainService.getPort() < 0) Text(stringResource(R.string.main_activity_not_listening))
                else activity.hosts.forEach { host ->
                    SelectionContainer { Text(formatEndpoint(host, MainService.getPort()), style = MaterialTheme.typography.titleMedium) }
                    if (host != "127.0.0.1") TextButton(onClick = { activity.share(host) }) { Text(stringResource(R.string.main_activity_share_link)) }
                }
            }
            Button(onClick = activity::toggleServer, enabled = !activity.busy, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                Text(stringResource(if (activity.busy) R.string.ui_please_wait else if (activity.running) R.string.stop else R.string.start))
            }
        }
    }
}

@Composable
private fun NetworkSettings(activity: MainActivity, revision: Int) {
    val prefs = activity.prefs
    val defaults = activity.defaults
    val enabled = !activity.running && !activity.busy
    SettingsCard(stringResource(R.string.ui_network_security)) {
        if (!enabled) Text(stringResource(R.string.ui_settings_locked), style = MaterialTheme.typography.bodySmall)
        var expanded by remember { mutableStateOf(false) }
        val selected = remember(revision) { prefs.getString(Constants.PREFS_KEY_SETTINGS_INTERFACE, "").orEmpty() }
        Text(stringResource(R.string.main_activity_settings_interface), style = MaterialTheme.typography.labelLarge)
        Box {
            OutlinedButton(onClick = { expanded = true }, enabled = enabled) {
                Text(selected.ifEmpty { stringResource(R.string.main_activity_settings_interface_any) })
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                activity.interfaces.forEach { name ->
                    DropdownMenuItem(text = { Text(name.ifEmpty { stringResource(R.string.main_activity_settings_interface_any) }) }, onClick = {
                        prefs.edit().putString(Constants.PREFS_KEY_SETTINGS_INTERFACE, name).apply(); expanded = false
                    })
                }
            }
        }
        var port by rememberSaveable { mutableStateOf(prefs.getInt(Constants.PREFS_KEY_SETTINGS_PORT, defaults.port).let { if (it < 0) "" else it.toString() }) }
        val portValid = port.isEmpty() || port.toIntOrNull()?.let { it in 0..65535 } == true
        OutlinedTextField(value = port, onValueChange = {
            port = it
            if (it.isEmpty()) prefs.edit().putInt(Constants.PREFS_KEY_SETTINGS_PORT, -1).apply()
            else it.toIntOrNull()?.takeIf { value -> value in 0..65535 }?.let { value -> prefs.edit().putInt(Constants.PREFS_KEY_SETTINGS_PORT, value).apply() }
        }, label = { Text(stringResource(R.string.main_activity_settings_port)) }, enabled = enabled, singleLine = true,
            isError = !portValid, supportingText = { Text(stringResource(if (portValid) R.string.ui_port_hint else R.string.ui_invalid_port)) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
        SecretPreference(activity, Constants.PREFS_KEY_SETTINGS_PASSWORD, defaults.password, R.string.main_activity_settings_password, enabled)
        SecretPreference(activity, Constants.PREFS_KEY_SETTINGS_ACCESS_KEY, defaults.accessKey, R.string.main_activity_settings_access_key, enabled, resetEmpty = true)
    }
}

@Composable
private fun SecretPreference(activity: MainActivity, key: String, default: String, label: Int, enabled: Boolean, resetEmpty: Boolean = false) {
    // Secrets stay in preferences / memory, not in the saved-instance-state bundle.
    var value by remember { mutableStateOf(activity.prefs.getString(key, default).orEmpty()) }
    var visible by remember { mutableStateOf(false) }
    OutlinedTextField(value = value, onValueChange = {
        value = it
        activity.prefs.edit().putString(key, if (resetEmpty && it.isEmpty()) default else it).apply()
    }, enabled = enabled, label = { Text(stringResource(label)) }, singleLine = true,
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        trailingIcon = { TextButton(onClick = { visible = !visible }, enabled = enabled) { Text(stringResource(if (visible) R.string.ui_hide else R.string.ui_show)) } },
        modifier = Modifier.fillMaxWidth().onFocusChanged { if (!it.hasFocus && resetEmpty && value.isEmpty()) value = default })
}

@Composable
private fun DisplaySettings(activity: MainActivity, revision: Int) {
    val prefs = activity.prefs
    val defaults = activity.defaults
    val enabled = !activity.running && !activity.busy
    val viewOnly = remember(revision) { prefs.getBoolean(Constants.PREFS_KEY_SETTINGS_VIEW_ONLY, defaults.viewOnly) }
    val scaling = remember(revision) { prefs.getFloat(Constants.PREFS_KEY_SETTINGS_SCALING, defaults.scaling).coerceIn(.1f, 1f) }
    val scalingLabel = stringResource(R.string.main_activity_settings_scaling)
    SettingsCard(stringResource(R.string.ui_display_input)) {
        Text("${stringResource(R.string.main_activity_settings_scaling)} · ${(scaling * 100).roundToInt()}%")
        Slider(value = scaling, onValueChange = { prefs.edit().putFloat(Constants.PREFS_KEY_SETTINGS_SCALING, (it * 20).roundToInt() / 20f).apply() },
            valueRange = .1f..1f, steps = 17, enabled = enabled,
            modifier = Modifier.semantics { contentDescription = scalingLabel })
        PreferenceSwitch(stringResource(R.string.main_activity_settings_view_only), viewOnly, enabled) {
            prefs.edit().putBoolean(Constants.PREFS_KEY_SETTINGS_VIEW_ONLY, it).apply()
        }
        PreferenceSwitch(stringResource(R.string.main_activity_settings_show_pointers), prefs.getBoolean(Constants.PREFS_KEY_SETTINGS_SHOW_POINTERS, defaults.showPointers), enabled && !viewOnly) {
            prefs.edit().putBoolean(Constants.PREFS_KEY_SETTINGS_SHOW_POINTERS, it).apply()
        }
        OutlinedButton(onClick = { activity.startActivity(Intent(activity, InputKeyShortcutSetupActivity::class.java)) }, enabled = !viewOnly) {
            Text(stringResource(R.string.main_activity_settings_key_shortcuts))
        }
    }
}

@Composable
private fun BootSettings(activity: MainActivity, revision: Int) {
    val prefs = activity.prefs
    val defaults = activity.defaults
    val boot = remember(revision) { prefs.getBoolean(Constants.PREFS_KEY_SETTINGS_START_ON_BOOT, defaults.startOnBoot) }
    SettingsCard(stringResource(R.string.ui_automation)) {
        PreferenceSwitch(stringResource(R.string.main_activity_settings_start_on_boot), boot) { prefs.edit().putBoolean(Constants.PREFS_KEY_SETTINGS_START_ON_BOOT, it).apply() }
        var delay by rememberSaveable { mutableStateOf(prefs.getInt(Constants.PREFS_KEY_SETTINGS_START_ON_BOOT_DELAY, defaults.startOnBootDelay).toString()) }
        OutlinedTextField(value = delay, onValueChange = {
            delay = it
            if (it.isEmpty()) prefs.edit().remove(Constants.PREFS_KEY_SETTINGS_START_ON_BOOT_DELAY).apply()
            else it.toIntOrNull()?.takeIf { value -> value >= 0 }?.let { value -> prefs.edit().putInt(Constants.PREFS_KEY_SETTINGS_START_ON_BOOT_DELAY, value).apply() }
        }, label = { Text(stringResource(R.string.main_activity_settings_start_on_boot_delay)) },
            placeholder = { Text(defaults.startOnBootDelay.toString()) }, singleLine = true, enabled = boot,
            isError = delay.isNotEmpty() && (delay.toIntOrNull()?.let { it >= 0 } != true),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
    }
}

@Composable
internal fun PreferenceSwitch(label: String, checked: Boolean, enabled: Boolean = true, onCheckedChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(label, modifier = Modifier.weight(1f), color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = .38f))
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

@Composable
private fun PermissionsCard(activity: MainActivity, revision: Int) {
    val boot = remember(revision) { activity.prefs.getBoolean(Constants.PREFS_KEY_SETTINGS_START_ON_BOOT, activity.defaults.startOnBoot) }
    SettingsCard(stringResource(R.string.main_activity_permissions_dashboard)) {
        PermissionRow(R.string.main_activity_input, activity.inputGranted, onClick = if (!activity.inputGranted) ({ activity.requestInput() }) else null)
        PermissionRow(R.string.main_activity_notifications, activity.notificationsGranted, onClick = activity::notificationSettings)
        PermissionRow(R.string.main_activity_screen_capturing, activity.captureGranted,
            status = if (!activity.captureGranted && activity.fallback) stringResource(R.string.main_activity_fallback) else null,
            onClick = if (!activity.captureGranted && activity.running) activity::upgradeCapture else null)
        if (boot) PermissionRow(R.string.main_activity_start_on_boot, activity.inputGranted, onClick = if (!activity.inputGranted) ({ activity.requestInput() }) else null)
    }
}

@Composable
private fun PermissionRow(label: Int, granted: Boolean, status: String? = null, onClick: (() -> Unit)? = null) {
    Column {
        Text(stringResource(label), style = MaterialTheme.typography.bodyLarge)
        val text = status ?: stringResource(if (granted) R.string.main_activity_granted else R.string.main_activity_denied)
        if (onClick != null) TextButton(onClick = onClick) { Text(text) }
        else Text(text, style = MaterialTheme.typography.bodyMedium, color = if (granted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ShortcutsCard(activity: MainActivity, revision: Int) {
    val viewOnly = remember(revision) { activity.prefs.getBoolean(Constants.PREFS_KEY_SETTINGS_VIEW_ONLY, activity.defaults.viewOnly) }
    SettingsCard(stringResource(R.string.main_activity_settings_key_shortcuts)) {
        InputKeyShortcut.Action.entries.filter { it != InputKeyShortcut.Action.ROTATE || Utils.hasPortraitInLandscapeQuirk() }.forEach { action ->
            val chord = remember(revision) { InputKeyShortcut.Chord.fromString(activity.prefs.getString(action.prefKey, action.defaultChord(activity.defaults))) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(stringResource(action.labelRes), modifier = Modifier.weight(1f))
                Text(chordLabel(chord), style = MaterialTheme.typography.labelLarge)
            }
        }
        TextButton(onClick = { activity.startActivity(Intent(activity, InputKeyShortcutSetupActivity::class.java)) }, enabled = !viewOnly) {
            Text(stringResource(R.string.main_activity_settings_key_shortcuts_button))
        }
    }
}

@Composable
internal fun chordLabel(chord: InputKeyShortcut.Chord): String {
    if (!chord.isAssigned) return stringResource(R.string.key_label_none)
    return buildList {
        if (chord.ctrl) add(stringResource(R.string.key_modifier_ctrl))
        if (chord.alt) add(stringResource(R.string.key_modifier_alt))
        if (chord.shift) add(stringResource(R.string.key_modifier_shift))
        add(InputKeyShortcut.TriggerKey.of(chord.keysym)?.let { stringResource(it.labelRes) } ?: InputKeysyms.nameOf(chord.keysym) ?: "0x${chord.keysym.toString(16)}")
    }.joinToString(" + ")
}

@Composable
private fun clientDescription(client: ClientList.Client): String = when {
    client.connectionId == null && client.repeaterId != null -> stringResource(R.string.main_activity_connection_to_repeater_now_reconnecting, client.host.orEmpty(), client.repeaterId)
    client.connectionId == null -> stringResource(R.string.main_activity_connection_to_now_reconnecting, client.host.orEmpty())
    client.repeaterId != null -> stringResource(R.string.main_activity_connection_to_repeater, client.host.orEmpty(), client.repeaterId)
    client.port != null -> stringResource(R.string.main_activity_connection_to, client.host.orEmpty())
    else -> stringResource(R.string.main_activity_connection_from, client.host.orEmpty())
}

@Composable
private fun OutgoingDialog(activity: MainActivity, repeater: Boolean, dismiss: () -> Unit) {
    var host by rememberSaveable { mutableStateOf(activity.prefs.getString(if (repeater) "repeater_vnc_last_host" else "reverse_vnc_last_host", "").orEmpty()) }
    var id by rememberSaveable { mutableStateOf(activity.prefs.getString("repeater_vnc_last_id", "").orEmpty()) }
    var retries by rememberSaveable { mutableStateOf("") }
    val endpoint = parseEndpoint(host, if (repeater) activity.defaults.portRepeater else activity.defaults.portReverse)
    val valid = endpoint != null && (!repeater || id.isNotBlank()) && (retries.isEmpty() || retries.toIntOrNull()?.let { it >= 0 } == true)
    AlertDialog(onDismissRequest = dismiss,
        title = { Text(stringResource(if (repeater) R.string.main_activity_repeater_vnc_button else R.string.main_activity_reverse_vnc_button)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(host, { host = it }, label = { Text(stringResource(R.string.main_activity_reverse_vnc_hint)) }, singleLine = true, isError = host.isNotEmpty() && endpoint == null)
                if (repeater) OutlinedTextField(id, { id = it }, label = { Text(stringResource(R.string.main_activity_repeater_vnc_hint_id)) }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                OutlinedTextField(retries, { retries = it }, label = { Text(stringResource(R.string.main_activity_reconnect_tries_hint)) }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            }
        },
        confirmButton = { TextButton(enabled = valid, onClick = {
            endpoint?.let { activity.connect(it.first, it.second, if (repeater) id.trim() else null, retries.toIntOrNull()) }; dismiss()
        }) { Text(stringResource(android.R.string.ok)) } },
        dismissButton = { TextButton(onClick = dismiss) { Text(stringResource(android.R.string.cancel)) } })
}

/** Accepts host, host:port, IPv6, and [IPv6]:port without splitting an IPv6 address. */
private fun parseEndpoint(value: String, defaultPort: Int): Pair<String, Int>? {
    val input = value.trim()
    if (input.isEmpty() || input.any { it.isWhitespace() } || '/' in input) return null
    var host = input
    var port = defaultPort
    if (input.startsWith("[")) {
        val end = input.indexOf(']')
        if (end < 2) return null
        host = input.substring(1, end)
        val suffix = input.substring(end + 1)
        if (suffix.isNotEmpty()) {
            if (!suffix.startsWith(":")) return null
            port = suffix.drop(1).toIntOrNull() ?: return null
        }
    } else if (input.count { it == ':' } == 1) {
        host = input.substringBefore(':')
        port = input.substringAfter(':').toIntOrNull() ?: return null
    }
    return if (host.isNotEmpty() && port in 1..65535) host to port else null
}
