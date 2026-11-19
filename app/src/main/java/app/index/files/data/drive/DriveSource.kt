package app.index.files.data.drive

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.CancellationSignal
import androidx.core.content.FileProvider
import app.index.files.data.FileItem
import app.index.files.data.FileSource
import app.index.files.data.NotEditable
import app.index.files.data.Prefs
import java.io.File
import java.io.FilterInputStream
import java.io.InputStream

/**
 * Pasta do Google Drive. Mesmo contrato da pasta local: a UI, o cache de
 * listagem, o índice e as miniaturas não sabem a diferença.
 */
class DriveSource(ctx: Context) : FileSource {
    private val app = ctx.applicationContext

    override fun listChildren(folderId: String): List<FileItem> = DriveApi.children(app, folderId)

    override fun uriFor(item: FileItem): Uri = Uri.parse("https://drive.google.com/open?id=${item.id}")

    override val cacheTag: String = "drive"

    /** Miniatura pronta do próprio Drive (=s256), sem baixar o arquivo. */
    override fun loadThumbnail(item: FileItem, maxSide: Int, signal: CancellationSignal?): Bitmap? {
        item.thumbUrl?.let { link ->
            // O link da listagem pode ter expirado: em caso de erro pede um novo.
            runCatching { return decode(DriveApi.bytes(app, sized(link, maxSide)), maxSide) }
        }
        val fresh = DriveApi.thumbnailLink(app, item.id) ?: return null
        item.thumbUrl = fresh
        return decode(DriveApi.bytes(app, sized(fresh, maxSide)), maxSide)
    }

    private fun sized(link: String, side: Int) =
        if (SIZE_SUFFIX.containsMatchIn(link)) link.replace(SIZE_SUFFIX, "=s$side") else link

    private fun decode(bytes: ByteArray, maxSide: Int): Bitmap? {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
        var sample = 1
        while (maxOf(o.outWidth, o.outHeight) / (sample * 2) >= maxSide) sample *= 2
        o.inJustDecodeBounds = false
        o.inSampleSize = sample
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
    }

    /** Modo efetivo: Documentos Google só abrem no Drive; tamanho desconhecido não dá para transmitir. */
    private fun modeFor(item: FileItem): Int {
        if (isGoogleDoc(item)) return Prefs.OPEN_DRIVE
        val m = Prefs.get(app).driveOpenMode
        return if (m == Prefs.OPEN_STREAM && item.size <= 0) Prefs.OPEN_DOWNLOAD else m
    }

    override fun needsDownload(item: FileItem) = modeFor(item) == Prefs.OPEN_DOWNLOAD

    override fun openIntent(item: FileItem): Intent {
        when (modeFor(item)) {
            Prefs.OPEN_DRIVE -> return Intent(Intent.ACTION_VIEW, uriFor(item))
            Prefs.OPEN_STREAM -> return Intent(Intent.ACTION_VIEW)
                .also { StreamService.start(app) } // mantém a rede viva enquanto o player lê
                .setDataAndType(DriveStreamProvider.uriFor(app, item), item.mime)
                .putExtra("title", item.name) // VLC e outros players mostram o nome
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return Intent(Intent.ACTION_VIEW)
            .setDataAndType(downloaded(item), item.mime)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    /**
     * Compartilhar sem baixar antes: o app escolhido recebe o mesmo link de streaming dos
     * players e lê o arquivo direto do Drive (nome e tamanho já vêm no link).
     */
    override fun shareUri(item: FileItem): Uri {
        StreamService.start(app) // mantém a rede viva enquanto o outro app lê
        return DriveStreamProvider.uriFor(app, item)
    }

    /** Cópia local em cache/drive_open (a mesma usada no modo "baixar e abrir"). */
    private fun downloaded(item: FileItem): Uri {
        val dir = File(app.cacheDir, "drive_open")
        trimOpenCache(dir)
        val target = File(File(dir, safe(item.id)), safe(item.name))
        val fresh = target.exists() && target.length() == item.size && target.lastModified() >= item.modified
        if (!fresh) {
            target.parentFile?.mkdirs()
            DriveApi.download(app, item.id, target)
        }
        return FileProvider.getUriForFile(app, app.packageName + ".files", target)
    }

    // ---- Edição ----

    /**
     * A listagem mostra o ALVO de um atalho dentro da pasta do atalho. Editar o alvo
     * (renomear, apagar, mover) mexeria num arquivo de outra pasta: só segue se [parent]
     * é mesmo pai do item.
     */
    private fun checkParent(item: FileItem, parent: String) {
        if (parent !in DriveApi.parents(app, item.id)) throw NotEditable("atalho do Drive")
    }

    override fun createFolder(parent: String, name: String) = DriveApi.createFolder(app, parent, name)

    override fun rename(parent: String, item: FileItem, name: String): FileItem {
        checkParent(item, parent)
        return DriveApi.rename(app, item.id, name)
    }

    override fun delete(parent: String, item: FileItem) {
        checkParent(item, parent)
        DriveApi.trash(app, item.id)
    }

    override fun moveWithin(item: FileItem, from: String, to: String, name: String): FileItem {
        checkParent(item, from)
        return DriveApi.move(app, item.id, from, to, name)
    }

    override fun copyWithin(item: FileItem, to: String, name: String): FileItem? =
        if (item.isDir) null else DriveApi.copy(app, item.id, to, name)

    override fun openRead(item: FileItem): InputStream {
        if (isGoogleDoc(item)) throw NotEditable("documento Google (não há arquivo para copiar)")
        // Arquivo vazio: o Range daria 416.
        if (item.size == 0L) return java.io.ByteArrayInputStream(ByteArray(0))
        val c = DriveApi.openRange(app, item.id, 0)
        return object : FilterInputStream(c.inputStream) {
            override fun close() {
                try {
                    super.close()
                } finally {
                    c.disconnect()
                }
            }
        }
    }

    override fun write(
        parent: String, name: String, mime: String, size: Long, input: InputStream,
        cancelled: () -> Boolean, onBytes: (Long) -> Unit,
    ): FileItem = DriveApi.upload(app, parent, name, mime, size, input, cancelled, onBytes)

    /** Arquivos baixados para abrir duram 24 h. */
    private fun trimOpenCache(dir: File) {
        val limit = System.currentTimeMillis() - 24 * 3600_000L
        dir.listFiles()?.forEach { sub ->
            val files = sub.listFiles() ?: return@forEach
            files.filter { it.lastModified() < limit }.forEach { it.delete() }
            if (sub.listFiles()?.isEmpty() == true) sub.delete()
        }
    }

    companion object {
        private val SIZE_SUFFIX = Regex("=s\\d+$")

        /** Documentos/Planilhas/Apresentações Google não têm arquivo para baixar. */
        fun isGoogleDoc(item: FileItem) = item.mime.startsWith("application/vnd.google-apps.")

        private fun safe(s: String) = s.replace(Regex("[\\\\/:*?\"<>|\\u0000]"), "_").take(120).ifEmpty { "arquivo" }
    }
}
