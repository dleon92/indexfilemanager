package app.index.files.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
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
import app.index.files.data.Protection
import app.index.files.data.Roots
import app.index.files.security.Vault
import app.index.files.security.VaultEntry

/**
 * "Pastas protegidas": só existe depois da autenticação ([Vault.gateIsOpen]). Abrir, trocar
 * a senha própria ou remover a proteção. Sair desta tela oculta tudo de novo.
 */
class ProtectedFragment : Fragment(R.layout.fragment_list) {

    private var entries: List<VaultEntry> = emptyList()
    private val adapter = Adapter()

    private fun main() = activity as MainActivity

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        if (!Vault.gateIsOpen()) {
            // Restaurado sem autenticação (ex.: processo recriado): nada aparece.
            parentFragmentManager.popBackStack(MainActivity.VAULT_STACK, androidx.fragment.app.FragmentManager.POP_BACK_STACK_INCLUSIVE)
            return
        }
        val list = view.findViewById<RecyclerView>(R.id.list)
        list.layoutManager = LinearLayoutManager(requireContext())
        list.adapter = adapter
        view.findViewById<TextView>(R.id.empty_text).setText(R.string.vault_empty)
        view.findViewById<Button>(R.id.empty_action).visibility = View.GONE
        requireActivity().addMenuProvider(menu, viewLifecycleOwner, Lifecycle.State.RESUMED)
        load()
    }

    override fun onResume() {
        super.onResume()
        requireActivity().setTitle(R.string.protected_folders)
    }

    override fun onDestroy() {
        super.onDestroy()
        // Saiu da lista (voltar): tudo oculto de novo.
        if (isRemoving && activity?.isChangingConfigurations != true) {
            (activity as? MainActivity)?.closeVault()
        }
    }

    private fun load() {
        val app = requireContext().applicationContext
        Bg.run({ Vault.entries(app) }) { res ->
            val v = view ?: return@run
            entries = res.getOrDefault(emptyList())
            adapter.notifyDataSetChanged()
            v.findViewById<View>(R.id.empty).visibility = if (entries.isEmpty()) View.VISIBLE else View.GONE
        }
    }

    private fun open(e: VaultEntry) {
        main().vaultUi.openFolder(e) {
            if (isAdded && view != null) main().push(BrowserFragment.newVault(e.id))
        }
    }

    private fun showActions(e: VaultEntry, anchor: View) {
        val pm = PopupMenu(requireContext(), anchor)
        pm.menu.add(0, 1, 0, R.string.open)
        if (e.method == Vault.METHOD_OWN) pm.menu.add(0, 2, 1, R.string.vault_change_password)
        pm.menu.add(0, 3, 2, R.string.vault_unprotect)
        pm.setOnMenuItemClickListener {
            when (it.itemId) {
                1 -> open(e)
                2 -> {
                    val app = requireContext().applicationContext
                    val ui = main().vaultUi
                    ui.confirmFolder(e) {
                        ui.changePassword(e) { Toast.makeText(app, R.string.lock_changed, Toast.LENGTH_SHORT).show() }
                    }
                }
                3 -> confirmUnprotect(e)
            }
            true
        }
        pm.show()
    }

    private fun confirmUnprotect(e: VaultEntry) {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.vault_unprotect)
            .setMessage(getString(R.string.vault_unprotect_msg, e.name))
            .setPositiveButton(R.string.vault_unprotect) { _, _ ->
                // A autenticação pode demorar; nada aqui depende de a tela ainda existir.
                val app = requireContext().applicationContext
                main().vaultUi.confirmFolder(e) {
                    Bg.run({ Protection.unprotect(app, e) }) { res ->
                        Toast.makeText(
                            app,
                            if (res.isSuccess) R.string.vault_unprotected else R.string.vault_protect_failed,
                            Toast.LENGTH_SHORT,
                        ).show()
                        if (isAdded && view != null) load()
                    }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private val menu = object : MenuProvider {
        override fun onCreateMenu(menu: Menu, inflater: MenuInflater) {
            menu.add(0, 1, 0, R.string.vault_relock)
        }

        override fun onMenuItemSelected(item: MenuItem): Boolean {
            if (item.itemId != 1) return false
            val ctx = requireContext()
            AlertDialog.Builder(ctx)
                .setTitle(R.string.vault_relock)
                .setSingleChoiceItems(R.array.vault_relock_options, Vault.relockIndex(ctx)) { d, which ->
                    Vault.setRelockIndex(ctx, which)
                    d.dismiss()
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
            return true
        }
    }

    private inner class Adapter : RecyclerView.Adapter<Adapter.VH>() {
        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val icon: ImageView = v.findViewById(R.id.icon)
            val name: TextView = v.findViewById(R.id.name)
            val meta: TextView = v.findViewById(R.id.meta)
            val more: View = v.findViewById(R.id.more)
        }

        override fun getItemCount() = entries.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_protected, parent, false)
            val h = VH(v)
            v.setOnClickListener { h.bindingAdapterPosition.takeIf { it >= 0 }?.let { open(entries[it]) } }
            h.more.setOnClickListener { h.bindingAdapterPosition.takeIf { it >= 0 }?.let { p -> showActions(entries[p], h.more) } }
            v.setOnLongClickListener {
                h.bindingAdapterPosition.takeIf { it >= 0 }?.let { p -> showActions(entries[p], h.more) }
                true
            }
            return h
        }

        override fun onBindViewHolder(h: VH, position: Int) {
            val e = entries[position]
            h.name.text = e.name
            h.icon.setImageResource(if (e.tag == Roots.DRIVE) R.drawable.ic_cloud else R.drawable.ic_folder)
            val method = getString(
                when (e.method) {
                    Vault.METHOD_APP -> R.string.vault_uses_app
                    Vault.METHOD_DEVICE -> R.string.vault_uses_device
                    else -> R.string.vault_uses_own
                },
            )
            h.meta.text = "${e.path} · $method"
        }
    }
}
