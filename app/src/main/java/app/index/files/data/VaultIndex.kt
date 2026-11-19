package app.index.files.data

import android.content.Context
import android.util.Log
import app.index.files.security.Crypto
import app.index.files.security.Vault
import app.index.files.security.VaultEntry
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.util.concurrent.Executors

/**
 * Índice das pastas protegidas: fora do banco comum, um arquivo por pasta protegida
 * (`files/vault/<id>/index.bin`) cifrado com AES-GCM (chave de dados do cofre). Fica na
 * memória só enquanto a pasta está desbloqueada; serve à listagem em cache, à busca dentro
 * dela e às operações de edição.
 */
object VaultIndex {

    private class Folder(val path: String, val items: List<FileItem>)

    private class Idx(val folders: HashMap<String, Folder>, var walkedAt: Long)

    private val loaded = HashMap<String, Idx>()
    private val io = Executors.newSingleThreadExecutor { r ->
        Thread(r, "vault-index").apply {
            priority = Thread.MIN_PRIORITY
            isDaemon = true
        }
    }
    private const val TAG = "Vault"
    private const val VERSION = 1
    private const val DRIVE_WALK_MS = 12 * 3600_000L

    private fun file(ctx: Context, e: VaultEntry) = File(File(Vault.dir(ctx), e.id), "index.bin")

    private fun idx(ctx: Context, e: VaultEntry): Idx = synchronized(loaded) {
        loaded.getOrPut(e.id) { load(ctx, e) }
    }

    private fun load(ctx: Context, e: VaultEntry): Idx {
        val f = file(ctx, e)
        if (!f.exists()) return Idx(HashMap(), 0L)
        return try {
            val plain = Crypto.decrypt(Vault.dataKey(ctx), f.readBytes())
            DataInputStream(ByteArrayInputStream(plain)).use { inp ->
                if (inp.readInt() != VERSION) return Idx(HashMap(), 0L)
                val walked = inp.readLong()
                val n = inp.readInt()
                val map = HashMap<String, Folder>(n * 2)
                repeat(n) {
                    val id = inp.readUTF()
                    val path = inp.readUTF()
                    val count = inp.readInt()
                    val items = ArrayList<FileItem>(count)
                    repeat(count) {
                        items.add(
                            FileItem(
                                id = inp.readUTF(),
                                name = inp.readUTF(),
                                mime = inp.readUTF(),
                                size = inp.readLong(),
                                modified = inp.readLong(),
                                isDir = inp.readBoolean(),
                            ),
                        )
                    }
                    map[id] = Folder(path, items)
                }
                Idx(map, walked)
            }
        } catch (ex: Exception) {
            Log.w(TAG, "índice protegido ilegível; será refeito", ex)
            Idx(HashMap(), 0L)
        }
    }

    /** Grava em segundo plano (cópia do estado atual). */
    private fun scheduleSave(ctx: Context, e: VaultEntry) {
        val app = ctx.applicationContext
        io.execute {
            val snapshot = synchronized(loaded) {
                val i = loaded[e.id] ?: return@execute
                i.walkedAt to HashMap(i.folders)
            }
            try {
                val bos = ByteArrayOutputStream()
                DataOutputStream(bos).use { out ->
                    out.writeInt(VERSION)
                    out.writeLong(snapshot.first)
                    out.writeInt(snapshot.second.size)
                    for ((id, folder) in snapshot.second) {
                        out.writeUTF(id)
                        out.writeUTF(folder.path)
                        out.writeInt(folder.items.size)
                        for (it in folder.items) {
                            out.writeUTF(it.id)
                            out.writeUTF(it.name)
                            out.writeUTF(it.mime)
                            out.writeLong(it.size)
                            out.writeLong(it.modified)
                            out.writeBoolean(it.isDir)
                        }
                    }
                }
                val f = file(app, e)
                f.parentFile?.mkdirs()
                val tmp = File(f.parentFile, "index.tmp")
                tmp.writeBytes(Crypto.encrypt(Vault.dataKey(app), bos.toByteArray()))
                if (!tmp.renameTo(f)) tmp.delete()
            } catch (ex: Exception) {
                Log.w(TAG, "falha ao gravar índice protegido", ex)
            }
        }
    }

    // ---- Consultas ----

    fun read(ctx: Context, e: VaultEntry, folderId: String): List<FileItem>? =
        synchronized(loaded) { idx(ctx, e).folders[folderId]?.items }

    /** Arquivos (não pastas) dentro de [folderId], em qualquer nível. */
    fun filesUnder(ctx: Context, e: VaultEntry, folderId: String, skipHidden: Boolean = false): List<FileItem> = synchronized(loaded) {
        val folders = idx(ctx, e).folders
        val out = ArrayList<FileItem>()
        val pending = ArrayDeque<String>().apply { add(folderId) }
        val seen = HashSet<String>()
        while (true) {
            val id = pending.removeFirstOrNull() ?: break
            if (!seen.add(id)) continue
            for (it in folders[id]?.items ?: continue) {
                if (skipHidden && it.name.startsWith('.')) continue
                // Protegida dentro de protegida: tem a própria entrada, não entra aqui.
                if (it.isDir) {
                    if (!Vault.isHidden(ctx, e.rootId, it.id)) pending.add(it.id)
                } else {
                    out.add(it)
                }
            }
        }
        out
    }

    /** Busca por nome dentro da pasta protegida (mesmas regras da busca comum). */
    fun search(ctx: Context, e: VaultEntry, text: String, filter: Int, showHidden: Boolean, spec: SortSpec, limit: Int = Search.LIMIT): SearchResult {
        val t0 = android.os.SystemClock.elapsedRealtime()
        val needle = normalizeName(text.trim())
        val out = ArrayList<FileItem>()
        synchronized(loaded) {
            for ((folderId, folder) in idx(ctx, e).folders) {
                if (!showHidden && folder.path.split('/').any { it.startsWith('.') }) continue
                for (it in folder.items) {
                    if (!it.sortKey.contains(needle)) continue
                    if (!showHidden && it.name.startsWith('.')) continue
                    when (filter) {
                        Search.PHOTOS -> if (it.kind != FileItem.KIND_IMAGE) continue
                        Search.VIDEOS -> if (it.kind != FileItem.KIND_VIDEO) continue
                    }
                    out.add(
                        FileItem(it.id, it.name, it.mime, it.size, it.modified, it.isDir, it.sortKey).also { r ->
                            r.rootId = e.rootId
                            r.parentId = folderId
                            r.parentPath = folder.path
                        },
                    )
                    if (out.size > limit) break
                }
            }
        }
        val truncated = out.size > limit
        val list = if (truncated) out.subList(0, limit) else out
        return SearchResult(list.sortedWith(comparatorFor(spec)), truncated, android.os.SystemClock.elapsedRealtime() - t0)
    }

    /** Caminho (ids e nomes) da pasta protegida até [folderId]. */
    fun pathTo(ctx: Context, e: VaultEntry, folderId: String): Pair<ArrayList<String>, ArrayList<String>>? = synchronized(loaded) {
        val folders = idx(ctx, e).folders
        // Pai de cada pasta, a partir das listagens.
        val parent = HashMap<String, Pair<String, String>>()
        for ((pid, f) in folders) for (it in f.items) if (it.isDir) parent[it.id] = pid to it.name
        val ids = ArrayList<String>()
        val names = ArrayList<String>()
        var cur = folderId
        var guard = 0
        while (cur != e.folderId) {
            if (++guard > 256) return@synchronized null
            val (pid, name) = parent[cur] ?: return@synchronized null
            ids.add(0, cur)
            names.add(0, name)
            cur = pid
        }
        ids.add(0, e.folderId)
        names.add(0, e.name)
        ids to names
    }

    // ---- Alterações ----

    fun write(ctx: Context, e: VaultEntry, folderId: String, path: String, items: List<FileItem>) {
        // Pasta protegida dentro de outra: também some daqui (tem a própria entrada).
        val visible = items.filter { !(it.isDir && Vault.isHidden(ctx, e.rootId, it.id)) }
        synchronized(loaded) { idx(ctx, e).folders[folderId] = Folder(path, visible) }
        scheduleSave(ctx, e)
    }

    fun removeSubtree(ctx: Context, e: VaultEntry, folderId: String) {
        synchronized(loaded) {
            val folders = idx(ctx, e).folders
            val pending = ArrayDeque<String>().apply { add(folderId) }
            while (true) {
                val id = pending.removeFirstOrNull() ?: break
                val f = folders.remove(id) ?: continue
                f.items.filter { it.isDir }.forEach { pending.add(it.id) }
            }
        }
        scheduleSave(ctx, e)
    }

    /**
     * Relista a pasta protegida inteira em segundo plano (busca completa). Drive: no máximo
     * a cada 12 h, a menos que [force].
     */
    fun walk(ctx: Context, e: VaultEntry, src: FileSource, isDrive: Boolean, force: Boolean = false, path: String = "", from: String = e.folderId) {
        val app = ctx.applicationContext
        io.execute {
            val i = synchronized(loaded) { loaded[e.id] } ?: idx(app, e)
            if (!force && isDrive && from == e.folderId && System.currentTimeMillis() - i.walkedAt < DRIVE_WALK_MS) return@execute
            val queue = ArrayDeque<Pair<String, String>>().apply { add(from to path) }
            val seen = HashSet<String>()
            var failures = 0
            while (true) {
                val (id, p) = queue.removeFirstOrNull() ?: break
                if (!seen.add(id)) continue
                if (!Vault.isOpen(e.id)) return@execute // trancou no meio: para
                val items = try {
                    src.listChildren(id)
                } catch (ex: Exception) {
                    failures++
                    continue
                }
                write(app, e, id, p, items)
                for (it in items) if (it.isDir && !Vault.isHidden(app, e.rootId, it.id)) {
                    queue.add(it.id to if (p.isEmpty()) it.name else "$p/${it.name}")
                }
            }
            if (failures == 0 && from == e.folderId) synchronized(loaded) { loaded[e.id]?.walkedAt = System.currentTimeMillis() }
            scheduleSave(app, e)
        }
    }

    /** Pasta trancada de novo: o índice sai da memória (continua cifrado no disco). */
    fun drop(id: String) {
        synchronized(loaded) { loaded.remove(id) }
    }

    fun dropAll() = synchronized(loaded) { loaded.clear() }
}
