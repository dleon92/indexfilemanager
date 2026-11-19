package app.index.files.ui

import android.os.Bundle
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.PopupMenu
import androidx.core.view.MenuProvider
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import app.index.files.Bg
import app.index.files.R
import app.index.files.data.Indexer
import app.index.files.data.Root
import app.index.files.data.Roots
import app.index.files.thumbs.ThumbnailManager

/** "Gerenciar pastas": adicionar, renomear apelido, readicionar e remover. */
class ManageFoldersFragment : Fragment(R.layout.fragment_list) {

    private val adapter = RootAdapter(manage = true) { r, anchor -> showActions(r, anchor) }
    private var gen = 0
    private val indexListener = Indexer.Listener { running -> if (!running && view != null) load() }

    private val menu = object : MenuProvider {
        override fun onCreateMenu(menu: Menu, inflater: MenuInflater) = inflater.inflate(R.menu.manage, menu)
        override fun onMenuItemSelected(item: MenuItem): Boolean {
            when (item.itemId) {
                R.id.action_add_local -> main().addLocalFolder()
                R.id.action_add_drive -> main().addDriveFolder()
                else -> return false
            }
            return true
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val list = view.findViewById<RecyclerView>(R.id.list)
        list.layoutManager = LinearLayoutManager(requireContext())
        list.setHasFixedSize(true)
        list.adapter = adapter
        view.findViewById<TextView>(R.id.empty_text).setText(R.string.manage_empty)
        view.findViewById<Button>(R.id.empty_action).apply {
            setText(R.string.add_folder)
            setOnClickListener { main().chooseAdd() }
        }
        requireActivity().addMenuProvider(menu, viewLifecycleOwner, Lifecycle.State.RESUMED)
        parentFragmentManager.setFragmentResultListener(MainActivity.ROOTS_CHANGED, viewLifecycleOwner) { _, _ ->
            load()
        }
        Indexer.addListener(indexListener)
        load()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        Indexer.removeListener(indexListener)
    }

    override fun onResume() {
        super.onResume()
        requireActivity().setTitle(R.string.manage_folders)
    }

    private fun main() = activity as MainActivity

    private fun load() {
        val g = ++gen
        val ctx = requireContext().applicationContext
        Bg.run({ Roots.all(ctx).onEach { it.available = Roots.checkAvailable(ctx, it) } }) { res ->
            val v = view ?: return@run
            if (g != gen) return@run
            val roots = res.getOrDefault(emptyList())
            adapter.items = roots
            v.findViewById<View>(R.id.empty).visibility = if (roots.isEmpty()) View.VISIBLE else View.GONE
        }
    }

    private fun showActions(r: Root, anchor: View) {
        val pm = PopupMenu(requireContext(), anchor)
        pm.menu.add(0, 1, 0, R.string.rename)
        if (!r.available) pm.menu.add(0, 2, 1, if (r.isDrive) R.string.drive_connect else R.string.readd)
        if (r.available) pm.menu.add(0, 5, 2, R.string.reindex)
        pm.menu.add(0, 4, 3, R.string.clear_folder_thumbs)
        pm.menu.add(0, 6, 4, R.string.vault_protect)
        pm.menu.add(0, 3, 5, R.string.remove)
        pm.setOnMenuItemClickListener {
            when (it.itemId) {
                1 -> rename(r)
                2 -> if (r.isDrive) main().connectDrive() else main().readd(r)
                3 -> confirmRemove(r)
                4 -> clearThumbs(r)
                6 -> main().vaultUi.protect(r, listOf(r.docId), listOf(r.alias)) {
                    if (view != null) load()
                    parentFragmentManager.setFragmentResult(MainActivity.ROOTS_CHANGED, Bundle.EMPTY)
                }
                5 -> {
                    Indexer.start(requireContext(), r.id, full = true)
                    Toast.makeText(requireContext(), R.string.reindexing, Toast.LENGTH_SHORT).show()
                }
            }
            true
        }
        pm.show()
    }

    private fun rename(r: Root) {
        val ctx = requireContext()
        val input = EditText(ctx).apply {
            setText(r.alias)
            setSelectAllOnFocus(true)
            isSingleLine = true
        }
        val pad = (20 * resources.displayMetrics.density).toInt()
        val box = FrameLayout(ctx).apply {
            setPadding(pad, pad / 2, pad, 0)
            addView(input)
        }
        AlertDialog.Builder(ctx)
            .setTitle(R.string.rename)
            .setView(box)
            .setPositiveButton(R.string.ok) { _, _ ->
                val alias = input.text.toString().trim().ifEmpty { r.name }
                val app = ctx.applicationContext
                Bg.run({ Roots.rename(app, r.id, alias) }) { if (view != null) load() }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
        input.requestFocus()
    }

    private fun clearThumbs(r: Root) {
        val app = requireContext().applicationContext
        Bg.run({ ThumbnailManager.get(app).clearRoot(r.id, Roots.source(app, r)) }) {
            Toast.makeText(app, R.string.folder_thumbs_cleared, Toast.LENGTH_SHORT).show()
        }
    }

    private fun confirmRemove(r: Root) {
        val ctx = requireContext()
        AlertDialog.Builder(ctx)
            .setTitle(R.string.remove)
            .setMessage(getString(R.string.remove_confirm, r.alias))
            .setPositiveButton(R.string.remove) { _, _ ->
                val app = ctx.applicationContext
                Bg.run({ Roots.remove(app, r.id) }) { if (view != null) load() }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }
}
