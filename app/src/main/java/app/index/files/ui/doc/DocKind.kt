package app.index.files.ui.doc

import app.index.files.data.FileItem

/** Formatos que o leitor de documentos do app abre (só leitura). */
enum class DocKind {
    PDF, TEXT, MARKDOWN, CSV, TSV, HTML, RTF,
    DOCX, XLSX, PPTX, ODT, ODS, ODP,
    DOC, XLS, PPT;

    companion object {
        private val TEXT_EXT = setOf(
            "txt", "text", "log", "ini", "cfg", "conf", "properties", "json", "xml", "yaml", "yml", "toml",
            "kt", "kts", "java", "gradle", "js", "mjs", "ts", "tsx", "jsx", "py", "rb", "php", "c", "h", "cpp",
            "hpp", "cc", "cs", "go", "rs", "swift", "sh", "bash", "bat", "cmd", "ps1", "sql", "css", "scss",
            "srt", "vtt", "nfo", "m3u", "m3u8", "tex", "diff", "patch", "env", "gitignore",
        )

        /** null: não é documento que o leitor abre (fica com o app externo). */
        fun of(item: FileItem): DocKind? {
            if (item.isDir) return null
            val ext = item.name.substringAfterLast('.', "").lowercase()
            val mime = item.mime.lowercase()
            return when {
                ext == "pdf" || mime == "application/pdf" -> PDF
                ext == "md" || ext == "markdown" || mime == "text/markdown" -> MARKDOWN
                ext == "csv" || mime == "text/csv" -> CSV
                ext == "tsv" || mime == "text/tab-separated-values" -> TSV
                ext == "html" || ext == "htm" || ext == "xhtml" -> HTML
                ext == "rtf" || mime == "application/rtf" || mime == "text/rtf" -> RTF
                ext == "docx" || ext == "docm" || ext == "dotx" -> DOCX
                ext == "xlsx" || ext == "xlsm" || ext == "xltx" -> XLSX
                ext == "pptx" || ext == "pptm" || ext == "ppsx" || ext == "potx" -> PPTX
                ext == "odt" || ext == "ott" -> ODT
                ext == "ods" || ext == "ots" -> ODS
                ext == "odp" || ext == "otp" -> ODP
                ext == "doc" || ext == "dot" || mime == "application/msword" -> DOC
                ext == "xls" || ext == "xlt" || mime == "application/vnd.ms-excel" -> XLS
                ext == "ppt" || ext == "pps" || ext == "pot" || mime == "application/vnd.ms-powerpoint" -> PPT
                ext in TEXT_EXT -> TEXT
                mime.startsWith("text/") -> TEXT
                else -> null
            }
        }
    }
}
