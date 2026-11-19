package app.index.files.data.drive

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import android.os.ProxyFileDescriptorCallback
import android.os.SystemClock
import android.os.storage.StorageManager
import android.provider.OpenableColumns
import android.system.ErrnoException
import android.system.OsConstants
import android.util.Log
import app.index.files.data.FileItem
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection

/**
 * Streaming de arquivos do Drive para outros apps (player nativo, VLC…), sem baixar.
 *
 * O app recebe um content:// e, ao abri-lo, ganha um descritor com acesso aleatório
 * (StorageManager.openProxyFileDescriptor). As leituras do player são servidas de um
 * cache em blocos que "faixas" de download em segundo plano mantêm à frente da posição
 * lida; um salto (seek) reposiciona uma faixa com "Range: bytes=N-".
 * Enquanto o player segura o descritor, o sistema mantém este processo vivo.
 *
 * Uri: content://<pacote>.stream/<id>/<tamanho>/<nome>?m=<mime>
 */
class DriveStreamProvider : ContentProvider() {

    override fun onCreate() = true

    override fun getType(uri: Uri): String = uri.getQueryParameter("m") ?: "application/octet-stream"

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        val p = parse(uri)
        val cols = projection?.filter { it == OpenableColumns.DISPLAY_NAME || it == OpenableColumns.SIZE }?.toTypedArray()
            ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        return MatrixCursor(cols, 1).apply {
            addRow(cols.map { if (it == OpenableColumns.SIZE) p.size else p.name })
        }
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw FileNotFoundException("somente leitura")
        val ctx = context ?: throw FileNotFoundException()
        val p = parse(uri)
        if (!DriveAuth.connected(ctx)) throw FileNotFoundException("conta Google desconectada")
        val thread = HandlerThread("stream", android.os.Process.THREAD_PRIORITY_BACKGROUND).apply { start() }
        val sm = ctx.getSystemService(StorageManager::class.java)
        val handler = Handler(thread.looper)
        val pfd = try {
            sm.openProxyFileDescriptor(
                ParcelFileDescriptor.MODE_READ_ONLY,
                BlockReader(ctx.applicationContext, p.id, p.size, p.name, thread, handler),
                handler,
            )
        } catch (e: Exception) {
            thread.quitSafely()
            throw e
        }
        StreamService.streamOpened() // fecha em onRelease
        return pfd
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0

    private class Parsed(val id: String, val size: Long, val name: String)

    private fun parse(uri: Uri): Parsed {
        val s = uri.pathSegments
        if (s.size < 3) throw FileNotFoundException(uri.toString())
        return Parsed(s[0], s[1].toLongOrNull() ?: throw FileNotFoundException(uri.toString()), s[2])
    }

    /**
     * Leitor por blocos de [BLOCK] bytes com download antecipado.
     *
     * O player nativo da Samsung lê áudio e vídeo em pontos próximos mas alternados do
     * arquivo e relê trechos. Servir cada leitura sob demanda (esperando a rede) fazia o
     * vídeo travar. Aqui até [MAX_LANES] faixas baixam em segundo plano, cada uma numa
     * conexão sequencial, mantendo [AHEAD_BLOCKS] blocos à frente da última leitura da
     * sua região; o player normalmente encontra o bloco já no cache. Blocos já no cache
     * não são baixados de novo. O cache é limitado a [CACHE_BLOCKS] (LRU).
     *
     * As leituras do player chegam numa só thread (a do [handler]); as faixas têm
     * threads próprias. Tudo que é compartilhado fica sob [lock].
     */
    private class BlockReader(
        private val ctx: Context,
        private val id: String,
        private val total: Long,
        private val name: String,
        private val thread: HandlerThread,
        private val handler: Handler,
    ) : ProxyFileDescriptorCallback() {
        private val lock = Object()
        private val blocks = (total + BLOCK - 1) / BLOCK
        private val spares = ArrayList<ByteArray>()
        private val cache = object : LinkedHashMap<Long, ByteArray>(CACHE_BLOCKS + 1, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, ByteArray>): Boolean {
                if (size <= CACHE_BLOCKS) return false
                if (spares.size < 4) spares.add(eldest.value)
                return true
            }
        }
        private val lanes = ArrayList<Lane>(MAX_LANES)
        private var closed = false
        private var tick = 0L

        // Estatísticas (sob lock).
        private var opens = 0
        private var fetched = 0L
        private var served = 0L
        private var waits = 0
        private var waitMs = 0L

        /**
         * Players costumam segurar o descritor depois de parar (o VLC só solta quando o
         * processo dele morre). Sem leituras por um tempo (ex.: vídeo pausado), encerra
         * as faixas e as conexões e libera o cache; a próxima leitura recria o que
         * precisar (~1 s para a primeira requisição).
         */
        private val idle = Runnable {
            synchronized(lock) {
                stopLanes()
                cache.clear()
                spares.clear()
            }
        }

        override fun onGetSize(): Long = total

        override fun onRead(offset: Long, size: Int, data: ByteArray): Int {
            if (offset >= total || size <= 0) return 0
            val end = minOf(total, offset + size)
            var off = offset
            var n = 0
            synchronized(lock) {
                while (off < end) {
                    val index = off / BLOCK
                    val block = await(index)
                    val inBlock = (off - index * BLOCK).toInt()
                    val len = minOf(blockLen(index) - inBlock, (end - off).toInt())
                    System.arraycopy(block, inBlock, data, n, len)
                    n += len
                    off += len
                }
                served += n
            }
            handler.removeCallbacks(idle)
            handler.postDelayed(idle, IDLE_MS)
            StreamService.touch()
            return n
        }

        private fun blockLen(index: Long): Int = minOf(BLOCK.toLong(), total - index * BLOCK).toInt()

        /** Bloco [index], esperando a faixa que o baixa. Chamar com [lock]. */
        private fun await(index: Long): ByteArray {
            cache[index]?.let {
                // Mantém a faixa desta região baixando à frente.
                lanes.filter { l -> index >= l.base && index <= l.next + SKIP_BLOCKS }
                    .maxByOrNull { l -> l.base }
                    ?.let { l ->
                        l.lastUse = ++tick
                        if (index > l.want) {
                            l.want = index
                            lock.notifyAll()
                        }
                    }
                return it
            }
            var lane = laneFor(index)
            val t0 = SystemClock.elapsedRealtime()
            waits++
            while (true) {
                cache[index]?.let {
                    waitMs += SystemClock.elapsedRealtime() - t0
                    return it
                }
                lane.error?.let { e ->
                    lane.error = null
                    Log.w(TAG, "falha ao ler $name no bloco $index: $e / ${e.cause}")
                    lane.retarget(index)
                    throw ErrnoException("onRead", OsConstants.EIO)
                }
                if (closed) throw ErrnoException("onRead", OsConstants.EIO)
                if (SystemClock.elapsedRealtime() - t0 > READ_TIMEOUT_MS) {
                    Log.w(TAG, "tempo esgotado lendo $name no bloco $index")
                    throw ErrnoException("onRead", OsConstants.EIO)
                }
                // A faixa pode ter passado do bloco (ex.: estava no cache e saiu): reposiciona.
                if (!lane.covers(index)) lane = laneFor(index)
                lock.wait(500)
            }
        }

        /**
         * Faixa responsável pelo bloco: a que já está chegando nele (até [SKIP_BLOCKS] à
         * frente da posição dela); senão uma nova, ou a usada há mais tempo, reposicionada.
         * Atualiza até onde a faixa deve baixar. Chamar com [lock].
         */
        private fun laneFor(index: Long): Lane {
            val lane = lanes.filter { it.covers(index) }.minByOrNull { index - it.next }
                ?: if (lanes.size < MAX_LANES) {
                    Lane(index).also { lanes.add(it); it.start() }
                } else {
                    lanes.minByOrNull { it.lastUse }!!.also { it.retarget(index) }
                }
            lane.lastUse = ++tick
            if (index > lane.want) lane.want = index
            lock.notifyAll()
            return lane
        }

        /** Chamar com [lock]. */
        private fun stopLanes() {
            lanes.forEach { it.quit() }
            lanes.clear()
            lock.notifyAll()
        }

        override fun onRelease() {
            handler.removeCallbacks(idle)
            synchronized(lock) {
                closed = true
                stopLanes()
                cache.clear()
                spares.clear()
                Log.i(
                    TAG,
                    "stream $name: $opens requisições, ${fetched / 1024} KB baixados, " +
                        "${served / 1024} KB entregues, $waits esperas (${waitMs} ms) de ${total / 1024} KB",
                )
            }
            StreamService.streamClosed()
            thread.quitSafely()
        }

        /** Download sequencial em segundo plano a partir de [next]. */
        private inner class Lane(start: Long) : Thread("stream-lane") {
            /** Próximo bloco a baixar. */
            var next = start
            /** Onde a faixa começou (ou foi reposicionada): início da sua região. */
            var base = start
            /** Bloco mais adiante já pedido pelo player nesta região. */
            var want = start
            var lastUse = 0L
            var error: IOException? = null
            @Volatile private var quit = false

            // Só usados pela thread da faixa.
            @Volatile private var conn: HttpURLConnection? = null
            private var input: InputStream? = null
            private var connAt = -1L
            private var failures = 0

            init {
                isDaemon = true
                priority = MIN_PRIORITY + 2
            }

            /** O bloco está no caminho desta faixa. Chamar com [lock]. */
            fun covers(index: Long) = index >= next && index - next <= SKIP_BLOCKS

            /** Chamar com [lock]. */
            fun retarget(index: Long) {
                next = index
                base = index
                want = index
                error = null
                failures = 0
            }

            /** Chamar com [lock]. */
            fun quit() {
                quit = true
                interrupt()
                // Desbloqueia uma leitura de rede em andamento.
                runCatching { conn?.disconnect() }
            }

            override fun run() {
                try {
                    loop()
                } catch (_: InterruptedException) {
                } finally {
                    closeConn()
                }
            }

            private fun loop() {
                while (!quit) {
                    val target: Long
                    val buf: ByteArray
                    synchronized(lock) {
                        while (true) {
                            if (quit || closed) return
                            // Pula blocos que outra faixa (ou uma leitura anterior) já trouxe.
                            while (next < blocks && cache.containsKey(next)) next++
                            if (error == null && next < blocks && next <= want + AHEAD_BLOCKS) break
                            lock.wait()
                        }
                        target = next
                        buf = if (spares.isNotEmpty()) spares.removeAt(spares.size - 1) else ByteArray(BLOCK)
                    }
                    try {
                        val len = blockLen(target)
                        read(target, buf, len)
                        synchronized(lock) {
                            if (quit) return // encerrada durante o download: não repovoa o cache
                            cache[target] = buf
                            fetched += len
                            if (next == target) next++
                            failures = 0
                            lock.notifyAll()
                        }
                    } catch (e: IOException) {
                        closeConn()
                        if (quit) return
                        synchronized(lock) {
                            // Conexão caiu (rede trocou, token expirou…): reabre uma vez.
                            if (++failures >= 2 || e is AuthRequired) {
                                error = e
                                lock.notifyAll()
                            }
                        }
                    }
                }
            }

            /** Lê o bloco [index] na conexão, abrindo ou avançando-a se preciso. */
            private fun read(index: Long, buf: ByteArray, len: Int) {
                val gap = index - connAt
                if (input == null || gap < 0 || gap > SKIP_BLOCKS) {
                    open(index)
                } else {
                    // Avança lendo e descartando (mais barato que uma nova requisição).
                    var left = gap * BLOCK
                    while (left > 0) {
                        val r = input!!.read(buf, 0, minOf(left, BLOCK.toLong()).toInt())
                        if (r < 0) throw IOException("fim inesperado")
                        left -= r
                    }
                }
                var n = 0
                while (n < len) {
                    val r = input!!.read(buf, n, len - n)
                    if (r < 0) throw IOException("fim inesperado")
                    n += r
                }
                connAt = index + 1
                if (connAt >= blocks) closeConn()
            }

            private fun open(index: Long) {
                closeConn()
                val start = index * BLOCK
                // "id" sozinho aqui seria o Thread.id.
                val c = DriveApi.openRange(ctx, this@BlockReader.id, start)
                synchronized(lock) { opens++ }
                conn = c
                val code = c.responseCode
                if (code == 416) throw IOException("posição $start além do fim")
                val stream = c.inputStream
                if (code == 200 && start > 0) {
                    // Servidor ignorou o Range: descarta até a posição.
                    var left = start
                    while (left > 0) {
                        val r = stream.skip(left)
                        if (r <= 0) throw IOException("fim inesperado")
                        left -= r
                    }
                }
                input = stream
                connAt = index
            }

            private fun closeConn() {
                runCatching { input?.close() }
                runCatching { conn?.disconnect() }
                input = null
                conn = null
                connAt = -1
            }
        }
    }

    companion object {
        private const val TAG = "Drive"
        private const val BLOCK = 128 * 1024
        /** Salto para frente que uma faixa ainda cobre lendo, em vez de abrir outra conexão (1 MB). */
        private const val SKIP_BLOCKS = 8L
        /** Quanto cada faixa baixa à frente da última leitura (8 MB). */
        private const val AHEAD_BLOCKS = 64L
        /** Faixas simultâneas por arquivo (ex.: reprodução, índice no fim, salto recente). */
        private const val MAX_LANES = 3
        /** 256 × 128 KB = 32 MB de cache por arquivo aberto (só durante o streaming). */
        private const val CACHE_BLOCKS = 256
        private const val READ_TIMEOUT_MS = 30_000L
        private const val IDLE_MS = 20_000L

        fun uriFor(ctx: Context, item: FileItem): Uri =
            Uri.Builder()
                .scheme("content")
                .authority(ctx.packageName + ".stream")
                .appendPath(item.id)
                .appendPath(item.size.toString())
                .appendPath(item.name)
                .appendQueryParameter("m", item.mime)
                .build()
    }
}
