package app.index.files.ui

import android.app.Activity
import android.text.format.Formatter
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import app.index.files.Bg
import app.index.files.R
import app.index.files.data.FileItem
import app.index.files.data.FileSource
import app.index.files.data.Prefs
import app.index.files.data.VaultSource
import app.index.files.thumbs.Preload
import app.index.files.thumbs.ThumbnailManager
import java.text.NumberFormat

/** Interface da pré-carga de miniaturas, comum à navegação e à pesquisa. */
object PreloadUi {

    /** Tamanho médio estimado de uma miniatura no disco (WebP 256 px). */
    private const val AVG_THUMB_BYTES = 16L * 1024

    private fun fmt(n: Int) = NumberFormat.getIntegerInstance().format(n)

    /** RAM ocupada pelas miniaturas, em MB inteiros ("54 MB"). */
    private fun mb(bytes: Long) = "${(bytes + 512 * 1024) / (1024 * 1024)} MB"

    /**
     * "Carregar todas as miniaturas" da lista [items]. Avisa antes se as que faltam não
     * cabem no limite do cache em disco.
     */
    fun start(f: Fragment, key: String, items: List<FileItem>, sourceFor: (FileItem) -> FileSource, first: Int) {
        val ctx = f.requireContext()
        val app = ctx.applicationContext
        val media = items.filter(ThumbnailManager::hasThumb)
        if (media.isEmpty()) {
            Toast.makeText(app, R.string.preload_nothing, Toast.LENGTH_SHORT).show()
            return
        }
        val pairs = media.map { it to sourceFor(it) }
        val tm = ThumbnailManager.get(app)
        val limit = Prefs.get(app).thumbLimitMb.toLong() * 1024 * 1024
        Bg.run({
            // Pastas protegidas têm cache próprio (fora do limite).
            val missing = pairs.count { (it, src) -> src !is VaultSource && tm.missingOnDisk(it, src) }
            missing * AVG_THUMB_BYTES to tm.disk.size()
        }) { res ->
            if (!f.isAdded) return@run
            val (need, used) = res.getOrDefault(0L to 0L)
            val go = { Preload.start(app, key, items, sourceFor, first) }
            if (limit > 0 && need > 0 && used + need > limit) {
                AlertDialog.Builder(f.requireContext())
                    .setTitle(R.string.preload_disk_title)
                    .setMessage(
                        f.getString(
                            R.string.preload_disk_msg,
                            Formatter.formatShortFileSize(app, need),
                            Formatter.formatShortFileSize(app, limit),
                        ),
                    )
                    .setPositiveButton(R.string.preload_continue) { _, _ -> go() }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
            } else {
                go()
            }
        }
    }

    /** Barra de progresso da lista [key] (visível só enquanto a pré-carga dela roda). */
    fun renderBar(bar: View, key: String?) {
        val show = Preload.running && key != null && key == Preload.listKey
        bar.visibility = if (show) View.VISIBLE else View.GONE
        if (!show) return
        bar.findViewById<TextView>(R.id.preload_text).text =
            bar.context.getString(R.string.preload_progress, fmt(Preload.done), fmt(Preload.total), mb(Preload.memBytes))
        bar.findViewById<View>(R.id.preload_cancel).setOnClickListener { Preload.release() }
    }

    /** Fim da pré-carga: resumo em uma linha. */
    fun toastFinished(ctx: android.content.Context) {
        var msg = if (Preload.fitsAll) {
            ctx.getString(R.string.preload_done)
        } else {
            ctx.getString(R.string.preload_done_window, fmt(Preload.inRam))
        }
        if (Preload.skippedNoWifi > 0) msg += " " + ctx.getString(R.string.preload_skipped, fmt(Preload.skippedNoWifi))
        Toast.makeText(ctx.applicationContext, msg, Toast.LENGTH_LONG).show()
    }

    /** Toque no raio da barra superior: situação e opção de parar/liberar. */
    fun showStatus(a: Activity) {
        val msg = if (Preload.running) {
            a.getString(R.string.preload_status_running, fmt(Preload.done), fmt(Preload.total), mb(Preload.memBytes))
        } else {
            a.getString(R.string.preload_status_holding, fmt(Preload.inRam), mb(Preload.memBytes))
        }
        AlertDialog.Builder(a)
            .setTitle(R.string.preload_active)
            .setMessage(msg)
            .setPositiveButton(if (Preload.running) R.string.preload_stop else R.string.preload_release) { _, _ -> Preload.release() }
            .setNegativeButton(R.string.close, null)
            .show()
    }

    /**
     * Rolagem: quando nem tudo coube na RAM, pede a janela de ~3 telas à frente e atrás.
     * Chamado no fim de cada gesto de rolagem e ao terminar a pré-carga.
     */
    fun requestWindow(key: String, list: RecyclerView, items: List<FileItem>, sourceFor: (FileItem) -> FileSource) {
        if (!Preload.needsWindow(key) || items.isEmpty()) return
        val lm = list.layoutManager as? GridLayoutManager ?: return
        val first = lm.findFirstVisibleItemPosition()
        val last = lm.findLastVisibleItemPosition()
        if (first < 0 || last < first) return
        val span = Preload.windowSpan(last - first + 1)
        val from = (first - span).coerceAtLeast(0)
        val to = (last + span).coerceAtMost(items.size - 1)
        // Primeiro a tela, depois para fora (abaixo e acima alternados).
        val ordered = ArrayList<FileItem>(to - from + 1)
        for (i in first..last.coerceAtMost(to)) ordered.add(items[i])
        var d = 1
        while (last + d <= to || first - d >= from) {
            if (last + d <= to) ordered.add(items[last + d])
            if (first - d >= from) ordered.add(items[first - d])
            d++
        }
        Preload.window(key, ordered, sourceFor)
    }
}
