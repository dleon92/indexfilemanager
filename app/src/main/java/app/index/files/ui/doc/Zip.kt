package app.index.files.ui.doc

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.zip.ZipInputStream

/**
 * Lê só as partes pedidas de um pacote ZIP (Office/LibreOffice) direto do fluxo, na memória:
 * nada é gravado em disco (o arquivo pode estar numa pasta protegida).
 */
object Zip {
    const val MAX_ENTRY = 48 * 1024 * 1024
    const val MAX_TOTAL = 96 * 1024 * 1024

    fun read(input: InputStream, wanted: (String) -> Boolean): Map<String, ByteArray> {
        val out = HashMap<String, ByteArray>()
        var total = 0L
        ZipInputStream(input.buffered(64 * 1024)).use { z ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val e = z.nextEntry ?: break
                if (e.isDirectory || !wanted(e.name)) continue
                val bos = java.io.ByteArrayOutputStream(if (e.size in 1..MAX_ENTRY) e.size.toInt() else 64 * 1024)
                var n: Int
                while (bos.size() < MAX_ENTRY && total + bos.size() < MAX_TOTAL) {
                    n = z.read(buf)
                    if (n < 0) break
                    bos.write(buf, 0, n)
                }
                total += bos.size()
                out[e.name] = bos.toByteArray()
                if (total >= MAX_TOTAL) break
            }
        }
        if (out.isEmpty()) throw java.io.IOException("arquivo vazio ou corrompido")
        return out
    }

    /** Parser sem namespaces: os nomes chegam com prefixo ("w:p"); [local] tira o prefixo. */
    fun parser(b: ByteArray): XmlPullParser = Xml.newPullParser().apply {
        setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        setInput(ByteArrayInputStream(b), null)
    }

    fun local(name: String?): String = name?.substringAfter(':') ?: ""

    /** Atributo pelo nome local (ignora o prefixo: "w:val", "r:id"...). */
    fun attr(p: XmlPullParser, localName: String): String? {
        for (i in 0 until p.attributeCount) {
            if (local(p.getAttributeName(i)) == localName) return p.getAttributeValue(i)
        }
        return null
    }

    /**
     * Percorre o XML chamando [onStart]/[onEnd]/[onText]. Um XML cortado (parte grande demais)
     * termina sem erro: o que já foi lido é mostrado.
     */
    inline fun walk(
        b: ByteArray,
        onStart: (XmlPullParser, String) -> Unit,
        onEnd: (String) -> Unit = {},
        onText: (String) -> Unit = {},
    ) {
        val p = parser(b)
        try {
            var ev = p.eventType
            while (ev != XmlPullParser.END_DOCUMENT) {
                when (ev) {
                    XmlPullParser.START_TAG -> onStart(p, local(p.name))
                    XmlPullParser.END_TAG -> onEnd(local(p.name))
                    XmlPullParser.TEXT -> onText(p.text)
                }
                ev = p.next()
            }
        } catch (e: org.xmlpull.v1.XmlPullParserException) {
            // parte cortada no limite de tamanho: fica o começo
        } catch (e: java.io.IOException) {
        }
    }

    /** Destinos de um arquivo .rels: Id → caminho completo dentro do pacote. */
    fun rels(parts: Map<String, ByteArray>, relsPath: String, baseDir: String): Map<String, String> {
        val b = parts[relsPath] ?: return emptyMap()
        val m = HashMap<String, String>()
        walk(b, { p, n ->
            if (n == "Relationship") {
                val id = attr(p, "Id")
                val t = attr(p, "Target")
                if (id != null && t != null) m[id] = resolve(baseDir, t)
            }
        })
        return m
    }

    private fun resolve(base: String, target: String): String {
        if (target.startsWith("/")) return target.drop(1)
        val parts = ArrayList(base.split('/').filter { it.isNotEmpty() })
        for (seg in target.split('/')) {
            when (seg) {
                ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.size - 1)
                ".", "" -> {}
                else -> parts.add(seg)
            }
        }
        return parts.joinToString("/")
    }
}
