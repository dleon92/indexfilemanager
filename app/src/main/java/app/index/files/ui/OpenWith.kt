package app.index.files.ui

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.graphics.drawable.Drawable
import android.net.Uri
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import app.index.files.R
import app.index.files.data.Prefs

/**
 * "Abrir com..." próprio. A janela de escolha do sistema lê o arquivo para montar a prévia,
 * o que no streaming do Drive esperava o vídeo inteiro; esta lista só consulta quais apps
 * abrem o tipo (sem tocar no arquivo) e entrega ao app escolhido o mesmo Intent da abertura
 * normal.
 *
 * Em Configurações → "Abrir com..." dá para limitar a lista aos players (vídeo/áudio) e aos
 * visualizadores de imagem escolhidos.
 */
object OpenWith {

    class App(val pkg: String, val activity: String, val label: String, val icon: Drawable?)

    /** Apps que abrem [intent], em ordem alfabética, um por pacote. */
    fun appsFor(ctx: Context, intent: Intent): List<App> {
        val pm = ctx.packageManager
        val seen = HashSet<String>()
        return pm.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
            .filter { it.activityInfo.packageName != ctx.packageName && seen.add(it.activityInfo.packageName) }
            .map { it.toApp(pm) }
            .sortedBy { it.label.lowercase() }
    }

    private fun ResolveInfo.toApp(pm: PackageManager) =
        App(activityInfo.packageName, activityInfo.name, loadLabel(pm).toString(), runCatching { loadIcon(pm) }.getOrNull())

    /** Apps de vídeo instalados (para a configuração). */
    fun videoPlayers(ctx: Context): List<App> = appsFor(
        ctx,
        Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse("content://${ctx.packageName}.probe/v.mp4"), "video/*"),
    )

    /** Visualizadores de imagem instalados (para a configuração). */
    fun imageViewers(ctx: Context): List<App> = appsFor(
        ctx,
        Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse("content://${ctx.packageName}.probe/i.jpg"), "image/*"),
    )

    /** Mostra a lista e abre [intent] no app escolhido. */
    fun pick(a: Activity, intent: Intent) {
        val all = appsFor(a, intent)
        if (all.isEmpty()) {
            Toast.makeText(a, R.string.no_app_to_open, Toast.LENGTH_SHORT).show()
            return
        }
        // Fotos: lista de visualizadores; vídeo e áudio: lista de players; o resto: todos os apps.
        val type = intent.type.orEmpty()
        val prefs = Prefs.get(a)
        val chosen = when {
            type.startsWith("image/") -> prefs.openWithImageApps
            type.startsWith("video/") || type.startsWith("audio/") -> prefs.openWithApps
            else -> emptySet()
        }
        pickFrom(a, intent, all, chosen, R.string.open_with)
    }

    /** Apps que recebem um compartilhamento (para a configuração). */
    fun shareTargets(ctx: Context): List<App> = appsFor(ctx, Intent(Intent.ACTION_SEND).setType("*/*"))

    /** "Compartilhar": a mesma lista própria, filtrada pelos apps escolhidos nas configurações. */
    fun pickShare(a: Activity, intent: Intent) {
        val all = appsFor(a, intent)
        if (all.isEmpty()) {
            Toast.makeText(a, R.string.no_app_to_share, Toast.LENGTH_SHORT).show()
            return
        }
        pickFrom(a, intent, all, Prefs.get(a).shareApps, R.string.share)
    }

    private fun pickFrom(a: Activity, intent: Intent, all: List<App>, chosen: Set<String>, title: Int) {
        val filtered = if (chosen.isEmpty()) all else all.filter { it.pkg in chosen }
        // Nenhum dos escolhidos serve para este tipo (ex.: desinstalado): mostra todos.
        val list = filtered.ifEmpty { all }
        val showAll = list.size < all.size
        show(a, intent, list, if (showAll) all else null, title)
    }

    private fun show(a: Activity, intent: Intent, apps: List<App>, more: List<App>?, title: Int) {
        val d = a.resources.displayMetrics.density
        val iconPx = (32 * d).toInt()
        val rows = apps.size + if (more != null) 1 else 0
        val adapter = object : BaseAdapter() {
            override fun getCount() = rows
            override fun getItem(p: Int) = p
            override fun getItemId(p: Int) = p.toLong()
            override fun getView(p: Int, convert: View?, parent: ViewGroup): View {
                val tv = (convert as? TextView) ?: TextView(a).apply {
                    minHeight = (56 * d).toInt()
                    gravity = android.view.Gravity.CENTER_VERTICAL
                    setPadding((24 * d).toInt(), 0, (24 * d).toInt(), 0)
                    compoundDrawablePadding = (16 * d).toInt()
                    textSize = 16f
                    setTextColor(a.getColor(R.color.text))
                }
                if (p < apps.size) {
                    tv.text = apps[p].label
                    val ic = apps[p].icon?.mutate()?.also { it.setBounds(0, 0, iconPx, iconPx) }
                    tv.setCompoundDrawables(ic, null, null, null)
                } else {
                    tv.setText(R.string.open_with_all)
                    tv.setCompoundDrawables(null, null, null, null)
                }
                return tv
            }
        }
        AlertDialog.Builder(a)
            .setTitle(title)
            .setAdapter(adapter) { _, p ->
                if (p < apps.size) launch(a, intent, apps[p]) else show(a, intent, more!!, null, title)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun launch(a: Activity, intent: Intent, app: App) {
        try {
            a.startActivity(Intent(intent).setComponent(ComponentName(app.pkg, app.activity)))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(a, R.string.no_app_to_open, Toast.LENGTH_SHORT).show()
        } catch (e: SecurityException) {
            Toast.makeText(a, R.string.no_app_to_open, Toast.LENGTH_SHORT).show()
        }
    }
}
