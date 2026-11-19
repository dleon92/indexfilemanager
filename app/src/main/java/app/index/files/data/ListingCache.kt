package app.index.files.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import app.index.files.security.Vault

/**
 * Cache persistente das listagens (tabela "files"). A pasta é exibida na hora a
 * partir daqui e revalidada em segundo plano contra o SAF/Drive.
 * Chamar fora da main thread.
 */
class ListingCache(ctx: Context) {
    private val app = ctx.applicationContext
    private val db = AppDb.get(ctx)

    /** Itens em cache de uma pasta, ou null se ela nunca foi listada. */
    fun read(rootId: Long, folderId: String): List<FileItem>? {
        val rd = db.readableDatabase
        val args = arrayOf(rootId.toString(), folderId)
        val listed = rd.rawQuery(
            "SELECT 1 FROM folders_cached WHERE root_id=? AND folder_id=?",
            args,
        ).use { it.moveToFirst() }
        if (!listed) return null
        return rd.rawQuery(
            "SELECT doc_id, name, name_norm, mime, size, modified, is_dir FROM files WHERE root_id=? AND parent_id=?",
            args,
        ).use { c ->
            val out = ArrayList<FileItem>(c.count)
            while (c.moveToNext()) {
                out.add(
                    FileItem(
                        id = c.getString(0),
                        name = c.getString(1),
                        mime = c.getString(3),
                        size = c.getLong(4),
                        modified = c.getLong(5),
                        isDir = c.getInt(6) != 0,
                        sortKey = c.getString(2),
                    ),
                )
            }
            out
        }
    }

    /**
     * Substitui a listagem em cache de uma pasta (transação única, statement reaproveitado).
     * [dirMtime] = data da pasta no momento da listagem (-1 se desconhecida ⇒ o indexador relista).
     */
    fun write(rootId: Long, folderId: String, parentPath: String, all: List<FileItem>, dirMtime: Long = -1) {
        // Pastas protegidas nunca entram no banco comum (nem a linha delas, nem o conteúdo).
        val items = all.filter { !(it.isDir && Vault.isHidden(app, rootId, it.id)) }
        val w = db.writableDatabase
        val root = rootId.toString()
        w.beginTransaction()
        try {
            w.delete("files", "root_id=? AND parent_id=?", arrayOf(root, folderId))
            val st = w.compileStatement(
                "INSERT OR REPLACE INTO files(root_id, doc_id, parent_id, parent_path, name, name_norm, mime, size, modified, is_dir) VALUES(?,?,?,?,?,?,?,?,?,?)",
            )
            for (it in items) {
                st.bindLong(1, rootId)
                st.bindString(2, it.id)
                st.bindString(3, folderId)
                st.bindString(4, parentPath)
                st.bindString(5, it.name)
                st.bindString(6, it.sortKey)
                st.bindString(7, it.mime)
                st.bindLong(8, it.size)
                st.bindLong(9, it.modified)
                st.bindLong(10, if (it.isDir) 1 else 0)
                st.executeInsert()
            }
            st.close()
            val v = ContentValues(4).apply {
                put("root_id", rootId)
                put("folder_id", folderId)
                put("listed_at", System.currentTimeMillis())
                put("dir_mtime", dirMtime)
            }
            w.insertWithOnConflict("folders_cached", null, v, SQLiteDatabase.CONFLICT_REPLACE)
            w.setTransactionSuccessful()
        } finally {
            w.endTransaction()
        }
    }

    /** dir_mtime gravado na última listagem; -2 se a pasta nunca foi listada. */
    fun folderMtime(rootId: Long, folderId: String): Long =
        db.readableDatabase.rawQuery(
            "SELECT dir_mtime FROM folders_cached WHERE root_id=? AND folder_id=?",
            arrayOf(rootId.toString(), folderId),
        ).use { if (it.moveToFirst()) it.getLong(0) else -2L }

    /** Remove do índice pastas (e seus arquivos) que não existem mais. */
    fun prune(rootId: Long, visited: Set<String>) {
        val w = db.writableDatabase
        w.beginTransaction()
        try {
            w.execSQL("CREATE TEMP TABLE IF NOT EXISTS visited(id TEXT PRIMARY KEY)")
            w.execSQL("DELETE FROM visited")
            val st = w.compileStatement("INSERT OR IGNORE INTO visited(id) VALUES(?)")
            for (id in visited) {
                st.bindString(1, id)
                st.executeInsert()
            }
            st.close()
            val r = arrayOf(rootId.toString())
            w.execSQL("DELETE FROM files WHERE root_id=? AND parent_id NOT IN (SELECT id FROM visited)", r)
            w.execSQL("DELETE FROM folders_cached WHERE root_id=? AND folder_id NOT IN (SELECT id FROM visited)", r)
            w.execSQL("DELETE FROM visited")
            w.setTransactionSuccessful()
        } finally {
            w.endTransaction()
        }
    }

    /**
     * Esquece tudo o que está DENTRO de [folderId] (pasta apagada ou movida): os
     * descendentes saem do índice e da busca. A linha da própria pasta fica na listagem
     * de quem a contém, que é regravada à parte.
     */
    fun removeSubtree(rootId: Long, folderId: String) {
        val w = db.writableDatabase
        val root = rootId.toString()
        val pending = ArrayDeque<String>().apply { add(folderId) }
        val seen = HashSet<String>()
        w.beginTransaction()
        try {
            while (true) {
                val id = pending.removeFirstOrNull() ?: break
                if (!seen.add(id)) continue
                w.rawQuery("SELECT doc_id FROM files WHERE root_id=? AND parent_id=? AND is_dir=1", arrayOf(root, id))
                    .use { while (it.moveToNext()) pending.add(it.getString(0)) }
                w.delete("files", "root_id=? AND parent_id=?", arrayOf(root, id))
                w.delete("folders_cached", "root_id=? AND folder_id=?", arrayOf(root, id))
            }
            w.setTransactionSuccessful()
        } finally {
            w.endTransaction()
        }
    }

    /** A pasta passou a ser protegida: some do banco comum (a linha dela e tudo dentro). */
    fun removeFolder(rootId: Long, folderId: String) {
        removeSubtree(rootId, folderId)
        db.writableDatabase.delete("files", "root_id=? AND doc_id=?", arrayOf(rootId.toString(), folderId))
    }

    /** Arquivos (imagens e vídeos) dentro de [folderId], em qualquer nível, a partir do índice. */
    /**
     * Arquivos (não pastas) dentro de [folderId], em qualquer nível. [skipHidden]: ignora
     * arquivos e pastas com nome iniciado por "."; [skipDir]: pastas que não devem ser abertas.
     */
    fun filesUnder(
        rootId: Long,
        folderId: String,
        skipHidden: Boolean = false,
        skipDir: ((String) -> Boolean)? = null,
    ): List<FileItem> {
        val rd = db.readableDatabase
        val root = rootId.toString()
        val out = ArrayList<FileItem>()
        val pending = ArrayDeque<String>().apply { add(folderId) }
        val seen = HashSet<String>()
        while (true) {
            val id = pending.removeFirstOrNull() ?: break
            if (!seen.add(id)) continue
            rd.rawQuery(
                "SELECT doc_id, name, mime, size, modified, is_dir FROM files WHERE root_id=? AND parent_id=?",
                arrayOf(root, id),
            ).use { c ->
                while (c.moveToNext()) {
                    val name = c.getString(1)
                    if (skipHidden && name.startsWith('.')) continue
                    if (c.getInt(5) != 0) {
                        val dir = c.getString(0)
                        if (skipDir?.invoke(dir) != true) pending.add(dir)
                    } else {
                        out.add(FileItem(c.getString(0), name, c.getString(2), c.getLong(3), c.getLong(4), false, ""))
                    }
                }
            }
        }
        return out
    }

    /** Quantidade de arquivos (não pastas) indexados numa raiz. */
    fun countFiles(rootId: Long): Int =
        db.readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM files WHERE root_id=? AND is_dir=0",
            arrayOf(rootId.toString()),
        ).use { if (it.moveToFirst()) it.getInt(0) else 0 }

    /**
     * Caminho (ids e nomes) da raiz até [folderId], subindo pelo índice.
     * Usado em "ir para a pasta". Null se algum nível não estiver indexado.
     */
    fun pathTo(rootId: Long, rootDocId: String, rootName: String, folderId: String): Pair<ArrayList<String>, ArrayList<String>>? {
        val ids = ArrayList<String>()
        val names = ArrayList<String>()
        val rd = db.readableDatabase
        var cur = folderId
        var guard = 0
        while (cur != rootDocId) {
            if (++guard > 256) return null
            val row = rd.rawQuery(
                "SELECT name, parent_id FROM files WHERE root_id=? AND doc_id=?",
                arrayOf(rootId.toString(), cur),
            ).use { if (it.moveToFirst()) it.getString(0) to it.getString(1) else null } ?: return null
            ids.add(0, cur)
            names.add(0, row.first)
            cur = row.second
        }
        ids.add(0, rootDocId)
        names.add(0, rootName)
        return ids to names
    }

    /** Apaga todo o cache de uma raiz (ao remover ou readicionar a pasta). */
    fun clearRoot(rootId: Long) {
        val w = db.writableDatabase
        val a = arrayOf(rootId.toString())
        w.beginTransaction()
        try {
            w.delete("files", "root_id=?", a)
            w.delete("folders_cached", "root_id=?", a)
            w.setTransactionSuccessful()
        } finally {
            w.endTransaction()
        }
    }
}
