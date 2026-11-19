package app.index.files.data

import android.content.Context
import app.index.files.security.PasswordRec
import app.index.files.security.Vault
import app.index.files.security.VaultEntry
import app.index.files.thumbs.ThumbnailManager

/** Marcar e desmarcar pastas protegidas, limpando ou refazendo o índice comum. Fora da main. */
object Protection {

    /**
     * Protege a pasta (raiz: [ids] com um elemento). Some na hora do banco comum (listagem
     * em cache, busca, contagens) e as miniaturas comuns dela são apagadas.
     */
    fun protect(ctx: Context, r: Root, ids: List<String>, names: List<String>, method: Int, pw: PasswordRec?): VaultEntry {
        val cache = ListingCache(ctx)
        val folderId = ids.last()
        val thumbs = ThumbnailManager.get(ctx)
        val src = Roots.source(ctx, r)
        thumbs.forget(src, cache.filesUnder(r.id, folderId))
        val e = Vault.protect(ctx, r, ids, names, method, pw)
        if (ids.size == 1) {
            cache.clearRoot(r.id)
            Roots.setCount(ctx, r.id, -1)
            Roots.setIndexedAt(ctx, r.id, 0)
        } else {
            cache.removeFolder(r.id, folderId)
            Roots.setCount(ctx, r.id, cache.countFiles(r.id))
        }
        thumbs.dropDecrypted()
        return e
    }

    /** Pasta apagada: proteções dela e de dentro dela saem do cofre (sem deixar entrada órfã). */
    fun onFolderDeleted(ctx: Context, rootId: Long, item: FileItem) {
        if (!item.isDir || Vault.isEmpty(ctx)) return
        for (id in Vault.forgetDeleted(ctx, rootId, item.id)) VaultIndex.drop(id)
    }

    /** Remove a proteção: apaga o índice e as miniaturas cifradas e devolve a pasta ao índice comum. */
    fun unprotect(ctx: Context, e: VaultEntry) {
        Vault.unprotect(ctx, e.id)
        VaultIndex.drop(e.id)
        val r = Roots.get(ctx, e.rootId) ?: return
        if (e.isRoot) {
            Indexer.start(ctx, r.id)
            return
        }
        // A pasta volta a aparecer na listagem de quem a contém…
        val parent = e.ids[e.ids.size - 2]
        val src = Roots.source(ctx, r)
        runCatching {
            ListingCache(ctx).write(r.id, parent, e.names.drop(1).dropLast(1).joinToString("/"), src.listChildren(parent))
        }
        // …e o conteúdo volta para a busca.
        Indexer.startSubtree(ctx, r.id, e.folderId, e.relPath)
    }
}
