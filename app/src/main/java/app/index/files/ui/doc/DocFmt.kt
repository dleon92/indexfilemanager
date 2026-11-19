package app.index.files.ui.doc

import java.nio.charset.Charset

/**
 * Word 97–2003 (.doc) com formatação, a partir das estruturas binárias do arquivo:
 * folha de estilos (STSH), propriedades de caractere (FKP de CHPX) e de parágrafo (FKP de PAPX),
 * listas (PlfLst/PlfLfo) e a tabela de peças do texto. Sai o mesmo HTML do [Docx] (folha branca).
 *
 * Só o corpo do documento (sem cabeçalhos, rodapés e notas). Imagens viram "[imagem]".
 * Qualquer estrutura estranha lança exceção e quem chama cai no modo só texto.
 */
internal object DocFmt {
    private val CP1252: Charset = Charset.forName("windows-1252")
    private const val BASE_PX = 16.0
    private const val MAX_CHARS = 3_000_000

    private val ICO = arrayOf(
        null, "000000", "0000FF", "00FFFF", "00FF00", "FF00FF", "FF0000", "FFFF00", "FFFFFF",
        "000080", "008080", "008000", "800080", "800000", "808000", "808080", "C0C0C0",
    )

    // ---- Leitura little-endian ----

    private fun u8(b: ByteArray, i: Int) = b[i].toInt() and 0xFF
    private fun u16(b: ByteArray, i: Int) = (b[i].toInt() and 0xFF) or ((b[i + 1].toInt() and 0xFF) shl 8)
    private fun s16(b: ByteArray, i: Int) = u16(b, i).toShort().toInt()
    private fun i32(b: ByteArray, i: Int) = u16(b, i) or (u16(b, i + 2) shl 16)

    // ---- Propriedades ----

    private data class C(
        val b: Boolean = false, val i: Boolean = false, val u: Boolean = false, val s: Boolean = false,
        val hps: Int? = null, val color: String? = null, val hl: String? = null, val vert: Int = 0,
        val caps: Boolean = false, val hidden: Boolean = false, val del: Boolean = false, val spec: Boolean = false,
    )

    private class P {
        var istd = 0
        var jc = 0
        var left: Int? = null; var first: Int? = null; var right: Int? = null
        var before: Int? = null; var after: Int? = null; var line: Int? = null
        var ilfo = 0; var ilvl = 0
        var inTable = false; var ttp = false
        var shd: String? = null
        fun copy() = P().also {
            it.istd = istd; it.jc = jc; it.left = left; it.first = first; it.right = right
            it.before = before; it.after = after; it.line = line; it.ilfo = ilfo; it.ilvl = ilvl
            it.inTable = inTable; it.ttp = ttp; it.shd = shd
        }
    }

    /** Percorre os sprms de [g] entre [from] e [to]: (código, posição do operando). */
    private inline fun sprms(g: ByteArray, from: Int, to: Int, f: (Int, Int) -> Unit) {
        var i = from
        val end = minOf(to, g.size)
        while (i + 2 <= end) {
            val op = u16(g, i)
            i += 2
            val size = when (op ushr 13) {
                0, 1 -> 1
                2, 4, 5 -> 2
                3 -> 4
                7 -> 3
                else -> if (i >= end) return else if (op == 0xD608 || op == 0xD606) {
                    if (i + 2 > end) return
                    u16(g, i) + 1
                } else u8(g, i) + 1
            }
            if (i + size > end) return
            f(op, i)
            i += size
        }
    }

    private fun toggle(v: Int, base: Boolean) = when (v) { 0 -> false; 1 -> true; 0x81 -> !base; else -> base }

    private fun cv(g: ByteArray, at: Int): String? =
        if (u8(g, at + 3) == 0xFF) null else "%02X%02X%02X".format(u8(g, at), u8(g, at + 1), u8(g, at + 2))

    private fun applyC(c0: C, g: ByteArray, from: Int, to: Int, base: C, charStyle: (Int) -> C?): C {
        var c = c0
        sprms(g, from, to) { op, at ->
            when (op) {
                0x4A30 -> charStyle(u16(g, at))?.let { st -> c = merge(c, st) }
                0x0835 -> c = c.copy(b = toggle(u8(g, at), base.b))
                0x0836 -> c = c.copy(i = toggle(u8(g, at), base.i))
                0x0837, 0x2A53 -> c = c.copy(s = toggle(u8(g, at), base.s))
                0x083B, 0x083A -> c = c.copy(caps = toggle(u8(g, at), base.caps))
                0x083C -> c = c.copy(hidden = toggle(u8(g, at), base.hidden))
                0x0800 -> c = c.copy(del = u8(g, at) != 0)
                0x0855 -> c = c.copy(spec = u8(g, at) != 0)
                0x2A3E -> c = c.copy(u = u8(g, at) != 0)
                0x4A43 -> u16(g, at).let { if (it in 2..400) c = c.copy(hps = it) }
                0x2A42 -> c = c.copy(color = ICO.getOrNull(u8(g, at)))
                0x6870 -> c = c.copy(color = cv(g, at))
                0x2A0C -> c = c.copy(hl = ICO.getOrNull(u8(g, at)))
                0x2A48 -> c = c.copy(vert = u8(g, at))
            }
        }
        return c
    }

    /** Estilo de caractere por cima do que já há: só o que o estilo define (aqui, o que difere do padrão). */
    private fun merge(c: C, st: C) = c.copy(
        b = c.b || st.b, i = c.i || st.i, u = c.u || st.u, s = c.s || st.s,
        hps = st.hps ?: c.hps, color = st.color ?: c.color, hl = st.hl ?: c.hl,
        vert = if (st.vert != 0) st.vert else c.vert, caps = c.caps || st.caps,
    )

    private fun applyP(p: P, g: ByteArray, from: Int, to: Int) {
        sprms(g, from, to) { op, at ->
            when (op) {
                0x2403, 0x2461 -> p.jc = u8(g, at)
                0x840F, 0x845E -> p.left = s16(g, at)
                0x8411, 0x8460 -> p.first = s16(g, at)
                0x840E, 0x845D -> p.right = s16(g, at)
                0xA413 -> p.before = u16(g, at)
                0xA414 -> p.after = u16(g, at)
                0x6412 -> if (s16(g, at + 2) == 1) p.line = s16(g, at)
                0x460B -> p.ilfo = s16(g, at)
                0x260A -> p.ilvl = u8(g, at)
                0x2416 -> p.inTable = u8(g, at) != 0
                0x6649 -> if (i32(g, at) > 0) p.inTable = true
                0x2417 -> p.ttp = u8(g, at) != 0
                0x664A -> {} // sprmPDtap
                0x442D -> ICO.getOrNull((u16(g, at) shr 5) and 0x1F)?.let { if (it != "FFFFFF") p.shd = it }
                0xC64D -> if (u8(g, at) >= 10) cv(g, at + 5)?.let { if (it != "FFFFFF") p.shd = it }
            }
        }
    }

    // ---- Folha de estilos ----

    private class Std(val stk: Int, val base: Int, val sti: Int, val papx: ByteArray?, val chpx: ByteArray?)

    private class Styles(val list: Array<Std?>) {
        private val cacheC = HashMap<Int, C>()
        private val cacheP = HashMap<Int, P>()

        fun chp(istd: Int, depth: Int = 0): C {
            cacheC[istd]?.let { return it }
            val st = list.getOrNull(istd) ?: return C()
            val base = if (st.base != 0xFFF && st.base != istd && depth < 20) chp(st.base, depth + 1) else C()
            val g = st.chpx
            val c = if (g == null) base else applyC(base, g, 0, g.size, base) { null }
            cacheC[istd] = c
            return c
        }

        fun pap(istd: Int, depth: Int = 0): P {
            cacheP[istd]?.let { return it.copy() }
            val st = list.getOrNull(istd)
            val p = if (st != null && st.base != 0xFFF && st.base != istd && depth < 20) pap(st.base, depth + 1) else P()
            st?.papx?.let { applyP(p, it, 0, it.size) }
            p.istd = istd
            cacheP[istd] = p.copy()
            return p
        }

        /** Nível de título (Título 1–9, Título do documento, Subtítulo), seguindo a herança. */
        fun heading(istd: Int): Int? {
            var cur = istd
            repeat(20) {
                val st = list.getOrNull(cur) ?: return null
                when (st.sti) {
                    in 1..9 -> return st.sti.coerceAtMost(6)
                    62 -> return 1
                    74 -> return 3
                }
                if (st.base == 0xFFF || st.base == cur) return null
                cur = st.base
            }
            return null
        }

        fun charStyle(istd: Int): C? = list.getOrNull(istd)?.takeIf { it.stk == 2 }?.let { chp(istd) }
    }

    private fun styles(t: ByteArray, fc: Int, lcb: Int): Styles {
        if (lcb <= 4 || fc < 0 || fc + lcb > t.size) return Styles(emptyArray())
        val cbStshi = u16(t, fc)
        val cstd = u16(t, fc + 2)
        val cbBase = u16(t, fc + 4).let { if (it in 10..64) it else 10 }
        val arr = arrayOfNulls<Std>(cstd.coerceIn(0, 4096))
        var o = fc + 2 + cbStshi
        val end = fc + lcb
        for (k in arr.indices) {
            if (o + 2 > end) break
            val cb = u16(t, o)
            o += 2
            if (cb == 0) continue
            val s = o
            o += cb
            if (o > end || cb < cbBase) continue
            val sti = u16(t, s) and 0x0FFF
            val w2 = u16(t, s + 2)
            val stk = w2 and 0x0F
            val base = w2 ushr 4
            val cupx = u16(t, s + 4) and 0x0F
            var q = s + cbBase
            if (q + 2 > o) { arr[k] = Std(stk, base, sti, null, null); continue }
            val cch = u16(t, q)
            q += 2 + cch * 2 + 2
            val upx = ArrayList<ByteArray>()
            for (u in 0 until cupx) {
                if ((q - s) % 2 != 0) q++
                if (q + 2 > o) break
                val n = u16(t, q)
                q += 2
                if (q + n > o) break
                upx.add(t.copyOfRange(q, q + n))
                q += n
            }
            arr[k] = when (stk) {
                1 -> Std(stk, base, sti, upx.getOrNull(0)?.let { if (it.size >= 2) it.copyOfRange(2, it.size) else null }, upx.getOrNull(1))
                2 -> Std(stk, base, sti, null, upx.getOrNull(0))
                else -> Std(stk, base, sti, null, null)
            }
        }
        return Styles(arr)
    }

    // ---- Listas ----

    private class Lvl(val start: Int, val nfc: Int, val xst: String, val papx: ByteArray?)

    private class Lists(val byLsid: Map<Int, Array<Lvl?>>, val lfo: IntArray) {
        private val counters = HashMap<Int, IntArray>()

        fun next(ilfo: Int, ilvl: Int): Pair<String, Lvl>? {
            val lsid = lfo.getOrNull(ilfo - 1) ?: return null
            val levels = byLsid[lsid] ?: return null
            val l = ilvl.coerceIn(0, levels.size - 1)
            val def = levels[l] ?: return null
            val c = counters.getOrPut(lsid) { IntArray(9) { Int.MIN_VALUE } }
            c[l] = if (c[l] == Int.MIN_VALUE) def.start else c[l] + 1
            for (k in l + 1 until 9) c[k] = Int.MIN_VALUE
            if (def.nfc == 255) return "" to def
            if (def.nfc == 23) return Docx.bullet(def.xst) to def
            val sb = StringBuilder()
            for (ch in def.xst) {
                val k = ch.code
                if (k in 0..8) {
                    val v = if (c.getOrNull(k) == null || c[k] == Int.MIN_VALUE) (levels.getOrNull(k)?.start ?: 1) else c[k]
                    sb.append(Docx.fmt(v, nfcName(if (k == l) def.nfc else levels.getOrNull(k)?.nfc ?: 0)))
                } else sb.append(ch)
            }
            return sb.toString() to def
        }

        private fun nfcName(n: Int) = when (n) {
            1 -> "upperRoman"; 2 -> "lowerRoman"; 3 -> "upperLetter"; 4 -> "lowerLetter"
            5 -> "ordinal"; 22 -> "decimalZero"; else -> "decimal"
        }
    }

    private fun lists(t: ByteArray, fcLst: Int, lcbLst: Int, fcLfo: Int, lcbLfo: Int): Lists {
        val by = HashMap<Int, Array<Lvl?>>()
        if (lcbLst > 2 && fcLst >= 0 && fcLst + lcbLst <= t.size) {
            val n = s16(t, fcLst).coerceIn(0, 4096)
            val lstf = fcLst + 2
            var o = lstf + n * 28
            // Os níveis (LVL) vêm logo depois do PlfLst, fora do tamanho declarado dele.
            val end = t.size
            for (k in 0 until n) {
                val e = lstf + k * 28
                if (e + 28 > t.size) break
                val lsid = i32(t, e)
                val simple = u8(t, e + 26) and 1 != 0
                val count = if (simple) 1 else 9
                val levels = arrayOfNulls<Lvl>(count)
                for (l in 0 until count) {
                    if (o + 28 > end) break
                    val start = i32(t, o)
                    val nfc = u8(t, o + 4)
                    val cbChpx = u8(t, o + 24)
                    val cbPapx = u8(t, o + 25)
                    var q = o + 28
                    val papx = if (cbPapx > 0 && q + cbPapx <= end) t.copyOfRange(q, q + cbPapx) else null
                    q += cbPapx + cbChpx
                    if (q + 2 > end) break
                    val cch = u16(t, q).coerceAtMost(64)
                    q += 2
                    if (q + cch * 2 > end) break
                    val xst = String(t, q, cch * 2, Charsets.UTF_16LE)
                    q += cch * 2
                    levels[l] = Lvl(start, nfc, xst, papx)
                    o = q
                }
                by[lsid] = levels
            }
        }
        var lfo = IntArray(0)
        if (lcbLfo > 4 && fcLfo >= 0 && fcLfo + lcbLfo <= t.size) {
            val n = i32(t, fcLfo).coerceIn(0, 4096)
            lfo = IntArray(n) { k -> if (fcLfo + 4 + k * 16 + 4 <= t.size) i32(t, fcLfo + 4 + k * 16) else 0 }
        }
        return Lists(by, lfo)
    }

    // ---- FKPs: faixas de posição no arquivo (FC) → propriedades ----

    private class Ranges(val start: IntArray, val end: IntArray, val data: Array<ByteArray?>, val istd: IntArray) {
        private var last = 0
        fun find(fc: Int): Int {
            if (last < start.size && fc >= start[last] && fc < end[last]) return last
            var lo = 0; var hi = start.size - 1
            while (lo <= hi) {
                val m = (lo + hi) ushr 1
                when {
                    fc < start[m] -> hi = m - 1
                    fc >= end[m] -> lo = m + 1
                    else -> { last = m; return m }
                }
            }
            return -1
        }
    }

    private fun fkps(wd: ByteArray, t: ByteArray, fc: Int, lcb: Int, papx: Boolean): Ranges {
        val s = ArrayList<Int>(); val e = ArrayList<Int>(); val d = ArrayList<ByteArray?>(); val ist = ArrayList<Int>()
        if (lcb >= 8 && fc >= 0 && fc + lcb <= t.size) {
            val n = (lcb - 4) / 8
            for (k in 0 until n) {
                val pn = i32(t, fc + (n + 1) * 4 + k * 4) and 0x3FFFFF
                val page = pn * 512
                if (page + 512 > wd.size) continue
                val crun = u8(wd, page + 511)
                for (r in 0 until crun) {
                    val a = i32(wd, page + r * 4)
                    val b = i32(wd, page + (r + 1) * 4)
                    var grp: ByteArray? = null
                    var istd = 0
                    if (papx) {
                        val off = u8(wd, page + (crun + 1) * 4 + r * 13) * 2
                        if (off > 0) {
                            var q = page + off
                            var cb = u8(wd, q)
                            q++
                            val len = if (cb == 0) { cb = u8(wd, q); q++; cb * 2 } else cb * 2 - 1
                            if (len >= 2 && q + len <= page + 512) {
                                istd = u16(wd, q)
                                grp = wd.copyOfRange(q + 2, q + len)
                            }
                        }
                    } else {
                        val off = u8(wd, page + (crun + 1) * 4 + r) * 2
                        if (off > 0) {
                            val cb = u8(wd, page + off)
                            if (page + off + 1 + cb <= page + 512) grp = wd.copyOfRange(page + off + 1, page + off + 1 + cb)
                        }
                    }
                    s.add(a); e.add(b); d.add(grp); ist.add(istd)
                }
            }
        }
        // Em ordem de posição (as páginas já vêm assim; garante a busca binária).
        val idx = s.indices.sortedBy { s[it] }
        return Ranges(
            IntArray(idx.size) { s[idx[it]] }, IntArray(idx.size) { e[idx[it]] },
            Array(idx.size) { d[idx[it]] }, IntArray(idx.size) { ist[idx[it]] },
        )
    }

    // ---- Documento ----

    /**
     * [lcbBase]: posição do bloco fc/lcb do FIB; [ccpText]: caracteres do corpo do documento.
     * Devolve o HTML (dentro de `<div class="w">`).
     */
    fun render(wd: ByteArray, t: ByteArray, lcbBase: Int, ccpText: Int, fcClx: Int, lcbClx: Int): String {
        fun fcAt(i: Int) = i32(wd, lcbBase + i * 8)
        fun lcbAt(i: Int) = i32(wd, lcbBase + i * 8 + 4)
        val st = styles(t, fcAt(1), lcbAt(1))
        val chpx = fkps(wd, t, fcAt(12), lcbAt(12), false)
        val papx = fkps(wd, t, fcAt(13), lcbAt(13), true)
        val lists = lists(t, fcAt(73), lcbAt(73), fcAt(74), lcbAt(74))

        // Texto do corpo com a posição de cada caractere no arquivo.
        val n = ccpText.coerceIn(0, MAX_CHARS)
        val chars = CharArray(n)
        val fcs = IntArray(n)
        var got = 0
        run {
            var i = fcClx
            val end = fcClx + lcbClx
            if (lcbClx <= 0 || end > t.size) throw java.io.IOException("sem tabela de peças")
            while (i < end && t[i].toInt() == 1) i += 3 + u16(t, i + 1)
            if (i >= end || t[i].toInt() != 2) throw java.io.IOException("tabela de peças inválida")
            val lcbPlc = i32(t, i + 1)
            val plc = i + 5
            val np = (lcbPlc - 4) / 12
            for (k in 0 until np) {
                val cpStart = i32(t, plc + k * 4)
                val cpEnd = i32(t, plc + (k + 1) * 4)
                val raw = i32(t, plc + (np + 1) * 4 + k * 8 + 2)
                val compressed = raw and 0x40000000 != 0
                val base = if (compressed) (raw and 0x3FFFFFFF) / 2 else raw
                for (cp in cpStart until minOf(cpEnd, n)) {
                    if (cp < 0) continue
                    val j = cp - cpStart
                    if (compressed) {
                        val at = base + j
                        if (at >= wd.size) break
                        chars[cp] = String(wd, at, 1, CP1252)[0]
                        fcs[cp] = at
                    } else {
                        val at = base + j * 2
                        if (at + 1 >= wd.size) break
                        chars[cp] = u16(wd, at).toChar()
                        fcs[cp] = at
                    }
                    if (cp + 1 > got) got = cp + 1
                }
            }
        }

        val normal = st.chp(0)
        val baseHps = (normal.hps ?: 20).coerceAtLeast(8)
        val k = BASE_PX / (baseHps / 2.0)
        fun px(tw: Int) = "%.1fpx".format(java.util.Locale.ROOT, tw / 20.0 * k)
        fun fontPx(hps: Int) = "%.1fpx".format(java.util.Locale.ROOT, hps / 2.0 * k)

        val out = StringBuilder(n + 4096)
        out.append("<div class=\"w\">")
        var inTable = false
        var rowOpen = false
        val cell = StringBuilder()
        val para = StringBuilder()
        var field = 0
        val fieldStack = ArrayList<Boolean>()
        val runCache = HashMap<Long, Pair<String, String>>()

        fun papAt(fc: Int): P {
            val r = papx.find(fc)
            if (r < 0) return st.pap(0)
            val p = st.pap(papx.istd[r])
            papx.data[r]?.let { applyP(p, it, 0, it.size) }
            return p
        }

        val chpCache = HashMap<Long, C>()
        fun chpAt(fc: Int, istd: Int, pstyle: C): Pair<C, Int> {
            val r = chpx.find(fc)
            val key = (r.toLong() shl 20) or istd.toLong()
            val c = chpCache.getOrPut(key) {
                val g = if (r >= 0) chpx.data[r] else null
                if (g == null) pstyle else applyC(pstyle, g, 0, g.size, pstyle) { st.charStyle(it) }
            }
            return c to r
        }

        fun marks(c: C, pstyle: C): Pair<String, String> {
            val o = StringBuilder(); val cl = StringBuilder(); val css = StringBuilder()
            if (c.hps != null && c.hps != pstyle.hps) css.append("font-size:").append(fontPx(c.hps)).append(';')
            if (c.color != null && c.color != pstyle.color) css.append("color:#").append(c.color).append(';')
            if (c.hl != null) css.append("background:#").append(c.hl).append(';')
            if (c.caps) css.append("text-transform:uppercase;")
            if (css.isNotEmpty()) { o.append("<span style=\"").append(css).append("\">"); cl.insert(0, "</span>") }
            fun tag(x: String) { o.append('<').append(x).append('>'); cl.insert(0, "</$x>") }
            if (c.b && !pstyle.b) tag("b")
            if (c.i && !pstyle.i) tag("i")
            if (c.u && !pstyle.u) tag("u")
            if (c.s) tag("s")
            when (c.vert) { 1 -> tag("sup"); 2 -> tag("sub") }
            return o.toString() to cl.toString()
        }

        fun paraHtml(p: P, body: CharSequence): String {
            val heading = st.heading(p.istd)
            val pc = st.chp(p.istd)
            val css = StringBuilder()
            var label = ""
            var left = p.left
            var first = p.first
            if (p.ilfo > 0) lists.next(p.ilfo, p.ilvl)?.let { (txt, lvl) ->
                label = txt
                if (left == null || first == null) lvl.papx?.let { g ->
                    val lp = P(); applyP(lp, g, 0, g.size)
                    if (left == null) left = lp.left
                    if (first == null) first = lp.first
                }
            }
            when (p.jc) { 1 -> "center"; 2 -> "right"; 3, 4 -> "justify"; else -> null }?.let { css.append("text-align:").append(it).append(';') }
            left?.let { if (it > 0) css.append("margin-left:").append(px(it.coerceAtMost(8000))).append(';') }
            p.right?.let { if (it > 0) css.append("margin-right:").append(px(it.coerceAtMost(8000))).append(';') }
            first?.let { if (it != 0) css.append("text-indent:").append(px(it.coerceIn(-8000, 8000))).append(';') }
            p.before?.let { if (it > 0) css.append("margin-top:").append(px(it.coerceAtMost(2000))).append(';') }
            p.after?.let { if (it > 0) css.append("margin-bottom:").append(px(it.coerceAtMost(2000))).append(';') }
            p.line?.let { if (it in 120..1200) css.append("line-height:").append("%.2f".format(java.util.Locale.ROOT, 1.17 * it / 240)).append(';') }
            p.shd?.let { css.append("background:#").append(it).append(';') }
            val hps = pc.hps ?: heading?.let { (baseHps * when (it) { 1 -> 1.6; 2 -> 1.35; 3 -> 1.15; else -> 1.0 }).toInt() }
            if (hps != null && hps != baseHps) css.append("font-size:").append(fontPx(hps)).append(';')
            if (pc.color != null) css.append("color:#").append(pc.color).append(';')
            if (pc.b || heading != null) css.append("font-weight:bold;")
            if (pc.i) css.append("font-style:italic;")
            if (pc.u) css.append("text-decoration:underline;")
            val tag = if (heading != null) "h$heading" else "p"
            val sb = StringBuilder()
            sb.append('<').append(tag)
            if (css.isNotEmpty()) sb.append(" style=\"").append(css).append('"')
            sb.append('>')
            if (label.isNotEmpty()) sb.append("<span class=\"ln\">").append(Html.esc(label)).append("</span>")
            if (body.isEmpty() && label.isEmpty()) sb.append("<br>") else sb.append(body)
            sb.append("</").append(tag).append('>')
            return sb.toString()
        }

        var cp = 0
        while (cp < got) {
            // Fim do parágrafo (marca de parágrafo ou de célula): dele vêm as propriedades.
            var endCp = cp
            while (endCp < got && chars[endCp] != '\r' && chars[endCp] != '\u0007') endCp++
            val markFc = if (endCp < got) fcs[endCp] else fcs[got - 1]
            val p = papAt(markFc)
            val pstyle = st.chp(p.istd)
            para.setLength(0)
            var curRun = Int.MIN_VALUE
            var close = ""
            for (x in cp until endCp) {
                val ch = chars[x]
                when (ch.code) {
                    0x13 -> { fieldStack.add(false); field++; continue }
                    0x14 -> { if (fieldStack.isNotEmpty() && !fieldStack.last()) { fieldStack[fieldStack.size - 1] = true; field-- }; continue }
                    0x15 -> { if (fieldStack.isNotEmpty() && !fieldStack.removeAt(fieldStack.size - 1)) field--; continue }
                }
                if (field > 0) continue
                val (c, r) = chpAt(fcs[x], p.istd, pstyle)
                if (c.del || c.hidden) continue
                if (r != curRun) {
                    para.append(close)
                    val key = (r.toLong() shl 20) or p.istd.toLong()
                    val m = runCache.getOrPut(key) { marks(c, pstyle) }
                    para.append(m.first)
                    close = m.second
                    curRun = r
                }
                when (ch.code) {
                    0x09 -> para.append("&emsp;")
                    0x0B -> para.append("<br>")
                    0x0C -> para.append("<hr>")
                    0x1E -> para.append('-')
                    0xA0 -> para.append("&nbsp;")
                    0x01 -> if (c.spec) para.append("<span class=\"ph\">[imagem]</span>")
                    0x02, 0x03, 0x04, 0x05, 0x08, 0x1F -> {}
                    else -> if (ch.code >= 0x20) Html.escTo(para, ch.toString())
                }
            }
            para.append(close)
            val mark = if (endCp < got) chars[endCp] else '\r'
            if (p.inTable) {
                if (!inTable) { out.append("<div class=\"sheet\"><table>"); inTable = true }
                if (!rowOpen) { out.append("<tr>"); rowOpen = true }
                if (mark == '\u0007' && p.ttp) {
                    out.append("</tr>"); rowOpen = false
                } else if (mark == '\u0007') {
                    cell.append(paraHtml(p, para))
                    out.append("<td>").append(cell).append("</td>")
                    cell.setLength(0)
                } else cell.append(paraHtml(p, para))
            } else {
                if (inTable) {
                    if (cell.isNotEmpty()) { out.append("<td>").append(cell).append("</td>"); cell.setLength(0) }
                    if (rowOpen) out.append("</tr>")
                    out.append("</table></div>")
                    inTable = false; rowOpen = false
                }
                out.append(paraHtml(p, para))
            }
            cp = endCp + 1
        }
        if (inTable) { if (rowOpen) out.append("</tr>"); out.append("</table></div>") }
        out.append("</div>")
        return out.toString()
    }
}
