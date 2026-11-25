package app.index.files.ui

import android.annotation.SuppressLint
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import app.index.files.data.Prefs
import app.index.files.thumbs.ThumbnailManager

/**
 * Tamanho da grade (fase 12). As colunas saem da largura disponível dividida pelo tamanho
 * mínimo da miniatura ([Prefs.thumbDp]) e são recalculadas sempre que a lista muda de
 * tamanho (girar, multi-janela, painel lateral), mantendo o item do topo no lugar.
 *
 * Pinça (ou Ctrl + roda do mouse, Ctrl + / Ctrl −) anda pelos degraus, do menor ao maior:
 * lista com detalhes → grade de 64 dp → ... → uma imagem por tela (imagem inteira).
 * O item sob os dedos fica onde estava.
 */
@SuppressLint("ClickableViewAccessibility")
class GridZoom(
    private val rv: RecyclerView,
    private val lm: GridLayoutManager,
    private val adapter: FileAdapter,
    private val prefs: Prefs,
    /** Grade ↔ lista mudou pela pinça (o menu troca o ícone). */
    private val onModeChanged: () -> Unit,
) : RecyclerView.OnItemTouchListener {

    private val density = rv.resources.displayMetrics.density
    private var lastW = 0
    private var lastH = 0

    private val scaler = ScaleGestureDetector(rv.context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        private var acc = 1f

        override fun onScaleBegin(d: ScaleGestureDetector): Boolean {
            acc = 1f
            return true
        }

        override fun onScale(d: ScaleGestureDetector): Boolean {
            acc *= d.scaleFactor
            // Abrir os dedos = maior; um degrau a cada ~25%.
            if (acc > 1.25f) {
                step(+1, d.focusX, d.focusY)
                acc = 1f
            } else if (acc < 0.8f) {
                step(-1, d.focusX, d.focusY)
                acc = 1f
            }
            return true
        }
    })
    private var scaling = false

    init {
        rv.addOnItemTouchListener(this)
        rv.addOnLayoutChangeListener { _, l, t, r, b, _, _, _, _ ->
            if (r - l != lastW || b - t != lastH) rv.post { apply() }
        }
        rv.setOnGenericMotionListener { _, e ->
            if (e.isFromSource(InputDevice.SOURCE_CLASS_POINTER) && e.actionMasked == MotionEvent.ACTION_SCROLL &&
                e.metaState and KeyEvent.META_CTRL_ON != 0
            ) {
                val v = e.getAxisValue(MotionEvent.AXIS_VSCROLL)
                if (v != 0f) step(if (v > 0) +1 else -1, e.x, e.y)
                true
            } else {
                false
            }
        }
    }

    private fun innerWidth(): Int =
        (if (rv.width > 0) rv.width else estimate()) - rv.paddingLeft - rv.paddingRight

    /** Colunas para a largura atual (ou estimada, antes da primeira medição). */
    fun span(): Int {
        if (!adapter.grid) return 1
        if (prefs.thumbDp >= FILL) return 1
        return (innerWidth() / (prefs.thumbDp * density)).toInt().coerceIn(1, 30)
    }

    /** Largura provável antes do layout: a área de conteúdo da Activity. */
    private fun estimate(): Int = (rv.context as? MainActivity)?.contentWidthPx()
        ?: rv.resources.displayMetrics.widthPixels

    /**
     * Recalcula colunas, altura da coluna única e tamanho das miniaturas, mantendo o item
     * [anchorPos] (ou o do topo) na mesma altura. [force]: reposiciona mesmo sem mudar colunas.
     */
    fun apply(anchorPos: Int = -1, anchorTop: Int = 0, force: Boolean = false) {
        lastW = rv.width
        lastH = rv.height
        val span = span()
        val cell = if (adapter.grid) innerWidth() / span else 0
        // Uma por tela: a imagem inteira cabe na altura visível (descontado o nome).
        val fit = if (adapter.grid && span == 1 && rv.height > 0) {
            (rv.height - rv.paddingTop - (NAME_DP * density).toInt()).coerceAtLeast(1)
        } else {
            0
        }
        val tier = when {
            cell > XL_CELL_PX -> 2
            cell > LARGE_CELL_PX -> 1
            else -> 0
        }
        val rebind = ThumbnailManager.setTier(tier) or (adapter.fitHeight != fit)
        adapter.fitHeight = fit
        if (lm.spanCount == span && !force) {
            if (rebind) adapter.refreshThumbs()
            return
        }
        var pos = anchorPos
        var top = anchorTop
        if (pos < 0) {
            pos = lm.findFirstVisibleItemPosition()
            top = pos.takeIf { it >= 0 }?.let { lm.findViewByPosition(it)?.top } ?: 0
        }
        lm.spanCount = span
        if (rebind) adapter.refreshThumbs()
        if (pos >= 0) lm.scrollToPositionWithOffset(pos, top - rv.paddingTop)
    }

    private fun stepIndex(): Int = STEPS.indexOfFirst { it >= prefs.thumbDp }.let { if (it < 0) STEPS.size - 1 else it }

    /**
     * Um degrau maior (+1) ou menor (−1), com o item em (x, y) parado sob o ponto. Degraus
     * que não mudam o número de colunas nesta largura são pulados.
     */
    fun step(dir: Int, x: Float = rv.width / 2f, y: Float = rv.height / 2f) {
        val child = rv.findChildViewUnder(x, y)
        val pos = child?.let { rv.getChildAdapterPosition(it) } ?: RecyclerView.NO_POSITION
        val top = child?.top ?: 0
        val anchor = { force: Boolean -> if (pos != RecyclerView.NO_POSITION) apply(pos, top, force) else apply(force = force) }

        if (!adapter.grid) {
            // Lista é o degrau mais baixo: abrir os dedos volta para a menor grade.
            if (dir > 0) {
                prefs.thumbDp = STEPS[0]
                setGrid(true)
                anchor(true)
            }
            return
        }
        val oldSpan = span()
        val oldFit = prefs.thumbDp >= FILL
        var i = stepIndex()
        while (true) {
            val ni = i + dir
            if (ni < 0) {
                // Abaixo da menor grade: lista com detalhes.
                setGrid(false)
                anchor(true)
                return
            }
            if (ni >= STEPS.size) return
            i = ni
            prefs.thumbDp = STEPS[i]
            if (span() != oldSpan || (prefs.thumbDp >= FILL) != oldFit) break
        }
        anchor(false)
    }

    /** Modo fixo pelo teclado (Ctrl+Shift+1…6): [dp] da miniatura; 0 = lista com detalhes. */
    fun setMode(dp: Int) {
        if (dp == 0) {
            if (adapter.grid) {
                setGrid(false)
                apply(force = true)
            }
            return
        }
        prefs.thumbDp = dp
        if (!adapter.grid) setGrid(true)
        apply(force = true)
    }

    private fun setGrid(on: Boolean) {
        adapter.grid = on
        prefs.viewGrid = on
        onModeChanged()
    }

    override fun onInterceptTouchEvent(rv: RecyclerView, e: MotionEvent): Boolean {
        scaler.onTouchEvent(e)
        if (scaler.isInProgress && !scaling) {
            scaling = true
            rv.stopScroll()
            rv.parent?.requestDisallowInterceptTouchEvent(true)
        }
        if (e.actionMasked == MotionEvent.ACTION_UP || e.actionMasked == MotionEvent.ACTION_CANCEL) scaling = false
        return scaling
    }

    override fun onTouchEvent(rv: RecyclerView, e: MotionEvent) {
        scaler.onTouchEvent(e)
        if (e.actionMasked == MotionEvent.ACTION_UP || e.actionMasked == MotionEvent.ACTION_CANCEL) scaling = false
    }

    override fun onRequestDisallowInterceptTouchEvent(disallowIntercept: Boolean) {}

    companion object {
        /** Uma imagem por tela (sempre 1 coluna). */
        const val FILL = 10_000

        /** Degraus de tamanho mínimo da miniatura (dp); o último é "uma por tela". */
        val STEPS = intArrayOf(64, 80, 96, 112, 128, 160, 200, 256, 320, 400, 560, FILL)
        private const val LARGE_CELL_PX = 420
        private const val XL_CELL_PX = 700
        /** Altura da linha do nome sob a miniatura (aprox.). */
        private const val NAME_DP = 40
    }
}
