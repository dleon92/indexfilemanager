package app.index.files.ui

import android.content.Context
import android.os.Bundle
import android.os.Environment
import android.os.storage.StorageManager
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import app.index.files.Bg
import app.index.files.R
import app.index.files.data.Indexer
import app.index.files.data.Prefs
import app.index.files.data.Roots
import app.index.files.data.normalizeName
import app.index.files.security.Vault
import java.io.File

/**
 * Escolha de uma pasta do aparelho pelo caminho ("Acesso a todos os arquivos"): aceita também
 * o que o seletor do sistema recusa (Download inteira, raiz do armazenamento, raiz do cartão).
 * Navega só por pastas, a partir dos volumes de armazenamento. Mesmo layout do seletor do Drive.
 */
class LocalPickerFragment : Fragment(R.layout.fragment_drive_picker) {

    private val paths = ArrayList<String>()
    private val names = ArrayList<String>()
    private var gen = 0

    private val adapter = PickAdapter { p, name -> enter(p, name) }
    private lateinit var path: TextView
    private lateinit var progress: View
    private lateinit var empty: View
    private lateinit var emptyText: TextView
    private lateinit var add: Button

    private val backCb = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            paths.removeAt(paths.size - 1)
            names.removeAt(names.size - 1)
            render()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requireActivity().onBackPressedDispatcher.addCallback(this, backCb)
        savedInstanceState?.getStringArrayList(S_PATHS)?.let { paths.addAll(it) }
        savedInstanceState?.getStringArrayList(S_NAMES)?.let { names.addAll(it) }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putStringArrayList(S_PATHS, paths)
        outState.putStringArrayList(S_NAMES, names)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        path = view.findViewById(R.id.path)
        progress = view.findViewById(R.id.progress)
        empty = view.findViewById(R.id.empty)
        emptyText = view.findViewById(R.id.empty_text)
        view.findViewById<View>(R.id.empty_action).visibility = View.GONE
        add = view.findViewById(R.id.add)
        val list = view.findViewById<RecyclerView>(R.id.list)
        list.layoutManager = LinearLayoutManager(requireContext())
        list.adapter = adapter
        add.setOnClickListener { addCurrent() }
        render()
    }

    override fun onResume() {
        super.onResume()
        requireActivity().setTitle(R.string.local_pick_title)
    }

    private fun enter(p: String, name: String) {
        paths.add(p)
        names.add(name)
        render()
    }

    private fun render() {
        val g = ++gen
        backCb.isEnabled = paths.isNotEmpty()
        path.text = if (names.isEmpty()) getString(R.string.local_pick_hint) else names.joinToString(" › ")
        add.isEnabled = paths.isNotEmpty()
        empty.visibility = View.GONE
        adapter.set(emptyList())
        progress.visibility = View.VISIBLE
        val app = requireContext().applicationContext
        val dir = paths.lastOrNull()
        Bg.run({ if (dir == null) volumes(app) else folders(app, dir) }) { res ->
            if (g != gen || view == null) return@run
            progress.visibility = View.GONE
            val items = res.getOrDefault(emptyList())
            adapter.set(items)
            if (items.isEmpty()) {
                emptyText.setText(if (dir == null) R.string.folder_add_failed else R.string.drive_no_subfolders)
                empty.visibility = View.VISIBLE
            }
        }
    }

    /** Armazenamento interno e cartões montados. */
    private fun volumes(ctx: Context): List<Entry> {
        val sm = ctx.getSystemService(StorageManager::class.java)
        val out = ArrayList<Entry>()
        for (v in sm.storageVolumes) {
            if (v.state != Environment.MEDIA_MOUNTED) continue
            val d = (if (android.os.Build.VERSION.SDK_INT >= 30) v.directory else null) ?: continue
            out.add(Entry(d.absolutePath, v.getDescription(ctx), R.drawable.ic_folder))
        }
        if (out.isEmpty()) {
            val d = Environment.getExternalStorageDirectory()
            out.add(Entry(d.absolutePath, ctx.getString(R.string.source_local_device), R.drawable.ic_folder))
        }
        return out
    }

    private fun folders(ctx: Context, dir: String): List<Entry> {
        val showHidden = Prefs.get(ctx).showHidden
        val list = File(dir).listFiles { f -> f.isDirectory } ?: return emptyList()
        return list
            .filter { (showHidden || !it.name.startsWith(".")) && !Vault.isHiddenTag(ctx, Roots.FILE, it.absolutePath) }
            .sortedBy { normalizeName(it.name) }
            .map { Entry(it.absolutePath, it.name, R.drawable.ic_folder) }
    }

    private fun addCurrent() {
        val p = paths.lastOrNull() ?: return
        val name = names.last()
        val app = requireContext().applicationContext
        add.isEnabled = false
        Bg.run({ Roots.addFile(app, File(p), name) }) { res ->
            res.onSuccess { newId ->
                if (newId == -1L) {
                    Toast.makeText(app, R.string.folder_already_added, Toast.LENGTH_SHORT).show()
                } else {
                    Indexer.start(app, newId)
                }
                if (view == null) return@run
                parentFragmentManager.setFragmentResult(MainActivity.ROOTS_CHANGED, Bundle.EMPTY)
                parentFragmentManager.popBackStack()
            }.onFailure {
                Toast.makeText(app, R.string.folder_add_failed, Toast.LENGTH_SHORT).show()
                if (view != null) add.isEnabled = true
            }
        }
    }

    private class Entry(val path: String, val name: String, val icon: Int)

    private class PickAdapter(private val onClick: (String, String) -> Unit) : RecyclerView.Adapter<PickAdapter.VH>() {
        private var items: List<Entry> = emptyList()

        @android.annotation.SuppressLint("NotifyDataSetChanged")
        fun set(v: List<Entry>) {
            items = v
            notifyDataSetChanged()
        }

        override fun getItemCount() = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_root, parent, false)
            val h = VH(v)
            v.setOnClickListener {
                val p = h.bindingAdapterPosition
                if (p != RecyclerView.NO_POSITION) onClick(items[p].path, items[p].name)
            }
            h.meta.visibility = View.GONE
            return h
        }

        override fun onBindViewHolder(h: VH, position: Int) {
            val e = items[position]
            h.icon.setImageResource(e.icon)
            h.name.text = e.name
        }

        class VH(v: View) : RecyclerView.ViewHolder(v) {
            val icon: ImageView = v.findViewById(R.id.icon)
            val name: TextView = v.findViewById(R.id.name)
            val meta: TextView = v.findViewById(R.id.meta)
        }
    }

    private companion object {
        const val S_PATHS = "paths"
        const val S_NAMES = "names"
    }
}
