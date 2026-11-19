package app.index.files.ui.doc

import android.annotation.SuppressLint
import android.graphics.pdf.PdfRenderer
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.util.Log
import android.util.TypedValue
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import app.index.files.Bg
import app.index.files.R
import app.index.files.data.FileItem
import app.index.files.data.FileSource
import app.index.files.data.drive.AuthRequired
import app.index.files.ui.FileOpener
import app.index.files.ui.MainActivity
import java.io.File

/**
 * Leitor de documentos do app (só leitura): PDF pelo renderizador do Android; os demais
 * formatos convertidos em HTML e mostrados num WebView sem JavaScript, sem rede e sem acesso a
 * arquivos. Abre por cima da tela atual, como o visualizador de fotos.
 */
class DocViewerFragment : Fragment(R.layout.fragment_doc_viewer), MainActivity.KeyTarget {

    private class Session(val item: FileItem, val src: FileSource, val kind: DocKind)

    private var session: Session? = null
    private var pdf: PdfView? = null
    private var web: WebView? = null
    private var temp: File? = null
    private lateinit var info: TextView
    private var gen = 0

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val s = pending
        pending = null
        if (s == null) {
            view.post { close() }
            return
        }
        session = s
        view.findViewById<TextView>(R.id.doc_name).text = s.item.name
        info = view.findViewById(R.id.doc_info)
        info.text = label(s.kind)
        view.findViewById<View>(R.id.doc_back).setOnClickListener { close() }
        view.findViewById<View>(R.id.doc_open_with).setOnClickListener { FileOpener.open(this, s.src, s.item, chooser = true) }
        view.findViewById<View>(R.id.doc_share).setOnClickListener { app.index.files.ui.Share.share(this, listOf(s.item to s.src)) }

        val top = view.findViewById<View>(R.id.doc_top)
        val content = view.findViewById<View>(R.id.doc_content)
        ViewCompat.setOnApplyWindowInsetsListener(view) { _, wi ->
            val b = wi.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            top.setPadding(b.left, b.top, b.right, 0)
            content.setPadding(b.left, 0, b.right, b.bottom)
            wi
        }
        ViewCompat.requestApplyInsets(view)
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = close()
        })
        view.requestFocus()
        load(s)
    }

    private fun label(k: DocKind) = when (k) {
        DocKind.PDF -> "PDF"
        DocKind.TEXT -> "Texto"
        DocKind.MARKDOWN -> "Markdown"
        DocKind.CSV, DocKind.TSV -> "Tabela"
        DocKind.HTML -> "Página HTML"
        DocKind.RTF -> "Texto formatado (RTF)"
        DocKind.DOCX, DocKind.DOC -> "Documento do Word"
        DocKind.XLSX, DocKind.XLS -> "Planilha do Excel"
        DocKind.PPTX, DocKind.PPT -> "Apresentação do PowerPoint"
        DocKind.ODT -> "Documento do LibreOffice"
        DocKind.ODS -> "Planilha do LibreOffice"
        DocKind.ODP -> "Apresentação do LibreOffice"
    }

    private fun load(s: Session) {
        val g = ++gen
        val app = requireContext().applicationContext
        if (s.kind == DocKind.PDF) {
            val tmp = File(File(app.cacheDir, "doc_view"), "doc.pdf")
            temp = tmp
            Bg.run({
                val fd = s.src.openFd(s.item) ?: copyToTemp(s, tmp)
                try {
                    fd to PdfRenderer(fd)
                } catch (e: Exception) {
                    fd.close()
                    throw e
                }
            }) { res ->
                if (g != gen || view == null) {
                    res.getOrNull()?.second?.close() // o PdfRenderer fecha o descritor junto
                    return@run
                }
                res.onSuccess { (_, r) ->
                    if (r.pageCount == 0) {
                        r.close()
                        showError(getString(R.string.doc_empty))
                        return@onSuccess
                    }
                    val v = PdfView(requireContext(), r) { page, count ->
                        info.text = getString(R.string.doc_page, page + 1, count)
                    }
                    pdf = v
                    attach(v)
                    info.text = getString(R.string.doc_pages, v.pageCount)
                }.onFailure { fail(it) }
            }
            return
        }
        val css = css()
        Bg.run({ DocRender.html(app, s.src, s.item, s.kind, css) }) { res ->
            if (g != gen || view == null) return@run
            res.onSuccess { html -> showHtml(html) }.onFailure { fail(it) }
        }
    }

    /** Sem descritor de arquivo (Drive): cópia temporária, apagada ao fechar. */
    private fun copyToTemp(s: Session, f: File): ParcelFileDescriptor {
        val dir = f.parentFile!!
        dir.mkdirs()
        dir.listFiles()?.forEach { it.delete() }
        s.src.openRead(s.item).use { input -> f.outputStream().use { input.copyTo(it, 256 * 1024) } }
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    private fun attach(v: View) {
        val root = view?.findViewById<FrameLayout>(R.id.doc_content) ?: return
        view?.findViewById<View>(R.id.doc_progress)?.visibility = View.GONE
        root.addView(v, 0, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun showHtml(html: String) {
        val w = WebView(requireContext())
        w.settings.apply {
            javaScriptEnabled = false
            allowFileAccess = false
            allowContentAccess = false
            blockNetworkLoads = true
            blockNetworkImage = true
            builtInZoomControls = true
            displayZoomControls = false
            setSupportZoom(true)
            cacheMode = WebSettings.LOAD_NO_CACHE
            loadWithOverviewMode = false
            useWideViewPort = false
            defaultTextEncodingName = "utf-8"
        }
        w.webViewClient = object : WebViewClient() {
            // Nenhum link navega (nem para fora): é só leitura.
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = true
        }
        // Página HTML de verdade e Word (folha com as cores do documento) em fundo branco; o resto segue o tema.
        val white = session?.kind == DocKind.HTML || session?.kind == DocKind.DOCX || session?.kind == DocKind.DOC
        w.setBackgroundColor(if (white) android.graphics.Color.WHITE else color(R.attr.idxBg))
        w.isVerticalScrollBarEnabled = true
        web = w
        attach(w)
        w.loadDataWithBaseURL("about:blank", html, "text/html", "utf-8", null)
    }

    private fun fail(e: Throwable) {
        Log.w("Index", "documento ${session?.item?.name}", e)
        if (e is AuthRequired) {
            (activity as? MainActivity)?.askConnect()
            close()
            return
        }
        val msg = when {
            e is SecurityException || e.message?.contains("senha") == true -> getString(R.string.doc_password)
            e is OutOfMemoryError -> getString(R.string.doc_too_big)
            else -> getString(R.string.doc_failed)
        }
        showError(msg)
    }

    private fun showError(msg: String) {
        val v = view ?: return
        v.findViewById<View>(R.id.doc_progress).visibility = View.GONE
        v.findViewById<TextView>(R.id.doc_error).apply {
            text = msg
            visibility = View.VISIBLE
        }
    }

    private fun color(attr: Int): Int {
        val tv = TypedValue()
        requireContext().theme.resolveAttribute(attr, tv, true)
        return tv.data
    }

    /** Cores do tema do app; a das linhas de tabela é o texto secundário bem clarinho sobre o fundo. */
    private fun css(): Html.Css {
        val bg = color(R.attr.idxBg)
        val muted = color(R.attr.idxTextSecondary)
        return Html.Css(bg, color(R.attr.idxText), muted, color(R.attr.idxAccent), blend(bg, muted, 0.18f))
    }

    private fun blend(a: Int, b: Int, t: Float): Int {
        fun ch(s: Int) = (((a shr s) and 0xFF) * (1 - t) + ((b shr s) and 0xFF) * t).toInt() shl s
        return ch(16) or ch(8) or ch(0)
    }

    // ---- Teclado ----

    override fun onKey(e: KeyEvent): Boolean {
        when (e.keyCode) {
            KeyEvent.KEYCODE_ESCAPE, KeyEvent.KEYCODE_DEL -> if (e.repeatCount == 0) close()
            KeyEvent.KEYCODE_PAGE_DOWN, KeyEvent.KEYCODE_SPACE -> { pdf?.pageBy(1) ?: web?.pageDown(false) }
            KeyEvent.KEYCODE_PAGE_UP -> { pdf?.pageBy(-1) ?: web?.pageUp(false) }
            KeyEvent.KEYCODE_MOVE_HOME -> { pdf?.scrollToPosition(0) ?: web?.pageUp(true) }
            KeyEvent.KEYCODE_MOVE_END -> { pdf?.let { it.scrollToPosition(it.pageCount - 1) } ?: web?.pageDown(true) }
            else -> return e.isCtrlPressed
        }
        return true
    }

    fun close() {
        val fm = parentFragmentManager
        if (!fm.isStateSaved && isAdded) fm.beginTransaction().remove(this).commit()
        cleanup()
        // Aberto por outro app: fechar o leitor volta para quem chamou.
        (activity as? DocActivity)?.finish()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        cleanup()
    }

    private fun cleanup() {
        gen++
        pdf?.close()
        pdf = null
        web?.let {
            (it.parent as? ViewGroup)?.removeView(it)
            it.destroy()
        }
        web = null
        temp?.delete()
        temp = null
        session = null
    }

    companion object {
        private const val TAG = "doc_viewer"
        private var pending: Session? = null

        /** Abre [item] no leitor; false se o formato não é um dos que ele lê. */
        fun open(act: FragmentActivity, item: FileItem, src: FileSource): Boolean {
            val kind = DocKind.of(item) ?: return false
            val fm = act.supportFragmentManager
            if (fm.isStateSaved) return true
            if (fm.findFragmentByTag(TAG) != null) return true
            pending = Session(item, src, kind)
            fm.beginTransaction().replace(R.id.viewer_host, DocViewerFragment(), TAG).commit()
            return true
        }

        fun find(act: FragmentActivity): DocViewerFragment? =
            act.supportFragmentManager.findFragmentByTag(TAG) as? DocViewerFragment
    }
}
