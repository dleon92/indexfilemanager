package app.index.files.ui

import android.content.Context
import android.text.format.Formatter
import androidx.recyclerview.widget.RecyclerView
import app.index.files.R
import app.index.files.data.FileItem
import app.index.files.data.SortSpec
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Rótulo do balão da barra de rolagem, conforme o critério de ordenação. */
object ScrollLabels {
    private val month = SimpleDateFormat("MMM yyyy", Locale.getDefault())
    private val date = Date()

    fun of(ctx: Context, item: FileItem, by: Int): String = when (by) {
        SortSpec.SIZE -> if (item.isDir) ctx.getString(R.string.folder) else Formatter.formatShortFileSize(ctx, item.size)
        SortSpec.DATE -> {
            date.time = item.modified
            month.format(date)
        }
        SortSpec.TYPE -> if (item.isDir) ctx.getString(R.string.folder) else item.ext.uppercase().ifEmpty { "—" }
        else -> item.sortKey.firstOrNull()?.uppercaseChar()?.toString() ?: "#"
    }

    /** Liga aceleração do fling + barra arrastável numa lista de arquivos. */
    fun setup(list: RecyclerView, scroller: FastScroller, adapter: FileAdapter, sortBy: () -> Int) {
        FlingBooster.attach(list)
        scroller.attach(list)
        val ctx = list.context.applicationContext
        scroller.labelFor = { p -> adapter.items.getOrNull(p)?.let { of(ctx, it, sortBy()) } }
    }
}
