package app.index.files.thumbs

import android.app.ActivityManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.SystemClock
import app.index.files.Bg
import app.index.files.data.FileItem
import app.index.files.data.FileSource
import app.index.files.data.Prefs
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * "Carregar todas as miniaturas" (item H). Só age quando pedido: gera/baixa no disco as
 * miniaturas que faltam na lista atual (nunca os originais) e as carrega na RAM até o
 * orçamento. Se não couber tudo, depois mantém uma janela de ~3 telas à frente e atrás da
 * rolagem. A RAM nunca passa do orçamento (o LruCache comum só ganha um teto maior durante
 * a sessão) e é liberada pelos prazos configurados ou por falta de memória; o disco fica.
 *
 * Estado e chamadas na thread principal; o trabalho roda em [THREADS] tarefas em paralelo.
 */
object Preload {

    fun interface Listener {
        fun onPreloadChanged()
    }

    private const val THREADS = 3
    private const val WINDOW_SCREENS = 3
    private const val NET_CHECK_MS = 5_000L

    private val listeners = CopyOnWriteArraySet<Listener>()
    fun addListener(l: Listener) = listeners.add(l)
    fun removeListener(l: Listener) = listeners.remove(l)

    private val pool = ThreadPoolExecutor(
        THREADS, THREADS, 30, TimeUnit.SECONDS, LinkedBlockingQueue(),
        ThumbnailManager.factoryFor("thumb-preload", Thread.MIN_PRIORITY + 1),
    ).apply { allowCoreThreadTimeOut(true) }

    private var app: Context? = null
    private var tm: ThumbnailManager? = null

    /** Identifica a lista pré-carregada (pasta, pesquisa, seleção sorteada). */
    var listKey: String? = null
        private set

    /** Pré-carga rodando. */
    var running = false
        private set

    /** Sessão segurando RAM extra (rodando ou terminada e ainda não liberada). */
    var holding = false
        private set

    private val doneCount = AtomicInteger()
    private val skippedCount = AtomicInteger()
    private val inRamCount = AtomicInteger()
    val done get() = doneCount.get()
    var total = 0
        private set
    val skippedNoWifi get() = skippedCount.get()
    val inRam get() = inRamCount.get()

    /** Bytes que as miniaturas ocupam agora na RAM. */
    val memBytes: Long get() = tm?.memUsed()?.toLong() ?: 0L

    /** Tudo coube na RAM (sem janela de rolagem). */
    @Volatile var fitsAll = true
        private set
    private var budget = 0

    @Volatile private var token = 0
    @Volatile private var windowToken = 0

    // ---- Início / fim ----

    /**
     * Começa a pré-carga de [items] (na ordem da lista), a partir da posição [first] visível:
     * as mais próximas da tela primeiro.
     */
    fun start(ctx: Context, key: String, items: List<FileItem>, sourceFor: (FileItem) -> FileSource, first: Int) {
        release()
        val a = ctx.applicationContext
        val t = ThumbnailManager.get(a)
        app = a
        tm = t
        listKey = key
        val entries = items.mapIndexedNotNull { i, it ->
            if (ThumbnailManager.hasThumb(it)) Triple(i, it, sourceFor(it)) else null
        }
        total = entries.size
        doneCount.set(0)
        skippedCount.set(0)
        inRamCount.set(0)
        fitsAll = true
        budget = budgetBytes(a, t)
        t.setMemLimit(budget)
        running = true
        holding = true
        touch()
        cancelLeave()
        val tk = ++token
        // Do centro (o que está na tela) para fora.
        val ordered = entries.sortedBy { kotlin.math.abs(it.first - first) }
        val queue = ConcurrentLinkedQueue(ordered)
        val left = AtomicInteger(THREADS)
        val wifiOnly = Prefs.get(a).preloadWifiOnly
        val net = NetCheck(a)
        notifyChanged()
        repeat(THREADS) {
            pool.execute {
                try {
                    while (tk == token) {
                        val (_, item, src) = queue.poll() ?: break
                        if (wifiOnly && isDrive(src) && !net.wifi()) {
                            skippedCount.incrementAndGet()
                        } else {
                            // Enquanto couber no orçamento (90%), vai para a RAM; depois só disco.
                            val toMem = fitsAll && t.memUsed() < budget * 9L / 10
                            if (!toMem) fitsAll = false
                            val ok = runCatching { t.preloadOne(item, src, toMem) }.getOrDefault(false)
                            if (ok && toMem) inRamCount.incrementAndGet()
                        }
                        doneCount.incrementAndGet()
                        progress()
                    }
                } finally {
                    if (left.decrementAndGet() == 0) Bg.main.post { if (tk == token) finished() }
                }
            }
        }
    }

    private fun finished() {
        running = false
        notifyChanged()
    }

    /** Cancela a pré-carga e solta a RAM extra (as miniaturas no disco ficam). */
    fun release() {
        token++
        windowToken++
        cancelLeave()
        Bg.main.removeCallbacks(bgRelease)
        Bg.main.removeCallbacks(idleCheck)
        val was = holding || running
        running = false
        holding = false
        listKey = null
        tm?.resetMemLimit()
        if (was) notifyChanged()
    }

    // ---- Janela de rolagem (quando não coube tudo) ----

    /** A lista [key] rolou: carrega do disco os itens de [window] que não estão na RAM. */
    fun window(key: String, window: List<FileItem>, sourceFor: (FileItem) -> FileSource) {
        if (!holding || running || fitsAll || key != listKey) return
        touch()
        val t = tm ?: return
        val wk = ++windowToken
        // Origem resolvida aqui (thread principal); o trabalho vai para o pool.
        val todo = window.filter(ThumbnailManager::hasThumb).map { it to sourceFor(it) }
        pool.execute {
            for ((item, src) in todo) {
                if (wk != windowToken) return@execute
                runCatching { t.preloadOne(item, src, toMem = true) }
            }
        }
    }

    fun needsWindow(key: String) = holding && !running && !fitsAll && key == listKey

    /** Quantos itens de cada lado da tela entram na janela. */
    fun windowSpan(visible: Int) = visible * WINDOW_SCREENS

    // ---- Prazos de liberação ----

    private val leaveRelease = Runnable { release() }
    private val bgRelease = Runnable { release() }
    private var lastTouch = 0L
    private val idleCheck = object : Runnable {
        override fun run() {
            if (!holding) return
            val limit = minutes { releaseIdleMin }
            val idle = SystemClock.elapsedRealtime() - lastTouch
            if (idle >= limit) release() else Bg.main.postDelayed(this, limit - idle)
        }
    }

    private fun minutes(pick: Prefs.() -> Int): Long =
        (app?.let { Prefs.get(it).pick() } ?: 5).coerceAtLeast(1) * 60_000L

    /** A lista [key] voltou à tela: cancela a liberação por saída. */
    fun enter(key: String) {
        if (key == listKey) cancelLeave()
    }

    /** A lista [key] saiu da tela (outra pasta, outra tela). */
    fun leave(key: String) {
        if (!holding || key != listKey) return
        cancelLeave()
        Bg.main.postDelayed(leaveRelease, minutes { releaseLeaveMin })
    }

    private fun cancelLeave() = Bg.main.removeCallbacks(leaveRelease)

    fun appHidden() {
        if (holding) Bg.main.postDelayed(bgRelease, minutes { releaseBgMin })
    }

    fun appShown() {
        Bg.main.removeCallbacks(bgRelease)
        if (holding) touch()
    }

    /** Interação do usuário: adia a liberação por inatividade. */
    fun touch() {
        lastTouch = SystemClock.elapsedRealtime()
        if (holding) {
            Bg.main.removeCallbacks(idleCheck)
            Bg.main.postDelayed(idleCheck, minutes { releaseIdleMin })
        }
    }

    // ---- Auxiliares ----

    /** Orçamento: 1/4 da classe de memória do app (sem largeHeap), no máximo 1/3 do heap. */
    private fun budgetBytes(ctx: Context, t: ThumbnailManager): Int {
        val am = ctx.getSystemService(ActivityManager::class.java)
        val byClass = am.memoryClass.toLong() * 1024 * 1024 / 4
        val byHeap = Runtime.getRuntime().maxMemory() / 3
        return minOf(byClass, byHeap).coerceAtLeast(t.normalBytes.toLong()).toInt()
    }

    private fun isDrive(src: FileSource) = src.cacheTag == "drive" || src.cacheTag == "vault:drive"

    @Volatile private var progressPosted = false

    private fun progress() {
        if (progressPosted) return
        progressPosted = true
        Bg.main.postDelayed({
            progressPosted = false
            notifyChanged()
        }, 150)
    }

    private fun notifyChanged() {
        for (l in listeners) l.onPreloadChanged()
    }

    /** Wi-Fi (ou cabo) agora? Consultado no máximo a cada 5 s. */
    private class NetCheck(ctx: Context) {
        private val cm = ctx.getSystemService(ConnectivityManager::class.java)
        @Volatile private var at = 0L
        @Volatile private var ok = false

        fun wifi(): Boolean {
            val now = SystemClock.elapsedRealtime()
            if (now - at > NET_CHECK_MS) {
                ok = runCatching {
                    val caps = cm.getNetworkCapabilities(cm.activeNetwork)
                    caps != null && (
                        caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
                        )
                }.getOrDefault(false)
                at = now
            }
            return ok
        }
    }
}
