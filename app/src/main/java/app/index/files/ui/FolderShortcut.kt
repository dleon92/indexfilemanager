package app.index.files.ui

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import app.index.files.R
import app.index.files.data.Root

/**
 * Atalho de uma pasta adicionada na tela inicial do Android. Abre o Index direto na pasta; a
 * checagem (pasta removida, protegida, indisponível) é feita ao abrir, na MainActivity.
 */
object FolderShortcut {
    const val ACTION_OPEN_ROOT = "app.index.files.action.OPEN_ROOT"
    const val EXTRA_ROOT = "root_id"

    private fun id(rootId: Long) = "root_$rootId"

    fun pin(ctx: Context, r: Root) {
        if (!ShortcutManagerCompat.isRequestPinShortcutSupported(ctx)) {
            Toast.makeText(ctx, R.string.shortcut_unsupported, Toast.LENGTH_LONG).show()
            return
        }
        val icon = when {
            r.isRecent -> R.drawable.ic_shortcut_recent
            r.isDrive -> R.drawable.ic_shortcut_cloud
            else -> R.drawable.ic_shortcut_folder
        }
        val intent = Intent(ctx, MainActivity::class.java)
            .setAction(ACTION_OPEN_ROOT)
            .putExtra(EXTRA_ROOT, r.id)
        val info = ShortcutInfoCompat.Builder(ctx, id(r.id))
            .setShortLabel(r.alias)
            .setLongLabel(r.alias)
            .setIcon(IconCompat.createWithResource(ctx, icon))
            .setIntent(intent)
            .build()
        ShortcutManagerCompat.requestPinShortcut(ctx, info, null)
    }

    /** Pasta removida da lista: o atalho fica cinza e avisa, em vez de abrir outra coisa. */
    fun removed(ctx: Context, rootId: Long) {
        runCatching {
            ShortcutManagerCompat.disableShortcuts(ctx, listOf(id(rootId)), ctx.getString(R.string.shortcut_folder_removed))
        }
    }
}
