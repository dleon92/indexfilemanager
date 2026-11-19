package app.index.files.ui

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import app.index.files.R
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * Seleção por arraste: toque longo num item e, sem soltar, arrastar desenha uma área
 * retangular; todos os itens que ela toca entram na seleção (ou saem, se o arraste
 * começou num item que ficou desmarcado). Perto da borda de cima/baixo a lista rola
 * sozinha, mais rápido quanto mais perto da borda, e a área acompanha a rolagem.
 *
 * As células da grade têm o mesmo tamanho (lista = 1 coluna), então a área é um bloco
 * de linhas × colunas calculado por posição: itens fora da tela também entram.
 */
class DragSelect(
    private val rv: RecyclerView,
    private val cb: Callback,
) : RecyclerView.ItemDecoration(), RecyclerView.OnItemTouchListener {

    interface Callback {
        fun isSelectedAt(pos: Int): Boolean
        fun setSelectedAt(pos: Int, on: Boolean)
        /** Fim de um lote de mudanças (atualiza título/menu). */
        fun onDragChanged()
    }

    private val d = rv.resources.displayMetrics.density
    private val fillPaint = Paint().apply { color = rv.context.getColor(R.color.accent); alpha = 0x30 }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = rv.context.getColor(R.color.accent)
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * d
    }
    private val band = RectF()

    private var lastX = 0f
    private var lastY = 0f

    /** Toque longo aconteceu; o próximo movimento começa a área. */
    private var armed = false
    private var dragging = false
    private var selectMode = true
    private var anchorRow = 0
    private var anchorCol = 0
    private var anchorX = 0f
    /** Y do ponto inicial no momento do toque longo; a rolagem desde então é [scrolled]. */
    private var anchorY = 0f
    private var scrolled = 0

    /** Bloco atual (linhas e colunas inclusivas); vazio = rowFrom > rowTo. */
    private var rowFrom = 0
    private var rowTo = -1
    private var colFrom = 0
    private var colTo = -1

    /** Estado de cada posição tocada antes do arraste (para desfazer ao encolher). */
    private val before = HashMap<Int, Boolean>()

    init {
        rv.addItemDecoration(this)
        rv.addOnItemTouchListener(this)
        rv.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(r: RecyclerView, dx: Int, dy: Int) {
                if (dragging) scrolled += dy
            }
        })
    }

    private val lm get() = rv.layoutManager as GridLayoutManager

    /**
     * Chamado pelo toque longo do item [pos], depois de o item já ter sido
     * marcado/desmarcado como de costume. O arraste repete esse estado.
     */
    fun arm(pos: Int) {
        if (pos < 0) return
        val span = lm.spanCount
        armed = true
        selectMode = cb.isSelectedAt(pos)
        anchorRow = pos / span
        anchorCol = pos % span
        anchorX = lastX
        anchorY = lastY
    }

    override fun onInterceptTouchEvent(rv: RecyclerView, e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                armed = false
                lastX = e.x; lastY = e.y
            }
            MotionEvent.ACTION_MOVE -> {
                lastX = e.x; lastY = e.y
                if (armed && !dragging) {
                    start()
                    return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> armed = false
        }
        return dragging
    }

    override fun onTouchEvent(rv: RecyclerView, e: MotionEvent) {
        when (e.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                lastX = e.x; lastY = e.y
                update()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> finish()
        }
    }

    override fun onRequestDisallowInterceptTouchEvent(disallowIntercept: Boolean) {}

    private fun start() {
        armed = false
        dragging = true
        scrolled = 0
        before.clear()
        rowFrom = 0; rowTo = -1; colFrom = 0; colTo = -1
        rv.parent?.requestDisallowInterceptTouchEvent(true)
        rv.stopScroll()
        update()
        rv.postOnAnimation(autoScroll)
    }

    private fun finish() {
        if (!dragging) return
        dragging = false
        rv.removeCallbacks(autoScroll)
        before.clear()
        rv.invalidateItemDecorations()
        cb.onDragChanged()
    }

    /** Rolagem automática: até ~70 dp de cada borda, velocidade proporcional à profundidade. */
    private val autoScroll = object : Runnable {
        override fun run() {
            if (!dragging) return
            val edge = 72 * d
            val h = rv.height.toFloat()
            val depth = when {
                lastY < edge -> -(edge - lastY) / edge
                lastY > h - edge -> (lastY - (h - edge)) / edge
                else -> 0f
            }.coerceIn(-1.6f, 1.6f)
            if (depth != 0f) {
                val step = (depth * abs(depth) * 28 * d).toInt().let { if (it == 0) (if (depth > 0) 1 else -1) else it }
                rv.scrollBy(0, step)
                update()
            }
            rv.postOnAnimation(this)
        }
    }

    /** Linha e coluna da célula sob (x, y), pela geometria de uma célula visível. */
    private fun cellAt(x: Float, y: Float): Pair<Int, Int>? {
        val lm = lm
        val span = lm.spanCount
        val count = rv.adapter?.itemCount ?: 0
        if (count == 0) return null
        val first = lm.findFirstVisibleItemPosition()
        if (first < 0) return null
        val ref = lm.findViewByPosition(first) ?: return null
        val rowH = (lm.getDecoratedBottom(ref) - lm.getDecoratedTop(ref)).coerceAtLeast(1)
        val refRow = first / span
        val lastRow = (count - 1) / span
        val row = (refRow + floor((y - lm.getDecoratedTop(ref)) / rowH).toInt()).coerceIn(0, lastRow)
        val inner = (rv.width - rv.paddingLeft - rv.paddingRight).coerceAtLeast(1)
        val col = floor((x - rv.paddingLeft) / (inner.toFloat() / span)).toInt().coerceIn(0, span - 1)
        return row to col
    }

    private fun inBlock(pos: Int, span: Int, r0: Int, r1: Int, c0: Int, c1: Int): Boolean {
        val r = pos / span
        val c = pos % span
        return r in r0..r1 && c in c0..c1
    }

    private fun update() {
        val (row, col) = cellAt(lastX, lastY) ?: return
        val span = lm.spanCount
        val count = rv.adapter?.itemCount ?: 0
        val nr0 = min(anchorRow, row)
        val nr1 = max(anchorRow, row)
        val nc0 = min(anchorCol, col)
        val nc1 = max(anchorCol, col)
        rv.invalidateItemDecorations()
        if (nr0 == rowFrom && nr1 == rowTo && nc0 == colFrom && nc1 == colTo) return
        // Só as posições da união dos dois blocos podem mudar.
        val ur0 = if (rowFrom > rowTo) nr0 else min(nr0, rowFrom)
        val ur1 = if (rowFrom > rowTo) nr1 else max(nr1, rowTo)
        val uc0 = if (colFrom > colTo) nc0 else min(nc0, colFrom)
        val uc1 = if (colFrom > colTo) nc1 else max(nc1, colTo)
        var changed = false
        for (r in ur0..ur1) {
            for (c in uc0..uc1) {
                val p = r * span + c
                if (p >= count) break
                val wasIn = rowFrom <= rowTo && inBlock(p, span, rowFrom, rowTo, colFrom, colTo)
                val isIn = inBlock(p, span, nr0, nr1, nc0, nc1)
                if (wasIn == isIn) continue
                if (isIn) {
                    val cur = cb.isSelectedAt(p)
                    if (!before.containsKey(p)) before[p] = cur
                    if (cur != selectMode) { cb.setSelectedAt(p, selectMode); changed = true }
                } else {
                    val orig = before[p] ?: continue
                    if (cb.isSelectedAt(p) != orig) { cb.setSelectedAt(p, orig); changed = true }
                }
            }
        }
        rowFrom = nr0; rowTo = nr1; colFrom = nc0; colTo = nc1
        if (changed) cb.onDragChanged()
    }

    override fun onDrawOver(c: Canvas, parent: RecyclerView, state: RecyclerView.State) {
        if (!dragging) return
        val ay = anchorY - scrolled
        band.set(min(anchorX, lastX), min(ay, lastY), max(anchorX, lastX), max(ay, lastY))
        c.drawRect(band, fillPaint)
        c.drawRect(band, strokePaint)
    }
}
