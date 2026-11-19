package app.index.files.ui.viewer

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.RectF
import android.media.ExifInterface
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.LruCache
import app.index.files.Bg
import app.index.files.data.FileItem
import app.index.files.data.FileSource
import app.index.files.thumbs.Decode
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Carga do visualizador, em 2 níveis:
 * 1. Imagem base: lado maior ≈ tela (Drive: um pouco maior, pela miniatura grande do próprio
 *    Drive, sem baixar o original). RGB_565 (metade da memória). Só 3 na memória: a foto
 *    atual e as vizinhas.
 * 2. Recorte nítido: com zoom além do que a base mostra, só a área visível é decodificada do
 *    original na resolução necessária (BitmapRegionDecoder; arquivos locais).
 */
class ViewerLoader(private val screenLong: Int) {

    /** Base decodificada. [w]/[h] = tamanho original na orientação de exibição. */
    class Full(
        val bmp: Bitmap,
        val w: Int,
        val h: Int,
        val rawW: Int,
        val rawH: Int,
        val orientation: Int,
        val region: Boolean,
    )

    private val cache = LruCache<String, Full>(3)
    private val pool = Executors.newFixedThreadPool(2) { r -> Thread(r, "viewer").apply { isDaemon = true } }
    private val tilePool = Executors.newSingleThreadExecutor { r -> Thread(r, "viewer-tile").apply { isDaemon = true } }
    private val pending = HashSet<String>()
    private val tileGen = AtomicInteger()
    @Volatile private var closed = false

    // Decodificador de regiões da foto atual (aberto sob demanda, um por vez).
    private var regionId: String? = null
    private var regionFd: ParcelFileDescriptor? = null
    private var region: BitmapRegionDecoder? = null

    fun cached(item: FileItem): Full? = cache.get(item.id)

    /** Carrega a base (se não estiver na memória nem a caminho) e entrega na main thread. */
    fun load(item: FileItem, src: FileSource, done: (Full?) -> Unit) {
        cache.get(item.id)?.let { done(it); return }
        if (!pending.add(item.id)) return
        pool.execute {
            val r = runCatching { decode(item, src) }.getOrNull()
            Bg.main.post {
                pending.remove(item.id)
                if (closed) return@post
                if (r != null) cache.put(item.id, r)
                done(r)
            }
        }
    }

    private fun decode(item: FileItem, src: FileSource): Full? {
        // Tamanho e rotação do original (só locais; Drive usa a própria base como referência).
        var rawW = 0
        var rawH = 0
        var orientation = ExifInterface.ORIENTATION_NORMAL
        var regionOk = false
        runCatching {
            src.openFd(item)?.use { pfd ->
                val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFileDescriptor(pfd.fileDescriptor, null, o)
                rawW = o.outWidth
                rawH = o.outHeight
                val mime = o.outMimeType ?: ""
                regionOk = rawW > 0 && (mime == "image/jpeg" || mime == "image/png" || mime == "image/webp" ||
                    (Build.VERSION.SDK_INT >= 28 && (mime == "image/heif" || mime == "image/heic")))
            }
            if (rawW > 0) {
                src.openFd(item)?.use { pfd ->
                    orientation = ExifInterface(pfd.fileDescriptor)
                        .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
                }
            }
        }
        // Espelhamentos são raros: sem recorte nítido neles.
        if (orientation !in ROTATIONS) regionOk = false
        val cap = if (rawW > 0) screenLong else (screenLong * 3 / 2).coerceAtMost(3072)
        val b = src.loadThumbnail(item, cap, null) ?: return null
        val base = Decode.toDisplay(Decode.scaleDown(b, cap))
        val swap = orientation == ExifInterface.ORIENTATION_ROTATE_90 || orientation == ExifInterface.ORIENTATION_ROTATE_270
        val w = if (rawW > 0) (if (swap) rawH else rawW) else base.width
        val h = if (rawW > 0) (if (swap) rawW else rawH) else base.height
        return Full(base, w, h, rawW, rawH, orientation, regionOk)
    }

    /**
     * Pede o recorte nítido da área [visible] (lógica) para [k] pixels de tela por pixel
     * lógico. Entrega null quando a base já basta (o chamador tira o recorte).
     */
    fun requestTile(item: FileItem, src: FileSource, full: Full, visible: RectF, k: Float, done: (Bitmap?, RectF?) -> Unit) {
        val gen = tileGen.incrementAndGet()
        val baseRes = full.bmp.width.toFloat() / full.w
        if (!full.region || k <= baseRes * 1.15f) {
            done(null, null)
            return
        }
        // Margem de 15% em volta: arrastar um pouco não mostra a borda borrada.
        val mx = visible.width() * 0.15f
        val my = visible.height() * 0.15f
        val area = RectF(
            max(0f, visible.left - mx), max(0f, visible.top - my),
            (visible.right + mx).coerceAtMost(full.w.toFloat()), (visible.bottom + my).coerceAtMost(full.h.toFloat()),
        )
        var sample = 1
        while (sample * 2 <= 1f / k) sample *= 2
        tilePool.execute {
            if (gen != tileGen.get() || closed) return@execute
            val bmp = runCatching { decodeRegion(item, src, full, area, sample) }.getOrNull()
            Bg.main.post {
                if (gen == tileGen.get() && !closed) done(bmp, area) else bmp?.recycle()
            }
        }
    }

    /** Cancela recortes pendentes (troca de foto). */
    fun cancelTiles() {
        tileGen.incrementAndGet()
    }

    private fun decodeRegion(item: FileItem, src: FileSource, full: Full, d: RectF, sample: Int): Bitmap? {
        val dec = decoderFor(item, src) ?: return null
        val rw = full.rawW.toFloat()
        val rh = full.rawH.toFloat()
        // Área de exibição → área do arquivo (antes da rotação EXIF).
        val raw = when (full.orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> RectF(d.top, rh - d.right, d.bottom, rh - d.left)
            ExifInterface.ORIENTATION_ROTATE_180 -> RectF(rw - d.right, rh - d.bottom, rw - d.left, rh - d.top)
            ExifInterface.ORIENTATION_ROTATE_270 -> RectF(rw - d.bottom, d.left, rw - d.top, d.right)
            else -> RectF(d)
        }
        val r = Rect(
            raw.left.roundToInt().coerceIn(0, full.rawW), raw.top.roundToInt().coerceIn(0, full.rawH),
            raw.right.roundToInt().coerceIn(0, full.rawW), raw.bottom.roundToInt().coerceIn(0, full.rawH),
        )
        if (r.width() <= 0 || r.height() <= 0) return null
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.RGB_565
        }
        val b = synchronized(dec) { dec.decodeRegion(r, opts) } ?: return null
        val deg = when (full.orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> return b
        }
        val m = Matrix().apply { postRotate(deg) }
        val out = Bitmap.createBitmap(b, 0, 0, b.width, b.height, m, true)
        if (out !== b) b.recycle()
        return out
    }

    /** Só no thread de recortes. */
    private fun decoderFor(item: FileItem, src: FileSource): BitmapRegionDecoder? {
        if (regionId == item.id) return region
        closeRegion()
        val pfd = src.openFd(item) ?: return null
        val dec = runCatching {
            @Suppress("DEPRECATION")
            if (Build.VERSION.SDK_INT >= 31) BitmapRegionDecoder.newInstance(pfd) else BitmapRegionDecoder.newInstance(pfd.fileDescriptor, false)
        }.getOrNull()
        if (dec == null) {
            runCatching { pfd.close() }
            return null
        }
        regionId = item.id
        regionFd = pfd
        region = dec
        return dec
    }

    private fun closeRegion() {
        region?.recycle()
        runCatching { regionFd?.close() }
        region = null
        regionFd = null
        regionId = null
    }

    /** Visualizador fechado: solta tudo. */
    fun close() {
        closed = true
        tileGen.incrementAndGet()
        cache.evictAll()
        pool.shutdownNow()
        tilePool.execute { closeRegion() }
        tilePool.shutdown()
    }

    companion object {
        private val ROTATIONS = setOf(
            ExifInterface.ORIENTATION_NORMAL, ExifInterface.ORIENTATION_UNDEFINED,
            ExifInterface.ORIENTATION_ROTATE_90, ExifInterface.ORIENTATION_ROTATE_180, ExifInterface.ORIENTATION_ROTATE_270,
        )
    }
}
