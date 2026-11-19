package app.index.files.ui.doc

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Leitor mínimo do contêiner OLE2 (Compound File Binary) dos .doc/.xls/.ppt antigos: só abre
 * os fluxos (streams) pelo nome. O arquivo inteiro fica na memória (lido com limite antes).
 */
class Ole2(private val data: ByteArray) {
    private val bb = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
    private val sectorSize: Int
    private val miniSectorSize: Int
    private val miniCutoff: Int
    private val fat: IntArray
    private val miniFat: IntArray
    private val entries = ArrayList<Entry>()
    private var miniStream: ByteArray = ByteArray(0)

    private class Entry(val name: String, val type: Int, val start: Int, val size: Long)

    init {
        if (data.size < 512 || bb.getLong(0) != SIGNATURE) throw IOException("não é um arquivo OLE2")
        sectorSize = 1 shl (bb.getShort(30).toInt() and 0xFFFF)
        miniSectorSize = 1 shl (bb.getShort(32).toInt() and 0xFFFF)
        val fatSectors = bb.getInt(44)
        val dirStart = bb.getInt(48)
        miniCutoff = bb.getInt(56)
        val miniFatStart = bb.getInt(60)
        val difatStart = bb.getInt(68)
        val difatCount = bb.getInt(72)
        if (sectorSize !in setOf(512, 4096) || fatSectors < 0 || fatSectors > 1_000_000) throw IOException("OLE2 inválido")

        // DIFAT: 109 no cabeçalho, o resto em setores encadeados.
        val difat = ArrayList<Int>()
        for (i in 0 until 109) bb.getInt(76 + i * 4).let { if (it >= 0) difat.add(it) }
        var ds = difatStart
        var guard = 0
        while (ds >= 0 && guard++ < difatCount + 1 && difat.size < fatSectors) {
            val off = offset(ds)
            if (off + sectorSize > data.size) break
            for (i in 0 until sectorSize / 4 - 1) bb.getInt(off + i * 4).let { if (it >= 0) difat.add(it) }
            ds = bb.getInt(off + sectorSize - 4)
        }
        val per = sectorSize / 4
        fat = IntArray(minOf(difat.size, fatSectors) * per)
        for ((k, s) in difat.take(fatSectors).withIndex()) {
            val off = offset(s)
            if (off + sectorSize > data.size) break
            for (i in 0 until per) fat[k * per + i] = bb.getInt(off + i * 4)
        }
        val mf = chain(miniFatStart)
        miniFat = IntArray(mf.size * per)
        for ((k, s) in mf.withIndex()) {
            val off = offset(s)
            for (i in 0 until per) miniFat[k * per + i] = bb.getInt(off + i * 4)
        }
        // Diretório: entradas de 128 bytes.
        for (s in chain(dirStart)) {
            val off = offset(s)
            for (e in 0 until sectorSize / 128) {
                val o = off + e * 128
                if (o + 128 > data.size) break
                val nameLen = (bb.getShort(o + 64).toInt() and 0xFFFF).coerceIn(0, 64)
                val name = String(data, o, (nameLen - 2).coerceAtLeast(0), Charsets.UTF_16LE)
                val type = data[o + 66].toInt()
                val start = bb.getInt(o + 116)
                val size = bb.getInt(o + 120).toLong() and 0xFFFFFFFFL
                entries.add(Entry(name, type, start, size))
            }
        }
        entries.firstOrNull { it.type == 5 }?.let { root -> miniStream = readChain(root.start, root.size, fat, sectorSize) { offset(it) } }
    }

    private fun offset(sector: Int) = (sector + 1) * sectorSize

    private fun chain(start: Int): List<Int> {
        val out = ArrayList<Int>()
        var s = start
        while (s >= 0 && s < fat.size && out.size < fat.size) {
            out.add(s)
            s = fat[s]
        }
        return out
    }

    private inline fun readChain(start: Int, size: Long, table: IntArray, unit: Int, off: (Int) -> Int): ByteArray {
        val len = minOf(size, Int.MAX_VALUE.toLong()).toInt()
        val out = ByteArray(len)
        var s = start
        var pos = 0
        var guard = 0
        val src = if (table === fat) data else miniStream
        while (s >= 0 && pos < len && guard++ < table.size + 1) {
            val o = off(s)
            val n = minOf(unit, len - pos, src.size - o)
            if (n <= 0) break
            System.arraycopy(src, o, out, pos, n)
            pos += n
            s = if (s < table.size) table[s] else -2
        }
        return if (pos == len) out else out.copyOf(pos)
    }

    /** Conteúdo do fluxo [name] (ignora maiúsculas), ou null. */
    fun stream(name: String): ByteArray? {
        val e = entries.firstOrNull { it.type == 2 && it.name.equals(name, ignoreCase = true) } ?: return null
        return if (e.size < miniCutoff) {
            readChain(e.start, e.size, miniFat, miniSectorSize) { it * miniSectorSize }
        } else {
            readChain(e.start, e.size, fat, sectorSize) { offset(it) }
        }
    }

    companion object {
        private const val SIGNATURE = -0x1ee54e5e1fee3030L // D0 CF 11 E0 A1 B1 1A E1 (little-endian)
    }
}
