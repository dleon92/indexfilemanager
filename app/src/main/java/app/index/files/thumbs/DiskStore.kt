package app.index.files.thumbs

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import java.io.File
import java.io.FileOutputStream

/**
 * Cache de thumbs em disco: um arquivo WebP por chave. Arquivo de 0 bytes =
 * "sem miniatura possível" (evita tentar de novo). LRU pela data de modificação,
 * atualizada nas leituras (no máximo 1x por hora por arquivo).
 */
class DiskStore(
    private val dirProvider: () -> File,
    private val limitBytes: () -> Long,
) {
    private val lock = Any()
    private var total = -1L

    fun dir(): File = dirProvider().also { if (!it.exists()) it.mkdirs() }

    fun file(key: String) = File(dir(), "$key.webp")

    /** Lê a thumb (RGB_565). Retorna null se não existe; EMPTY se marcada como impossível. */
    fun read(key: String): Bitmap? {
        val f = file(key)
        val len = f.length()
        if (len == 0L) return if (f.exists()) EMPTY else null
        val b = BitmapFactory.decodeFile(f.path, OPTS) ?: return null
        val now = System.currentTimeMillis()
        if (now - f.lastModified() > HOUR) f.setLastModified(now)
        return b
    }

    fun write(key: String, bmp: Bitmap) {
        val dir = dir()
        val tmp = File(dir, "$key.tmp")
        FileOutputStream(tmp).use { bmp.compress(FORMAT, QUALITY, it) }
        val dst = File(dir, "$key.webp")
        if (!tmp.renameTo(dst)) {
            tmp.delete()
            return
        }
        add(dst.length())
    }

    fun writeEmpty(key: String) {
        runCatching { file(key).createNewFile() }
    }

    /** Tamanho total (varre a pasta na primeira chamada; depois é contabilizado). */
    fun size(): Long = synchronized(lock) {
        if (total < 0) total = dir().listFiles()?.sumOf { it.length() } ?: 0L
        total
    }

    private fun add(n: Long) {
        synchronized(lock) { if (total >= 0) total += n }
        trimIfNeeded()
    }

    /** Remove os menos usados até ficar em 90% do limite. */
    fun trimIfNeeded() {
        val limit = limitBytes()
        if (limit <= 0 || size() <= limit) return
        synchronized(lock) {
            val files = dir().listFiles() ?: return
            files.sortBy { it.lastModified() }
            var t = files.sumOf { it.length() }
            val target = limit * 9 / 10
            for (f in files) {
                if (t <= target) break
                val l = f.length()
                if (f.delete()) t -= l
            }
            total = t
        }
    }

    fun delete(keys: Collection<String>) {
        synchronized(lock) {
            for (k in keys) {
                val f = file(k)
                val l = f.length()
                if (f.delete() && total >= 0) total -= l
            }
        }
    }

    fun clear(dir: File = dir()) {
        synchronized(lock) {
            dir.listFiles()?.forEach { it.delete() }
            total = -1
        }
    }

    companion object {
        private const val HOUR = 3_600_000L
        private const val QUALITY = 75

        /** Marcador "não há miniatura" (bitmap 1x1 compartilhado). */
        val EMPTY: Bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.RGB_565)

        private val OPTS = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.RGB_565 }

        @Suppress("DEPRECATION")
        private val FORMAT = if (Build.VERSION.SDK_INT >= 30) Bitmap.CompressFormat.WEBP_LOSSY else Bitmap.CompressFormat.WEBP
    }
}
