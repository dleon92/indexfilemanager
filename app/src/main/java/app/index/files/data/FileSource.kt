package app.index.files.data

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.CancellationSignal
import java.io.InputStream

/**
 * Contrato comum para Local (SAF) e Drive (REST), para que a UI trate os dois
 * da mesma forma.
 */
interface FileSource {
    /**
     * Lista TODOS os filhos (inclusive ocultos; o filtro é aplicado na exibição,
     * para que o cache sirva às duas opções). Lança exceção se indisponível.
     */
    fun listChildren(folderId: String): List<FileItem>

    /** Uri para abrir o arquivo com ACTION_VIEW. */
    fun uriFor(item: FileItem): Uri

    /** Identifica a origem na chave do cache de thumbs (ex.: autoridade SAF, "drive"). */
    val cacheTag: String

    /**
     * Gera a miniatura (lado maior ≈ [maxSide]) a partir do arquivo original.
     * Chamado fora da main thread; null se não houver miniatura possível.
     */
    fun loadThumbnail(item: FileItem, maxSide: Int, signal: CancellationSignal?): Bitmap?

    /**
     * Data de modificação atual da pasta [folderId], sem listar (índice incremental: a data salva
     * na listagem da pasta de cima fica velha quando só a subpasta muda). -1 = desconhecida.
     */
    fun folderMtime(folderId: String): Long = -1L

    /**
     * Descritor do arquivo para leitura aleatória (visualizador: recorte nítido no zoom).
     * Null onde não há (Drive). Fora da main thread.
     */
    fun openFd(item: FileItem): android.os.ParcelFileDescriptor? = null

    /** Uri com o conteúdo do arquivo para compartilhar. Pode bloquear (Drive baixa): fora da main thread. */
    fun shareUri(item: FileItem): Uri = uriFor(item)

    /** Arquivo precisa ser baixado antes de abrir (mostra "Baixando…"). */
    fun needsDownload(item: FileItem): Boolean = false

    /**
     * Intent para abrir o arquivo. Pode bloquear (o Drive baixa o arquivo antes):
     * chamar fora da main thread.
     */
    fun openIntent(item: FileItem): Intent =
        Intent(Intent.ACTION_VIEW)
            .setDataAndType(uriFor(item), item.mime)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

    // ---- Edição (fase 7). Tudo bloqueante: fora da main thread. [parent] = pasta que contém o item. ----

    fun createFolder(parent: String, name: String): FileItem
    fun rename(parent: String, item: FileItem, name: String): FileItem
    /** Local: apaga de vez (o SAF não tem lixeira). Drive: manda para a lixeira. */
    fun delete(parent: String, item: FileItem)

    /** Move dentro da mesma origem sem copiar bytes; null = não deu (quem chama copia e apaga). */
    fun moveWithin(item: FileItem, from: String, to: String, name: String): FileItem?

    /** Copia um arquivo dentro da mesma origem sem passar pelo celular; null = não suportado. */
    fun copyWithin(item: FileItem, to: String, name: String): FileItem? = null

    fun openRead(item: FileItem): InputStream

    /** Cria o arquivo [name] em [parent] com o conteúdo de [input]. Em falha, não deixa arquivo pela metade. */
    fun write(
        parent: String, name: String, mime: String, size: Long, input: InputStream,
        cancelled: () -> Boolean, onBytes: (Long) -> Unit,
    ): FileItem
}

/** O item não pode ser editado por aqui (ex.: atalho do Drive, que aponta para outra pasta). */
class NotEditable(msg: String) : java.io.IOException(msg)

/** Item de listagem com só os campos necessários (projeção mínima). */
class FileItem(
    val id: String,
    val name: String,
    val mime: String,
    val size: Long,
    val modified: Long,
    val isDir: Boolean,
    /** Nome sem acentos e em minúsculas, calculado uma vez (ou lido do cache). */
    val sortKey: String = normalizeName(name),
) {
    val ext: String = if (isDir) "" else name.substringAfterLast('.', "").lowercase()

    // Preenchidos só nos resultados de busca (itens de várias raízes/pastas).
    var rootId: Long = 0
    var parentId: String = ""
    var parentPath: String = ""

    /** Drive: link de miniatura vindo da listagem (expira; não vai para o banco). */
    var thumbUrl: String? = null

    val kind: Int = when {
        isDir -> KIND_DIR
        mime.startsWith("image/") -> KIND_IMAGE
        mime.startsWith("video/") -> KIND_VIDEO
        mime.startsWith("audio/") -> KIND_AUDIO
        else -> KIND_FILE
    }

    /** Foto que o visualizador do app mostra (GIF animado vai para outro app: aqui ficaria parado). */
    val inViewer get() = kind == KIND_IMAGE && ext != "gif"

    /** Vídeo que o player do app toca (WMV/ASF não: sem leitor nem decodificador no Android). */
    val inPlayer get() = kind == KIND_VIDEO && ext != "wmv" && ext != "asf"

    companion object {
        const val KIND_DIR = 0
        const val KIND_IMAGE = 1
        const val KIND_VIDEO = 2
        const val KIND_AUDIO = 3
        const val KIND_FILE = 4
    }
}
