package app.index.files.ui.doc

import java.math.BigDecimal
import java.math.MathContext

/**
 * Uma planilha em memória (só células com conteúdo) e a tabela HTML dela, com letras das
 * colunas e números das linhas como no Excel. Usada por xlsx, ods e xls.
 */
class Sheet(val name: String) {
    /** linha → (coluna → (texto, é número)) */
    private val rows = java.util.TreeMap<Int, java.util.TreeMap<Int, Pair<String, Boolean>>>()
    var cells = 0
        private set

    fun put(row: Int, col: Int, text: String, numeric: Boolean = false) {
        if (text.isEmpty() || row < 0 || col < 0 || row >= MAX_ROWS || col >= MAX_COLS) return
        rows.getOrPut(row) { java.util.TreeMap() }[col] = text to numeric
        cells++
    }

    fun render(out: Html, showName: Boolean) {
        if (showName) out.raw("<h2>").text(name).raw("</h2>")
        if (rows.isEmpty()) {
            out.raw("<p class=\"note\">Planilha vazia.</p>")
            return
        }
        val lastCol = rows.values.maxOf { it.lastKey() }
        val firstCol = rows.values.minOf { it.firstKey() }.coerceAtMost(0)
        out.raw("<div class=\"sheet\"><table><tr><th></th>")
        for (c in firstCol..lastCol) out.raw("<th>").raw(colName(c)).raw("</th>")
        out.raw("</tr>")
        var prev = -1
        for ((r, cols) in rows) {
            if (out.full) break
            // Linhas vazias no meio: uma linha só, para não esticar a tabela.
            if (prev >= 0 && r > prev + 1) out.raw("<tr><td class=\"rh\">⋮</td><td colspan=\"${lastCol - firstCol + 1}\"></td></tr>")
            prev = r
            out.raw("<tr><td class=\"rh\">${r + 1}</td>")
            var c = firstCol
            for ((col, v) in cols) {
                if (col > c) {
                    out.raw(if (col - c == 1) "<td></td>" else "<td colspan=\"${col - c}\"></td>")
                }
                out.raw(if (v.second) "<td class=\"n\">" else "<td>").text(v.first).raw("</td>")
                c = col + 1
            }
            if (c <= lastCol) out.raw("<td colspan=\"${lastCol - c + 1}\"></td>")
            out.raw("</tr>")
        }
        out.raw("</table></div>")
    }

    companion object {
        const val MAX_ROWS = 5000
        const val MAX_COLS = 200

        fun colName(c: Int): String {
            var n = c + 1
            val sb = StringBuilder()
            while (n > 0) {
                val m = (n - 1) % 26
                sb.append('A' + m)
                n = (n - 1) / 26
            }
            return sb.reverse().toString()
        }

        /** "B12" → (linha 11, coluna 1). */
        fun ref(r: String): Pair<Int, Int>? {
            var col = 0
            var i = 0
            while (i < r.length && r[i].isLetter()) {
                col = col * 26 + (r[i].uppercaseChar() - 'A' + 1)
                i++
            }
            val row = r.substring(i).toIntOrNull() ?: return null
            if (col == 0) return null
            return (row - 1) to (col - 1)
        }

        /** Número legível: sem ".0", sem ruído de ponto flutuante (0.1+0.2), sem notação científica. */
        fun num(v: Double): String {
            if (v.isNaN() || v.isInfinite()) return v.toString()
            if (v == Math.rint(v) && Math.abs(v) < 1e15) return v.toLong().toString()
            val s = BigDecimal(v).round(MathContext(12)).stripTrailingZeros().toPlainString()
            val sep = java.text.DecimalFormatSymbols.getInstance().decimalSeparator
            return if (sep == '.') s else s.replace('.', sep)
        }

        /** Número de série de data do Excel (sistema 1900; [d1904] para o de 1904) → texto. */
        fun date(serial: Double, d1904: Boolean = false): String {
            val days = Math.floor(serial).toLong()
            val frac = serial - days
            // 25569 = 1970-01-01 no sistema 1900 (já com o falso 29/02/1900 do Excel).
            val epochDays = days - (if (d1904) 24107 else 25569)
            val d = java.time.LocalDate.ofEpochDay(epochDays)
            val secs = Math.round(frac * 86400)
            val datePart = if (serial < 1 && !d1904) "" else "%02d/%02d/%04d".format(d.dayOfMonth, d.monthValue, d.year)
            if (secs == 0L || secs >= 86400) return datePart.ifEmpty { "00:00" }
            val t = "%02d:%02d".format(secs / 3600, (secs % 3600) / 60) + if (secs % 60 != 0L) ":%02d".format(secs % 60) else ""
            return if (datePart.isEmpty()) t else "$datePart $t"
        }

        /** Formato numérico é de data/hora? Ids embutidos do Excel ou código com d/m/y/h fora de aspas. */
        fun isDateFormat(id: Int, code: String?): Boolean {
            if (id in 14..22 || id in 45..47 || id in 27..36 || id in 50..58) return true
            if (code == null) return false
            val clean = code.replace(Regex("\"[^\"]*\"|\\[[^\\]]*]|\\\\."), "").lowercase()
            if (clean.contains("general") || clean.contains('0') || clean.contains('#')) return false
            return clean.any { it == 'd' || it == 'm' || it == 'y' || it == 'h' || it == 's' }
        }
    }
}
