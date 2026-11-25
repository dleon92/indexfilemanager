package app.index.files.thumbs

import android.content.Context
import android.graphics.Bitmap
import android.util.LruCache
import android.widget.ImageView
import app.index.files.Bg
import app.index.files.R
import app.index.files.data.AppDb
import app.index.files.data.FileItem
import app.index.files.data.FileSource
import app.index.files.data.Prefs
import app.index.files.data.VaultSource
import java.io.File
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * Miniaturas: memória (LruCache por bytes, ~1/8 do heap) → disco (WebP) → geração.
 *
 * Filas LIFO: o pedido mais recente (o item que acabou de aparecer na tela) é
 * atendido primeiro. Ao reciclar uma view o pedido é cancelado; pedidos
 * cancelados ainda na fila saem sem fazer nada (rolagem rápida não gera trabalho inútil).
 */
class ThumbnailManager private constructor(ctx: Context) {

    private val app = ctx.applicationContext
    private val prefs = Prefs.get(app)

    // 1/8 do heap, com teto de 32 MB: acima disso as texturas da GPU acompanham
    // os bitmaps vivos e o PSS dispara (medido: 214 MB com 1/8 = 64 MB no S23).
    private val normalMax = minOf(Runtime.getRuntime().maxMemory() / 8, MEM_CAP).toInt()
    private val mem = object : LruCache<String, Bitmap>(normalMax) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount

        // Saiu do cache: libera já (pixels e a cópia na GPU), sem esperar o coletor de lixo.
        override fun entryRemoved(evicted: Boolean, key: String, oldValue: Bitmap, newValue: Bitmap?) {
            if (oldValue !== newValue) Bg.main.post { drop(oldValue) }
        }
    }

    // ---- Liberação imediata (fase 13) ----
    // Sem isto, miniaturas fora do cache seguiam vivas até o GC: o heap nativo passava do teto
    // (77 MB com teto de 32) e o driver da GPU segurava as texturas delas (~75 MB a mais).
    // Só é liberada a que não está em nenhuma célula; a que está, ao sair dela. Tudo na main.

    // Referências fracas (Bitmap compara por identidade): views de telas fechadas que nunca
    // trocam de imagem não prendem os bitmaps; esses saem pelo GC como antes.
    /** Quantas views mostram cada bitmap agora. */
    private val shown = java.util.WeakHashMap<Bitmap, Int>()
    /** Fora do cache, mas ainda numa célula: libera quando a última célula trocar. */
    private val dropped: MutableSet<Bitmap> = Collections.newSetFromMap(java.util.WeakHashMap())

    private fun drop(b: Bitmap) {
        if (b.isRecycled) return
        if ((shown[b] ?: 0) > 0) dropped.add(b) else b.recycle()
    }

    /** A view [v] passa a mostrar [b] (null = ícone): atualiza as contagens. */
    private fun track(v: ImageView, b: Bitmap?) {
        val prev = v.getTag(R.id.thumb_shown) as? Bitmap
        if (prev === b) return
        v.setTag(R.id.thumb_shown, b)
        if (b != null) shown[b] = (shown[b] ?: 0) + 1
        if (prev != null) {
            val n = (shown[prev] ?: 1) - 1
            if (n > 0) {
                shown[prev] = n
            } else {
                shown.remove(prev)
                if (dropped.remove(prev)) prev.recycle()
            }
        }
    }
    /** Chaves sem miniatura possível nesta sessão. */
    private val failed: MutableSet<String> = Collections.newSetFromMap(ConcurrentHashMap())

    /**
     * Falhas de rede (Drive sem conexão): não tentam de novo a cada rolagem, mas voltam a valer
     * um minuto depois da última falha (a rede pode ter voltado).
     */
    private val netFailed: MutableSet<String> = Collections.newSetFromMap(ConcurrentHashMap())
    @Volatile private var netFailedAt = 0L

    private fun isFailed(k: String): Boolean {
        if (netFailed.isNotEmpty() && System.currentTimeMillis() - netFailedAt > NET_RETRY_MS) netFailed.clear()
        return k in failed || k in netFailed
    }

    val disk = DiskStore(
        dirProvider = { thumbsDir(prefs.thumbsInFiles) },
        limitBytes = { prefs.thumbLimitMb.toLong() * 1024 * 1024 },
    )

    /**
     * Miniaturas do Drive: arquivo permanente próprio (filesDir, o sistema não apaga) com limite
     * à parte, para que as miniaturas locais não as empurrem para fora. Baixar de novo da nuvem
     * custa caro; uma miniatura só sai quando o arquivo muda (a chave muda), é apagado, ou
     * quando o limite do Drive enche.
     */
    val driveDisk = DiskStore(
        dirProvider = { File(app.filesDir, "thumbs_drive") },
        limitBytes = { prefs.driveThumbLimitMb.toLong() * 1024 * 1024 },
    )

    /** Miniaturas das pastas protegidas (cifradas, fora do cache comum). */
    private val vault = VaultThumbs(app)

    private fun storeFor(src: FileSource) = if (isDrive(src)) driveDisk else disk

    private fun readStore(src: FileSource, key: String): Bitmap? =
        if (src is VaultSource) vault.read(src.entry.id, key) else storeFor(src).read(key)

    private fun writeStore(src: FileSource, key: String, b: Bitmap) =
        if (src is VaultSource) vault.write(src.entry.id, key, b) else storeFor(src).write(key, b)

    private fun writeEmptyStore(src: FileSource, key: String) =
        if (src is VaultSource) vault.writeEmpty(src.entry.id, key) else storeFor(src).writeEmpty(key)

    private fun existsStore(src: FileSource, key: String) =
        if (src is VaultSource) vault.exists(src.entry.id, key) else storeFor(src).file(key).exists()

    /** Pastas protegidas trancadas: nenhuma miniatura delas fica na memória. */
    fun dropDecrypted() {
        Preload.release()
        mem.evictAll()
    }

    private val diskPool = lifoPool(2, "thumb-disk", Thread.NORM_PRIORITY - 1)
    private val genPool = lifoPool(2, "thumb-gen", Thread.MIN_PRIORITY + 1)

    // ---- Pré-carga (fase 10) ----

    /** Teto normal da memória de miniaturas (fora da pré-carga). */
    internal val normalBytes: Int get() = normalMax

    /** Bytes ocupados agora na memória de miniaturas. */
    internal fun memUsed(): Int = mem.size()

    /** Durante a pré-carga o teto sobe ao orçamento; ao liberar volta ao normal (o excedente sai). */
    internal fun setMemLimit(bytes: Int) = mem.resize(bytes)

    internal fun resetMemLimit() = mem.resize(normalMax)

    internal fun inMem(item: FileItem, src: FileSource): Boolean = mem.get(key(src, item)) != null

    /** Maior miniatura já na memória (qualquer tamanho), para o visualizador mostrar na hora. */
    fun cachedAny(src: FileSource, item: FileItem): Bitmap? {
        for (t in TIER_SUFFIX.indices.reversed()) mem.get(key(src, item, t))?.let { return it }
        return null
    }

    /**
     * Garante a miniatura de [item] no disco (gera/baixa se faltar) e, com [toMem], também na
     * memória. False se não há miniatura possível (ou falhou agora).
     */
    internal fun preloadOne(item: FileItem, src: FileSource, toMem: Boolean): Boolean {
        val k = key(src, item)
        if (isFailed(k)) return false
        if (mem.get(k) != null) return true
        if (!toMem && existsStore(src, k)) return true
        if (toMem) {
            val b = runCatching { readStore(src, k) }.getOrNull()
            if (b === DiskStore.EMPTY) {
                failed.add(k)
                return false
            }
            if (b != null) {
                mem.put(k, b)
                return true
            }
        }
        val g = generate(k, item, src) ?: return false
        if (toMem) mem.put(k, g) else g.recycle()
        return true
    }

    /** Falta no disco (para estimar o espaço antes da pré-carga). */
    internal fun missingOnDisk(item: FileItem, src: FileSource): Boolean {
        val k = key(src, item)
        return !isFailed(k) && !existsStore(src, k)
    }

    fun thumbsDir(inFiles: Boolean) = File(if (inFiles) app.filesDir else app.cacheDir, "thumbs")

    /** Chave do disco/memória; o sufixo marca o tamanho ("" 256 px, "L" 384 px, "X" 768 px). */
    fun key(src: FileSource, item: FileItem, t: Int = tier): String = key(src.cacheTag, item, t)

    private fun key(tag: String, item: FileItem, t: Int): String =
        hash("$tag|${item.id}|${item.modified}|${item.size}") + TIER_SUFFIX[t]

    /**
     * Mostra a thumb de [item] em [view] (ou o [icon] genérico enquanto/se não houver).
     * Cache em memória responde na hora, sem trocar de thread.
     */
    fun bind(view: ImageView, item: FileItem, src: FileSource, icon: Int) {
        cancel(view)
        if (!hasThumb(item)) {
            showIcon(view, icon)
            return
        }
        val k = key(src, item)
        val hit = mem.get(k)
        if (hit != null) {
            showBitmap(view, hit)
            return
        }
        showIcon(view, icon)
        if (isFailed(k)) return
        val req = Request(k, item, src, view)
        view.setTag(R.id.thumb_request, req)
        diskPool.execute(req)
    }

    fun cancel(view: ImageView) {
        (view.getTag(R.id.thumb_request) as? Request)?.cancelled = true
        view.setTag(R.id.thumb_request, null)
    }

    private fun showIcon(v: ImageView, icon: Int) {
        v.scaleType = ImageView.ScaleType.CENTER
        v.setImageResource(icon)
        track(v, null)
    }

    private fun showBitmap(v: ImageView, b: Bitmap) {
        // Saiu do cache e foi liberada entre a carga e esta entrega: fica o ícone.
        if (b.isRecycled) return
        v.scaleType = if ((v as? app.index.files.ui.ThumbView)?.fit == true) ImageView.ScaleType.FIT_CENTER else ImageView.ScaleType.CENTER_CROP
        v.setImageBitmap(b)
        track(v, b)
    }

    private inner class Request(
        val key: String,
        val item: FileItem,
        val src: FileSource,
        val view: ImageView,
    ) : Runnable {
        @Volatile var cancelled = false
        private var generating = false

        override fun run() {
            if (cancelled) return
            if (!generating) {
                val b = runCatching { readStore(src, key) }.getOrNull()
                when {
                    b === DiskStore.EMPTY -> failed.add(key)
                    b != null -> deliver(b)
                    else -> {
                        generating = true
                        genPool.execute(this)
                    }
                }
                return
            }
            generate(key, item, src)?.let { deliver(it) }
        }

        private fun deliver(b: Bitmap) {
            mem.put(key, b)
            Bg.main.post {
                if (!cancelled && view.getTag(R.id.thumb_request) === this) {
                    view.setTag(R.id.thumb_request, null)
                    showBitmap(view, b)
                }
            }
        }
    }

    /** Gera, grava no disco e devolve a versão para exibição (RGB_565). */
    private fun generate(key: String, item: FileItem, src: FileSource): Bitmap? {
        val side = when {
            key.endsWith("X") -> MAX_SIDE_XL
            key.endsWith("L") -> MAX_SIDE_LARGE
            else -> MAX_SIDE
        }
        val raw = try {
            src.loadThumbnail(item, side, null)
        } catch (e: java.io.IOException) {
            // Rede (Drive) ou E/S: falha passageira, não grava o marcador permanente.
            netFailedAt = System.currentTimeMillis()
            netFailed.add(key)
            return null
        } catch (e: Exception) {
            null
        } catch (e: OutOfMemoryError) {
            null
        }
        if (raw == null) {
            writeEmptyStore(src, key)
            failed.add(key)
            return null
        }
        val scaled = Decode.scaleDown(raw, side)
        runCatching { writeStore(src, key, scaled) }
        return Decode.toDisplay(scaled)
    }

    /** Apaga do disco as thumbs dos arquivos de uma pasta raiz (via cache de listagem). */
    fun clearRoot(rootId: Long, src: FileSource) {
        val keys = ArrayList<String>()
        AppDb.get(app).readableDatabase.rawQuery(
            "SELECT doc_id, mime, size, modified, name FROM files WHERE root_id=? AND (mime LIKE 'image/%' OR mime LIKE 'video/%')",
            arrayOf(rootId.toString()),
        ).use { c ->
            while (c.moveToNext()) {
                val it = FileItem(c.getString(0), c.getString(4), c.getString(1), c.getLong(2), c.getLong(3), false, "")
                for (t in TIER_SUFFIX.indices) keys.add(key(src, it, t))
            }
        }
        storeFor(src).delete(keys)
        mem.evictAll()
        failed.clear()
        netFailed.clear()
    }

    /**
     * Miniaturas antigas de arquivos do Drive que mudaram ou sumiram (registro de mudanças do
     * Drive): [old] traz o id, a data e o tamanho de antes. Fora da main.
     */
    fun forgetDrive(old: List<FileItem>) {
        val keys = old.flatMap { item -> TIER_SUFFIX.indices.map { key(DRIVE_TAG, item, it) } }
        if (keys.isEmpty()) return
        for (k in keys) {
            mem.remove(k)
            failed.remove(k)
            netFailed.remove(k)
        }
        driveDisk.delete(keys)
    }

    /** Desconectou do Drive: as miniaturas da conta saem do aparelho. */
    fun clearDrive() {
        driveDisk.clear()
        mem.evictAll()
        failed.clear()
        netFailed.clear()
    }

    /** Arquivos apagados, movidos ou renomeados: as thumbs antigas não servem mais. */
    fun forget(src: FileSource, items: List<FileItem>) {
        // Todos os tamanhos (256, 384 e 768 px).
        val keys = items.filter(::hasThumb).flatMap { item -> TIER_SUFFIX.indices.map { key(src, item, it) } }
        if (keys.isEmpty()) return
        for (k in keys) {
            mem.remove(k)
            failed.remove(k)
            netFailed.remove(k)
        }
        if (src is VaultSource) vault.delete(src.entry.id, keys) else storeFor(src).delete(keys)
    }

    /** Volta a tentar as que falharam nesta sessão (ex.: depois de reconectar ao Drive). */
    fun retryFailed() {
        failed.clear()
        netFailed.clear()
    }

    fun clearAll() {
        disk.clear()
        driveDisk.clear()
        mem.evictAll()
        failed.clear()
        netFailed.clear()
    }

    /** Troca o local do cache (cacheDir ↔ filesDir), apagando o antigo. */
    fun relocate(inFiles: Boolean) {
        val old = thumbsDir(prefs.thumbsInFiles)
        prefs.thumbsInFiles = inFiles
        disk.clear(old)
        old.delete()
        mem.evictAll()
    }

    companion object {
        const val MAX_SIDE = 256
        /** Colunas largas (poucas colunas): miniaturas de 384 px; uma por tela: 768 px. */
        const val MAX_SIDE_LARGE = 384
        const val MAX_SIDE_XL = 768
        private val TIER_SUFFIX = arrayOf("", "L", "X")
        private const val MEM_CAP = 32L * 1024 * 1024

        /** 0 = 256 px, 1 = 384 px, 2 = 768 px. */
        @Volatile var tier = 0
            private set

        /** Troca o tamanho das miniaturas geradas; true se mudou (a tela precisa religar). */
        fun setTier(t: Int): Boolean {
            if (tier == t) return false
            tier = t
            return true
        }

        @Volatile private var instance: ThumbnailManager? = null

        fun get(ctx: Context): ThumbnailManager = instance ?: synchronized(this) {
            instance ?: ThumbnailManager(ctx).also { instance = it }
        }

        /**
         * App foi para segundo plano (ou o sistema pediu memória): solta as miniaturas da
         * RAM. Continuam no disco e voltam em milissegundos ao reabrir a tela.
         */
        fun trimMemory() {
            Preload.release()
            instance?.mem?.evictAll()
        }

        internal fun factoryFor(name: String, priority: Int) = factory(name, priority)

        private const val DRIVE_TAG = "drive"
        private const val NET_RETRY_MS = 60_000L

        /** Drive comum (não a pasta protegida, que tem as miniaturas cifradas à parte). */
        fun isDrive(src: FileSource) = src.cacheTag == DRIVE_TAG

        fun hasThumb(item: FileItem) = item.kind == FileItem.KIND_IMAGE || item.kind == FileItem.KIND_VIDEO

        /** FNV-1a 64 bits: rápido, sem MessageDigest; colisões desprezíveis aqui. */
        private fun hash(s: String): String {
            var h = -0x340d631b7bdddcdbL
            for (c in s) {
                h = h xor c.code.toLong()
                h *= 0x100000001b3L
            }
            return java.lang.Long.toHexString(h)
        }

        private fun factory(name: String, priority: Int) = ThreadFactory { r ->
            Thread(r, name).apply {
                this.priority = priority
                isDaemon = true
            }
        }

        /** Pool cuja fila funciona como pilha (o mais recente sai primeiro). */
        private fun lifoPool(n: Int, name: String, priority: Int): ThreadPoolExecutor {
            val stack = object : LinkedBlockingDeque<Runnable>() {
                override fun offer(e: Runnable) = offerFirst(e)
            }
            return ThreadPoolExecutor(n, n, 30, TimeUnit.SECONDS, stack, factory(name, priority)).apply {
                allowCoreThreadTimeOut(true)
            }
        }
    }
}
