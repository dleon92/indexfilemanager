package app.index.files.ui

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.fragment.app.Fragment
import app.index.files.Bg
import app.index.files.R
import app.index.files.data.FileItem
import app.index.files.data.FileSource

/**
 * "Compartilhar" de um ou vários arquivos. A lista de apps aparece na hora: locais vão com a
 * Uri do SAF; do Drive, com o link de streaming (o app escolhido lê direto, sem download prévio).
 */
object Share {

    fun share(f: Fragment, files: List<Pair<FileItem, FileSource>>) {
        val list = files.filter { !it.first.isDir }
        if (list.isEmpty()) return
        val app = f.requireContext().applicationContext
        Bg.run({ list.map { (item, src) -> src.shareUri(item) } }) { res ->
            val act = f.activity ?: return@run
            res.onSuccess { uris -> OpenWith.pickShare(act, intentFor(list.map { it.first }, uris)) }
                .onFailure { Toast.makeText(app, R.string.share_failed, Toast.LENGTH_SHORT).show() }
        }
    }

    private fun intentFor(items: List<FileItem>, uris: List<Uri>): Intent {
        val clip = ClipData.newRawUri(items[0].name, uris[0])
        for (i in 1 until uris.size) clip.addItem(ClipData.Item(uris[i]))
        val intent = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).setType(items[0].mime).putExtra(Intent.EXTRA_STREAM, uris[0])
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).setType(commonType(items)).putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
        }
        intent.clipData = clip
        return intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    // Tipo comum: o próprio se todos iguais; "família/qualquer" se mesma família; senão qualquer.
    private fun commonType(items: List<FileItem>): String {
        val types = items.map { it.mime }.toSet()
        if (types.size == 1) return types.first()
        val families = types.map { it.substringBefore('/') }.toSet()
        return if (families.size == 1) "${families.first()}/*" else "*/*"
    }
}
