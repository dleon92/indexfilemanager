package app.index.files.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import app.index.files.R
import kotlin.math.max

/**
 * Barra de rolagem arrastável (sobreposta à lista; só a faixa de 48 dp à direita é tocável).
 * Aparece ao rolar listas longas e some após 1,5 s parada. Arrastar o
 * polegar pula direto para a posição; o balão mostra [labelFor] do item.
 * Toques fora do polegar passam para a lista normalmente.
 */
class FastScroller @JvmOverloads constructor(
    ctx: Context,
    attrs: AttributeSet? = null,
) : View(ctx, attrs) {

    /** Rótulo do balão para a posição (letra, data, tamanho…); null = sem balão. */
    var labelFor: ((Int) -> String?)? = null

    private val d = resources.displayMetrics.density
    private val thumbW = 6 * d
    private val thumbH = 56 * d
    private val margin = 4 * d
    private val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ctx.getColor(R.color.accent) }
    private val bubblePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ctx.getColor(R.color.accent) }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ctx.getColor(R.color.on_accent)
        textSize = 22 * resources.displayMetrics.scaledDensity
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }
    private val rect = RectF()

    private var rv: RecyclerView? = null
    private var shown = false
    private var dragging = false
    private var dragPos = 0

    private val hide = Runnable {
        if (!dragging) {
            shown = false
            invalidate()
        }
    }

    fun attach(list: RecyclerView) {
        rv = list
        list.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                if (dy != 0 && isLong()) show()
                invalidate()
            }

            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                if (newState == RecyclerView.SCROLL_STATE_IDLE) scheduleHide()
            }
        })
    }

    /** Só vale a pena em listas com mais de ~4 telas. */
    private fun isLong(): Boolean {
        val r = rv ?: return false
        return r.computeVerticalScrollRange() > r.height * 4
    }

    private fun show() {
        shown = true
        removeCallbacks(hide)
        invalidate()
    }

    private fun scheduleHide() {
        removeCallbacks(hide)
        postDelayed(hide, 1500)
    }

    private fun fraction(): Float {
        val r = rv ?: return 0f
        val range = r.computeVerticalScrollRange() - r.computeVerticalScrollExtent()
        if (range <= 0) return 0f
        return (r.computeVerticalScrollOffset().toFloat() / range).coerceIn(0f, 1f)
    }

    private fun thumbTop(): Float {
        val track = height - paddingTop - paddingBottom - thumbH
        return paddingTop + track * fraction()
    }

    override fun onDraw(canvas: Canvas) {
        if (!shown) return
        val top = thumbTop()
        val right = width - margin
        rect.set(right - thumbW, top, right, top + thumbH)
        canvas.drawRoundRect(rect, thumbW / 2, thumbW / 2, thumbPaint)
        if (!dragging) return
        val label = labelFor?.invoke(dragPos) ?: return
        // Balão à esquerda do polegar.
        val r = 28 * d
        val cy = (top + thumbH / 2).coerceIn(r, height - r)
        val w = max(r * 2, textPaint.measureText(label) + 24 * d)
        rect.set(right - thumbW - 16 * d - w, cy - r, right - thumbW - 16 * d, cy + r)
        canvas.drawRoundRect(rect, r, r, bubblePaint)
        canvas.drawText(label, rect.centerX(), cy - (textPaint.ascent() + textPaint.descent()) / 2, textPaint)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val r = rv ?: return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // Só a faixa da direita é "pegável"; o resto passa para a lista.
                // Com a barra visível, tocar em qualquer ponto da faixa pula para lá.
                if (!shown || !isLong() || e.x < width - 48 * d) return false
                dragging = true
                removeCallbacks(hide)
                parent?.requestDisallowInterceptTouchEvent(true)
                r.stopScroll()
                scrollTo(e.y)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!dragging) return false
                scrollTo(e.y)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (!dragging) return false
                dragging = false
                invalidate()
                scheduleHide()
                return true
            }
        }
        return false
    }

    private fun scrollTo(y: Float) {
        val r = rv ?: return
        val lm = r.layoutManager as? LinearLayoutManager ?: return
        val count = r.adapter?.itemCount ?: 0
        if (count == 0) return
        val track = height - paddingTop - paddingBottom - thumbH
        val f = ((y - paddingTop - thumbH / 2) / track).coerceIn(0f, 1f)
        dragPos = (f * (count - 1)).toInt()
        lm.scrollToPositionWithOffset(dragPos, 0)
        invalidate()
    }
}
