/* DroidVNC-NG configurable keyboard shortcuts.
 * Original author: slab-tsuchiya <https://github.com/slab-tsuchiya>.
 * SPDX-License-Identifier: GPL-2.0-only
 */
package net.christianbeier.droidvnc_ng.input

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.preference.PreferenceManager
import net.christianbeier.droidvnc_ng.Defaults
import net.christianbeier.droidvnc_ng.InputService
import net.christianbeier.droidvnc_ng.R
import net.christianbeier.droidvnc_ng.Utils
import net.christianbeier.droidvnc_ng.ui.SettingsCard
import net.christianbeier.droidvnc_ng.ui.BackNavigationButton
import net.christianbeier.droidvnc_ng.ui.VncTheme
import net.christianbeier.droidvnc_ng.ui.chordLabel

class InputKeyShortcutSetupActivity : ComponentActivity() {
    private val chords = mutableStateMapOf<InputKeyShortcut.Action, InputKeyShortcut.Chord>()
    private val actions = InputKeyShortcut.Action.entries.filter {
        it != InputKeyShortcut.Action.ROTATE || Utils.hasPortraitInLandscapeQuirk()
    }
    private val prefs by lazy { PreferenceManager.getDefaultSharedPreferences(this) }
    private val defaults by lazy { Defaults(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        loadChords()
        setContent { VncTheme { ShortcutScreen() } }
    }

    private fun loadChords() {
        actions.forEach { action -> chords[action] = InputKeyShortcut.Chord.fromString(prefs.getString(action.prefKey, action.defaultChord(defaults))) }
    }

    private fun update(action: InputKeyShortcut.Action, chord: InputKeyShortcut.Chord) {
        val proposed = InputKeyShortcut.Manager.from { other ->
            (if (other == action) chord else chords[other])?.toString().orEmpty()
        }
        if (chord.isAssigned && chord in proposed.conflicts) {
            Toast.makeText(this, getString(R.string.main_activity_settings_chord_conflict, chord.toString()), Toast.LENGTH_SHORT).show()
            return
        }
        // Keep in-progress modifier choices even when no trigger is selected yet.
        chords[action] = chord
        prefs.edit().putString(action.prefKey, chord.toString()).apply()
        InputService.reloadShortcuts()
    }

    @OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
    @Composable
    private fun ShortcutScreen() {
        var restore by rememberSaveable { mutableStateOf(false) }
        Scaffold(topBar = {
            TopAppBar(title = { Text(stringResource(R.string.main_activity_settings_key_shortcuts)) }, navigationIcon = {
                BackNavigationButton(onClick = { finish() })
            })
        }) { padding ->
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
                LazyColumn(Modifier.widthIn(max = 840.dp).fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    item {
                        Text(stringResource(R.string.ui_shortcuts_hint), style = MaterialTheme.typography.bodyLarge)
                    }
                    items(actions, key = { it.name }) { action ->
                        val chord = chords.getValue(action)
                        SettingsCard(stringResource(action.labelRes)) {
                            Text(chordLabel(chord), style = MaterialTheme.typography.titleLarge)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                FilterChip(chord.ctrl, { update(action, chord.copy(ctrl = !chord.ctrl)) }, label = { Text(stringResource(R.string.key_modifier_ctrl)) })
                                FilterChip(chord.alt, { update(action, chord.copy(alt = !chord.alt)) }, label = { Text(stringResource(R.string.key_modifier_alt)) })
                                FilterChip(chord.shift, { update(action, chord.copy(shift = !chord.shift)) }, label = { Text(stringResource(R.string.key_modifier_shift)) })
                            }
                            var expanded by remember { mutableStateOf(false) }
                            val keys = buildList {
                                add(0L)
                                addAll(InputKeyShortcut.TriggerKey.entries.map { it.keysym })
                                if (chord.keysym !in this) add(chord.keysym)
                            }
                            Box {
                                OutlinedButton(onClick = { expanded = true }) {
                                    Text(stringResource(R.string.ui_trigger_key, keyLabel(chord.keysym)))
                                }
                                DropdownMenu(expanded, { expanded = false }, modifier = Modifier.heightIn(max = 360.dp)) {
                                    keys.forEach { keysym ->
                                        DropdownMenuItem(text = { Text(keyLabel(keysym)) }, onClick = {
                                            update(action, chord.copy(keysym = keysym)); expanded = false
                                        })
                                    }
                                }
                            }
                        }
                    }
                    item {
                        OutlinedButton(onClick = { restore = true }, modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.main_activity_settings_key_shortcuts_restore))
                        }
                    }
                }
            }
        }
        if (restore) AlertDialog(onDismissRequest = { restore = false },
            text = { Text(stringResource(R.string.main_activity_settings_key_shortcuts_restore_confirm)) },
            confirmButton = { TextButton(onClick = {
                prefs.edit().apply { InputKeyShortcut.Action.entries.forEach { remove(it.prefKey) } }.apply()
                loadChords()
                InputService.reloadShortcuts()
                restore = false
            }) { Text(stringResource(R.string.main_activity_settings_key_shortcuts_restore)) } },
            dismissButton = { TextButton(onClick = { restore = false }) { Text(stringResource(android.R.string.cancel)) } })
    }

    @Composable
    private fun keyLabel(keysym: Long): String = if (keysym == 0L) stringResource(R.string.key_label_none)
        else InputKeyShortcut.TriggerKey.of(keysym)?.let { stringResource(it.labelRes) } ?: InputKeysyms.nameOf(keysym) ?: "0x${keysym.toString(16)}"
}
