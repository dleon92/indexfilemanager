package app.index.files.ui

import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import app.index.files.R
import app.index.files.data.Root

/** Lista de pastas raiz (tela inicial e "Gerenciar pastas"). */
class RootAdapter(
    private val manage: Boolean,
    private val onClick: (Root, View) -> Unit,
) : RecyclerView.Adapter<RootAdapter.VH>() {

    var items: List<Root> = emptyList()
        @SuppressLint("NotifyDataSetChanged")
        set(v) {
            field = v
            notifyDataSetChanged()
        }

    override fun getItemCount() = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_root, parent, false)
        val h = VH(v)
        val click = View.OnClickListener { anchor ->
            val p = h.bindingAdapterPosition
            if (p != RecyclerView.NO_POSITION) onClick(items[p], anchor)
        }
        v.setOnClickListener(click)
        if (manage) {
            h.more.visibility = View.VISIBLE
            h.more.setOnClickListener(click)
        }
        return h
    }

    override fun onBindViewHolder(h: VH, position: Int) {
        val r = items[position]
        val ctx = h.itemView.context
        h.icon.setImageResource(
            when {
                r.isRecent -> R.drawable.ic_recent
                !r.available -> R.drawable.ic_warning
                r.isDrive -> R.drawable.ic_cloud
                else -> R.drawable.ic_folder
            },
        )
        h.name.text = r.alias
        if (r.isRecent) {
            h.meta.text = when {
                r.isDriveRecent && !r.available -> ctx.getString(R.string.drive_disconnected_tap)
                !r.available -> ctx.getString(R.string.recent_needs_access)
                else -> app.index.files.data.RecentSource.describe(ctx, r.isDriveRecent)
            }
            return
        }
        var origin = ctx.getString(if (r.isDrive) R.string.source_drive else R.string.source_local)
        if (r.alias != r.name) origin += " · ${r.name}"
        if (r.itemCount >= 0) {
            origin += " · " + ctx.resources.getQuantityString(R.plurals.item_count, r.itemCount, r.itemCount)
        }
        h.meta.text = when {
            r.available -> origin
            r.isDrive -> ctx.getString(R.string.drive_disconnected_tap)
            else -> ctx.getString(R.string.unavailable_tap)
        }
    }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val icon: ImageView = v.findViewById(R.id.icon)
        val name: TextView = v.findViewById(R.id.name)
        val meta: TextView = v.findViewById(R.id.meta)
        val more: ImageView = v.findViewById(R.id.more)
    }
}
