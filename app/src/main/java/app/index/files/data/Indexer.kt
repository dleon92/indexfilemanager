package app.index.files.data

import android.content.Context
import android.os.SystemClock
import android.util.Log
import app.index.files.Bg
import app.index.files.data.drive.AuthRequired
import app.index.files.security.Vault
import java.util.ArrayDeque
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executors

/**
 * Indexação em segundo plano (thread única, prioridade mínima; sem WorkManager).
 * Percorre cada pasta raiz em largura e grava as listagens na tabela "files".
 *
 * Incremental: se a data de modificação de uma pasta é a mesma da última
 * listagem, os filhos vêm do índice em vez do SAF (criar/apagar/renomear um item
 * muda a data da pasta que o contém). "Reindexar" ignora isso e relista tudo.
 */
object Indexer {

    fun interface Listener {
        fun onIndexChanged(running: Boolean)
    }

    private val exec = Executors.newSingleThreadExecutor { r ->
        Thread(r, "indexer").apply {
            priority = Thread.MIN_PRIORITY
            isDaemon = true
        }
    }
    private val listeners = CopyOnWriteArraySet<Listener>()

    @Volatile var running = false
        private set

    @Volatile private var pendingAll = false

    fun addListener(l: Listener) = listeners.add(l)
    fun removeListener(l: Listener) = listeners.remove(l)

    /** Indexa todas as raízes ([rootId] null) ou uma só. [full] = relistar tudo. */
    fun start(ctx: Context, rootId: Long? = null, full: Boolean = false) {
        if (rootId == null && !full) {
            if (pendingAll) return
            pendingAll = true
        }
        val app = ctx.applicationContext
        exec.execute {
            if (rootId == null && !full) pendingAll = false
            setRunning(true)
            try {
                val roots = if (rootId == null) Roots.all(app) else listOfNotNull(Roots.get(app, rootId))
                // Na abertura do app o Drive só é reindexado a cada 12 h (custa rede e cota).
                val auto = rootId == null && !full
                for (r in roots) runCatching { indexRoot(app, r, full, auto) }
                    .onFailure { Log.w(TAG, "falha ao indexar ${r.alias}", it) }
            } finally {
                setRunning(false)
            }
        }
    }

    /**
     * Indexa só uma subárvore (pasta copiada ou movida pela edição de arquivos), sem
     * podar o resto da raiz. [path] = caminho relativo da própria pasta.
     */
    fun startSubtree(ctx: Context, rootId: Long, folderId: String, path: String) {
        val app = ctx.applicationContext
        exec.execute {
            setRunning(true)
            try {
                val r = Roots.get(app, rootId) ?: return@execute
                if (r.isRecent) return@execute
                if (Vault.isHiddenRoot(app, r) || Vault.isHidden(app, rootId, folderId)) return@execute
                runCatching { walk(app, r, Dir(folderId, path, -1L), full = true) }
                    .onFailure { Log.w(TAG, "falha ao indexar subpasta", it) }
                Roots.setCount(app, r.id, ListingCache(app).countFiles(r.id))
            } finally {
                setRunning(false)
            }
        }
    }

    private fun setRunning(v: Boolean) {
        running = v
        Bg.main.post { for (l in listeners) l.onIndexChanged(v) }
    }

    private class Dir(val id: String, val path: String, val mtime: Long)

    private fun indexRoot(ctx: Context, r: Root, full: Boolean, auto: Boolean) {
        if (r.isRecent) return // pasta virtual: vem do índice de mídia do Android
        if (Vault.isHiddenRoot(ctx, r)) return // protegida: índice próprio, cifrado
        if (!Roots.checkAvailable(ctx, r)) return
        val now = System.currentTimeMillis()
        if (r.isDrive && auto && now - r.indexedAt < DRIVE_INTERVAL_MS) return
        val t0 = SystemClock.elapsedRealtime()
        val w = walk(ctx, r, Dir(r.docId, "", Roots.rootModified(ctx, r)), full)
        val cache = ListingCache(ctx)
        // Com falhas, pastas não visitadas podem existir ainda: não poda.
        if (w.failures == 0) cache.prune(r.id, w.visited)
        Roots.setCount(ctx, r.id, cache.countFiles(r.id))
        if (w.failures == 0) Roots.setIndexedAt(ctx, r.id, now)
        Log.i(TAG, "${r.alias}: ${w.visited.size} pastas (${w.listed} listadas, ${w.reused} do índice, ${w.failures} falhas) em ${SystemClock.elapsedRealtime() - t0} ms")
    }

    private class Walk(val visited: Set<String>, val listed: Int, val reused: Int, val failures: Int)

    /** Percorre em largura a partir de [start], gravando as listagens no índice. */
    private fun walk(ctx: Context, r: Root, start: Dir, full: Boolean): Walk {
        val src = Roots.source(ctx, r)
        val cache = ListingCache(ctx)
        val visited = HashSet<String>()
        val queue = ArrayDeque<Dir>()
        queue.add(start)
        var listed = 0
        var reused = 0
        var failures = 0
        while (true) {
            val d = queue.poll() ?: break
            // Atalhos do Drive podem formar ciclos: cada pasta só uma vez.
            if (!visited.add(d.id)) continue
            // Drive: a data da pasta não muda quando os filhos mudam (mtime -1 ⇒ sempre relista).
            val known = if (full) -2L else cache.folderMtime(r.id, d.id)
            var fromCache = false
            val items: List<FileItem> = if (d.mtime > 0 && known == d.mtime) {
                reused++
                fromCache = true
                cache.read(r.id, d.id) ?: emptyList()
            } else {
                try {
                    src.listChildren(d.id).also {
                        cache.write(r.id, d.id, d.path, it, d.mtime)
                        listed++
                    }
                } catch (e: AuthRequired) {
                    throw e // sem conta: aborta a raiz inteira, índice fica como estava
                } catch (e: Exception) {
                    // Pasta inacessível / sem rede: mantém o que havia no índice.
                    failures++
                    cache.read(r.id, d.id) ?: emptyList()
                }
            }
            for (it in items) {
                if (!it.isDir || Vault.isHidden(ctx, r.id, it.id)) continue
                // Pasta de cima reaproveitada do índice: a data guardada da subpasta é a de quando
                // a de cima foi listada (arquivo novo só na subpasta não muda a de cima). Lê a atual.
                val childMtime = when {
                    r.isDrive -> -1L
                    fromCache -> src.folderMtime(it.id)
                    else -> it.modified
                }
                queue.add(Dir(it.id, if (d.path.isEmpty()) it.name else "${d.path}/${it.name}", childMtime))
            }
        }
        return Walk(visited, listed, reused, failures)
    }

    private const val TAG = "Index"
    private const val DRIVE_INTERVAL_MS = 12 * 3600_000L
}
