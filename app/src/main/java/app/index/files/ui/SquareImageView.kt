package app.index.files.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatImageView
import app.index.files.R
import kotlin.math.min

/**
 * ImageView de miniatura que desenha o selo de vídeo (play) por cima,
 * sem precisar de um FrameLayout extra no item.
 */
open class ThumbView @JvmOverloads constructor(
    ctx: Context,
    attrs: AttributeSet? = null,
) : AppCompatImageView(ctx, attrs) {

    /** Mostrar a imagem inteira (uma por tela) em vez de recortar para preencher a célula. */
    var fit = false

    var badge = false
        set(v) {
            if (field != v) {
                field = v
                invalidate()
            }
        }

    private val maxBadge = (36 * resources.displayMetrics.density).toInt()
    private var play: Drawable? = null

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!badge) return
        val d = play ?: context.getDrawable(R.drawable.ic_play_badge)!!.also { play = it }
        val s = min((min(width, height) * 0.4f).toInt(), maxBadge)
        val l = (width - s) / 2
        val t = (height - s) / 2
        d.setBounds(l, t, l + s, t + s)
        d.draw(canvas)
    }
}

/**
 * Miniatura com altura = largura (células da grade). Com [maxSide] > 0 (uma coluna só,
 * fase 12) a altura é limitada para a imagem inteira caber na tela.
 */
class SquareImageView @JvmOverloads constructor(
    ctx: Context,
    attrs: AttributeSet? = null,
) : ThumbView(ctx, attrs) {
    var maxSide = 0
        set(v) {
            if (field != v) {
                field = v
                requestLayout()
            }
        }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = if (maxSide > 0) min(w, maxSide) else w
        setMeasuredDimension(w, h)
    }
}
