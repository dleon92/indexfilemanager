package app.index.files.ui

import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import app.index.files.Bg
import app.index.files.R
import app.index.files.data.Indexer
import app.index.files.data.Prefs
import app.index.files.data.Root
import app.index.files.data.Roots

/**
 * Tela inicial: só as pastas raiz liberadas pelo usuário.
 *
 * Em tela larga (fase 12) uma segunda instância fica fixa no painel esquerdo ([pane]); a da
 * direita então só mostra "escolha uma pasta" (ou o convite para adicionar a primeira).
 */
class HomeFragment : Fragment(R.layout.fragment_list) {

    private val adapter = RootAdapter(manage = false) { r, _ -> open(r) }
    private var gen = 0
    private val indexListener = Indexer.Listener { running -> if (!running && view != null) load() }
    private val pane get() = arguments?.getBoolean(ARG_PANE) == true
    private var count = -1

    /** Painel lateral: atualiza ao voltar de outra tela (pastas podem ter mudado). */
    private val backListener = FragmentManager.OnBackStackChangedListener { if (view != null) load() }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val list = view.findViewById<RecyclerView>(R.id.list)
        list.layoutManager = LinearLayoutManager(requireContext())
        list.setHasFixedSize(true)
        list.adapter = adapter
        view.findViewById<Button>(R.id.empty_action).apply {
            setText(R.string.add_folder)
            setOnClickListener { (activity as MainActivity).chooseAdd() }
        }
        if (pane) {
            parentFragmentManager.addOnBackStackChangedListener(backListener)
        } else {
            parentFragmentManager.setFragmentResultListener(MainActivity.ROOTS_CHANGED, viewLifecycleOwner) { _, _ ->
                load()
            }
        }
        Indexer.addListener(indexListener)
        load()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        Indexer.removeListener(indexListener)
        if (pane) parentFragmentManager.removeOnBackStackChangedListener(backListener)
    }

    override fun onResume() {
        super.onResume()
        if (!pane) requireActivity().setTitle(R.string.app_name)
    }

    fun reload() {
        if (view != null) load()
    }

    /** A tela passou a ter (ou deixou de ter) o painel lateral. */
    fun onPaneChanged() {
        if (view != null) render()
    }

    private val sideBySide get() = !pane && (activity as? MainActivity)?.twoPane == true

    private fun render() {
        val v = view ?: return
        val roots = count
        if (roots < 0) return
        val hint = sideBySide && roots > 0
        v.findViewById<View>(R.id.list).visibility = if (hint) View.GONE else View.VISIBLE
        v.findViewById<TextView>(R.id.empty_text).setText(if (hint) R.string.pane_hint else R.string.home_empty)
        v.findViewById<View>(R.id.empty_action).visibility = if (hint) View.GONE else View.VISIBLE
        v.findViewById<View>(R.id.empty).visibility = if (roots == 0 || hint) View.VISIBLE else View.GONE
    }

    private fun load() {
        val g = ++gen
        val ctx = requireContext().applicationContext
        Bg.run({
            val roots = Roots.all(ctx).onEach { it.available = Roots.checkAvailable(ctx, it) }
            // Recentes no topo; sem pastas nem permissão, a tela vazia ("Adicionar pasta") vem primeiro.
            // Recentes (só as adicionadas pelo usuário) no topo.
            Roots.virtual(ctx) + roots
        }) { res ->
            if (view == null || g != gen) return@run
            val roots = res.getOrDefault(emptyList())
            adapter.items = roots
            count = roots.size
            render()
        }
    }

    private fun open(r: Root) {
        val main = activity as MainActivity
        if (r.available) {
            val f = BrowserFragment.newInstance(r.id)
            if (pane) main.openFromPane(f) else main.push(f)
            return
        }
        if (r.isDrive) {
            main.askConnect()
            return
        }
        if (r.isRecent) {
            main.readd(r) // pede "Acesso a todos os arquivos"
            return
        }
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.unavailable_title)
            .setMessage(R.string.folder_unavailable)
            .setPositiveButton(R.string.readd) { _, _ -> main.readd(r) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    companion object {
        private const val ARG_PANE = "pane"

        fun newPane() = HomeFragment().apply { arguments = Bundle().apply { putBoolean(ARG_PANE, true) } }
    }
}
