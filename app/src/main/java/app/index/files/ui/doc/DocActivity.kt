package app.index.files.ui.doc

import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.widget.FrameLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import app.index.files.R
import app.index.files.data.UriSource
import app.index.files.ui.Themes

/**
 * Leitor aberto por outros apps ("Abrir com" → Index): só o documento recebido, sem o resto do
 * app (nem bloqueio, nem pastas). Fechar o leitor fecha esta tela e volta para quem chamou.
 */
class DocActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(Themes.current(this).style)
        // Sem restaurar fragments: o leitor é aberto de novo a partir do Intent.
        super.onCreate(null)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(FrameLayout(this).apply { id = R.id.viewer_host })
        show(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        DocViewerFragment.find(this)?.let { supportFragmentManager.beginTransaction().remove(it).commitNow() }
        show(intent)
    }

    private fun show(i: Intent) {
        val uri = i.data
        if (i.action != Intent.ACTION_VIEW || uri == null || (uri.scheme != "content" && uri.scheme != "file")) {
            finish()
            return
        }
        val src = UriSource(this, uri)
        val item = runCatching { src.item(i.type) }.getOrNull()
        if (item == null || !DocViewerFragment.open(this, item, src)) {
            Toast.makeText(this, R.string.doc_failed, Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            DocViewerFragment.find(this)?.let { if (it.onKey(event)) return true }
        }
        return super.dispatchKeyEvent(event)
    }
}
