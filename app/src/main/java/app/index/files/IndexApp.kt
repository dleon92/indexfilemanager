package app.index.files

import android.app.Application
import app.index.files.thumbs.Preload
import app.index.files.thumbs.ThumbnailManager
import app.index.files.ui.Themes

class IndexApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Aplicado antes de qualquer Activity existir: a primeira tela já nasce
        // no tema certo, sem "piscar" claro→escuro.
        Themes.apply(this)
    }

    /**
     * UI_HIDDEN chega quando o app sai da tela (ex.: o player assumiu durante o
     * streaming, que mantém o processo vivo); níveis acima, quando falta memória.
     */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        @Suppress("DEPRECATION")
        when {
            // Pré-carga ativa e o app só saiu da tela: quem libera é o prazo de segundo plano.
            level == TRIM_MEMORY_UI_HIDDEN && Preload.holding -> Unit
            level >= TRIM_MEMORY_UI_HIDDEN -> ThumbnailManager.trimMemory()
            // Falta de memória com o app na tela: a pré-carga solta a RAM na hora.
            level >= TRIM_MEMORY_RUNNING_LOW && Preload.holding -> Preload.release()
        }
    }
}
