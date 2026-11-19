package app.index.files.ui

import android.content.Context
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.TooltipCompat
import app.index.files.R
import app.index.files.data.Prefs
import app.index.files.data.RandomPick
import java.text.NumberFormat

/** Peças de interface da seleção aleatória, comuns à navegação e à pesquisa. */
object RandomUi {

    /** Botão da barra: toque sorteia, toque longo abre as opções. */
    fun bindButton(item: MenuItem?, onTap: () -> Unit, onLong: () -> Unit) {
        val v = item?.actionView ?: return
        TooltipCompat.setTooltipText(v, v.context.getString(R.string.random_tooltip))
        v.setOnClickListener { onTap() }
        v.setOnLongClickListener {
            onLong()
            true
        }
    }

    /**
     * Painel de opções (quantidade, tipo, subpastas). Os valores escolhidos viram o padrão.
     * [subfolders]: mostra a opção (só na navegação). [positive]: texto do botão de confirmar.
     */
    fun panel(ctx: Context, subfolders: Boolean, positive: Int, onOk: () -> Unit) {
        val prefs = Prefs.get(ctx)
        val v = LayoutInflater.from(ctx).inflate(R.layout.dialog_random, null)
        val counts = v.findViewById<LinearLayout>(R.id.counts)
        val custom = v.findViewById<EditText>(R.id.custom)
        val sub = v.findViewById<CompoundButton>(R.id.subfolders)

        var count = prefs.randomCount
        var isCustom = count !in RandomPick.COUNTS
        val countChips = ArrayList<Pair<Int, TextView>>() // 0 = "Outra"
        fun syncCounts() {
            for ((n, chip) in countChips) chip.isSelected = if (n == 0) isCustom else !isCustom && n == count
            custom.visibility = if (isCustom) View.VISIBLE else View.GONE
        }
        val inflater = LayoutInflater.from(ctx)
        for (n in RandomPick.COUNTS.toList() + 0) {
            val chip = inflater.inflate(R.layout.chip_item, counts, false) as TextView
            chip.text = if (n == 0) ctx.getString(R.string.random_custom) else n.toString()
            chip.setOnClickListener {
                if (n == 0) {
                    isCustom = true
                    custom.requestFocus()
                } else {
                    isCustom = false
                    count = n
                }
                syncCounts()
            }
            counts.addView(chip)
            countChips.add(n to chip)
        }
        if (isCustom) custom.setText(count.toString())
        syncCounts()

        var type = prefs.randomType
        val typeChips = listOf(
            RandomPick.BOTH to v.findViewById<TextView>(R.id.type_both),
            RandomPick.PHOTOS to v.findViewById<TextView>(R.id.type_photos),
            RandomPick.VIDEOS to v.findViewById<TextView>(R.id.type_videos),
        )
        fun syncTypes() = typeChips.forEach { (t, chip) -> chip.isSelected = t == type }
        typeChips.forEach { (t, chip) -> chip.setOnClickListener { type = t; syncTypes() } }
        syncTypes()

        sub.visibility = if (subfolders) View.VISIBLE else View.GONE
        sub.isChecked = prefs.randomSubfolders

        val d = AlertDialog.Builder(ctx)
            .setTitle(R.string.random_options)
            .setView(v)
            .setPositiveButton(positive, null)
            .setNegativeButton(R.string.cancel, null)
            .create()
        d.setOnShowListener {
            d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val n = if (isCustom) custom.text.toString().toIntOrNull() ?: 0 else count
                if (n !in 1..RandomPick.MAX) {
                    custom.error = ctx.getString(R.string.random_custom_hint)
                    return@setOnClickListener
                }
                prefs.randomCount = n
                prefs.randomType = type
                if (subfolders) prefs.randomSubfolders = sub.isChecked
                d.dismiss()
                onOk()
            }
        }
        d.show()
    }

    /** "10 aleatórios de 1.000". */
    fun banner(ctx: Context, shown: Int, total: Int): String =
        ctx.resources.getQuantityString(R.plurals.random_banner, shown, shown, NumberFormat.getIntegerInstance().format(total))

    fun toastNone(ctx: Context, type: Int) = Toast.makeText(
        ctx,
        when (type) {
            RandomPick.PHOTOS -> R.string.random_none_photos
            RandomPick.VIDEOS -> R.string.random_none_videos
            else -> R.string.random_none_both
        },
        Toast.LENGTH_SHORT,
    ).show()

    /** Resumo para Configurações: "10 · Fotos e vídeos · sem subpastas". */
    fun summary(ctx: Context): String {
        val p = Prefs.get(ctx)
        val type = ctx.getString(
            when (p.randomType) {
                RandomPick.PHOTOS -> R.string.random_type_photos
                RandomPick.VIDEOS -> R.string.random_type_videos
                else -> R.string.random_type_both
            },
        )
        val sub = ctx.getString(if (p.randomSubfolders) R.string.random_with_subfolders else R.string.random_without_subfolders)
        return "${p.randomCount} · $type · $sub"
    }
}
