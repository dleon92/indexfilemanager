package app.index.files.ui.doc

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.util.LruCache
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import app.index.files.Bg
import java.util.concurrent.Executors

/**
 * PDF pelo renderizador do próprio Android: páginas numa lista vertical, desenhadas sob demanda
 * numa thread só (o PdfRenderer não aceita duas ao mesmo tempo), na largura da tela.
 *
 * Zoom (pinça ou toque duplo) escala o desenho da lista inteira; a rolagem vertical continua sendo a
 * da lista e a horizontal é um deslocamento próprio. Parado o zoom, as páginas visíveis são
 * desenhadas de novo com mais resolução.
 */
@SuppressLint("ViewConstructor")
class PdfView(ctx: Context, private val renderer: PdfRenderer, private val onPage: (Int, Int) -> Unit) : RecyclerView(ctx) {
    private val exec = Executors.newSingleThreadExecutor { r -> Thread(r, "pdf").apply { priority = Thread.NORM_PRIORITY - 1 } }
    val pageCount = renderer.pageCount

    /** Proporção (altura/largura) de cada página; até ser lida, a da primeira. */
    private val ratio = FloatArray(pageCount)
    private val gap = (8 * resources.displayMetrics.density).toInt()
    private val lm = LinearLayoutManager(ctx)
    private val cache: LruCache<Int, Bitmap>
    private val pending = HashSet<Int>()
    /** Holders com página ligada, inclusive os pré-carregados e guardados fora da tela pela lista. */
    private val bound = HashSet<Holder>()
    @Volatile private var closed = false

    private var scale = 1f
    private var panX = 0f
    private var fracY = 0f
    private var renderScale = 1f

    init {
        val first = renderer.openPage(0).use { it.height.toFloat() / it.width }
        ratio.fill(first)
        val bytes = minOf(Runtime.getRuntime().maxMemory() / 5, 96L * 1024 * 1024).toInt()
        cache = object : LruCache<Int, Bitmap>(bytes) {
            override fun sizeOf(key: Int, value: Bitmap) = value.byteCount
        }
        layoutManager = lm
        setHasFixedSize(false)
        itemAnimator = null
        setBackgroundColor(0xFF5F6368.toInt())
        setPadding(0, gap, 0, gap)
        clipToPadding = false
        adapter = Pages()
        addOnScrollListener(object : OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) = report()
        })
        // Tamanho real de cada página, em segundo plano e em blocos: as páginas pedidas na tela
        // entram na fila entre um bloco e outro (não esperam o PDF inteiro).
        postDelayed({ scanSizes(1) }, 300)
    }

    private fun scanSizes(from: Int) {
        if (closed || from >= pageCount) return
        exec.execute {
            val changed = ArrayList<Int>()
            val to = minOf(pageCount, from + 40)
            for (i in from until to) {
                if (closed) return@execute
                val r = try { renderer.openPage(i).use { it.height.toFloat() / it.width } } catch (e: Exception) { continue }
                if (Math.abs(r - ratio[i]) > 0.001f) { ratio[i] = r; changed.add(i) }
            }
            Bg.main.post {
                if (closed) return@post
                changed.forEach { adapter?.notifyItemChanged(it) }
                scanSizes(to)
            }
        }
    }

    private fun report() {
        val y = (height / scale) / 2f
        val v = findChildViewUnder(width / 2f, y) ?: getChildAt(0) ?: return
        onPage(getChildAdapterPosition(v).coerceAtLeast(0), pageCount)
    }

    // ---- Páginas ----

    private class Holder(val img: ImageView) : ViewHolder(img) { var page = -1 }

    private inner class Pages : Adapter<Holder>() {
        override fun getItemCount() = pageCount

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val v = ImageView(parent.context)
            v.scaleType = ImageView.ScaleType.FIT_XY
            v.setBackgroundColor(Color.WHITE)
            return Holder(v)
        }

        override fun onBindViewHolder(h: Holder, position: Int) {
            h.page = position
            val w = width.coerceAtLeast(1)
            h.img.layoutParams = LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (w * ratio[position]).toInt()).apply {
                bottomMargin = gap
            }
            bound.add(h)
            h.img.setImageBitmap(null)
            show(h)
        }

        // A lista devolve à tela páginas pré-carregadas ou guardadas sem chamar onBind de novo:
        // aqui a imagem é conferida (pode ter ficado pronta, ou saído do cache, nesse meio-tempo).
        override fun onViewAttachedToWindow(h: Holder) = show(h)

        override fun onViewRecycled(h: Holder) {
            bound.remove(h)
            h.img.setImageBitmap(null)
        }
    }

    private fun show(h: Holder) {
        if (h.page < 0) return
        val bmp = cache.get(h.page)
        if (bmp != null) h.img.setImageBitmap(bmp)
        if (bmp == null || bmp.width < width * renderScale * 0.9f) render(h.page)
    }

    private fun render(page: Int) {
        val w = (width * renderScale).toInt().coerceIn(1, 4096)
        if (!pending.add(page)) return
        exec.execute {
            var bmp: Bitmap? = null
            if (!closed) try {
                renderer.openPage(page).use { p ->
                    val h = (w.toLong() * p.height / p.width).toInt().coerceIn(1, 8192)
                    // Limite de pixels por página (zoom alto em página longa).
                    val f = Math.min(1.0, Math.sqrt(12_000_000.0 / (w.toLong() * h)))
                    val bw = (w * f).toInt().coerceAtLeast(1)
                    val bh = (h * f).toInt().coerceAtLeast(1)
                    val b = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
                    b.eraseColor(Color.WHITE)
                    p.render(b, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    bmp = b
                }
            } catch (e: Throwable) { // inclusive falta de memória: a página fica em branco, o resto segue
            }
            Bg.main.post {
                pending.remove(page)
                val b = bmp ?: return@post
                if (closed) return@post
                cache.put(page, b)
                for (h in bound) if (h.page == page) h.img.setImageBitmap(b)
            }
        }
    }

    /** Depois do zoom: mais resolução nas páginas visíveis (até 2,5×). */
    private fun refineVisible() {
        val want = scale.coerceIn(1f, 2.5f)
        if (Math.abs(want - renderScale) < 0.2f && want <= renderScale) return
        renderScale = want
        for (i in 0 until childCount) {
            val h = getChildViewHolder(getChildAt(i)) as Holder
            val b = cache.get(h.page)
            if (b == null || b.width < width * renderScale * 0.9f) render(h.page)
        }
    }

    // ---- Zoom ----

    private val scaler = ScaleGestureDetector(ctx, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(d: ScaleGestureDetector): Boolean {
            zoomTo(scale * d.scaleFactor, d.focusX, d.focusY)
            return true
        }

        override fun onScaleEnd(d: ScaleGestureDetector) = refineVisible()
    })

    private val gestures = GestureDetector(ctx, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDoubleTap(e: MotionEvent): Boolean {
            zoomTo(if (scale > 1.05f) 1f else 2.5f, e.x, e.y)
            refineVisible()
            return true
        }

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            if (scale <= 1f || scaler.isInProgress) return false
            panBy(dx, dy)
            return true
        }

        override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
            if (scale <= 1f) return false
            fling(0, (-vy / scale).toInt())
            return true
        }
    })

    private fun zoomTo(target: Float, fx: Float, fy: Float) {
        val s = target.coerceIn(1f, 5f)
        if (s == scale) return
        // Mantém o ponto sob os dedos no lugar: horizontal pelo deslocamento, vertical pela rolagem.
        panX = ((panX + fx) * s / scale - fx).coerceIn(0f, width * s - width)
        scrollPrecise(fy / scale - fy / s)
        scale = s
        if (scale == 1f) panX = 0f
        // Com zoom só o alto da área da lista aparece: espaço extra no fim para chegar ao rodapé da última página.
        setPadding(0, gap, 0, gap + (height - height / scale).toInt())
        invalidate()
        report()
    }

    private fun panBy(dx: Float, dy: Float) {
        panX = (panX + dx).coerceIn(0f, width * scale - width)
        scrollPrecise(dy / scale)
        invalidate()
    }

    private fun scrollPrecise(dy: Float) {
        fracY += dy
        val n = fracY.toInt()
        if (n != 0) { scrollBy(0, n); fracY -= n }
    }

    private var multi = false

    override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
        if (e.pointerCount > 1 || scale > 1f) return true
        return super.onInterceptTouchEvent(e)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        scaler.onTouchEvent(e)
        if (e.actionMasked == MotionEvent.ACTION_POINTER_DOWN) multi = true
        val handled = gestures.onTouchEvent(e)
        if (e.actionMasked == MotionEvent.ACTION_UP || e.actionMasked == MotionEvent.ACTION_CANCEL) multi = false
        // Sem zoom e com um dedo: a rolagem normal da lista.
        if (scale <= 1f && !multi && !scaler.isInProgress) return super.onTouchEvent(e) || handled
        if (scaler.isInProgress && e.actionMasked != MotionEvent.ACTION_UP) {
            // Cancela a rolagem da lista que tenha começado antes do segundo dedo.
            stopScroll()
        }
        return true
    }

    override fun dispatchDraw(c: Canvas) {
        if (scale == 1f && panX == 0f) return super.dispatchDraw(c)
        c.save()
        c.translate(-panX, 0f)
        c.scale(scale, scale)
        super.dispatchDraw(c)
        c.restore()
    }

    // Teclado: Page Up/Down, setas.
    fun pageBy(dir: Int) = smoothScrollBy(0, (dir * height * 0.9f / scale).toInt())

    fun close() {
        closed = true
        exec.execute { try { renderer.close() } catch (e: Exception) {} }
        exec.shutdown()
        cache.evictAll()
    }

    private inline fun <T> PdfRenderer.Page.use(block: (PdfRenderer.Page) -> T): T = try { block(this) } finally { close() }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        if (w != ow && ow != 0) {
            // Girou a tela: tudo na nova largura.
            cache.evictAll()
            panX = 0f
            scale = 1f
            renderScale = 1f
            post {
                setPadding(0, gap, 0, gap)
                adapter?.notifyDataSetChanged()
            }
        }
    }

}
