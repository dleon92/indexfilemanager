package app.index.files.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.fragment.app.Fragment
import app.index.files.R

/**
 * Tela inicial das configurações: uma lista curta de categorias. Cada uma abre o
 * [SettingsFragment] mostrando só as seções dela (ou outra tela, como o IndexWeb).
 */
class SettingsHomeFragment : Fragment() {

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val ctx = requireContext()
        val pad = (16 * resources.displayMetrics.density).toInt()
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, pad)
        }
        for (cat in SettingsCategory.ALL) {
            val row = inflater.inflate(R.layout.row_setting, box, false)
            row.findViewById<TextView>(R.id.title).setText(cat.title)
            row.findViewById<TextView>(R.id.summary).setText(cat.summary)
            row.setOnClickListener { open(cat) }
            box.addView(row)
        }
        return ScrollView(ctx).apply { addView(box) }
    }

    override fun onResume() {
        super.onResume()
        requireActivity().setTitle(R.string.settings)
    }

    private fun open(cat: SettingsCategory) {
        val main = activity as? MainActivity ?: return
        if (cat.key == SettingsCategory.WEB) main.push(WebFragment()) else main.push(SettingsFragment.newInstance(cat.key))
    }
}

/** Categorias das configurações e quais seções do layout cada uma mostra. */
class SettingsCategory(val key: String, val title: Int, val summary: Int, val sections: List<String>) {
    companion object {
        const val WEB = "web"
        val ALL = listOf(
            SettingsCategory("appearance", R.string.section_appearance, R.string.cat_appearance_sum, listOf("section_appearance")),
            SettingsCategory("browsing", R.string.cat_browsing, R.string.cat_browsing_sum, listOf("section_browsing")),
            SettingsCategory("open", R.string.section_open_share, R.string.cat_open_sum, listOf("section_open_share", "section_random")),
            SettingsCategory("thumbs", R.string.section_thumbs, R.string.cat_thumbs_sum, listOf("section_thumbs", "section_preload")),
            SettingsCategory("drive", R.string.section_drive, R.string.cat_drive_sum, listOf("section_drive")),
            SettingsCategory("security", R.string.section_security, R.string.cat_security_sum, listOf("section_security")),
            SettingsCategory(WEB, R.string.web_title, R.string.cat_web_sum, emptyList()),
            SettingsCategory("data", R.string.cat_data, R.string.cat_data_sum, listOf("section_data", "section_about")),
        )

        fun find(key: String?) = ALL.firstOrNull { it.key == key }
    }
}
