package app.glimpse.engines.mlkit

import android.graphics.Bitmap
import android.graphics.Rect
import app.glimpse.core.*
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.tasks.await

class MlKitOcr : OcrEngine {
    override val info =
        EngineInfo(
            "mlkit-ocr",
            "ML Kit",
            "Bundled English / Latin and Japanese recognition. Runs on this device.",
            true,
        )
    override val scripts = OcrScript.entries.toSet()
    private val latin by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }
    private val japanese by lazy {
        TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
    }

    override suspend fun recognize(image: Bitmap, script: OcrScript): Recognition {
        // The Japanese recognizer also handles Latin text in mixed-language images.
        val recognizer = if (script == OcrScript.LATIN) latin else japanese
        val text = recognizer.process(InputImage.fromBitmap(image, 0)).await()
        fun region(rect: Rect?): ImageRegion? = rect?.let {
            ImageRegion.between(
                it.left.toFloat() / image.width,
                it.top.toFloat() / image.height,
                it.right.toFloat() / image.width,
                it.bottom.toFloat() / image.height,
            )
        }
        val lines =
            text.textBlocks
                .flatMap { it.lines }
                .mapNotNull { line ->
                    val bounds = region(line.boundingBox) ?: return@mapNotNull null
                    TextLine(
                        line.text,
                        bounds,
                        line.recognizedLanguage,
                        line.elements.mapNotNull { word ->
                            region(word.boundingBox)?.let { TextSpan(word.text, it) }
                        },
                    )
                }
        return Recognition(lines, image.width, image.height)
    }

    override fun close() {
        latin.close()
        japanese.close()
    }
}
