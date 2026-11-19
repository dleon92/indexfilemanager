package app.index.files.data

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.InputStream

/**
 * Um único arquivo recebido de outro app ("Abrir com" → Index). Só leitura: o Index não guarda,
 * não lista e não edita nada dele; o acesso dura enquanto o outro app mantiver a permissão.
 */
class UriSource(ctx: Context, private val uri: Uri) : FileSource {
    private val cr = ctx.applicationContext.contentResolver

    override val cacheTag = "uri"

    /** O item com nome, tamanho e tipo informados pelo app de origem. */
    fun item(typeHint: String?): FileItem {
        var name: String? = null
        var size = -1L
        if (uri.scheme == "content") runCatching {
            cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    name = c.getString(0)
                    if (!c.isNull(1)) size = c.getLong(1)
                }
            }
        }
        val n = name?.takeIf { it.isNotBlank() } ?: uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() } ?: "documento"
        val mime = typeHint?.takeIf { it.isNotBlank() && it != "*/*" } ?: runCatching { cr.getType(uri) }.getOrNull() ?: "application/octet-stream"
        return FileItem(uri.toString(), n, mime, size, 0L, false)
    }

    override fun openRead(item: FileItem): InputStream =
        cr.openInputStream(uri) ?: throw java.io.IOException("sem acesso ao arquivo")

    override fun openFd(item: FileItem): ParcelFileDescriptor? =
        runCatching { cr.openFileDescriptor(uri, "r") }.getOrNull()

    override fun uriFor(item: FileItem): Uri = uri

    override fun listChildren(folderId: String): List<FileItem> = emptyList()
    override fun loadThumbnail(item: FileItem, maxSide: Int, signal: CancellationSignal?): Bitmap? = null

    private fun readOnly(): Nothing = throw NotEditable("arquivo de outro app: só leitura")
    override fun createFolder(parent: String, name: String) = readOnly()
    override fun rename(parent: String, item: FileItem, name: String) = readOnly()
    override fun delete(parent: String, item: FileItem) = readOnly()
    override fun moveWithin(item: FileItem, from: String, to: String, name: String): FileItem? = null
    override fun write(
        parent: String, name: String, mime: String, size: Long, input: InputStream,
        cancelled: () -> Boolean, onBytes: (Long) -> Unit,
    ) = readOnly()
}
