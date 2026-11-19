package app.index.files.ui

import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import androidx.recyclerview.widget.RecyclerView
import kotlin.math.min

/**
 * "Puxar para atualizar" sem biblioteca: com a lista no topo, arrastar para baixo traz o círculo
 * [indicator] (com o ícone [icon]); soltando além do limite chama [onRefresh] e o círculo gira
 * ([spinner]) até [setRefreshing] (false). O mesmo círculo avisa "atualizando" quando a consulta
 * da pasta demora. Ignora pinça (dois dedos) e o arraste de seleção ([enabled] = false).
 */
class PullRefresh(
    private val rv: RecyclerView,
    private val indicator: View,
    private val icon: View,
    private val spinner: View,
    private val enabled: () -> Boolean,
    private val onRefresh: () -> Unit,
) : RecyclerView.OnItemTouchListener {

    private val density = rv.resources.displayMetrics.density
    private val slop = ViewConfiguration.get(rv.context).scaledTouchSlop
    private val rest = 16 * density          // posição do círculo enquanto atualiza
    private val trigger = 72 * density       // puxada (já amortecida) que dispara
    private val maxPull = 110 * density
    private val hidden get() = -(indicator.height.takeIf { it > 0 } ?: (40 * density).toInt()).toFloat()

    private var startY = 0f
    private var armed = false
    private var pulling = false
    private var refreshing = false

    init {
        rv.addOnItemTouchListener(this)
    }

    override fun onInterceptTouchEvent(rv: RecyclerView, e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                startY = e.y
                pulling = false
                armed = !refreshing && enabled() && !rv.canScrollVertically(-1)
            }
            MotionEvent.ACTION_POINTER_DOWN -> armed = false // pinça
            MotionEvent.ACTION_MOVE -> if (armed && !pulling) {
                val dy = e.y - startY
                if (dy < -slop || !enabled()) armed = false
                else if (dy > slop && !rv.canScrollVertically(-1)) {
                    pulling = true
                    startY = e.y
                    rv.parent?.requestDisallowInterceptTouchEvent(true)
                    show()
                    return true
                }
            }
        }
        return pulling
    }

    override fun onTouchEvent(rv: RecyclerView, e: MotionEvent) {
        when (e.actionMasked) {
            MotionEvent.ACTION_MOVE -> drag(e.y - startY)
            MotionEvent.ACTION_POINTER_DOWN -> finish(false)
            MotionEvent.ACTION_UP -> finish(pullOf(e.y - startY) >= trigger)
            MotionEvent.ACTION_CANCEL -> finish(false)
        }
    }

    override fun onRequestDisallowInterceptTouchEvent(disallowIntercept: Boolean) {}

    /** Resistência: o círculo anda metade do dedo, até [maxPull]. */
    private fun pullOf(dy: Float) = min(maxOf(dy, 0f) * 0.5f, maxPull)

    private fun drag(dy: Float) {
        val p = pullOf(dy)
        indicator.translationY = hidden + p
        val f = min(p / trigger, 1f)
        indicator.alpha = 0.3f + 0.7f * f
        icon.rotation = 300f * f
        icon.alpha = if (f >= 1f) 1f else 0.6f
    }

    private fun finish(fire: Boolean) {
        if (!pulling) return
        pulling = false
        armed = false
        if (fire) {
            setRefreshing(true)
            onRefresh()
        } else if (!refreshing) {
            hide()
        }
    }

    private fun show() {
        indicator.animate().cancel()
        indicator.visibility = View.VISIBLE
        indicator.translationY = hidden
        icon.visibility = View.VISIBLE
        spinner.visibility = View.GONE
    }

    private fun hide() {
        indicator.animate().cancel()
        indicator.animate().translationY(hidden).alpha(0f).setDuration(180)
            .withEndAction { if (!refreshing && !pulling) indicator.visibility = View.GONE }
            .start()
    }

    /** Círculo girando parado no topo (puxada ou consulta lenta) ou recolhido. */
    fun setRefreshing(on: Boolean) {
        if (on == refreshing && !pulling) return
        refreshing = on
        if (on) {
            indicator.animate().cancel()
            if (indicator.visibility != View.VISIBLE) {
                indicator.visibility = View.VISIBLE
                indicator.translationY = hidden
                indicator.alpha = 0f
            }
            icon.visibility = View.GONE
            spinner.visibility = View.VISIBLE
            indicator.animate().translationY(rest).alpha(1f).setDuration(160).start()
        } else if (!pulling) {
            hide()
        }
    }

    val isRefreshing get() = refreshing
}
