package app.index.files.thumbs

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import app.index.files.security.Crypto
import app.index.files.security.Vault
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Miniaturas das pastas protegidas: WebP cifrado (AES-GCM, chave de dados do cofre) em
 * `files/vault/<id>/t/`, separado do cache comum. Apagadas junto com a proteção.
 */
class VaultThumbs(ctx: Context) {
    private val app = ctx.applicationContext

    private fun file(entryId: String, key: String) = File(File(File(Vault.dir(app), entryId), "t"), key)

    fun exists(entryId: String, key: String) = file(entryId, key).exists()

    /** null = não existe; [DiskStore.EMPTY] = sem miniatura possível. */
    fun read(entryId: String, key: String): Bitmap? {
        val f = file(entryId, key)
        if (!f.exists()) return null
        if (f.length() == 0L) return DiskStore.EMPTY
        val plain = Crypto.decrypt(Vault.dataKey(app), f.readBytes())
        return BitmapFactory.decodeByteArray(plain, 0, plain.size, OPTS)
    }

    fun write(entryId: String, key: String, bmp: Bitmap) {
        val bos = ByteArrayOutputStream()
        bmp.compress(FORMAT, 75, bos)
        val f = file(entryId, key)
        f.parentFile?.mkdirs()
        val tmp = File(f.parentFile, "$key.tmp")
        tmp.writeBytes(Crypto.encrypt(Vault.dataKey(app), bos.toByteArray()))
        if (!tmp.renameTo(f)) tmp.delete()
    }

    fun writeEmpty(entryId: String, key: String) {
        val f = file(entryId, key)
        f.parentFile?.mkdirs()
        runCatching { f.createNewFile() }
    }

    fun delete(entryId: String, keys: Collection<String>) {
        for (k in keys) file(entryId, k).delete()
    }

    private companion object {
        val OPTS = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.RGB_565 }

        @Suppress("DEPRECATION")
        val FORMAT = if (Build.VERSION.SDK_INT >= 30) Bitmap.CompressFormat.WEBP_LOSSY else Bitmap.CompressFormat.WEBP
    }
}
