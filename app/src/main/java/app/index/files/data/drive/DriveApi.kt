package app.index.files.data.drive

import android.content.Context
import android.os.CancellationSignal
import android.util.Log
import app.index.files.data.FileItem
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.Instant
import kotlin.random.Random

/** Resposta HTTP de erro que não vale a pena repetir. */
class DriveHttpError(val code: Int, msg: String) : IOException("HTTP $code: $msg")

/**
 * Cliente mínimo da API REST v3 do Drive (HttpURLConnection + org.json).
 * 401 ⇒ renova o token uma vez; 403 de cota / 429 / 5xx ⇒ backoff exponencial com jitter.
 * Tudo bloqueante: chamar fora da main thread.
 */
object DriveApi {
    const val FOLDER = "application/vnd.google-apps.folder"
    private const val SHORTCUT = "application/vnd.google-apps.shortcut"
    private const val BASE = "https://www.googleapis.com/drive/v3/"
    private const val FIELDS =
        "nextPageToken,files(id,name,mimeType,size,modifiedTime,thumbnailLink,shortcutDetails(targetId,targetMimeType))"
    private const val MAX_TRIES = 6
    private const val TAG = "Drive"

    /** Filhos diretos de uma pasta (todas as páginas). */
    fun children(ctx: Context, folderId: String): List<FileItem> =
        list(ctx, "'${esc(folderId)}' in parents and trashed=false")

    /** Só as subpastas (seletor de pastas). */
    fun folders(ctx: Context, parentId: String): List<FileItem> =
        list(ctx, "'${esc(parentId)}' in parents and mimeType='$FOLDER' and trashed=false")

    /** Pastas compartilhadas comigo (nível de cima do seletor). */
    fun sharedFolders(ctx: Context): List<FileItem> =
        list(ctx, "sharedWithMe and mimeType='$FOLDER' and trashed=false")

    /** Id real de "Meu Drive" (o alias "root" não aparece em parents). */
    fun rootId(ctx: Context): String = JSONObject(getText(ctx, BASE + "files/root?fields=id")).getString("id")

    fun email(ctx: Context): String =
        JSONObject(getText(ctx, BASE + "about?fields=user(emailAddress)")).getJSONObject("user").getString("emailAddress")

    /** Link de miniatura atual (os links expiram em algumas horas). */
    fun thumbnailLink(ctx: Context, id: String): String? =
        JSONObject(getText(ctx, BASE + "files/${enc(id)}?fields=thumbnailLink&supportsAllDrives=true"))
            .optString("thumbnailLink").ifEmpty { null }

    fun bytes(ctx: Context, url: String): ByteArray = call(ctx, url) { it.readBytes() }

    /** Baixa o conteúdo para [dest] (arquivo temporário + rename). */
    fun download(ctx: Context, id: String, dest: File, signal: CancellationSignal? = null) {
        val tmp = File(dest.parentFile, dest.name + ".part")
        call(ctx, BASE + "files/${enc(id)}?alt=media&supportsAllDrives=true") { input ->
            tmp.outputStream().use { out ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    signal?.throwIfCanceled()
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                }
            }
        }
        if (!tmp.renameTo(dest)) {
            tmp.delete()
            throw IOException("não foi possível salvar o arquivo")
        }
    }

    /**
     * Arquivos criados ou modificados desde [sinceMs] (pastas Recentes do Drive), mais novos
     * primeiro, até [limit]. [extra]: trecho de consulta do filtro de tipo (com "and" na frente).
     * Atalhos e pastas ficam de fora. Devolve cada item com as pastas pai (para as protegidas).
     */
    fun recent(ctx: Context, sinceMs: Long, extra: String, limit: Int): List<Pair<FileItem, List<String>>> {
        val t = Instant.ofEpochMilli(sinceMs).toString()
        val q = "(modifiedTime > '$t' or createdTime > '$t') and trashed=false and " +
            "mimeType!='$FOLDER' and mimeType!='$SHORTCUT'$extra"
        val out = ArrayList<Pair<FileItem, List<String>>>()
        var page: String? = null
        do {
            val url = BASE + "files?" + query(
                "q" to q,
                "fields" to "nextPageToken,files(id,name,mimeType,size,modifiedTime,thumbnailLink,parents)",
                "orderBy" to "modifiedTime desc",
                "pageSize" to minOf(1000, limit - out.size).toString(),
                "pageToken" to page,
                "supportsAllDrives" to "true",
                "includeItemsFromAllDrives" to "true",
            )
            val json = JSONObject(getText(ctx, url))
            val files = json.optJSONArray("files")
            if (files != null) for (i in 0 until files.length()) {
                val o = files.getJSONObject(i)
                val item = parse(o) ?: continue
                val ps = o.optJSONArray("parents")
                out.add(item to if (ps == null) emptyList() else List(ps.length()) { ps.getString(it) })
            }
            page = json.optString("nextPageToken").ifEmpty { null }
        } while (page != null && out.size < limit)
        return out
    }

    private fun list(ctx: Context, q: String): List<FileItem> {
        val out = ArrayList<FileItem>()
        var page: String? = null
        do {
            val url = BASE + "files?" + query(
                "q" to q,
                "fields" to FIELDS,
                "pageSize" to "1000",
                "pageToken" to page,
                "supportsAllDrives" to "true",
                "includeItemsFromAllDrives" to "true",
            )
            val json = JSONObject(getText(ctx, url))
            val files = json.optJSONArray("files")
            if (files != null) for (i in 0 until files.length()) parse(files.getJSONObject(i))?.let(out::add)
            page = json.optString("nextPageToken").ifEmpty { null }
        } while (page != null)
        return out
    }

    private fun parse(o: JSONObject): FileItem? {
        var id = o.optString("id").ifEmpty { return null }
        val name = o.optString("name").ifEmpty { return null }
        var mime = o.optString("mimeType", "application/octet-stream")
        if (mime == SHORTCUT) {
            // Atalho: aparece com o nome do atalho, mas abre o alvo.
            val sd = o.optJSONObject("shortcutDetails") ?: return null
            id = sd.optString("targetId").ifEmpty { return null }
            mime = sd.optString("targetMimeType", "application/octet-stream")
        }
        val modified = o.optString("modifiedTime").let { s ->
            if (s.isEmpty()) 0L else runCatching { Instant.parse(s).toEpochMilli() }.getOrDefault(0L)
        }
        return FileItem(
            id = id,
            name = name,
            mime = mime,
            size = o.optString("size").toLongOrNull() ?: 0L,
            modified = modified,
            isDir = mime == FOLDER,
        ).also { it.thumbUrl = o.optString("thumbnailLink").ifEmpty { null } }
    }

    private fun getText(ctx: Context, url: String): String = call(ctx, url) { it.readBytes().toString(Charsets.UTF_8) }

    /** GET autenticado com renovação de token e backoff. */
    private fun <T> call(ctx: Context, url: String, read: (InputStream) -> T): T {
        val c = connect(ctx, url, null)
        try {
            return c.inputStream.use(read)
        } finally {
            c.disconnect()
        }
    }

    /**
     * Conteúdo a partir do byte [from] (streaming). Devolve a conexão aberta;
     * quem chama lê o inputStream e chama disconnect(). Código 416 = fim do arquivo.
     */
    fun openRange(ctx: Context, id: String, from: Long): HttpURLConnection =
        connect(ctx, BASE + "files/${enc(id)}?alt=media&supportsAllDrives=true", from)

    // ---- Edição (fase 7; escopo drive) ----

    private const val ITEM_FIELDS = "id,name,mimeType,size,modifiedTime,thumbnailLink"

    /** Pais diretos do arquivo (um atalho aparece numa pasta que não é pai do alvo). */
    fun parents(ctx: Context, id: String): List<String> {
        val a = JSONObject(getText(ctx, BASE + "files/${enc(id)}?fields=parents&supportsAllDrives=true"))
            .optJSONArray("parents") ?: return emptyList()
        return List(a.length()) { a.getString(it) }
    }

    fun createFolder(ctx: Context, parentId: String, name: String): FileItem {
        val body = JSONObject().put("name", name).put("mimeType", FOLDER)
            .put("parents", org.json.JSONArray().put(parentId))
        return item(send(ctx, "POST", BASE + "files?supportsAllDrives=true&fields=$ITEM_FIELDS", body))
    }

    fun rename(ctx: Context, id: String, name: String): FileItem =
        item(send(ctx, "PATCH", BASE + "files/${enc(id)}?supportsAllDrives=true&fields=$ITEM_FIELDS", JSONObject().put("name", name)))

    /** Apagar no Drive = mover para a lixeira (recuperável por 30 dias). */
    fun trash(ctx: Context, id: String) {
        send(ctx, "PATCH", BASE + "files/${enc(id)}?supportsAllDrives=true&fields=id", JSONObject().put("trashed", true))
    }

    fun move(ctx: Context, id: String, fromId: String, toId: String, name: String): FileItem {
        val url = BASE + "files/${enc(id)}?" + query(
            "addParents" to toId,
            "removeParents" to fromId,
            "supportsAllDrives" to "true",
            "fields" to ITEM_FIELDS,
        )
        return item(send(ctx, "PATCH", url, JSONObject().put("name", name)))
    }

    /** Cópia no próprio servidor (só arquivos; pastas são recriadas por quem chama). */
    fun copy(ctx: Context, id: String, toId: String, name: String): FileItem {
        val body = JSONObject().put("name", name).put("parents", org.json.JSONArray().put(toId))
        return item(send(ctx, "POST", BASE + "files/${enc(id)}/copy?supportsAllDrives=true&fields=$ITEM_FIELDS", body))
    }

    /**
     * Envio retomável: abre a sessão e transmite [size] bytes de [input] num PUT só
     * (sem buffer do arquivo inteiro na memória). [onBytes] recebe o total enviado.
     */
    fun upload(
        ctx: Context, parentId: String, name: String, mime: String, size: Long,
        input: InputStream, cancelled: () -> Boolean, onBytes: (Long) -> Unit,
    ): FileItem {
        val meta = JSONObject().put("name", name).put("parents", org.json.JSONArray().put(parentId))
        val session = connect(
            ctx,
            "https://www.googleapis.com/upload/drive/v3/files?uploadType=resumable&supportsAllDrives=true&fields=$ITEM_FIELDS",
            null,
        ) { c ->
            c.requestMethod = "POST"
            c.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            c.setRequestProperty("X-Upload-Content-Type", mime)
            if (size >= 0) c.setRequestProperty("X-Upload-Content-Length", size.toString())
            writeBody(c, meta)
        }
        val location = try {
            session.getHeaderField("Location") ?: throw IOException("sessão de envio sem endereço")
        } finally {
            session.disconnect()
        }
        val c = URL(location).openConnection() as HttpURLConnection
        try {
            c.requestMethod = "PUT"
            c.doOutput = true
            c.connectTimeout = 15_000
            c.readTimeout = 120_000
            c.setRequestProperty("Authorization", "Bearer ${DriveAuth.token(ctx)}")
            c.setRequestProperty("Content-Type", mime)
            if (size >= 0) c.setFixedLengthStreamingMode(size) else c.setChunkedStreamingMode(256 * 1024)
            c.outputStream.use { out ->
                val buf = ByteArray(256 * 1024)
                var sent = 0L
                while (true) {
                    if (cancelled()) throw java.util.concurrent.CancellationException()
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    sent += n
                    onBytes(sent)
                }
            }
            val code = c.responseCode
            if (code !in 200..299) {
                val body = runCatching { c.errorStream?.use { it.readBytes().toString(Charsets.UTF_8) } }.getOrNull() ?: ""
                throw DriveHttpError(code, body.take(300))
            }
            return item(c.inputStream.use { it.readBytes().toString(Charsets.UTF_8) })
        } finally {
            c.disconnect()
        }
    }

    private fun item(json: String): FileItem =
        parse(JSONObject(json)) ?: throw IOException("resposta inesperada do Drive")

    /** Escrita com corpo JSON (POST/PATCH), com as mesmas regras de token e backoff. */
    private fun send(ctx: Context, method: String, url: String, body: JSONObject): String {
        val c = connect(ctx, url, null) { c ->
            if (method == "PATCH") {
                // HttpURLConnection não aceita PATCH; as APIs do Google aceitam o override.
                c.requestMethod = "POST"
                c.setRequestProperty("X-HTTP-Method-Override", "PATCH")
            } else {
                c.requestMethod = method
            }
            c.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            writeBody(c, body)
        }
        try {
            return c.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
        } finally {
            c.disconnect()
        }
    }

    private fun writeBody(c: HttpURLConnection, body: JSONObject) {
        val bytes = body.toString().toByteArray(Charsets.UTF_8)
        c.doOutput = true
        c.setFixedLengthStreamingMode(bytes.size)
        c.outputStream.use { it.write(bytes) }
    }

    /** Conexão com resposta 2xx (ou 416 num Range), já com renovação de token e backoff. */
    private fun connect(
        ctx: Context, url: String, rangeFrom: Long?,
        prepare: ((HttpURLConnection) -> Unit)? = null,
    ): HttpURLConnection {
        var refreshed = false
        var attempt = 0
        while (true) {
            val token = DriveAuth.token(ctx)
            val c = URL(url).openConnection() as HttpURLConnection
            c.connectTimeout = 15_000
            c.readTimeout = 30_000
            c.setRequestProperty("Authorization", "Bearer $token")
            if (prepare != null) {
                c.setRequestProperty("User-Agent", "IndexFileManager/0.7 (gzip)")
                prepare(c)
            } else if (rangeFrom != null) {
                // Bytes crus: com gzip as posições do Range não batem.
                c.setRequestProperty("Range", "bytes=$rangeFrom-")
                c.setRequestProperty("Accept-Encoding", "identity")
                c.setRequestProperty("User-Agent", "IndexFileManager/0.5")
            } else {
                // O Google só comprime se o User-Agent tiver "gzip"; o HttpURLConnection descomprime sozinho.
                c.setRequestProperty("User-Agent", "IndexFileManager/0.5 (gzip)")
            }
            var ok = false
            try {
                val code = c.responseCode
                if (code in 200..299 || (code == 416 && rangeFrom != null)) {
                    ok = true
                    return c
                }
                val body = runCatching { c.errorStream?.use { it.readBytes().toString(Charsets.UTF_8) } }.getOrNull() ?: ""
                val retryable = code == 429 || code >= 500 ||
                    (code == 403 && ("rateLimitExceeded" in body || "userRateLimitExceeded" in body))
                when {
                    code == 401 && !refreshed -> {
                        refreshed = true
                        DriveAuth.invalidate(ctx, token)
                    }
                    code == 401 -> throw AuthRequired()
                    code == 403 && "insufficient" in body.lowercase() -> throw AuthRequired()
                    retryable && attempt < MAX_TRIES - 1 -> {
                        val wait = (500L shl attempt) + Random.nextLong(0, 500)
                        Log.w(TAG, "HTTP $code, nova tentativa em $wait ms")
                        attempt++
                        Thread.sleep(wait)
                    }
                    else -> throw DriveHttpError(code, body.take(300))
                }
            } finally {
                if (!ok) c.disconnect()
            }
        }
    }

    private fun esc(s: String) = s.replace("\\", "\\\\").replace("'", "\\'")
    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")
    private fun query(vararg p: Pair<String, String?>) =
        p.filter { it.second != null }.joinToString("&") { "${it.first}=${enc(it.second!!)}" }
}
