package app.glimpse.ui

import android.graphics.Paint
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import app.glimpse.WorkspaceState
import app.glimpse.core.*
import kotlin.math.max

@Composable
fun SelectableImage(
    state: WorkspaceState,
    modifier: Modifier = Modifier,
    onSelect: (ImageRegion?) -> Unit,
) {
    val bitmap = state.image?.bitmap ?: return
    var size by remember { mutableStateOf(IntSize.Zero) }
    var dragging by remember(state.image.id) { mutableStateOf<ImageRegion?>(null) }
    var points by remember(state.image.id) { mutableStateOf<List<Offset>>(emptyList()) }
    val placement =
        remember(bitmap, size) {
            if (size.width > 0 && size.height > 0)
                ImagePlacement.fit(
                    bitmap.width,
                    bitmap.height,
                    size.width.toFloat(),
                    size.height.toFloat(),
                )
            else null
        }
    val currentSelect by rememberUpdatedState(onSelect)
    val currentRecognition by rememberUpdatedState(state.recognition)
    val currentSelection by rememberUpdatedState(state.selection)
    val accent = MaterialTheme.colorScheme.primary
    val background = MaterialTheme.colorScheme.surfaceContainer
    val overlayText = MaterialTheme.colorScheme.onSurface

    Box(modifier.background(background).onSizeChanged { size = it }) {
        Image(
            bitmap.asImageBitmap(),
            contentDescription = state.image.origin,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Fit,
        )
        Canvas(
            Modifier.fillMaxSize()
                .semantics {
                    contentDescription =
                        "Image selection. Drag a circle or rectangle, tap a text line, or use Select all."
                    customActions =
                        listOf(
                            CustomAccessibilityAction("Select all text") {
                                currentSelect(null)
                                true
                            },
                        )
                }
                .pointerInput(placement, bitmap) {
                    detectTapGestures { tap ->
                        val p = placement ?: return@detectTapGestures
                        if (!p.contains(tap.x, tap.y)) return@detectTapGestures
                        val x = p.imageX(tap.x)
                        val y = p.imageY(tap.y)
                        val line =
                            currentRecognition?.lines?.firstOrNull { it.bounds.contains(x, y) }
                        if (line != null) currentSelect(line.bounds)
                    }
                }
                .pointerInput(placement, bitmap) {
                    var start = Offset.Zero
                    var corner: Int? = null
                    var original: ImageRegion? = null
                    detectDragGestures(
                        onDragStart = { position ->
                            val p = placement ?: return@detectDragGestures
                            start = position
                            original = currentSelection
                            val r = currentSelection
                            val corners =
                                r?.let {
                                        listOf(
                                            Offset(
                                                p.left + it.left * p.width,
                                                p.top + it.top * p.height,
                                            ),
                                            Offset(
                                                p.left + it.right * p.width,
                                                p.top + it.top * p.height,
                                            ),
                                            Offset(
                                                p.left + it.right * p.width,
                                                p.top + it.bottom * p.height,
                                            ),
                                            Offset(
                                                p.left + it.left * p.width,
                                                p.top + it.bottom * p.height,
                                            ),
                                        )
                                    }
                                    .orEmpty()
                            corner =
                                corners.indices
                                    .minByOrNull { (corners[it] - position).getDistance() }
                                    ?.takeIf {
                                        (corners[it] - position).getDistance() < 32.dp.toPx()
                                    }
                            points =
                                if (p.contains(position.x, position.y)) listOf(position)
                                else emptyList()
                        },
                        onDrag = { change, _ ->
                            val p = placement ?: return@detectDragGestures
                            if (points.isEmpty()) return@detectDragGestures
                            change.consume()
                            points = points + change.position
                            val r = original
                            val c = corner
                            dragging =
                                if (c != null && r != null) {
                                    val x = p.imageX(change.position.x)
                                    val y = p.imageY(change.position.y)
                                    when (c) {
                                        0 -> ImageRegion.between(x, y, r.right, r.bottom)
                                        1 -> ImageRegion.between(r.left, y, x, r.bottom)
                                        2 -> ImageRegion.between(r.left, r.top, x, y)
                                        else -> ImageRegion.between(x, r.top, r.right, y)
                                    }
                                } else {
                                    // A circle/lasso becomes an adjustable bounding crop.
                                    ImageRegion.between(
                                        p.imageX(points.minOf { it.x }),
                                        p.imageY(points.minOf { it.y }),
                                        p.imageX(points.maxOf { it.x }),
                                        p.imageY(points.maxOf { it.y }),
                                    )
                                }
                        },
                        onDragEnd = {
                            dragging?.let(currentSelect)
                            dragging = null
                            points = emptyList()
                        },
                        onDragCancel = {
                            dragging = null
                            points = emptyList()
                        },
                    )
                },
        ) {
            val p = placement ?: return@Canvas
            val selected = dragging ?: state.selection
            for (line in state.recognition?.lines.orEmpty()) {
                if (selected == null || selected.includesCenter(line.bounds)) {
                    val r = line.bounds
                    drawRoundRect(
                        accent.copy(alpha = .15f),
                        Offset(p.left + r.left * p.width, p.top + r.top * p.height),
                        Size(r.width * p.width, r.height * p.height),
                        androidx.compose.ui.geometry.CornerRadius(4.dp.toPx()),
                    )
                }
            }
            if (points.size > 1) {
                val path =
                    Path().apply {
                        moveTo(points.first().x, points.first().y)
                        points.drop(1).forEach { lineTo(it.x, it.y) }
                    }
                drawPath(path, accent, style = Stroke(3.dp.toPx()))
            }
            selected?.let { r ->
                val left = p.left + r.left * p.width
                val top = p.top + r.top * p.height
                val right = p.left + r.right * p.width
                val bottom = p.top + r.bottom * p.height
                drawRect(
                    Color.Black.copy(alpha = .28f),
                    Offset(p.left, p.top),
                    Size(p.width, (top - p.top).coerceAtLeast(0f)),
                )
                drawRect(
                    Color.Black.copy(alpha = .28f),
                    Offset(p.left, bottom),
                    Size(p.width, (p.top + p.height - bottom).coerceAtLeast(0f)),
                )
                drawRect(
                    Color.Black.copy(alpha = .28f),
                    Offset(p.left, top),
                    Size((left - p.left).coerceAtLeast(0f), bottom - top),
                )
                drawRect(
                    Color.Black.copy(alpha = .28f),
                    Offset(right, top),
                    Size((p.left + p.width - right).coerceAtLeast(0f), bottom - top),
                )
                drawRoundRect(
                    accent,
                    Offset(left, top),
                    Size(right - left, bottom - top),
                    androidx.compose.ui.geometry.CornerRadius(8.dp.toPx()),
                    style = Stroke(2.dp.toPx()),
                )
                listOf(
                        Offset(left, top),
                        Offset(right, top),
                        Offset(right, bottom),
                        Offset(left, bottom),
                    )
                    .forEach {
                        drawCircle(background, 7.dp.toPx(), it)
                        drawCircle(accent, 5.dp.toPx(), it)
                    }
            }
            if (state.overlay)
                drawTranslatedLines(
                    p,
                    state.recognition?.lines.orEmpty(),
                    state.translatedLines,
                    background,
                    overlayText,
                )
        }
        if (state.reading)
            LinearProgressIndicator(Modifier.fillMaxWidth().align(Alignment.TopCenter))
    }
}

/** Reused by the static workspace and live camera; input boxes always use image coordinates. */
fun androidx.compose.ui.graphics.drawscope.DrawScope.drawTranslatedLines(
    placement: ImagePlacement,
    lines: List<TextLine>,
    translations: Map<String, String>,
    background: Color,
    foreground: Color,
) {
    val native = drawContext.canvas.nativeCanvas
    for (line in lines) {
        val translated = translations[line.text] ?: continue
        val r = line.bounds
        val left = placement.left + r.left * placement.width
        val top = placement.top + r.top * placement.height
        val width =
            max(r.width * placement.width, 64.dp.toPx())
                .coerceAtMost(placement.width - r.left * placement.width)
        if (width < 1) continue
        val paint =
            TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = foreground.toArgb()
                textSize = (r.height * placement.height * .74f).coerceIn(12.dp.toPx(), 28.dp.toPx())
            }
        val padding = 3.dp.toPx()
        val layout =
            StaticLayout.Builder.obtain(
                    translated,
                    0,
                    translated.length,
                    paint,
                    max(1, (width - 2 * padding).toInt()),
                )
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setIncludePad(false)
                .setMaxLines(5)
                .build()
        native.save()
        native.drawRoundRect(
            left - padding,
            top - padding,
            left + width + padding,
            top + max(r.height * placement.height, layout.height.toFloat()) + padding,
            5.dp.toPx(),
            5.dp.toPx(),
            Paint(Paint.ANTI_ALIAS_FLAG).apply { color = background.copy(alpha = .97f).toArgb() },
        )
        native.translate(left + padding, top)
        layout.draw(native)
        native.restore()
    }
}
