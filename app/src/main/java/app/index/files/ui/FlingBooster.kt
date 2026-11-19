package app.index.files.ui

import android.os.SystemClock
import android.view.animation.Interpolator
import android.widget.Scroller
import androidx.recyclerview.widget.RecyclerView
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * "Sensibilidade" do deslizar: flings seguidos na mesma direção aceleram
 * (1× → 1,5× → 2× → 4× → 6× → 8× a distância natural). Um fling isolado continua
 * idêntico ao do sistema. Tocar na lista interrompe a rolagem, como sempre.
 *
 * Calcula a distância natural com um Scroller (mesmo modelo de atrito do sistema) e
 * aplica o multiplicador via smoothScrollBy, já que o RecyclerView limita a
 * velocidade máxima do fling.
 */
class FlingBooster private constructor(private val rv: RecyclerView) : RecyclerView.OnFlingListener() {

    private val calc = Scroller(rv.context)
    private var lastFlingAt = 0L
    private var lastDir = 0
    private var streak = 0

    override fun onFling(velocityX: Int, velocityY: Int): Boolean {
        if (velocityY == 0) return false
        val now = SystemClock.uptimeMillis()
        val dir = if (velocityY > 0) 1 else -1
        streak = if (dir == lastDir && now - lastFlingAt < STREAK_WINDOW_MS) min(streak + 1, MULT.size - 1) else 0
        lastDir = dir
        lastFlingAt = now
        if (streak == 0) return false // fling normal do sistema

        calc.fling(0, 0, 0, velocityY, 0, 0, Int.MIN_VALUE, Int.MAX_VALUE)
        val natural = calc.finalY
        val duration = calc.duration
        calc.forceFinished(true)
        val dy = (natural * MULT[streak]).roundToInt()
        if (abs(dy) < 1) return false
        val ms = min((duration * (1f + 0.15f * streak)).roundToInt(), MAX_DURATION_MS)
        rv.smoothScrollBy(0, dy, DECEL, ms)
        return true
    }

    companion object {
        private val MULT = floatArrayOf(1f, 1.5f, 2f, 4f, 6f, 8f)
        private const val STREAK_WINDOW_MS = 900L
        private const val MAX_DURATION_MS = 2200

        /** Desaceleração quíntica, parecida com a do fling nativo. */
        private val DECEL = Interpolator { t ->
            val u = 1f - t
            1f - u * u * u * u * u
        }

        fun attach(rv: RecyclerView) {
            rv.onFlingListener = FlingBooster(rv)
        }
    }
}
