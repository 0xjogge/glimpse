@file:OptIn(ExperimentalMaterial3Api::class)

package app.glimpse.ui

import android.app.role.RoleManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.glimpse.*
import app.glimpse.camera.CameraScreen
import app.glimpse.core.*
import app.glimpse.data.Images
import app.glimpse.search.VisualSearchActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun GlimpseApp(model: GlimpseViewModel, requestAssistant: () -> Unit, captureScreen: () -> Unit) {
    val state by model.state.collectAsStateWithLifecycle()
    val prefs by model.preferences.collectAsStateWithLifecycle()
    val context = LocalContext.current
    ConfigureSystemBars(
        state.page == Page.CAMERA ||
            when (prefs.theme) {
                "dark" -> true
                "light" -> false
                else -> androidx.compose.foundation.isSystemInDarkTheme()
            },
    )
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var returnPage by rememberSaveable { mutableStateOf(Page.HOME) }
    var searchMode by remember { mutableStateOf<String?>(null) }
    var editText by remember { mutableStateOf<String?>(null) }
    var languageDialog by remember { mutableStateOf<String?>(null) }
    var moreMenu by remember { mutableStateOf(false) }
    var selectedTab by remember(state.image?.id) { mutableIntStateOf(0) }
    var assistantHeld by remember { mutableStateOf(false) }
    val picker =
        rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) {
            it?.let(model::load)
        }
    val saveImage =
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/jpeg")) {
            uri ->
            if (uri != null)
                scope.launch {
                    try {
                        val snapshot = state
                        val bitmap = snapshot.image?.bitmap ?: return@launch
                        withContext(Dispatchers.IO) {
                            context.contentResolver.openOutputStream(uri)?.use {
                                Images.crop(bitmap, snapshot.selection)
                                    .compress(android.graphics.Bitmap.CompressFormat.JPEG, 95, it)
                            } ?: error("Could not open the chosen destination")
                        }
                        model.message("Image saved")
                    } catch (e: Exception) {
                        model.message(e.localizedMessage ?: "Could not save the image")
                    }
                }
        }

    fun navigate(page: Page) {
        returnPage = state.page
        model.page(page)
    }
    fun back() {
        when (state.page) {
            Page.SETTINGS,
            Page.MODELS ->
                model.page(
                    if (returnPage in listOf(Page.SETTINGS, Page.MODELS))
                        if (state.image != null) Page.WORKSPACE else Page.HOME
                    else returnPage,
                )
            Page.CAMERA -> model.page(if (state.image != null) Page.WORKSPACE else Page.HOME)
            else -> model.home()
        }
    }
    fun copy(text: String) {
        if (text.isBlank()) return
        context
            .getSystemService(ClipboardManager::class.java)
            .setPrimaryClip(ClipData.newPlainText("Glimpse", text))
    }
    fun browser(url: String) {
        runCatching {
            requireSecureUrl(url)
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }
            .onFailure { model.message("No browser could open this link") }
    }
    fun onlineTranslation() {
        if (state.text.length > 8000) {
            model.message("Select a shorter passage for browser translation")
            return
        }
        browser(prefs.browserTranslator.url(state.text, prefs.source, prefs.target))
    }

    BackHandler(state.page != Page.HOME, ::back)
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        assistantHeld =
            context.getSystemService(RoleManager::class.java).isRoleHeld(RoleManager.ROLE_ASSISTANT)
    }
    LaunchedEffect(state.message) {
        val message = state.message ?: return@LaunchedEffect
        snackbar.showSnackbar(message)
        if (model.state.value.message == message) model.message(null)
    }
    LaunchedEffect(state.translation) { if (state.translation != null) selectedTab = 1 }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            if (state.page != Page.CAMERA)
                TopAppBar(
                    title = {
                        Text(
                            when (state.page) {
                                Page.HOME -> "Glimpse"
                                Page.WORKSPACE -> "${state.image?.origin ?: "Selected text"}"
                                Page.SETTINGS -> "Settings"
                                Page.MODELS -> "Offline languages"
                                else -> "Glimpse"
                            },
                            style = MaterialTheme.typography.titleLarge,
                        )
                    },
                    navigationIcon = {
                        if (state.page != Page.HOME)
                            IconButton(onClick = ::back) { Icon(Icons.Outlined.ArrowBack, "Back") }
                    },
                    actions = {
                        // Always visible when the assistant opens the screen workspace.
                        IconButton(onClick = { navigate(Page.CAMERA) }) {
                            Icon(Icons.Outlined.PhotoCamera, "Open camera")
                        }
                        if (state.page == Page.HOME || state.page == Page.WORKSPACE) {
                            IconButton(onClick = { navigate(Page.SETTINGS) }) {
                                Icon(Icons.Outlined.Settings, "Settings")
                            }
                        }
                        if (state.page == Page.WORKSPACE && state.image != null) {
                            Box {
                                IconButton(onClick = { moreMenu = true }) {
                                    Icon(Icons.Outlined.MoreVert, "Image options")
                                }
                                DropdownMenu(moreMenu, { moreMenu = false }) {
                                    DropdownMenuItem(
                                        text = { Text("Share selected image") },
                                        onClick = {
                                            moreMenu = false
                                            scope.launch {
                                                try {
                                                    val uri =
                                                        Images.shareCrop(
                                                            context,
                                                            state.image!!.bitmap,
                                                            state.selection,
                                                        )
                                                    context.startActivity(
                                                        Intent.createChooser(
                                                            Intent(Intent.ACTION_SEND)
                                                                .setType("image/jpeg")
                                                                .putExtra(Intent.EXTRA_STREAM, uri)
                                                                .addFlags(
                                                                    Intent
                                                                        .FLAG_GRANT_READ_URI_PERMISSION,
                                                                ),
                                                            "Share image",
                                                        ),
                                                    )
                                                } catch (e: Exception) {
                                                    model.message(
                                                        e.localizedMessage
                                                            ?: "Could not share image",
                                                    )
                                                }
                                            }
                                        },
                                        leadingIcon = { Icon(Icons.Outlined.Share, null) },
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Save selected image") },
                                        onClick = {
                                            moreMenu = false
                                            saveImage.launch("glimpse.jpg")
                                        },
                                        leadingIcon = { Icon(Icons.Outlined.SaveAlt, null) },
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Recognize again") },
                                        onClick = {
                                            moreMenu = false
                                            model.recognize()
                                        },
                                        leadingIcon = { Icon(Icons.Outlined.Refresh, null) },
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Close image") },
                                        onClick = {
                                            moreMenu = false
                                            model.home()
                                        },
                                        leadingIcon = { Icon(Icons.Outlined.Close, null) },
                                    )
                                }
                            }
                        }
                    },
                )
        },
        bottomBar = {
            if (state.page == Page.WORKSPACE)
                Surface(tonalElevation = 3.dp) {
                    Row(
                        Modifier.fillMaxWidth()
                            .navigationBarsPadding()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                    ) {
                        Action("Copy", Icons.Outlined.ContentCopy, state.text.isNotBlank()) {
                            copy(
                                if (selectedTab == 1) state.translation?.text ?: state.text
                                else state.text,
                            )
                        }
                        Action(
                            "Translate",
                            Icons.Outlined.Translate,
                            state.text.isNotBlank() && !state.translating,
                            state.translating,
                            model::translate,
                        )
                        Action("Search text", Icons.Outlined.Search, state.text.isNotBlank()) {
                            searchMode = "text"
                        }
                        Action("Search image", Icons.Outlined.ImageSearch, state.image != null) {
                            searchMode = "image"
                        }
                    }
                }
        },
    ) { padding ->
        when (state.page) {
            Page.HOME ->
                HomeScreen(
                    Modifier.padding(padding),
                    assistantHeld,
                    { navigate(Page.CAMERA) },
                    {
                        picker.launch(
                            PickVisualMediaRequest(
                                ActivityResultContracts.PickVisualMedia.ImageOnly,
                            ),
                        )
                    },
                    requestAssistant,
                    captureScreen,
                    model::sample,
                )
            Page.WORKSPACE ->
                Column(Modifier.fillMaxSize().padding(padding)) {
                    LanguageStrip(
                        model.languageName(prefs.source),
                        model.languageName(prefs.target),
                        { languageDialog = "source" },
                        { languageDialog = "target" },
                        {
                            if (prefs.source == "auto")
                                model.set("target", if (prefs.target == "en") "ja" else "en")
                            else {
                                model.set("source", prefs.target)
                                model.set("target", prefs.source)
                            }
                        },
                    )
                    if (state.image != null) {
                        Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp)) {
                            SelectableImage(state, Modifier.fillMaxSize(), model::select)
                            Row(
                                Modifier.align(Alignment.TopEnd).padding(8.dp),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                if (state.overlay || state.translatedLines.isNotEmpty())
                                    FilledTonalIconButton(onClick = model::toggleOverlay) {
                                        Icon(
                                            if (state.overlay) Icons.Outlined.Visibility
                                            else Icons.Outlined.VisibilityOff,
                                            "Toggle translated image",
                                        )
                                    }
                                FilledTonalButton(
                                    onClick = { model.select(null) },
                                    contentPadding = PaddingValues(horizontal = 12.dp),
                                ) {
                                    Icon(Icons.Outlined.SelectAll, null, Modifier.size(18.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text("Select all")
                                }
                            }
                        }
                        Text(
                            if (state.selection == null) "Circle an area or tap text"
                            else "Drag the corner handles to refine your selection",
                            Modifier.fillMaxWidth().padding(8.dp),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                    } else Spacer(Modifier.height(8.dp))
                    Surface(
                        Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(bottom = 8.dp),
                        shape = RoundedCornerShape(24.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                    ) {
                        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                FilterChip(
                                    selectedTab == 0,
                                    { selectedTab = 0 },
                                    label = { Text("Original") },
                                )
                                Spacer(Modifier.width(8.dp))
                                FilterChip(
                                    selectedTab == 1,
                                    { selectedTab = 1 },
                                    label = { Text("Translation") },
                                    enabled = state.translation != null,
                                )
                                Spacer(Modifier.weight(1f))
                                IconButton(
                                    onClick = { editText = state.text },
                                    enabled = state.text.isNotBlank(),
                                ) {
                                    Icon(Icons.Outlined.Edit, "Edit recognized text")
                                }
                            }
                            SelectionContainer {
                                Text(
                                    if (selectedTab == 1 && state.translation != null)
                                        state.translation!!.text
                                    else
                                        state.text.ifBlank {
                                            if (state.reading) "Reading on your device…"
                                            else
                                                "No text in this selection. You can still search with the image."
                                        },
                                    Modifier.fillMaxWidth()
                                        .heightIn(
                                            min = 40.dp,
                                            max = if (state.image == null) 360.dp else 132.dp,
                                        )
                                        .verticalScroll(rememberScrollState()),
                                    style = MaterialTheme.typography.bodyLarge,
                                )
                            }
                            if (state.translating)
                                LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
                            if (state.missingModels.isNotEmpty()) {
                                Text(
                                    "Download ${state.missingModels.joinToString { model.languageName(it) }} to translate offline.",
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.padding(top = 8.dp),
                                )
                                Row {
                                    TextButton(
                                        onClick = { state.missingModels.forEach(model::download) },
                                        enabled = state.downloading.isEmpty(),
                                    ) {
                                        Text(
                                            if (state.downloading.isEmpty()) "Download models"
                                            else "Downloading…",
                                        )
                                    }
                                    TextButton(onClick = ::onlineTranslation) {
                                        Text("Use browser")
                                    }
                                }
                            } else if (state.text.isNotBlank()) {
                                Row(
                                    Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Icon(
                                        Icons.Outlined.Smartphone,
                                        null,
                                        Modifier.size(14.dp),
                                        tint = MaterialTheme.colorScheme.primary,
                                    )
                                    Text(
                                        "  On device",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                    Spacer(Modifier.weight(1f))
                                    TextButton(
                                        onClick = ::onlineTranslation,
                                        contentPadding = PaddingValues(horizontal = 8.dp),
                                    ) {
                                        Text(
                                            "Translate online",
                                            style = MaterialTheme.typography.labelMedium,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            Page.SETTINGS ->
                SettingsScreen(
                    model,
                    Modifier.padding(padding),
                    requestAssistant,
                    { navigate(Page.MODELS) },
                )
            Page.MODELS -> ModelsScreen(model, Modifier.padding(padding))
            Page.CAMERA ->
                CameraScreen(
                    model,
                    ::back,
                    { navigate(Page.MODELS) },
                    { languageDialog = "target" },
                )
        }
    }

    searchMode?.let { mode ->
        ModalBottomSheet(onDismissRequest = { searchMode = null }) {
            Column(Modifier.navigationBarsPadding().padding(bottom = 24.dp)) {
                Text(
                    if (mode == "image") "Search with your image" else "Search selected text",
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
                )
                Text(
                    if (mode == "image")
                        "Your selected crop is ready. Choose the engine, then tap its image-upload control."
                    else "Choose where to search. Results open in your browser.",
                    Modifier.padding(horizontal = 24.dp).padding(bottom = 16.dp),
                    style = MaterialTheme.typography.bodyMedium,
                )
                prefs.providers
                    .filter { mode != "image" || it.imageUploadUrl != null }
                    .sortedBy { it.id != prefs.search }
                    .forEach { provider ->
                        ListItem(
                            headlineContent = { Text(provider.name) },
                            leadingContent = {
                                Icon(
                                    if (mode == "image") Icons.Outlined.ImageSearch
                                    else Icons.Outlined.Search,
                                    null,
                                )
                            },
                            trailingContent = { Icon(Icons.Outlined.OpenInNew, null) },
                            modifier =
                                Modifier.clickable {
                                    searchMode = null
                                    model.set("search", provider.id)
                                    if (mode == "text") {
                                        if (state.text.length > 8000)
                                            model.message("Select a shorter passage to search")
                                        else browser(provider.textUrl(state.text))
                                    } else
                                        scope.launch {
                                            try {
                                                val bitmap = state.image?.bitmap ?: return@launch
                                                val uri =
                                                    Images.shareCrop(
                                                        context,
                                                        bitmap,
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
                                            } catch (e: Exception) {
                                                model.message(
                                                    e.localizedMessage
                                                        ?: "Could not prepare the image",
                                                )
                                            }
                                        }
                                },
                        )
                    }
                if (mode == "image")
                    Text(
                        "Kagi and custom text engines appear under Search text. Image upload is currently available for Google and Bing.",
                        Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
            }
        }
    }
    languageDialog?.let { kind ->
        LanguageDialog(
            if (kind == "source") "Translate from" else "Translate to",
            model.graph.engines.translator(prefs.translationEngine).languages,
            if (kind == "source") prefs.source else prefs.target,
            kind == "source",
            { languageDialog = null },
        ) {
            model.set(kind, it)
        }
    }
    editText?.let { value ->
        AlertDialog(
            onDismissRequest = { editText = null },
            title = { Text("Edit recognized text") },
            text = {
                OutlinedTextField(
                    value,
                    { editText = it },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 160.dp, max = 340.dp),
                    label = { Text("Text") },
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        model.editText(value)
                        editText = null
                        selectedTab = 0
                    },
                ) {
                    Text("Save")
                }
            },
            dismissButton = { TextButton(onClick = { editText = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun RowScope.Action(
    label: String,
    icon: ImageVector,
    enabled: Boolean,
    busy: Boolean = false,
    onClick: () -> Unit,
) {
    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
        FilledTonalIconButton(
            onClick = onClick,
            enabled = enabled,
            modifier = Modifier.size(width = 64.dp, height = 48.dp),
        ) {
            if (busy) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
            else Icon(icon, label)
        }
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color =
                if (enabled) MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
            maxLines = 1,
        )
    }
}

@Composable
fun LanguageStrip(
    source: String,
    target: String,
    onSource: () -> Unit,
    onTarget: () -> Unit,
    onSwap: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        TextButton(onClick = onSource, modifier = Modifier.weight(1f)) {
            Text(source, maxLines = 1)
        }
        IconButton(onClick = onSwap) {
            Icon(Icons.Outlined.SwapHoriz, "Swap translation languages")
        }
        TextButton(onClick = onTarget, modifier = Modifier.weight(1f)) {
            Text(target, maxLines = 1)
            Icon(Icons.Outlined.ExpandMore, null, Modifier.size(18.dp))
        }
    }
}

@Composable
private fun HomeScreen(
    modifier: Modifier,
    assistantHeld: Boolean,
    camera: () -> Unit,
    image: () -> Unit,
    assistant: () -> Unit,
    screen: () -> Unit,
    sample: () -> Unit,
) {
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(24.dp))
        Box(
            Modifier.size(136.dp)
                .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(40.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Outlined.DocumentScanner,
                null,
                Modifier.size(72.dp),
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Surface(
                Modifier.align(Alignment.BottomEnd).offset(x = 8.dp, y = 8.dp),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.tertiaryContainer,
            ) {
                Text(
                    "あ",
                    Modifier.padding(12.dp),
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                )
            }
        }
        Spacer(Modifier.height(32.dp))
        Text(
            "A closer look.",
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            "Copy, translate and search what you see.",
            Modifier.padding(top = 12.dp, bottom = 28.dp),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Button(onClick = camera, Modifier.fillMaxWidth().height(56.dp)) {
            Icon(Icons.Outlined.PhotoCamera, null)
            Spacer(Modifier.width(10.dp))
            Text("Open camera")
        }
        OutlinedButton(
            onClick = image,
            Modifier.fillMaxWidth().padding(top = 12.dp).height(56.dp),
        ) {
            Icon(Icons.Outlined.PhotoLibrary, null)
            Spacer(Modifier.width(10.dp))
            Text("Choose an image")
        }
        Spacer(Modifier.height(24.dp))
        ElevatedCard(
            onClick = if (assistantHeld) screen else assistant,
            modifier = Modifier.fillMaxWidth(),
            colors =
                CardDefaults.elevatedCardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                ),
        ) {
            Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Outlined.Screenshot,
                    null,
                    Modifier.size(28.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Column(Modifier.weight(1f).padding(horizontal = 16.dp)) {
                    Text(
                        if (assistantHeld) "Scan your screen" else "Make it one gesture away",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        if (assistantHeld) "Or use your assistant gesture in any app"
                        else "Set Glimpse as your digital assistant",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Icon(Icons.Outlined.ChevronRight, null)
            }
        }
        TextButton(onClick = sample, Modifier.padding(top = 12.dp)) {
            Icon(Icons.Outlined.AutoAwesome, null, Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Try an example")
        }
        Spacer(Modifier.height(20.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Outlined.Smartphone,
                null,
                Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Text(
                "  On-device text recognition & translation",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(24.dp))
    }
}
