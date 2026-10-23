package app.glimpse.assistant

import android.animation.ValueAnimator
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.glimpse.GlimpseViewModel
import app.glimpse.MainActivity
import app.glimpse.core.*
import app.glimpse.data.Images
import app.glimpse.search.VisualSearchActivity
import app.glimpse.ui.LanguageDialog
import app.glimpse.ui.SelectableImage
import kotlinx.coroutines.launch

@Composable
fun AssistantOverlay(model: GlimpseViewModel, dismiss: () -> Unit, openApp: (String) -> Unit) {
    val state by model.state.collectAsStateWithLifecycle()
    val prefs by model.preferences.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var query by rememberSaveable { mutableStateOf("") }
    var expanded by rememberSaveable { mutableStateOf(false) }
    var language by remember { mutableStateOf<String?>(null) }
    var editText by remember { mutableStateOf<String?>(null) }
    var pendingTranslation by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var music by rememberSaveable { mutableStateOf(false) }
    val provider =
        prefs.providers.firstOrNull { it.id == prefs.search } ?: SearchProviders.builtIn.first()
    LaunchedEffect(state.reading, state.text) {
        if (pendingTranslation && !state.reading) {
            pendingTranslation = false
            model.translate()
        }
    }
    fun search() {
        val text = query.ifBlank { state.text }.take(8000)
        if (text.isBlank()) {
            expanded = true
            return
        }
        runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(provider.textUrl(text))))
        }
            .onFailure { model.message("No browser could open the search") }
    }

    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .08f))) {
        if (state.image != null)
            SelectableImage(state, Modifier.fillMaxSize()) {
                model.select(it)
                expanded = true
            }
        EdgeLight(state.reading || state.translating)
        Row(
            Modifier.fillMaxWidth().statusBarsPadding().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FilledTonalIconButton(dismiss) { Icon(Icons.Outlined.Close, "Close assistant") }
            Spacer(Modifier.weight(1f))
            Box {
                FilledTonalIconButton({ menu = true }) {
                    Icon(Icons.Outlined.MoreVert, "Assistant options")
                }
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem(
                        { Text("Settings") },
                        {
                            menu = false
                            openApp(MainActivity.SETTINGS)
                        },
                    )
                    DropdownMenuItem(
                        { Text("Offline languages") },
                        {
                            menu = false
                            openApp(MainActivity.MODELS)
                        },
                    )
                    DropdownMenuItem(
                        { Text("Select whole screen") },
                        {
                            menu = false
                            model.select(null)
                        },
                    )
                }
            }
        }
        Column(
            Modifier.align(Alignment.BottomCenter)
                .fillMaxWidth()
                .imePadding()
                .navigationBarsPadding()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (music) {
                app.glimpse.music.MusicPanel(onClose = { music = false })
            } else if (expanded || state.message != null) {
                Surface(
                    shape = RoundedCornerShape(28.dp),
                    color = MaterialTheme.colorScheme.surface.copy(alpha = .97f),
                ) {
                    Column(
                        Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            TextButton({ language = "source" }, Modifier.weight(1f)) {
                                Text(model.languageName(prefs.source), maxLines = 1)
                            }
                            IconButton(
                                {
                                    if (prefs.source == "auto")
                                        model.set(
                                            "target",
                                            if (prefs.target == "en") "ja" else "en",
                                        )
                                    else {
                                        model.set("source", prefs.target)
                                        model.set("target", prefs.source)
                                    }
                                },
                            ) {
                                Icon(Icons.Outlined.SwapHoriz, "Swap translation languages")
                            }
                            TextButton({ language = "target" }, Modifier.weight(1f)) {
                                Text(model.languageName(prefs.target), maxLines = 1)
                            }
                            IconButton(
                                {
                                    expanded = false
                                    model.message(null)
                                },
                            ) {
                                Icon(Icons.Outlined.ExpandMore, "Collapse result")
                            }
                        }
                        state.message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                        SelectionContainer {
                            Text(
                                state.translation?.text
                                    ?: state.text.ifBlank {
                                        if (state.reading) "Reading on device…"
                                        else "Tap text, circle an area, or enter a search."
                                    },
                                Modifier.fillMaxWidth()
                                    .heightIn(max = 180.dp)
                                    .verticalScroll(rememberScrollState()),
                            )
                        }
                        if (state.reading || state.translating)
                            LinearProgressIndicator(Modifier.fillMaxWidth())
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            TextButton(
                                {
                                    context
                                        .getSystemService(ClipboardManager::class.java)
                                        .setPrimaryClip(
                                            ClipData.newPlainText(
                                                "Selected text",
                                                state.translation?.text ?: state.text,
                                            ),
                                        )
                                },
                                enabled = state.text.isNotBlank(),
                            ) {
                                Text("Copy")
                            }
                            TextButton(
                                ::search,
                                enabled = state.text.isNotBlank() || query.isNotBlank(),
                            ) {
                                Text("Search")
                            }
                            IconButton(
                                { editText = state.text },
                                enabled = state.text.isNotBlank(),
                            ) {
                                Icon(Icons.Outlined.Edit, "Edit selected text")
                            }
                            if (state.translatedLines.isNotEmpty())
                                IconButton(model::toggleOverlay) {
                                    Icon(Icons.Outlined.Visibility, "Toggle original text")
                                }
                            if (state.image != null && provider.imageUploadUrl != null)
                                IconButton(
                                    {
                                        scope.launch {
                                            runCatching {
                                                val uri =
                                                    Images.shareCrop(
                                                        context,
                                                        state.image!!.bitmap,
                                                        state.selection,
                                                    )
                                                context.startActivity(
                                                    Intent(
                                                            context,
                                                            VisualSearchActivity::class.java,
                                                        )
                                                        .putExtra("crop", uri.toString())
                                                        .putExtra("url", provider.imageUploadUrl)
                                                        .putExtra("provider", provider.name),
                                                )
                                            }
                                                .onFailure {
                                                    model.message("Could not prepare image search")
                                                }
                                        }
                                    },
                                ) {
                                    Icon(Icons.Outlined.ImageSearch, "Search selected image")
                                }
                        }
                        state.missingModels.forEach { tag ->
                            TextButton(
                                { model.download(tag) },
                                enabled = tag !in state.downloading,
                            ) {
                                Text(
                                    if (tag in state.downloading)
                                        "Downloading ${model.languageName(tag)}…"
                                    else "Download ${model.languageName(tag)}",
                                )
                            }
                        }
                        TextButton(
                            {
                                context.startActivity(
                                    Intent(context, CapturePermissionActivity::class.java)
                                        .setAction(CapturePermissionActivity.LIVE_TRANSLATE),
                                )
                                dismiss()
                            },
                        ) {
                            Icon(Icons.Outlined.ScreenShare, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Live screen translate")
                        }
                    }
                }
            }
            Surface(
                shape = RoundedCornerShape(36.dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = .96f),
                tonalElevation = 6.dp,
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextField(
                        query,
                        { query = it },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text("Search this screen…", maxLines = 1) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { search() }),
                        shape = CircleShape,
                        colors =
                            TextFieldDefaults.colors(
                                focusedContainerColor = Color.Transparent,
                                unfocusedContainerColor = Color.Transparent,
                                focusedIndicatorColor = Color.Transparent,
                                unfocusedIndicatorColor = Color.Transparent,
                            ),
                        trailingIcon = {
                            IconButton(::search) { Icon(Icons.Outlined.Search, "Search") }
                        },
                    )
                    IconButton({ music = !music }) {
                        Icon(Icons.Outlined.MusicNote, "Recognize music")
                    }
                    IconButton(
                        {
                            expanded = true
                            music = false
                            if (state.reading) pendingTranslation = true else model.translate()
                        },
                    ) {
                        Icon(Icons.Outlined.Translate, "Translate screen")
                    }
                    IconButton({ openApp(MainActivity.CAMERA) }) {
                        Icon(Icons.Outlined.PhotoCamera, "Open camera")
                    }
                }
            }
        }
    }
    language?.let { kind ->
        LanguageDialog(
            if (kind == "source") "Translate from" else "Translate to",
            model.graph.engines.translator(prefs.translationEngine).languages,
            if (kind == "source") prefs.source else prefs.target,
            kind == "source",
            { language = null },
        ) {
            model.set(kind, it)
        }
    }
    editText?.let { value ->
        AlertDialog(
            onDismissRequest = { editText = null },
            title = { Text("Edit selected text") },
            text = {
                OutlinedTextField(
                    value,
                    { editText = it },
                    Modifier.heightIn(max = 280.dp),
                    minLines = 3,
                    label = { Text("Text") },
                )
            },
            confirmButton = {
                TextButton(
                    {
                        model.editText(value)
                        editText = null
                    },
                ) {
                    Text("Save")
                }
            },
            dismissButton = { TextButton({ editText = null }) { Text("Cancel") } },
        )
    }
}

/** One finite refraction sweep; the resting border is still, including in live modes. */
@Composable
fun EdgeLight(busy: Boolean = false) {
    val progress = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        if (ValueAnimator.areAnimatorsEnabled()) progress.animateTo(1f, tween(850))
        else progress.snapTo(1f)
    }
    val first = lerp(MaterialTheme.colorScheme.primary, Color(0xFF66E2CE), .55f)
    val second = lerp(MaterialTheme.colorScheme.tertiary, Color(0xFFB995FF), .55f)
    Canvas(Modifier.fillMaxSize()) {
        val brush =
            Brush.linearGradient(
                listOf(first, second, first),
                Offset(0f, size.height * (1 - progress.value)),
                Offset(size.width, size.height),
            )
        for (width in listOf(40f, 24f, 10f, 3f)) {
            drawRoundRect(
                brush,
                cornerRadius = CornerRadius(30.dp.toPx()),
                style = Stroke(width.dp.toPx()),
                alpha = if (width == 3f) .85f else if (busy) .12f else .07f,
            )
        }
    }
}
