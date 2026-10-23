@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package app.glimpse.camera

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.net.Uri
import android.provider.Settings
import android.view.Surface
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.glimpse.GlimpseViewModel
import app.glimpse.core.ImagePlacement
import app.glimpse.data.ImageSession
import app.glimpse.ui.drawTranslatedLines
import java.util.concurrent.Executors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@Composable
fun CameraScreen(
    model: GlimpseViewModel,
    back: () -> Unit,
    models: () -> Unit,
    target: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    var permission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    val request =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
            permission = it
        }
    val picker =
        rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) {
            it?.let(model::load)
        }
    var liveEnabled by rememberSaveable { mutableStateOf(false) }
    val currentLive by rememberUpdatedState(liveEnabled)
    var torch by remember { mutableStateOf(false) }
    var zoom by remember { mutableFloatStateOf(1f) }
    var camera by remember { mutableStateOf<Camera?>(null) }
    var capture by remember { mutableStateOf<ImageCapture?>(null) }
    var takingPicture by remember { mutableStateOf(false) }
    val live by model.live.collectAsStateWithLifecycle()
    val prefs by model.preferences.collectAsStateWithLifecycle()
    val preview = remember {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FIT_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
    }
    val executor = remember { Executors.newSingleThreadExecutor() }
    var cameraError by remember { mutableStateOf<String?>(null) }
    var retry by remember { mutableIntStateOf(0) }

    LaunchedEffect(Unit) { if (!permission) request.launch(Manifest.permission.CAMERA) }
    LaunchedEffect(
        liveEnabled,
        prefs.source,
        prefs.target,
        prefs.ocrEngine,
        prefs.translationEngine,
    ) {
        model.resetLive()
    }
    DisposableEffect(permission, retry) {
        var disposed = false
        var provider: ProcessCameraProvider? = null
        var analysis: ImageAnalysis? = null
        var bound = emptyArray<UseCase>()
        if (permission) {
            val future = ProcessCameraProvider.getInstance(context)
            future.addListener(
                {
                    if (!disposed) {
                        try {
                            val cameraProvider = future.get()
                            provider = cameraProvider
                            val rotation = preview.display?.rotation ?: Surface.ROTATION_0
                            val previewUseCase =
                                Preview.Builder()
                                    .setTargetAspectRatio(AspectRatio.RATIO_4_3)
                                    .setTargetRotation(rotation)
                                    .build()
                            previewUseCase.surfaceProvider = preview.surfaceProvider
                            val imageCapture =
                                ImageCapture.Builder()
                                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                                    .setTargetAspectRatio(AspectRatio.RATIO_4_3)
                                    .setTargetRotation(rotation)
                                    .build()
                            val imageAnalysis =
                                ImageAnalysis.Builder()
                                    .setTargetResolution(android.util.Size(1280, 960))
                                    .setTargetRotation(rotation)
                                    .setBackpressureStrategy(
                                        ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST,
                                    )
                                    .build()
                            analysis = imageAnalysis
                            var lastFrame = 0L
                            imageAnalysis.setAnalyzer(executor) { proxy ->
                                val now = android.os.SystemClock.elapsedRealtime()
                                if (!currentLive || now - lastFrame < 650) proxy.close()
                                else {
                                    lastFrame = now
                                    scope.launch(Dispatchers.Default) {
                                        try {
                                            model.analyzeFrame(upright(proxy))
                                        } finally {
                                            proxy.close()
                                        }
                                    }
                                }
                            }
                            bound = arrayOf(previewUseCase, imageCapture, imageAnalysis)
                            camera =
                                cameraProvider.bindToLifecycle(
                                    lifecycle,
                                    CameraSelector.DEFAULT_BACK_CAMERA,
                                    *bound,
                                )
                            capture = imageCapture
                            cameraError = null
                        } catch (e: Exception) {
                            cameraError = e.localizedMessage ?: "Camera could not start"
                        }
                    }
                },
                ContextCompat.getMainExecutor(context),
            )
        }
        onDispose {
            disposed = true
            analysis?.clearAnalyzer()
            provider?.unbind(*bound)
            capture = null
            camera = null
        }
    }
    DisposableEffect(Unit) { onDispose { executor.shutdown() } }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (permission) AndroidView({ preview }, Modifier.fillMaxSize())
        if (permission && liveEnabled)
            Canvas(Modifier.fillMaxSize()) {
                val recognition = live.recognition ?: return@Canvas
                val placement =
                    ImagePlacement.fit(
                        recognition.imageWidth,
                        recognition.imageHeight,
                        size.width,
                        size.height,
                    )
                drawTranslatedLines(
                    placement,
                    recognition.lines,
                    live.translations,
                    Color(0xFF18231C),
                    Color(0xFFF0F8EF),
                )
            }
        if (!permission || cameraError != null)
            Column(
                Modifier.align(Alignment.Center).padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(Icons.Outlined.PhotoCamera, null, Modifier.size(56.dp), tint = Color.White)
                Text(
                    cameraError
                        ?: "Allow camera access to scan and translate what is in front of you.",
                    Modifier.padding(vertical = 20.dp),
                    color = Color.White,
                )
                Button(
                    onClick = {
                        if (permission) retry++ else request.launch(Manifest.permission.CAMERA)
                    },
                ) {
                    Text(if (permission) "Retry camera" else "Allow camera")
                }
                if (!permission)
                    TextButton(
                        onClick = {
                            context.startActivity(
                                Intent(
                                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                    Uri.parse("package:${context.packageName}"),
                                ),
                            )
                        },
                    ) {
                        Text("App settings", color = Color.White)
                    }
            }
        Row(
            Modifier.fillMaxWidth().statusBarsPadding().padding(12.dp).align(Alignment.TopCenter),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FilledIconButton(
                onClick = back,
                colors =
                    IconButtonDefaults.filledIconButtonColors(
                        containerColor = Color.Black.copy(alpha = .6f),
                        contentColor = Color.White,
                    ),
            ) {
                Icon(Icons.Outlined.ArrowBack, "Back")
            }
            Spacer(Modifier.weight(1f))
            FilledTonalButton(onClick = target) {
                Icon(Icons.Outlined.Translate, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(model.languageName(prefs.target))
            }
            Spacer(Modifier.width(8.dp))
            FilledIconButton(
                onClick = models,
                colors =
                    IconButtonDefaults.filledIconButtonColors(
                        containerColor = Color.Black.copy(alpha = .6f),
                        contentColor = Color.White,
                    ),
            ) {
                Icon(Icons.Outlined.DownloadForOffline, "Offline languages")
            }
        }
        Column(
            Modifier.fillMaxWidth()
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (liveEnabled)
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = Color.Black.copy(alpha = .75f),
                    contentColor = Color.White,
                ) {
                    Row(
                        Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            live.status,
                            Modifier.weight(1f),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        if (!live.recognition?.text.isNullOrBlank())
                            IconButton(
                                onClick = {
                                    context
                                        .getSystemService(ClipboardManager::class.java)
                                        .setPrimaryClip(
                                            ClipData.newPlainText(
                                                "Glimpse",
                                                live.recognition?.text.orEmpty(),
                                            ),
                                        )
                                },
                            ) {
                                Icon(Icons.Outlined.ContentCopy, "Copy camera text")
                            }
                    }
                }
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(top = 8.dp),
            ) {
                FilterChip(
                    !liveEnabled,
                    { liveEnabled = false },
                    label = { Text("Photo") },
                    leadingIcon = { Icon(Icons.Outlined.PhotoCamera, null, Modifier.size(18.dp)) },
                )
                FilterChip(
                    liveEnabled,
                    { liveEnabled = true },
                    label = { Text("Live translate") },
                    leadingIcon = { Icon(Icons.Outlined.Translate, null, Modifier.size(18.dp)) },
                )
            }
            Row(
                Modifier.fillMaxWidth().padding(vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FilledIconToggleButton(
                    torch,
                    { value ->
                        torch = value
                        camera?.cameraControl?.enableTorch(value)
                    },
                    enabled = camera?.cameraInfo?.hasFlashUnit() == true,
                    colors =
                        IconButtonDefaults.filledIconToggleButtonColors(
                            containerColor = Color(0xFF26342B),
                            contentColor = Color.White,
                        ),
                ) {
                    Icon(
                        if (torch) Icons.Outlined.FlashOn else Icons.Outlined.FlashOff,
                        "Toggle torch",
                    )
                }
                FilledIconButton(
                    onClick = {
                        val imageCapture = capture ?: return@FilledIconButton
                        takingPicture = true
                        imageCapture.takePicture(
                            executor,
                            object : ImageCapture.OnImageCapturedCallback() {
                                override fun onCaptureSuccess(image: ImageProxy) {
                                    try {
                                        val bitmap = upright(image)
                                        scope.launch {
                                            takingPicture = false
                                            ImageSession.offer(bitmap, "Camera photo")
                                        }
                                    } catch (e: Exception) {
                                        scope.launch {
                                            takingPicture = false
                                            model.message(
                                                e.localizedMessage ?: "Could not capture photo",
                                            )
                                        }
                                    } finally {
                                        image.close()
                                    }
                                }

                                override fun onError(exception: ImageCaptureException) {
                                    scope.launch {
                                        takingPicture = false
                                        model.message(
                                            exception.localizedMessage ?: "Could not capture photo",
                                        )
                                    }
                                }
                            },
                        )
                    },
                    enabled = capture != null && !takingPicture,
                    modifier = Modifier.size(76.dp),
                    shape = CircleShape,
                    colors =
                        IconButtonDefaults.filledIconButtonColors(
                            containerColor = Color.White,
                            contentColor = Color.Black,
                        ),
                ) {
                    if (takingPicture) CircularProgressIndicator(Modifier.size(32.dp))
                    else Icon(Icons.Outlined.Camera, "Take picture", Modifier.size(42.dp))
                }
                FilledIconButton(
                    onClick = {
                        picker.launch(
                            PickVisualMediaRequest(
                                ActivityResultContracts.PickVisualMedia.ImageOnly,
                            ),
                        )
                    },
                    colors =
                        IconButtonDefaults.filledIconButtonColors(
                            containerColor = Color(0xFF26342B),
                            contentColor = Color.White,
                        ),
                ) {
                    Icon(Icons.Outlined.PhotoLibrary, "Choose an image")
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(1f, 2f).forEach { ratio ->
                    FilterChip(
                        zoom == ratio,
                        {
                            zoom = ratio
                            camera
                                ?.cameraControl
                                ?.setZoomRatio(
                                    ratio.coerceIn(
                                        camera!!.cameraInfo.zoomState.value!!.minZoomRatio,
                                        camera!!.cameraInfo.zoomState.value!!.maxZoomRatio,
                                    ),
                                )
                        },
                        label = { Text("${ratio.toInt()}×") },
                        enabled = camera != null,
                    )
                }
            }
        }
    }
}

private fun upright(proxy: ImageProxy): Bitmap {
    val bitmap = proxy.toBitmap()
    val crop = proxy.cropRect
    val cropped =
        if (crop.width() != bitmap.width || crop.height() != bitmap.height)
            Bitmap.createBitmap(bitmap, crop.left, crop.top, crop.width(), crop.height())
        else bitmap
    val degrees = proxy.imageInfo.rotationDegrees
    return if (degrees == 0) cropped
    else
        Bitmap.createBitmap(
            cropped,
            0,
            0,
            cropped.width,
            cropped.height,
            Matrix().apply { postRotate(degrees.toFloat()) },
            true,
        )
}
