package app.index.files.data

import android.content.ContentResolver
import android.graphics.Bitmap
import android.net.Uri
import android.os.CancellationSignal
import app.index.files.thumbs.Decode
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.CancellationException

/**
 * Pasta local via SAF. Uma única query por pasta com projeção mínima
 * (sem DocumentFile, que faz uma query por arquivo).
 */
class LocalSource(private val cr: ContentResolver, private val tree: Uri) : FileSource {

    override fun listChildren(folderId: String): List<FileItem> {
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, folderId)
        // Atenção: no ExternalStorageProvider o custo é quase todo desta query
        // (~0,9 ms por arquivo, medido em 5.000 itens). Ver README.
        val c = cr.query(uri, PROJECTION, null, null, null)
            ?: throw FileNotFoundException("pasta indisponível")
        c.use {
            val out = ArrayList<FileItem>(c.count)
            while (c.moveToNext()) {
                val name = c.getString(1) ?: continue
                val mime = c.getString(2) ?: "application/octet-stream"
                out.add(
                    FileItem(
                        id = c.getString(0),
                        name = name,
                        mime = mime,
                        size = if (c.isNull(3)) 0 else c.getLong(3),
                        modified = if (c.isNull(4)) 0 else c.getLong(4),
                        isDir = mime == Document.MIME_TYPE_DIR,
                    ),
                )
            }
            return out
        }
    }

    override fun uriFor(item: FileItem): Uri = DocumentsContract.buildDocumentUriUsingTree(tree, item.id)

    override val cacheTag: String = tree.authority ?: "local"

    override fun openFd(item: FileItem): android.os.ParcelFileDescriptor? = cr.openFileDescriptor(uriFor(item), "r")

    override fun loadThumbnail(item: FileItem, maxSide: Int, signal: CancellationSignal?): Bitmap? =
        when (item.kind) {
            FileItem.KIND_IMAGE -> Decode.image(cr, uriFor(item), maxSide)
            FileItem.KIND_VIDEO -> Decode.video(cr, uriFor(item), maxSide, signal)
            else -> null
        }

    /** Uma consulta de uma coluna (bem mais barata que listar a pasta). */
    override fun folderMtime(folderId: String): Long = try {
        cr.query(doc(folderId), arrayOf(Document.COLUMN_LAST_MODIFIED), null, null, null)
            ?.use { if (it.moveToFirst() && !it.isNull(0)) it.getLong(0) else -1L } ?: -1L
    } catch (e: Exception) {
        -1L
    }

    // ---- Edição ----

    private fun doc(id: String): Uri = DocumentsContract.buildDocumentUriUsingTree(tree, id)

    /** Metadados de um documento (mesma projeção da listagem). */
    private fun stat(uri: Uri): FileItem {
        val c = cr.query(uri, PROJECTION, null, null, null) ?: throw FileNotFoundException("item indisponível")
        c.use {
            if (!c.moveToFirst()) throw FileNotFoundException("item indisponível")
            val mime = c.getString(2) ?: "application/octet-stream"
            return FileItem(
                id = c.getString(0),
                name = c.getString(1) ?: "",
                mime = mime,
                size = if (c.isNull(3)) 0 else c.getLong(3),
                modified = if (c.isNull(4)) 0 else c.getLong(4),
                isDir = mime == Document.MIME_TYPE_DIR,
            )
        }
    }

    override fun createFolder(parent: String, name: String): FileItem {
        val uri = DocumentsContract.createDocument(cr, doc(parent), Document.MIME_TYPE_DIR, name)
            ?: throw IOException("não foi possível criar a pasta")
        return stat(uri)
    }

    override fun rename(parent: String, item: FileItem, name: String): FileItem {
        val old = doc(item.id)
        // Null = o provedor manteve a mesma Uri.
        val uri = DocumentsContract.renameDocument(cr, old, name) ?: old
        return stat(uri)
    }

    override fun delete(parent: String, item: FileItem) {
        if (!DocumentsContract.deleteDocument(cr, doc(item.id))) throw IOException("não foi possível apagar")
    }

    override fun moveWithin(item: FileItem, from: String, to: String, name: String): FileItem? = try {
        val moved = DocumentsContract.moveDocument(cr, doc(item.id), doc(from), doc(to))
        if (moved == null) null
        else if (name != item.name) stat(DocumentsContract.renameDocument(cr, moved, name) ?: moved)
        else stat(moved)
    } catch (e: Exception) {
        // Provedor sem suporte, nome já existente no destino etc.: quem chama copia e apaga.
        null
    }

    override fun openRead(item: FileItem): InputStream =
        cr.openInputStream(doc(item.id)) ?: throw FileNotFoundException("não foi possível ler")

    override fun write(
        parent: String, name: String, mime: String, size: Long, input: InputStream,
        cancelled: () -> Boolean, onBytes: (Long) -> Unit,
    ): FileItem {
        // octet-stream: o provedor mantém o nome exatamente como pedido (sem trocar a extensão).
        val uri = DocumentsContract.createDocument(cr, doc(parent), "application/octet-stream", name)
            ?: throw IOException("não foi possível criar o arquivo")
        var ok = false
        try {
            val out = cr.openOutputStream(uri, "w") ?: throw IOException("não foi possível gravar")
            out.use {
                val buf = ByteArray(256 * 1024)
                var done = 0L
                while (true) {
                    if (cancelled()) throw CancellationException()
                    val n = input.read(buf)
                    if (n < 0) break
                    it.write(buf, 0, n)
                    done += n
                    onBytes(done)
                }
            }
            ok = true
            return stat(uri)
        } finally {
            if (!ok) runCatching { DocumentsContract.deleteDocument(cr, uri) }
        }
    }

    private companion object {
        val PROJECTION = arrayOf(
            Document.COLUMN_DOCUMENT_ID,
            Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_MIME_TYPE,
            Document.COLUMN_SIZE,
            Document.COLUMN_LAST_MODIFIED,
        )
    }
}
