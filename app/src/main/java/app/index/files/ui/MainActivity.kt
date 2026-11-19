package app.index.files.ui

import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.KeyEvent
import android.provider.DocumentsContract
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewStub
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.Lifecycle
import app.index.files.Bg
import app.index.files.R
import app.index.files.data.Indexer
import app.index.files.data.Prefs
import app.index.files.data.Roots
import app.index.files.data.drive.DriveAuth
import app.index.files.security.AppLock
import app.index.files.security.AppReset
import app.index.files.security.Vault
import app.index.files.data.VaultIndex
import app.index.files.thumbs.Preload
import app.index.files.thumbs.ThumbnailManager
import com.google.android.gms.auth.api.identity.AuthorizationResult

/** Activity única; as telas são Fragments simples, sem biblioteca de navegação. */
class MainActivity : AppCompatActivity() {

    /** Id da raiz sendo readicionada, ou -1 para adicionar uma nova. */
    private var replaceRootId = -1L

    private val treePicker = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) onTreePicked(uri)
    }

    /** O que fazer ao voltar da tela "Acesso a todos os arquivos" (true = permissão concedida). */
    private var afterAllFiles: ((Boolean) -> Unit)? = null

    private val allFilesLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        val then = afterAllFiles
        afterAllFiles = null
        then?.invoke(Roots.hasAllFiles())
    }

    /** Abrir o seletor do Drive assim que a conta for conectada. */
    private var pickDriveAfterConnect = false

    /** Conectou pelo atalho "Recentes do Drive" do Adicionar pasta: abre a pasta ao terminar. */
    private var recentAfterConnect = false

    /** Tela de consentimento/escolha de conta do Google (Play Services). */
    private val authLauncher = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { res ->
        if (res.resultCode != RESULT_OK) {
            pickDriveAfterConnect = false
            recentAfterConnect = false
            Toast.makeText(this, R.string.drive_connect_cancelled, Toast.LENGTH_SHORT).show()
            return@registerForActivityResult
        }
        val r = runCatching { DriveAuth.client(this).getAuthorizationResultFromIntent(res.data) }
        r.onSuccess { finishConnect(it) }.onFailure {
            Log.w("Drive", "resultado da autorização", it)
            pickDriveAfterConnect = false
            recentAfterConnect = false
            Toast.makeText(this, R.string.drive_connect_failed, Toast.LENGTH_LONG).show()
        }
    }

    val auth = Auth(this)

    private lateinit var mainUi: View
    private var lockView: View? = null
    /** A tela inicial ainda não foi criada (app aberto já bloqueado). */
    private var homePending = false
    /** Abrir o prompt (biometria / senha do celular) quando a tela de bloqueio ficar visível. */
    private var promptPending = false
    private val lockTick = object : Runnable {
        override fun run() = renderLockState()
    }

    /** Com o app bloqueado, "voltar" sai do app (não navega no conteúdo escondido). */
    private val lockBack = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            moveTaskToBack(true)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Precisa vir antes de super.onCreate para não haver troca visível de tema.
        appliedTheme = Themes.current(this).id
        setTheme(Themes.current(this).style)
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        applySecureFlag()
        setContentView(R.layout.activity_main)
        mainUi = findViewById(R.id.main_ui)
        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        applyInsets(toolbar)
        updatePanes()
        replaceRootId = savedInstanceState?.getLong(S_REPLACE, -1L) ?: -1L

        val fm = supportFragmentManager
        val locked = AppLock.needsUnlock(this)
        if (savedInstanceState == null) {
            // Bloqueado: nada de conteúdo (nem lista de pastas) até desbloquear.
            if (locked) {
                homePending = true
            } else {
                fm.beginTransaction().replace(R.id.content, HomeFragment()).commit()
                openStartFolder()
            }
        }
        fm.addOnBackStackChangedListener { syncToolbar() }
        syncToolbar()
        Preload.addListener(preloadListener)
        if (locked) showLock()
        // Indexação incremental ao abrir o app, depois do primeiro frame (não pesa na abertura).
        if (savedInstanceState == null) Bg.main.postDelayed({ Indexer.start(applicationContext) }, 1500)
    }

    /** Raio na barra: aparece/some com a pré-carga de miniaturas. */
    private var preloadShown = false
    private val preloadListener = Preload.Listener {
        if (Preload.holding != preloadShown) {
            preloadShown = Preload.holding
            invalidateOptionsMenu()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Preload.removeListener(preloadListener)
    }

    // ---- Tela larga e teclado (fase 12) ----

    /** Dois painéis: pastas à esquerda, conteúdo à direita (janela com ≥ 840 dp de largura). */
    var twoPane = false
        private set

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        updatePanes()
    }

    /** Liga/desliga o painel de pastas conforme a largura atual da janela (girar, multi-janela). */
    private fun updatePanes() {
        val wDp = resources.configuration.screenWidthDp
        val two = wDp >= TWO_PANE_DP
        val nav = findViewById<View>(R.id.nav_pane)
        val d = resources.displayMetrics.density
        nav.layoutParams = nav.layoutParams.apply { width = ((wDp * 0.26f).coerceIn(260f, 360f) * d).toInt() }
        nav.visibility = if (two) View.VISIBLE else View.GONE
        findViewById<View>(R.id.nav_divider).visibility = nav.visibility
        val fm = supportFragmentManager
        if (two && fm.findFragmentById(R.id.nav_pane) == null && !fm.isStateSaved) {
            fm.beginTransaction().replace(R.id.nav_pane, HomeFragment.newPane()).commit()
        }
        if (two != twoPane) {
            twoPane = two
            (fm.findFragmentById(R.id.content) as? HomeFragment)?.onPaneChanged()
        }
    }

    /** Largura da área de conteúdo (antes do layout, estimada pela janela). */
    fun contentWidthPx(): Int {
        val c = findViewById<View>(R.id.content)
        if (c.width > 0) return c.width
        val d = resources.displayMetrics.density
        val w = (resources.configuration.screenWidthDp * d).toInt()
        val nav = findViewById<View>(R.id.nav_pane)
        return if (twoPane) w - nav.layoutParams.width - d.toInt() else w
    }

    /** Só o painel lateral (ex.: filtro de Recentes mudou a descrição da linha). */
    fun refreshNavPane() {
        (supportFragmentManager.findFragmentById(R.id.nav_pane) as? HomeFragment)?.reload()
    }

    /** Pastas raiz mudaram: avisa a tela aberta e o painel lateral. */
    private fun notifyRootsChanged() {
        supportFragmentManager.setFragmentResult(ROOTS_CHANGED, Bundle.EMPTY)
        (supportFragmentManager.findFragmentById(R.id.nav_pane) as? HomeFragment)?.reload()
    }

    /** Pasta escolhida no painel lateral: substitui tudo o que está aberto à direita. */
    fun openFromPane(f: Fragment) {
        val fm = supportFragmentManager
        if (fm.isStateSaved) return
        fm.popBackStack(null, FragmentManager.POP_BACK_STACK_INCLUSIVE)
        push(f)
    }

    /** Telas que tratam atalhos de teclado próprios. */
    interface KeyTarget {
        fun onKey(e: KeyEvent): Boolean
    }

    /**
     * Teclado físico: a tela atual tem a primeira chance (Ctrl+F, Ctrl+A, setas...);
     * depois Backspace/Esc voltam (fora de campos de texto) e Ctrl+F abre a pesquisa geral.
     * Setas e Enter nos itens são a navegação por foco do próprio Android.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && lockView?.visibility != View.VISIBLE) {
            // Visualizador aberto: ele tem a vez (setas trocam de foto) e nada vaza para trás.
            app.index.files.ui.viewer.ViewerFragment.find(this)?.let { return it.onKey(event) || super.dispatchKeyEvent(event) }
            app.index.files.ui.doc.DocViewerFragment.find(this)?.let { return it.onKey(event) || super.dispatchKeyEvent(event) }
            val top = supportFragmentManager.findFragmentById(R.id.content)
            if ((top as? KeyTarget)?.onKey(event) == true) return true
            val typing = currentFocus is EditText
            when {
                event.isCtrlPressed && event.keyCode == KeyEvent.KEYCODE_F -> {
                    push(SearchFragment.newInstance())
                    return true
                }
                !typing && event.repeatCount == 0 &&
                    (event.keyCode == KeyEvent.KEYCODE_DEL || event.keyCode == KeyEvent.KEYCODE_ESCAPE) -> {
                    onBackPressedDispatcher.onBackPressed()
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    /** Id do tema com que esta Activity foi criada. */
    private var appliedTheme = ""

    /**
     * Edge-to-edge com cores próprias: a toolbar cresce por baixo da barra de status
     * (fica com a cor da barra superior) e o fundo de main_ui (cor da barra de
     * navegação) aparece no espaço da barra de navegação e do teclado.
     */
    private fun applyInsets(toolbar: Toolbar) {
        val barH = toolbar.layoutParams.height
        ViewCompat.setOnApplyWindowInsetsListener(mainUi) { v, wi ->
            val bars = wi.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val ime = wi.getInsets(WindowInsetsCompat.Type.ime())
            toolbar.layoutParams = toolbar.layoutParams.apply { height = barH + bars.top }
            toolbar.setPadding(toolbar.paddingLeft, bars.top, toolbar.paddingRight, toolbar.paddingBottom)
            v.setPadding(bars.left, 0, bars.right, maxOf(bars.bottom, ime.bottom))
            wi // não consome: a tela de bloqueio (irmã) também precisa dos insets
        }
    }

    /** Modo por horário: reavalia na próxima virada enquanto o app está na tela. */
    private val scheduleTick = Runnable { onThemeClock() }

    fun onThemeClock() {
        Bg.main.removeCallbacks(scheduleTick)
        Themes.apply(this)
        val ms = Themes.msToNextBoundary(Prefs.get(this))
        if (ms > 0) Bg.main.postDelayed(scheduleTick, ms)
    }

    /** Tema claro/escuro escolhido mudou: recria se afetou o que está na tela. */
    fun onThemeChoiceChanged() {
        if (Themes.current(this).id != appliedTheme) recreate()
    }

    override fun onUserInteraction() {
        super.onUserInteraction()
        Preload.touch()
    }

    override fun onStart() {
        super.onStart()
        onThemeClock()
        Preload.appShown()
        AppLock.onShown(this)
        // Tempo fora do app esgotado: pastas protegidas voltam a se ocultar.
        if (Vault.onShown(this)) closeVault()
        applySecureFlag()
        if (AppLock.needsUnlock(this) && lockView?.visibility != View.VISIBLE) showLock()
        // Volta de outro app: índice incremental de novo (busca e contagens veem arquivos novos).
        // Barato: só relista pastas cuja data mudou; o Drive respeita o próprio intervalo.
        if (hiddenSince > 0 && System.currentTimeMillis() - hiddenSince > 10_000) {
            Bg.main.postDelayed({ Indexer.start(applicationContext) }, 800)
        }
        hiddenSince = 0
    }

    /** Quando o app saiu da tela (0 = está visível). */
    private var hiddenSince = 0L

    override fun onResume() {
        super.onResume()
        if (promptPending && lockView?.visibility == View.VISIBLE) {
            promptPending = false
            autoPrompt()
        }
    }

    override fun onStop() {
        super.onStop()
        Bg.main.removeCallbacks(scheduleTick)
        if (!isChangingConfigurations) {
            hiddenSince = System.currentTimeMillis()
            AppLock.onHidden()
            Vault.onHidden()
            Preload.appHidden()
        }
    }

    // ---- Pastas protegidas ----

    val vaultUi = VaultUi(this)

    /** Abre "Pastas protegidas" (depois da autenticação). Tudo dali para frente sai junto em [closeVault]. */
    fun openVaultList() {
        vaultUi.openGate {
            applySecureFlag()
            supportFragmentManager.beginTransaction()
                .replace(R.id.content, ProtectedFragment())
                .addToBackStack(VAULT_STACK)
                .commit()
        }
    }

    /** Oculta de novo: fecha a lista e as pastas protegidas abertas, solta índice e miniaturas da memória. */
    fun closeVault() {
        Vault.lockAll()
        VaultIndex.dropAll()
        app.index.files.ui.viewer.ViewerFragment.find(this)?.close()
        app.index.files.ui.doc.DocViewerFragment.find(this)?.close()
        ThumbnailManager.get(this).dropDecrypted()
        if (!supportFragmentManager.isStateSaved) {
            supportFragmentManager.popBackStack(VAULT_STACK, androidx.fragment.app.FragmentManager.POP_BACK_STACK_INCLUSIVE)
        }
        applySecureFlag()
    }

    /** FLAG_SECURE: conteúdo oculto em apps recentes e capturas bloqueadas. */
    fun applySecureFlag() {
        // Pasta protegida visível: sempre, mesmo sem o bloqueio do app.
        if (AppLock.secureScreen(this) || Vault.anyOpen()) {
            window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }

    // ---- Tela de bloqueio ----

    private fun showLock() {
        val v = lockView ?: findViewById<ViewStub>(R.id.lock_stub).inflate().also {
            lockView = it
            bindLock(it)
        }
        closeOptionsMenu()
        OpRunner.setLocked(true) // a janela de progresso não pode aparecer por cima do bloqueio
        mainUi.visibility = View.INVISIBLE
        findViewById<View>(R.id.viewer_host).visibility = View.INVISIBLE
        v.visibility = View.VISIBLE
        // Recoloca o callback por último: tem prioridade sobre os dos fragments.
        lockBack.remove()
        onBackPressedDispatcher.addCallback(this, lockBack)
        lockBack.isEnabled = true
        v.findViewById<TextView>(R.id.lock_error).visibility = View.GONE
        renderLockState()
        promptPending = true
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            promptPending = false
            autoPrompt()
        }
    }

    private fun hideLock() {
        val v = lockView ?: return
        OpRunner.setLocked(false)
        Bg.main.removeCallbacks(lockTick)
        val input = v.findViewById<EditText>(R.id.lock_input)
        input.text.clear()
        getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(input.windowToken, 0)
        v.visibility = View.GONE
        mainUi.visibility = View.VISIBLE
        findViewById<View>(R.id.viewer_host).visibility = View.VISIBLE
        lockBack.isEnabled = false
        if (homePending && !supportFragmentManager.isStateSaved) {
            homePending = false
            supportFragmentManager.beginTransaction().replace(R.id.content, HomeFragment()).commit()
            openStartFolder()
        }
    }

    private fun unlocked() {
        AppLock.markUnlocked(this)
        hideLock()
    }

    private fun bindLock(v: View) {
        val input = v.findViewById<EditText>(R.id.lock_input)
        v.findViewById<View>(R.id.lock_ok).setOnClickListener { primaryAction() }
        input.setOnEditorActionListener { _, _, _ -> primaryAction(); true }
        v.findViewById<View>(R.id.lock_alt).setOnClickListener {
            auth.biometric(onOk = { unlocked() }, onFallback = { focusInput() })
        }
        v.findViewById<View>(R.id.lock_forgot).setOnClickListener { confirmReset() }
    }

    /** Ajusta a tela de bloqueio ao método atual e à espera por tentativas erradas. */
    private fun renderLockState() {
        val v = lockView ?: return
        Bg.main.removeCallbacks(lockTick)
        val msg = v.findViewById<TextView>(R.id.lock_msg)
        val input = v.findViewById<EditText>(R.id.lock_input)
        val ok = v.findViewById<Button>(R.id.lock_ok)
        val alt = v.findViewById<View>(R.id.lock_alt)
        val forgot = v.findViewById<View>(R.id.lock_forgot)
        if (AppLock.method(this) == AppLock.METHOD_DEVICE) {
            input.visibility = View.GONE
            alt.visibility = View.GONE
            forgot.visibility = View.GONE
            ok.isEnabled = true
            if (AppLock.deviceSecure(this)) {
                msg.setText(R.string.lock_msg_device)
                ok.setText(R.string.lock_unlock)
            } else {
                msg.setText(R.string.lock_device_removed)
                ok.setText(R.string.lock_create_title)
            }
            return
        }
        val kind = AppLock.kind(this)
        input.visibility = View.VISIBLE
        forgot.visibility = View.VISIBLE
        ok.setText(R.string.lock_unlock)
        alt.visibility = if (AppLock.biometric(this) && AppLock.biometricAvailable(this)) View.VISIBLE else View.GONE
        if (input.inputType != Auth.inputTypeFor(kind)) input.inputType = Auth.inputTypeFor(kind)
        input.setHint(if (kind == AppLock.KIND_PIN) R.string.lock_hint_pin else R.string.lock_hint_password)
        val wait = AppLock.waitRemainingMs(this)
        if (wait > 0) {
            msg.text = getString(R.string.lock_wait, Auth.formatWait(wait))
            input.isEnabled = false
            ok.isEnabled = false
            Bg.main.postDelayed(lockTick, 1000)
        } else {
            msg.setText(if (kind == AppLock.KIND_PIN) R.string.lock_msg_pin else R.string.lock_msg_password)
            input.isEnabled = true
            ok.isEnabled = true
        }
    }

    /** Ao aparecer: abre direto a senha do celular ou a biometria. */
    private fun autoPrompt() {
        if (AppLock.method(this) == AppLock.METHOD_DEVICE) {
            if (AppLock.deviceSecure(this)) auth.device { if (it) unlocked() }
            return
        }
        if (AppLock.waitRemainingMs(this) == 0L && AppLock.biometric(this) && AppLock.biometricAvailable(this)) {
            auth.biometric(onOk = { unlocked() }, onFallback = { focusInput() })
        } else {
            focusInput()
        }
    }

    private fun focusInput() {
        val input = lockView?.findViewById<EditText>(R.id.lock_input) ?: return
        if (!input.isEnabled || input.visibility != View.VISIBLE) return
        input.requestFocus()
        input.post { getSystemService(InputMethodManager::class.java).showSoftInput(input, 0) }
    }

    /** Botão principal: conferir a senha, abrir a senha do celular ou criar senha. */
    private fun primaryAction() {
        val v = lockView ?: return
        if (AppLock.method(this) == AppLock.METHOD_DEVICE) {
            if (AppLock.deviceSecure(this)) {
                auth.device { if (it) unlocked() }
            } else {
                auth.deviceLockRemoved { unlocked() }
            }
            return
        }
        val input = v.findViewById<EditText>(R.id.lock_input)
        val ok = v.findViewById<View>(R.id.lock_ok)
        val error = v.findViewById<TextView>(R.id.lock_error)
        if (input.text.isEmpty() || !ok.isEnabled) return
        val pw = input.text.toString().toCharArray()
        input.text.clear()
        ok.isEnabled = false
        val app = applicationContext
        Bg.run({ AppLock.verify(app, pw) }) { r ->
            if (isFinishing) return@run
            if (r.getOrDefault(false)) {
                error.visibility = View.GONE
                unlocked()
            } else {
                error.text = auth.wrongMessage()
                error.visibility = View.VISIBLE
                renderLockState()
            }
        }
    }

    private fun confirmReset() {
        AlertDialog.Builder(this)
            .setTitle(R.string.reset_title)
            .setMessage(R.string.reset_msg_forgot)
            .setPositiveButton(R.string.reset_confirm) { _, _ -> resetApp() }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** Apaga tudo o que o Index guarda (nunca os arquivos do usuário) e reinicia. */
    fun resetApp() {
        Toast.makeText(this, R.string.reset_running, Toast.LENGTH_SHORT).show()
        Bg.run({ AppReset.wipe(applicationContext) }) { AppReset.restart(this) }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putLong(S_REPLACE, replaceRootId)
    }

    private fun syncToolbar() {
        val nested = supportFragmentManager.backStackEntryCount > 0
        supportActionBar?.setDisplayHomeAsUpEnabled(nested)
        invalidateOptionsMenu()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main, menu)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        val top = supportFragmentManager.backStackEntryCount == 0
        // Configurações em todas as telas (último item do menu ⋮), menos nela mesma.
        menu.findItem(R.id.action_settings).isVisible =
            supportFragmentManager.findFragmentById(R.id.content) !is SettingsFragment
        menu.findItem(R.id.action_manage).isVisible = top
        // Id próprio: o navegador tem o seu "action_search" (com escopo da pasta atual).
        menu.findItem(R.id.action_search_all).isVisible = top
        // Indicador discreto da pré-carga (rodando ou com miniaturas extras na RAM).
        menu.findItem(R.id.action_preload_status).isVisible = Preload.holding
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.action_settings -> { push(SettingsFragment()); true }
        R.id.action_manage -> { push(ManageFoldersFragment()); true }
        R.id.action_search_all -> { push(SearchFragment.newInstance()); true }
        R.id.action_protected -> { openVaultList(); true }
        R.id.action_preload_status -> { PreloadUi.showStatus(this); true }
        else -> super.onOptionsItemSelected(item)
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }

    fun push(f: Fragment) {
        supportFragmentManager.beginTransaction()
            .replace(R.id.content, f)
            .addToBackStack(null)
            .commit()
    }

    /**
     * "Adicionar pasta": deste aparelho, do Google Drive ou as pastas Recentes ainda não
     * adicionadas (pedem a permissão ou o login e abrem a pasta).
     */
    fun chooseAdd() {
        val p = Prefs.get(this)
        val labels = arrayListOf(getString(R.string.source_local_device), getString(R.string.source_drive))
        val actions = arrayListOf<() -> Unit>({ addLocalFolder() }, { addDriveFolder() })
        if (Build.VERSION.SDK_INT >= 30 && !p.recentAdded) {
            labels.add(getString(R.string.add_recent_local))
            actions.add { addRecentLocal() }
        }
        if (!p.driveRecentAdded) {
            labels.add(getString(R.string.add_recent_drive))
            actions.add { addRecentDrive() }
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.add_folder_from)
            .setItems(labels.toTypedArray()) { _, which -> actions[which]() }
            .show()
    }

    /** "Recentes": pede "Acesso a todos os arquivos" se faltar e abre a pasta. */
    fun addRecentLocal() {
        if (Roots.hasAllFiles()) {
            openRecent(Roots.RECENT_ID)
            return
        }
        askAllFiles { granted ->
            if (!granted) {
                Toast.makeText(this, R.string.all_files_denied_recent, Toast.LENGTH_LONG).show()
                return@askAllFiles
            }
            if (!isFinishing && !supportFragmentManager.isStateSaved) openRecent(Roots.RECENT_ID)
        }
    }

    /** "Recentes do Drive": conecta a conta se preciso e abre a pasta. */
    fun addRecentDrive() {
        if (DriveAuth.connected(this)) {
            openRecent(Roots.DRIVE_RECENT_ID)
            return
        }
        recentAfterConnect = true
        connectDrive()
    }

    /** Adiciona a Recentes [id] à lista de pastas e abre. */
    private fun openRecent(id: Long) {
        Roots.setVirtual(this, id, true)
        notifyRootsChanged()
        push(BrowserFragment.newInstance(id))
    }

    /**
     * "Adicionar pasta local". Android 11+: seletor próprio pelo caminho, que aceita também as pastas
     * que o seletor do sistema recusa (Download, raiz do armazenamento). Pede "Acesso a todos os
     * arquivos" uma vez; recusada, cai no seletor do sistema. Antes do Android 11: seletor do sistema.
     */
    fun addLocalFolder() {
        if (Build.VERSION.SDK_INT < 30) {
            pickTree()
            return
        }
        if (Roots.hasAllFiles()) {
            push(LocalPickerFragment())
            return
        }
        askAllFiles { granted ->
            if (granted) {
                if (!isFinishing && !supportFragmentManager.isStateSaved) push(LocalPickerFragment())
            } else {
                Toast.makeText(this, R.string.all_files_denied, Toast.LENGTH_LONG).show()
                pickTree()
            }
        }
    }

    /** Explica e abre a tela do Android para "Acesso a todos os arquivos". [then] ao voltar. */
    private fun askAllFiles(onCancel: (() -> Unit)? = null, then: (Boolean) -> Unit) {
        AlertDialog.Builder(this)
            .setTitle(R.string.all_files_title)
            .setMessage(R.string.all_files_msg)
            .setPositiveButton(R.string.all_files_open) { _, _ ->
                afterAllFiles = then
                AppLock.beginOwnFlow()
                val i = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName"))
                // Alguns aparelhos não têm a tela por app: cai na lista geral.
                runCatching { allFilesLauncher.launch(i) }
                    .recoverCatching { allFilesLauncher.launch(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) }
                    .onFailure { afterAllFiles = null; then(false) }
            }
            .setNegativeButton(R.string.cancel) { _, _ -> onCancel?.invoke() }
            .show()
    }

    /**
     * Pasta inicial (Configurações): abre por cima da lista de pastas, então "voltar" leva à lista.
     * Pasta removida, indisponível ou protegida desde então: fica só a lista de pastas.
     */
    private fun openStartFolder() {
        val p = Prefs.get(this)
        val id = p.startRoot
        if (id == 0L) return
        val ids = p.startIds
        val names = p.startNames
        val app = applicationContext
        Bg.run({
            val r = Roots.get(app, id)?.takeIf { r ->
                Roots.checkAvailable(app, r) && !Vault.isHiddenRoot(app, r) && ids.none { Vault.isHidden(app, r.id, it) }
            } ?: return@run null
            // Subpasta apagada ou movida: abre a pasta raiz e passa a usá-la como inicial.
            // (Só no local; no Drive não há como saber sem listar, e o erro normal já aparece.)
            var sub = ids.size > 1 && ids.size == names.size
            if (sub && (r.isFile || r.source == Roots.LOCAL) && Roots.source(app, r).folderMtime(ids.last()) <= 0L) {
                p.setStart(r.id)
                sub = false
            }
            r to sub
        }) { res ->
            val (r, sub) = res.getOrNull() ?: return@run
            if (isFinishing || supportFragmentManager.isStateSaved) return@run
            val f = if (sub) {
                BrowserFragment.newInstance(r.id, ArrayList(ids), ArrayList(names))
            } else {
                BrowserFragment.newInstance(r.id)
            }
            push(f)
        }
    }

    /** "Readicionar" uma pasta indisponível: SAF de novo, ou a permissão para as lidas pelo caminho. */
    fun readd(r: app.index.files.data.Root) {
        if (!r.isFile && !r.isRecent) {
            pickTree(r.id, r.uri)
            return
        }
        if (Roots.hasAllFiles()) {
            // Permissão ok, mas a pasta sumiu (apagada, cartão removido).
            Toast.makeText(this, R.string.folder_unavailable, Toast.LENGTH_SHORT).show()
            return
        }
        askAllFiles { granted ->
            if (!granted) return@askAllFiles
            if (!r.isRecent) Indexer.start(applicationContext, r.id)
            if (!isFinishing && !supportFragmentManager.isStateSaved) notifyRootsChanged()
        }
    }

    fun addDriveFolder() {
        if (DriveAuth.connected(this)) {
            push(DrivePickerFragment())
        } else {
            pickDriveAfterConnect = true
            connectDrive()
        }
    }

    /** Pede a autorização (escopo drive). Só mostra tela se ainda não houver consentimento. */
    fun connectDrive() {
        val email = Prefs.get(this).driveEmail
        DriveAuth.client(this).authorize(DriveAuth.request(email))
            .addOnSuccessListener(this) { r ->
                val pi = r.pendingIntent
                if (r.hasResolution() && pi != null) {
                    AppLock.beginOwnFlow()
                    authLauncher.launch(IntentSenderRequest.Builder(pi.intentSender).build())
                } else {
                    finishConnect(r)
                }
            }
            .addOnFailureListener(this) { e ->
                Log.w("Drive", "authorize", e)
                pickDriveAfterConnect = false
                recentAfterConnect = false
                Toast.makeText(this, R.string.drive_connect_failed, Toast.LENGTH_LONG).show()
            }
    }

    private fun finishConnect(r: AuthorizationResult) {
        val app = applicationContext
        Bg.run({ DriveAuth.onAuthorized(app, r) }) { res ->
            res.onSuccess {
                val email = Prefs.get(app).driveEmail
                if (email != null) Toast.makeText(app, getString(R.string.drive_connected_as, email), Toast.LENGTH_SHORT).show()
                ThumbnailManager.get(app).retryFailed()
                Indexer.start(app)
                if (!isFinishing && !supportFragmentManager.isStateSaved) {
                    notifyRootsChanged()
                    if (pickDriveAfterConnect) push(DrivePickerFragment())
                    else if (recentAfterConnect) openRecent(Roots.DRIVE_RECENT_ID)
                }
            }.onFailure {
                Log.w("Drive", "onAuthorized", it)
                Toast.makeText(app, R.string.drive_connect_failed, Toast.LENGTH_LONG).show()
            }
            pickDriveAfterConnect = false
            recentAfterConnect = false
        }
    }

    /** Diálogo "conecte a conta Google" (pasta do Drive sem autorização). */
    fun askConnect() {
        AlertDialog.Builder(this)
            .setTitle(R.string.drive_connect)
            .setMessage(R.string.drive_connect_msg)
            .setPositiveButton(R.string.drive_connect) { _, _ -> connectDrive() }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** Abre o seletor de pastas do sistema (SAF). [replaceId] >= 0 = readicionar. */
    fun pickTree(replaceId: Long = -1L, initial: Uri? = null) {
        replaceRootId = replaceId
        val start = initial?.let {
            runCatching {
                DocumentsContract.buildDocumentUriUsingTree(it, DocumentsContract.getTreeDocumentId(it))
            }.getOrNull()
        }
        AppLock.beginOwnFlow()
        treePicker.launch(start)
    }

    private fun onTreePicked(tree: Uri) {
        val app = applicationContext
        val replace = replaceRootId
        replaceRootId = -1L
        Bg.run({
            if (replace >= 0) {
                Roots.replaceUri(app, replace, tree)
                replace
            } else {
                Roots.addLocal(app, tree)
            }
        }) { res ->
            res.onSuccess { id ->
                if (id == -1L) {
                    Toast.makeText(app, R.string.folder_already_added, Toast.LENGTH_SHORT).show()
                } else {
                    Indexer.start(app, id)
                }
            }.onFailure {
                Toast.makeText(app, R.string.folder_add_failed, Toast.LENGTH_SHORT).show()
            }
            if (!isFinishing && !supportFragmentManager.isStateSaved) {
                notifyRootsChanged()
            }
        }
    }

    companion object {
        const val ROOTS_CHANGED = "roots_changed"
        const val VAULT_STACK = "vault"
        private const val S_REPLACE = "replace_root"
        private const val TWO_PANE_DP = 840
    }
}
