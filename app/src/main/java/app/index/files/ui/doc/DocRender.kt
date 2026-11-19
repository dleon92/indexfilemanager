package app.index.files.ui.doc

import android.content.Context
import app.index.files.data.FileItem
import app.index.files.data.FileSource
import java.io.IOException

/** Converte um documento (menos PDF) no HTML do leitor. Fora da main thread. */
object DocRender {
    private const val TEXT_MAX = 4 * 1024 * 1024
    private const val BINARY_MAX = 64 * 1024 * 1024

    fun html(ctx: Context, src: FileSource, item: FileItem, kind: DocKind, css: Html.Css): String {
        // Word com imagens embutidas precisa de mais espaço que o texto puro.
        val out = Html(if (kind == DocKind.DOCX || kind == DocKind.DOC) 16_000_000 else 6_000_000)
        var word = kind == DocKind.DOCX
        when (kind) {
            DocKind.TEXT, DocKind.MARKDOWN, DocKind.CSV, DocKind.TSV, DocKind.HTML -> {
                val (bytes, more) = TextDocs.readCapped(src.openRead(item), TEXT_MAX)
                val text = TextDocs.decode(bytes)
                when (kind) {
                    // Página HTML: como está (o WebView não roda scripts nem busca nada na rede).
                    DocKind.HTML -> return text
                    DocKind.MARKDOWN -> TextDocs.markdown(text, out)
                    DocKind.CSV -> TextDocs.table(text, sniffSep(text), out)
                    DocKind.TSV -> TextDocs.table(text, '\t', out)
                    else -> TextDocs.plain(text, out)
                }
                if (more) out.raw("<p class=\"note\">Arquivo grande: mostrando os primeiros 4 MB.</p>")
            }
            DocKind.RTF -> {
                val (bytes, _) = TextDocs.readCapped(src.openRead(item), BINARY_MAX)
                TextDocs.rtf(bytes, out)
            }
            DocKind.DOCX -> src.openRead(item).use { Ooxml.docx(it, out) }
            DocKind.XLSX -> src.openRead(item).use { Ooxml.xlsx(it, out) }
            DocKind.PPTX -> src.openRead(item).use { Ooxml.pptx(it, out) }
            DocKind.ODT, DocKind.ODS, DocKind.ODP -> src.openRead(item).use { Odf.open(it, kind, out) }
            DocKind.DOC, DocKind.XLS, DocKind.PPT -> {
                val (bytes, more) = TextDocs.readCapped(src.openRead(item), BINARY_MAX)
                if (more) throw IOException("arquivo grande demais")
                // Um .doc que na verdade é RTF ou HTML (comum em arquivos antigos).
                val head = String(bytes, 0, minOf(16, bytes.size), Charsets.ISO_8859_1)
                when {
                    head.startsWith("{\\rtf") -> TextDocs.rtf(bytes, out)
                    head.startsWith("PK") -> {
                        val stream = bytes.inputStream()
                        when (kind) {
                            DocKind.DOC -> { word = true; Ooxml.docx(stream, out) }
                            DocKind.XLS -> Ooxml.xlsx(stream, out)
                            else -> Ooxml.pptx(stream, out)
                        }
                    }
                    head.trimStart().startsWith("<") -> return TextDocs.decode(bytes)
                    kind == DocKind.DOC -> if (Legacy.doc(bytes, out)) word = true
                    kind == DocKind.XLS -> Legacy.xls(bytes, out)
                    else -> Legacy.ppt(bytes, out)
                }
                if (!word) out.raw("<p class=\"note\">Formato antigo do Office: só o texto é mostrado.</p>")
            }
            DocKind.PDF -> throw IllegalArgumentException("PDF não passa por aqui")
        }
        if (out.length == 0) out.raw("<p class=\"note\">Documento sem texto.</p>")
        val extra = when {
            kind == DocKind.TEXT -> "body{font:14px/1.45 monospace}pre{white-space:pre-wrap}"
            word -> Docx.CSS
            else -> ""
        }
        return out.build(css, extra)
    }

    /** CSV brasileiro costuma vir com ";" (a vírgula é decimal). */
    private fun sniffSep(text: String): Char {
        val line = text.lineSequence().firstOrNull { it.isNotBlank() } ?: return ','
        val semi = line.count { it == ';' }
        val comma = line.count { it == ',' }
        val tab = line.count { it == '\t' }
        return when {
            tab > semi && tab > comma -> '\t'
            semi > comma -> ';'
            else -> ','
        }
    }

}
