package app.index.files.ui

import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import app.index.files.Bg
import app.index.files.R
import app.index.files.data.Protection
import app.index.files.data.Root
import app.index.files.security.AppLock
import app.index.files.security.PasswordRec
import app.index.files.security.Vault
import app.index.files.security.VaultEntry

/** Fluxos de autenticação e configuração das pastas protegidas (item C com os métodos do item D). */
class VaultUi(private val main: MainActivity) {

    private val auth get() = main.auth
    private val app get() = main.applicationContext

    /**
     * Acesso à lista: senha do app, senão o bloqueio do celular, senão a senha das pastas
     * protegidas (criada na primeira vez).
     */
    fun openGate(onOk: () -> Unit) {
        val done = {
            Vault.openGate()
            onOk()
        }
        when (Vault.gateMethod(main)) {
            Vault.METHOD_APP -> auth.confirmCurrent(done)
            Vault.METHOD_DEVICE -> auth.device { ok -> if (ok) done() }
            else -> {
                // Leitura do cofre (uma decifragem pequena no Keystore): aceitável na main.
                val gate = Vault.gatePassword(app)
                if (gate != null) auth.unlockWith(gate, done)
                else createGatePassword(main.getString(R.string.vault_gate_create), done)
            }
        }
    }

    /** Sem bloqueio do app nem do celular: uma senha só para abrir as pastas protegidas. */
    private fun createGatePassword(note: String, onDone: () -> Unit) {
        var rec: PasswordRec? = null
        auth.setupPassword(
            note = note,
            onDone = {
                rec?.let { r -> Bg.run({ Vault.setGatePassword(app, r) }) { onDone() } }
            },
            save = { pw, k -> rec = PasswordRec.create(pw, k) },
            setBiometric = { rec?.biometric = it },
        )
    }

    /** Abre uma pasta da lista, pedindo o método dela (se diferente do que abriu a lista). */
    fun openFolder(e: VaultEntry, onOk: () -> Unit) {
        val done = {
            Vault.unlock(e.id)
            main.applySecureFlag()
            onOk()
        }
        if (Vault.isOpen(e.id)) {
            onOk()
            return
        }
        val gate = Vault.gateMethod(main)
        when (e.method) {
            // "Senha do app": já confirmada para abrir a lista.
            Vault.METHOD_APP -> done()
            Vault.METHOD_DEVICE -> when {
                !AppLock.deviceSecure(main) -> ownPassword(main.getString(R.string.lock_device_removed)) { rec ->
                    Bg.run({ Vault.setPassword(app, e.id, rec) }) { done() }
                }
                gate == Vault.METHOD_DEVICE -> done()
                else -> auth.device { ok -> if (ok) done() }
            }
            else -> e.password?.let { auth.unlockWith(it, done) } ?: done()
        }
    }

    /** Confirma a identidade para mexer na proteção (remover, trocar senha). */
    fun confirmFolder(e: VaultEntry, onOk: () -> Unit) {
        when (e.method) {
            Vault.METHOD_APP -> openGate(onOk)
            Vault.METHOD_DEVICE -> if (AppLock.deviceSecure(main)) auth.device { ok -> if (ok) onOk() } else openFolder(e, onOk)
            else -> e.password?.let { auth.unlockWith(it, onOk) } ?: onOk()
        }
    }

    private fun ownPassword(note: String?, onDone: (PasswordRec) -> Unit) {
        var rec: PasswordRec? = null
        auth.setupPassword(
            note = note,
            onDone = { rec?.let(onDone) },
            save = { pw, k -> rec = PasswordRec.create(pw, k) },
            setBiometric = { rec?.biometric = it },
        )
    }

    /** Troca a senha própria de uma pasta. */
    fun changePassword(e: VaultEntry, onDone: () -> Unit) {
        ownPassword(null) { rec -> Bg.run({ Vault.setPassword(app, e.id, rec) }) { onDone() } }
    }

    /**
     * Proteger uma pasta (raiz: [ids] com um elemento): explica, escolhe o método e some com
     * ela do Index. Garante antes um jeito de abrir a lista.
     */
    fun protect(r: Root, ids: List<String>, names: List<String>, onDone: () -> Unit) {
        AlertDialog.Builder(main)
            .setTitle(R.string.vault_protect_title)
            .setMessage(R.string.vault_protect_msg)
            .setPositiveButton(R.string.vault_protect_continue) { _, _ ->
                ensureGate { chooseMethod { method, rec -> doProtect(r, ids, names, method, rec, onDone) } }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun ensureGate(then: () -> Unit) {
        if (Vault.gateMethod(main) != Vault.METHOD_OWN) {
            then()
            return
        }
        Bg.run({ Vault.gatePassword(app) }) { r ->
            if (r.getOrNull() != null) then() else createGatePassword(main.getString(R.string.vault_gate_first), then)
        }
    }

    private fun chooseMethod(onChosen: (Int, PasswordRec?) -> Unit) {
        val appOn = AppLock.enabled(main)
        val deviceOn = AppLock.deviceSecure(main)
        val labels = arrayOf(
            main.getString(if (appOn) R.string.vault_method_app else R.string.vault_method_app_off),
            main.getString(if (deviceOn) R.string.vault_method_device else R.string.vault_method_device_off),
            main.getString(R.string.vault_method_own),
        )
        val d = AlertDialog.Builder(main)
            .setTitle(R.string.vault_method_title)
            .setItems(labels) { _, which ->
                when (which) {
                    0 -> if (appOn) onChosen(Vault.METHOD_APP, null)
                    1 -> if (deviceOn) onChosen(Vault.METHOD_DEVICE, null)
                    else -> ownPassword(null) { onChosen(Vault.METHOD_OWN, it) }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .create()
        d.show()
        // Opções indisponíveis aparecem esmaecidas (com o motivo no texto).
        d.listView?.post {
            if (!appOn) d.listView.getChildAt(0)?.apply { isEnabled = false; alpha = 0.4f }
            if (!deviceOn) d.listView.getChildAt(1)?.apply { isEnabled = false; alpha = 0.4f }
        }
    }

    private fun doProtect(r: Root, ids: List<String>, names: List<String>, method: Int, rec: PasswordRec?, onDone: () -> Unit) {
        Bg.run({ Protection.protect(app, r, ids, names, method, rec) }) { res ->
            res.onSuccess {
                Toast.makeText(app, R.string.vault_protected_done, Toast.LENGTH_LONG).show()
            }.onFailure {
                Toast.makeText(app, R.string.vault_protect_failed, Toast.LENGTH_LONG).show()
            }
            onDone()
        }
    }
}
