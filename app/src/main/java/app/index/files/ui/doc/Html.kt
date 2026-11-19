package app.index.files.ui.doc

/**
 * Monta o HTML mostrado no leitor. Todo texto vindo do arquivo passa por [esc]: o WebView
 * roda sem JavaScript e sem rede, e nada do documento vira marcação.
 */
class Html(private val limitChars: Int = 6_000_000) {
    private val sb = StringBuilder(64 * 1024)

    /** Passou do limite: o resto do documento é cortado (aviso no fim). */
    var truncated = false
        private set

    val full get() = sb.length >= limitChars

    fun raw(s: String): Html {
        if (sb.length < limitChars) sb.append(s) else truncated = true
        return this
    }

    fun text(s: CharSequence): Html {
        if (sb.length >= limitChars) {
            truncated = true
            return this
        }
        escTo(sb, s)
        return this
    }

    fun tag(open: String, content: CharSequence, close: String = open.substringBefore(' ')): Html {
        raw("<$open>"); text(content); raw("</$close>")
        return this
    }

    val length get() = sb.length

    fun build(css: Css, extraCss: String = ""): String {
        val body = sb.toString()
        val note = if (truncated) "<p class=\"note\">Documento grande: só o começo é mostrado. Use “Abrir com…” para ver tudo.</p>" else ""
        return "<!doctype html><html><head><meta charset=\"utf-8\">" +
            "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">" +
            "<style>${css.base}$extraCss</style></head><body>$body$note</body></html>"
    }

    /** Cores do tema do app (claro/escuro) e estilo comum a todos os formatos. */
    class Css(bg: Int, fg: Int, muted: Int, accent: Int, line: Int) {
        val base = """
            :root{--bg:${hex(bg)};--fg:${hex(fg)};--mu:${hex(muted)};--ac:${hex(accent)};--ln:${hex(line)}}
            html,body{background:var(--bg);color:var(--fg);margin:0}
            body{padding:16px 16px 48px;font:16px/1.55 sans-serif;word-wrap:break-word;overflow-wrap:anywhere}
            h1,h2,h3,h4,h5,h6{line-height:1.25;margin:1.2em 0 .5em}
            h1{font-size:1.6em}h2{font-size:1.35em}h3{font-size:1.15em}
            p{margin:.45em 0}a{color:var(--ac)}
            pre,code{font-family:monospace;font-size:.92em}
            pre{white-space:pre-wrap;margin:0}
            pre.code,code.blk{display:block;white-space:pre-wrap;background:var(--ln);padding:10px;border-radius:6px;margin:.6em 0}
            code{background:var(--ln);padding:1px 4px;border-radius:4px}
            blockquote{margin:.6em 0;padding:0 0 0 12px;border-left:3px solid var(--mu);color:var(--mu)}
            table{border-collapse:collapse;margin:.6em 0;font-size:.92em}
            td,th{border:1px solid var(--ln);padding:4px 8px;vertical-align:top;text-align:left}
            th{background:var(--ln)}
            td.n{text-align:right;font-variant-numeric:tabular-nums}
            .sheet{overflow-x:auto}
            .rh{color:var(--mu);background:var(--ln);text-align:right}
            .slide{border:1px solid var(--ln);border-radius:8px;padding:8px 14px;margin:0 0 16px}
            .slide>.no{color:var(--mu);font-size:.8em;margin:4px 0}
            .note{color:var(--mu);font-style:italic;margin-top:24px}
            hr{border:0;border-top:1px solid var(--ln);margin:1em 0}
            img{max-width:100%}
            ul,ol{padding-left:1.4em;margin:.4em 0}
        """.trimIndent()

        private fun hex(c: Int) = String.format("#%06X", c and 0xFFFFFF)
    }

    companion object {
        fun esc(s: CharSequence): String = StringBuilder(s.length + 16).also { escTo(it, s) }.toString()

        fun escTo(sb: StringBuilder, s: CharSequence) {
            for (ch in s) {
                when (ch) {
                    '<' -> sb.append("&lt;")
                    '>' -> sb.append("&gt;")
                    '&' -> sb.append("&amp;")
                    '"' -> sb.append("&quot;")
                    '\u0000' -> {}
                    else -> sb.append(ch)
                }
            }
        }
    }
}
