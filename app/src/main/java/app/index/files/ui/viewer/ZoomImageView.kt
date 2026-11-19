package app.index.files.ui.viewer

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.animation.DecelerateInterpolator
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Imagem com zoom (pinça, duplo toque) e arraste, desenhada direto no Canvas.
 *
 * O estado é independente da resolução carregada: [zoom] (1 = imagem inteira na tela) e o
 * centro visível [cx], [cy] em fração da imagem (0..1). Assim a miniatura, a imagem e os
 * recortes nítidos trocam sem a tela pular, e o mesmo estado vale para a próxima foto.
 *
 * Coordenadas "lógicas" = pixels da imagem original já na orientação de exibição; a imagem
 * base e o recorte nítido ([setTile]) são desenhados esticados nesse espaço.
 */
class ZoomImageView(ctx: Context) : View(ctx) {

    interface Listener {
        fun onTap()
        /** Fim de um gesto (o estado novo vale para as outras fotos). */
        fun onStateCommitted(v: ZoomImageView)
    }

    var listener: Listener? = null

    private var bmp: Bitmap? = null
    private var lw = 0f
    private var lh = 0f
    private var tile: Bitmap? = null
    private val tileRect = RectF()

    var zoom = 1f
        private set
    var cx = 0.5f
        private set
    var cy = 0.5f
        private set

    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
    private val dst = RectF()
    private var anim: ValueAnimator? = null

    val hasImage get() = bmp != null
    val logicalWidth get() = lw
    val logicalHeight get() = lh

    /** Troca a imagem mantendo zoom e região. [w]/[h]: tamanho lógico (original). */
    fun setImage(b: Bitmap?, w: Int, h: Int) {
        bmp = b
        if (w > 0 && h > 0) {
            lw = w.toFloat()
            lh = h.toFloat()
        } else if (b != null) {
            lw = b.width.toFloat()
            lh = b.height.toFloat()
        }
        clamp()
        invalidate()
    }

    /** Recorte nítido da região [r] (coordenadas lógicas); null tira. */
    fun setTile(b: Bitmap?, r: RectF?) {
        tile = b
        if (r != null) tileRect.set(r)
        invalidate()
    }

    fun setState(z: Float, x: Float, y: Float) {
        anim?.cancel()
        zoom = max(1f, z)
        cx = x
        cy = y
        clamp()
        invalidate()
    }

    /** Tela inteira por pixel lógico. */
    private fun fit(): Float = if (lw <= 0f || width == 0 || height == 0) 1f else min(width / lw, height / lh)

    /** Pixels de tela por pixel lógico agora. */
    fun scale(): Float = fit() * zoom

    /** Até ~3 pixels de tela por pixel da foto (e no mínimo 4× a tela inteira). */
    private fun maxZoom(): Float = max(4f, 3f / fit())

    private fun clamp() {
        if (lw <= 0f || width == 0) return
        // Sem teto aqui: a miniatura provisória (poucos pixels) não pode cortar o zoom herdado.
        zoom = max(1f, zoom)
        val k = scale()
        val iw = lw * k
        val ih = lh * k
        cx = if (iw <= width) 0.5f else cx.coerceIn(width / 2f / iw, 1f - width / 2f / iw)
        cy = if (ih <= height) 0.5f else cy.coerceIn(height / 2f / ih, 1f - height / 2f / ih)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        clamp()
    }

    override fun onDraw(canvas: Canvas) {
        // A miniatura provisória vem do cache da grade, que pode liberá-la: então só a base/recorte.
        val b = bmp?.takeIf { !it.isRecycled } ?: return
        val k = scale()
        val ox = width / 2f - cx * lw * k
        val oy = height / 2f - cy * lh * k
        dst.set(ox, oy, ox + lw * k, oy + lh * k)
        canvas.drawBitmap(b, null, dst, paint)
        val t = tile ?: return
        dst.set(ox + tileRect.left * k, oy + tileRect.top * k, ox + tileRect.right * k, oy + tileRect.bottom * k)
        canvas.drawBitmap(t, null, dst, paint)
    }

    /** Região visível em coordenadas lógicas; false se ainda sem imagem/medida. */
    fun visibleRect(out: RectF): Boolean {
        if (lw <= 0f || width == 0) return false
        val k = scale()
        val l = cx * lw - width / 2f / k
        val t = cy * lh - height / 2f / k
        out.set(max(0f, l), max(0f, t), min(lw, l + width / k), min(lh, t + height / k))
        return out.width() > 0 && out.height() > 0
    }

    // ---- Gestos ----

    private fun zoomAround(fx: Float, fy: Float, newZoom: Float) {
        if (lw <= 0f) return
        val k0 = scale()
        val px = cx * lw + (fx - width / 2f) / k0
        val py = cy * lh + (fy - height / 2f) / k0
        zoom = newZoom.coerceIn(1f, maxZoom())
        val k1 = scale()
        cx = (px - (fx - width / 2f) / k1) / lw
        cy = (py - (fy - height / 2f) / k1) / lh
        clamp()
        invalidate()
    }

    private val scaler = ScaleGestureDetector(ctx, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(d: ScaleGestureDetector): Boolean {
            zoomAround(d.focusX, d.focusY, zoom * d.scaleFactor)
            return true
        }
    })

    private val gestures = GestureDetector(ctx, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true

        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            listener?.onTap()
            return true
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            val target = if (zoom > 1.05f) 1f else min(2.5f, maxZoom())
            animateZoom(e.x, e.y, target)
            return true
        }

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            if (zoom <= 1.01f || scaler.isInProgress || lw <= 0f) return false
            val k = scale()
            cx += dx / (lw * k)
            cy += dy / (lh * k)
            clamp()
            invalidate()
            return true
        }
    })

    private fun animateZoom(fx: Float, fy: Float, target: Float) {
        anim?.cancel()
        anim = ValueAnimator.ofFloat(zoom, target).apply {
            duration = 220
            interpolator = DecelerateInterpolator()
            addUpdateListener { zoomAround(fx, fy, it.animatedValue as Float) }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(a: android.animation.Animator) {
                    listener?.onStateCommitted(this@ZoomImageView)
                }
            })
            start()
        }
    }

    /** Ainda dá para arrastar a imagem nesse sentido (senão o deslize troca de foto). */
    private fun canPanX(dx: Float): Boolean {
        if (zoom <= 1.01f || lw <= 0f) return false
        val k = scale()
        val iw = lw * k
        if (iw <= width + 1) return false
        val minC = width / 2f / iw
        val maxC = 1f - minC
        val eps = 0.5f / iw
        return if (dx > 0) cx > minC + eps else cx < maxC - eps
    }

    /** Roda do mouse: zoom em volta do ponteiro. */
    override fun onGenericMotionEvent(e: MotionEvent): Boolean {
        if (e.actionMasked == MotionEvent.ACTION_SCROLL) {
            val v = e.getAxisValue(MotionEvent.AXIS_VSCROLL)
            if (v != 0f) {
                anim?.cancel()
                zoomAround(e.x, e.y, zoom * if (v > 0) 1.25f else 0.8f)
                listener?.onStateCommitted(this)
                return true
            }
        }
        return super.onGenericMotionEvent(e)
    }

    private var lastX = 0f

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        scaler.onTouchEvent(e)
        gestures.onTouchEvent(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                anim?.cancel()
                lastX = e.x
                parent?.requestDisallowInterceptTouchEvent(zoom > 1.01f)
            }
            MotionEvent.ACTION_POINTER_DOWN -> parent?.requestDisallowInterceptTouchEvent(true)
            MotionEvent.ACTION_MOVE -> {
                if (e.pointerCount == 1 && !scaler.isInProgress) {
                    val dx = e.x - lastX
                    if (abs(dx) > 0.5f) parent?.requestDisallowInterceptTouchEvent(canPanX(dx))
                }
                lastX = e.x
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (anim?.isRunning != true) listener?.onStateCommitted(this)
            }
        }
        return true
    }
}
