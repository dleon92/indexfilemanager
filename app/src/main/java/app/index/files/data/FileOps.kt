package app.index.files.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import app.index.files.security.Vault
import app.index.files.security.VaultEntry
import app.index.files.thumbs.ThumbnailManager
import java.io.IOException
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Uma pasta aberta no navegador: raiz, origem, id e caminho relativo (para o índice).
 * Dentro de uma pasta protegida, [vault] aponta para ela e tudo vai para o índice cifrado.
 */
class Loc(
    val root: Root, val src: FileSource, val folderId: String, val path: String,
    val vault: VaultEntry? = null,
) {
    fun childPath(name: String) = if (path.isEmpty()) name else "$path/$name"
}

/**
 * Edição de arquivos (item I), igual para pasta local (SAF) e Drive. Bloqueante: roda
 * numa thread própria. Depois de cada operação, a listagem em cache das pastas mexidas é
 * regravada, os descendentes de pastas apagadas/movidas saem do índice, pastas novas são
 * indexadas e as thumbs antigas são apagadas — listagem, busca e miniaturas ficam certas
 * na hora.
 */
class FileOps(ctx: Context, private val listener: Listener) {

    fun interface Listener {
        /** [index] = item atual (0-based) de [count]; [bytes]/[size] do arquivo atual (size <= 0: desconhecido). */
        fun onProgress(name: String, index: Int, count: Int, bytes: Long, size: Long)
    }

    class Result(val done: Int, val failed: Int, val firstError: Throwable?, val cancelled: Boolean)

    private val app = ctx.applicationContext
    private val cache = ListingCache(app)
    private val thumbs = ThumbnailManager.get(app)
    val cancelled = AtomicBoolean(false)

    private var done = 0
    private var failed = 0
    private var firstError: Throwable? = null

    private fun result() = Result(done, failed, firstError, cancelled.get())

    private fun fail(e: Throwable) {
        if (e is CancellationException) throw e
        Log.w(TAG, "falha na operação", e)
        failed++
        if (firstError == null) firstError = e
    }

    private fun checkCancel() {
        if (cancelled.get()) throw CancellationException()
    }

    // ---- Operações ----

    fun createFolder(at: Loc, name: String): Result = op {
        listener.onProgress(name, 0, 1, 0, 0)
        try {
            at.src.createFolder(at.folderId, uniqueName(existing(at), name))
            done++
        } catch (e: Exception) {
            fail(e)
        }
        relist(at)
    }

    fun rename(at: Loc, item: FileItem, name: String): Result = op {
        listener.onProgress(item.name, 0, 1, 0, 0)
        try {
            val others = existing(at).apply { remove(key(item.name)) }
            val renamed = at.src.rename(at.folderId, item, uniqueName(others, name))
            thumbs.forget(at.src, listOf(item))
            if (item.isDir && renamed.id != item.id) {
                // Local: o id muda com o nome (é o caminho); os filhos ficam com ids antigos.
                Vault.remap(app, at.root.id, item.id, renamed.id, renamed.name)
                removeSubtree(at, item.id)
            }
            if (item.isDir) indexSubtree(at, renamed)
            done++
        } catch (e: Exception) {
            fail(e)
        }
        relist(at)
    }

    fun delete(at: Loc, items: List<FileItem>): Result = op {
        try {
            for ((i, item) in items.withIndex()) {
                checkCancel()
                listener.onProgress(item.name, i, items.size, 0, 0)
                try {
                    // Pasta com uma protegida dentro: apagar levaria a protegida junto. Recusa com o
                    // mesmo erro genérico da cópia, sem revelar que ela existe.
                    if (item.isDir && Vault.containsProtected(app, at.root.id, item.id)) {
                        throw IOException("não foi possível apagar")
                    }
                    at.src.delete(at.folderId, item)
                    Protection.onFolderDeleted(app, at.root.id, item)
                    forgetTree(at, item)
                    done++
                } catch (e: Exception) {
                    fail(e)
                }
            }
        } finally {
            relist(at)
        }
    }

    /** Copia ou move [items] de [from] para [to] (mesma origem ou entre local e Drive). */
    fun transfer(from: Loc, items: List<FileItem>, to: Loc, move: Boolean, toAncestors: List<String>): Result = op {
        val names = existing(to)
        val sameRoot = from.root.id == to.root.id
        val bothDrive = from.root.isDrive && to.root.isDrive
        try {
            for ((i, item) in items.withIndex()) {
                checkCancel()
                listener.onProgress(item.name, i, items.size, 0, item.size)
                try {
                    // Pasta para dentro dela mesma (ou de uma subpasta dela).
                    if (item.isDir && (sameRoot || bothDrive) && item.id in toAncestors) {
                        throw NotEditable("destino dentro da própria pasta")
                    }
                    if (move && (sameRoot || bothDrive) && from.folderId == to.folderId) {
                        done++ // mover para a mesma pasta: nada a fazer
                        continue
                    }
                    val name = uniqueName(names, item.name)
                    names.add(key(name))
                    var result: FileItem? = null
                    if (move && (sameRoot || bothDrive)) {
                        result = from.src.moveWithin(item, from.folderId, to.folderId, name)
                        if (result != null && item.isDir) Vault.remap(app, from.root.id, item.id, result.id, result.name)
                    }
                    if (result == null) {
                        result = copyTree(item, from.src, to, to.folderId, name, bothDrive, i, items.size)
                        if (move) {
                            from.src.delete(from.folderId, item)
                            Protection.onFolderDeleted(app, from.root.id, item)
                        }
                    }
                    // Drive mantém o id ao mover: as thumbs continuam valendo.
                    if (move) forgetTree(from, item, thumbsToo = result.id != item.id)
                    if (result.isDir) indexSubtree(to, result)
                    done++
                } catch (e: Exception) {
                    fail(e)
                }
            }
        } finally {
            relist(to)
            if (move && !(sameRoot && from.folderId == to.folderId)) relist(from)
        }
    }

    /** "Enviar arquivos": copia Uris escolhidas no seletor do sistema para [to]. */
    fun upload(to: Loc, uris: List<Uri>): Result = op {
        val cr = app.contentResolver
        val names = existing(to)
        try {
            for ((i, uri) in uris.withIndex()) {
                checkCancel()
                try {
                    var name = "arquivo"
                    var size = -1L
                    cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                        if (c.moveToFirst()) {
                            if (!c.isNull(0)) name = c.getString(0)
                            if (!c.isNull(1)) size = c.getLong(1)
                        }
                    }
                    val mime = cr.getType(uri) ?: "application/octet-stream"
                    listener.onProgress(name, i, uris.size, 0, size)
                    val unique = uniqueName(names, name)
                    names.add(key(unique))
                    val input = cr.openInputStream(uri) ?: throw IOException("não foi possível ler")
                    input.use {
                        to.src.write(to.folderId, unique, mime, size, it, cancelled::get) { b ->
                            listener.onProgress(name, i, uris.size, b, size)
                        }
                    }
                    done++
                } catch (e: Exception) {
                    fail(e)
                }
            }
        } finally {
            relist(to)
        }
    }

    // ---- Apoio ----

    /** Executa capturando o cancelamento (o que já foi feito fica feito). */
    private inline fun op(block: () -> Unit): Result {
        try {
            block()
        } catch (e: CancellationException) {
            cancelled.set(true)
        }
        return result()
    }

    /** Copia um arquivo ou uma pasta inteira (recursivo) para [parent] em [to]. */
    private fun copyTree(
        item: FileItem, src: FileSource, to: Loc, parent: String, name: String,
        bothDrive: Boolean, index: Int, count: Int, depth: Int = 0,
    ): FileItem {
        checkCancel()
        // Raízes sobrepostas (uma dentro da outra) poderiam copiar uma pasta para dentro dela mesma.
        if (depth > MAX_DEPTH) throw NotEditable("pastas aninhadas demais")
        if (item.isDir) {
            val children = src.listChildren(item.id)
            // Copiar uma pasta protegida junto criaria uma cópia visível dela: recusa (erro genérico,
            // sem dizer o motivo) antes de criar qualquer coisa. Ao mover, nada é apagado.
            if (children.any { it.isDir && isHiddenAnywhere(it.id) }) throw IOException("não foi possível copiar")
            val dir = to.src.createFolder(parent, name)
            for (child in children) {
                copyTree(child, src, to, dir.id, child.name, bothDrive, index, count, depth + 1)
            }
            return dir
        }
        listener.onProgress(item.name, index, count, 0, item.size)
        if (bothDrive) src.copyWithin(item, parent, name)?.let { return it }
        return src.openRead(item).use { input ->
            to.src.write(parent, name, item.mime, item.size, input, cancelled::get) { b ->
                listener.onProgress(item.name, index, count, b, item.size)
            }
        }
    }

    /** O item saiu de [at]: tira os descendentes do índice e as thumbs. */
    private fun forgetTree(at: Loc, item: FileItem, thumbsToo: Boolean = true) {
        if (item.isDir) {
            if (thumbsToo) {
                val files = at.vault?.let { VaultIndex.filesUnder(app, it, item.id) } ?: cache.filesUnder(at.root.id, item.id)
                thumbs.forget(at.src, files)
            }
            removeSubtree(at, item.id)
        } else if (thumbsToo) {
            thumbs.forget(at.src, listOf(item))
        }
    }

    // Índice: o comum (SQLite) ou, dentro de uma pasta protegida, o cifrado dela.

    private fun removeSubtree(at: Loc, folderId: String) {
        val v = at.vault
        if (v != null) VaultIndex.removeSubtree(app, v, folderId) else cache.removeSubtree(at.root.id, folderId)
    }

    private fun indexSubtree(at: Loc, dir: FileItem) {
        val v = at.vault
        if (v != null) VaultIndex.walk(app, v, at.src, at.root.isDrive, force = true, path = at.childPath(dir.name), from = dir.id)
        else Indexer.startSubtree(app, at.root.id, dir.id, at.childPath(dir.name))
    }

    /** Protegida em qualquer raiz da mesma origem (ids do Drive e caminhos locais são globais). */
    private val rootsByTag by lazy { Roots.allIncludingProtected(app) }

    private fun isHiddenAnywhere(id: String) = rootsByTag.any { Vault.isHidden(app, it.id, id) }

    /** Nomes já usados na pasta (minúsculas: o armazenamento local não diferencia). */
    private fun existing(at: Loc): MutableSet<String> =
        at.src.listChildren(at.folderId).mapTo(HashSet()) { key(it.name) }

    /** Relista a pasta e grava no cache (a tela e a busca veem o resultado na hora). */
    private fun relist(at: Loc) {
        try {
            val items = at.src.listChildren(at.folderId)
            val v = at.vault
            if (v != null) {
                VaultIndex.write(app, v, at.folderId, at.path, items)
            } else {
                cache.write(at.root.id, at.folderId, at.path, items)
                Roots.setCount(app, at.root.id, cache.countFiles(at.root.id))
            }
        } catch (e: Exception) {
            Log.w(TAG, "relistar após a operação", e)
        }
    }

    companion object {
        private const val TAG = "Index"
        private const val MAX_DEPTH = 64

        private fun key(name: String) = name.lowercase()

        /** "foto.jpg" já existe ⇒ "foto (1).jpg", "foto (2).jpg"… */
        fun uniqueName(used: Set<String>, name: String): String {
            if (key(name) !in used) return name
            val dot = name.lastIndexOf('.')
            val base = if (dot > 0) name.substring(0, dot) else name
            val ext = if (dot > 0) name.substring(dot) else ""
            var n = 1
            while (true) {
                val candidate = "$base ($n)$ext"
                if (key(candidate) !in used) return candidate
                n++
            }
        }

        /** Nome válido para arquivo/pasta (sem barras nem caracteres reservados). */
        fun validName(name: String): Boolean {
            val t = name.trim()
            return t.isNotEmpty() && t != "." && t != ".." && t.length <= 255 && t.none { it in "/\\:*?\"<>|" || it < ' ' }
        }
    }
}
