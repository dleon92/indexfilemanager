package app.index.files.ui.doc

import java.io.InputStream

/** LibreOffice/OpenDocument (odt, ods, odp): tudo vem do content.xml do pacote ZIP. */
object Odf {
    /** Nomes automáticos de slide ("page1", "Slide 3", "Diapositivo2"): só repetem o número. */
    private val GENERIC_SLIDE = Regex("(?i)(page|slide|diapositivo|folha)\\s*\\d+")

    fun open(input: InputStream, kind: DocKind, out: Html) {
        val parts = Zip.read(input) { it == "content.xml" }
        val b = parts["content.xml"] ?: throw java.io.IOException("sem content.xml")
        when (kind) {
            DocKind.ODS -> sheets(b, out)
            else -> text(b, out, slides = kind == DocKind.ODP)
        }
    }

    /** Estilos automáticos: nome → (negrito, itálico, sublinhado). */
    private class Style(var b: Boolean = false, var i: Boolean = false, var u: Boolean = false)

    private fun styles(b: ByteArray): Map<String, Style> {
        val m = HashMap<String, Style>()
        var cur: Style? = null
        var inAuto = false
        Zip.walk(b, { p, n ->
            when (n) {
                "automatic-styles" -> inAuto = true
                "style" -> if (inAuto) cur = Style().also { s -> Zip.attr(p, "name")?.let { m[it] = s } }
                "text-properties" -> cur?.let { s ->
                    if (Zip.attr(p, "font-weight") == "bold") s.b = true
                    if (Zip.attr(p, "font-style") == "italic") s.i = true
                    Zip.attr(p, "text-underline-style")?.let { if (it != "none") s.u = true }
                }
            }
        }, { n ->
            when (n) {
                "automatic-styles" -> inAuto = false
                "style" -> cur = null
            }
        })
        return m
    }

    private fun text(b: ByteArray, out: Html, slides: Boolean) {
        val st = styles(b)
        val para = StringBuilder()
        var level = 0         // text:h → nível
        var listDepth = 0
        var spanStack = ArrayList<String>() // tags de fechamento das spans abertas
        var tableDepth = 0
        var skip = 0          // notas de rodapé, anotações
        var slide = 0
        var inPara = 0
        Zip.walk(b, { p, n ->
            if (skip > 0) {
                if (n == "note" || n == "annotation" || n == "tracked-changes") skip++
                return@walk
            }
            when (n) {
                "note", "annotation", "tracked-changes" -> skip++
                "page" -> if (slides) {
                    slide++
                    out.raw("<div class=\"slide\"><p class=\"no\">Slide $slide").raw(
                        Zip.attr(p, "name")?.takeIf { it.isNotBlank() && !it.matches(GENERIC_SLIDE) }?.let { " · " + Html.esc(it) } ?: "",
                    ).raw("</p>")
                }
                "h", "p" -> {
                    if (inPara == 0) {
                        para.setLength(0)
                        level = if (n == "h") (Zip.attr(p, "outline-level")?.toIntOrNull() ?: 1).coerceIn(1, 6) else 0
                        val s = Zip.attr(p, "style-name")?.let { st[it] }
                        spanStack = ArrayList()
                        if (s != null && level == 0) {
                            if (s.b) { para.append("<b>"); spanStack.add("</b>") }
                            if (s.i) { para.append("<i>"); spanStack.add("</i>") }
                        }
                    }
                    inPara++
                }
                "span" -> {
                    val s = Zip.attr(p, "style-name")?.let { st[it] }
                    val close = StringBuilder()
                    if (s?.b == true) { para.append("<b>"); close.insert(0, "</b>") }
                    if (s?.i == true) { para.append("<i>"); close.insert(0, "</i>") }
                    if (s?.u == true) { para.append("<u>"); close.insert(0, "</u>") }
                    spanStack.add(close.toString())
                }
                "s" -> para.append(" ".repeat((Zip.attr(p, "c")?.toIntOrNull() ?: 1).coerceIn(1, 200)))
                "tab" -> para.append("&emsp;")
                "line-break" -> para.append("<br>")
                "list" -> listDepth++
                "table" -> { tableDepth++; out.raw("<div class=\"sheet\"><table>") }
                "table-row" -> out.raw("<tr>")
                "table-cell" -> out.raw("<td>")
            }
        }, { n ->
            if (skip > 0) {
                if (n == "note" || n == "annotation" || n == "tracked-changes") skip--
                return@walk
            }
            when (n) {
                "span" -> if (spanStack.isNotEmpty()) para.append(spanStack.removeAt(spanStack.size - 1))
                "h", "p" -> {
                    inPara--
                    if (inPara == 0) {
                        while (spanStack.isNotEmpty()) para.append(spanStack.removeAt(spanStack.size - 1))
                        val h = para.toString()
                        val bullet = if (listDepth > 0) "&nbsp;".repeat((listDepth - 1) * 4) + "• " else ""
                        out.raw(when {
                            level > 0 && h.isNotBlank() -> "<h$level>$h</h$level>"
                            tableDepth > 0 -> if (h.isBlank()) "" else "$bullet$h<br>"
                            h.isBlank() -> if (slides) "" else "<p>&nbsp;</p>"
                            else -> "<p>$bullet$h</p>"
                        })
                    }
                }
                "list" -> listDepth--
                "table-cell" -> out.raw("</td>")
                "table-row" -> out.raw("</tr>")
                "table" -> { tableDepth--; out.raw("</table></div>") }
                "page" -> if (slides) out.raw("</div>")
            }
        }, { t -> if (skip == 0 && inPara > 0) para.append(Html.esc(t)) })
    }

    private fun sheets(b: ByteArray, out: Html) {
        val all = ArrayList<Sheet>()
        var sh: Sheet? = null
        var row = 0
        var col = 0
        var rowRepeat = 1
        var cellRepeat = 1
        var value: String? = null
        var numeric = false
        val text = StringBuilder()
        var inCell = false
        var paras = 0
        var skip = 0
        Zip.walk(b, { p, n ->
            if (skip > 0) { if (n == "annotation") skip++; return@walk }
            when (n) {
                "annotation" -> skip++
                "table" -> { sh = Sheet(Zip.attr(p, "name") ?: "Planilha ${all.size + 1}"); row = 0 }
                "table-row" -> { col = 0; rowRepeat = (Zip.attr(p, "number-rows-repeated")?.toIntOrNull() ?: 1) }
                "table-cell", "covered-table-cell" -> {
                    inCell = true
                    paras = 0
                    text.setLength(0)
                    cellRepeat = (Zip.attr(p, "number-columns-repeated")?.toIntOrNull() ?: 1)
                    val type = Zip.attr(p, "value-type")
                    numeric = type == "float" || type == "percentage" || type == "currency"
                    value = when (type) {
                        "float", "currency" -> Zip.attr(p, "value")?.toDoubleOrNull()?.let { Sheet.num(it) }
                        "percentage" -> Zip.attr(p, "value")?.toDoubleOrNull()?.let { Sheet.num(it * 100) + "%" }
                        "date" -> Zip.attr(p, "date-value")?.let { fmtDate(it) }
                        "boolean" -> Zip.attr(p, "boolean-value")?.let { if (it == "true") "VERDADEIRO" else "FALSO" }
                        else -> null
                    }
                }
                "p" -> if (inCell) { if (paras > 0) text.append('\n'); paras++ }
                "s" -> if (inCell) text.append(" ".repeat((Zip.attr(p, "c")?.toIntOrNull() ?: 1).coerceIn(1, 50)))
                "line-break" -> if (inCell) text.append('\n')
            }
        }, { n ->
            if (skip > 0) { if (n == "annotation") skip--; return@walk }
            when (n) {
                "table-cell", "covered-table-cell" -> {
                    inCell = false
                    val v = value ?: text.toString()
                    // Repetições enormes (linhas/colunas vazias até o fim da planilha) só avançam.
                    if (v.isNotEmpty() && cellRepeat <= Sheet.MAX_COLS) {
                        for (r in 0 until minOf(rowRepeat, 50)) for (c in 0 until cellRepeat) sh?.put(row + r, col + c, v, numeric && value != null)
                    }
                    col += cellRepeat
                }
                "table-row" -> row += rowRepeat
                "table" -> { sh?.let { all.add(it) }; sh = null }
            }
        }, { t -> if (inCell && skip == 0) text.append(t) })
        for (s in all) {
            if (out.full) break
            s.render(out, all.size > 1)
        }
    }

    /** "2026-11-18" ou "2026-11-18T10:30:00" → "18/11/2026 10:30". */
    private fun fmtDate(s: String): String {
        val d = s.take(10).split('-')
        if (d.size != 3) return s
        val date = "${d[2]}/${d[1]}/${d[0]}"
        val t = s.substringAfter('T', "").take(5)
        return if (t.isEmpty() || t == "00:00") date else "$date $t"
    }
}
