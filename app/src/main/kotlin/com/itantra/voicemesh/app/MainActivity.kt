package com.itantra.voicemesh.app

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.itantra.voicemesh.app.mesh.MeshSession
import com.itantra.voicemesh.app.speech.IndicConformerSpeechRecognizer
import com.itantra.voicemesh.app.speech.LanguageRoutingSpeechRecognizer
import com.itantra.voicemesh.app.speech.VoskSpeechRecognizer
import com.itantra.voicemesh.messaging.Language
import com.itantra.voicemesh.messaging.NodeId
import com.itantra.voicemesh.messaging.Priority

/**
 * Languages with a model actually bundled in this build — see the commented-out entries
 * in [VoskSpeechRecognizer] and [IndicConformerSpeechRecognizer] for the other seven,
 * trimmed for a smaller demo-video APK. Restore by switching this back to
 * `Language.entries` once those models are back under `app/src/main/assets/`.
 */
private val ACTIVE_DEMO_LANGUAGES = listOf(Language.HINDI, Language.GUJARATI, Language.ENGLISH)

/**
 * Minimal demo/skeleton UI for the transport+reliability core built this session
 * (spec §15 steps 1-3). STT/TTS are stubs (see :app's speech package) and there is no
 * "tap a nearby peer" pairing flow yet — destination is entered as a raw hex NodeId
 * so the network stack can be exercised end to end between two phones with Wi-Fi
 * Direct already paired. Real STT/TTS integration, peer-picker UI, and on-device
 * validation (spec §12) are the next steps, not done here.
 */
class MainActivity : ComponentActivity() {
    private lateinit var session: MeshSession

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        session = MeshSession(applicationContext)

        setContent {
            MaterialTheme {
                Surface {
                    VoiceMeshScreen(session = session, onRequestPermissions = { onPermissions -> requestMeshPermissions(onPermissions) })
                }
            }
        }
    }

    override fun onDestroy() {
        session.stop()
        super.onDestroy()
    }

    private fun requestMeshPermissions(launcher: (Array<String>) -> Unit) {
        val permissions = mutableListOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.NEARBY_WIFI_DEVICES)
        }
        launcher(permissions.toTypedArray())
    }
}

@Composable
private fun VoiceMeshScreen(session: MeshSession, onRequestPermissions: ((Array<String>) -> Unit) -> Unit) {
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
        if (results.values.all { it }) session.start()
    }

    var destinationHex by remember { mutableStateOf("") }
    var selectedLanguage by remember { mutableStateOf(Language.HINDI) }
    var languageMenuExpanded by remember { mutableStateOf(false) }
    var urgent by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val recognizer = remember {
        LanguageRoutingSpeechRecognizer(
            vosk = VoskSpeechRecognizer(context),
            fallback = IndicConformerSpeechRecognizer(context),
        )
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("Node ID: ${session.localNodeId}", style = MaterialTheme.typography.titleMedium)
        Button(onClick = { onRequestPermissions { permissions -> permissionLauncher.launch(permissions) } }) {
            Text("Start mesh (request permissions)")
        }

        Row(modifier = Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = { languageMenuExpanded = true }) { Text("Language: ${selectedLanguage.displayName}") }
            DropdownMenu(expanded = languageMenuExpanded, onDismissRequest = { languageMenuExpanded = false }) {
                // Restricted to the languages with models actually bundled in this demo
                // build — see VoskSpeechRecognizer/IndicConformerSpeechRecognizer for
                // how to restore the other seven; switch this back to Language.entries
                // once they're back in.
                ACTIVE_DEMO_LANGUAGES.forEach { language ->
                    DropdownMenuItem(text = { Text(language.displayName) }, onClick = {
                        selectedLanguage = language
                        languageMenuExpanded = false
                    })
                }
            }
            Row {
                Text("Urgent")
                Switch(checked = urgent, onCheckedChange = { urgent = it })
            }
        }

        TextField(
            value = destinationHex,
            onValueChange = { destinationHex = it },
            label = { Text("Destination NodeId (hex)") },
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
        )

        Button(
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            onClick = {
                val destination = destinationHex.toUIntOrNull(radix = 16)?.let { NodeId(it) } ?: return@Button
                recognizer.startListening(selectedLanguage) { sentence ->
                    session.sendMessage(
                        destination = destination,
                        language = selectedLanguage,
                        priority = if (urgent) Priority.URGENT else Priority.NORMAL,
                        text = sentence,
                    )
                }
            },
        ) {
            Text("Push-to-talk (stub STT)")
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
        Text("Neighbors: ${session.neighbors.joinToString { it.toString() }}")
        session.lastOutcome.value?.let { Text("Last outcome: $it") }

        HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
        Text("Log", style = MaterialTheme.typography.titleSmall)
        LazyColumn {
            items(session.log) { entry -> Text(entry) }
        }
    }
}
