package app.index.files.data

import java.security.SecureRandom

/**
 * Sorteio da seleção aleatória (item A): só fotos e/ou vídeos, sem repetição
 * (embaralhamento parcial de Fisher–Yates sobre uma cópia da origem).
 */
object RandomPick {
    const val BOTH = 0
    const val PHOTOS = 1
    const val VIDEOS = 2

    val COUNTS = intArrayOf(5, 10, 20, 50, 100)
    const val MAX = 1000

    private val rnd = SecureRandom()

    fun matches(it: FileItem, type: Int): Boolean = !it.isDir && when (type) {
        PHOTOS -> it.kind == FileItem.KIND_IMAGE
        VIDEOS -> it.kind == FileItem.KIND_VIDEO
        else -> it.kind == FileItem.KIND_IMAGE || it.kind == FileItem.KIND_VIDEO
    }

    /** Origem do sorteio: arquivos do tipo pedido, sem itens repetidos (mesmo id). */
    fun pool(items: List<FileItem>, type: Int): List<FileItem> {
        val seen = HashSet<String>()
        return items.filter { matches(it, type) && seen.add(it.id) }
    }

    /** [n] itens distintos em ordem embaralhada; origem menor que [n]: todos, embaralhados. */
    fun pick(pool: List<FileItem>, n: Int): List<FileItem> {
        val a = ArrayList(pool)
        val k = minOf(n, a.size)
        for (i in 0 until k) {
            val j = i + rnd.nextInt(a.size - i)
            val t = a[i]; a[i] = a[j]; a[j] = t
        }
        return ArrayList(a.subList(0, k))
    }
}
