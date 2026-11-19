package app.index.files.data.drive

import android.content.Context
import android.os.SystemClock
import android.util.Log
import app.index.files.data.FileItem
import app.index.files.data.NotEditable
import app.index.files.data.Prefs
import app.index.files.data.RecentSource
import app.index.files.data.Roots
import app.index.files.data.VaultIndex
import app.index.files.security.Vault

/**
 * Pasta virtual "Recentes do Drive": arquivos criados ou modificados na nuvem nos últimos
 * dias, com os mesmos filtros (tipo e período) da Recentes do aparelho. Lista plana, sem
 * subpastas; abrir, miniaturas, compartilhar, renomear, mover e apagar funcionam como em
 * [DriveSource]. Arquivos dentro de pastas protegidas do Drive ficam de fora.
 */
class DriveRecentSource(ctx: Context) : DriveSource(ctx) {
    private val app = ctx.applicationContext

    override fun listChildren(folderId: String): List<FileItem> {
        if (folderId != Roots.DRIVE_RECENT) return emptyList()
        val p = Prefs.get(app)
        val since = System.currentTimeMillis() - p.recentDays.coerceIn(1, 3650) * 86_400_000L
        val t0 = SystemClock.elapsedRealtime()
        val found = DriveApi.recent(app, since, typeFilter(p.recentType), RecentSource.LIMIT)
        val t1 = SystemClock.elapsedRealtime()
        val blocked = Guard.blocked(app)
        Log.i("Drive", "recentes: ${found.size} itens, consulta ${t1 - t0} ms, protegidas ${SystemClock.elapsedRealtime() - t1} ms")
        if (blocked.isEmpty()) return found.map { it.first }
        return found.filter { (_, parents) -> parents.none { it in blocked } }.map { it.first }
    }

    // Os itens não têm atalhos (ficam de fora da consulta): editar mexe no próprio arquivo.
    override fun rename(parent: String, item: FileItem, name: String): FileItem = DriveApi.rename(app, item.id, name)

    override fun delete(parent: String, item: FileItem) = DriveApi.trash(app, item.id)

    override fun moveWithin(item: FileItem, from: String, to: String, name: String): FileItem {
        val real = DriveApi.parents(app, item.id).firstOrNull() ?: throw NotEditable("arquivo sem pasta")
        return DriveApi.move(app, item.id, real, to, name)
    }

    /** Trecho da consulta do Drive para o filtro de tipo (com "and" na frente). */
    private fun typeFilter(type: Int): String = when (type) {
        RecentSource.IMAGES -> " and mimeType contains 'image/'"
        RecentSource.VIDEOS -> " and mimeType contains 'video/'"
        RecentSource.AUDIO -> " and mimeType contains 'audio/'"
        RecentSource.DOCS -> " and (mimeType contains 'text/' or mimeType='application/pdf' or " +
            "mimeType contains 'officedocument' or mimeType contains 'application/vnd.ms-' or " +
            "mimeType='application/msword' or mimeType contains 'opendocument' or " +
            "mimeType='application/vnd.google-apps.document' or " +
            "mimeType='application/vnd.google-apps.spreadsheet' or " +
            "mimeType='application/vnd.google-apps.presentation')"
        RecentSource.APKS -> " and mimeType='${RecentSource.APK_MIME}'"
        else -> ""
    }

    /**
     * Pastas do Drive dentro das protegidas (elas inclusive): uma consulta por pasta da árvore
     * protegida, que costuma ser pequena, em vez de subir pelos pais de cada arquivo recente.
     * Guardado por alguns minutos; recalculado quando as protegidas mudam.
     */
    private object Guard {
        private const val TTL_MS = 10 * 60_000L
        private var key = ""
        private var at = 0L
        private var cached: Set<String> = emptySet()

        @Synchronized
        fun blocked(ctx: Context): Set<String> {
            val es = Vault.entries(ctx).filter { it.tag == Roots.DRIVE }
            if (es.isEmpty()) return emptySet()
            val k = es.joinToString("|") { it.id + ":" + it.folderId }
            val now = System.currentTimeMillis()
            if (k == key && now - at < TTL_MS) return cached
            val out = HashSet<String>()
            for (e in es) out += VaultIndex.folderIds(ctx, e)
            val queue = ArrayDeque(es.map { it.folderId })
            val seen = HashSet<String>()
            while (queue.isNotEmpty() && seen.size < 5000) {
                val f = queue.removeFirst()
                if (!seen.add(f)) continue
                out += f
                for (sub in DriveApi.folders(ctx, f)) queue.add(sub.id)
            }
            key = k
            at = now
            cached = out
            return out
        }
    }
}
