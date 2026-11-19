package app.index.files.ui

import android.os.Bundle
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
import app.index.files.data.FileItem
import app.index.files.data.Indexer
import app.index.files.data.Roots
import app.index.files.data.SortSpec
import app.index.files.data.comparatorFor
import app.index.files.data.drive.AuthRequired
import app.index.files.data.drive.DriveApi
import app.index.files.security.Vault

/**
 * Escolha de uma pasta do Drive para liberar no app. Navega só por pastas,
 * a partir de "Meu Drive" ou "Compartilhados comigo".
 */
class DrivePickerFragment : Fragment(R.layout.fragment_drive_picker) {

    private val ids = ArrayList<String>()
    private val names = ArrayList<String>()
    private var gen = 0

    private val adapter = PickAdapter { id, name -> enter(id, name) }
    private lateinit var path: TextView
    private lateinit var progress: View
    private lateinit var empty: View
    private lateinit var emptyText: TextView
    private lateinit var emptyAction: Button
    private lateinit var add: Button

    private val backCb = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            ids.removeAt(ids.size - 1)
            names.removeAt(names.size - 1)
            render()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requireActivity().onBackPressedDispatcher.addCallback(this, backCb)
        savedInstanceState?.getStringArrayList(S_IDS)?.let { ids.addAll(it) }
        savedInstanceState?.getStringArrayList(S_NAMES)?.let { names.addAll(it) }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putStringArrayList(S_IDS, ids)
        outState.putStringArrayList(S_NAMES, names)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        path = view.findViewById(R.id.path)
        progress = view.findViewById(R.id.progress)
        empty = view.findViewById(R.id.empty)
        emptyText = view.findViewById(R.id.empty_text)
        emptyAction = view.findViewById(R.id.empty_action)
        add = view.findViewById(R.id.add)
        val list = view.findViewById<RecyclerView>(R.id.list)
        list.layoutManager = LinearLayoutManager(requireContext())
        list.adapter = adapter
        add.setOnClickListener { addCurrent() }
        parentFragmentManager.setFragmentResultListener(MainActivity.ROOTS_CHANGED, viewLifecycleOwner) { _, _ ->
            render() // conta conectada depois de um erro de autorização
        }
        render()
    }

    override fun onResume() {
        super.onResume()
        requireActivity().setTitle(R.string.drive_pick_title)
    }

    private fun enter(id: String, name: String) {
        ids.add(id)
        names.add(name)
        render()
    }

    private fun render() {
        val g = ++gen
        backCb.isEnabled = ids.isNotEmpty()
        path.text = if (names.isEmpty()) getString(R.string.drive_pick_hint) else names.joinToString(" › ")
        add.isEnabled = ids.isNotEmpty() && ids.last() != SHARED
        empty.visibility = View.GONE
        if (ids.isEmpty()) {
            progress.visibility = View.GONE
            adapter.set(
                listOf(
                    Entry("root", getString(R.string.drive_my_drive), R.drawable.ic_cloud),
                    Entry(SHARED, getString(R.string.drive_shared), R.drawable.ic_cloud),
                ),
            )
            return
        }
        adapter.set(emptyList())
        progress.visibility = View.VISIBLE
        val id = ids.last()
        val app = requireContext().applicationContext
        Bg.run({
            val items = if (id == SHARED) DriveApi.sharedFolders(app) else DriveApi.folders(app, id)
            // Pasta protegida não aparece nem no seletor.
            items.filter { !Vault.isHiddenTag(app, Roots.DRIVE, it.id) }.sortedWith(comparatorFor(SortSpec(SortSpec.NAME, true, true)))
        }) { res ->
            if (g != gen || view == null) return@run
            progress.visibility = View.GONE
            res.onSuccess { items ->
                adapter.set(items.map { Entry(it.id, it.name, R.drawable.ic_folder) })
                if (items.isEmpty()) showEmpty(R.string.drive_no_subfolders, 0, null)
            }.onFailure { e ->
                if (e is AuthRequired) {
                    showEmpty(R.string.drive_auth_needed, R.string.drive_connect) { (activity as MainActivity).connectDrive() }
                } else {
                    showEmpty(R.string.drive_offline, R.string.retry) { render() }
                }
            }
        }
    }

    private fun showEmpty(text: Int, action: Int, onAction: (() -> Unit)?) {
        emptyText.setText(text)
        if (action != 0 && onAction != null) {
            emptyAction.setText(action)
            emptyAction.setOnClickListener { onAction() }
            emptyAction.visibility = View.VISIBLE
        } else {
            emptyAction.visibility = View.GONE
        }
        empty.visibility = View.VISIBLE
    }

    private fun addCurrent() {
        val id = ids.lastOrNull() ?: return
        val name = names.last()
        val app = requireContext().applicationContext
        add.isEnabled = false
        Bg.run({ Roots.addDrive(app, id, name) }) { res ->
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

    private class Entry(val id: String, val name: String, val icon: Int)

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
                if (p != RecyclerView.NO_POSITION) onClick(items[p].id, items[p].name)
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
        const val SHARED = "\u0000shared"
        const val S_IDS = "ids"
        const val S_NAMES = "names"
    }
}
