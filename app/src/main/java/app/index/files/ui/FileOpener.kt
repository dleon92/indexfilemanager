package app.index.files.ui

import android.content.ActivityNotFoundException
import android.util.Log
import android.widget.Toast
import androidx.fragment.app.Fragment
import app.index.files.Bg
import app.index.files.R
import app.index.files.data.FileItem
import app.index.files.data.FileSource
import app.index.files.data.drive.AuthRequired

/**
 * Abre um arquivo no app adequado. O Drive baixa antes (fora da main thread).
 * [chooser] ("Abrir com..."): sempre mostra a lista de apps, ignorando o app padrão.
 */
object FileOpener {
    fun open(f: Fragment, src: FileSource, item: FileItem, chooser: Boolean = false) {
        val app = f.requireContext().applicationContext
        if (src.needsDownload(item)) {
            Toast.makeText(app, app.getString(R.string.drive_downloading, item.name), Toast.LENGTH_SHORT).show()
        }
        Bg.run({ src.openIntent(item) }) { res ->
            val act = f.activity ?: return@run
            res.onSuccess { intent ->
                try {
                    // "Abrir com...": lista própria (a do sistema lia o arquivo antes de aparecer).
                    if (chooser) OpenWith.pick(act, intent) else act.startActivity(intent)
                } catch (e: ActivityNotFoundException) {
                    Toast.makeText(app, R.string.no_app_to_open, Toast.LENGTH_SHORT).show()
                }
            }.onFailure { e ->
                Log.w("Index", "abrir ${item.name}", e)
                if (e is AuthRequired) {
                    (act as? MainActivity)?.askConnect()
                } else {
                    Toast.makeText(app, R.string.drive_open_failed, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
}
