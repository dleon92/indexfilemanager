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
import app.index.files.security.PdfPasswords
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
            openPdf(s, tmp, password = null, wrong = false)
            return
        }
        val css = css()
        Bg.run({ DocRender.html(app, s.src, s.item, s.kind, css) }) { res ->
            if (g != gen || view == null) return@run
            res.onSuccess { html -> showHtml(html) }.onFailure { fail(it) }
        }
    }

    /** Resultado da abertura do PDF: pronto, ou pede a senha ([wrong] = a digitada não serviu). */
    private sealed class Opened {
        class Ok(val r: PdfRenderer) : Opened()
        class NeedPassword(val wrong: Boolean) : Opened()
    }

    /**
     * Abre o PDF; protegido por senha, tenta a lembrada e senão pergunta. [password] null =
     * primeira tentativa (sem senha, depois a lembrada).
     */
    private fun openPdf(s: Session, tmp: File, password: String?, wrong: Boolean, remember: Boolean = false) {
        val g = ++gen
        val app = requireContext().applicationContext
        Bg.run({
            if (password == null) {
                try {
                    return@run Opened.Ok(render(s, tmp, null))
                } catch (e: SecurityException) {
                    if (!PdfPassword.supported()) throw e
                }
                // Protegido: a senha lembrada, se houver.
                val saved = PdfPasswords.get(app, s.src, s.item) ?: return@run Opened.NeedPassword(false)
                try {
                    return@run Opened.Ok(render(s, tmp, saved))
                } catch (e: SecurityException) {
                    // A senha do arquivo mudou: esquece a antiga.
                    PdfPasswords.remove(app, s.src, s.item)
                    return@run Opened.NeedPassword(false)
                }
            }
            try {
                val r = render(s, tmp, password)
                if (remember) PdfPasswords.put(app, s.src, s.item, password) else PdfPasswords.remove(app, s.src, s.item)
                Opened.Ok(r)
            } catch (e: SecurityException) {
                Opened.NeedPassword(true)
            }
        }) { res ->
            if (g != gen || view == null) {
                (res.getOrNull() as? Opened.Ok)?.r?.close() // o PdfRenderer fecha o descritor junto
                return@run
            }
            res.onSuccess { o ->
                when (o) {
                    is Opened.Ok -> showPdf(o.r)
                    is Opened.NeedPassword -> askPassword(s, tmp, o.wrong || wrong)
                }
            }.onFailure { fail(it) }
        }
    }

    /** Renderizador sobre um descritor novo (uma tentativa que falha fecha o anterior). */
    private fun render(s: Session, tmp: File, password: String?): PdfRenderer {
        val fd = s.src.openFd(s.item)
            ?: if (tmp.exists()) ParcelFileDescriptor.open(tmp, ParcelFileDescriptor.MODE_READ_ONLY) else copyToTemp(s, tmp)
        try {
            return if (password == null) PdfRenderer(fd) else PdfPassword.open(fd, password)
        } catch (e: Exception) {
            runCatching { fd.close() }
            throw e
        }
    }

    private fun showPdf(r: PdfRenderer) {
        if (r.pageCount == 0) {
            r.close()
            showError(getString(R.string.doc_empty))
            return
        }
        val v = PdfView(requireContext(), r) { page, count ->
            info.text = getString(R.string.doc_page, page + 1, count)
        }
        pdf = v
        attach(v)
        info.text = getString(R.string.doc_pages, v.pageCount)
    }

    /** Pergunta a senha do PDF, com "Lembrar a senha" (cifrada no aparelho). */
    private fun askPassword(s: Session, tmp: File, wrong: Boolean) {
        val ctx = requireContext()
        val pad = (20 * resources.displayMetrics.density).toInt()
        val box = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
        }
        val field = android.widget.EditText(ctx).apply {
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            setHint(R.string.pdf_password_hint)
            isSingleLine = true
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_DONE
            if (wrong) error = getString(R.string.pdf_password_wrong)
        }
        val keep = android.widget.CheckBox(ctx).apply {
            setText(R.string.pdf_password_remember)
            isChecked = true
        }
        box.addView(field)
        box.addView(keep)
        var answered = false
        val dlg = androidx.appcompat.app.AlertDialog.Builder(ctx)
            .setTitle(R.string.pdf_password_title)
            .setMessage(s.item.name)
            .setView(box)
            .setPositiveButton(R.string.open) { _, _ ->
                val pw = field.text.toString()
                if (pw.isEmpty()) return@setPositiveButton
                answered = true
                openPdf(s, tmp, pw, wrong = false, remember = keep.isChecked)
            }
            .setNegativeButton(R.string.cancel, null)
            .setOnDismissListener { if (!answered && view != null) showError(getString(R.string.doc_password_cancelled)) }
            .create()
        field.setOnEditorActionListener { _, _, _ ->
            dlg.getButton(android.content.DialogInterface.BUTTON_POSITIVE)?.performClick()
            true
        }
        dlg.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        dlg.show()
        field.requestFocus()
    }

    /** Sem descritor de arquivo (Drive): cópia temporária, apagada ao fechar. */
    private fun copyToTemp(s: Session, f: File): ParcelFileDescriptor {
        val dir = f.parentFile!!
        dir.mkdirs()
        dir.listFiles()?.forEach { it.delete() }
        // Cópia em .part: uma cópia pela metade não serve para as tentativas de senha.
        val part = File(dir, f.name + ".part")
        s.src.openRead(s.item).use { input -> part.outputStream().use { input.copyTo(it, 256 * 1024) } }
        if (!part.renameTo(f)) throw java.io.IOException("cópia temporária")
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
            e is SecurityException || e.message?.contains("senha") == true ->
                getString(if (PdfPassword.supported()) R.string.doc_password else R.string.doc_password_old)
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
