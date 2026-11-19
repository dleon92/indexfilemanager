package app.index.files.ui.video

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.max
import kotlin.math.min

/**
 * Linha do tempo com ajuste fino: tocar pula para o ponto; arrastar move a posição, e quanto
 * mais o dedo sobe acima da barra, mais devagar ela anda (1, 1/2, 1/4, 1/8, 1/16 da velocidade),
 * para acertar o quadro exato. Os passos são relativos, então trocar de nível não dá salto.
 */
class FineSeekBar @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : View(ctx, attrs) {

    interface Listener {
        fun onScrubStart()
        /** [level]: 0 = normal; 1..4 = cada vez mais fino (velocidade 1/2^level). */
        fun onScrub(positionMs: Long, level: Int)
        fun onScrubEnd(positionMs: Long)
    }

    var listener: Listener? = null

    var duration = 0L
        set(v) {
            field = max(0L, v)
            invalidate()
        }
    var position = 0L
        set(v) {
            if (scrubbing) return // o dedo manda enquanto arrasta
            field = v.coerceIn(0L, max(0L, duration))
            invalidate()
        }
    var buffered = 0L
        set(v) {
            field = v
            invalidate()
        }

    var scrubbing = false
        private set

    private val d = resources.displayMetrics.density
    private val trackH = 4 * d
    private val pad = 10 * d
    /** Distância acima da barra até começar o ajuste fino, e altura de cada nível. */
    private val fineStart = 40 * d
    private val fineStep = 56 * d

    private val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x4DFFFFFF }
    private val buf = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x80FFFFFF.toInt() }
    private val fg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() }
    private val fine = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFC107.toInt() }

    private var lastX = 0f
    private var exact = 0.0
    private var level = 0

    private val left get() = pad
    private val right get() = width - pad
    private val span get() = max(1f, right - left)

    private fun xOf(ms: Long) = if (duration <= 0) left else left + span * (ms.toFloat() / duration)

    override fun onDraw(c: Canvas) {
        val cy = height / 2f
        val r = trackH / 2
        c.drawRoundRect(left, cy - r, right, cy + r, r, r, bg)
        if (duration > 0) {
            c.drawRoundRect(left, cy - r, xOf(min(buffered, duration)), cy + r, r, r, buf)
            val x = xOf(position)
            c.drawRoundRect(left, cy - r, x, cy + r, r, r, fg)
            c.drawCircle(x, cy, if (scrubbing) 9 * d else 7 * d, if (scrubbing && level > 0) fine else fg)
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (duration <= 0) return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                scrubbing = true
                level = 0
                lastX = e.x
                // Tocar pula direto para o ponto tocado.
                exact = ((e.x - left) / span * duration).toDouble().coerceIn(0.0, duration.toDouble())
                setPos()
                listener?.onScrubStart()
                listener?.onScrub(position, level)
            }
            MotionEvent.ACTION_MOVE -> {
                val above = height / 2f - e.y
                level = if (above < fineStart) 0 else min(4, 1 + ((above - fineStart) / fineStep).toInt())
                val factor = 1.0 / (1 shl level)
                exact = (exact + (e.x - lastX) / span * duration * factor).coerceIn(0.0, duration.toDouble())
                lastX = e.x
                setPos()
                listener?.onScrub(position, level)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                scrubbing = false
                level = 0
                invalidate()
                listener?.onScrubEnd(position)
            }
        }
        return true
    }

    private fun setPos() {
        val wasScrubbing = scrubbing
        scrubbing = false
        position = exact.toLong()
        scrubbing = wasScrubbing
    }
}
