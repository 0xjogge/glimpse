@file:OptIn(ExperimentalMaterial3Api::class)

package app.glimpse.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.glimpse.GlimpseViewModel
import app.glimpse.core.*

@Composable
fun SettingsScreen(
    model: GlimpseViewModel,
    modifier: Modifier = Modifier,
    assistant: () -> Unit,
    models: () -> Unit,
) {
    val prefs by model.preferences.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var dialog by remember { mutableStateOf<String?>(null) }
    var addProvider by remember { mutableStateOf(false) }
    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
            .padding(bottom = 24.dp),
    ) {
        SectionTitle("Quick access")
        Surface(shape = RoundedCornerShape(24.dp)) {
            Column {
                SettingRow(
                    "Digital assistant",
                    "Use your system assistant gesture to scan the screen",
                    Icons.Outlined.Assistant,
                    assistant,
                )
                SettingRow(
                    "Assistant screen access",
                    "Enable screenshot access in the assistant settings",
                    Icons.Outlined.Screenshot,
                    {
                        runCatching {
                            context.startActivity(Intent(Settings.ACTION_VOICE_INPUT_SETTINGS))
                        }
                            .onFailure {
                                model.message(
                                    "Open Settings → Apps → Default apps → Digital assistant",
                                )
                            }
                    },
                )
                SettingRow(
                    "Camera shortcuts",
                    "Long-press Glimpse in your launcher, or add Scan camera and Scan screen in Quick Settings",
                    Icons.Outlined.PhotoCamera,
                )
            }
        }
        SectionTitle("Recognition & translation")
        Surface(shape = RoundedCornerShape(24.dp)) {
            Column {
                SettingRow(
                    "Text recognition engine",
                    model.graph.engines.ocr(prefs.ocrEngine).info.name,
                    Icons.Outlined.TextFields,
                    { dialog = "ocrEngine" },
                )
                SettingRow(
                    "Recognition script",
                    when (prefs.script) {
                        OcrScript.AUTO -> "English + Japanese"
                        OcrScript.LATIN -> "Latin script"
                        OcrScript.JAPANESE -> "Japanese + Latin"
                    },
                    onClick = { dialog = "script" },
                )
                SettingRow(
                    "Translation engine",
                    model.graph.engines.translator(prefs.translationEngine).info.name,
                    Icons.Outlined.Translate,
                    { dialog = "translationEngine" },
                )
                SettingRow(
                    "Offline languages",
                    "Download Japanese and add more translation languages",
                    Icons.Outlined.DownloadForOffline,
                    models,
                )
                SettingRow(
                    "Translate from",
                    model.languageName(prefs.source),
                    onClick = { dialog = "source" },
                )
                SettingRow(
                    "Translate to",
                    model.languageName(prefs.target),
                    onClick = { dialog = "target" },
                )
                SettingRow(
                    "Wi-Fi downloads only",
                    "Language packs are roughly 30 MB each",
                    trailing = { Switch(prefs.wifiOnly, { model.set("wifiOnly", it) }) },
                )
            }
        }
        SectionTitle("Search & online translation")
        Surface(shape = RoundedCornerShape(24.dp)) {
            Column {
                SettingRow(
                    "Preferred search engine",
                    prefs.providers.firstOrNull { it.id == prefs.search }?.name ?: "Google",
                    Icons.Outlined.Search,
                    { dialog = "search" },
                )
                SettingRow(
                    "Browser translator",
                    prefs.browserTranslator.title,
                    Icons.Outlined.OpenInNew,
                    { dialog = "browserTranslator" },
                )
                prefs.customProviders.forEach { provider ->
                    SettingRow(
                        provider.name,
                        provider.template,
                        trailing = {
                            IconButton(onClick = { model.removeProvider(provider.id) }) {
                                Icon(Icons.Outlined.DeleteOutline, "Remove ${provider.name}")
                            }
                        },
                    )
                }
                SettingRow(
                    "Add a search or AI engine",
                    "Use a search URL with {query} as the placeholder",
                    Icons.Outlined.Add,
                    { addProvider = true },
                )
            }
        }
        SectionTitle("Appearance")
        Surface(shape = RoundedCornerShape(24.dp)) {
            Column {
                SettingRow(
                    "Theme",
                    prefs.theme.replaceFirstChar(Char::uppercase),
                    Icons.Outlined.Palette,
                    { dialog = "theme" },
                )
                SettingRow(
                    "Wallpaper colors",
                    "Use Android's dynamic color palette",
                    trailing = { Switch(prefs.dynamicColor, { model.set("dynamicColor", it) }) },
                )
            }
        }
        SectionTitle("About Glimpse")
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
        ) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Local first. A little clearer.", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Recognition and offline translation run on this phone. Search and online translation send the selected content to the service you choose. Live camera translation stays on device.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    "The first release uses Google's proprietary ML Kit SDKs and models behind replaceable engine interfaces. Model downloads and SDK operational data can contact Google; offline inference does not upload your images or text.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "Version ${context.packageManager.getPackageInfo(context.packageName, 0).versionName} · GPL-3.0-only",
                    style = MaterialTheme.typography.labelMedium,
                )
                TextButton(
                    onClick = {
                        context.startActivity(
                            Intent(
                                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                Uri.parse("package:${context.packageName}"),
                            ),
                        )
                    },
                    contentPadding = PaddingValues(0.dp),
                ) {
                    Text("Android app settings")
                }
            }
        }
    }
    dialog?.let { key ->
        if (key == "source" || key == "target") {
            LanguageDialog(
                if (key == "source") "Translate from" else "Translate to",
                model.graph.engines.translator(prefs.translationEngine).languages,
                if (key == "source") prefs.source else prefs.target,
                key == "source",
                { dialog = null },
            ) {
                model.set(key, it)
            }
        } else {
            val (title, selected, choices) =
                when (key) {
                    "ocrEngine" ->
                        Triple(
                            "Recognition engine",
                            prefs.ocrEngine,
                            model.graph.engines.ocr.map {
                                Choice(it.info.id, it.info.name, it.info.description)
                            },
                        )
                    "translationEngine" ->
                        Triple(
                            "Translation engine",
                            prefs.translationEngine,
                            model.graph.engines.translation.map {
                                Choice(it.info.id, it.info.name, it.info.description)
                            },
                        )
                    "script" ->
                        Triple(
                            "Recognition script",
                            prefs.script.name,
                            listOf(
                                Choice(
                                    "AUTO",
                                    "English + Japanese",
                                    "Recommended for mixed-language images",
                                ),
                                Choice(
                                    "LATIN",
                                    "Latin script",
                                    "English and other Latin-script languages",
                                ),
                                Choice("JAPANESE", "Japanese + Latin"),
                            ),
                        )
                    "search" ->
                        Triple(
                            "Preferred search engine",
                            prefs.search,
                            prefs.providers.map { Choice(it.id, it.name) },
                        )
                    "browserTranslator" ->
                        Triple(
                            "Browser translator",
                            prefs.browserTranslator.name,
                            BrowserTranslator.entries.map { Choice(it.name, it.title) },
                        )
                    else ->
                        Triple(
                            "Theme",
                            prefs.theme,
                            listOf(
                                Choice("system", "Follow Android"),
                                Choice("light", "Light"),
                                Choice("dark", "Dark"),
                            ),
                        )
                }
            ChoiceDialog(title, choices, selected, { dialog = null }) { model.set(key, it) }
        }
    }
    if (addProvider)
        AddProviderDialog({ addProvider = false }) { name, template ->
            model.addProvider(name, template)
            addProvider = false
        }
}

@Composable
private fun AddProviderDialog(dismiss: () -> Unit, add: (String, String) -> Unit) {
    var name by remember { mutableStateOf("") }
    var template by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text("Add an engine") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true)
                OutlinedTextField(
                    template,
                    {
                        template = it
                        error = null
                    },
                    label = { Text("HTTPS search URL") },
                    placeholder = { Text("https://example.com/?q={query}") },
                    minLines = 2,
                    isError = error != null,
                    supportingText = {
                        Text(
                            error
                                ?: "The selected text replaces {query}. Your browser handles sign-in.",
                        )
                    },
                )
                TextButton(
                    onClick = {
                        name = "Perplexity"
                        template = "https://www.perplexity.ai/search?q={query}"
                    },
                ) {
                    Text("Use Perplexity template")
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    runCatching {
                        TemplateSearchProvider("validation", name.trim(), template.trim())
                    }
                        .onSuccess { add(name, template) }
                        .onFailure { error = it.localizedMessage ?: "Check the URL and name" }
                },
                enabled = name.isNotBlank() && template.isNotBlank(),
            ) {
                Text("Add")
            }
        },
        dismissButton = { TextButton(onClick = dismiss) { Text("Cancel") } },
    )
}

@Composable
fun ModelsScreen(model: GlimpseViewModel, modifier: Modifier = Modifier) {
    val state by model.state.collectAsStateWithLifecycle()
    val prefs by model.preferences.collectAsStateWithLifecycle()
    var query by remember { mutableStateOf("") }
    LaunchedEffect(prefs.translationEngine) { model.refreshModels() }
    val languages =
        state.models.ifEmpty {
            model.graph.engines.translator(prefs.translationEngine).languages.map {
                LanguageModel(it, it.tag == "en")
            }
        }
    val ordered =
        languages.sortedWith(
            compareBy<LanguageModel> {
                    when (it.language.tag) {
                        "en" -> 0
                        "ja" -> 1
                        else -> if (it.installed) 2 else 3
                    }
                }
                .thenBy { it.language.name },
        )
    LazyColumn(
        modifier.fillMaxSize().padding(horizontal = 16.dp),
        contentPadding = PaddingValues(bottom = 24.dp),
    ) {
        item {
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.primaryContainer,
            ) {
                Column(Modifier.padding(20.dp)) {
                    Icon(
                        Icons.Outlined.DownloadForOffline,
                        null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                    Text(
                        "Ready when you're offline",
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                    Text(
                        "English and Japanese recognition are already bundled. Download Japanese below for English ↔ Japanese translation. More language packs can be added anytime.",
                        Modifier.padding(top = 8.dp),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            OutlinedTextField(
                query,
                { query = it },
                Modifier.fillMaxWidth().padding(vertical = 16.dp),
                singleLine = true,
                label = { Text("Find a language") },
                leadingIcon = { Icon(Icons.Outlined.Search, null) },
            )
            if (state.downloading.isNotEmpty())
                Text(
                    if (prefs.wifiOnly)
                        "Downloading over Wi-Fi. If a download is waiting, connect to Wi-Fi or change the download setting."
                    else "Downloading language packs…",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(bottom = 12.dp),
                )
        }
        items(
            ordered.filter {
                it.language.name.contains(query, true) || it.language.tag.contains(query, true)
            },
            key = { it.language.tag },
        ) { language ->
            val tag = language.language.tag
            ListItem(
                headlineContent = { Text(language.language.name) },
                supportingContent = {
                    Text(
                        when {
                            tag == "en" -> "Included · shared base language"
                            tag in state.downloading -> "Downloading…"
                            language.installed -> "Ready offline"
                            else -> "Download for offline translation"
                        },
                    )
                },
                leadingContent = {
                    Icon(
                        if (language.installed) Icons.Outlined.CheckCircleOutline
                        else Icons.Outlined.Language,
                        null,
                        tint =
                            if (language.installed) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
                trailingContent = {
                    when {
                        tag in state.downloading ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                                IconButton(onClick = { model.cancelDownload(tag) }) {
                                    Icon(
                                        Icons.Outlined.Close,
                                        "Cancel ${language.language.name} download",
                                    )
                                }
                            }
                        tag == "en" -> Unit
                        language.installed ->
                            IconButton(onClick = { model.deleteModel(tag) }) {
                                Icon(
                                    Icons.Outlined.DeleteOutline,
                                    "Remove ${language.language.name} model",
                                )
                            }
                        else ->
                            IconButton(onClick = { model.download(tag) }) {
                                Icon(Icons.Outlined.Download, "Download ${language.language.name}")
                            }
                    }
                },
            )
        }
    }
}
