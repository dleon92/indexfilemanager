package app.index.files.data

import android.content.Context
import android.graphics.Bitmap
import android.media.ThumbnailUtils
import android.net.Uri
import android.os.Build
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.util.Size
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import app.index.files.thumbs.Decode
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.CancellationException

/**
 * Pasta lida direto pelo caminho (java.io.File), para o que o seletor do sistema não deixa
 * escolher (a pasta Download inteira). Só funciona com "Acesso a todos os arquivos".
 * Ids são caminhos absolutos, como no SAF local, então cofre e índice tratam igual.
 */
class FsSource(ctx: Context) : FileSource {
    private val app = ctx.applicationContext
    private val cr = app.contentResolver

    override fun listChildren(folderId: String): List<FileItem> {
        val files = File(folderId).listFiles() ?: throw FileNotFoundException("pasta indisponível")
        val out = ArrayList<FileItem>(files.size)
        for (f in files) out.add(toItem(f))
        return out
    }

    /** Para outros apps: Uri do FileProvider (file:// é bloqueado entre apps). */
    override fun uriFor(item: FileItem): Uri = FileProvider.getUriForFile(app, "${app.packageName}.files", File(item.id))

    override val cacheTag: String = Roots.FILE

    override fun openFd(item: FileItem): ParcelFileDescriptor? =
        ParcelFileDescriptor.open(File(item.id), ParcelFileDescriptor.MODE_READ_ONLY)

    override fun loadThumbnail(item: FileItem, maxSide: Int, signal: CancellationSignal?): Bitmap? {
        val f = File(item.id)
        return when (item.kind) {
            // Uri file:// só dentro do app: o ContentResolver lê direto do caminho.
            FileItem.KIND_IMAGE -> Decode.image(cr, Uri.fromFile(f), maxSide)
            FileItem.KIND_VIDEO -> if (Build.VERSION.SDK_INT >= 29) {
                ThumbnailUtils.createVideoThumbnail(f, Size(maxSide, maxSide), signal)
            } else {
                Decode.video(cr, Uri.fromFile(f), maxSide, signal)
            }
            else -> null
        }
    }

    override fun folderMtime(folderId: String): Long = File(folderId).lastModified().takeIf { it > 0 } ?: -1L

    // ---- Edição ----

    override fun createFolder(parent: String, name: String): FileItem {
        val f = File(parent, name)
        if (f.exists() || !f.mkdir()) throw IOException("não foi possível criar a pasta")
        return toItem(f)
    }

    override fun rename(parent: String, item: FileItem, name: String): FileItem {
        val from = File(item.id)
        val to = File(from.parentFile, name)
        if (to.exists() || !from.renameTo(to)) throw IOException("não foi possível renomear")
        return toItem(to)
    }

    override fun delete(parent: String, item: FileItem) {
        val f = File(item.id)
        if (!f.deleteRecursively() && f.exists()) throw IOException("não foi possível apagar")
    }

    override fun moveWithin(item: FileItem, from: String, to: String, name: String): FileItem? {
        val dest = File(to, name)
        if (dest.exists()) return null
        // renameTo só move no mesmo volume; senão quem chama copia e apaga.
        return if (File(item.id).renameTo(dest)) toItem(dest) else null
    }

    override fun openRead(item: FileItem): InputStream = FileInputStream(File(item.id))

    override fun write(
        parent: String, name: String, mime: String, size: Long, input: InputStream,
        cancelled: () -> Boolean, onBytes: (Long) -> Unit,
    ): FileItem {
        val f = File(parent, name)
        if (f.exists()) throw IOException("já existe")
        var ok = false
        try {
            f.outputStream().use { out ->
                val buf = ByteArray(256 * 1024)
                var done = 0L
                while (true) {
                    if (cancelled()) throw CancellationException()
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    done += n
                    onBytes(done)
                }
            }
            ok = true
            return toItem(f)
        } finally {
            if (!ok) f.delete()
        }
    }

    companion object {
        /** Mesmo tipo que o SAF usa para pastas. */
        private const val DIR_MIME = "vnd.android.document/directory"

        fun toItem(f: File): FileItem {
            val dir = f.isDirectory
            val mime = if (dir) DIR_MIME else mimeOf(f.name)
            return FileItem(
                id = f.absolutePath,
                name = f.name,
                mime = mime,
                size = if (dir) 0 else f.length(),
                modified = f.lastModified(),
                isDir = dir,
            )
        }

        private fun mimeOf(name: String): String {
            val ext = name.substringAfterLast('.', "").lowercase()
            if (ext.isEmpty()) return "application/octet-stream"
            return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
        }
    }
}
