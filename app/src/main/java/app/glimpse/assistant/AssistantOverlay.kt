package app.glimpse.assistant

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
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
fun AssistantOverlay(
    model: GlimpseViewModel,
    dismiss: () -> Unit,
    openApp: (String) -> Unit,
    translateScreen: () -> Unit,
    initialAction: AssistantAction? = null,
    initialQuery: String = "",
    submitInitialSearch: Boolean = false,
) {
    val state by model.state.collectAsStateWithLifecycle()
    val prefs by model.preferences.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var query by rememberSaveable { mutableStateOf(initialQuery) }
    var expanded by rememberSaveable { mutableStateOf(false) }
    var language by remember { mutableStateOf<String?>(null) }
    var editText by remember { mutableStateOf<String?>(null) }
    var menu by remember { mutableStateOf(false) }
    var mode by rememberSaveable { mutableStateOf(initialAction?.name) }
    val music = mode == AssistantAction.MUSIC.name
    val marking = mode == AssistantAction.MARK.name
    val imeVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    // Dialogs and popups own their Back; the IME consumes Back before the workspace exits.
    BackHandler(language == null && editText == null && !menu && !imeVisible, dismiss)
    val provider =
        prefs.providers.firstOrNull { it.id == prefs.search } ?: SearchProviders.builtIn.first()
    fun search() {
        val text = query.ifBlank { state.text }.take(8000)
        if (text.isBlank()) {
            mode = null
            expanded = true
            return
        }
        runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(provider.textUrl(text))))
        }
            .onFailure { model.message("No browser could open the search") }
    }
    LaunchedEffect(Unit) {
        if (submitInitialSearch) search()
    }

    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .08f))) {
        state.image?.let { image ->
            if (marking) {
                SelectableImage(state, Modifier.fillMaxSize()) {
                    model.select(it)
                    expanded = true
                }
            } else {
                Image(
                    image.bitmap.asImageBitmap(),
                    image.origin,
                    Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit,
                )
            }
        }
        EdgeLight(state.reading || state.translating)
        Row(
            Modifier.fillMaxWidth().statusBarsPadding().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
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
                            mode = AssistantAction.MARK.name
                            model.select(null)
                            expanded = true
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
                app.glimpse.music.MusicPanel(onClose = { mode = null })
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
                    }
                }
            }
            if (marking && !expanded && state.message == null)
                Surface(shape = RoundedCornerShape(16.dp)) {
                    Text(
                        if (state.image == null) "Enter a search; this screen is unavailable."
                        else "Circle an area or tap text to search.",
                        Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            AndroidView(
                factory = { AssistantActionStrip(it) },
                modifier = Modifier.fillMaxWidth().wrapContentHeight(),
                update = { strip ->
                    strip.onQueryChanged = { query = it }
                    strip.query = query
                    strip.selectedAction = AssistantAction.entries.firstOrNull { it.name == mode }
                    strip.onSearch = { search() }
                    strip.onAction = { action ->
                        when (action) {
                            AssistantAction.MARK -> {
                                mode = action.name
                                expanded = false
                            }
                            AssistantAction.TRANSLATE -> translateScreen()
                            AssistantAction.MUSIC -> mode = action.name
                            AssistantAction.CAMERA -> openApp(MainActivity.CAMERA)
                        }
                    }
                },
            )
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

/** The native bridge, Compose workspace and live service share one stationary renderer. */
@Composable
fun EdgeLight(busy: Boolean = false) {
    val density = LocalDensity.current.density
    val view = LocalView.current
    val renderer = remember(density) { EdgeGlowRenderer(density) }
    val location = remember { IntArray(2) }
    // A full-screen AndroidView would occlude the underlying Compose accessibility nodes even
    // when its native View is non-interactive. Draw the shared renderer in Compose instead.
    Canvas(Modifier.fillMaxSize()) {
        view.getLocationInWindow(location)
        renderer.busy = busy
        renderer.updateBounds(
            size.width.toInt(),
            size.height.toInt(),
            view.rootWindowInsets,
            location[0],
            location[1],
        )
        renderer.draw(drawContext.canvas.nativeCanvas)
    }
}
