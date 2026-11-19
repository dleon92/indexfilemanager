package app.index.files.ui.doc

import java.io.InputStream

/** Office atual (docx, xlsx, pptx): ZIP com XML. Texto, formatação básica, tabelas e slides. */
object Ooxml {

    // ---------------- Word (.docx) ----------------

    fun docx(input: InputStream, out: Html) = Docx.render(input, out)

    private fun on(v: String?) = v == null || (v != "0" && v != "false" && v != "off")

    // ---------------- Excel (.xlsx) ----------------

    fun xlsx(input: InputStream, out: Html) {
        val parts = Zip.read(input) {
            it == "xl/workbook.xml" || it == "xl/_rels/workbook.xml.rels" || it == "xl/sharedStrings.xml" ||
                it == "xl/styles.xml" || (it.startsWith("xl/worksheets/") && it.endsWith(".xml"))
        }
        val wb = parts["xl/workbook.xml"] ?: throw java.io.IOException("sem xl/workbook.xml")
        val rels = Zip.rels(parts, "xl/_rels/workbook.xml.rels", "xl")
        val sheets = ArrayList<Pair<String, String>>() // nome, caminho
        var d1904 = false
        Zip.walk(wb, { p, n ->
            when (n) {
                "workbookPr" -> d1904 = on(Zip.attr(p, "date1904")) && Zip.attr(p, "date1904") != null
                "sheet" -> {
                    val name = Zip.attr(p, "name") ?: "Planilha"
                    val path = Zip.attr(p, "id")?.let { rels[it] }
                    if (path != null) sheets.add(name to path)
                }
            }
        })
        val shared = sharedStrings(parts["xl/sharedStrings.xml"])
        val dateXf = dateStyles(parts["xl/styles.xml"])
        for ((name, path) in sheets) {
            if (out.full) break
            val b = parts[path] ?: continue
            val sh = Sheet(name)
            var row = -1; var col = 0; var type: String? = null; var style = 0
            var inV = false; var inIs = false; var inT = false
            val v = StringBuilder()
            var nextCol = 0
            Zip.walk(b, { p, n ->
                when (n) {
                    "row" -> { row = (Zip.attr(p, "r")?.toIntOrNull() ?: (row + 2)) - 1; nextCol = 0 }
                    "c" -> {
                        val r = Zip.attr(p, "r")?.let { Sheet.ref(it) }
                        if (r != null) { row = r.first; col = r.second } else col = nextCol
                        nextCol = col + 1
                        type = Zip.attr(p, "t")
                        style = Zip.attr(p, "s")?.toIntOrNull() ?: 0
                        v.setLength(0)
                    }
                    "v" -> inV = true
                    "is" -> inIs = true
                    "t" -> if (inIs) inT = true
                    "rPh" -> inIs = false // leitura fonética (japonês): fora
                }
            }, { n ->
                when (n) {
                    "v" -> inV = false
                    "t" -> inT = false
                    "is" -> inIs = false
                    "c" -> {
                        val raw = v.toString()
                        when (type) {
                            "s" -> raw.toIntOrNull()?.let { shared.getOrNull(it) }?.let { sh.put(row, col, it) }
                            "inlineStr", "str" -> sh.put(row, col, raw)
                            "b" -> sh.put(row, col, if (raw == "1") "VERDADEIRO" else "FALSO")
                            "e" -> sh.put(row, col, raw)
                            else -> raw.toDoubleOrNull()?.let { d ->
                                if (style in dateXf) sh.put(row, col, Sheet.date(d, d1904)) else sh.put(row, col, Sheet.num(d), true)
                            } ?: sh.put(row, col, raw)
                        }
                    }
                }
            }, { t -> if (inV || inT) v.append(t) })
            sh.render(out, sheets.size > 1)
        }
    }

    private fun sharedStrings(b: ByteArray?): List<String> {
        val list = ArrayList<String>()
        if (b == null) return list
        val cur = StringBuilder()
        var inT = false
        var phonetic = false
        Zip.walk(b, { _, n ->
            when (n) {
                "si" -> cur.setLength(0)
                "rPh" -> phonetic = true
                "t" -> inT = !phonetic
            }
        }, { n ->
            when (n) {
                "t" -> inT = false
                "rPh" -> phonetic = false
                "si" -> list.add(cur.toString())
            }
        }, { t -> if (inT) cur.append(t) })
        return list
    }

    /** Índices de cellXfs cujo formato numérico é de data/hora. */
    private fun dateStyles(b: ByteArray?): Set<Int> {
        val out = HashSet<Int>()
        if (b == null) return out
        val custom = HashMap<Int, String>()
        var inXfs = false
        var idx = 0
        Zip.walk(b, { p, n ->
            when (n) {
                "numFmt" -> {
                    val id = Zip.attr(p, "numFmtId")?.toIntOrNull()
                    val code = Zip.attr(p, "formatCode")
                    if (id != null && code != null) custom[id] = code
                }
                "cellXfs" -> { inXfs = true; idx = 0 }
                "xf" -> if (inXfs) {
                    val id = Zip.attr(p, "numFmtId")?.toIntOrNull() ?: 0
                    if (Sheet.isDateFormat(id, custom[id])) out.add(idx)
                    idx++
                }
            }
        }, { n -> if (n == "cellXfs") inXfs = false })
        return out
    }

    // ---------------- PowerPoint (.pptx) ----------------

    fun pptx(input: InputStream, out: Html) {
        val parts = Zip.read(input) {
            it == "ppt/presentation.xml" || it == "ppt/_rels/presentation.xml.rels" ||
                (it.startsWith("ppt/slides/slide") && it.endsWith(".xml"))
        }
        val pres = parts["ppt/presentation.xml"] ?: throw java.io.IOException("sem ppt/presentation.xml")
        val rels = Zip.rels(parts, "ppt/_rels/presentation.xml.rels", "ppt")
        val order = ArrayList<String>()
        Zip.walk(pres, { p, n -> if (n == "sldId") Zip.attr(p, "id")?.let { rels[it] }?.let { order.add(it) } })
        if (order.isEmpty()) {
            // Sem a lista: em ordem numérica dos arquivos.
            order.addAll(parts.keys.filter { it.startsWith("ppt/slides/slide") }.sortedBy { it.filter(Char::isDigit).toIntOrNull() ?: 0 })
        }
        for ((i, path) in order.withIndex()) {
            if (out.full) break
            val b = parts[path] ?: continue
            out.raw("<div class=\"slide\"><p class=\"no\">Slide ${i + 1}</p>")
            slideText(b, out)
            out.raw("</div>")
        }
    }

    /** Parágrafos (a:p) das caixas de texto; títulos em destaque; tabelas (a:tbl) como tabela. */
    private fun slideText(b: ByteArray, out: Html) {
        val para = StringBuilder()
        var inT = false
        var title = false
        var bold = false
        var inRPr = false
        var inTable = false
        var any = false
        Zip.walk(b, { p, n ->
            when (n) {
                "sp" -> title = false
                "ph" -> { val t = Zip.attr(p, "type"); if (t == "title" || t == "ctrTitle") title = true }
                "p" -> para.setLength(0)
                "r" -> bold = false
                "rPr" -> { inRPr = true; bold = Zip.attr(p, "b") == "1" }
                "t" -> inT = true
                "br" -> para.append("<br>")
                "tbl" -> { inTable = true; out.raw("<div class=\"sheet\"><table>") }
                "tr" -> out.raw("<tr>")
                "tc" -> out.raw("<td>")
            }
        }, { n ->
            when (n) {
                "rPr" -> inRPr = false
                "t" -> inT = false
                "p" -> {
                    val h = para.toString()
                    if (h.isNotBlank()) {
                        any = true
                        out.raw(when {
                            inTable -> "$h<br>"
                            title -> "<h3>$h</h3>"
                            else -> "<p>$h</p>"
                        })
                    }
                }
                "tc" -> out.raw("</td>")
                "tr" -> out.raw("</tr>")
                "tbl" -> { inTable = false; out.raw("</table></div>") }
            }
        }, { t ->
            if (inT && t.isNotEmpty()) {
                val e = Html.esc(t)
                para.append(if (bold && !title) "<b>$e</b>" else e)
            }
        })
        if (!any) out.raw("<p class=\"note\">(sem texto)</p>")
    }
}
