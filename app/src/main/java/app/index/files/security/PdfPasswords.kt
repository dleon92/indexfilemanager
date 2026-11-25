package app.index.files.security

import android.content.Context
import android.util.Base64
import app.index.files.data.FileItem
import app.index.files.data.FileSource
import java.security.MessageDigest

/**
 * Senhas de PDF lembradas. Cada senha vai cifrada com a chave-mestra do Android Keystore
 * (AES-256-GCM, a chave nunca sai do aparelho); o nome da entrada é um hash do arquivo, então
 * nem o nome do PDF fica salvo. Fora da main thread.
 */
object PdfPasswords {
    private const val FILE = "pdf_passwords"

    private fun sp(ctx: Context) = ctx.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** Mesmo arquivo (origem + id): vale mesmo que o PDF seja editado. */
    private fun keyOf(src: FileSource, item: FileItem): String {
        val d = MessageDigest.getInstance("SHA-256").digest("${src.cacheTag}|${item.id}".toByteArray())
        return d.joinToString("") { "%02x".format(it) }
    }

    fun get(ctx: Context, src: FileSource, item: FileItem): String? {
        val b64 = sp(ctx).getString(keyOf(src, item), null) ?: return null
        return runCatching {
            String(Crypto.decrypt(Crypto.masterKey(), Base64.decode(b64, Base64.NO_WRAP)), Charsets.UTF_8)
        }.getOrNull()
    }

    fun put(ctx: Context, src: FileSource, item: FileItem, password: String) {
        val enc = Crypto.encrypt(Crypto.masterKey(), password.toByteArray(Charsets.UTF_8))
        sp(ctx).edit().putString(keyOf(src, item), Base64.encodeToString(enc, Base64.NO_WRAP)).apply()
    }

    fun remove(ctx: Context, src: FileSource, item: FileItem) {
        sp(ctx).edit().remove(keyOf(src, item)).apply()
    }

    fun count(ctx: Context): Int = sp(ctx).all.size

    fun clear(ctx: Context) {
        sp(ctx).edit().clear().commit()
    }
}
