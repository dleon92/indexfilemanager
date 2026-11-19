package app.index.files.data

import android.content.Context
import android.os.SystemClock

class SearchQuery(
    val text: String,
    /** -1 = todas as pastas permitidas. */
    val rootId: Long,
    /** Caminho relativo da pasta atual (inclui subpastas); null/"" = raiz inteira. */
    val folderPath: String?,
    val filter: Int,
    val spec: SortSpec,
    val showHidden: Boolean,
    /** Máximo de resultados; a seleção aleatória usa um valor maior para sortear entre todos. */
    val limit: Int = Search.LIMIT,
)

class SearchResult(val items: List<FileItem>, val truncated: Boolean, val ms: Long)

/** Busca por nome no índice SQLite (contém, sem diferenciar maiúsculas/acentos). */
object Search {
    const val ALL = 0
    const val PHOTOS = 1
    const val VIDEOS = 2
    const val LIMIT = 5000

    fun run(ctx: Context, q: SearchQuery): SearchResult {
        val t0 = SystemClock.elapsedRealtime()
        val args = ArrayList<String>()
        val where = StringBuilder("f.name_norm LIKE ? ESCAPE '\\'")
        args.add("%" + escape(normalizeName(q.text.trim())) + "%")
        if (q.rootId >= 0) {
            where.append(" AND f.root_id=?")
            args.add(q.rootId.toString())
            val p = q.folderPath
            if (!p.isNullOrEmpty()) {
                where.append(" AND (f.parent_path=? OR f.parent_path LIKE ? ESCAPE '\\')")
                args.add(p)
                args.add(escape(p) + "/%")
            }
        }
        when (q.filter) {
            PHOTOS -> where.append(" AND f.mime LIKE 'image/%'")
            VIDEOS -> where.append(" AND f.mime LIKE 'video/%'")
        }
        if (!q.showHidden) {
            where.append(" AND f.name NOT LIKE '.%' AND f.parent_path NOT LIKE '.%' AND f.parent_path NOT LIKE '%/.%'")
        }
        // O SQL escolhe os primeiros LIMIT na ordem aproximada; a ordem exata
        // (natural, extensão) é aplicada em memória com o mesmo comparador da navegação.
        val s = q.spec
        val dir = if (s.asc) "ASC" else "DESC"
        val col = when (s.by) {
            SortSpec.SIZE -> "f.size"
            SortSpec.TYPE -> "f.mime"
            SortSpec.DATE -> "f.modified"
            else -> "f.name_norm"
        }
        val dirsFirst = if (s.dirsFirst) "f.is_dir DESC, " else ""
        val sql = "SELECT f.root_id, f.doc_id, f.parent_id, f.parent_path, f.name, f.name_norm, f.mime, f.size, f.modified, f.is_dir " +
            "FROM files f JOIN roots r ON r.id=f.root_id WHERE $where " +
            "ORDER BY $dirsFirst$col $dir, f.name_norm $dir LIMIT ${q.limit.toLong() + 1}"

        val out = ArrayList<FileItem>()
        AppDb.get(ctx).readableDatabase.rawQuery(sql, args.toTypedArray()).use { c ->
            while (c.moveToNext()) {
                out.add(
                    FileItem(
                        id = c.getString(1),
                        name = c.getString(4),
                        mime = c.getString(6),
                        size = c.getLong(7),
                        modified = c.getLong(8),
                        isDir = c.getInt(9) != 0,
                        sortKey = c.getString(5),
                    ).also {
                        it.rootId = c.getLong(0)
                        it.parentId = c.getString(2)
                        it.parentPath = c.getString(3)
                    },
                )
            }
        }
        val truncated = out.size > q.limit
        if (truncated) out.removeAt(out.size - 1)
        val sorted = out.sortedWith(comparatorFor(s))
        return SearchResult(sorted, truncated, SystemClock.elapsedRealtime() - t0)
    }

    private fun escape(s: String) = s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
}
