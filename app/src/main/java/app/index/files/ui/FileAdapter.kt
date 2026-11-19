package app.index.files.ui

import android.annotation.SuppressLint
import android.content.Context
import android.text.format.Formatter
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import app.index.files.R
import app.index.files.data.FileItem
import app.index.files.data.FileSource
import app.index.files.thumbs.ThumbnailManager
import java.text.DateFormat
import java.util.Date

/** Adapter único para grade e lista (o modo troca o tipo de view). */
class FileAdapter(
    ctx: Context,
    private val thumbs: ThumbnailManager,
    private val onClick: (FileItem) -> Unit,
) : RecyclerView.Adapter<FileAdapter.VH>() {

    /** Origem de cada item (para gerar/buscar thumbs); na busca, varia por raiz. */
    lateinit var sourceFor: (FileItem) -> FileSource

    /** Na busca: rótulo do caminho relativo, exibido no lugar do "Pasta"/tamanho. */
    var pathLabel: ((FileItem) -> String)? = null

    var onLongClick: ((FileItem, View) -> Unit)? = null

    /** Modo de seleção (navegador): itens marcados aparecem com o véu de destaque. */
    var isSelected: ((FileItem) -> Boolean)? = null

    /** Só redesenha a marcação de seleção (sem rebind da thumb). */
    fun refreshSelection(position: Int) = notifyItemChanged(position, PAYLOAD_SELECTION)

    @SuppressLint("NotifyDataSetChanged")
    fun refreshAllSelection() = notifyItemRangeChanged(0, items.size, PAYLOAD_SELECTION)

    /** Tamanho das miniaturas mudou (256 / 384 / 768 px): religa tudo. */
    fun refreshThumbs() = notifyItemRangeChanged(0, items.size)

    /** Uma coluna só (fase 12): altura máxima da miniatura, para caber uma por tela; 0 = quadrada. */
    var fitHeight = 0

    /** Clique direito do mouse num item (menu de contexto). */
    var onContextClick: ((FileItem, View) -> Unit)? = null

    var items: List<FileItem> = emptyList()
        private set

    var grid = true
        @SuppressLint("NotifyDataSetChanged")
        set(v) {
            if (field != v) {
                field = v
                notifyDataSetChanged()
            }
        }

    private val appCtx = ctx.applicationContext
    private val dateFmt = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
    private val folderLabel = ctx.getString(R.string.folder)
    private val date = Date()

    /** Troca de pasta ou de ordenação: substituição direta (diff seria só custo). */
    @SuppressLint("NotifyDataSetChanged")
    fun replace(list: List<FileItem>) {
        items = list
        notifyDataSetChanged()
    }

    /** Atualização da mesma pasta: aplica o diff calculado em segundo plano. */
    fun apply(list: List<FileItem>, diff: DiffUtil.DiffResult) {
        items = list
        diff.dispatchUpdatesTo(this)
    }

    override fun getItemCount() = items.size

    override fun getItemViewType(position: Int) = if (grid) TYPE_GRID else TYPE_LIST

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val layout = if (viewType == TYPE_GRID) R.layout.item_grid else R.layout.item_list
        val v = LayoutInflater.from(parent.context).inflate(layout, parent, false)
        val h = VH(v)
        v.setOnClickListener {
            val p = h.bindingAdapterPosition
            if (p != RecyclerView.NO_POSITION) onClick(items[p])
        }
        v.setOnLongClickListener {
            val p = h.bindingAdapterPosition
            val cb = onLongClick
            if (p == RecyclerView.NO_POSITION || cb == null) return@setOnLongClickListener false
            cb(items[p], v)
            true
        }
        // Mouse: clique direito abre o menu de contexto (sem ele, faz o mesmo que o toque longo).
        v.setOnContextClickListener {
            val p = h.bindingAdapterPosition
            if (p == RecyclerView.NO_POSITION) return@setOnContextClickListener false
            val cb = onContextClick ?: onLongClick ?: return@setOnContextClickListener false
            cb(items[p], v)
            true
        }
        return h
    }

    /** Seleção: contorno na tela e "Selecionado" no leitor de tela (TalkBack). */
    private fun markSelected(h: VH, on: Boolean) {
        h.itemView.isActivated = on
        androidx.core.view.ViewCompat.setStateDescription(h.itemView, if (on) h.itemView.context.getString(R.string.a11y_selected) else null)
    }

    override fun onBindViewHolder(h: VH, position: Int, payloads: MutableList<Any>) {
        if (payloads.isNotEmpty() && payloads.all { it === PAYLOAD_SELECTION }) {
            markSelected(h, isSelected?.invoke(items[position]) == true)
            return
        }
        onBindViewHolder(h, position)
    }

    override fun onBindViewHolder(h: VH, position: Int) {
        val it = items[position]
        markSelected(h, isSelected?.invoke(it) == true)
        (h.icon as? SquareImageView)?.let { sq ->
            sq.maxSide = fitHeight
            sq.fit = fitHeight > 0
        }
        thumbs.bind(h.icon, it, sourceFor(it), iconFor(it.kind))
        h.icon.badge = it.kind == FileItem.KIND_VIDEO
        h.name.text = it.name
        val meta = h.meta ?: return
        val path = pathLabel
        meta.text = when {
            path != null && it.isDir -> path(it)
            path != null -> path(it) + " · " + Formatter.formatShortFileSize(appCtx, it.size)
            it.isDir -> folderLabel
            else -> {
                date.time = it.modified
                Formatter.formatShortFileSize(appCtx, it.size) + " · " + dateFmt.format(date)
            }
        }
    }

    /** Item saiu da tela: cancela o pedido de thumb pendente. */
    override fun onViewRecycled(h: VH) {
        thumbs.cancel(h.icon)
    }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val icon: ThumbView = v.findViewById(R.id.icon)
        val name: TextView = v.findViewById(R.id.name)
        val meta: TextView? = v.findViewById(R.id.meta)
    }

    companion object {
        const val TYPE_LIST = 0
        const val TYPE_GRID = 1
        private val PAYLOAD_SELECTION = Any()

        fun iconFor(kind: Int) = when (kind) {
            FileItem.KIND_DIR -> R.drawable.ic_folder
            FileItem.KIND_IMAGE -> R.drawable.ic_image
            FileItem.KIND_VIDEO -> R.drawable.ic_video
            FileItem.KIND_AUDIO -> R.drawable.ic_audio
            else -> R.drawable.ic_file
        }

        fun diffCallback(old: List<FileItem>, new: List<FileItem>) = object : DiffUtil.Callback() {
            override fun getOldListSize() = old.size
            override fun getNewListSize() = new.size
            override fun areItemsTheSame(o: Int, n: Int) = old[o].id == new[n].id
            override fun areContentsTheSame(o: Int, n: Int): Boolean {
                val a = old[o]
                val b = new[n]
                return a.name == b.name && a.size == b.size && a.modified == b.modified && a.mime == b.mime
            }
        }
    }
}
