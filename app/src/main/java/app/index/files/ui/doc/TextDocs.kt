package app.index.files.ui.doc

import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/** Texto puro, código, CSV/TSV, Markdown, RTF e HTML. */
object TextDocs {
    /** Lê até [max] bytes. O segundo valor diz se o arquivo era maior. */
    fun readCapped(input: InputStream, max: Int): Pair<ByteArray, Boolean> {
        val buf = java.io.ByteArrayOutputStream(minOf(max, 256 * 1024))
        val chunk = ByteArray(64 * 1024)
        var total = 0
        input.use {
            while (total < max) {
                val n = it.read(chunk, 0, minOf(chunk.size, max - total))
                if (n < 0) return buf.toByteArray() to false
                buf.write(chunk, 0, n)
                total += n
            }
            return buf.toByteArray() to (it.read() >= 0)
        }
    }

    /** BOM (UTF-8/16) → UTF-8 válido → Windows-1252 (arquivos antigos do Windows). */
    fun decode(b: ByteArray): String {
        if (b.size >= 3 && b[0] == 0xEF.toByte() && b[1] == 0xBB.toByte() && b[2] == 0xBF.toByte()) {
            return String(b, 3, b.size - 3, Charsets.UTF_8)
        }
        if (b.size >= 2 && b[0] == 0xFF.toByte() && b[1] == 0xFE.toByte()) return String(b, 2, b.size - 2, Charsets.UTF_16LE)
        if (b.size >= 2 && b[0] == 0xFE.toByte() && b[1] == 0xFF.toByte()) return String(b, 2, b.size - 2, Charsets.UTF_16BE)
        return try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(b, 0, cutUtf8(b))).toString()
        } catch (e: CharacterCodingException) {
            String(b, Charset.forName("windows-1252"))
        }
    }

    /** Não corta um caractere UTF-8 no meio (arquivo lido só até o limite). */
    private fun cutUtf8(b: ByteArray): Int {
        var i = b.size
        var k = 0
        while (i > 0 && k < 4 && (b[i - 1].toInt() and 0xC0) == 0x80) { i--; k++ }
        if (i > 0 && (b[i - 1].toInt() and 0xC0) == 0xC0) {
            val lead = b[i - 1].toInt() and 0xFF
            val need = when { lead >= 0xF0 -> 4; lead >= 0xE0 -> 3; else -> 2 }
            return if (k + 1 >= need) b.size else i - 1
        }
        return b.size
    }

    fun plain(text: String, out: Html) {
        out.raw("<pre>").text(text).raw("</pre>")
    }

    /** CSV/TSV numa tabela. Aspas com vírgulas, quebras de linha e "" escapado. */
    fun table(text: String, sep: Char, out: Html, maxRows: Int = 5000) {
        out.raw("<div class=\"sheet\"><table>")
        var row = ArrayList<String>()
        val cell = StringBuilder()
        var quoted = false
        var rows = 0
        var i = 0
        fun endCell() { row.add(cell.toString()); cell.setLength(0) }
        fun endRow() {
            endCell()
            if (!(row.size == 1 && row[0].isEmpty())) {
                out.raw(if (rows == 0) "<tr>" else "<tr>")
                for (c in row) {
                    val tagName = if (rows == 0) "th" else "td"
                    val num = rows > 0 && c.isNotEmpty() && c.trim().toDoubleOrNull() != null
                    out.raw(if (num) "<$tagName class=\"n\">" else "<$tagName>").text(c).raw("</$tagName>")
                }
                out.raw("</tr>")
                rows++
            }
            row = ArrayList()
        }
        while (i < text.length && rows < maxRows && !out.full) {
            val ch = text[i]
            if (quoted) {
                if (ch == '"') {
                    if (i + 1 < text.length && text[i + 1] == '"') { cell.append('"'); i++ } else quoted = false
                } else {
                    cell.append(ch)
                }
            } else when (ch) {
                '"' -> if (cell.isEmpty()) quoted = true else cell.append(ch)
                sep -> endCell()
                '\r' -> {}
                '\n' -> endRow()
                else -> cell.append(ch)
            }
            i++
        }
        if (cell.isNotEmpty() || row.isNotEmpty()) endRow()
        out.raw("</table></div>")
        if (i < text.length) out.raw("<p class=\"note\">Mostrando as primeiras $rows linhas.</p>")
    }

    // ---- Markdown (o essencial: títulos, ênfase, listas, citações, código, tabelas, links) ----

    fun markdown(text: String, out: Html) {
        val lines = text.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        var i = 0
        val para = StringBuilder()
        var list: String? = null // "ul" / "ol" aberta
        fun flushPara() {
            if (para.isNotEmpty()) {
                out.raw("<p>").raw(inline(para.toString())).raw("</p>")
                para.setLength(0)
            }
        }
        fun closeList() {
            if (list != null) { out.raw("</$list>"); list = null }
        }
        while (i < lines.size && !out.full) {
            val line = lines[i]
            val t = line.trim()
            when {
                t.startsWith("```") || t.startsWith("~~~") -> {
                    flushPara(); closeList()
                    val fence = t.take(3)
                    val code = StringBuilder()
                    i++
                    while (i < lines.size && !lines[i].trim().startsWith(fence)) {
                        code.append(lines[i]).append('\n')
                        i++
                    }
                    out.raw("<pre class=\"code\">").text(code.toString().trimEnd('\n')).raw("</pre>")
                }
                t.isEmpty() -> { flushPara(); closeList() }
                HEADING.matches(t) -> {
                    flushPara(); closeList()
                    val m = HEADING.find(t)!!
                    val n = m.groupValues[1].length
                    out.raw("<h$n>").raw(inline(m.groupValues[2].trimEnd('#', ' '))).raw("</h$n>")
                }
                t.matches(Regex("^(\\*\\s*){3,}$|^(-\\s*){3,}$|^(_\\s*){3,}$")) -> { flushPara(); closeList(); out.raw("<hr>") }
                t.startsWith(">") -> {
                    flushPara(); closeList()
                    val q = StringBuilder()
                    while (i < lines.size && lines[i].trim().startsWith(">")) {
                        q.append(lines[i].trim().removePrefix(">").trim()).append(' ')
                        i++
                    }
                    out.raw("<blockquote>").raw(inline(q.toString().trim())).raw("</blockquote>")
                    continue
                }
                t.startsWith("|") && i + 1 < lines.size && lines[i + 1].trim().matches(Regex("^\\|?\\s*:?-{2,}.*")) -> {
                    flushPara(); closeList()
                    out.raw("<div class=\"sheet\"><table>")
                    fun cells(s: String) = s.trim().trim('|').split('|').map { it.trim() }
                    out.raw("<tr>"); for (c in cells(t)) out.raw("<th>").raw(inline(c)).raw("</th>"); out.raw("</tr>")
                    i += 2
                    while (i < lines.size && lines[i].trim().startsWith("|")) {
                        out.raw("<tr>"); for (c in cells(lines[i])) out.raw("<td>").raw(inline(c)).raw("</td>"); out.raw("</tr>")
                        i++
                    }
                    out.raw("</table></div>")
                    continue
                }
                BULLET.matches(line) || NUMBERED.matches(line) -> {
                    flushPara()
                    val ordered = NUMBERED.matches(line)
                    val want = if (ordered) "ol" else "ul"
                    if (list != want) { closeList(); out.raw("<$want>"); list = want }
                    val content = (if (ordered) NUMBERED else BULLET).find(line)!!.groupValues[1]
                    val box = when {
                        content.startsWith("[ ] ") -> "☐ " + content.drop(4)
                        content.startsWith("[x] ", true) -> "☑ " + content.drop(4)
                        else -> content
                    }
                    out.raw("<li>").raw(inline(box)).raw("</li>")
                }
                line.startsWith("    ") || line.startsWith("\t") -> {
                    flushPara(); closeList()
                    val code = StringBuilder()
                    while (i < lines.size && (lines[i].startsWith("    ") || lines[i].startsWith("\t") || lines[i].isBlank())) {
                        code.append(lines[i].removePrefix("    ").removePrefix("\t")).append('\n')
                        i++
                    }
                    out.raw("<pre class=\"code\">").text(code.toString().trimEnd('\n')).raw("</pre>")
                    continue
                }
                else -> {
                    closeList()
                    if (para.isNotEmpty()) para.append(if (line.endsWith("  ")) "\n" else " ")
                    para.append(t)
                }
            }
            i++
        }
        flushPara(); closeList()
    }

    /** Ênfase, código e links numa linha (o texto é escapado antes de virar marcação). */
    private fun inline(s: String): String {
        val code = ArrayList<String>()
        // Código primeiro: nada dentro dele é interpretado.
        var t = Regex("`([^`]+)`").replace(s) { m -> code.add(m.groupValues[1]); "\u0001${code.size - 1}\u0001" }
        t = Html.esc(t)
        t = Regex("!\\[([^\\]]*)]\\(([^)]*)\\)").replace(t) { m -> "[imagem: ${m.groupValues[1]}]" }
        t = Regex("\\[([^\\]]+)]\\(([^)\\s]+)[^)]*\\)").replace(t) { m -> "<a>${m.groupValues[1]}</a>" }
        t = Regex("\\*\\*(.+?)\\*\\*|__(.+?)__").replace(t) { m -> "<b>${m.groupValues[1].ifEmpty { m.groupValues[2] }}</b>" }
        t = Regex("(?<![\\w*])\\*(?!\\s)(.+?)(?<!\\s)\\*(?![\\w*])|(?<![\\w_])_(?!\\s)(.+?)(?<!\\s)_(?![\\w_])")
            .replace(t) { m -> "<i>${m.groupValues[1].ifEmpty { m.groupValues[2] }}</i>" }
        t = Regex("~~(.+?)~~").replace(t) { m -> "<s>${m.groupValues[1]}</s>" }
        t = t.replace("\n", "<br>")
        return Regex("\u0001(\\d+)\u0001").replace(t) { m -> "<code>${Html.esc(code[m.groupValues[1].toInt()])}</code>" }
    }

    private val HEADING = Regex("^(#{1,6})\\s+(.*)$")
    private val BULLET = Regex("^\\s{0,6}[-*+]\\s+(.*)$")
    private val NUMBERED = Regex("^\\s{0,6}\\d{1,9}[.)]\\s+(.*)$")

    // ---- RTF: só o texto (parágrafos, tabulações, acentos \'hh e \uN) ----

    fun rtf(b: ByteArray, out: Html) {
        val s = String(b, Charsets.ISO_8859_1)
        val text = StringBuilder()
        val cp1252 = Charset.forName("windows-1252")
        var i = 0
        var depth = 0
        var skipDepth = -1 // grupo ignorado (\*, fonttbl, colortbl, stylesheet, info, pict)
        var uc = 1
        var pendingSkip = 0
        while (i < s.length) {
            val ch = s[i]
            when {
                ch == '{' -> { depth++; i++ }
                ch == '}' -> { if (depth == skipDepth) skipDepth = -1; depth--; i++ }
                ch == '\\' && i + 1 < s.length -> {
                    val nx = s[i + 1]
                    if (nx == '\'' && i + 3 < s.length) {
                        if (skipDepth < 0 && pendingSkip == 0) {
                            s.substring(i + 2, i + 4).toIntOrNull(16)?.let { text.append(String(byteArrayOf(it.toByte()), cp1252)) }
                        } else if (pendingSkip > 0) pendingSkip--
                        i += 4
                    } else if (!nx.isLetter()) {
                        if (skipDepth < 0) when (nx) {
                            '*' -> skipDepth = depth
                            '\\', '{', '}' -> text.append(nx)
                            '~' -> text.append(' ')
                            '-', '_' -> {}
                            '\n', '\r' -> text.append('\n')
                        }
                        i += 2
                    } else {
                        var j = i + 1
                        while (j < s.length && s[j].isLetter()) j++
                        val word = s.substring(i + 1, j)
                        var k = j
                        if (k < s.length && (s[k] == '-' || s[k].isDigit())) {
                            k++
                            while (k < s.length && s[k].isDigit()) k++
                        }
                        val num = s.substring(j, k).toIntOrNull()
                        if (k < s.length && s[k] == ' ') k++
                        i = k
                        if (skipDepth >= 0) continue
                        when (word) {
                            "par", "line", "row" -> text.append('\n')
                            "sect", "page" -> text.append("\n\n")
                            "tab" -> text.append('\t')
                            "cell" -> text.append(CELL)
                            "uc" -> uc = num ?: 1
                            "u" -> {
                                num?.let { text.append((if (it < 0) it + 65536 else it).toChar()) }
                                pendingSkip = uc
                            }
                            "emdash" -> text.append('—')
                            "endash" -> text.append('–')
                            "bullet" -> text.append('•')
                            "lquote" -> text.append('‘')
                            "rquote" -> text.append('’')
                            "ldblquote" -> text.append('“')
                            "rdblquote" -> text.append('”')
                            "fonttbl", "colortbl", "stylesheet", "info", "pict", "object", "header", "footer",
                            "headerl", "headerr", "footerl", "footerr", "listtable", "listoverridetable",
                            "themedata", "colorschememapping", "latentstyles", "datastore", "xmlnstbl",
                            "rsidtbl", "generator", "filetbl", "revtbl",
                            -> skipDepth = depth
                        }
                    }
                }
                ch == '\r' || ch == '\n' -> i++
                else -> {
                    if (skipDepth < 0) {
                        if (pendingSkip > 0) pendingSkip-- else text.append(ch)
                    }
                    i++
                }
            }
        }
        paragraphs(text.toString(), out)
    }

    /** Marca de fim de célula no texto extraído de .doc e .rtf. */
    const val CELL = '\u0007'

    /**
     * Texto de .doc/.rtf em HTML: um parágrafo por linha; linhas com [CELL] são linhas de tabela,
     * e as seguidas viram uma tabela só. Form feed sozinho na linha = quebra de página.
     */
    fun paragraphs(text: String, out: Html) {
        var inTable = false
        for (line in text.split('\n')) {
            if (out.full) break
            val row = line.indexOf(CELL) >= 0
            if (row != inTable) { out.raw(if (row) "<div class=\"sheet\"><table>" else "</table></div>"); inTable = row }
            when {
                row -> {
                    out.raw("<tr>")
                    for (c in line.trimEnd(CELL).split(CELL)) out.raw("<td>").text(c.replace("\t", "    ")).raw("</td>")
                    out.raw("</tr>")
                }
                line == "\u000C" -> out.raw("<hr>")
                line.isBlank() -> out.raw("<p>&nbsp;</p>")
                else -> out.raw("<p>").text(line.replace("\t", "    ")).raw("</p>")
            }
        }
        if (inTable) out.raw("</table></div>")
    }
}
