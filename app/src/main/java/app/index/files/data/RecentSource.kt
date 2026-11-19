package app.index.files.data

import android.content.ContentResolver
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import app.index.files.security.Vault
import java.io.File
import java.io.FileNotFoundException

/**
 * Pasta virtual "Recentes": arquivos que entraram no aparelho nos últimos dias (7, 30 ou 90)
 * (downloads, fotos, capturas de tela, documentos, APKs...), vindos do índice de mídia do
 * Android (MediaStore.Files). Com "Acesso a todos os arquivos" ele lista todos os tipos.
 * Lista plana, sem subpastas. Os itens são arquivos pelo caminho, então abrir, miniaturas,
 * compartilhar, renomear, copiar, mover e apagar funcionam como em [FsSource].
 * Ficam de fora: pastas protegidas, Android/data e obb, a lixeira e arquivos pendentes.
 */
class RecentSource(ctx: Context, private val fs: FsSource = FsSource(ctx)) : FileSource by fs {
    private val app = ctx.applicationContext

    override val cacheTag: String = Roots.FILE // mesmos ids (caminhos) e miniaturas das pastas pelo caminho

    override fun listChildren(folderId: String): List<FileItem> {
        // Só a raiz virtual tem conteúdo (não há subpastas).
        if (folderId != Roots.RECENT) return emptyList()
        if (!Roots.hasAllFiles()) throw FileNotFoundException("sem acesso a todos os arquivos")
        val p = Prefs.get(app)
        val cutoff = System.currentTimeMillis() / 1000 - p.recentDays.coerceIn(1, 3650) * 86_400L
        val uri = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)
        val proj = arrayOf(
            MediaStore.Files.FileColumns.DATA,
            MediaStore.Files.FileColumns.MIME_TYPE,
            MediaStore.Files.FileColumns.SIZE,
        )
        // Pastas têm mime nulo; data de entrada OU de modificação recente.
        val sel0 = "${MediaStore.Files.FileColumns.MIME_TYPE} IS NOT NULL AND " +
            "(${MediaStore.Files.FileColumns.DATE_ADDED} >= ? OR ${MediaStore.Files.FileColumns.DATE_MODIFIED} >= ?)"
        val (typeSel, typeArgs) = typeFilter(p.recentType)
        val sel = sel0 + typeSel
        val args = arrayOf(cutoff.toString(), cutoff.toString()) + typeArgs
        val order = "${MediaStore.Files.FileColumns.DATE_ADDED} DESC"
        val c = if (Build.VERSION.SDK_INT >= 30) {
            val q = Bundle().apply {
                putString(ContentResolver.QUERY_ARG_SQL_SELECTION, sel)
                putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, args)
                putString(ContentResolver.QUERY_ARG_SQL_SORT_ORDER, order)
                putInt(ContentResolver.QUERY_ARG_LIMIT, LIMIT * 2) // folga para os filtros abaixo
            }
            app.contentResolver.query(uri, proj, q, null)
        } else {
            app.contentResolver.query(uri, proj, sel, args, "$order LIMIT ${LIMIT * 2}")
        } ?: throw FileNotFoundException("índice de mídia indisponível")
        val blocked = Vault.protectedPaths(app)
        val out = ArrayList<FileItem>(minOf(c.count, LIMIT))
        c.use {
            while (it.moveToNext() && out.size < LIMIT) {
                val path = it.getString(0) ?: continue
                if (skip(path, blocked)) continue
                val f = File(path)
                val mod = f.lastModified()
                if (mod <= 0) continue // apagado depois de indexado
                val mime = it.getString(1) ?: "application/octet-stream"
                out.add(FileItem(id = path, name = f.name, mime = mime, size = if (it.isNull(2)) f.length() else it.getLong(2), modified = mod, isDir = false))
            }
        }
        return out
    }

    private fun skip(path: String, blocked: List<String>): Boolean {
        if (APP_DATA.containsMatchIn(path)) return true
        // Arquivos dentro de pastas ocultas (.thumbnails, .Statuses, caches): só os do próprio nome com ponto
        // seguem a opção "mostrar ocultos" na exibição; os de pastas ocultas ficam sempre de fora.
        if (path.substringBeforeLast('/').contains("/.")) return true
        return blocked.any { path.startsWith(it) }
    }

    // A pasta virtual não recebe arquivos novos (colar, criar pasta, enviar): a UI esconde essas ações.

    /** Trecho SQL (com AND na frente) e argumentos do filtro de tipo. */
    private fun typeFilter(type: Int): Pair<String, Array<String>> {
        val mt = MediaStore.Files.FileColumns.MEDIA_TYPE
        val mime = MediaStore.Files.FileColumns.MIME_TYPE
        return when (type) {
            IMAGES -> " AND $mt = ?" to arrayOf(MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE.toString())
            VIDEOS -> " AND $mt = ?" to arrayOf(MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO.toString())
            AUDIO -> " AND $mt = ?" to arrayOf(MediaStore.Files.FileColumns.MEDIA_TYPE_AUDIO.toString())
            // Documentos: o tipo do Android (API 30+) e, por garantia, texto e PDFs/Office pelo mime.
            DOCS -> " AND ($mt = ? OR $mime LIKE 'text/%' OR $mime = 'application/pdf' OR " +
                "$mime LIKE 'application/vnd.openxmlformats%' OR $mime LIKE 'application/vnd.ms-%' OR " +
                "$mime LIKE 'application/vnd.oasis.opendocument%' OR $mime = 'application/msword')" to
                arrayOf(MediaStore.Files.FileColumns.MEDIA_TYPE_DOCUMENT.toString())
            APKS -> " AND $mime = ?" to arrayOf(APK_MIME)
            else -> "" to emptyArray()
        }
    }

    companion object {
        const val LIMIT = 500

        // Filtro de tipo (Prefs.recentType).
        const val ALL = 0
        const val IMAGES = 1
        const val VIDEOS = 2
        const val AUDIO = 3
        const val DOCS = 4
        const val APKS = 5
        const val APK_MIME = "application/vnd.android.package-archive"
        val DAYS_OPTIONS = intArrayOf(7, 30, 90)

        /** Nome do tipo filtrado (null = todos). */
        fun typeLabel(type: Int): Int? = when (type) {
            IMAGES -> app.index.files.R.string.recent_images
            VIDEOS -> app.index.files.R.string.recent_videos
            AUDIO -> app.index.files.R.string.recent_audio
            DOCS -> app.index.files.R.string.recent_docs
            APKS -> app.index.files.R.string.recent_apks
            else -> null
        }

        /** Descrição da pasta com o filtro atual ("Fotos · últimos 7 dias"). */
        fun describe(ctx: Context, drive: Boolean = false): String {
            val p = Prefs.get(ctx)
            val t = typeLabel(p.recentType)
            return if (t == null) ctx.getString(if (drive) app.index.files.R.string.drive_recent_meta else app.index.files.R.string.recent_meta, p.recentDays)
            else ctx.getString(app.index.files.R.string.recent_meta_type, ctx.getString(t), p.recentDays)
        }
        private val APP_DATA = Regex("/Android/(data|obb)/")
    }
}
