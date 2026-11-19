package app.index.files.ui.doc

import android.util.Base64
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream

/**
 * Word (.docx) com a formatação do documento: estilos (com herança "basedOn"), tamanho, cor, realce
 * e família da fonte, negrito/itálico/sublinhado/tachado, sobrescrito, alinhamento, recuos,
 * espaçamento, listas numeradas e com marcadores (numbering.xml), imagens, tabelas (sombreamento,
 * células mescladas na horizontal) e caixas de texto. Mostrado como folha branca.
 *
 * Tamanhos: o texto normal do documento vira 16 px; o resto é proporcional (um título de 16 pt num
 * documento de 11 pt sai com 16/11 do tamanho). Cores e nomes de fonte são validados antes de
 * entrar no CSS.
 */
internal object Docx {
    private const val IMG_ONE = 4 * 1024 * 1024
    private const val IMG_TOTAL = 10 * 1024 * 1024
    private const val BASE_PX = 16.0

    /** Folha branca, texto preto: as cores do documento valem como no Word. */
    const val CSS = "html,body{background:#fff;color:#000}body{line-height:1.25}" +
        ".w p,.w h1,.w h2,.w h3,.w h4,.w h5,.w h6{margin:0;min-height:1.15em;font-size:1em;line-height:inherit}.w td,.w th{border:1px solid #aaa}.w hr{border-top:1px dashed #bbb;margin:1.2em 0}" +
        ".w img{height:auto;vertical-align:middle}.w .ln{display:inline-block;min-width:1.4em}" +
        ".w .ph{color:#777;font-style:italic}.w .tb{border:1px solid #ccc;padding:4px 8px;margin:6px 0}"

    // ---- Propriedades ----

    private class R(
        var b: Boolean? = null, var i: Boolean? = null, var u: Boolean? = null, var s: Boolean? = null,
        var sz: Int? = null, var color: String? = null, var hl: String? = null, var caps: Boolean? = null,
        var vert: String? = null, var font: String? = null, var hidden: Boolean? = null,
    ) {
        fun over(o: R?): R = if (o == null) this else R(
            o.b ?: b, o.i ?: i, o.u ?: u, o.s ?: s, o.sz ?: sz, o.color ?: color, o.hl ?: hl,
            o.caps ?: caps, o.vert ?: vert, o.font ?: font, o.hidden ?: hidden,
        )
    }

    private class P(
        var jc: String? = null, var left: Int? = null, var first: Int? = null, var right: Int? = null,
        var before: Int? = null, var after: Int? = null, var line: Int? = null,
        var numId: String? = null, var ilvl: Int? = null, var shd: String? = null,
    ) {
        fun over(o: P?): P = if (o == null) this else P(
            o.jc ?: jc, o.left ?: left, o.first ?: first, o.right ?: right, o.before ?: before,
            o.after ?: after, o.line ?: line, o.numId ?: numId, o.ilvl ?: ilvl, o.shd ?: shd,
        )
        val hasInd get() = left != null || first != null
    }

    private class Style(val basedOn: String?, val r: R, val p: P, val heading: Int?)

    private class Level(var fmt: String = "decimal", var text: String = "%1.", var start: Int = 1, var left: Int? = null, var hanging: Int? = null)

    private val HEX = Regex("[0-9A-Fa-f]{6}")

    private val HIGHLIGHT = mapOf(
        "yellow" to "FFFF00", "green" to "00FF00", "cyan" to "00FFFF", "magenta" to "FF00FF", "blue" to "0000FF",
        "red" to "FF0000", "darkBlue" to "000080", "darkCyan" to "008080", "darkGreen" to "008000",
        "darkMagenta" to "800080", "darkRed" to "800000", "darkYellow" to "808000", "darkGray" to "808080",
        "lightGray" to "C0C0C0", "black" to "000000", "white" to "FFFFFF",
    )

    private fun on(v: String?) = v == null || (v != "0" && v != "false" && v != "off")

    private fun twips(p: XmlPullParser, vararg names: String): Int? {
        for (n in names) Zip.attr(p, n)?.toDoubleOrNull()?.let { return it.toInt() }
        return null
    }

    /** Propriedade de fonte (filho de rPr). */
    private fun runProp(p: XmlPullParser, n: String, r: R) {
        when (n) {
            "b" -> r.b = on(Zip.attr(p, "val"))
            "i" -> r.i = on(Zip.attr(p, "val"))
            "u" -> r.u = Zip.attr(p, "val").let { it != "none" && it != "0" }
            "strike", "dstrike" -> r.s = on(Zip.attr(p, "val"))
            "sz" -> Zip.attr(p, "val")?.toIntOrNull()?.let { if (it in 2..400) r.sz = it }
            "color" -> Zip.attr(p, "val")?.let { r.color = if (HEX.matches(it)) it else "" }
            "highlight" -> Zip.attr(p, "val")?.let { r.hl = HIGHLIGHT[it] ?: "" }
            "shd" -> Zip.attr(p, "fill")?.let { if (HEX.matches(it) && !it.equals("FFFFFF", true) && r.hl == null) r.hl = it }
            "caps", "smallCaps" -> r.caps = on(Zip.attr(p, "val"))
            "vertAlign" -> r.vert = Zip.attr(p, "val")
            "vanish" -> r.hidden = on(Zip.attr(p, "val"))
            "rFonts" -> (Zip.attr(p, "ascii") ?: Zip.attr(p, "hAnsi"))?.let { r.font = family(it) }
        }
    }

    /** Propriedade de parágrafo (filho de pPr, ou de numPr). */
    private fun paraProp(p: XmlPullParser, n: String, pp: P) {
        when (n) {
            "jc" -> pp.jc = when (Zip.attr(p, "val")) {
                "center" -> "center"
                "right", "end" -> "right"
                "both", "distribute", "lowKashida", "mediumKashida", "highKashida", "thaiDistribute" -> "justify"
                else -> "left"
            }
            "ind" -> {
                twips(p, "left", "start")?.let { pp.left = it }
                twips(p, "right", "end")?.let { pp.right = it }
                twips(p, "hanging")?.let { pp.first = -it } ?: twips(p, "firstLine")?.let { pp.first = it }
            }
            "spacing" -> {
                twips(p, "before")?.let { pp.before = it }
                twips(p, "after")?.let { pp.after = it }
                val rule = Zip.attr(p, "lineRule")
                if (rule == null || rule == "auto") twips(p, "line")?.let { pp.line = it }
            }
            "numId" -> pp.numId = Zip.attr(p, "val")
            "ilvl" -> pp.ilvl = Zip.attr(p, "val")?.toIntOrNull()
            "shd" -> Zip.attr(p, "fill")?.let { if (HEX.matches(it) && !it.equals("FFFFFF", true)) pp.shd = it }
        }
    }

    private fun family(name: String): String {
        val n = name.lowercase()
        return when {
            listOf("courier", "consolas", "mono", "lucida console").any { it in n } -> "monospace"
            listOf("times", "cambria", "georgia", "garamond", "book antiqua", "palatino", "serif", "century").any { it in n } &&
                "sans" !in n -> "serif"
            else -> "sans-serif"
        }
    }

    // ---- styles.xml ----

    private class Styles(val map: Map<String, Style>, val defR: R, val defP: P, val defPara: String?) {
        private val cacheR = HashMap<String, R>()
        private val cacheP = HashMap<String, P>()

        fun r(id: String?): R? = id?.let { k -> cacheR[k] ?: chain(k).fold(R()) { acc, s -> acc.over(s.r) }.also { cacheR[k] = it } }
        fun p(id: String?): P? = id?.let { k -> cacheP[k] ?: chain(k).fold(P()) { acc, s -> acc.over(s.p) }.also { cacheP[k] = it } }
        fun heading(id: String?): Int? = id?.let { k -> chain(k).lastOrNull { it.heading != null }?.heading }

        /** Do mais básico ao próprio estilo. */
        private fun chain(id: String): List<Style> {
            val out = ArrayList<Style>()
            var cur: String? = id
            while (cur != null && out.size < 20) {
                val s = map[cur] ?: break
                out.add(0, s)
                cur = s.basedOn
            }
            return out
        }
    }

    private fun styles(b: ByteArray?): Styles {
        val map = HashMap<String, Style>()
        val defR = R(); val defP = P()
        var defPara: String? = null
        if (b == null) return Styles(map, defR, defP, null)
        var id: String? = null; var basedOn: String? = null; var heading: Int? = null
        var r = R(); var pp = P()
        var inStyle = false; var inDefaults = false
        val ctx = ArrayList<String>()
        Zip.walk(b, { p, n ->
            ctx.add(n)
            val parent = ctx.getOrNull(ctx.size - 2)
            when {
                n == "docDefaults" -> inDefaults = true
                n == "style" -> {
                    inStyle = true; id = Zip.attr(p, "styleId"); basedOn = null; heading = null; r = R(); pp = P()
                    if (Zip.attr(p, "type") == "paragraph" && on(Zip.attr(p, "default")) && Zip.attr(p, "default") != null) defPara = id
                }
                inStyle && n == "basedOn" -> basedOn = Zip.attr(p, "val")
                inStyle && n == "name" -> {
                    val name = Zip.attr(p, "val")?.lowercase() ?: ""
                    when {
                        name == "title" -> heading = 1
                        name == "subtitle" -> heading = 3
                        name.startsWith("heading ") -> name.removePrefix("heading ").trim().toIntOrNull()?.let { heading = it.coerceIn(1, 6) }
                    }
                }
                inStyle && n == "outlineLvl" && heading == null -> Zip.attr(p, "val")?.toIntOrNull()?.let { if (it in 0..5) heading = it + 1 }
                parent == "rPr" -> runProp(p, n, if (inDefaults) defR else r)
                parent == "pPr" || parent == "numPr" -> paraProp(p, n, if (inDefaults) defP else pp)
            }
        }, { n ->
            if (ctx.isNotEmpty()) ctx.removeAt(ctx.size - 1)
            when (n) {
                "docDefaults" -> inDefaults = false
                "style" -> { inStyle = false; id?.let { map[it] = Style(basedOn, r, pp, heading) } }
            }
        })
        return Styles(map, defR, defP, defPara)
    }

    // ---- numbering.xml ----

    private class Numbering(val nums: Map<String, Map<Int, Level>>) {
        private val counters = HashMap<String, IntArray>()

        /** Rótulo do item ("1.", "a)", "•") e o nível usado; avança o contador. */
        fun next(numId: String, ilvl: Int): Pair<String, Level>? {
            val levels = nums[numId] ?: return null
            val lvl = ilvl.coerceIn(0, 8)
            val def = levels[lvl] ?: Level()
            val c = counters.getOrPut(numId) { IntArray(9) { Int.MIN_VALUE } }
            c[lvl] = if (c[lvl] == Int.MIN_VALUE) def.start else c[lvl] + 1
            for (k in lvl + 1 until 9) c[k] = Int.MIN_VALUE
            if (def.fmt == "none") return "" to def
            if (def.fmt == "bullet") return bullet(def.text) to def
            var t = def.text
            for (k in 0..lvl) {
                val v = if (c[k] == Int.MIN_VALUE) (levels[k]?.start ?: 1) else c[k]
                t = t.replace("%${k + 1}", fmt(v, if (k == lvl) def.fmt else levels[k]?.fmt ?: "decimal"))
            }
            return t to def
        }

    }

    internal fun bullet(t: String): String = when {
        t.isEmpty() -> "•"
        t == "o" -> "◦"
        t[0] == '\uF0A7' || t[0] == '§' -> "▪"
        t[0] == '\uF0D8' -> "➢"
        t[0] == '\uF0FC' -> "✓"
        t[0] == '\uF076' -> "❖"
        t[0].code in 0xF000..0xF0FF -> "•"
        else -> t
    }

    internal fun fmt(v: Int, f: String): String = when (f) {
        "lowerLetter" -> letters(v).lowercase()
        "upperLetter" -> letters(v)
        "lowerRoman" -> roman(v).lowercase()
        "upperRoman" -> roman(v)
        "decimalZero" -> if (v in 0..9) "0$v" else "$v"
        "ordinal" -> "$v.º"
        else -> "$v"
    }

    private fun letters(v: Int): String {
        if (v <= 0) return "$v"
        val ch = 'A' + (v - 1) % 26
        return ch.toString().repeat((v - 1) / 26 + 1)
    }

    private fun roman(v: Int): String {
        if (v !in 1..3999) return "$v"
        val vals = intArrayOf(1000, 900, 500, 400, 100, 90, 50, 40, 10, 9, 5, 4, 1)
        val syms = arrayOf("M", "CM", "D", "CD", "C", "XC", "L", "XL", "X", "IX", "V", "IV", "I")
        var n = v
        val sb = StringBuilder()
        for (k in vals.indices) while (n >= vals[k]) { sb.append(syms[k]); n -= vals[k] }
        return sb.toString()
    }

    private fun numbering(b: ByteArray?): Numbering {
        if (b == null) return Numbering(emptyMap())
        val abstract = HashMap<String, HashMap<Int, Level>>()
        val numAbs = HashMap<String, String>()
        val overrides = HashMap<String, HashMap<Int, Int>>()
        var absId: String? = null
        var lvl: Level? = null
        var numId: String? = null
        var ovLvl = -1
        val ctx = ArrayList<String>()
        Zip.walk(b, { p, n ->
            ctx.add(n)
            val parent = ctx.getOrNull(ctx.size - 2)
            when {
                n == "abstractNum" -> { absId = Zip.attr(p, "abstractNumId"); abstract.getOrPut(absId ?: "") { HashMap() } }
                n == "lvl" && absId != null && numId == null -> {
                    val l = Level(); lvl = l
                    abstract[absId!!]?.put(Zip.attr(p, "ilvl")?.toIntOrNull() ?: 0, l)
                }
                n == "num" -> numId = Zip.attr(p, "numId")
                n == "abstractNumId" && numId != null -> Zip.attr(p, "val")?.let { numAbs[numId!!] = it }
                n == "lvlOverride" -> ovLvl = Zip.attr(p, "ilvl")?.toIntOrNull() ?: -1
                n == "startOverride" && numId != null && ovLvl >= 0 ->
                    Zip.attr(p, "val")?.toIntOrNull()?.let { overrides.getOrPut(numId!!) { HashMap() }[ovLvl] = it }
                lvl != null && parent == "lvl" -> when (n) {
                    "start" -> Zip.attr(p, "val")?.toIntOrNull()?.let { lvl!!.start = it }
                    "numFmt" -> Zip.attr(p, "val")?.let { lvl!!.fmt = it }
                    "lvlText" -> lvl!!.text = Zip.attr(p, "val") ?: ""
                }
                lvl != null && n == "ind" && parent == "pPr" -> {
                    twips(p, "left", "start")?.let { lvl!!.left = it }
                    twips(p, "hanging")?.let { lvl!!.hanging = it }
                }
            }
        }, { n ->
            if (ctx.isNotEmpty()) ctx.removeAt(ctx.size - 1)
            when (n) {
                "lvl" -> lvl = null
                "abstractNum" -> absId = null
                "num" -> numId = null
                "lvlOverride" -> ovLvl = -1
            }
        })
        val nums = HashMap<String, Map<Int, Level>>()
        for ((id, a) in numAbs) {
            val base = abstract[a] ?: continue
            val ov = overrides[id]
            nums[id] = if (ov == null) base else base.mapValues { (k, l) ->
                ov[k]?.let { Level(l.fmt, l.text, it, l.left, l.hanging) } ?: l
            }
        }
        return Numbering(nums)
    }

    // ---- document.xml ----

    /** [depth]: quantas células/caixas estavam abertas quando o parágrafo começou. */
    private class Para(val depth: Int, val sb: StringBuilder = StringBuilder()) {
        var style: String? = null
        val direct = P()
        val after = StringBuilder() // caixas de texto achadas no parágrafo: vão depois dele
    }

    private class Cell(val sb: StringBuilder = StringBuilder()) {
        var span = 1
        var fill: String? = null
        var vContinue = false
    }

    fun render(input: InputStream, out: Html) {
        val parts = Zip.read(input) {
            it == "word/document.xml" || it == "word/styles.xml" || it == "word/numbering.xml" ||
                it == "word/_rels/document.xml.rels" || it.startsWith("word/media/")
        }
        val doc = parts["word/document.xml"] ?: throw java.io.IOException("sem word/document.xml")
        val st = styles(parts["word/styles.xml"])
        val num = numbering(parts["word/numbering.xml"])
        val rels = Zip.rels(parts, "word/_rels/document.xml.rels", "word")

        val baseR = st.defR.over(st.r(st.defPara))
        val baseSz = (baseR.sz ?: 20).coerceAtLeast(8)
        val k = BASE_PX / (baseSz / 2.0) // px por ponto do documento
        fun px(twips: Int) = "%.1fpx".format(java.util.Locale.ROOT, twips / 20.0 * k)
        fun fontPx(sz: Int) = "%.1fpx".format(java.util.Locale.ROOT, sz / 2.0 * k)

        var imgBytes = 0
        val ctx = ArrayList<String>()
        val paras = ArrayList<Para>()
        val sinks = ArrayList<StringBuilder>() // células de tabela abertas
        val cells = ArrayList<Cell>()
        var tblDepth = 0
        var skip = 0
        var runStyle: String? = null
        var runR = R()
        var runOpen: String? = null
        var runClose = ""
        var inText = false
        var imgW = 0L

        fun emit(s: String) {
            when {
                // Caixa de texto que fechou dentro de um parágrafo: entra logo depois dele.
                paras.isNotEmpty() && sinks.size == paras.last().depth -> paras.last().after.append(s)
                sinks.isNotEmpty() -> sinks.last().append(s)
                else -> out.raw(s)
            }
        }

        fun paraOut(): StringBuilder? = paras.lastOrNull()?.sb

        /** Abertura/fechamento das marcas do trecho atual (calculado no primeiro texto do trecho). */
        fun openRun() {
            if (runOpen != null) return
            val pr = paras.lastOrNull()
            val pStyleR = st.r(pr?.style ?: st.defPara)
            val pBase = st.defR.over(pStyleR)
            val eff = pBase.over(st.r(runStyle)).over(runR)
            val o = StringBuilder(); val c = StringBuilder()
            val css = StringBuilder()
            if (eff.sz != null && eff.sz != pBase.sz) css.append("font-size:").append(fontPx(eff.sz!!)).append(';')
            if (!eff.color.isNullOrEmpty() && eff.color != pBase.color) css.append("color:#").append(eff.color).append(';')
            if (!eff.hl.isNullOrEmpty()) css.append("background:#").append(eff.hl).append(';')
            if (eff.font != null && eff.font != pBase.font) css.append("font-family:").append(eff.font).append(';')
            if (eff.caps == true) css.append("text-transform:uppercase;")
            if (eff.hidden == true) css.append("display:none;")
            if (css.isNotEmpty()) { o.append("<span style=\"").append(css).append("\">"); c.insert(0, "</span>") }
            fun tag(t: String) { o.append('<').append(t).append('>'); c.insert(0, "</$t>") }
            if (eff.b == true) tag("b")
            if (eff.i == true) tag("i")
            if (eff.u == true) tag("u")
            if (eff.s == true) tag("s")
            when (eff.vert) { "superscript" -> tag("sup"); "subscript" -> tag("sub") }
            runOpen = o.toString(); runClose = c.toString()
        }

        fun image(id: String?) {
            val w = imgW
            imgW = 0
            val sb = paraOut() ?: return
            val path = id?.let { rels[it] }
            val data = path?.let { parts[it] }
            val ext = path?.substringAfterLast('.', "")?.lowercase() ?: ""
            val mime = when (ext) {
                "png" -> "image/png"; "jpg", "jpeg" -> "image/jpeg"; "gif" -> "image/gif"
                "bmp" -> "image/bmp"; "webp" -> "image/webp"; else -> null
            }
            if (data == null || mime == null || data.size > IMG_ONE || imgBytes + data.size > IMG_TOTAL) {
                sb.append("<span class=\"ph\">[imagem]</span>")
                return
            }
            imgBytes += data.size
            sb.append("<img src=\"data:").append(mime).append(";base64,")
                .append(Base64.encodeToString(data, Base64.NO_WRAP)).append('"')
            if (w > 0) sb.append(" style=\"width:").append("%.0fpx".format(java.util.Locale.ROOT, w / 12700.0 * k)).append('"')
            sb.append('>')
        }

        fun flushPara(pa: Para) {
            val sid = pa.style ?: st.defPara
            val pp = st.defP.over(st.p(sid)).over(pa.direct)
            val pr = st.defR.over(st.r(sid))
            val heading = st.heading(sid)
            val css = StringBuilder()
            var label = ""
            var left = pp.left
            var first = pp.first
            val nid = pp.numId
            if (nid != null && nid != "0") {
                num.next(nid, pp.ilvl ?: 0)?.let { (text, lvl) ->
                    label = text
                    if (!pa.direct.hasInd) {
                        lvl.left?.let { left = it }
                        lvl.hanging?.let { first = -it }
                    }
                }
            }
            pp.jc?.let { if (it != "left") css.append("text-align:").append(it).append(';') }
            left?.let { if (it > 0) css.append("margin-left:").append(px(it.coerceAtMost(8000))).append(';') }
            pp.right?.let { if (it > 0) css.append("margin-right:").append(px(it.coerceAtMost(8000))).append(';') }
            first?.let { if (it != 0) css.append("text-indent:").append(px(it.coerceIn(-8000, 8000))).append(';') }
            pp.before?.let { if (it > 0) css.append("margin-top:").append(px(it.coerceAtMost(2000))).append(';') }
            pp.after?.let { if (it > 0) css.append("margin-bottom:").append(px(it.coerceAtMost(2000))).append(';') }
            pp.line?.let { if (it in 120..1200) css.append("line-height:").append("%.2f".format(java.util.Locale.ROOT, 1.17 * it / 240)).append(';') }
            pp.shd?.let { css.append("background:#").append(it).append(';') }
            val sz = st.r(sid)?.sz ?: if (heading != null) (baseSz * when (heading) { 1 -> 1.6; 2 -> 1.35; 3 -> 1.15; else -> 1.0 }).toInt() else null
            if (sz != null && sz != baseSz) css.append("font-size:").append(fontPx(sz)).append(';')
            if (!pr.color.isNullOrEmpty()) css.append("color:#").append(pr.color).append(';')
            if (pr.font != null && pr.font != baseR.font) css.append("font-family:").append(pr.font).append(';')
            if (pr.b == true || (heading != null && pr.b == null)) css.append("font-weight:bold;")
            if (pr.i == true) css.append("font-style:italic;")
            val tag = if (heading != null) "h$heading" else "p"
            val body = StringBuilder()
            body.append('<').append(tag)
            if (css.isNotEmpty()) body.append(" style=\"").append(css).append('"')
            body.append('>')
            if (label.isNotEmpty()) body.append("<span class=\"ln\">").append(Html.esc(label)).append("</span>")
            if (pa.sb.isEmpty() && label.isEmpty()) body.append("<br>") else body.append(pa.sb)
            body.append("</").append(tag).append('>')
            body.append(pa.after)
            emit(body.toString())
        }

        out.raw("<div class=\"w\">")
        Zip.walk(doc, { p, n ->
            ctx.add(n)
            if (skip > 0) {
                if (n in SKIP) skip++
                return@walk
            }
            if (n in SKIP) { skip++; return@walk }
            val parent = ctx.getOrNull(ctx.size - 2)
            val gp = ctx.getOrNull(ctx.size - 3)
            when {
                n == "p" -> paras.add(Para(sinks.size))
                n == "pStyle" && parent == "pPr" -> paras.lastOrNull()?.style = Zip.attr(p, "val")
                paras.isNotEmpty() && ((parent == "pPr" && gp == "p") || (parent == "numPr" && gp == "pPr")) -> paraProp(p, n, paras.last().direct)
                n == "r" -> { runStyle = null; runR = R(); runOpen = null; runClose = "" }
                n == "rStyle" && parent == "rPr" -> runStyle = Zip.attr(p, "val")
                parent == "rPr" && gp == "r" -> runProp(p, n, runR)
                n == "t" && parent == "r" -> inText = true
                n == "tab" && parent == "r" -> paraOut()?.append("&emsp;")
                n == "br" && parent == "r" -> paraOut()?.append(if (Zip.attr(p, "type") == "page") "<hr>" else "<br>")
                n == "cr" -> paraOut()?.append("<br>")
                n == "noBreakHyphen" -> paraOut()?.append('-')
                n == "sym" -> paraOut()?.append('•')
                n == "extent" && imgW == 0L -> imgW = Zip.attr(p, "cx")?.toLongOrNull() ?: 0L
                n == "blip" -> image(Zip.attr(p, "embed"))
                n == "imagedata" -> image(Zip.attr(p, "id"))
                n == "txbxContent" -> sinks.add(StringBuilder()) // parágrafos da caixa ficam juntos
                n == "tbl" -> { tblDepth++; emit(if (tblDepth == 1 && sinks.isEmpty()) "<div class=\"sheet\"><table>" else "<table>") }
                n == "tr" -> emit("<tr>")
                n == "tc" -> { val c = Cell(); cells.add(c); sinks.add(c.sb) }
                n == "gridSpan" && parent == "tcPr" -> cells.lastOrNull()?.span = Zip.attr(p, "val")?.toIntOrNull()?.coerceIn(1, 64) ?: 1
                n == "vMerge" && parent == "tcPr" -> cells.lastOrNull()?.vContinue = Zip.attr(p, "val").let { it == null || it == "continue" }
                n == "shd" && parent == "tcPr" -> Zip.attr(p, "fill")?.let { if (HEX.matches(it)) cells.lastOrNull()?.fill = it }
            }
        }, { n ->
            if (ctx.isNotEmpty()) ctx.removeAt(ctx.size - 1)
            if (skip > 0) {
                if (n in SKIP) skip--
                return@walk
            }
            when (n) {
                "t" -> inText = false
                "r" -> { runOpen = null }
                "p" -> if (paras.isNotEmpty()) flushPara(paras.removeAt(paras.size - 1))
                "txbxContent" -> if (sinks.isNotEmpty()) {
                    val box = sinks.removeAt(sinks.size - 1).toString()
                    if (box.isNotBlank()) emit("<div class=\"tb\">$box</div>")
                }
                "tc" -> if (cells.isNotEmpty()) {
                    val c = cells.removeAt(cells.size - 1)
                    if (sinks.isNotEmpty()) sinks.removeAt(sinks.size - 1)
                    val attrs = StringBuilder()
                    if (c.span > 1) attrs.append(" colspan=\"").append(c.span).append('"')
                    c.fill?.let { if (!it.equals("FFFFFF", true)) attrs.append(" style=\"background:#").append(it).append('"') }
                    emit("<td$attrs>${if (c.vContinue) "" else c.sb}</td>")
                }
                "tr" -> emit("</tr>")
                "tbl" -> { emit(if (tblDepth == 1 && sinks.isEmpty()) "</table></div>" else "</table>"); tblDepth-- }
            }
        }, { text ->
            if (skip == 0 && inText && text.isNotEmpty()) {
                val sb = paraOut() ?: return@walk
                openRun()
                sb.append(runOpen)
                Html.escTo(sb, text)
                sb.append(runClose)
            }
        })
        out.raw("</div>")
    }

    /** Conteúdo que não aparece: texto apagado em revisão, código de campo, versão alternativa repetida, formatação antiga. */
    private val SKIP = setOf("del", "delText", "instrText", "Fallback", "rPrChange", "pPrChange", "tblPrChange", "trPrChange", "tcPrChange", "sectPrChange", "moveFrom")
}
