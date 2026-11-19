package app.index.files

import android.os.Handler
import android.os.Looper
import java.util.concurrent.Executors

/**
 * Execução em segundo plano sem bibliotecas: um pool pequeno + Handler da main.
 * Quem chama descarta resultados obsoletos (ver contador "gen" nos fragments).
 */
object Bg {
    private val pool = Executors.newFixedThreadPool(2) { r ->
        Thread(r, "index-bg").apply { priority = Thread.NORM_PRIORITY - 1 }
    }
    val main = Handler(Looper.getMainLooper())

    fun <T> run(task: () -> T, done: (Result<T>) -> Unit) {
        pool.execute {
            val r = runCatching(task)
            main.post { done(r) }
        }
    }
}
