package app.index.files.web

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaScannerConnection
import android.media.ThumbnailUtils
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.os.storage.StorageManager
import android.util.Log
import android.util.Size
import android.webkit.MimeTypeMap
import app.index.files.security.Vault
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Servidor HTTP mínimo (sem biblioteca) para o acesso pelo navegador do computador na rede
 * local. Mostra o armazenamento todo (interno e cartão), menos as pastas protegidas do Index.
 *
 * Segurança: senha fixa (PBKDF2, com espera após 5 erros), sessão por cookie HttpOnly e
 * SameSite=Strict, cabeçalho próprio obrigatório nas ações que mudam arquivos (barra pedidos de
 * outros sites), só aceita Host com endereço IP (contra DNS rebinding), caminhos sempre
 * resolvidos dentro do volume e arquivos do celular abertos no navegador só em tipos inofensivos
 * (HTML e afins vão como download, nunca rodam na página). Nada de nomes de arquivos no log.
 */
class WebServer(
    private val app: Context,
    private val checkPassword: (CharArray) -> Long,
    private val onActivity: () -> Unit,
) {
    private var server: ServerSocket? = null
    private val pool = Executors.newCachedThreadPool()
    private val open = AtomicInteger()
    /** Conexões abertas: ao desligar, todas são fechadas na hora (mesmo no meio de um envio). */
    private val sockets: MutableSet<Socket> = ConcurrentHashMap.newKeySet()
    @Volatile private var stopped = false
    private val sessions = ConcurrentHashMap<String, Long>()
    private val thumbGate = Semaphore(3)
    /** Miniaturas prontas (JPEG) em memória, até 12 MB; some quando o servidor desliga. */
    private val thumbCache = object : android.util.LruCache<String, ByteArray>(12 * 1024 * 1024) {
        override fun sizeOf(key: String, value: ByteArray) = value.size
    }
    private val rnd = SecureRandom()

    @Volatile var port = 0
        private set

    fun start(preferred: Int): Int {
        var last: IOException? = null
        for (p in preferred until preferred + 10) {
            try {
                val s = ServerSocket(p, 50, null as InetAddress?)
                s.reuseAddress = true
                server = s
                port = p
                break
            } catch (e: IOException) {
                last = e
            }
        }
        val s = server ?: throw last ?: IOException("sem porta")
        Thread({ acceptLoop(s) }, "index-web").start()
        return port
    }

    fun stop() {
        stopped = true
        runCatching { server?.close() }
        server = null
        sessions.clear()
        // Fechar o ServerSocket só para de aceitar; as conexões keep-alive e as transferências em
        // curso seguiriam vivas. Fecha cada uma para o navegador perder o acesso na hora.
        for (c in sockets.toList()) runCatching { c.close() }
        sockets.clear()
        pool.shutdownNow()
    }

    private fun acceptLoop(s: ServerSocket) {
        while (!s.isClosed) {
            val c = try { s.accept() } catch (_: IOException) { break }
            if (stopped || open.get() >= MAX_CONN) {
                runCatching { c.close() }
                continue
            }
            open.incrementAndGet()
            sockets.add(c)
            try {
                pool.execute {
                    try {
                        serve(c)
                    } finally {
                        open.decrementAndGet()
                        sockets.remove(c)
                        runCatching { c.close() }
                    }
                }
            } catch (_: java.util.concurrent.RejectedExecutionException) {
                open.decrementAndGet()
                sockets.remove(c)
                runCatching { c.close() }
            }
        }
    }

    // ---------------------------------------------------------------- HTTP

    private class Req(
        val method: String,
        val path: String,
        val query: Map<String, List<String>>,
        val headers: Map<String, String>,
        val input: InputStream,
        val length: Long,
    ) {
        fun q(k: String) = query[k]?.firstOrNull()
        fun cookie(k: String): String? = headers["cookie"]?.split(';')?.map { it.trim() }
            ?.firstOrNull { it.startsWith("$k=") }?.substringAfter('=')
        var consumed = 0L
            private set
        fun body(max: Int): ByteArray {
            if (length > max) throw HttpError(413, "grande demais")
            val b = ByteArray(length.toInt())
            var o = 0
            while (o < b.size) {
                val n = input.read(b, o, b.size - o)
                if (n < 0) throw IOException("fim")
                o += n
            }
            consumed = length
            return b
        }
        fun json(): JSONObject = JSONObject(String(body(1 shl 20), Charsets.UTF_8))
        fun markConsumed() { consumed = length }
        fun skipRest() {
            var left = length - consumed
            val buf = ByteArray(8192)
            while (left > 0) {
                val n = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                if (n < 0) break
                left -= n
            }
            consumed = length
        }
    }

    private class HttpError(val code: Int, msg: String) : Exception(msg)

    /** Resposta já enviada; [keep] = a conexão pode seguir para o próximo pedido. */
    private class Sent(val keep: Boolean)

    private fun serve(c: Socket) {
        c.soTimeout = 60_000
        c.tcpNoDelay = true
        val input = BufferedInputStream(c.getInputStream(), 16384)
        val out = c.getOutputStream().buffered(32768)
        while (!stopped) {
            val req = try { readRequest(input) } catch (_: Exception) { return } ?: return
            val sent = try {
                onActivity()
                route(req, out)
            } catch (e: HttpError) {
                // Corpo grande ainda não lido (envio recusado): responde e fecha, em vez de ler tudo.
                if (req.length - req.consumed > 65536) {
                    runCatching { errorClose(out, e) }
                    return
                }
                req.skipRest()
                json(out, e.code, JSONObject().put("error", e.message))
            } catch (e: SocketException) {
                return
            } catch (e: IOException) {
                return
            } catch (e: Exception) {
                Log.w(TAG, "erro no pedido ${req.method} ${req.path}", e)
                runCatching { json(out, 500, JSONObject().put("error", "erro interno")) }.getOrNull() ?: return
            }
            out.flush()
            if (!sent.keep) return
            req.skipRest()
        }
    }

    private fun readRequest(input: InputStream): Req? {
        val head = ByteArrayOutputStream(1024)
        var state = 0
        while (state < 4) {
            val b = input.read()
            if (b < 0) return null
            head.write(b)
            if (head.size() > 16384) throw HttpError(431, "cabeçalho grande")
            state = when {
                b == '\r'.code && (state == 0 || state == 2) -> state + 1
                b == '\n'.code && (state == 1 || state == 3) -> state + 1
                else -> 0
            }
        }
        val lines = head.toString("ISO-8859-1").split("\r\n")
        val first = lines[0].split(' ')
        if (first.size < 3) throw HttpError(400, "pedido inválido")
        val headers = HashMap<String, String>()
        for (l in lines.drop(1)) {
            val i = l.indexOf(':')
            if (i > 0) headers[l.substring(0, i).trim().lowercase(Locale.US)] = l.substring(i + 1).trim()
        }
        val target = first[1]
        val path = target.substringBefore('?')
        val query = HashMap<String, MutableList<String>>()
        if ('?' in target) {
            for (kv in target.substringAfter('?').split('&')) {
                if (kv.isEmpty()) continue
                val k = dec(kv.substringBefore('='))
                val v = dec(kv.substringAfter('=', ""))
                query.getOrPut(k) { ArrayList() }.add(v)
            }
        }
        val len = headers["content-length"]?.toLongOrNull() ?: 0L
        if (headers["transfer-encoding"]?.contains("chunked", true) == true) throw HttpError(411, "sem tamanho")
        return Req(first[0].uppercase(Locale.US), dec(path), query, headers, input, len)
    }

    private fun dec(s: String) = URLDecoder.decode(s.replace("+", "%2B"), "UTF-8")

    private fun head(out: OutputStream, code: Int, type: String?, length: Long, extra: List<Pair<String, String>> = emptyList(), keep: Boolean = true) {
        val sb = StringBuilder()
        sb.append("HTTP/1.1 ").append(code).append(' ').append(reason(code)).append("\r\n")
        if (type != null) sb.append("Content-Type: ").append(type).append("\r\n")
        if (length >= 0) sb.append("Content-Length: ").append(length).append("\r\n")
        sb.append("X-Content-Type-Options: nosniff\r\n")
        sb.append("Referrer-Policy: no-referrer\r\n")
        sb.append("X-Frame-Options: SAMEORIGIN\r\n")
        for ((k, v) in extra) sb.append(k).append(": ").append(v).append("\r\n")
        if (extra.none { it.first == "Cache-Control" }) sb.append("Cache-Control: no-store\r\n")
        sb.append(if (keep && length >= 0) "Connection: keep-alive\r\n" else "Connection: close\r\n")
        sb.append("\r\n")
        out.write(sb.toString().toByteArray(Charsets.UTF_8))
    }

    private fun errorClose(out: OutputStream, e: HttpError) {
        val b = JSONObject().put("error", e.message).toString().toByteArray(Charsets.UTF_8)
        head(out, e.code, "application/json; charset=utf-8", b.size.toLong(), keep = false)
        out.write(b)
        out.flush()
    }

    private fun bytes(out: OutputStream, code: Int, type: String, b: ByteArray, extra: List<Pair<String, String>> = emptyList()): Sent {
        head(out, code, type, b.size.toLong(), extra)
        out.write(b)
        return Sent(true)
    }

    private fun json(out: OutputStream, code: Int, o: Any, extra: List<Pair<String, String>> = emptyList()): Sent =
        bytes(out, code, "application/json; charset=utf-8", o.toString().toByteArray(Charsets.UTF_8), extra)

    private fun ok(out: OutputStream) = json(out, 200, JSONObject().put("ok", true))

    private fun reason(code: Int) = when (code) {
        200 -> "OK"; 206 -> "Partial Content"; 304 -> "Not Modified"; 400 -> "Bad Request"; 401 -> "Unauthorized"
        403 -> "Forbidden"; 404 -> "Not Found"; 405 -> "Method Not Allowed"; 409 -> "Conflict"; 411 -> "Length Required"
        413 -> "Payload Too Large"; 416 -> "Range Not Satisfiable"; 429 -> "Too Many Requests"; 431 -> "Header Too Large"
        507 -> "Insufficient Storage"
        else -> "Error"
    }

    // ---------------------------------------------------------------- rotas

    private fun route(r: Req, out: OutputStream): Sent {
        // Só endereço IP, localhost ou o nome fixo index.local no Host: um site de fora não consegue
        // apontar o nome dele para cá (.local só existe na rede local, respondido pelo próprio celular).
        val host = r.headers["host"].orEmpty().substringBeforeLast(':').trim('[', ']')
        if (!isIpHost(host)) throw HttpError(403, "host")

        if (r.method == "GET" && (r.path == "/" || r.path == "/index.html")) return page(out)
        if (r.method == "POST" && r.path == "/api/login") return login(r, out)
        // Tela de senha: só o nome do aparelho, para saber em qual se está entrando.
        if (r.method == "GET" && r.path == "/api/hello") return json(out, 200, JSONObject().put("device", deviceName()).put("model", model()))

        val token = r.cookie(COOKIE)
        val authed = token != null && sessions[token]?.let { System.currentTimeMillis() - it < SESSION_MS } == true
        if (!authed) {
            r.skipRest()
            return json(out, 401, JSONObject().put("error", "login"))
        }
        sessions[token!!] = System.currentTimeMillis()
        val mutating = r.method != "GET"
        // Pedidos de outros sites não conseguem mandar este cabeçalho sem permissão (CORS).
        if (mutating && r.headers["x-index"] != "1") throw HttpError(403, "origem")

        return when (r.method to r.path) {
            "GET" to "/api/info" -> info(out)
            "GET" to "/api/list" -> list(r, out)
            "GET" to "/api/file" -> file(r, out)
            "GET" to "/api/thumb" -> thumb(r, out)
            "GET" to "/api/zip" -> zip(r, out)
            "GET" to "/api/search" -> search(r, out)
            "GET" to "/api/stats" -> stats(r, out)
            "GET" to "/api/kind" -> kind(r, out)
            "PUT" to "/api/upload" -> upload(r, out)
            "POST" to "/api/mkdir" -> mkdir(r, out)
            "POST" to "/api/rename" -> rename(r, out)
            "POST" to "/api/transfer" -> transfer(r, out)
            "POST" to "/api/delete" -> delete(r, out)
            "POST" to "/api/logout" -> {
                r.skipRest()
                sessions.remove(token)
                json(out, 200, JSONObject().put("ok", true), listOf("Set-Cookie" to "$COOKIE=; Path=/; Max-Age=0; HttpOnly; SameSite=Strict"))
            }
            else -> throw HttpError(404, "não encontrado")
        }
    }

    private fun isIpHost(h: String): Boolean {
        if (h == "localhost" || h.equals(MdnsResponder.HOST, true)) return true
        if (h.matches(Regex("""\d{1,3}(\.\d{1,3}){3}"""))) return true
        return ':' in h && h.all { it.isLetterOrDigit() || it == ':' || it == '.' || it == '%' }
    }

    private fun page(out: OutputStream): Sent {
        val b = app.assets.open("web/index.html").use { it.readBytes() }
        return bytes(
            out, 200, "text/html; charset=utf-8", b,
            listOf(
                "Content-Security-Policy" to "default-src 'self'; img-src 'self' blob: data:; media-src 'self' blob:; style-src 'unsafe-inline'; script-src 'unsafe-inline'; frame-src 'self'; frame-ancestors 'none'; form-action 'none'",
            ),
        )
    }

    private fun login(r: Req, out: OutputStream): Sent {
        val pw = r.json().optString("password").toCharArray()
        val wait = checkPassword(pw)
        if (wait != 0L) {
            val o = JSONObject().put("error", if (wait > 0) "wait" else "wrong")
            if (wait > 0) o.put("wait", (wait + 999) / 1000)
            return json(out, if (wait > 0) 429 else 401, o)
        }
        val t = ByteArray(32).also { rnd.nextBytes(it) }.joinToString("") { "%02x".format(it) }
        sessions[t] = System.currentTimeMillis()
        return json(out, 200, JSONObject().put("ok", true), listOf("Set-Cookie" to "$COOKIE=$t; Path=/; HttpOnly; SameSite=Strict"))
    }

    // ---------------------------------------------------------------- volumes e caminhos

    private class Vol(val name: String, val dir: File, val removable: Boolean)

    private fun volumes(): List<Vol> {
        val sm = app.getSystemService(StorageManager::class.java)
        val list = ArrayList<Vol>()
        for (v in sm.storageVolumes) {
            if (v.state != Environment.MEDIA_MOUNTED && v.state != Environment.MEDIA_MOUNTED_READ_ONLY) continue
            val dir = (if (Build.VERSION.SDK_INT >= 30) v.directory else null)
                ?: if (v.isPrimary) Environment.getExternalStorageDirectory() else v.uuid?.let { File("/storage/$it") }
                ?: continue
            if (!dir.canRead()) continue
            list.add(Vol(v.getDescription(app), dir, v.isRemovable))
        }
        if (list.isEmpty()) list.add(Vol("Armazenamento interno", Environment.getExternalStorageDirectory(), false))
        return list
    }

    private fun vol(r: Req): Vol {
        val i = r.q("v")?.toIntOrNull() ?: 0
        return volumes().getOrNull(i) ?: throw HttpError(404, "volume")
    }

    private fun vol(o: JSONObject): Vol = volumes().getOrNull(o.optInt("v", 0)) ?: throw HttpError(404, "volume")

    private fun protectedPaths(): List<String> = runCatching { Vault.protectedPaths(app) }.getOrDefault(emptyList())

    private fun isProtected(f: File, prot: List<String> = protectedPaths()): Boolean {
        val p = f.absolutePath.trimEnd('/') + "/"
        return prot.any { p.startsWith(it) }
    }

    private fun containsProtected(f: File, prot: List<String> = protectedPaths()): Boolean {
        val p = f.absolutePath.trimEnd('/') + "/"
        return prot.any { it.startsWith(p) }
    }

    /** Arquivo/pasta [rel] dentro do volume; nunca sai dele (.., links) nem entra em protegidas. */
    private fun resolve(v: Vol, rel: String?): File {
        val root = v.dir.canonicalFile
        val clean = (rel ?: "").split('/').filter { it.isNotEmpty() && it != "." }
        if (clean.any { it == ".." || '\u0000' in it }) throw HttpError(400, "caminho")
        val f = File(root, clean.joinToString("/")).canonicalFile
        if (f != root && !f.path.startsWith(root.path + "/")) throw HttpError(403, "fora do volume")
        if (isProtected(File(root.absolutePath + (if (clean.isEmpty()) "" else "/" + clean.joinToString("/"))))) throw HttpError(404, "não encontrado")
        if (isProtected(f)) throw HttpError(404, "não encontrado")
        return f
    }

    private fun relOf(v: Vol, f: File): String {
        val root = v.dir.canonicalFile.path
        return f.canonicalFile.path.removePrefix(root).ifEmpty { "/" }
    }

    private fun validName(n: String): String {
        val t = n.trim()
        if (t.isEmpty() || t == "." || t == ".." || '/' in t || '\u0000' in t || t.toByteArray().size > 255) throw HttpError(400, "nome inválido")
        return t
    }

    private fun scan(vararg files: File) {
        val paths = files.map { it.absolutePath }.toTypedArray()
        runCatching { MediaScannerConnection.scanFile(app, paths, null, null) }
    }

    // ---------------------------------------------------------------- API

    private fun model() = "${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${Build.MODEL}"

    /** Nome do aparelho definido pelo usuário (Configurações > Sobre o telefone); senão, o modelo. */
    private fun deviceName(): String =
        runCatching { android.provider.Settings.Global.getString(app.contentResolver, "device_name") }.getOrNull()
            ?.trim()?.takeIf { it.isNotEmpty() } ?: model()

    private fun info(out: OutputStream): Sent {
        val o = JSONObject()
        o.put("device", deviceName())
        o.put("model", model())
        o.put("android", Build.VERSION.RELEASE)
        val bat = app.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        if (bat != null) {
            val level = bat.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = bat.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
            val status = bat.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            val plugged = bat.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
            val temp = bat.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
            o.put(
                "battery",
                JSONObject()
                    .put("level", if (level >= 0) level * 100 / scale else JSONObject.NULL)
                    .put("charging", status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL)
                    .put("full", status == BatteryManager.BATTERY_STATUS_FULL)
                    .put("plugged", plugged != 0)
                    .put("temp", if (temp != Int.MIN_VALUE) temp / 10.0 else JSONObject.NULL),
            )
        }
        val vols = JSONArray()
        volumes().forEachIndexed { i, v ->
            val st = runCatching { StatFs(v.dir.path) }.getOrNull()
            vols.put(
                JSONObject().put("i", i).put("name", v.name).put("removable", v.removable)
                    .put("total", st?.totalBytes ?: 0L).put("free", st?.availableBytes ?: 0L),
            )
        }
        o.put("volumes", vols)
        return json(out, 200, o)
    }

    private fun entry(f: File, prot: List<String>): JSONObject? {
        if (isProtected(f, prot)) return null
        val d = f.isDirectory
        val o = JSONObject().put("n", f.name).put("d", d).put("m", f.lastModified())
        if (!d) o.put("s", f.length())
        return o
    }

    private fun list(r: Req, out: OutputStream): Sent {
        val v = vol(r)
        val dir = resolve(v, r.q("p"))
        if (!dir.isDirectory) throw HttpError(404, "pasta")
        val prot = protectedPaths()
        val arr = JSONArray()
        dir.listFiles()?.forEach { f -> entry(f, prot)?.let { arr.put(it) } }
        return json(out, 200, JSONObject().put("path", relOf(v, dir)).put("entries", arr).put("writable", dir.canWrite()))
    }

    private fun search(r: Req, out: OutputStream): Sent {
        val v = vol(r)
        val dir = resolve(v, r.q("p"))
        val q = r.q("q")?.trim()?.lowercase(Locale.getDefault()).orEmpty()
        if (q.isEmpty()) throw HttpError(400, "busca vazia")
        val prot = protectedPaths()
        val arr = JSONArray()
        val stack = ArrayDeque<Pair<File, Int>>()
        stack.add(dir to 0)
        var seen = 0
        while (stack.isNotEmpty() && arr.length() < 500 && seen < 200_000) {
            val (d, depth) = stack.removeLast()
            val kids = d.listFiles() ?: continue
            for (f in kids) {
                seen++
                if (isProtected(f, prot)) continue
                if (f.name.lowercase(Locale.getDefault()).contains(q)) {
                    entry(f, prot)?.put("p", relOf(v, f.parentFile ?: f))?.let { arr.put(it) }
                    if (arr.length() >= 500) break
                }
                if (f.isDirectory && depth < 32) stack.add(f to depth + 1)
            }
        }
        return json(out, 200, JSONObject().put("entries", arr).put("truncated", arr.length() >= 500))
    }

    // ---------------------------------------------------------------- por tipo (índice do sistema)

    private class Media(val path: String, val size: Long, val modified: Long, val kind: String)

    /** Tipo de arquivo para o resumo: img, vid, aud, doc, zip, apk (ou null). */
    private fun kindOf(mime: String?, name: String): String? {
        val m = (mime ?: mimeOf(name)).lowercase(Locale.US)
        val ext = name.substringAfterLast('.', "").lowercase(Locale.US)
        return when {
            m.startsWith("image/") -> "img"
            m.startsWith("video/") -> "vid"
            m.startsWith("audio/") -> "aud"
            ext == "apk" -> "apk"
            ext in setOf("zip", "rar", "7z", "tar", "gz", "tgz", "bz2", "xz") -> "zip"
            ext in setOf("pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "odt", "ods", "odp", "rtf", "txt", "md", "csv", "epub") -> "doc"
            else -> null
        }
    }

    /**
     * Arquivos do índice de mídia do Android (MediaStore), já sem pastas protegidas, ocultas
     * (começando com ponto) e fora dos volumes mostrados. [only] filtra um tipo.
     */
    private fun indexed(only: String?): Sequence<Pair<Int, Media>> = sequence {
        val vols = volumes().map { it.dir.canonicalFile.path + "/" }
        val prot = protectedPaths()
        val uri = android.provider.MediaStore.Files.getContentUri("external")
        @Suppress("DEPRECATION")
        val cols = arrayOf(android.provider.MediaStore.MediaColumns.DATA, android.provider.MediaStore.MediaColumns.SIZE, android.provider.MediaStore.MediaColumns.DATE_MODIFIED, android.provider.MediaStore.MediaColumns.MIME_TYPE)
        val order = "${android.provider.MediaStore.MediaColumns.DATE_MODIFIED} DESC"
        val c = runCatching { app.contentResolver.query(uri, cols, null, null, order) }.getOrNull() ?: return@sequence
        c.use {
            while (it.moveToNext()) {
                val path = it.getString(0) ?: continue
                val name = path.substringAfterLast('/')
                if (name.isEmpty() || path.contains("/.")) continue
                val k = kindOf(it.getString(3), name) ?: continue
                if (only != null && k != only) continue
                val vi = vols.indexOfFirst { root -> path.startsWith(root) }
                if (vi < 0) continue
                if (prot.any { pp -> "$path/".startsWith(pp) }) continue
                yield(vi to Media(path, it.getLong(1), it.getLong(2) * 1000, k))
            }
        }
    }

    private fun roots() = volumes().map { it.dir.canonicalFile.path }

    private fun mediaJson(roots: List<String>, vi: Int, m: Media): JSONObject {
        val rel = m.path.removePrefix(roots[vi])
        return JSONObject().put("n", rel.substringAfterLast('/')).put("p", rel.substringBeforeLast('/').ifEmpty { "/" })
            .put("v", vi).put("d", false).put("s", m.size).put("m", m.modified)
    }

    @Volatile private var statsCache: Pair<Long, JSONObject>? = null

    /** Resumo para o painel inicial: quantidade e tamanho por tipo e os recentes. */
    private fun stats(r: Req, out: OutputStream): Sent {
        statsCache?.let { (t, o) -> if (System.currentTimeMillis() - t < 30_000) return json(out, 200, o) }
        val count = HashMap<String, Long>()
        val size = HashMap<String, Long>()
        val recent = JSONArray()
        val roots = roots()
        for ((vi, m) in indexed(null)) {
            count[m.kind] = (count[m.kind] ?: 0) + 1
            size[m.kind] = (size[m.kind] ?: 0) + m.size
            if (recent.length() < 24 && (m.kind == "img" || m.kind == "vid")) recent.put(mediaJson(roots, vi, m))
        }
        val kinds = JSONObject()
        for (k in listOf("img", "vid", "aud", "doc", "zip", "apk")) kinds.put(k, JSONObject().put("n", count[k] ?: 0).put("s", size[k] ?: 0))
        val o = JSONObject().put("kinds", kinds).put("recent", recent)
        statsCache = System.currentTimeMillis() to o
        return json(out, 200, o)
    }

    /** Todos os arquivos de um tipo, mais novos primeiro (até 1000). */
    private fun kind(r: Req, out: OutputStream): Sent {
        val k = r.q("k") ?: throw HttpError(400, "tipo")
        val arr = JSONArray()
        val roots = roots()
        for ((vi, m) in indexed(k)) {
            arr.put(mediaJson(roots, vi, m))
            if (arr.length() >= 1000) break
        }
        return json(out, 200, JSONObject().put("entries", arr).put("truncated", arr.length() >= 1000))
    }

    private fun mimeOf(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase(Locale.US)
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
            ?: when (ext) {
                "mkv" -> "video/x-matroska"
                "srt", "vtt", "ass", "log", "md", "kt", "kts", "gradle", "ini", "cfg", "conf", "yml", "yaml", "toml", "properties", "sh", "bat", "ps1", "py", "c", "h", "cpp", "java" -> "text/plain"
                else -> "application/octet-stream"
            }
    }

    /** Tipos que o navegador pode abrir na página sem risco (nada que rode script). */
    private fun inlineType(mime: String): String? = when {
        mime == "image/svg+xml" -> null
        mime.startsWith("image/") || mime.startsWith("video/") || mime.startsWith("audio/") -> mime
        mime == "application/pdf" -> mime
        mime.startsWith("text/") || mime == "application/json" || mime == "application/xml" || mime == "application/javascript" -> "text/plain; charset=utf-8"
        else -> null
    }

    private fun disposition(kind: String, name: String): String {
        val ascii = name.map { if (it.code in 32..126 && it != '"' && it != '\\') it else '_' }.joinToString("")
        val enc = URLEncoder.encode(name, "UTF-8").replace("+", "%20")
        return "$kind; filename=\"$ascii\"; filename*=UTF-8''$enc"
    }

    private fun httpDate(ms: Long): String = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US)
        .apply { timeZone = TimeZone.getTimeZone("GMT") }.format(Date(ms))

    private fun file(r: Req, out: OutputStream): Sent {
        val v = vol(r)
        val f = resolve(v, r.q("p"))
        if (!f.isFile) throw HttpError(404, "arquivo")
        val len = f.length()
        val mime = mimeOf(f.name)
        val inline = if (r.q("dl") == "1") null else inlineType(mime)
        val extra = arrayListOf(
            "Accept-Ranges" to "bytes",
            "Content-Disposition" to disposition(if (inline != null) "inline" else "attachment", f.name),
            "Last-Modified" to httpDate(f.lastModified()),
            "Cache-Control" to "private, no-cache",
        )
        // Nada vindo do celular roda script na página (o PDF usa o leitor do próprio navegador).
        if (inline != "application/pdf") extra.add("Content-Security-Policy" to "default-src 'none'; img-src 'self'; media-src 'self'; style-src 'unsafe-inline'")
        var start = 0L
        var end = len - 1
        var code = 200
        val range = r.headers["range"]
        if (range != null && range.startsWith("bytes=") && ',' !in range) {
            val spec = range.removePrefix("bytes=")
            val a = spec.substringBefore('-')
            val b = spec.substringAfter('-')
            if (a.isEmpty()) {
                val n = b.toLongOrNull() ?: 0L
                start = (len - n).coerceAtLeast(0L)
            } else {
                start = a.toLongOrNull() ?: 0L
                if (b.isNotEmpty()) end = minOf(b.toLongOrNull() ?: end, len - 1)
            }
            if (start >= len || start > end) {
                head(out, 416, null, 0, listOf("Content-Range" to "bytes */$len"))
                return Sent(true)
            }
            code = 206
            extra.add("Content-Range" to "bytes $start-$end/$len")
        }
        val count = if (len == 0L) 0L else end - start + 1
        head(out, code, inline ?: mime.takeIf { it.startsWith("text/").not() } ?: "application/octet-stream", count, extra)
        if (r.method == "HEAD") return Sent(true)
        FileInputStream(f).use { inp ->
            if (start > 0) inp.channel.position(start)
            copy(inp, out, count)
        }
        return Sent(true)
    }

    private fun copy(inp: InputStream, out: OutputStream, count: Long) {
        val buf = ByteArray(65536)
        var left = count
        while (left > 0) {
            val n = inp.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
            if (n < 0) break
            out.write(buf, 0, n)
            left -= n
            onActivity()
        }
    }

    private fun thumb(r: Req, out: OutputStream): Sent {
        val v = vol(r)
        val f = resolve(v, r.q("p"))
        if (!f.isFile) throw HttpError(404, "arquivo")
        val mime = mimeOf(f.name)
        val px = (r.q("s")?.toIntOrNull() ?: 256).coerceIn(64, 512)
        val key = "${f.path}|${f.lastModified()}|${f.length()}|$px"
        val cacheHdr = listOf("Cache-Control" to "private, max-age=86400")
        thumbCache.get(key)?.let { return bytes(out, 200, "image/jpeg", it, cacheHdr) }
        thumbGate.acquire()
        val bmp: Bitmap? = try {
            when {
                // Android 10+: usa a miniatura embutida quando há, gira pela orientação da foto e lê HEIC.
                mime.startsWith("image/") && mime != "image/svg+xml" && Build.VERSION.SDK_INT >= 29 ->
                    runCatching { ThumbnailUtils.createImageThumbnail(f, Size(px, px), null) }.getOrNull() ?: decodeSampled(f, px)
                mime.startsWith("image/") && mime != "image/svg+xml" -> decodeSampled(f, px)
                mime.startsWith("video/") && Build.VERSION.SDK_INT >= 29 ->
                    runCatching { ThumbnailUtils.createVideoThumbnail(f, Size(px, px), null) }.getOrNull()
                mime.startsWith("audio/") && Build.VERSION.SDK_INT >= 29 ->
                    runCatching { ThumbnailUtils.createAudioThumbnail(f, Size(px, px), null) }.getOrNull()
                else -> null
            }
        } catch (_: Throwable) {
            null
        } finally {
            thumbGate.release()
        }
        if (bmp == null) throw HttpError(404, "sem miniatura")
        val bo = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 78, bo)
        bmp.recycle()
        val jpg = bo.toByteArray()
        thumbCache.put(key, jpg)
        return bytes(out, 200, "image/jpeg", jpg, cacheHdr)
    }

    private fun decodeSampled(f: File, px: Int): Bitmap? {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, o)
        if (o.outWidth <= 0 || o.outHeight <= 0) return null
        var s = 1
        while (o.outWidth / (s * 2) >= px && o.outHeight / (s * 2) >= px) s *= 2
        val b = BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = s }) ?: return null
        val scale = px.toFloat() / maxOf(b.width, b.height)
        if (scale >= 1f) return b
        val r = Bitmap.createScaledBitmap(b, (b.width * scale).toInt().coerceAtLeast(1), (b.height * scale).toInt().coerceAtLeast(1), true)
        if (r !== b) b.recycle()
        return r
    }

    /** Pasta ou itens escolhidos em .zip, gerado na hora (sem compressão: rápido, fotos e vídeos já são comprimidos). */
    private fun zip(r: Req, out: OutputStream): Sent {
        val v = vol(r)
        val dir = resolve(v, r.q("p"))
        val names = r.query["n"].orEmpty()
        val items = if (names.isEmpty()) listOf(dir) else names.map { resolve(v, relOf(v, dir) + "/" + validName(it)) }
        if (items.any { !it.exists() }) throw HttpError(404, "não encontrado")
        val prot = protectedPaths()
        val zipName = (if (items.size == 1) items[0].name.ifEmpty { "armazenamento" } else dir.name.ifEmpty { "arquivos" }) + ".zip"
        head(out, 200, "application/zip", -1, listOf("Content-Disposition" to disposition("attachment", zipName)), keep = false)
        val zos = ZipOutputStream(out)
        zos.setLevel(Deflater.NO_COMPRESSION)
        fun add(f: File, prefix: String) {
            if (isProtected(f, prot)) return
            val name = prefix + f.name
            if (f.isDirectory) {
                zos.putNextEntry(ZipEntry("$name/").apply { time = f.lastModified() })
                zos.closeEntry()
                f.listFiles()?.forEach { add(it, "$name/") }
            } else if (f.isFile) {
                zos.putNextEntry(ZipEntry(name).apply { time = f.lastModified() })
                runCatching { FileInputStream(f).use { copy(it, zos, Long.MAX_VALUE) } }
                zos.closeEntry()
            }
        }
        for (f in items) add(f, "")
        zos.finish()
        zos.flush()
        return Sent(false)
    }

    private fun uniqueIn(dir: File, name: String): File {
        var f = File(dir, name)
        if (!f.exists()) return f
        val base = name.substringBeforeLast('.', name)
        val ext = if ('.' in name && !name.startsWith('.')) "." + name.substringAfterLast('.') else ""
        var i = 2
        while (f.exists()) {
            f = File(dir, "$base ($i)$ext")
            i++
        }
        return f
    }

    private fun upload(r: Req, out: OutputStream): Sent {
        val v = vol(r)
        var dir = resolve(v, r.q("p"))
        // Envio de pasta: "rel" traz as subpastas (ex.: "Fotos/2024").
        r.q("rel")?.split('/')?.filter { it.isNotEmpty() }?.forEach { part ->
            dir = File(dir, validName(part))
        }
        dir = resolve(v, relOf(v, dir.parentFile ?: dir).trimEnd('/') + "/" + dir.name)
        if (!dir.isDirectory && !dir.mkdirs()) throw HttpError(409, "não foi possível criar a pasta")
        val name = validName(r.q("name").orEmpty())
        val free = runCatching { StatFs(dir.path).availableBytes }.getOrDefault(Long.MAX_VALUE)
        if (r.length > free) throw HttpError(507, "sem espaço")
        val target = uniqueIn(dir, name)
        val tmp = File(dir, ".${target.name}.index-part")
        r.markConsumed()
        try {
            FileOutputStream(tmp).use { fo ->
                val buf = ByteArray(65536)
                var left = r.length
                while (left > 0) {
                    val n = r.input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                    if (n < 0) throw IOException("envio interrompido")
                    fo.write(buf, 0, n)
                    left -= n
                    onActivity()
                }
                fo.fd.sync()
            }
            if (!tmp.renameTo(target)) throw IOException("não foi possível salvar")
        } catch (e: IOException) {
            tmp.delete()
            throw e
        }
        r.q("m")?.toLongOrNull()?.let { if (it > 0) target.setLastModified(it) }
        scan(target)
        return json(out, 200, JSONObject().put("ok", true).put("name", target.name))
    }

    private fun mkdir(r: Req, out: OutputStream): Sent {
        val o = r.json()
        val v = vol(o)
        val dir = resolve(v, o.optString("p"))
        val f = File(dir, validName(o.optString("name")))
        if (f.exists()) throw HttpError(409, "já existe")
        if (!f.mkdir()) throw HttpError(409, "não foi possível criar")
        return ok(out)
    }

    private fun rename(r: Req, out: OutputStream): Sent {
        val o = r.json()
        val v = vol(o)
        val f = resolve(v, o.optString("p"))
        if (f == v.dir.canonicalFile) throw HttpError(400, "raiz")
        if (containsProtected(f)) throw HttpError(409, "tem pasta protegida dentro")
        val to = File(f.parentFile, validName(o.optString("name")))
        if (to.exists() && !to.path.equals(f.path, ignoreCase = true)) throw HttpError(409, "já existe")
        if (!f.renameTo(to)) throw HttpError(409, "não foi possível renomear")
        scan(f, to)
        return ok(out)
    }

    /** Mover ou copiar itens de uma pasta para outra (mesmo volume ou entre volumes). */
    private fun transfer(r: Req, out: OutputStream): Sent {
        val o = r.json()
        val v = vol(o)
        val copy = o.optBoolean("copy")
        val src = resolve(v, o.optString("p"))
        val dv = volumes().getOrNull(o.optInt("tv", o.optInt("v"))) ?: throw HttpError(404, "volume")
        val dest = resolve(dv, o.optString("to"))
        if (!dest.isDirectory) throw HttpError(404, "destino")
        val names = o.optJSONArray("names") ?: JSONArray()
        val prot = protectedPaths()
        var done = 0
        val fails = JSONArray()
        for (i in 0 until names.length()) {
            val n = validName(names.getString(i))
            val f = File(src, n)
            if (!f.exists() || isProtected(f, prot)) { fails.put(n); continue }
            if (!copy && containsProtected(f, prot)) { fails.put(n); continue }
            if (dest.path == f.path || dest.path.startsWith(f.path + "/")) { fails.put(n); continue }
            if (!copy && f.parentFile?.canonicalPath == dest.canonicalPath) { done++; continue }
            val to = uniqueIn(dest, n)
            val okk = try {
                if (!copy && f.renameTo(to)) true
                else {
                    copyTree(f, to, prot)
                    if (!copy) deleteTree(f)
                    true
                }
            } catch (e: IOException) {
                Log.w(TAG, "falha ao ${if (copy) "copiar" else "mover"} um item", e)
                false
            }
            if (okk) {
                done++
                scan(to)
                if (!copy) scan(f)
            } else fails.put(n)
        }
        return json(out, 200, JSONObject().put("done", done).put("failed", fails))
    }

    private fun copyTree(f: File, to: File, prot: List<String>) {
        if (isProtected(f, prot)) return
        if (f.isDirectory) {
            if (!to.isDirectory && !to.mkdirs()) throw IOException("pasta")
            f.listFiles()?.forEach { copyTree(it, File(to, it.name), prot) }
        } else {
            FileInputStream(f).use { i -> FileOutputStream(to).use { o -> copy(i, o, Long.MAX_VALUE) } }
            to.setLastModified(f.lastModified())
        }
    }

    private fun deleteTree(f: File): Boolean {
        if (f.isDirectory && !isSymlink(f)) f.listFiles()?.forEach { deleteTree(it) }
        return f.delete()
    }

    private fun isSymlink(f: File) = runCatching { java.nio.file.Files.isSymbolicLink(f.toPath()) }.getOrDefault(false)

    private fun delete(r: Req, out: OutputStream): Sent {
        val o = r.json()
        val v = vol(o)
        val dir = resolve(v, o.optString("p"))
        val names = o.optJSONArray("names") ?: JSONArray()
        val prot = protectedPaths()
        var done = 0
        val fails = JSONArray()
        for (i in 0 until names.length()) {
            val n = validName(names.getString(i))
            val f = File(dir, n)
            if (!f.exists() || isProtected(f, prot) || containsProtected(f, prot)) { fails.put(n); continue }
            if (deleteTree(f)) { done++; scan(f) } else fails.put(n)
        }
        return json(out, 200, JSONObject().put("done", done).put("failed", fails))
    }

    companion object {
        private const val TAG = "Index.Web"
        private const val COOKIE = "index_s"
        private const val MAX_CONN = 48
        private const val SESSION_MS = 12 * 60 * 60 * 1000L
    }
}
