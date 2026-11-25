package app.index.files.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.os.Parcelable
import android.os.SystemClock
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.widget.PopupMenu
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.os.bundleOf
import androidx.core.view.MenuProvider
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ListUpdateCallback
import androidx.recyclerview.widget.RecyclerView
import app.index.files.Bg
import app.index.files.R
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import app.index.files.data.AppDb
import app.index.files.data.FileItem
import app.index.files.data.FileOps
import app.index.files.data.drive.DriveChanges
import app.index.files.data.FileSource
import app.index.files.data.ListingCache
import app.index.files.data.Loc
import app.index.files.data.Prefs
import app.index.files.data.RandomPick
import app.index.files.data.RecentSource
import app.index.files.data.Root
import app.index.files.data.Roots
import app.index.files.data.SortSpec
import app.index.files.data.comparatorFor
import app.index.files.data.drive.AuthRequired
import app.index.files.data.VaultIndex
import app.index.files.data.VaultSource
import app.index.files.security.AppLock
import app.index.files.security.Vault
import app.index.files.security.VaultEntry
import app.index.files.thumbs.Preload
import app.index.files.thumbs.ThumbnailManager

/**
 * Navegação dentro de uma pasta raiz. A pilha de subpastas fica no próprio
 * fragment (ids + nomes), o que deixa o breadcrumb e o "voltar" triviais.
 */
class BrowserFragment : Fragment(R.layout.fragment_browser), MainActivity.KeyTarget {

    private var root: Root? = null
    private lateinit var source: FileSource
    private lateinit var prefs: Prefs

    private val ids = ArrayList<String>()
    private val names = ArrayList<String>()
    /** Posição de rolagem de cada nível, salva ao entrar numa subpasta. */
    private val scrollStates = ArrayList<Parcelable?>()

    /** Posição da lista guardada quando a tela some (visualizador aberto, processo recriado). */
    private var pendingScroll: Parcelable? = null

    private var spec: SortSpec? = null
    private var customSpec = false
    private var shownFolder: String? = null
    private var shownSpec: SortSpec? = null
    private var gen = 0
    private var sortGen = 0

    private lateinit var adapter: FileAdapter
    private lateinit var lm: GridLayoutManager
    private lateinit var list: RecyclerView
    private lateinit var progress: View
    private lateinit var empty: View
    private lateinit var emptyText: TextView
    private lateinit var emptyAction: Button
    private lateinit var crumbs: LinearLayout
    private lateinit var crumbsScroll: HorizontalScrollView

    private val showProgress = Runnable { progress.visibility = View.VISIBLE }

    private val backCb = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = goTo(ids.size - 2)
    }

    // ---- Edição (fase 7) ----
    /** Ids marcados no modo de seleção (toque longo). */
    private val selected = LinkedHashSet<String>()
    private var selecting = false
    private lateinit var pull: PullRefresh
    /** O app saiu da tela (onStop): ao voltar, a pasta aberta é consultada de novo. */
    private var stopped = false
    /** Consulta lenta com a lista do cache na tela: mostra o círculo "atualizando". */
    private val showRefreshing = Runnable { if (::pull.isInitialized) pull.setRefreshing(true) }
    private lateinit var dragSelect: DragSelect
    private lateinit var pasteBar: View
    private lateinit var pasteText: TextView

    /** Registrado depois do [backCb]: com seleção ativa, "voltar" só sai da seleção. */
    private val selectBack = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = endSelection()
    }

    private val uploadPicker = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) upload(uris)
    }

    /** Volta do digitalizador do Google com as páginas (JPEG) e o PDF. */
    private val scanLauncher = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { res ->
        val r = Scan.result(res.resultCode, res.data) ?: return@registerForActivityResult
        onScanned(r)
    }

    /** Dentro de uma pasta protegida desbloqueada (índice e miniaturas cifrados). */
    private var vault: VaultEntry? = null
    private var vaultId: String? = null

    // ---- Seleção aleatória (fase 9) ----
    private var randomActive = false
    /** Origem do sorteio atual (para "Sortear de novo"). */
    private var randomPool: List<FileItem> = emptyList()
    /** Lista completa da pasta quando o sorteio começou (base para um novo sorteio). */
    private var randomFull: List<FileItem> = emptyList()
    private var randomRestore: Parcelable? = null
    /** Itens sorteados na tela (para reexibir ao voltar de outra tela). */
    private var randomShown: List<FileItem> = emptyList()
    private var randomGen = 0
    private lateinit var randomBar: View
    private lateinit var randomText: TextView

    // ---- Pré-carga de miniaturas (fase 10) ----
    private lateinit var preloadBar: View
    /** Lista na tela, como a pré-carga a identifica. */
    private var currentKey: String? = null
    /** Cada sorteio novo é outra lista. */
    private var randomDraw = 0
    private var preloadWasRunning = false
    private val preloadListener = Preload.Listener { onPreloadChanged() }

    /** Com a seleção aleatória na tela, "voltar" volta à lista completa. */
    private val randomBack = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = exitRandom()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requireActivity().onBackPressedDispatcher.addCallback(this, backCb)
        requireActivity().onBackPressedDispatcher.addCallback(this, randomBack)
        requireActivity().onBackPressedDispatcher.addCallback(this, selectBack)
        vaultId = arguments?.getString(ARG_VAULT)
        vaultId?.let { Vault.retain(it) }
        val s = savedInstanceState ?: arguments
        s?.getStringArrayList(S_IDS)?.let { ids.addAll(it) }
        s?.getStringArrayList(S_NAMES)?.let { names.addAll(it) }
        // Processo recriado (o app ficou em segundo plano): volta para a mesma posição da lista.
        savedInstanceState?.let { b ->
            @Suppress("DEPRECATION")
            pendingScroll = b.getParcelable(S_SCROLL)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putStringArrayList(S_IDS, ids)
        outState.putStringArrayList(S_NAMES, names)
        val state = if (::lm.isInitialized && view != null && !randomActive) lm.onSaveInstanceState() else pendingScroll
        state?.let { outState.putParcelable(S_SCROLL, it) }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val ctx = requireContext()
        prefs = Prefs.get(ctx)
        val vid = vaultId
        if (vid != null) {
            vault = Vault.entry(ctx, vid)?.takeIf { Vault.isOpen(vid) }
            if (vault == null) {
                // Trancada (tempo esgotado, processo recriado): não mostra nada.
                parentFragmentManager.popBackStack()
                return
            }
        }
        val r = Roots.get(ctx, vault?.rootId ?: requireArguments().getLong(ARG_ROOT))
        if (r == null) {
            parentFragmentManager.popBackStack()
            return
        }
        bindRoot(r)

        list = view.findViewById(R.id.list)
        progress = view.findViewById(R.id.progress)
        empty = view.findViewById(R.id.empty)
        emptyText = view.findViewById(R.id.empty_text)
        emptyAction = view.findViewById(R.id.empty_action)
        crumbs = view.findViewById(R.id.crumbs)
        crumbsScroll = view.findViewById(R.id.crumbs_scroll)

        adapter = FileAdapter(ctx, ThumbnailManager.get(ctx), ::onItemClick)
        adapter.sourceFor = { source }
        // Na seleção aleatória também se seleciona (só "Abrir com..." e "Selecionar tudo":
        // os itens podem vir de subpastas, então sem edição).
        adapter.onLongClick = { item, _ ->
            if (selecting) toggle(item) else startSelection(item)
            // Sem soltar o dedo, arrastar seleciona uma área (DragSelect).
            dragSelect.arm(adapter.items.indexOfFirst { it.id == item.id })
        }
        adapter.onContextClick = ::contextMenu
        randomBar = view.findViewById(R.id.random_bar)
        randomText = view.findViewById(R.id.random_text)
        view.findViewById<View>(R.id.random_again).setOnClickListener { drawAgain() }
        view.findViewById<View>(R.id.random_full).setOnClickListener { exitRandom() }
        preloadBar = view.findViewById(R.id.preload_bar)
        adapter.isSelected = { selecting && it.id in selected }
        pasteBar = view.findViewById(R.id.paste_bar)
        pasteText = view.findViewById(R.id.paste_text)
        view.findViewById<View>(R.id.paste_cancel).setOnClickListener {
            val main = activity as MainActivity
            if (main.saving) {
                main.shareCancelled()
                updatePasteBar()
                return@setOnClickListener
            }
            Clip.clear()
            updatePasteBar()
        }
        view.findViewById<View>(R.id.paste_here).setOnClickListener {
            if ((activity as MainActivity).saving) saveShared() else paste()
        }
        adapter.grid = prefs.viewGrid
        lm = GridLayoutManager(ctx, 1)
        list.layoutManager = lm
        zoom = GridZoom(list, lm, adapter, prefs) { requireActivity().invalidateOptionsMenu() }
        lm.spanCount = zoom.span()
        list.setHasFixedSize(true)
        list.adapter = adapter
        list.recycledViewPool.setMaxRecycledViews(FileAdapter.TYPE_GRID, 48)
        list.recycledViewPool.setMaxRecycledViews(FileAdapter.TYPE_LIST, 24)
        dragSelect = DragSelect(list, object : DragSelect.Callback {
            override fun isSelectedAt(pos: Int) = adapter.items.getOrNull(pos)?.id?.let { it in selected } == true
            override fun setSelectedAt(pos: Int, on: Boolean) {
                val id = adapter.items.getOrNull(pos)?.id ?: return
                if (on) selected.add(id) else selected.remove(id)
                adapter.refreshSelection(pos)
            }
            override fun onDragChanged() {
                if (selecting && view != null) selectionChanged()
            }
        })
        // Depois do DragSelect e da pinça: eles têm prioridade quando ativos.
        pull = PullRefresh(
            list, view.findViewById(R.id.pull), view.findViewById(R.id.pull_icon), view.findViewById(R.id.pull_spinner),
            enabled = { !randomActive && root != null },
            onRefresh = { revalidate(force = true) },
        )
        ScrollLabels.setup(list, view.findViewById(R.id.fast_scroller), adapter) {
            (spec ?: defaultSpec()).by
        }
        list.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(rv: RecyclerView, newState: Int) {
                if (newState == RecyclerView.SCROLL_STATE_IDLE) requestWindow()
            }
        })
        Preload.addListener(preloadListener)
        preloadWasRunning = Preload.running

        requireActivity().addMenuProvider(menu, viewLifecycleOwner, Lifecycle.State.RESUMED)
        parentFragmentManager.setFragmentResultListener(MainActivity.ROOTS_CHANGED, viewLifecycleOwner) { _, _ ->
            reloadRoot()
        }

        shownFolder = null
        renderCrumbs()
        if (randomActive && randomShown.isNotEmpty()) {
            // Voltou de outra tela (pesquisa, configurações): a seleção sorteada continua.
            showRandom(randomShown)
        } else {
            // Voltou do visualizador (ou de outra tela): continua de onde a lista estava.
            val restore = pendingScroll
            pendingScroll = null
            load(restore)
        }
        // Pasta protegida: índice completo dela (para a busca), em segundo plano.
        vault?.let { if (savedInstanceState == null) VaultIndex.walk(ctx, it, source, r.isDrive) }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Último navegador desta pasta protegida fechado: ela volta a se ocultar.
        val id = vaultId ?: return
        if (Vault.release(id)) {
            VaultIndex.drop(id)
            ThumbnailManager.get(requireContext()).dropDecrypted()
            (activity as? MainActivity)?.applySecureFlag()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        gen++
        Preload.removeListener(preloadListener)
        // A lista saiu da tela: a RAM da pré-carga dela é solta depois do prazo.
        currentKey?.let(Preload::leave)
        currentKey = null
        if (::list.isInitialized) {
            // A tela da lista some ao abrir uma imagem ou outra tela: guarda onde estava.
            if (!randomActive && shownFolder != null) pendingScroll = lm.onSaveInstanceState()
            list.removeCallbacks(showProgress)
            list.removeCallbacks(showRefreshing)
        }
    }

    override fun onStop() {
        super.onStop()
        stopped = true
    }

    override fun onStart() {
        super.onStart()
        // Voltou de outro app (baixou um arquivo, tirou uma foto): consulta a pasta aberta de novo.
        if (stopped && view != null && !randomActive && shownFolder != null && shownFolder == ids.lastOrNull()) revalidate()
        stopped = false
    }

    /**
     * Consulta a pasta exibida de novo (puxar para atualizar ou volta ao app). Só aplica o que
     * mudou (DiffUtil), sem recarregar a tela; descarta consultas anteriores ainda em curso.
     */
    /** Muda o filtro da pasta Recentes (tipo e/ou período) e consulta de novo. */
    private fun setRecentFilter(type: Int = prefs.recentType, days: Int = prefs.recentDays) {
        if (type == prefs.recentType && days == prefs.recentDays) return
        prefs.recentType = type
        prefs.recentDays = days
        (activity as? MainActivity)?.refreshNavPane() // painel lateral: "Fotos · últimos 7 dias"
        requireActivity().invalidateOptionsMenu()
        revalidate()
    }

    /** [force]: puxar para atualizar (o Drive lista de novo mesmo com a cópia salva em dia). */
    private fun revalidate(force: Boolean = false) {
        val r = root ?: return
        val folder = ids.lastOrNull() ?: return
        if (randomActive || shownFolder != folder) {
            pull.setRefreshing(false)
            return
        }
        val g = ++gen
        refresh(g, r, folder, spec ?: defaultSpec(), prefs.showHidden, null, force)
    }

    override fun onResume() {
        super.onResume()
        updateTitle()
        if (::pasteBar.isInitialized) updatePasteBar()
    }

    private fun updateTitle() {
        val r = root ?: return
        requireActivity().title = if (selecting) {
            resources.getQuantityString(R.plurals.selected_count, selected.size, selected.size)
        } else {
            vault?.name ?: r.alias
        }
    }

    private fun bindRoot(r: Root) {
        root = r
        val base = Roots.source(requireContext(), r)
        val v = vault
        source = if (v != null) VaultSource(base, v) else base
        // Na pasta protegida o caminho começa nela (não dá para subir além).
        val topId = v?.folderId ?: r.docId
        val topName = v?.name ?: r.alias
        if (ids.isEmpty() || ids[0] != topId) {
            ids.clear(); names.clear()
            ids.add(topId); names.add(topName)
            scrollStates.clear()
        } else {
            names[0] = topName
        }
        // Mesma pilha de pastas (a tela foi recriada): mantém as posições dos níveis de cima.
        if (scrollStates.size != ids.size) {
            scrollStates.clear()
            repeat(ids.size) { scrollStates.add(null) }
        }
    }

    /** Depois de "Readicionar": a Uri da raiz mudou. */
    private fun reloadRoot() {
        val r = Roots.get(requireContext(), root?.id ?: return) ?: return
        endSelection()
        bindRoot(r)
        shownFolder = null
        renderCrumbs()
        load(null)
    }

    private lateinit var zoom: GridZoom

    private class Cached(val items: List<FileItem>?, val spec: SortSpec, val custom: Boolean)

    private class Fresh(val items: List<FileItem>, val all: List<FileItem>, val diff: DiffUtil.DiffResult?, val changed: Boolean)

    /** A cópia salva do Drive está em dia (registro de mudanças): nada a consultar. */
    private object UpToDate

    private fun visible(all: List<FileItem>, showHidden: Boolean, s: SortSpec): List<FileItem> {
        val ctx = context?.applicationContext
        val rootId = root?.id ?: -1L
        val src = all.filter {
            (showHidden || !it.name.startsWith('.')) &&
                // Pastas protegidas: nunca aparecem na listagem (nem dentro de outra protegida).
                !(it.isDir && ctx != null && Vault.isHidden(ctx, rootId, it.id))
        }
        return src.sortedWith(comparatorFor(s))
    }

    /**
     * Carrega em duas etapas: (1) cache do SQLite, exibido na hora; (2) consulta
     * real ao SAF em segundo plano, aplicada com DiffUtil e gravada no cache.
     */
    private fun load(restore: Parcelable?) {
        val r = root ?: return
        clearRandom()
        keyChanged()
        val g = ++gen
        sortGen++
        val folder = ids.last()
        val key = r.folderKey(folder)
        val showHidden = prefs.showHidden
        val def = defaultSpec()
        val ctx = requireContext().applicationContext
        val db = AppDb.get(ctx)
        val cache = ListingCache(ctx)
        val t0 = SystemClock.elapsedRealtime()

        empty.visibility = View.GONE
        list.removeCallbacks(showProgress)
        // Outra pasta (ou recarga completa): a consulta anterior não conta mais.
        list.removeCallbacks(showRefreshing)
        pull.setRefreshing(false)

        val v = vault
        Bg.run({
            val folderSpec = db.folderSort(key)
            val s = folderSpec ?: def
            val cached = if (v != null) VaultIndex.read(ctx, v, folder) else cache.read(r.id, folder)
            Cached(cached?.let { visible(it, showHidden, s) }, s, folderSpec != null)
        }) { res ->
            if (g != gen || view == null) return@run
            val c = res.getOrNull()
            val s = c?.spec ?: def
            spec = s
            customSpec = c?.custom ?: false
            shownSpec = s
            var restorePending = restore
            if (randomActive) {
                // Sorteio aberto antes do cache chegar: só revalida em segundo plano.
                refresh(g, r, folder, s, showHidden, null)
                return@run
            }
            if (c?.items != null) {
                shownFolder = folder
                adapter.replace(c.items)
                if (restore != null) lm.onRestoreInstanceState(restore) else lm.scrollToPosition(0)
                restorePending = null
                showEmpty(if (c.items.isEmpty()) R.string.folder_empty else 0)
                applyPendingCursor()
                Log.i(TAG, "cache: ${c.items.size} itens em ${SystemClock.elapsedRealtime() - t0} ms")
            } else {
                shownFolder = null
                adapter.replace(emptyList())
                list.postDelayed(showProgress, 150)
            }
            requireActivity().invalidateOptionsMenu()
            refresh(g, r, folder, s, showHidden, restorePending)
        }
    }

    private fun refresh(
        g: Int, r: Root, folder: String, s: SortSpec, showHidden: Boolean, restore: Parcelable?, force: Boolean = false,
    ) {
        val old = if (shownFolder == folder) adapter.items else null
        val src = source
        val app = requireContext().applicationContext
        val cache = ListingCache(app)
        // Drive com a cópia salva na tela: só lista se o registro de mudanças tocou a pasta.
        val checkChanges = !force && old != null && vault == null && r.isDrive && !r.isDriveRecent
        val parentPath = names.drop(1).joinToString("/")
        val t0 = SystemClock.elapsedRealtime()
        // Lista antiga (cache) na tela: se a consulta passar de 400 ms, avisa que ainda pode mudar.
        list.removeCallbacks(showRefreshing)
        if (old != null && !pull.isRefreshing) list.postDelayed(showRefreshing, 400)
        Bg.run({
            if (checkChanges && !DriveChanges.needsList(app, r.id, folder)) return@run UpToDate
            val all = src.listChildren(folder)
            val items = visible(all, showHidden, s)
            var changed = true
            val diff = old?.let {
                val d = DiffUtil.calculateDiff(FileAdapter.diffCallback(it, items), false)
                var ops = 0
                d.dispatchUpdatesTo(object : ListUpdateCallback {
                    override fun onInserted(position: Int, count: Int) { ops++ }
                    override fun onRemoved(position: Int, count: Int) { ops++ }
                    override fun onMoved(fromPosition: Int, toPosition: Int) { ops++ }
                    override fun onChanged(position: Int, count: Int, payload: Any?) { ops++ }
                })
                changed = ops > 0
                d
            }
            Fresh(items, all, diff, changed)
        }) { res ->
            if (g != gen || view == null) return@run
            list.removeCallbacks(showProgress)
            list.removeCallbacks(showRefreshing)
            pull.setRefreshing(false)
            progress.visibility = View.GONE
            res.onSuccess { out ->
                val f = out as? Fresh ?: run {
                    Log.i(TAG, "Drive: cópia salva em dia, sem consulta (${SystemClock.elapsedRealtime() - t0} ms)")
                    return@onSuccess
                }
                Log.i(TAG, "listagem: ${f.items.size} itens em ${SystemClock.elapsedRealtime() - t0} ms, mudou=${f.changed}")
                if (f.changed || shownFolder != folder) {
                    // Gravação fora da main e depois da exibição (pasta protegida: índice cifrado).
                    val v = vault
                    val app = requireContext().applicationContext
                    Bg.run({
                        if (v != null) VaultIndex.write(app, v, folder, parentPath, f.all)
                        else cache.write(r.id, folder, parentPath, f.all)
                    }) {}
                }
                // Sorteio aberto enquanto a consulta rodava: a seleção sorteada continua na tela.
                if (randomActive) return@onSuccess
                if (shownSpec != s) {
                    // A ordenação mudou enquanto a consulta rodava: reordena o resultado novo.
                    shownFolder = folder
                    resort(f.items)
                } else if (f.diff != null && shownFolder == folder) {
                    if (f.changed) adapter.apply(f.items, f.diff)
                } else {
                    shownFolder = folder
                    adapter.replace(f.items)
                    if (restore != null) lm.onRestoreInstanceState(restore) else lm.scrollToPosition(0)
                }
                showEmpty(if (f.items.isEmpty()) R.string.folder_empty else 0)
                applyPendingCursor()
            }.onFailure { e -> onListFailed(e, folder) }
            requireActivity().invalidateOptionsMenu()
        }
    }

    /**
     * Falha na revalidação. Drive sem rede/conta: se o cache já está na tela,
     * continua navegável (só avisa); senão mostra o motivo com uma ação.
     */
    private fun onListFailed(e: Throwable, folder: String) {
        Log.w(TAG, "falha ao listar", e)
        val r = root ?: return
        val main = activity as MainActivity
        val cached = shownFolder == folder
        if (r.isDrive) {
            val auth = e is AuthRequired
            if (cached) {
                if (auth) main.askConnect()
                else if (SystemClock.elapsedRealtime() - lastOfflineToast > OFFLINE_TOAST_MS) {
                    // Navegando sem rede: avisa uma vez, não a cada pasta aberta.
                    lastOfflineToast = SystemClock.elapsedRealtime()
                    Toast.makeText(requireContext(), R.string.drive_offline_cached, Toast.LENGTH_SHORT).show()
                }
                return
            }
            adapter.replace(emptyList())
            if (auth) showEmpty(R.string.drive_auth_needed, R.string.drive_connect) { main.connectDrive() }
            else showEmpty(R.string.drive_offline, R.string.retry) { load(null) }
            return
        }
        shownFolder = null
        adapter.replace(emptyList())
        showEmpty(R.string.folder_unavailable, R.string.readd) { main.readd(r) }
    }

    private fun showEmpty(text: Int, action: Int = 0, onAction: (() -> Unit)? = null) {
        if (text == 0) {
            empty.visibility = View.GONE
            return
        }
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

    /** Recentes: mais novos primeiro (a ordenação global vale para as pastas de verdade). */
    private fun defaultSpec(): SortSpec =
        if (root?.isRecent == true) SortSpec(SortSpec.DATE, asc = false, dirsFirst = false) else prefs.defaultSort()

    /** Esta pasta (raiz ou subpasta) passa a abrir junto com o app. */
    private fun setAsStart() {
        val r = root ?: return
        // Na raiz basta o id; numa subpasta guarda o caminho desde a raiz (como a busca faz).
        if (ids.size <= 1) prefs.setStart(r.id) else prefs.setStart(r.id, ids, names)
        Toast.makeText(requireContext(), getString(R.string.start_folder_set, names.lastOrNull() ?: r.alias), Toast.LENGTH_SHORT).show()
    }

    /** Muda a ordenação desta pasta (null = voltar ao padrão global) sem reconsultar o SAF. */
    private fun setSpec(s: SortSpec?) {
        val r = root ?: return
        val key = r.folderKey(ids.last())
        val effective = s ?: defaultSpec()
        val db = AppDb.get(requireContext())
        spec = effective
        customSpec = s != null
        shownSpec = effective
        Bg.run({ db.setFolderSort(key, s) }) {}
        resort(adapter.items)
        requireActivity().invalidateOptionsMenu()
    }

    /** Reordena em memória com a ordenação atual. Não invalida a revalidação em curso. */
    private fun resort(items: List<FileItem>) {
        val s = shownSpec ?: return
        val sg = ++sortGen
        Bg.run({ items.sortedWith(comparatorFor(s)) }) { res ->
            if (sg != sortGen || view == null) return@run
            res.onSuccess {
                adapter.replace(it)
                if (randomActive) randomShown = it
                lm.scrollToPosition(0)
            }
        }
    }

    private fun onItemClick(item: FileItem) {
        when {
            selecting -> toggle(item)
            item.isDir -> enter(item)
            else -> open(item)
        }
    }

    private fun enter(item: FileItem) {
        endSelection()
        // Outro caminho: o Alt+→ não volta mais para onde se tinha saído.
        if (forwardIds.lastOrNull() == item.id) {
            forwardIds.removeAt(forwardIds.size - 1)
            forwardNames.removeAt(forwardNames.size - 1)
        } else {
            forwardIds.clear()
            forwardNames.clear()
        }
        cursor = -1
        scrollStates[ids.size - 1] = lm.onSaveInstanceState()
        ids.add(item.id)
        names.add(item.name)
        scrollStates.add(null)
        renderCrumbs()
        load(null)
    }

    /** Volta para o nível [level] da pilha (breadcrumb ou botão voltar). */
    private fun goTo(level: Int) {
        if (level < 0 || level >= ids.size - 1) return
        endSelection()
        val state = scrollStates[level]
        val left = ids.getOrNull(level + 1)
        while (ids.size > level + 1) {
            forwardIds.add(ids[ids.size - 1])
            forwardNames.add(names[names.size - 1])
            ids.removeAt(ids.size - 1)
            names.removeAt(names.size - 1)
            scrollStates.removeAt(scrollStates.size - 1)
        }
        scrollStates[level] = null
        // Na volta, o foco do teclado fica na pasta de onde se saiu.
        cursor = -1
        pendingCursorId = left
        renderCrumbs()
        load(state)
    }

    private fun open(item: FileItem) {
        if (item.inViewer && prefs.internalViewer) {
            // Só as fotos da lista na tela (na ordem dela); vídeos vão para o player.
            val photos = adapter.items.filter { it.inViewer }
            val start = photos.indexOfFirst { it.id == item.id }
            if (start >= 0) {
                app.index.files.ui.viewer.ViewerFragment.open(requireActivity(), photos, start, { source }) onClose@{ last ->
                    if (view == null) return@onClose
                    val p = adapter.items.indexOfFirst { it.id == last.id }
                    if (p >= 0 && (p < lm.findFirstCompletelyVisibleItemPosition() || p > lm.findLastCompletelyVisibleItemPosition())) {
                        lm.scrollToPositionWithOffset(p, list.height / 3)
                    }
                }
                return
            }
        }
        // Vídeos: player do app (legendas com o mesmo nome vêm da pasta).
        if (item.inPlayer && prefs.internalPlayer &&
            app.index.files.ui.video.VideoFragment.open(requireActivity(), item, source, adapter.items)
        ) return
        // Documentos (PDF, Office, LibreOffice, textos): leitor do app.
        if (prefs.internalDocs && app.index.files.ui.doc.DocViewerFragment.open(requireActivity(), item, source)) return
        FileOpener.open(this, source, item)
    }

    private fun renderCrumbs() {
        backCb.isEnabled = ids.size > 1
        val ctx = requireContext()
        crumbs.removeAllViews()
        val tv = TypedValue()
        ctx.theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)
        val pad = (8 * resources.displayMetrics.density).toInt()
        for (i in names.indices) {
            if (i > 0) {
                crumbs.addView(
                    TextView(ctx).apply {
                        text = "›"
                        setTextColor(ctx.getColor(R.color.text_secondary))
                        textSize = 16f
                    },
                )
            }
            val last = i == names.size - 1
            crumbs.addView(
                TextView(ctx).apply {
                    text = names[i]
                    textSize = 14f
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(pad, 0, pad, 0)
                    setSingleLine()
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.MATCH_PARENT,
                    )
                    if (last) {
                        setTextColor(ctx.getColor(R.color.text))
                        setTypeface(typeface, Typeface.BOLD)
                    } else {
                        setTextColor(ctx.getColor(R.color.accent))
                        setBackgroundResource(tv.resourceId)
                        setOnClickListener { goTo(i) }
                    }
                },
            )
        }
        crumbsScroll.post { crumbsScroll.fullScroll(View.FOCUS_RIGHT) }
    }

    private val menu = object : MenuProvider {
        override fun onCreateMenu(menu: Menu, inflater: MenuInflater) =
            inflater.inflate(if (selecting) R.menu.selection else R.menu.browser, menu)

        override fun onPrepareMenu(menu: Menu) {
            if (selecting) {
                val any = selected.isNotEmpty()
                val edit = !randomActive
                val sel = selectedItems()
                menu.findItem(R.id.sel_open_with)?.isVisible = sel.singleOrNull()?.isDir == false
                menu.findItem(R.id.sel_share)?.isVisible = sel.isNotEmpty() && sel.none { it.isDir }
                menu.findItem(R.id.sel_rename)?.isVisible = edit && selected.size == 1
                menu.findItem(R.id.sel_copy)?.isVisible = edit && any
                menu.findItem(R.id.sel_move)?.isVisible = edit && any
                menu.findItem(R.id.sel_delete)?.isVisible = edit && any
                // Proteger: uma pasta só, fora de pasta protegida.
                menu.findItem(R.id.sel_protect)?.isVisible =
                    edit && vault == null && root?.isRecent != true && selected.size == 1 && selectedItems().singleOrNull()?.isDir == true
                return
            }
            RandomUi.bindButton(menu.findItem(R.id.action_random), { startRandom() }) {
                RandomUi.panel(requireContext(), subfolders = true, positive = R.string.random_draw) { startRandom() }
            }
            // Na seleção sorteada não há edição (os itens podem vir de subpastas).
            // Recentes é uma lista virtual: nada entra nela, e ela não está no índice (busca, sorteio, pré-carga).
            val recent = root?.isRecent == true
            for (id in intArrayOf(R.id.action_add_menu, R.id.action_new_folder, R.id.action_upload, R.id.action_scan)) {
                menu.findItem(id)?.isVisible = !randomActive && !recent
            }
            for (id in intArrayOf(R.id.action_search, R.id.action_random, R.id.action_preload)) {
                if (recent) menu.findItem(id)?.isVisible = false
            }
            menu.findItem(R.id.action_recent_filter)?.apply {
                isVisible = recent
                // Linha entre "tipo de arquivo" e "período" (um grupo cada).
                subMenu?.let { androidx.core.view.MenuCompat.setGroupDividerEnabled(it, true) }
            }
            if (recent) {
                menu.findItem(
                    when (prefs.recentType) {
                        RecentSource.IMAGES -> R.id.recent_images
                        RecentSource.VIDEOS -> R.id.recent_videos
                        RecentSource.AUDIO -> R.id.recent_audio
                        RecentSource.DOCS -> R.id.recent_docs
                        RecentSource.APKS -> R.id.recent_apks
                        else -> R.id.recent_all
                    },
                )?.isChecked = true
                menu.findItem(
                    when (prefs.recentDays) {
                        7 -> R.id.recent_7
                        90 -> R.id.recent_90
                        else -> R.id.recent_30
                    },
                )?.isChecked = true
            }
            menu.findItem(R.id.action_set_start)?.isVisible = vault == null && !randomActive
            val grid = adapter.grid
            menu.findItem(R.id.action_bigger).isVisible = true // da lista, volta à grade
            menu.findItem(R.id.action_smaller).isVisible = grid
            val s = spec ?: defaultSpec()
            menu.findItem(
                when (s.by) {
                    SortSpec.SIZE -> R.id.sort_size
                    SortSpec.TYPE -> R.id.sort_type
                    SortSpec.DATE -> R.id.sort_date
                    else -> R.id.sort_name
                },
            ).isChecked = true
            menu.findItem(R.id.sort_desc).isChecked = !s.asc
            menu.findItem(R.id.sort_dirs_first).isChecked = s.dirsFirst
            menu.findItem(R.id.sort_reset).isVisible = customSpec
        }

        override fun onMenuItemSelected(item: MenuItem): Boolean {
            val cur = spec ?: defaultSpec()
            when (item.itemId) {
                R.id.sel_open_with -> openWithSelected()
                R.id.sel_share -> shareSelected()
                R.id.sel_rename -> renameSelected()
                R.id.sel_copy -> clipSelected(move = false)
                R.id.sel_move -> clipSelected(move = true)
                R.id.sel_delete -> deleteSelected()
                R.id.sel_all -> selectAll()
                R.id.action_new_folder -> newFolder()
                R.id.action_upload -> withWrite { AppLock.beginOwnFlow(); uploadPicker.launch(arrayOf("*/*")) }
                R.id.action_scan -> startScan()
                R.id.action_select -> startSelection(null)
                R.id.action_preload -> startPreload()
                R.id.sel_protect -> protectSelected()
                R.id.action_search -> openSearch()
                R.id.action_set_start -> setAsStart()
                R.id.sort_name -> setSpec(cur.copy(by = SortSpec.NAME))
                R.id.sort_size -> setSpec(cur.copy(by = SortSpec.SIZE))
                R.id.sort_type -> setSpec(cur.copy(by = SortSpec.TYPE))
                R.id.sort_date -> setSpec(cur.copy(by = SortSpec.DATE))
                R.id.sort_desc -> setSpec(cur.copy(asc = !cur.asc))
                R.id.sort_dirs_first -> setSpec(cur.copy(dirsFirst = !cur.dirsFirst))
                R.id.sort_reset -> setSpec(null)
                R.id.recent_all -> setRecentFilter(type = RecentSource.ALL)
                R.id.recent_images -> setRecentFilter(type = RecentSource.IMAGES)
                R.id.recent_videos -> setRecentFilter(type = RecentSource.VIDEOS)
                R.id.recent_audio -> setRecentFilter(type = RecentSource.AUDIO)
                R.id.recent_docs -> setRecentFilter(type = RecentSource.DOCS)
                R.id.recent_apks -> setRecentFilter(type = RecentSource.APKS)
                R.id.recent_7 -> setRecentFilter(days = 7)
                R.id.recent_30 -> setRecentFilter(days = 30)
                R.id.recent_90 -> setRecentFilter(days = 90)
                R.id.action_bigger -> zoom.step(+1)
                R.id.action_smaller -> zoom.step(-1)
                else -> return false
            }
            return true
        }
    }

    /** Pesquisa com escopo na pasta atual (menu e Ctrl+F). */
    private fun openSearch() {
        val r = root ?: return
        val main = activity as MainActivity
        val path = names.drop(1).joinToString("/")
        val v = vault
        main.push(
            if (v != null) SearchFragment.newVault(v.id, path, names.last()) else SearchFragment.newInstance(r.id, path, names.last()),
        )
    }

    // ---- Teclado e mouse (fase 12) ----

    /**
     * Teclado como no Explorador do Windows. As setas andam por um cursor próprio (← → um item,
     * ↑ ↓ uma linha da grade), com Shift estendendo a seleção e Ctrl só movendo o foco; digitar
     * letras pula para o nome. Sem seleção, Delete, F2, Ctrl+C/X, Alt+Enter e Shift+F10 valem
     * para o item em foco.
     */
    override fun onKey(e: KeyEvent): Boolean {
        if (view == null) return false
        if (activity?.currentFocus is android.widget.EditText) return false
        val ctrl = e.isCtrlPressed
        val shift = e.isShiftPressed
        val alt = e.isAltPressed
        val edit = !randomActive && root?.isRecent != true
        val k = e.keyCode
        when {
            ctrl && shift && k == KeyEvent.KEYCODE_N -> if (edit) newFolder()
            ctrl && shift && k == KeyEvent.KEYCODE_C -> copyPaths()
            ctrl && shift && k in KeyEvent.KEYCODE_1..KeyEvent.KEYCODE_6 -> zoom.setMode(VIEW_MODES[k - KeyEvent.KEYCODE_1])
            ctrl && (k == KeyEvent.KEYCODE_F || k == KeyEvent.KEYCODE_E) -> openSearch()
            ctrl && k == KeyEvent.KEYCODE_A -> {
                if (!selecting) startSelection(null)
                selectAll()
            }
            ctrl && k == KeyEvent.KEYCODE_C -> if (!randomActive) onTargets { clipSelected(move = false) }
            ctrl && k == KeyEvent.KEYCODE_X -> if (!randomActive) onTargets { clipSelected(move = true) }
            ctrl && k == KeyEvent.KEYCODE_V -> {
                if (pasteBar.visibility == View.VISIBLE) view?.findViewById<View>(R.id.paste_here)?.performClick()
            }
            ctrl && k == KeyEvent.KEYCODE_SPACE -> cursorItem()?.let { item ->
                if (!selecting) startSelection(item) else toggle(item)
            }
            alt && k == KeyEvent.KEYCODE_DPAD_UP -> goTo(ids.size - 2)
            alt && k == KeyEvent.KEYCODE_DPAD_LEFT -> goTo(ids.size - 2)
            alt && k == KeyEvent.KEYCODE_DPAD_RIGHT -> forward()
            alt && k == KeyEvent.KEYCODE_ENTER -> targets().firstOrNull()?.let { showProperties(it) }
            k == KeyEvent.KEYCODE_FORWARD_DEL -> if (!randomActive) onTargets { deleteSelected() }
            k == KeyEvent.KEYCODE_F2 -> if (!randomActive) onTargets(single = true) { renameSelected() }
            k == KeyEvent.KEYCODE_F5 -> {
                pull.setRefreshing(true)
                revalidate(force = true)
            }
            (shift && k == KeyEvent.KEYCODE_F10) || k == KeyEvent.KEYCODE_MENU -> {
                val item = cursorItem() ?: return false
                val v = lm.findViewByPosition(cursor) ?: return false
                contextMenu(item, v)
            }
            !alt && (k == KeyEvent.KEYCODE_ENTER || k == KeyEvent.KEYCODE_NUMPAD_ENTER) -> {
                val item = cursorItem() ?: return false
                if (selecting) endSelection()
                if (item.isDir) enter(item) else open(item)
            }
            Keys.zoom(e, zoom) -> Unit
            moveKey(e) -> Unit
            !ctrl && !alt && typeAhead(e) -> Unit
            else -> return false
        }
        return true
    }

    // ---- Cursor do teclado ----

    /** Posição do item em foco no teclado (-1 = nenhum ainda). */
    private var cursor = -1

    /** Início da faixa do Shift+setas. */
    private var anchor = -1

    /** Pastas de onde se voltou com Alt+← (Alt+→ entra de novo). */
    private val forwardIds = ArrayList<String>()
    private val forwardNames = ArrayList<String>()

    /** Depois de subir: o cursor vai para esta pasta quando a lista chegar. */
    private var pendingCursorId: String? = null

    private var typed = StringBuilder()
    private var typedAt = 0L

    private fun cursorItem(): FileItem? {
        syncCursor()
        return adapter.items.getOrNull(cursor)
    }

    /** O foco do Android pode ter andado (Tab, mouse): o cursor segue o item focado. */
    private fun syncCursor() {
        val f = list.focusedChild ?: return
        val p = list.getChildAdapterPosition(f)
        if (p != RecyclerView.NO_POSITION) cursor = p
    }

    /** Itens das ações do teclado: a seleção ou, sem ela, o item em foco. */
    private fun targets(): List<FileItem> =
        if (selecting && selected.isNotEmpty()) selectedItems() else listOfNotNull(cursorItem())

    /** Roda uma ação de seleção sobre a seleção ou, sem ela, sobre o item em foco. */
    private fun onTargets(single: Boolean = false, action: () -> Unit) {
        if (!(selecting && selected.isNotEmpty())) {
            val item = cursorItem() ?: return
            startSelection(item)
        }
        if (single && selected.size != 1) return
        action()
    }

    private fun moveKey(e: KeyEvent): Boolean {
        val n = adapter.items.size
        if (n == 0) return false
        val span = if (adapter.grid) lm.spanCount.coerceAtLeast(1) else 1
        syncCursor()
        val had = cursor in 0 until n
        val cur = if (had) cursor else -1
        val first = lm.findFirstVisibleItemPosition().coerceAtLeast(0)
        val last = lm.findLastVisibleItemPosition().coerceAtLeast(first)
        val page = ((last - first + 1) / span).coerceAtLeast(1) * span
        val target = when (e.keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> if (span == 1) return false else if (had) cur - 1 else first
            KeyEvent.KEYCODE_DPAD_RIGHT -> if (span == 1) return false else if (had) cur + 1 else first
            KeyEvent.KEYCODE_DPAD_UP -> if (had) cur - span else first
            KeyEvent.KEYCODE_DPAD_DOWN -> if (had) cur + span else first
            KeyEvent.KEYCODE_MOVE_HOME -> 0
            KeyEvent.KEYCODE_MOVE_END -> n - 1
            KeyEvent.KEYCODE_PAGE_UP -> if (had) cur - page else first
            KeyEvent.KEYCODE_PAGE_DOWN -> if (had) cur + page else first
            else -> return false
        }
        // Na beirada: setas ficam no lugar; páginas e linhas param no primeiro/último.
        val to = when (e.keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> if (target !in 0 until n) cur.coerceIn(0, n - 1) else target
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> if (target !in 0 until n) cur.coerceIn(0, n - 1) else target
            else -> target.coerceIn(0, n - 1)
        }
        moveCursor(to, if (had) cur else to, e.isShiftPressed, e.isCtrlPressed)
        return true
    }

    /**
     * Leva o cursor a [to]. Shift: seleciona a faixa desde a âncora; Ctrl: só o foco;
     * sozinho com a seleção aberta: a seleção vira o item em foco.
     */
    private fun moveCursor(to: Int, from: Int, shift: Boolean, ctrl: Boolean) {
        val items = adapter.items
        cursor = to
        when {
            shift -> {
                if (anchor !in items.indices || !selecting) anchor = from
                if (!selecting) startSelection(null)
                selected.clear()
                val (a, b) = if (anchor <= to) anchor to to else to to anchor
                for (i in a..b) selected.add(items[i].id)
                adapter.refreshAllSelection()
                selectionChanged()
            }
            ctrl -> Unit
            else -> {
                anchor = to
                if (selecting) {
                    selected.clear()
                    selected.add(items[to].id)
                    adapter.refreshAllSelection()
                    selectionChanged()
                }
            }
        }
        focusAt(to)
    }

    /** Rola até [pos] (se preciso) e põe o foco do Android nele (o contorno de foco). */
    private fun focusAt(pos: Int) {
        val first = lm.findFirstCompletelyVisibleItemPosition()
        val last = lm.findLastCompletelyVisibleItemPosition()
        if (first == RecyclerView.NO_POSITION || pos < first || pos > last) lm.scrollToPosition(pos)
        lm.findViewByPosition(pos)?.takeIf { pos in first..last }?.requestFocus()
            ?: list.post { lm.findViewByPosition(pos)?.requestFocus() }
    }

    /** Letras pulam para o primeiro nome que começa com elas (repetir a mesma letra percorre). */
    private fun typeAhead(e: KeyEvent): Boolean {
        val ch = e.unicodeChar.takeIf { it != 0 }?.toChar() ?: return false
        if (ch.isWhitespace() && typed.isEmpty()) return false
        if (ch.isISOControl()) return false
        val now = SystemClock.elapsedRealtime()
        if (now - typedAt > 1000) typed.clear()
        typedAt = now
        typed.append(ch.lowercaseChar())
        val items = adapter.items
        if (items.isEmpty()) return true
        val q = typed.toString()
        val same = q.length > 1 && q.all { it == q[0] }
        val needle = if (same) q.substring(0, 1) else q
        syncCursor()
        // Mesma letra de novo: o próximo com ela; texto novo: a partir do item atual.
        val start = if (same || q.length == 1) cursor + 1 else cursor.coerceAtLeast(0)
        val hit = (items.indices).map { (start + it).mod(items.size) }
            .firstOrNull { items[it].name.lowercase().startsWith(needle) } ?: return true
        moveCursor(hit, hit, shift = false, ctrl = false)
        return true
    }

    private fun applyPendingCursor() {
        val id = pendingCursorId ?: return
        pendingCursorId = null
        val p = adapter.items.indexOfFirst { it.id == id }
        if (p < 0) return
        cursor = p
        anchor = p
        // Só mostra o contorno se o teclado estiver em uso (a lista já tem o foco).
        if (list.hasFocus()) focusAt(p)
    }

    /** Alt+→: entra de novo na pasta de onde se voltou. */
    private fun forward() {
        if (forwardIds.isEmpty()) return
        endSelection()
        scrollStates[ids.size - 1] = lm.onSaveInstanceState()
        ids.add(forwardIds.removeAt(forwardIds.size - 1))
        names.add(forwardNames.removeAt(forwardNames.size - 1))
        scrollStates.add(null)
        cursor = -1
        renderCrumbs()
        load(null)
    }

    /** Ctrl+Shift+C: caminho dos itens (um por linha) na área de transferência. */
    private fun copyPaths() {
        val r = root ?: return
        val items = targets()
        if (items.isEmpty()) return
        val base = (listOf(vault?.name ?: r.alias) + names.drop(1)).joinToString("/")
        val text = items.joinToString("\n") { if (r.isFile && it.id.startsWith("/")) it.id else "$base/${it.name}" }
        val cm = requireContext().getSystemService(android.content.ClipboardManager::class.java)
        cm.setPrimaryClip(android.content.ClipData.newPlainText(getString(R.string.path_copied), text))
        Toast.makeText(requireContext(), R.string.path_copied, Toast.LENGTH_SHORT).show()
    }

    /** Alt+Enter: propriedades (nome, tipo, tamanho, data e onde está). */
    private fun showProperties(item: FileItem) {
        val r = root ?: return
        val ctx = requireContext()
        val where = (listOf(vault?.name ?: r.alias) + names.drop(1)).joinToString(" › ")
        val lines = ArrayList<String>()
        lines += getString(R.string.prop_type, if (item.isDir) getString(R.string.prop_folder) else item.mime)
        if (!item.isDir) {
            lines += getString(
                R.string.prop_size,
                android.text.format.Formatter.formatFileSize(ctx, item.size),
                java.text.NumberFormat.getIntegerInstance().format(item.size),
            )
        }
        if (item.modified > 0) {
            lines += getString(
                R.string.prop_modified,
                java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.LONG, java.text.DateFormat.SHORT).format(java.util.Date(item.modified)),
            )
        }
        lines += getString(R.string.prop_where, where)
        val dlg = AlertDialog.Builder(ctx)
            .setTitle(item.name)
            .setMessage(lines.joinToString("\n"))
            .setPositiveButton(R.string.ok, null)
            .show()
        if (item.isDir && vault == null) {
            // Pasta: quantos itens tem (da cópia salva, sem consultar o armazenamento).
            val app = ctx.applicationContext
            Bg.run({ ListingCache(app).read(r.id, item.id)?.size }) { res ->
                val n = res.getOrNull() ?: return@run
                if (!dlg.isShowing) return@run
                lines.add(1, resources.getQuantityString(R.plurals.prop_items, n, n))
                dlg.setMessage(lines.joinToString("\n"))
            }
        }
    }

    /**
     * Clique direito num item: seleciona-o (somando à seleção, se houver) e abre o menu de
     * ações ali mesmo. Fechar o menu sem escolher desfaz a seleção que ele criou.
     */
    private fun contextMenu(item: FileItem, anchor: View) {
        val temp = !selecting
        if (temp) startSelection(item) else if (item.id !in selected) toggle(item)
        val popup = PopupMenu(requireContext(), anchor, Gravity.END)
        popup.menu.add(0, R.id.ctx_open, 0, R.string.open)
        popup.menuInflater.inflate(R.menu.selection, popup.menu)
        menu.onPrepareMenu(popup.menu)
        popup.menu.findItem(R.id.sel_all)?.isVisible = false
        var acted = false
        popup.setOnMenuItemClickListener { mi ->
            acted = true
            if (mi.itemId == R.id.ctx_open) {
                if (temp) endSelection()
                onItemClick(item)
                true
            } else {
                menu.onMenuItemSelected(mi)
            }
        }
        popup.setOnDismissListener { if (temp && !acted && selected.size <= 1) endSelection() }
        popup.show()
    }

    // ---- Seleção e edição (fase 7) ----

    private fun startSelection(first: FileItem?) {
        selecting = true
        selected.clear()
        first?.let { selected.add(it.id) }
        selectBack.isEnabled = true
        adapter.refreshAllSelection()
        selectionChanged()
    }

    private fun endSelection() {
        if (!selecting) return
        selecting = false
        selected.clear()
        selectBack.isEnabled = false
        if (::adapter.isInitialized) adapter.refreshAllSelection()
        if (view != null) selectionChanged()
    }

    private fun toggle(item: FileItem) {
        if (!selected.remove(item.id)) selected.add(item.id)
        val pos = adapter.items.indexOfFirst { it.id == item.id }
        if (pos >= 0) adapter.refreshSelection(pos)
        selectionChanged()
    }

    private fun selectAll() {
        if (selected.size == adapter.items.size) selected.clear()
        else adapter.items.forEach { selected.add(it.id) }
        adapter.refreshAllSelection()
        selectionChanged()
    }

    private fun selectionChanged() {
        updateTitle()
        requireActivity().invalidateOptionsMenu()
        (activity as? MainActivity)?.updateFabs()
    }

    /** Botões flutuantes da MainActivity: fora da seleção, da barra de colar e das pastas protegidas. */
    fun fabsAllowed() = !selecting && vaultId == null &&
        (!::pasteBar.isInitialized || pasteBar.visibility != View.VISIBLE)

    /** "Abrir com...": um arquivo só, sempre pergunta o app. */
    private fun openWithSelected() {
        val item = selectedItems().singleOrNull()?.takeIf { !it.isDir } ?: return
        endSelection()
        FileOpener.open(this, source, item, chooser = true)
    }

    /** "Compartilhar": os arquivos selecionados (pastas não entram). */
    private fun shareSelected() {
        val files = selectedItems().filter { !it.isDir }
        if (files.isEmpty()) return
        endSelection()
        Share.share(this, files.map { it to source })
    }

    private fun selectedItems(): List<FileItem> = adapter.items.filter { it.id in selected }

    private fun here(): Loc? {
        val r = root ?: return null
        return Loc(r, source, ids.last(), names.drop(1).joinToString("/"), vault)
    }

    /**
     * Garante permissão de escrita. Pasta local adicionada antes da fase 7 só tem leitura:
     * explica e reabre o seletor do sistema na mesma pasta (o índice é mantido).
     */
    private fun withWrite(r: Root? = root, action: () -> Unit) {
        val rr = r ?: return
        if (Roots.canWrite(requireContext(), rr)) {
            action()
            return
        }
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.write_needed_title)
            .setMessage(R.string.write_needed_msg)
            .setPositiveButton(R.string.write_needed_continue) { _, _ -> (activity as MainActivity).readd(rr) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** Roda a operação e, ao terminar, recarrega a pasta mantendo a rolagem. */
    private fun runOp(title: Int, work: (FileOps) -> FileOps.Result) {
        val main = activity as? MainActivity ?: return
        endSelection()
        OpRunner.run(main, title, work) {
            if (view != null) {
                load(lm.onSaveInstanceState())
                updatePasteBar()
            }
        }
    }

    private fun newFolder() = withWrite {
        val main = activity as MainActivity
        OpRunner.askName(main, R.string.new_folder, getString(R.string.new_folder), isFile = false) { name ->
            val at = here() ?: return@askName
            runOp(R.string.op_creating) { it.createFolder(at, name) }
        }
    }

    private fun renameSelected() {
        val item = selectedItems().singleOrNull() ?: return
        withWrite {
            val main = activity as MainActivity
            OpRunner.askName(main, R.string.rename_item, item.name, isFile = !item.isDir) { name ->
                val at = here() ?: return@askName
                runOp(R.string.op_renaming) { it.rename(at, item, name) }
            }
        }
    }

    private fun deleteSelected() {
        val items = selectedItems()
        val r = root ?: return
        if (items.isEmpty()) return
        withWrite {
            AlertDialog.Builder(requireContext())
                .setTitle(resources.getQuantityString(R.plurals.delete_title, items.size, items.size))
                .setMessage(if (r.isDrive) R.string.delete_drive_msg else R.string.delete_local_msg)
                .setPositiveButton(R.string.delete) { _, _ ->
                    val at = here() ?: return@setPositiveButton
                    runOp(R.string.op_deleting) { it.delete(at, items) }
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
    }

    private fun clipSelected(move: Boolean) {
        val items = selectedItems()
        val at = here() ?: return
        if (items.isEmpty()) return
        val go = {
            Clip.set(at, items, move)
            endSelection()
            updatePasteBar()
            Toast.makeText(requireContext(), R.string.clip_hint, Toast.LENGTH_SHORT).show()
        }
        // Mover também apaga da origem: precisa de escrita lá.
        if (move) withWrite(at.root, go) else go()
    }

    private fun paste() {
        val from = Clip.from ?: return
        val items = Clip.items
        val move = Clip.move
        withWrite {
            val to = here() ?: return@withWrite
            val ancestors = ids.toList()
            Clip.clear()
            runOp(if (move) R.string.op_moving else R.string.op_copying) {
                it.transfer(from, items, to, move, ancestors)
            }
        }
    }

    // ---- Digitalizar documento (scanner do Google Play services) ----

    private fun startScan() = withWrite {
        val act = activity ?: return@withWrite
        Scan.start(act, scanLauncher)
    }

    /** Pergunta o formato e salva nesta pasta. */
    private fun onScanned(r: GmsDocumentScanningResult) {
        Scan.askFormat(requireContext(), r) { uris, named ->
            val at = here() ?: return@askFormat
            runOp(R.string.op_saving) { it.upload(at, uris, named) }
        }
    }

    private fun upload(uris: List<android.net.Uri>) {
        val at = here() ?: return
        runOp(R.string.op_uploading) { it.upload(at, uris) }
    }

    private fun protectSelected() {
        val item = selectedItems().singleOrNull()?.takeIf { it.isDir } ?: return
        val r = root ?: return
        val pathIds = ids + item.id
        val pathNames = names + item.name
        (activity as MainActivity).vaultUi.protect(r, pathIds, pathNames) {
            endSelection()
            if (view != null) load(lm.onSaveInstanceState())
        }
    }

    fun refreshPasteBar() {
        if (view != null) updatePasteBar()
    }

    private fun updatePasteBar() {
        val main = activity as? MainActivity ?: return
        val here = pasteBar.findViewById<android.widget.Button>(R.id.paste_here)
        // Recentes não recebe arquivos (é uma lista do aparelho todo): a barra espera outra pasta.
        if ((!Clip.active && !main.saving) || root?.isRecent == true) {
            pasteBar.visibility = View.GONE
            main.updateFabs()
            return
        }
        if (main.saving) {
            val n = main.incoming.size
            pasteText.text = resources.getQuantityString(R.plurals.share_count, n, n)
            here.setText(R.string.share_save_here)
        } else {
            val n = Clip.items.size
            pasteText.text = resources.getQuantityString(if (Clip.move) R.plurals.clip_move else R.plurals.clip_copy, n, n)
            here.setText(R.string.paste_here)
        }
        pasteBar.visibility = View.VISIBLE
        main.updateFabs()
    }

    /** "Salvar no Index": copia os arquivos recebidos para esta pasta e volta ao app de origem. */
    private fun saveShared() = withWrite {
        val main = activity as? MainActivity ?: return@withWrite
        val at = here() ?: return@withWrite
        val uris = main.incoming
        val named = main.incomingNames
        endSelection()
        OpRunner.run(main, R.string.op_saving, { it.upload(at, uris, named) }) { res ->
            main.shareSaved(res)
            if (view != null) {
                load(lm.onSaveInstanceState())
                updatePasteBar()
            }
        }
    }


    // ---- Seleção aleatória (fase 9) ----

    /**
     * Sorteia com as opções salvas: arquivos da pasta atual (já filtrados como na listagem)
     * e, se pedido, das subpastas pelo índice. Pastas protegidas nunca entram.
     */
    private fun startRandom() {
        val r = root ?: return
        endSelection()
        val app = requireContext().applicationContext
        val type = prefs.randomType
        val n = prefs.randomCount
        val sub = prefs.randomSubfolders
        val skipHidden = !prefs.showHidden
        val base = if (randomActive) randomFull else adapter.items
        val folder = ids.last()
        val v = vault
        val rg = ++randomGen
        Bg.run({
            val all = if (!sub) {
                base
            } else {
                val deeper = if (v != null) {
                    VaultIndex.filesUnder(app, v, folder, skipHidden)
                } else {
                    ListingCache(app).filesUnder(r.id, folder, skipHidden) { Vault.isHidden(app, r.id, it) }
                }
                base + deeper
            }
            RandomPick.pool(all, type)
        }) { res ->
            if (rg != randomGen || view == null) return@run
            val pool = res.getOrDefault(emptyList())
            if (pool.isEmpty()) {
                RandomUi.toastNone(app, type)
                return@run
            }
            if (!randomActive) {
                randomFull = base
                randomRestore = lm.onSaveInstanceState()
            }
            randomPool = pool
            randomDraw++
            showRandom(RandomPick.pick(pool, n))
        }
    }

    private fun drawAgain() {
        if (!randomActive || randomPool.isEmpty()) return
        randomDraw++
        showRandom(RandomPick.pick(randomPool, prefs.randomCount))
    }

    private fun showRandom(items: List<FileItem>) {
        endSelection()
        randomActive = true
        randomShown = items
        randomBack.isEnabled = true
        sortGen++
        list.removeCallbacks(showProgress)
        progress.visibility = View.GONE
        adapter.replace(items)
        lm.scrollToPosition(0)
        showEmpty(0)
        randomText.text = RandomUi.banner(requireContext(), items.size, randomPool.size)
        randomBar.visibility = View.VISIBLE
        keyChanged()
        requireActivity().invalidateOptionsMenu()
    }

    // ---- Pré-carga de miniaturas (fase 10) ----

    private fun listKey(): String {
        val base = "b|${root?.id}|${vaultId ?: ""}|${ids.lastOrNull()}"
        return if (randomActive) "$base|r$randomDraw" else base
    }

    /** A lista na tela mudou (pasta, sorteio): a anterior conta como "saiu da lista". */
    private fun keyChanged() {
        val k = listKey()
        if (k != currentKey) {
            currentKey?.let(Preload::leave)
            currentKey = k
            Preload.enter(k)
        }
        if (::preloadBar.isInitialized) PreloadUi.renderBar(preloadBar, currentKey)
    }

    private fun startPreload() {
        val k = listKey()
        currentKey = k
        PreloadUi.start(this, k, adapter.items, { source }, lm.findFirstVisibleItemPosition().coerceAtLeast(0))
    }

    private fun onPreloadChanged() {
        if (view == null) return
        PreloadUi.renderBar(preloadBar, currentKey)
        val mine = currentKey != null && currentKey == Preload.listKey
        if (preloadWasRunning && !Preload.running && mine && Preload.holding) {
            PreloadUi.toastFinished(requireContext())
            requestWindow()
        }
        preloadWasRunning = Preload.running && mine
    }

    private fun requestWindow() {
        val k = currentKey ?: return
        PreloadUi.requestWindow(k, list, adapter.items) { source }
    }

    private fun clearRandom() {
        randomGen++
        if (!randomActive) return
        endSelection()
        randomActive = false
        randomPool = emptyList()
        randomFull = emptyList()
        randomShown = emptyList()
        randomBack.isEnabled = false
        if (::randomBar.isInitialized) randomBar.visibility = View.GONE
    }

    /** "Lista completa" (ou voltar): a pasta como estava, na mesma rolagem. */
    private fun exitRandom() {
        val state = randomRestore
        randomRestore = null
        load(state)
    }

    companion object {
        /** Último aviso de "sem conexão" (vale para todas as telas de lista). */
        private var lastOfflineToast = -OFFLINE_TOAST_MS

        /** Ctrl+Shift+1…6 como no Explorador: extragrandes, grandes, médios, pequenos, lista, detalhes (0 = lista). */
        private val VIEW_MODES = intArrayOf(GridZoom.FILL, 320, 160, 96, 0, 0)
        private const val OFFLINE_TOAST_MS = 2 * 60_000L

        private const val TAG = "Index"
        private const val ARG_ROOT = "root"
        private const val ARG_VAULT = "vault"

        /** Pasta protegida desbloqueada; com [ids]/[names] (da pasta protegida até a subpasta) abre direto nela. */
        fun newVault(entryId: String, ids: ArrayList<String>? = null, names: ArrayList<String>? = null) =
            BrowserFragment().apply {
                arguments = bundleOf(ARG_VAULT to entryId, S_IDS to ids, S_NAMES to names)
            }
        private const val S_IDS = "ids"
        private const val S_NAMES = "names"
        private const val S_SCROLL = "scroll"

        /** Abre a raiz; com [ids]/[names] (da raiz até a pasta) abre direto numa subpasta. */
        fun newInstance(rootId: Long, ids: ArrayList<String>? = null, names: ArrayList<String>? = null) =
            BrowserFragment().apply {
                arguments = bundleOf(ARG_ROOT to rootId, S_IDS to ids, S_NAMES to names)
            }
    }
}
