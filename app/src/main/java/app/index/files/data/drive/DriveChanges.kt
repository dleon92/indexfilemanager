package app.index.files.data.drive

import android.content.Context
import android.util.Log
import app.index.files.data.AppDb
import app.index.files.data.FileItem
import app.index.files.data.ListingCache
import app.index.files.data.Prefs
import app.index.files.data.Roots
import app.index.files.thumbs.ThumbnailManager

/**
 * Registro de mudanças do Drive (changes API): em vez de listar cada pasta de novo ao abrir,
 * uma única consulta diz o que mudou em toda a conta desde a última vez. Só as pastas tocadas
 * são marcadas para relistar; as outras abrem direto da cópia salva, sem rede.
 * Tudo fora da main thread.
 */
object DriveChanges {
    private const val TAG = "DriveChanges"

    /** Pasta listada há menos que isto abre só da cópia salva (nem consulta as mudanças). */
    const val FRESH_MS = 10 * 60_000L

    /** Depois disto a pasta é relistada mesmo sem mudança registrada (atalhos, segurança). */
    private const val MAX_TRUST_MS = 24 * 3600_000L

    /** Intervalo mínimo entre consultas ao registro (abrir várias pastas seguidas). */
    private const val POLL_MS = 30_000L

    private val lock = Any()
    @Volatile private var lastPoll = 0L

    /**
     * A pasta precisa ser listada na rede? false = a cópia salva está em dia. Sem rede devolve
     * true (quem chama tenta listar e cai no aviso de "sem conexão" com a cópia na tela).
     */
    fun needsList(ctx: Context, rootId: Long, folderId: String): Boolean {
        val cache = ListingCache(ctx)
        val at = cache.listedAt(rootId, folderId)
        if (at <= 0) return true
        val now = System.currentTimeMillis()
        if (now - at < FRESH_MS) return false
        if (now - at > MAX_TRUST_MS) return true
        val since = try {
            sync(ctx)
        } catch (e: Exception) {
            Log.w(TAG, "registro de mudanças indisponível", e)
            return true
        }
        // Listada antes de o registro começar: não há como saber o que mudou nesse meio-tempo.
        if (at < since) return true
        // O sync zera listed_at das pastas tocadas.
        return cache.listedAt(rootId, folderId) <= 0
    }

    /** Esquece o registro (desconectou ou trocou de conta). */
    fun reset(ctx: Context) {
        Prefs.get(ctx).setDriveChanges(null, 0L, null)
        lastPoll = 0L
    }

    /**
     * Aplica as mudanças desde o último token. Devolve desde quando o registro cobre a conta
     * (pastas listadas antes disso não são confiáveis). Lança IOException sem rede.
     */
    private fun sync(ctx: Context): Long = synchronized(lock) {
        val prefs = Prefs.get(ctx)
        val email = prefs.driveEmail
        val token = prefs.driveChangesToken
        val now = System.currentTimeMillis()
        if (token == null || prefs.driveChangesEmail != email) {
            prefs.setDriveChanges(DriveApi.startPageToken(ctx), now, email)
            lastPoll = now
            return now
        }
        if (now - lastPoll < POLL_MS) return prefs.driveChangesSince
        val (changes, next) = try {
            DriveApi.changes(ctx, token)
        } catch (e: DriveHttpError) {
            if (e.code != 404 && e.code != 410 && e.code != 400) throw e
            // Token velho demais: recomeça (as pastas abertas depois daqui voltam a valer).
            prefs.setDriveChanges(DriveApi.startPageToken(ctx), now, email)
            lastPoll = now
            return now
        }
        if (changes.isNotEmpty()) apply(ctx, changes)
        prefs.setDriveChanges(next, prefs.driveChangesSince, email)
        lastPoll = now
        Log.i(TAG, "${changes.size} mudanças")
        return prefs.driveChangesSince
    }

    /** Marca as pastas tocadas (pai novo e pai antigo) e apaga as miniaturas que não valem mais. */
    private fun apply(ctx: Context, changes: List<DriveApi.Change>) {
        val roots = Roots.allIncludingProtected(ctx).filter { it.isDrive }.map { it.id.toString() }
        if (roots.isEmpty()) return
        val inRoots = roots.joinToString(",", "(", ")") { "?" }
        val db = AppDb.get(ctx).writableDatabase
        val dirty = HashSet<String>()
        val staleThumbs = ArrayList<FileItem>()
        for (ch in changes) {
            dirty.addAll(ch.parents)
            // Onde o arquivo estava (movido, apagado) e como era (para as miniaturas antigas).
            db.rawQuery(
                "SELECT parent_id, name, mime, size, modified, is_dir FROM files WHERE doc_id=? AND root_id IN $inRoots",
                arrayOf(ch.fileId) + roots,
            ).use { c ->
                while (c.moveToNext()) {
                    dirty.add(c.getString(0))
                    val old = FileItem(ch.fileId, c.getString(1), c.getString(2), c.getLong(3), c.getLong(4), c.getInt(5) != 0, "")
                    val same = !ch.removed && old.size == ch.size && old.modified == ch.modified
                    if (!old.isDir && !same && ThumbnailManager.hasThumb(old)) staleThumbs.add(old)
                }
            }
        }
        if (dirty.isNotEmpty()) {
            db.beginTransaction()
            try {
                val st = db.compileStatement("UPDATE folders_cached SET listed_at=0 WHERE folder_id=? AND root_id IN $inRoots")
                for (id in dirty) {
                    st.clearBindings()
                    st.bindString(1, id)
                    roots.forEachIndexed { i, r -> st.bindString(i + 2, r) }
                    st.executeUpdateDelete()
                }
                st.close()
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        }
        if (staleThumbs.isNotEmpty()) ThumbnailManager.get(ctx).forgetDrive(staleThumbs)
    }
}
