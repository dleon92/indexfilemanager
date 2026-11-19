package app.index.files.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.widget.PopupMenu
import androidx.core.os.bundleOf
import androidx.core.view.MenuProvider
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import app.index.files.Bg
import app.index.files.R
import app.index.files.data.FileItem
import app.index.files.data.FileSource
import app.index.files.data.Indexer
import app.index.files.data.ListingCache
import app.index.files.data.Prefs
import app.index.files.data.RandomPick
import app.index.files.data.Root
import app.index.files.data.Roots
import app.index.files.data.Search
import app.index.files.data.SearchQuery
import app.index.files.data.SortSpec
import app.index.files.data.comparatorFor
import app.index.files.data.VaultIndex
import app.index.files.data.VaultSource
import app.index.files.security.Vault
import app.index.files.security.VaultEntry
import app.index.files.thumbs.Preload
import app.index.files.thumbs.ThumbnailManager

/**
 * Pesquisa por nome no índice. Escopo: pasta atual (com subpastas) ou todas;
 * filtro Todos/Fotos/Vídeos; mesma ordenação e modos de exibição da navegação.
 */
class SearchFragment : Fragment(R.layout.fragment_search), MainActivity.KeyTarget {

    private lateinit var prefs: Prefs
    private lateinit var adapter: FileAdapter
    private lateinit var lm: GridLayoutManager
    private lateinit var input: EditText
    private lateinit var status: TextView
    private val chips = HashMap<Int, TextView>()

    private var rootId = -1L
    private var folderPath = ""
    private var scopeFolder = false
    private var filter = Search.ALL
    private lateinit var spec: SortSpec

    private var roots: Map<Long, Root> = emptyMap()
    private val sources = HashMap<Long, FileSource>()
    private var gen = 0

    private val debounce = Runnable { runSearch() }
    private val indexListener = Indexer.Listener { running ->
        // Com a seleção sorteada na tela, a indexação terminando não troca a lista.
        if (!running) { if (!randomActive) runSearch() } else updateStatusSuffix()
    }
    private var lastStatus = ""

    /** Última pesquisa exibida: origem do sorteio. */
    private var lastQuery: SearchQuery? = null
    private var lastItems: List<FileItem> = emptyList()
    private var lastTruncated = false

    // ---- Seleção aleatória (fase 9) ----
    private var randomActive = false
    private var randomPool: List<FileItem> = emptyList()
    private var randomShown: List<FileItem> = emptyList()
    private var randomGen = 0
    private lateinit var randomBar: View
    private lateinit var randomText: TextView

    // ---- Pré-carga de miniaturas (fase 10) ----
    private lateinit var preloadBar: View
    private var currentKey: String? = null
    private var randomDraw = 0
    private var preloadWasRunning = false
    private val preloadListener = Preload.Listener { onPreloadChanged() }

    private val randomBack = object : androidx.activity.OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = exitRandom()
    }

    // ---- Seleção (toque longo e arraste) ----
    private val selected = LinkedHashSet<String>()
    private var selecting = false
    private lateinit var dragSelect: DragSelect

    /** Registrado depois do [randomBack]: com seleção ativa, "voltar" só sai da seleção. */
    private val selectBack = object : androidx.activity.OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = endSelection()
    }

    /** Busca dentro de uma pasta protegida desbloqueada (índice cifrado, em memória). */
    private var vaultId: String? = null
    private var vault: VaultEntry? = null

    override fun onDestroy() {
        super.onDestroy()
        val id = vaultId ?: return
        if (Vault.release(id)) {
            VaultIndex.drop(id)
            ThumbnailManager.get(requireContext()).dropDecrypted()
            (activity as? MainActivity)?.applySecureFlag()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requireActivity().onBackPressedDispatcher.addCallback(this, randomBack)
        requireActivity().onBackPressedDispatcher.addCallback(this, selectBack)
        val a = requireArguments()
        vaultId = a.getString(ARG_VAULT)
        vaultId?.let { Vault.retain(it) }
        rootId = a.getLong(ARG_ROOT, -1L)
        folderPath = a.getString(ARG_PATH) ?: ""
        scopeFolder = savedInstanceState?.getBoolean(S_SCOPE) ?: (rootId >= 0)
        filter = savedInstanceState?.getInt(S_FILTER) ?: Search.ALL
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(S_SCOPE, scopeFolder)
        outState.putInt(S_FILTER, filter)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val ctx = requireContext()
        vaultId?.let { id ->
            vault = Vault.entry(ctx, id)?.takeIf { Vault.isOpen(id) }
            if (vault == null) {
                parentFragmentManager.popBackStack()
                return
            }
        }
        prefs = Prefs.get(ctx)
        spec = prefs.defaultSort()
        input = view.findViewById(R.id.input)
        status = view.findViewById(R.id.status)

        adapter = FileAdapter(ctx, ThumbnailManager.get(ctx), ::onItemClick)
        adapter.sourceFor = ::sourceFor
        adapter.pathLabel = ::pathOf
        // Toque longo: seleção (como na navegação); arrastar sem soltar seleciona uma área.
        adapter.onLongClick = { item, _ ->
            if (selecting) toggle(item) else startSelection(item)
            dragSelect.arm(adapter.items.indexOfFirst { it.id == item.id })
        }
        // Clique direito do mouse: as ações do item (caminho, abrir, ir para a pasta).
        adapter.onContextClick = ::showActions
        adapter.isSelected = { selecting && it.id in selected }
        adapter.grid = prefs.viewGrid
        lm = GridLayoutManager(ctx, 1)
        val list = view.findViewById<RecyclerView>(R.id.list)
        list.layoutManager = lm
        zoom = GridZoom(list, lm, adapter, prefs) { requireActivity().invalidateOptionsMenu() }
        lm.spanCount = zoom.span()
        list.setHasFixedSize(true)
        list.adapter = adapter
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
        ScrollLabels.setup(list, view.findViewById(R.id.fast_scroller), adapter) { spec.by }
        randomBar = view.findViewById(R.id.random_bar)
        randomText = view.findViewById(R.id.random_text)
        view.findViewById<View>(R.id.random_again).setOnClickListener { drawAgain() }
        view.findViewById<View>(R.id.random_full).setOnClickListener { exitRandom() }
        preloadBar = view.findViewById(R.id.preload_bar)
        list.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(rv: RecyclerView, newState: Int) {
                if (newState == RecyclerView.SCROLL_STATE_IDLE) requestWindow()
            }
        })
        listView = list
        Preload.addListener(preloadListener)
        preloadWasRunning = Preload.running

        // Escopo e filtro
        val folderChip = view.findViewById<TextView>(R.id.scope_folder)
        if (rootId >= 0) {
            folderChip.text = getString(R.string.scope_folder, requireArguments().getString(ARG_LABEL))
        } else {
            folderChip.visibility = View.GONE
        }
        chips[R.id.scope_folder] = folderChip
        for (id in intArrayOf(R.id.scope_all, R.id.filter_all, R.id.filter_photos, R.id.filter_videos)) {
            chips[id] = view.findViewById(id)
        }
        folderChip.setOnClickListener { scopeFolder = true; syncChips(); runSearch() }
        chips[R.id.scope_all]!!.setOnClickListener { scopeFolder = false; syncChips(); runSearch() }
        chips[R.id.filter_all]!!.setOnClickListener { filter = Search.ALL; syncChips(); runSearch() }
        chips[R.id.filter_photos]!!.setOnClickListener { filter = Search.PHOTOS; syncChips(); runSearch() }
        chips[R.id.filter_videos]!!.setOnClickListener { filter = Search.VIDEOS; syncChips(); runSearch() }
        syncChips()
        if (vault != null) {
            // Escopo fixo: a pasta protegida inteira.
            folderChip.visibility = View.GONE
            chips[R.id.scope_all]!!.visibility = View.GONE
        }

        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                // Texto restaurado ao voltar de outra tela: não desfaz a seleção sorteada.
                if (randomActive && s.toString().trim() == lastQuery?.text) return
                input.removeCallbacks(debounce)
                input.postDelayed(debounce, DEBOUNCE_MS)
            }
        })
        input.setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_SEARCH) {
                input.removeCallbacks(debounce)
                runSearch()
                hideKeyboard()
                true
            } else {
                false
            }
        }

        requireActivity().addMenuProvider(menu, viewLifecycleOwner, Lifecycle.State.RESUMED)
        Indexer.addListener(indexListener)

        val app = ctx.applicationContext
        val v = vault
        Bg.run({
            if (v != null) listOfNotNull(Roots.get(app, v.rootId)).associateBy { it.id }
            else Roots.all(app).associateBy { it.id }
        }) { res ->
            if (view.isAttachedToWindow || this.view != null) {
                roots = res.getOrDefault(emptyMap())
                // Voltou de outra tela com a seleção sorteada: ela continua.
                if (randomActive && randomShown.isNotEmpty()) showRandom(randomShown) else runSearch()
            }
        }

        if (savedInstanceState == null) {
            input.requestFocus()
            input.post {
                (ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                    .showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        Preload.removeListener(preloadListener)
        currentKey?.let(Preload::leave)
        currentKey = null
        Indexer.removeListener(indexListener)
        input.removeCallbacks(debounce)
        gen++
    }

    override fun onResume() {
        super.onResume()
        updateTitle()
    }

    private fun updateTitle() {
        requireActivity().title = if (selecting) {
            resources.getQuantityString(R.plurals.selected_count, selected.size, selected.size)
        } else {
            getString(R.string.search)
        }
    }

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
        if (selected.size == adapter.items.size) selected.clear() else adapter.items.forEach { selected.add(it.id) }
        adapter.refreshAllSelection()
        selectionChanged()
    }

    private fun selectionChanged() {
        updateTitle()
        requireActivity().invalidateOptionsMenu()
    }

    private fun selectedItems(): List<FileItem> = adapter.items.filter { it.id in selected }

    private lateinit var zoom: GridZoom

    private fun syncChips() {
        chips[R.id.scope_folder]?.isSelected = scopeFolder
        chips[R.id.scope_all]?.isSelected = !scopeFolder
        chips[R.id.filter_all]?.isSelected = filter == Search.ALL
        chips[R.id.filter_photos]?.isSelected = filter == Search.PHOTOS
        chips[R.id.filter_videos]?.isSelected = filter == Search.VIDEOS
    }

    private fun sourceFor(item: FileItem): FileSource = sources.getOrPut(item.rootId) {
        val r = roots[item.rootId] ?: Roots.get(requireContext(), item.rootId)
        val base = Roots.source(requireContext(), r!!)
        vault?.let { VaultSource(base, it) } ?: base
    }

    private fun pathOf(item: FileItem): String {
        val alias = vault?.name ?: roots[item.rootId]?.alias ?: ""
        return if (item.parentPath.isEmpty()) alias else "$alias/${item.parentPath}"
    }

    private fun runSearch() {
        endSelection()
        if (view == null) return
        clearRandom()
        val text = input.text.toString().trim()
        val g = ++gen
        if (text.isEmpty()) {
            lastQuery = null
            lastItems = emptyList()
            adapter.replace(emptyList())
            keyChanged()
            setStatus(getString(R.string.search_type_something))
            return
        }
        val q = SearchQuery(
            text = text,
            rootId = if (scopeFolder) rootId else -1L,
            folderPath = if (scopeFolder) folderPath else null,
            filter = filter,
            spec = spec,
            showHidden = prefs.showHidden,
        )
        val app = requireContext().applicationContext
        val v = vault
        Bg.run({ if (v != null) VaultIndex.search(app, v, q.text, q.filter, q.showHidden, q.spec) else Search.run(app, q) }) { res ->
            if (g != gen || view == null) return@run
            res.onSuccess { r ->
                lastQuery = q
                lastItems = r.items
                lastTruncated = r.truncated
                adapter.replace(r.items)
                keyChanged()
                lm.scrollToPosition(0)
                var s = if (r.items.isEmpty()) {
                    getString(R.string.search_none, r.ms)
                } else {
                    resources.getQuantityString(R.plurals.search_results, r.items.size, r.items.size, r.ms)
                }
                if (r.truncated) s += " · " + getString(R.string.search_truncated, Search.LIMIT)
                setStatus(s)
            }.onFailure { setStatus(getString(R.string.search_failed)) }
        }
    }

    private fun setStatus(s: String) {
        lastStatus = s
        updateStatusSuffix()
    }

    private fun updateStatusSuffix() {
        if (view == null) return
        status.text = if (Indexer.running) lastStatus + " · " + getString(R.string.indexing) else lastStatus
    }

    private fun hideKeyboard() {
        (requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(input.windowToken, 0)
    }

    // ---- Teclado (fase 12) ----

    override fun onKey(e: KeyEvent): Boolean {
        if (view == null) return false
        val list = listView ?: return false
        when {
            e.isCtrlPressed && e.keyCode == KeyEvent.KEYCODE_F -> {
                input.requestFocus()
                input.selectAll()
                (requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                    .showSoftInput(input, 0)
            }
            e.isCtrlPressed && e.keyCode == KeyEvent.KEYCODE_A && !input.hasFocus() -> {
                if (!selecting) startSelection(null)
                selectAll()
            }
            Keys.zoom(e, zoom) -> Unit
            // No campo de texto as setas movem o cursor; só "para baixo" desce para a lista.
            input.hasFocus() && e.keyCode != KeyEvent.KEYCODE_DPAD_DOWN -> return false
            Keys.focusList(e, list) -> Unit
            else -> return false
        }
        return true
    }

    private fun onItemClick(item: FileItem) {
        if (selecting) toggle(item) else if (item.isDir) goToFolder(item, inside = true) else open(item)
    }

    private fun open(item: FileItem) {
        if (item.kind == FileItem.KIND_IMAGE && prefs.internalViewer) {
            val photos = adapter.items.filter { it.kind == FileItem.KIND_IMAGE }
            val start = photos.indexOfFirst { it.id == item.id && it.rootId == item.rootId }
            if (start >= 0) {
                app.index.files.ui.viewer.ViewerFragment.open(requireActivity(), photos, start, ::sourceFor) onClose@{ last ->
                    if (view == null) return@onClose
                    val p = adapter.items.indexOfFirst { it === last }
                    if (p >= 0 && (p < lm.findFirstCompletelyVisibleItemPosition() || p > lm.findLastCompletelyVisibleItemPosition())) {
                        lm.scrollToPositionWithOffset(p, 0)
                    }
                }
                return
            }
        }
        if (prefs.internalDocs && app.index.files.ui.doc.DocViewerFragment.open(requireActivity(), item, sourceFor(item))) return
        FileOpener.open(this, sourceFor(item), item)
    }

    private fun showActions(item: FileItem, anchor: View) {
        val pm = PopupMenu(requireContext(), anchor)
        pm.menu.add(0, 0, 0, pathOf(item)).isEnabled = false
        if (!item.isDir) pm.menu.add(0, 1, 1, R.string.open)
        pm.menu.add(0, 2, 2, R.string.go_to_folder)
        pm.setOnMenuItemClickListener {
            when (it.itemId) {
                1 -> open(item)
                2 -> goToFolder(item, inside = false)
            }
            true
        }
        pm.show()
    }

    /** Abre o navegador na pasta que contém o item (ou dentro dele, se for pasta). */
    private fun goToFolder(item: FileItem, inside: Boolean) {
        val r = roots[item.rootId] ?: return
        val app = requireContext().applicationContext
        val v = vault
        Bg.run({
            if (v != null) VaultIndex.pathTo(app, v, item.parentId)
            else ListingCache(app).pathTo(r.id, r.docId, r.alias, item.parentId)
        }) { res ->
            if (view == null) return@run
            val path = res.getOrNull()
            if (path == null) {
                Toast.makeText(app, R.string.folder_not_indexed, Toast.LENGTH_SHORT).show()
                return@run
            }
            val (ids, names) = path
            if (inside) {
                ids.add(item.id)
                names.add(item.name)
            }
            hideKeyboard()
            (activity as MainActivity).push(
                if (v != null) BrowserFragment.newVault(v.id, ids, names) else BrowserFragment.newInstance(r.id, ids, names),
            )
        }
    }

    private val menu = object : MenuProvider {
        override fun onCreateMenu(menu: Menu, inflater: MenuInflater) =
            inflater.inflate(if (selecting) R.menu.selection_search else R.menu.browser, menu)

        override fun onPrepareMenu(menu: Menu) {
            if (selecting) {
                val one = selectedItems().singleOrNull()
                menu.findItem(R.id.sel_open_with)?.isVisible = one?.isDir == false
                val sel = selectedItems()
                menu.findItem(R.id.sel_share)?.isVisible = sel.isNotEmpty() && sel.none { it.isDir }
                menu.findItem(R.id.sel_goto)?.isVisible = one != null
                return
            }
            menu.findItem(R.id.action_search).isVisible = false
            menu.findItem(R.id.sort_reset).isVisible = false
            for (id in intArrayOf(R.id.action_new_folder, R.id.action_upload, R.id.action_select)) {
                menu.findItem(id)?.isVisible = false
            }
            RandomUi.bindButton(menu.findItem(R.id.action_random), { startRandom() }) {
                RandomUi.panel(requireContext(), subfolders = false, positive = R.string.random_draw) { startRandom() }
            }
            val grid = adapter.grid
            menu.findItem(R.id.action_view_mode).apply {
                setIcon(if (grid) R.drawable.ic_view_list else R.drawable.ic_view_grid)
                setTitle(if (grid) R.string.view_list else R.string.view_grid)
            }
            menu.findItem(R.id.action_bigger).isVisible = true // da lista, volta à grade
            menu.findItem(R.id.action_smaller).isVisible = grid
            menu.findItem(
                when (spec.by) {
                    SortSpec.SIZE -> R.id.sort_size
                    SortSpec.TYPE -> R.id.sort_type
                    SortSpec.DATE -> R.id.sort_date
                    else -> R.id.sort_name
                },
            ).isChecked = true
            menu.findItem(R.id.sort_desc).isChecked = !spec.asc
            menu.findItem(R.id.sort_dirs_first).isChecked = spec.dirsFirst
        }

        override fun onMenuItemSelected(item: MenuItem): Boolean {
            if (selecting) {
                val one = selectedItems().singleOrNull()
                when (item.itemId) {
                    R.id.sel_share -> {
                        val files = selectedItems().filter { !it.isDir }
                        if (files.isNotEmpty()) {
                            endSelection()
                            Share.share(this@SearchFragment, files.map { it to sourceFor(it) })
                        }
                    }
                    R.id.sel_open_with -> one?.takeIf { !it.isDir }?.let {
                        endSelection()
                        FileOpener.open(this@SearchFragment, sourceFor(it), it, chooser = true)
                    }
                    R.id.sel_goto -> one?.let {
                        endSelection()
                        goToFolder(it, inside = false)
                    }
                    R.id.sel_all -> selectAll()
                    else -> return false
                }
                return true
            }
            when (item.itemId) {
                R.id.action_view_mode -> {
                    adapter.grid = !adapter.grid
                    prefs.viewGrid = adapter.grid
                    zoom.apply()
                }
                R.id.sort_name -> spec = spec.copy(by = SortSpec.NAME)
                R.id.sort_size -> spec = spec.copy(by = SortSpec.SIZE)
                R.id.sort_type -> spec = spec.copy(by = SortSpec.TYPE)
                R.id.sort_date -> spec = spec.copy(by = SortSpec.DATE)
                R.id.sort_desc -> spec = spec.copy(asc = !spec.asc)
                R.id.sort_dirs_first -> spec = spec.copy(dirsFirst = !spec.dirsFirst)
                R.id.action_bigger -> zoom.step(+1)
                R.id.action_smaller -> zoom.step(-1)
                R.id.action_preload -> startPreload()
                else -> return false
            }
            if (item.groupId == R.id.group_sort_by || item.groupId == R.id.group_sort_opts) {
                // Seleção sorteada: só reordena os itens na tela.
                if (randomActive) sortShown() else runSearch()
            }
            requireActivity().invalidateOptionsMenu()
            return true
        }
    }


    // ---- Seleção aleatória (fase 9) ----

    /**
     * Sorteia entre os resultados da pesquisa atual (com os filtros ativos). Se a lista foi
     * cortada no limite, refaz a consulta sem o corte para sortear entre todos.
     */
    private fun startRandom() {
        val q = lastQuery
        val app = requireContext().applicationContext
        if (q == null) {
            Toast.makeText(app, R.string.random_no_results, Toast.LENGTH_SHORT).show()
            return
        }
        val type = prefs.randomType
        val n = prefs.randomCount
        val items = lastItems
        val truncated = lastTruncated
        val v = vault
        val rg = ++randomGen
        Bg.run({
            val all = if (!truncated) {
                items
            } else {
                val wide = SearchQuery(q.text, q.rootId, q.folderPath, q.filter, q.spec, q.showHidden, limit = WIDE_LIMIT)
                if (v != null) VaultIndex.search(app, v, wide.text, wide.filter, wide.showHidden, wide.spec, WIDE_LIMIT).items
                else Search.run(app, wide).items
            }
            RandomPick.pool(all, type)
        }) { res ->
            if (rg != randomGen || view == null) return@run
            val pool = res.getOrDefault(emptyList())
            if (pool.isEmpty()) {
                RandomUi.toastNone(app, type)
                return@run
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
        hideKeyboard()
        adapter.replace(items)
        lm.scrollToPosition(0)
        randomText.text = RandomUi.banner(requireContext(), items.size, randomPool.size)
        randomBar.visibility = View.VISIBLE
        updateStatusSuffix()
        keyChanged()
    }

    // ---- Pré-carga de miniaturas (fase 10) ----

    private var listView: RecyclerView? = null

    private fun listKey(): String? {
        val q = lastQuery ?: return null
        val base = "s|${vaultId ?: ""}|${q.rootId}|${q.folderPath}|${q.filter}|${q.text}"
        return if (randomActive) "$base|r$randomDraw" else base
    }

    private fun keyChanged() {
        val k = listKey()
        if (k != currentKey) {
            currentKey?.let(Preload::leave)
            currentKey = k
            k?.let(Preload::enter)
        }
        if (::preloadBar.isInitialized) PreloadUi.renderBar(preloadBar, currentKey)
    }

    private fun startPreload() {
        val k = listKey()
        if (k == null) {
            Toast.makeText(requireContext(), R.string.random_no_results, Toast.LENGTH_SHORT).show()
            return
        }
        currentKey = k
        PreloadUi.start(this, k, adapter.items, ::sourceFor, lm.findFirstVisibleItemPosition().coerceAtLeast(0))
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
        val l = listView ?: return
        PreloadUi.requestWindow(k, l, adapter.items, ::sourceFor)
    }

    private fun sortShown() {
        val s = spec
        val items = adapter.items
        val rg = randomGen
        Bg.run({ items.sortedWith(comparatorFor(s)) }) { res ->
            if (rg != randomGen || view == null || !randomActive) return@run
            res.onSuccess {
                randomShown = it
                adapter.replace(it)
                lm.scrollToPosition(0)
            }
        }
    }

    private fun clearRandom() {
        randomGen++
        if (!randomActive) return
        randomActive = false
        randomPool = emptyList()
        randomShown = emptyList()
        randomBack.isEnabled = false
        if (::randomBar.isInitialized) randomBar.visibility = View.GONE
    }

    /** "Lista completa" (ou voltar): todos os resultados da pesquisa. */
    private fun exitRandom() = runSearch()

    companion object {
        private const val ARG_ROOT = "root"
        private const val ARG_VAULT = "vault"

        /** Busca dentro da pasta protegida [entryId] (desbloqueada). */
        fun newVault(entryId: String, path: String, label: String) = SearchFragment().apply {
            arguments = bundleOf(ARG_VAULT to entryId, ARG_ROOT to -2L, ARG_PATH to path, ARG_LABEL to label)
        }
        private const val ARG_PATH = "path"
        private const val ARG_LABEL = "label"
        private const val S_SCOPE = "scope_folder"
        private const val S_FILTER = "filter"
        private const val DEBOUNCE_MS = 250L
        /** Teto da consulta usada como origem do sorteio (resultados acima do limite da lista). */
        private const val WIDE_LIMIT = 1_000_000

        /** [rootId] -1 = abre com escopo "todas as pastas". */
        fun newInstance(rootId: Long = -1L, path: String = "", label: String = "") = SearchFragment().apply {
            arguments = bundleOf(ARG_ROOT to rootId, ARG_PATH to path, ARG_LABEL to label)
        }
    }
}
