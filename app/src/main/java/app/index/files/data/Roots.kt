package app.index.files.data

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import app.index.files.data.drive.DriveApi
import app.index.files.data.drive.DriveAuth
import app.index.files.data.drive.DriveSource
import app.index.files.security.Vault

/** Pasta raiz liberada pelo usuário. Nada fora delas é exibido. */
class Root(
    val id: Long,
    val source: String,
    val uri: Uri,
    val docId: String,
    val name: String,
    val alias: String,
    /** Arquivos indexados; -1 enquanto a pasta não foi indexada. */
    val itemCount: Int = -1,
    /** Última indexação completa (ms); 0 = nunca. */
    val indexedAt: Long = 0,
) {
    /** Pasta do Drive, incluindo a virtual "Recentes do Drive". */
    val isDrive get() = source == Roots.DRIVE || source == Roots.DRIVE_RECENT

    /** Lida pelo caminho ("Acesso a todos os arquivos"), não pelo SAF. */
    val isFile get() = source == Roots.FILE

    /** Pasta virtual "Recentes" (não fica no banco; ver [Roots.recent]). */
    val isRecent get() = source == Roots.RECENT || source == Roots.DRIVE_RECENT

    /** Pasta virtual "Recentes do Drive" (não fica no banco; ver [Roots.driveRecent]). */
    val isDriveRecent get() = source == Roots.DRIVE_RECENT

    /** Calculado em segundo plano (permissão persistida + pasta ainda existe). */
    var available: Boolean = true

    fun folderKey(folderId: String) = "$id:$folderId"
}

/** Repositório das pastas raiz (tabela "roots"). Chamar fora da main thread. */
object Roots {
    const val LOCAL = "local"
    const val DRIVE = "drive"
    const val FILE = "file"
    const val RECENT = "recent"
    const val DRIVE_RECENT = "drive_recent"

    /** Id fixo da pasta virtual Recentes (negativo: nunca colide com as linhas do banco; -1/-2 já são usados). */
    const val RECENT_ID = -10L

    /** Id fixo da pasta virtual Recentes do Drive. */
    const val DRIVE_RECENT_ID = -11L

    /** Pasta virtual "Recentes" (Android 11+, com "Acesso a todos os arquivos"). */
    fun recent(ctx: Context): Root = Root(
        id = RECENT_ID,
        source = RECENT,
        uri = Uri.parse("recent://files"),
        docId = RECENT,
        name = ctx.getString(app.index.files.R.string.recent_title),
        alias = ctx.getString(app.index.files.R.string.recent_title),
    )

    /** Pasta virtual "Recentes do Drive" (arquivos novos da nuvem, com os filtros da Recentes). */
    fun driveRecent(ctx: Context): Root = Root(
        id = DRIVE_RECENT_ID,
        source = DRIVE_RECENT,
        uri = Uri.parse("drive-recent://files"),
        docId = DRIVE_RECENT,
        name = ctx.getString(app.index.files.R.string.drive_recent_title),
        alias = ctx.getString(app.index.files.R.string.drive_recent_title),
    )

    /** Pastas Recentes adicionadas pelo usuário, com a disponibilidade (permissão ou conta). */
    fun virtual(ctx: Context): List<Root> {
        val p = Prefs.get(ctx)
        val out = ArrayList<Root>(2)
        if (p.recentAdded && Build.VERSION.SDK_INT >= 30) out.add(recent(ctx).also { it.available = hasAllFiles() })
        if (p.driveRecentAdded) out.add(driveRecent(ctx).also { it.available = DriveAuth.connected(ctx) })
        return out
    }

    /** Adiciona (ou tira) uma pasta Recentes da lista. */
    fun setVirtual(ctx: Context, id: Long, added: Boolean) {
        val p = Prefs.get(ctx)
        if (id == RECENT_ID) p.recentAdded = added else if (id == DRIVE_RECENT_ID) p.driveRecentAdded = added
        if (!added && p.startRoot == id) p.setStart(0L)
    }

    /**
     * Acesso às pastas pelo caminho: "Acesso a todos os arquivos" (Android 11+) ou, no Android 10 ou
     * anterior, a permissão de armazenamento comum, pedida só em aparelhos sem o seletor de pastas
     * do sistema ([legacyFiles]; nos demais o SAF continua como antes).
     */
    fun hasAllFiles(): Boolean =
        if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager() else legacyFiles

    /** Android ≤ 10: permissão de armazenamento concedida (atualizada por [refreshLegacyFiles]). */
    @Volatile var legacyFiles = false
        private set

    fun refreshLegacyFiles(ctx: Context) {
        legacyFiles = Build.VERSION.SDK_INT < 30 &&
            ctx.checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    /** Leitura e escrita desde a fase 7 (pastas antigas, só leitura, são readicionadas uma vez). */
    private const val FLAGS = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION

    /** Pode editar: Drive sempre (o escopo vale para a conta); local só com permissão de escrita. */
    fun canWrite(ctx: Context, r: Root): Boolean =
        r.isDrive || ((r.isFile || r.isRecent) && hasAllFiles()) || ctx.contentResolver.persistedUriPermissions.any { it.uri == r.uri && it.isWritePermission }

    /** Pastas visíveis no Index (as protegidas ficam de fora: só na tela "Pastas protegidas"). */
    fun all(ctx: Context): List<Root> = allIncludingProtected(ctx).filter { !Vault.isHiddenRoot(ctx, it) }

    fun allIncludingProtected(ctx: Context): List<Root> =
        AppDb.get(ctx).readableDatabase.rawQuery(
            "SELECT id, source, uri, doc_id, name, alias, item_count, indexed_at FROM roots ORDER BY alias COLLATE NOCASE",
            null,
        ).use { c ->
            val out = ArrayList<Root>(c.count)
            while (c.moveToNext()) out.add(read(c))
            out
        }

    fun get(ctx: Context, id: Long): Root? = if (id == RECENT_ID) recent(ctx) else if (id == DRIVE_RECENT_ID) driveRecent(ctx) else
        AppDb.get(ctx).readableDatabase.rawQuery(
            "SELECT id, source, uri, doc_id, name, alias, item_count, indexed_at FROM roots WHERE id=?",
            arrayOf(id.toString()),
        ).use { if (it.moveToFirst()) read(it) else null }

    private fun read(c: android.database.Cursor) = Root(
        id = c.getLong(0),
        source = c.getString(1),
        uri = Uri.parse(c.getString(2)),
        docId = c.getString(3),
        name = c.getString(4),
        alias = c.getString(5),
        itemCount = c.getInt(6),
        indexedAt = c.getLong(7),
    )

    fun setCount(ctx: Context, id: Long, count: Int) {
        val v = ContentValues(1).apply { put("item_count", count) }
        AppDb.get(ctx).writableDatabase.update("roots", v, "id=?", arrayOf(id.toString()))
    }

    fun setIndexedAt(ctx: Context, id: Long, at: Long) {
        val v = ContentValues(1).apply { put("indexed_at", at) }
        AppDb.get(ctx).writableDatabase.update("roots", v, "id=?", arrayOf(id.toString()))
    }

    /** Data de modificação da própria pasta raiz (para a indexação incremental). -1 no Drive. */
    fun rootModified(ctx: Context, r: Root): Long = if (r.isDrive) -1L
    else if (r.isFile) java.io.File(r.docId).lastModified().takeIf { it > 0 } ?: -1L
    else try {
        val doc = DocumentsContract.buildDocumentUriUsingTree(r.uri, r.docId)
        ctx.contentResolver.query(doc, arrayOf(Document.COLUMN_LAST_MODIFIED), null, null, null)
            ?.use { if (it.moveToFirst() && !it.isNull(0)) it.getLong(0) else -1L } ?: -1L
    } catch (e: Exception) {
        -1L
    }

    /** Adiciona uma pasta escolhida no SAF. Retorna o id, ou -1 se já existia. */
    fun addLocal(ctx: Context, tree: Uri): Long {
        val cr = ctx.contentResolver
        cr.takePersistableUriPermission(tree, FLAGS)
        val db = AppDb.get(ctx).writableDatabase
        db.rawQuery("SELECT id FROM roots WHERE uri=?", arrayOf(tree.toString())).use {
            if (it.moveToFirst()) {
                // Já existe mas é protegida: não diz "já adicionada" (seria uma pista dela).
                val id = it.getLong(0)
                return if (get(ctx, id)?.let { r -> Vault.isHiddenRoot(ctx, r) } == true) id else -1
            }
        }
        val docId = DocumentsContract.getTreeDocumentId(tree)
        val name = displayName(ctx, tree, docId)
        val v = ContentValues().apply {
            put("source", LOCAL)
            put("uri", tree.toString())
            put("doc_id", docId)
            put("name", name)
            put("alias", name)
            put("created", System.currentTimeMillis())
        }
        return db.insert("roots", null, v)
    }

    /**
     * Adiciona uma pasta pelo caminho ("Acesso a todos os arquivos"). [name]: nome a exibir
     * (a raiz de um volume se chama "0" no disco). Retorna o id, ou -1 se já existia.
     */
    fun addFile(ctx: Context, dir: java.io.File, name: String = dir.name): Long {
        val uri = Uri.fromFile(dir).toString()
        val db = AppDb.get(ctx).writableDatabase
        db.rawQuery("SELECT id FROM roots WHERE uri=?", arrayOf(uri)).use {
            if (it.moveToFirst()) {
                val id = it.getLong(0)
                return if (get(ctx, id)?.let { r -> Vault.isHiddenRoot(ctx, r) } == true) id else -1
            }
        }
        val v = ContentValues().apply {
            put("source", FILE)
            put("uri", uri)
            put("doc_id", dir.absolutePath)
            put("name", name)
            put("alias", name)
            put("created", System.currentTimeMillis())
        }
        return db.insert("roots", null, v)
    }

    /**
     * Adiciona uma pasta do Drive ("root" = Meu Drive). Rede: chamar fora da main.
     * Retorna o id, ou -1 se já existia.
     */
    fun addDrive(ctx: Context, folderId: String, name: String): Long {
        val id = if (folderId == "root") DriveApi.rootId(ctx) else folderId
        val uri = "drive://folder/$id"
        val db = AppDb.get(ctx).writableDatabase
        db.rawQuery("SELECT id FROM roots WHERE uri=?", arrayOf(uri)).use {
            if (it.moveToFirst()) return -1
        }
        val v = ContentValues().apply {
            put("source", DRIVE)
            put("uri", uri)
            put("doc_id", id)
            put("name", name)
            put("alias", name)
            put("created", System.currentTimeMillis())
        }
        return db.insert("roots", null, v)
    }

    /** Readicionar: troca a Uri de uma pasta que ficou indisponível. */
    fun replaceUri(ctx: Context, id: Long, tree: Uri) {
        val old = get(ctx, id) ?: return
        ctx.contentResolver.takePersistableUriPermission(tree, FLAGS)
        // Mesma pasta (ex.: readicionada só para liberar a escrita): o índice continua valendo.
        if (old.uri == tree) return
        val docId = DocumentsContract.getTreeDocumentId(tree)
        val v = ContentValues().apply {
            put("uri", tree.toString())
            put("doc_id", docId)
            put("name", displayName(ctx, tree, docId))
        }
        v.put("item_count", -1)
        AppDb.get(ctx).writableDatabase.update("roots", v, "id=?", arrayOf(id.toString()))
        // Ids de documento podem mudar com a nova Uri: o cache antigo não vale mais.
        ListingCache(ctx).clearRoot(id)
        if (old.uri != tree) releaseIfUnused(ctx, old.uri)
    }

    fun rename(ctx: Context, id: Long, alias: String) {
        val v = ContentValues(1).apply { put("alias", alias) }
        AppDb.get(ctx).writableDatabase.update("roots", v, "id=?", arrayOf(id.toString()))
    }

    /** Remove do app (arquivos do usuário nunca são tocados) e libera a permissão. */
    fun remove(ctx: Context, id: Long) {
        if (id == RECENT_ID || id == DRIVE_RECENT_ID) return setVirtual(ctx, id, false)
        val r = get(ctx, id) ?: return
        val db = AppDb.get(ctx).writableDatabase
        db.delete("roots", "id=?", arrayOf(id.toString()))
        db.delete("folder_prefs", "folder_key LIKE ?", arrayOf("$id:%"))
        ListingCache(ctx).clearRoot(id)
        if (!r.isDrive && !r.isFile) releaseIfUnused(ctx, r.uri)
        // Era a pasta inicial: o app volta a abrir na lista de pastas.
        val p = Prefs.get(ctx)
        if (p.startRoot == id) p.setStart(0L)
    }

    /** Permissão ainda persistida e a pasta ainda existe. */
    fun checkAvailable(ctx: Context, r: Root): Boolean {
        // Drive: basta a conta estar conectada (sem rede, o cache continua navegável).
        if (r.isDrive) return DriveAuth.connected(ctx)
        if (r.isFile) return hasAllFiles() && java.io.File(r.docId).isDirectory
        if (r.isRecent) return hasAllFiles()
        val cr = ctx.contentResolver
        if (cr.persistedUriPermissions.none { it.uri == r.uri && it.isReadPermission }) return false
        return try {
            val doc = DocumentsContract.buildDocumentUriUsingTree(r.uri, r.docId)
            cr.query(doc, arrayOf(Document.COLUMN_DOCUMENT_ID), null, null, null)
                ?.use { it.moveToFirst() } ?: false
        } catch (e: Exception) {
            false
        }
    }

    /** Desconectar do Drive: revoga o acesso e apaga índice/arquivos baixados do Drive. Fora da main. */
    fun driveSignedOut(ctx: Context) {
        DriveAuth.signOut(ctx)
        val cache = ListingCache(ctx)
        for (r in allIncludingProtected(ctx)) if (r.isDrive) {
            cache.clearRoot(r.id)
            setCount(ctx, r.id, -1)
            setIndexedAt(ctx, r.id, 0)
        }
        java.io.File(ctx.cacheDir, "drive_open").deleteRecursively()
        app.index.files.data.drive.DriveChanges.reset(ctx)
        app.index.files.thumbs.ThumbnailManager.get(ctx).clearDrive()
    }

    fun source(ctx: Context, r: Root): FileSource =
        when {
            r.isDriveRecent -> app.index.files.data.drive.DriveRecentSource(ctx)
            r.isDrive -> DriveSource(ctx)
            r.isFile -> FsSource(ctx)
            r.isRecent -> RecentSource(ctx)
            else -> LocalSource(ctx.contentResolver, r.uri)
        }

    private fun releaseIfUnused(ctx: Context, uri: Uri) {
        val inUse = AppDb.get(ctx).readableDatabase
            .rawQuery("SELECT 1 FROM roots WHERE uri=?", arrayOf(uri.toString()))
            .use { it.moveToFirst() }
        if (inUse) return
        try {
            ctx.contentResolver.releasePersistableUriPermission(uri, FLAGS)
        } catch (_: SecurityException) {
            // Já não tínhamos a permissão (revogada): nada a liberar.
        }
    }

    private fun displayName(ctx: Context, tree: Uri, docId: String): String {
        val fallback = docId.substringAfterLast(':').substringAfterLast('/').ifEmpty { docId }
        return try {
            val doc = DocumentsContract.buildDocumentUriUsingTree(tree, docId)
            ctx.contentResolver.query(doc, arrayOf(Document.COLUMN_DISPLAY_NAME), null, null, null)
                ?.use { if (it.moveToFirst()) it.getString(0) else null } ?: fallback
        } catch (e: Exception) {
            fallback
        }
    }
}
