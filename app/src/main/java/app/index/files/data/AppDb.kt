package app.index.files.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * Banco único do app (SQLiteOpenHelper puro).
 * v1: pastas raiz permitidas + ordenação por pasta.
 * v2: cache de listagem ("files" + "folders_cached"), base do índice de busca da fase 3.
 * v3: folders_cached.dir_mtime (indexação incremental).
 * v4: roots.indexed_at (Drive).
 */
class AppDb private constructor(ctx: Context) : SQLiteOpenHelper(ctx, "index.db", null, 4) {

    init {
        setWriteAheadLoggingEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE roots(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                source TEXT NOT NULL,
                uri TEXT NOT NULL,
                doc_id TEXT NOT NULL,
                name TEXT NOT NULL,
                alias TEXT NOT NULL,
                item_count INTEGER NOT NULL DEFAULT -1,
                created INTEGER NOT NULL)""",
        )
        db.execSQL(
            """CREATE TABLE folder_prefs(
                folder_key TEXT PRIMARY KEY,
                sort_by INTEGER NOT NULL,
                sort_asc INTEGER NOT NULL,
                dirs_first INTEGER NOT NULL)""",
        )
        createV2(db)
        createV3(db)
        createV4(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) createV2(db)
        if (oldVersion < 3) createV3(db)
        if (oldVersion < 4) createV4(db)
    }

    /** Última indexação completa da raiz (o Drive não é reindexado a cada abertura). */
    private fun createV4(db: SQLiteDatabase) {
        db.execSQL("ALTER TABLE roots ADD COLUMN indexed_at INTEGER NOT NULL DEFAULT 0")
    }

    /** Data de modificação da pasta quando foi listada: igual na próxima varredura ⇒ não relista. */
    private fun createV3(db: SQLiteDatabase) {
        db.execSQL("ALTER TABLE folders_cached ADD COLUMN dir_mtime INTEGER NOT NULL DEFAULT -1")
    }

    private fun createV2(db: SQLiteDatabase) {
        // Um registro por arquivo/pasta já listado. parent_path = caminho relativo
        // da pasta que o contém (para exibir na busca). Origem vem de roots.source.
        db.execSQL(
            """CREATE TABLE files(
                root_id INTEGER NOT NULL,
                doc_id TEXT NOT NULL,
                parent_id TEXT NOT NULL,
                parent_path TEXT NOT NULL,
                name TEXT NOT NULL,
                name_norm TEXT NOT NULL,
                mime TEXT NOT NULL,
                size INTEGER NOT NULL,
                modified INTEGER NOT NULL,
                is_dir INTEGER NOT NULL,
                PRIMARY KEY(root_id, doc_id)) WITHOUT ROWID""",
        )
        db.execSQL("CREATE INDEX files_parent ON files(root_id, parent_id)")
        // Pastas cuja listagem está no cache (distingue "vazia" de "nunca listada").
        db.execSQL(
            """CREATE TABLE folders_cached(
                root_id INTEGER NOT NULL,
                folder_id TEXT NOT NULL,
                listed_at INTEGER NOT NULL,
                PRIMARY KEY(root_id, folder_id)) WITHOUT ROWID""",
        )
    }

    /** Ordenação salva para uma pasta, ou null para usar o padrão global. */
    fun folderSort(key: String): SortSpec? =
        readableDatabase.rawQuery(
            "SELECT sort_by, sort_asc, dirs_first FROM folder_prefs WHERE folder_key=?",
            arrayOf(key),
        ).use { if (it.moveToFirst()) SortSpec(it.getInt(0), it.getInt(1) != 0, it.getInt(2) != 0) else null }

    fun setFolderSort(key: String, spec: SortSpec?) {
        val db = writableDatabase
        if (spec == null) {
            db.delete("folder_prefs", "folder_key=?", arrayOf(key))
            return
        }
        val v = ContentValues(4).apply {
            put("folder_key", key)
            put("sort_by", spec.by)
            put("sort_asc", if (spec.asc) 1 else 0)
            put("dirs_first", if (spec.dirsFirst) 1 else 0)
        }
        db.insertWithOnConflict("folder_prefs", null, v, SQLiteDatabase.CONFLICT_REPLACE)
    }

    companion object {
        @Volatile private var instance: AppDb? = null

        fun get(ctx: Context): AppDb = instance ?: synchronized(this) {
            instance ?: AppDb(ctx.applicationContext).also { instance = it }
        }
    }
}
