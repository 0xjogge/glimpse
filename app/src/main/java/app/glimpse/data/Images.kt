package app.glimpse.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Paint
import android.graphics.Typeface
import android.net.Uri
import androidx.core.content.FileProvider
import app.glimpse.core.ImageRegion
import java.io.File
import java.util.UUID
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext

data class CapturedImage(
    val bitmap: Bitmap,
    val origin: String,
    val id: String = UUID.randomUUID().toString(),
    val assistantOnly: Boolean = false,
)

/** One ephemeral image; never put screenshots in Binder extras or the photo library. */
object ImageSession {
    val image = MutableStateFlow<CapturedImage?>(null)

    fun offer(bitmap: Bitmap, origin: String, assistantOnly: Boolean = false) {
        image.value = CapturedImage(bitmap, origin, assistantOnly = assistantOnly)
    }

    fun clear() {
        image.value = null
    }

    /** Closing an older assistant must not discard a newer capture or an imported image. */
    fun clear(imageId: String) {
        val current = image.value ?: return
        if (current.id == imageId) image.compareAndSet(current, null)
    }
}

object Images {
    suspend fun decode(context: Context, uri: Uri): Bitmap =
        withContext(Dispatchers.IO) {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) {
                decoder,
                info,
                _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val largest = maxOf(info.size.width, info.size.height)
                if (largest > 3000)
                    decoder.setTargetSize(
                        (info.size.width * 3000f / largest).roundToInt(),
                        (info.size.height * 3000f / largest).roundToInt(),
                    )
            }
        }

    fun crop(bitmap: Bitmap, region: ImageRegion?): Bitmap {
        val r = region ?: ImageRegion.Full
        val left = (r.left * bitmap.width).toInt().coerceIn(0, bitmap.width - 1)
        val top = (r.top * bitmap.height).toInt().coerceIn(0, bitmap.height - 1)
        val right = (r.right * bitmap.width).roundToInt().coerceIn(left + 1, bitmap.width)
        val bottom = (r.bottom * bitmap.height).roundToInt().coerceIn(top + 1, bitmap.height)
        return Bitmap.createBitmap(bitmap, left, top, right - left, bottom - top)
    }

    suspend fun shareCrop(context: Context, bitmap: Bitmap, region: ImageRegion?): Uri =
        withContext(Dispatchers.IO) {
            val directory = File(context.cacheDir, "shared").apply { mkdirs() }
            // Keep active handoffs intact; reclaim abandoned files from previous days.
            directory
                .listFiles()
                ?.filter { System.currentTimeMillis() - it.lastModified() > 24 * 60 * 60 * 1000 }
                ?.forEach(File::delete)
            val file = File(directory, "glimpse-${UUID.randomUUID()}.jpg")
            file.outputStream().use {
                require(crop(bitmap, region).compress(Bitmap.CompressFormat.JPEG, 95, it))
            }
            FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        }

    fun sample(): Bitmap {
        val bitmap = Bitmap.createBitmap(1080, 1400, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.rgb(246, 243, 234))
        val paint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.rgb(36, 57, 43)
                typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            }
        paint.textSize = 28f
        canvas.drawText("A LITTLE WINDOW INTO YOUR WORLD", 72f, 130f, paint)
        paint.textSize = 80f
        canvas.drawText("こんにちは", 72f, 300f, paint)
        paint.textSize = 58f
        canvas.drawText("Hello, world.", 72f, 395f, paint)
        paint.textSize = 44f
        canvas.drawText("日本へようこそ", 72f, 600f, paint)
        paint.textSize = 35f
        canvas.drawText("Welcome to Japan", 72f, 670f, paint)
        paint.textSize = 42f
        canvas.drawText("コーヒー  450円", 72f, 880f, paint)
        canvas.drawText("お茶  300円", 72f, 960f, paint)
        paint.textSize = 30f
        canvas.drawText("Circle something. See what happens.", 72f, 1240f, paint)
        return bitmap
    }
}
