package app.index.files.ui.doc

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.Charset

/**
 * Office 97–2003 (.doc, .xls, .ppt). Word com formatação pelo [DocFmt] (cai no texto puro se a
 * estrutura não for entendida); Excel só células; PowerPoint só texto. Sem imagens.
 */
object Legacy {
    private val CP1252: Charset = Charset.forName("windows-1252")

    private fun le(b: ByteArray) = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)

    // ---------------- Word 97 (.doc) ----------------

    /** true = com formatação ([DocFmt]); false = só o texto (estrutura que o leitor formatado não entendeu). */
    fun doc(data: ByteArray, out: Html): Boolean {
        val ole = Ole2(data)
        val wd = ole.stream("WordDocument") ?: throw IOException("sem WordDocument")
        val w = le(wd)
        if (wd.size < 0x200) throw IOException("doc curto demais")
        val flags = w.getShort(0x0A).toInt()
        if (flags and 0x0100 != 0) throw IOException("documento protegido por senha")
        val table = ole.stream(if (flags and 0x0200 != 0) "1Table" else "0Table") ?: throw IOException("sem tabela")
        // FIB: tamanhos variáveis antes do bloco fc/lcb; fcClx é o par nº 33.
        var o = 32
        val csw = w.getShort(o).toInt() and 0xFFFF
        o += 2 + csw * 2
        val cslw = w.getShort(o).toInt() and 0xFFFF
        val ccpText = if (cslw > 3) w.getInt(o + 2 + 3 * 4) else -1
        o += 2 + cslw * 4
        o += 2 // cbRgFcLcb
        val fcClx = w.getInt(o + 33 * 8)
        val lcbClx = w.getInt(o + 33 * 8 + 4)
        if (ccpText > 0 && lcbClx > 0) {
            val html = runCatching { DocFmt.render(wd, table, o, ccpText, fcClx, lcbClx) }
                .onFailure { android.util.Log.w("Index", "doc: formatação não lida", it) }
                .getOrNull()
            if (html != null && html.length > 30) {
                out.raw(html)
                return true
            }
        }
        val text = StringBuilder()
        if (lcbClx > 0 && fcClx >= 0 && fcClx + lcbClx <= table.size) {
            pieces(table, fcClx, lcbClx, wd, text)
        } else {
            // Sem tabela de peças (Word 6/95): texto simples a partir de fcMin.
            val fcMin = w.getInt(0x18)
            val fcMac = w.getInt(0x1C)
            if (fcMin in 0 until fcMac && fcMac <= wd.size) text.append(String(wd, fcMin, fcMac - fcMin, CP1252))
        }
        render(clean(text), out)
        return false
    }

    private fun pieces(table: ByteArray, fc: Int, lcb: Int, wd: ByteArray, text: StringBuilder) {
        val t = le(table)
        var i = fc
        val end = fc + lcb
        // Clx: blocos Prc (0x01) antes do Pcdt (0x02).
        while (i < end && table[i].toInt() == 1) {
            val cb = t.getShort(i + 1).toInt() and 0xFFFF
            i += 3 + cb
        }
        if (i >= end || table[i].toInt() != 2) return
        val lcbPlc = t.getInt(i + 1)
        val plc = i + 5
        val n = (lcbPlc - 4) / 12
        if (n <= 0 || plc + lcbPlc > table.size) return
        for (k in 0 until n) {
            val cpStart = t.getInt(plc + k * 4)
            val cpEnd = t.getInt(plc + (k + 1) * 4)
            val pcd = plc + (n + 1) * 4 + k * 8
            val fcRaw = t.getInt(pcd + 2)
            val chars = cpEnd - cpStart
            if (chars <= 0) continue
            if (fcRaw and 0x40000000 != 0) {
                val off = (fcRaw and 0x3FFFFFFF) / 2
                if (off + chars <= wd.size) text.append(String(wd, off, chars, CP1252))
            } else {
                if (fcRaw + chars * 2 <= wd.size) text.append(String(wd, fcRaw, chars * 2, Charsets.UTF_16LE))
            }
            if (text.length > 4_000_000) break
        }
    }

    /** Marcas especiais do Word: fim de parágrafo/célula, campos (código escondido), objetos. */
    private fun clean(s: CharSequence): String {
        val sb = StringBuilder(s.length)
        var field = 0      // dentro do código de um campo (entre 0x13 e 0x14)
        val stack = ArrayList<Boolean>() // por campo aberto: já passou do separador?
        var prev = 0
        for (ch in s) {
            val wasCell = prev == 0x07
            prev = ch.code
            when (ch.code) {
                0x13 -> { stack.add(false); field++ }
                0x14 -> if (stack.isNotEmpty() && !stack.last()) { stack[stack.size - 1] = true; field-- }
                0x15 -> if (stack.isNotEmpty()) { if (!stack.removeAt(stack.size - 1)) field--; }
                else -> if (field <= 0) when (ch.code) {
                    0x0D -> sb.append('\n')
                    // Fim de célula; duas marcas seguidas = fim da linha da tabela.
                    0x07 -> if (wasCell) { sb.setLength(sb.length - 1); sb.append('\n'); prev = 0 } else sb.append(TextDocs.CELL)
                    0x0B -> sb.append('\n')         // quebra de linha manual
                    0x0C -> sb.append("\n\u000C\n") // quebra de página
                    0x09 -> sb.append('\t')
                    0x01, 0x08, 0x02, 0x05 -> {}    // figuras, objetos, notas
                    0x1E -> sb.append('-')
                    0x1F -> {}
                    0xA0 -> sb.append(' ')
                    else -> if (ch.code >= 0x20) sb.append(ch)
                }
            }
        }
        return sb.toString()
    }

    private fun render(text: String, out: Html) = TextDocs.paragraphs(text, out)

    // ---------------- Excel 97 (.xls, BIFF8) ----------------

    fun xls(data: ByteArray, out: Html) {
        val ole = Ole2(data)
        val wb = ole.stream("Workbook") ?: ole.stream("Book") ?: throw IOException("sem Workbook")
        val b = le(wb)
        val sheetNames = ArrayList<Pair<Int, String>>() // posição do BOF, nome
        val sst = ArrayList<String>()
        val xfFmt = ArrayList<Int>()
        val customFmt = HashMap<Int, String>()
        var d1904 = false
        // 1ª passada: globais (SST, BOUNDSHEET, formatos) até o primeiro EOF.
        var p = 0
        var sstRecs: ArrayList<ByteArray>? = null
        while (p + 4 <= wb.size) {
            val type = b.getShort(p).toInt() and 0xFFFF
            val len = b.getShort(p + 2).toInt() and 0xFFFF
            val d = p + 4
            if (d + len > wb.size) break
            when (type) {
                0x0085 -> { // BOUNDSHEET
                    val pos = b.getInt(d)
                    val nameLen = wb[d + 6].toInt() and 0xFF
                    val wide = wb[d + 7].toInt() and 1
                    val name = if (wide == 1) String(wb, d + 8, nameLen * 2, Charsets.UTF_16LE) else String(wb, d + 8, nameLen, CP1252)
                    if (wb[d + 5].toInt() == 0) sheetNames.add(pos to name) // só planilhas (não gráficos/macros)
                }
                0x00FC -> sstRecs = arrayListOf(wb.copyOfRange(d, d + len))
                0x003C -> sstRecs?.add(wb.copyOfRange(d, d + len))
                0x041E -> { // FORMAT
                    val id = b.getShort(d).toInt() and 0xFFFF
                    readXlString(wb, d + 2, true)?.let { customFmt[id] = it.first }
                }
                0x00E0 -> xfFmt.add(b.getShort(d + 2).toInt() and 0xFFFF) // XF → numFmt
                0x0022 -> d1904 = (b.getShort(d).toInt() and 1) == 1
                0x000A -> break // EOF dos globais
                else -> if (type != 0x003C) sstRecs?.let { if (it.isNotEmpty()) { parseSst(it, sst); sstRecs = null } }
            }
            p = d + len
        }
        sstRecs?.let { parseSst(it, sst) }
        val dateXf = xfFmt.indices.filter { Sheet.isDateFormat(xfFmt[it], customFmt[xfFmt[it]]) }.toSet()

        for ((pos, name) in sheetNames) {
            if (out.full) break
            val sh = Sheet(name)
            var q = pos
            var first = true
            while (q + 4 <= wb.size) {
                val type = b.getShort(q).toInt() and 0xFFFF
                val len = b.getShort(q + 2).toInt() and 0xFFFF
                val d = q + 4
                if (d + len > wb.size) break
                if (type == 0x0809 && !first) break
                first = false
                if (type == 0x000A) break
                if (len >= 6) {
                    val row = b.getShort(d).toInt() and 0xFFFF
                    val col = b.getShort(d + 2).toInt() and 0xFFFF
                    val xf = b.getShort(d + 4).toInt() and 0xFFFF
                    fun putNum(v: Double, xfi: Int, c: Int) {
                        if (xfi in dateXf) sh.put(row, c, Sheet.date(v, d1904)) else sh.put(row, c, Sheet.num(v), true)
                    }
                    when (type) {
                        0x00FD -> sst.getOrNull(b.getInt(d + 6))?.let { sh.put(row, col, it) } // LABELSST
                        0x0203 -> putNum(b.getDouble(d + 6), xf, col)                            // NUMBER
                        0x027E -> putNum(rk(b.getInt(d + 6)), xf, col)                          // RK
                        0x00BD -> {                                                              // MULRK
                            val last = b.getShort(d + len - 2).toInt() and 0xFFFF
                            for ((k, c) in (col..last).withIndex()) {
                                val o = d + 4 + k * 6
                                if (o + 6 > d + len - 2) break
                                putNum(rk(b.getInt(o + 2)), b.getShort(o).toInt() and 0xFFFF, c)
                            }
                        }
                        0x0204 -> readXlString(wb, d + 6, true)?.let { sh.put(row, col, it.first) } // LABEL
                        0x0006 -> {                                                              // FORMULA (resultado guardado)
                            val r = wb.copyOfRange(d + 6, d + 14)
                            if ((r[6] == 0xFF.toByte()) && (r[7] == 0xFF.toByte())) {
                                when (r[0].toInt()) {
                                    0 -> { // texto no registro STRING seguinte
                                        val nq = d + len
                                        if (nq + 4 <= wb.size && (b.getShort(nq).toInt() and 0xFFFF) == 0x0207) {
                                            readXlString(wb, nq + 4, true)?.let { sh.put(row, col, it.first) }
                                        }
                                    }
                                    1 -> sh.put(row, col, if (r[2].toInt() != 0) "VERDADEIRO" else "FALSO")
                                }
                            } else {
                                putNum(le(r).getDouble(0), xf, col)
                            }
                        }
                        0x0205 -> if (wb[d + 7].toInt() == 0) sh.put(row, col, if (wb[d + 6].toInt() != 0) "VERDADEIRO" else "FALSO")
                    }
                }
                q = d + len
            }
            sh.render(out, sheetNames.size > 1)
        }
    }

    private fun rk(v: Int): Double {
        var d = if (v and 2 != 0) (v shr 2).toDouble() else java.lang.Double.longBitsToDouble((v.toLong() and 0xFFFFFFFCL) shl 32)
        if (v and 1 != 0) d /= 100
        return d
    }

    /** String do BIFF8 com tamanho de 16 bits: (texto, bytes consumidos). */
    private fun readXlString(b: ByteArray, at: Int, len16: Boolean): Pair<String, Int>? {
        if (at + 3 > b.size) return null
        val n = if (len16) (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8) else b[at].toInt() and 0xFF
        val f = at + if (len16) 2 else 1
        val opt = b[f].toInt()
        var p = f + 1
        if (opt and 8 != 0) p += 2
        if (opt and 4 != 0) p += 4
        val wide = opt and 1 != 0
        val bytes = if (wide) n * 2 else n
        if (p + bytes > b.size) return null
        val s = if (wide) String(b, p, bytes, Charsets.UTF_16LE) else String(b, p, bytes, CP1252)
        return s to (p + bytes - at)
    }

    /**
     * SST (strings compartilhadas) espalhado por CONTINUE: uma string pode começar num registro
     * e terminar no próximo, inclusive trocando entre 8 e 16 bits por caractere.
     */
    private fun parseSst(recs: List<ByteArray>, out: MutableList<String>) {
        var ri = 0
        var buf = recs[0]
        var p = 8 // total, únicas
        val unique = le(buf).getInt(4)
        fun ensure(n: Int): Boolean {
            while (p + n > buf.size) {
                if (p < buf.size || ri + 1 >= recs.size) return p + n <= buf.size
                ri++; buf = recs[ri]; p = 0
            }
            return true
        }
        repeat(unique) {
            if (!ensure(3)) return
            val n = (buf[p].toInt() and 0xFF) or ((buf[p + 1].toInt() and 0xFF) shl 8)
            val opt = buf[p + 2].toInt()
            p += 3
            var runs = 0
            var ext = 0
            if (opt and 8 != 0) { if (!ensure(2)) return; runs = (buf[p].toInt() and 0xFF) or ((buf[p + 1].toInt() and 0xFF) shl 8); p += 2 }
            if (opt and 4 != 0) { if (!ensure(4)) return; ext = le(buf).getInt(p); p += 4 }
            var wide = opt and 1 != 0
            val sb = StringBuilder(n)
            while (sb.length < n) {
                if (p >= buf.size) {
                    if (ri + 1 >= recs.size) break
                    ri++; buf = recs[ri]
                    wide = buf[0].toInt() and 1 != 0 // CONTINUE começa com o byte de opções
                    p = 1
                }
                val left = n - sb.length
                if (wide) {
                    val k = minOf(left, (buf.size - p) / 2)
                    sb.append(String(buf, p, k * 2, Charsets.UTF_16LE)); p += k * 2
                    if (k == 0) p = buf.size
                } else {
                    val k = minOf(left, buf.size - p)
                    sb.append(String(buf, p, k, CP1252)); p += k
                }
            }
            out.add(sb.toString())
            // Pula formatação e dados estendidos (podem atravessar registros).
            var skip = runs * 4 + ext
            while (skip > 0) {
                if (p >= buf.size) { if (ri + 1 >= recs.size) return; ri++; buf = recs[ri]; p = 0 }
                val k = minOf(skip, buf.size - p)
                p += k; skip -= k
            }
        }
    }

    // ---------------- PowerPoint 97 (.ppt) ----------------

    fun ppt(data: ByteArray, out: Html) {
        val ole = Ole2(data)
        val s = ole.stream("PowerPoint Document") ?: throw IOException("sem PowerPoint Document")
        val b = le(s)
        val slides = ArrayList<MutableList<String>>()
        var cur: MutableList<String>? = null
        // Percorre os registros; contêineres (versão 0xF) são abertos, os átomos de texto lidos.
        fun walk(start: Int, end: Int, depth: Int, inMaster: Boolean, inNotes: Boolean) {
            var p = start
            while (p + 8 <= end) {
                val ver = b.getShort(p).toInt() and 0xF
                val type = b.getShort(p + 2).toInt() and 0xFFFF
                val len = b.getInt(p + 4)
                val d = p + 8
                if (len < 0 || d + len > end) break
                when {
                    type == 0x03EE -> { cur = ArrayList<String>().also { slides.add(it) }; walk(d, d + len, depth + 1, inMaster, inNotes) } // Slide
                    type == 0x03F8 || type == 0x0FC3 -> {} // MainMaster / Handout: modelos, fora
                    type == 0x03F0 -> {} // Notes: fora
                    type == 0x03F3 && depth > 0 -> cur = ArrayList<String>().also { slides.add(it) } // SlidePersistAtom (lista de textos)
                    ver == 0xF && depth < 16 -> walk(d, d + len, depth + 1, inMaster, inNotes)
                    type == 0x0FA0 -> cur = add(cur, String(s, d, len, Charsets.UTF_16LE), slides) // TextCharsAtom
                    type == 0x0FA8 -> cur = add(cur, String(s, d, len, CP1252), slides) // TextBytesAtom
                }
                p = d + len
            }
        }
        walk(0, s.size, 0, false, false)
        // Os mesmos textos aparecem na lista do documento e nos slides: some com os slides vazios e repetidos.
        val seen = HashSet<String>()
        var n = 0
        for (sl in slides) {
            val lines = sl.filter { it.isNotBlank() && seen.add(it) }
            if (lines.isEmpty()) continue
            if (out.full) break
            n++
            out.raw("<div class=\"slide\"><p class=\"no\">Slide $n</p>")
            for ((i, t) in lines.withIndex()) {
                val html = t.split('\r', '\u000B').joinToString("<br>") { Html.esc(it) }
                out.raw(if (i == 0) "<h3>$html</h3>" else "<p>$html</p>")
            }
            out.raw("</div>")
        }
        if (n == 0) out.raw("<p class=\"note\">Nenhum texto encontrado.</p>")
    }

    private fun add(cur: MutableList<String>?, text: String, slides: MutableList<MutableList<String>>): MutableList<String> {
        val list = cur ?: ArrayList<String>().also { slides.add(it) }
        val t = text.trim()
        // Textos-modelo do PowerPoint ("Clique para editar...") não são conteúdo.
        if (t.isNotEmpty() && !t.startsWith("Click to edit") && !t.startsWith("Clique para editar") && t != "*") list.add(t)
        return list
    }
}
