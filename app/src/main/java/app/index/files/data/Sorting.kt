package app.index.files.data

import java.text.Normalizer

/** Critério de ordenação. Usado na navegação e (fase 3) nos resultados de busca. */
data class SortSpec(val by: Int, val asc: Boolean, val dirsFirst: Boolean) {
    companion object {
        const val NAME = 0
        const val SIZE = 1
        const val TYPE = 2
        const val DATE = 3
    }
}

/** Minúsculas e sem acentos ("Árvore" == "arvore"). Caminho rápido para ASCII. */
fun normalizeName(s: String): String {
    var ascii = true
    for (ch in s) if (ch.code > 127) { ascii = false; break }
    if (ascii) return s.lowercase()
    val d = Normalizer.normalize(s, Normalizer.Form.NFD)
    val sb = StringBuilder(d.length)
    val mark = Character.NON_SPACING_MARK.toInt()
    for (ch in d) if (Character.getType(ch) != mark) sb.append(ch.lowercaseChar())
    return sb.toString()
}

/** Ordem "natural": foto2 < foto10. Sequências de dígitos comparadas pelo valor. */
object NaturalOrder : Comparator<String> {
    override fun compare(a: String, b: String): Int {
        var i = 0
        var j = 0
        while (i < a.length && j < b.length) {
            val ca = a[i]
            val cb = b[j]
            if (ca in '0'..'9' && cb in '0'..'9') {
                var si = i; while (si < a.length && a[si] == '0') si++
                var sj = j; while (sj < b.length && b[sj] == '0') sj++
                var ei = si; while (ei < a.length && a[ei] in '0'..'9') ei++
                var ej = sj; while (ej < b.length && b[ej] in '0'..'9') ej++
                val li = ei - si
                val lj = ej - sj
                if (li != lj) return li - lj
                for (k in 0 until li) {
                    val d = a[si + k] - b[sj + k]
                    if (d != 0) return d
                }
                val zeros = (ei - i) - (ej - j)
                if (zeros != 0) return zeros
                i = ei
                j = ej
            } else {
                if (ca != cb) return ca - cb
                i++
                j++
            }
        }
        return (a.length - i) - (b.length - j)
    }
}

private val byName = Comparator<FileItem> { a, b ->
    val c = NaturalOrder.compare(a.sortKey, b.sortKey)
    if (c != 0) c else a.name.compareTo(b.name)
}

fun comparatorFor(s: SortSpec): Comparator<FileItem> {
    val primary: Comparator<FileItem> = when (s.by) {
        // Pastas não têm tamanho: entre elas vale o nome.
        SortSpec.SIZE -> Comparator<FileItem> { a, b ->
            if (a.isDir && b.isDir) 0 else a.size.compareTo(b.size)
        } then byName
        SortSpec.TYPE -> Comparator<FileItem> { a, b -> a.ext.compareTo(b.ext) } then byName
        SortSpec.DATE -> Comparator<FileItem> { a, b -> a.modified.compareTo(b.modified) } then byName
        else -> byName
    }
    val dir = if (s.asc) primary else primary.reversed()
    if (!s.dirsFirst) return dir
    return Comparator { a, b ->
        if (a.isDir != b.isDir) (if (a.isDir) -1 else 1) else dir.compare(a, b)
    }
}
