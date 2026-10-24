package app.glimpse.music

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.glimpse.GlimpseViewModel
import app.glimpse.MainActivity
import app.glimpse.assistant.CapturePermissionActivity
import app.glimpse.core.*
import app.glimpse.ui.Choice
import app.glimpse.ui.ChoiceDialog
import app.glimpse.ui.SettingRow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun MusicPanel(onClose: () -> Unit) {
    val context = LocalContext.current
    val state by MusicSession.state.collectAsStateWithLifecycle()
    var input by remember { mutableStateOf("both") }
    Surface(
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Column(
            Modifier.padding(16.dp).heightIn(max = 400.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Identify a song",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                IconButton(
                    {
                        context.startActivity(
                            Intent(context, MainActivity::class.java)
                                .setAction(MainActivity.SETTINGS),
                        )
                    },
                ) {
                    Icon(Icons.Outlined.Settings, "Music settings")
                }
                IconButton(
                    {
                        context.stopService(Intent(context, MusicCaptureService::class.java))
                        onClose()
                    },
                ) {
                    Icon(Icons.Outlined.Close, "Close music")
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("both" to "Both", "phone" to "Phone", "microphone" to "Mic").forEach {
                    (id, title) ->
                    FilterChip(
                        input == id,
                        { input = id },
                        label = { Text(title) },
                        enabled = !state.active,
                    )
                }
            }
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            state.status.forEach { (source, status) ->
                Text("${source.title}: $status", style = MaterialTheme.typography.bodySmall)
                if (state.active)
                    LinearProgressIndicator(
                        progress = { state.levels[source] ?: 0f },
                        modifier = Modifier.fillMaxWidth(),
                    )
            }
            state.matches.forEach { match ->
                HorizontalDivider()
                Text(match.song.title, style = MaterialTheme.typography.titleMedium)
                Text(match.song.artist)
                Text(
                    "Heard from: ${match.evidence.map { it.source.title }.distinct().joinToString(" + ")}",
                    style = MaterialTheme.typography.bodySmall,
                )
                match.evidence.forEach { evidence ->
                    Text(evidence.backend, style = MaterialTheme.typography.labelSmall)
                    evidence.playingApp?.let {
                        Text(
                            "Playing app: $it · reported by media session",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                match.song.url?.let { url ->
                    TextButton(
                        {
                            runCatching {
                                requireSecureUrl(url)
                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                            }
                        },
                    ) {
                        Text("Open song")
                    }
                }
            }
            if (state.active)
                OutlinedButton(
                    {
                        context.stopService(Intent(context, MusicCaptureService::class.java))
                    },
                ) {
                    Text("Stop listening")
                }
            else
                Button(
                    {
                        context.startActivity(
                            Intent(context, CapturePermissionActivity::class.java)
                                .setAction(CapturePermissionActivity.MUSIC)
                                .putExtra("input", input),
                        )
                    },
                ) {
                    Icon(Icons.Outlined.MusicNote, null)
                    Spacer(Modifier.width(8.dp))
                    Text(if (state.matches.isEmpty()) "Listen" else "Listen again")
                }
        }
    }
}

@Composable
fun MusicSettings(model: GlimpseViewModel) {
    val context = LocalContext.current
    val prefs by model.preferences.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val repository = remember { SongPackRepository(context) }
    val credentials = remember { MusicCredentials(context) }
    var packs by remember { mutableStateOf(emptyList<OfflineSongPack>()) }
    var revision by remember { mutableIntStateOf(0) }
    var keyPresent by remember { mutableStateOf(credentials.read().isNotBlank()) }
    var keyDialog by remember { mutableStateOf(false) }
    var modeDialog by remember { mutableStateOf(false) }
    var packDialog by remember { mutableStateOf(false) }
    var panel by remember { mutableStateOf(false) }
    var downloading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(revision) { packs = withContext(Dispatchers.IO) { repository.installed() } }
    val modes =
        listOf(
            Choice("offline_first", "Offline first", "Try installed packs, then AudD"),
            Choice("offline_only", "Offline only", "Never send audio for online recognition"),
            Choice("online_only", "Online only", "Use your AudD account"),
        )
    Surface(shape = RoundedCornerShape(24.dp)) {
        Column {
            SettingRow(
                "Recognize music",
                "Listen to the microphone, phone audio, or both",
                Icons.Outlined.MusicNote,
                { panel = !panel },
            )
            if (panel) MusicPanel { panel = false }
            SettingRow(
                "Recognition mode",
                modes.first { it.id == prefs.musicMode }.title,
                onClick = { modeDialog = true },
            )
            SettingRow(
                "AudD API key",
                if (keyPresent) "Configured · stored with Android Keystore encryption"
                else "Add your own key for online recognition",
                onClick = { keyDialog = true },
            )
            SettingRow(
                "Playing app identification",
                "Optional notification access lets Glimpse read active media sessions",
                onClick = {
                    context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                },
            )
            SettingRow(
                "Offline song packs",
                "${packs.sumOf { it.songs.size }} songs in ${packs.size} installed packs. Only indexed songs can match offline.",
            )
            packs.forEach { pack ->
                SettingRow(
                    pack.name,
                    "Version ${pack.version} · ${pack.songs.size} songs",
                    trailing = {
                        IconButton(
                            {
                                scope.launch {
                                    withContext(Dispatchers.IO) { repository.delete(pack.id) }
                                    revision++
                                }
                            },
                        ) {
                            Icon(Icons.Outlined.DeleteOutline, "Remove ${pack.name}")
                        }
                    },
                )
            }
            SettingRow(
                if (downloading) "Downloading song pack…" else "Download a song pack",
                "Direct HTTPS pack URL and publisher's SHA-256 checksum",
                Icons.Outlined.Download,
                { if (!downloading) packDialog = true },
            )
            error?.let {
                Text(it, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error)
            }
        }
    }
    if (modeDialog)
        ChoiceDialog("Music recognition", modes, prefs.musicMode, { modeDialog = false }) {
            model.set("musicMode", it)
        }
    if (keyDialog) {
        var key by remember { mutableStateOf(credentials.read()) }
        AlertDialog(
            onDismissRequest = { keyDialog = false },
            title = { Text("AudD API key") },
            text = {
                Column {
                    OutlinedTextField(
                        key,
                        { key = it },
                        label = { Text("API key") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                    )
                    TextButton(
                        {
                            context.startActivity(
                                Intent(Intent.ACTION_VIEW, Uri.parse("https://dashboard.audd.io/")),
                            )
                        },
                    ) {
                        Text("Get an AudD key")
                    }
                    Text(
                        "Online recognition sends a short clip from each selected input to AudD using your account.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    {
                        runCatching { credentials.write(key) }
                            .onSuccess {
                                keyPresent = key.isNotBlank()
                                keyDialog = false
                            }
                            .onFailure {
                                error = "Could not store the key"
                                keyDialog = false
                            }
                    },
                ) {
                    Text("Save")
                }
            },
            dismissButton = { TextButton({ keyDialog = false }) { Text("Cancel") } },
        )
    }
    if (packDialog) {
        var url by remember { mutableStateOf("") }
        var checksum by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { packDialog = false },
            title = { Text("Download song pack") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        "Install a glimpse-landmarks-v1 pack from its publisher. Glimpse does not currently provide a popular-song catalogue.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    OutlinedTextField(
                        url,
                        { url = it },
                        label = { Text("Direct HTTPS URL") },
                        maxLines = 3,
                    )
                    OutlinedTextField(
                        checksum,
                        { checksum = it },
                        label = { Text("SHA-256") },
                        maxLines = 3,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    {
                        packDialog = false
                        downloading = true
                        error = null
                        scope.launch {
                            try {
                                repository.download(url.trim(), checksum.trim(), prefs.wifiOnly)
                                revision++
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                error = e.localizedMessage ?: "Song pack download failed"
                            } finally {
                                downloading = false
                            }
                        }
                    },
                    enabled =
                        url.startsWith("https://") &&
                            checksum.trim().matches(Regex("[a-fA-F0-9]{64}")),
                ) {
                    Text("Download")
                }
            },
            dismissButton = { TextButton({ packDialog = false }) { Text("Cancel") } },
        )
    }
}
