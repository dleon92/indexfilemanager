package app.index.files.ui.viewer

import android.graphics.RectF
import android.os.Bundle
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.PagerSnapHelper
import androidx.recyclerview.widget.RecyclerView
import app.index.files.R
import app.index.files.data.FileItem
import app.index.files.data.FileSource
import app.index.files.thumbs.ThumbnailManager
import app.index.files.ui.FileOpener
import app.index.files.ui.MainActivity

/**
 * Visualizador de fotos do próprio app. Leve: só a foto atual e as vizinhas na memória,
 * na resolução da tela; o zoom busca a nitidez só da área visível.
 *
 * O zoom e a região (normalizados) valem para todas as fotos: ao passar para a próxima, ela
 * já aparece com o mesmo zoom na mesma região.
 */
class ViewerFragment : Fragment(R.layout.fragment_viewer), MainActivity.KeyTarget, ZoomImageView.Listener {

    /** O que abrir (guardado fora do Bundle: listas grandes e origens não são serializáveis). */
    private class Session(
        val items: List<FileItem>,
        val start: Int,
        val sourceFor: (FileItem) -> FileSource,
        val onClose: (FileItem) -> Unit,
    )

    private var session: Session? = null
    private lateinit var loader: ViewerLoader
    private lateinit var pager: RecyclerView
    private lateinit var lm: LinearLayoutManager
    private lateinit var top: View
    private lateinit var prev: View
    private lateinit var next: View
    private lateinit var name: TextView
    private lateinit var counter: TextView
    private val adapter = PageAdapter()
    private var current = 0
    private var chrome = true

    // Estado compartilhado entre as fotos.
    private var zoom = 1f
    private var cx = 0.5f
    private var cy = 0.5f

    private var savedLightStatus = false
    private var savedLightNav = false
    private val visible = RectF()

    private val items get() = session?.items.orEmpty()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val s = pending
        pending = null
        if (s == null) {
            // Recriado pelo sistema sem a lista em memória: fecha.
            view.post { close() }
            return
        }
        session = s
        current = s.start.coerceIn(0, (s.items.size - 1).coerceAtLeast(0))
        val dm = resources.displayMetrics
        loader = ViewerLoader(maxOf(dm.widthPixels, dm.heightPixels))

        pager = view.findViewById(R.id.viewer_pager)
        top = view.findViewById(R.id.viewer_top)
        prev = view.findViewById(R.id.viewer_prev)
        next = view.findViewById(R.id.viewer_next)
        name = view.findViewById(R.id.viewer_name)
        counter = view.findViewById(R.id.viewer_counter)

        lm = LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)
        pager.layoutManager = lm
        pager.setHasFixedSize(true)
        pager.itemAnimator = null
        pager.adapter = adapter
        val snap = PagerSnapHelper()
        snap.attachToRecyclerView(pager)
        lm.scrollToPosition(current)
        pager.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(rv: RecyclerView, newState: Int) {
                if (newState != RecyclerView.SCROLL_STATE_IDLE) return
                val v = snap.findSnapView(lm) ?: return
                val p = lm.getPosition(v)
                if (p != RecyclerView.NO_POSITION && p != current) onPageChanged(p)
            }
        })
        // Girou a tela / mudou a janela: continua na mesma foto.
        pager.addOnLayoutChangeListener { _, l, _, r, _, ol, _, or, _ ->
            if (r - l != or - ol) pager.post { lm.scrollToPositionWithOffset(current, 0) }
        }

        view.findViewById<View>(R.id.viewer_back).setOnClickListener { close() }
        view.findViewById<View>(R.id.viewer_open_with).setOnClickListener {
            val item = items.getOrNull(current) ?: return@setOnClickListener
            FileOpener.open(this, s.sourceFor(item), item, chooser = true)
        }
        view.findViewById<View>(R.id.viewer_share).setOnClickListener {
            val item = items.getOrNull(current) ?: return@setOnClickListener
            app.index.files.ui.Share.share(this, listOf(item to s.sourceFor(item)))
        }
        prev.setOnClickListener { go(current - 1) }
        next.setOnClickListener { go(current + 1) }

        // Barra e setas ficam fora das barras do sistema (mesmo quando elas somem e voltam).
        ViewCompat.setOnApplyWindowInsetsListener(view) { _, wi ->
            val b = wi.getInsetsIgnoringVisibility(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            top.setPadding(b.left, b.top, b.right, top.paddingBottom)
            (prev.layoutParams as ViewGroup.MarginLayoutParams).marginStart = b.left
            (next.layoutParams as ViewGroup.MarginLayoutParams).marginEnd = b.right
            prev.requestLayout()
            next.requestLayout()
            wi
        }
        ViewCompat.requestApplyInsets(view)

        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = close()
        })

        val win = requireActivity().window
        val ctl = WindowCompat.getInsetsController(win, win.decorView)
        savedLightStatus = ctl.isAppearanceLightStatusBars
        savedLightNav = ctl.isAppearanceLightNavigationBars
        ctl.isAppearanceLightStatusBars = false
        ctl.isAppearanceLightNavigationBars = false
        ctl.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        view.requestFocus()

        renderChrome()
        preloadAround()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        cleanup()
    }

    fun close() {
        val s = session
        val fm = parentFragmentManager
        if (!fm.isStateSaved && isAdded) fm.beginTransaction().remove(this).commit()
        s?.items?.getOrNull(current)?.let { s.onClose(it) }
        cleanup()
    }

    private var cleaned = false

    /** Solta a memória e devolve as barras do sistema (uma vez só). */
    private fun cleanup() {
        if (cleaned || !::loader.isInitialized) return
        cleaned = true
        session = null
        loader.close()
        val win = activity?.window ?: return
        val ctl = WindowCompat.getInsetsController(win, win.decorView)
        ctl.show(WindowInsetsCompat.Type.systemBars())
        ctl.isAppearanceLightStatusBars = savedLightStatus
        ctl.isAppearanceLightNavigationBars = savedLightNav
    }

    private fun go(p: Int) {
        if (p < 0 || p >= items.size) return
        pager.smoothScrollToPosition(p)
    }

    private fun onPageChanged(p: Int) {
        val old = current
        current = p
        loader.cancelTiles()
        holderAt(old)?.view?.setTile(null, null)
        holderAt(p)?.let { h ->
            h.view.setState(zoom, cx, cy)
            refine(h)
        }
        renderChrome()
        preloadAround()
    }

    private fun renderChrome() {
        val item = items.getOrNull(current)
        name.text = item?.name.orEmpty()
        counter.text = getString(R.string.viewer_counter, current + 1, items.size)
        val vis = if (chrome) View.VISIBLE else View.GONE
        top.visibility = vis
        prev.visibility = if (chrome && current > 0) View.VISIBLE else View.GONE
        next.visibility = if (chrome && current < items.size - 1) View.VISIBLE else View.GONE
        val win = requireActivity().window
        val ctl = WindowCompat.getInsetsController(win, win.decorView)
        if (chrome) ctl.show(WindowInsetsCompat.Type.systemBars()) else ctl.hide(WindowInsetsCompat.Type.systemBars())
    }

    /** Carrega a foto atual e as vizinhas (a atual por último: o cache guarda as 3 mais recentes). */
    private fun preloadAround() {
        val s = session ?: return
        for (p in intArrayOf(current + 1, current - 1, current)) {
            val item = s.items.getOrNull(p) ?: continue
            loader.load(item, s.sourceFor(item)) { full -> if (full != null) deliver(item, full) else failed(item) }
        }
    }

    private fun deliver(item: FileItem, full: ViewerLoader.Full) {
        val h = holderFor(item) ?: return
        h.view.setImage(full.bmp, full.w, full.h)
        h.view.setState(zoom, cx, cy)
        if (h.bindingAdapterPosition == current) refine(h)
    }

    private fun failed(item: FileItem) {
        if (items.getOrNull(current)?.id == item.id && holderFor(item)?.view?.hasImage != true) {
            Toast.makeText(requireContext(), R.string.viewer_failed, Toast.LENGTH_SHORT).show()
        }
    }

    /** Nitidez: decodifica do original só a área visível (quando o zoom passa da base). */
    private fun refine(h: Holder) {
        val s = session ?: return
        val item = h.item ?: return
        val full = loader.cached(item) ?: return
        if (!h.view.visibleRect(visible)) return
        loader.requestTile(item, s.sourceFor(item), full, RectF(visible), h.view.scale()) { b, r ->
            if (h.item === item && h.bindingAdapterPosition == current) h.view.setTile(b, r)
        }
    }

    private fun holderAt(p: Int): Holder? = pager.findViewHolderForAdapterPosition(p) as? Holder

    private fun holderFor(item: FileItem): Holder? {
        for (i in 0 until pager.childCount) {
            val h = pager.getChildViewHolder(pager.getChildAt(i)) as? Holder ?: continue
            if (h.item?.id == item.id) return h
        }
        return null
    }

    // ---- ZoomImageView.Listener ----

    override fun onTap() {
        chrome = !chrome
        renderChrome()
    }

    override fun onStateCommitted(v: ZoomImageView) {
        val h = holderAt(current) ?: return
        if (h.view !== v) return
        zoom = v.zoom
        cx = v.cx
        cy = v.cy
        // As vizinhas já ficam prontas com o mesmo zoom e região.
        for (i in 0 until pager.childCount) {
            val o = pager.getChildViewHolder(pager.getChildAt(i)) as? Holder ?: continue
            if (o !== h) o.view.setState(zoom, cx, cy)
        }
        refine(h)
    }

    // ---- Teclado ----

    override fun onKey(e: KeyEvent): Boolean {
        when (e.keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_PAGE_UP -> go(current - 1)
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_PAGE_DOWN, KeyEvent.KEYCODE_SPACE -> go(current + 1)
            KeyEvent.KEYCODE_MOVE_HOME -> go(0)
            KeyEvent.KEYCODE_MOVE_END -> go(items.size - 1)
            KeyEvent.KEYCODE_ESCAPE, KeyEvent.KEYCODE_DEL -> if (e.repeatCount == 0) close()
            else -> return e.isCtrlPressed // atalhos com Ctrl não vazam para a tela de trás
        }
        return true
    }

    // ---- Páginas ----

    private class Holder(val view: ZoomImageView) : RecyclerView.ViewHolder(view) {
        var item: FileItem? = null
    }

    private inner class PageAdapter : RecyclerView.Adapter<Holder>() {
        override fun getItemCount() = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val v = ZoomImageView(parent.context)
            v.layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            v.listener = this@ViewerFragment
            return Holder(v)
        }

        override fun onBindViewHolder(h: Holder, position: Int) {
            val s = session ?: return
            val item = s.items[position]
            h.item = item
            h.view.contentDescription = item.name
            h.view.setTile(null, null)
            val full = loader.cached(item)
            if (full != null) {
                h.view.setImage(full.bmp, full.w, full.h)
            } else {
                // Miniatura da grade na hora (se estiver na memória); a imagem vem logo depois.
                val src = s.sourceFor(item)
                h.view.setImage(ThumbnailManager.get(requireContext()).cachedAny(src, item), 0, 0)
                loader.load(item, src) { f -> if (f != null) deliver(item, f) else failed(item) }
            }
            h.view.setState(zoom, cx, cy)
            if (position == current && full != null) h.view.post { if (h.item === item) refine(h) }
        }

        override fun onViewRecycled(h: Holder) {
            h.item = null
            h.view.setTile(null, null)
            h.view.setImage(null, 0, 0)
        }
    }

    companion object {
        private const val TAG = "viewer"

        /** Passada da tela de origem para o fragment (só vive até onViewCreated). */
        private var pending: Session? = null

        /**
         * Abre [items] (só fotos) a partir de [start]. [onClose] recebe a foto em que o
         * visualizador estava (a grade rola até ela).
         */
        fun open(
            act: FragmentActivity,
            items: List<FileItem>,
            start: Int,
            sourceFor: (FileItem) -> FileSource,
            onClose: (FileItem) -> Unit,
        ) {
            val fm = act.supportFragmentManager
            if (fm.isStateSaved || items.isEmpty()) return
            if (fm.findFragmentByTag(TAG) != null) return
            pending = Session(items, start, sourceFor, onClose)
            fm.beginTransaction().replace(R.id.viewer_host, ViewerFragment(), TAG).commit()
        }

        fun find(act: FragmentActivity): ViewerFragment? =
            act.supportFragmentManager.findFragmentByTag(TAG) as? ViewerFragment
    }
}
