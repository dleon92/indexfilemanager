package app.index.files.thumbs

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.media.ExifInterface
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.CancellationSignal
import android.util.Size
import kotlin.math.max
import kotlin.math.roundToInt

/** Decodificação de miniaturas só com APIs da plataforma. */
object Decode {

    /**
     * Imagem reduzida já na decodificação (sem carregar a foto inteira na memória).
     * API 28+: ImageDecoder (aplica a rotação EXIF e suporta HEIC);
     * API 26–27: BitmapFactory com inSampleSize + rotação EXIF manual.
     */
    fun image(cr: ContentResolver, uri: Uri, maxSide: Int): Bitmap? {
        if (Build.VERSION.SDK_INT >= 28) {
            val src = ImageDecoder.createSource(cr, uri)
            return ImageDecoder.decodeBitmap(src) { d, info, _ ->
                val w = info.size.width
                val h = info.size.height
                val longSide = max(w, h)
                if (longSide > maxSide) {
                    val s = maxSide.toFloat() / longSide
                    d.setTargetSize(max(1, (w * s).roundToInt()), max(1, (h * s).roundToInt()))
                }
                d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                d.memorySizePolicy = ImageDecoder.MEMORY_POLICY_LOW_RAM
            }
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) } ?: return null
        val longSide = max(bounds.outWidth, bounds.outHeight)
        if (longSide <= 0) return null
        var sample = 1
        while (longSide / (sample * 2) >= maxSide) sample *= 2
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.RGB_565
        }
        val bmp = cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) } ?: return null
        val orientation = runCatching {
            cr.openInputStream(uri)?.use {
                ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            }
        }.getOrNull() ?: ExifInterface.ORIENTATION_NORMAL
        return rotate(bmp, orientation)
    }

    private fun rotate(b: Bitmap, orientation: Int): Bitmap {
        val m = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.postScale(1f, -1f)
            else -> return b
        }
        val r = Bitmap.createBitmap(b, 0, 0, b.width, b.height, m, true)
        if (r !== b) b.recycle()
        return r
    }

    /** Quadro de vídeo: loadThumbnail (API 29+) e, se falhar, MediaMetadataRetriever. */
    fun video(cr: ContentResolver, uri: Uri, maxSide: Int, signal: CancellationSignal?): Bitmap? {
        if (Build.VERSION.SDK_INT >= 29) {
            try {
                return cr.loadThumbnail(uri, Size(maxSide, maxSide), signal)
            } catch (_: Exception) {
                // Provedor sem suporte a thumbs de vídeo: cai no retriever.
            }
        }
        val mmr = MediaMetadataRetriever()
        try {
            cr.openFileDescriptor(uri, "r")?.use { fd ->
                mmr.setDataSource(fd.fileDescriptor)
                val at = 1_000_000L
                val opt = MediaMetadataRetriever.OPTION_CLOSEST_SYNC
                val frame = if (Build.VERSION.SDK_INT >= 27) {
                    mmr.getScaledFrameAtTime(at, opt, maxSide, maxSide)
                } else {
                    mmr.getFrameAtTime(at, opt)
                }
                return frame ?: mmr.frameAtTime
            }
            return null
        } finally {
            runCatching { mmr.release() }
        }
    }

    /** Garante lado maior ≤ [maxSide]. */
    fun scaleDown(b: Bitmap, maxSide: Int): Bitmap {
        val longSide = max(b.width, b.height)
        if (longSide <= maxSide) return b
        val s = maxSide.toFloat() / longSide
        val r = Bitmap.createScaledBitmap(b, max(1, (b.width * s).roundToInt()), max(1, (b.height * s).roundToInt()), true)
        if (r !== b) b.recycle()
        return r
    }

    /** RGB_565 usa metade da memória; só quando a imagem não tem transparência. */
    fun toDisplay(b: Bitmap): Bitmap {
        if (b.config == Bitmap.Config.RGB_565 || b.hasAlpha()) return b
        val r = b.copy(Bitmap.Config.RGB_565, false) ?: return b
        b.recycle()
        return r
    }
}
