package app.index.files.ui

import android.app.Activity
import android.app.UiModeManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import androidx.appcompat.app.AppCompatDelegate
import app.index.files.R
import app.index.files.data.Prefs
import java.util.Calendar

/**
 * Temas (fase 11). Claro e escuro são escolhidos à parte; o modo decide qual vale
 * agora: seguir o sistema, sempre claro, sempre escuro ou por horário.
 *
 * Cada tema é um estilo Theme.Index.<id> só com cores (atributos idx*). A Activity
 * aplica o estilo antes de super.onCreate, então não há troca visível de tema.
 */
object Themes {

    class Theme(val id: String, val name: String, val style: Int, val dark: Boolean)

    /** Valor de Prefs.themeMode para o modo "por horário". */
    const val MODE_SCHEDULE = 100

    val LIGHT = listOf(
        Theme("papel", "Papel", R.style.Theme_Index_papel, false),
        Theme("areia", "Areia", R.style.Theme_Index_areia, false),
        Theme("ceu", "Céu", R.style.Theme_Index_ceu, false),
        Theme("menta", "Menta", R.style.Theme_Index_menta, false),
        Theme("lavanda", "Lavanda", R.style.Theme_Index_lavanda, false),
        Theme("pessego", "Pêssego", R.style.Theme_Index_pessego, false),
        Theme("nuvem", "Nuvem", R.style.Theme_Index_nuvem, false),
        Theme("limao", "Limão", R.style.Theme_Index_limao, false),
        Theme("rosa", "Rosa Antigo", R.style.Theme_Index_rosa, false),
        Theme("artico", "Ártico", R.style.Theme_Index_artico, false),
    )

    val DARK = listOf(
        Theme("oled", "OLED Puro", R.style.Theme_Index_oled, true),
        Theme("grafite", "Grafite", R.style.Theme_Index_grafite, true),
        Theme("meianoite", "Meia-Noite", R.style.Theme_Index_meianoite, true),
        Theme("floresta", "Floresta", R.style.Theme_Index_floresta, true),
        Theme("vinho", "Vinho", R.style.Theme_Index_vinho, true),
        Theme("ambar", "Âmbar", R.style.Theme_Index_ambar, true),
        Theme("ametista", "Ametista", R.style.Theme_Index_ametista, true),
        Theme("oceano", "Oceano", R.style.Theme_Index_oceano, true),
        Theme("cafe", "Café", R.style.Theme_Index_cafe, true),
        Theme("neon", "Neon", R.style.Theme_Index_neon, true),
    )

    fun light(id: String) = LIGHT.firstOrNull { it.id == id } ?: LIGHT[0]
    fun dark(id: String) = DARK.firstOrNull { it.id == id } ?: DARK[1]

    private fun minutesNow(): Int {
        val c = Calendar.getInstance()
        return c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE)
    }

    /** No modo por horário: o escuro vale agora? (o intervalo pode cruzar a meia-noite) */
    fun scheduleIsDark(p: Prefs, m: Int = minutesNow()): Boolean {
        val s = p.scheduleStart
        val e = p.scheduleEnd
        return when {
            s == e -> false
            s < e -> m in s until e
            else -> m >= s || m < e
        }
    }

    /** Modo noturno efetivo (AppCompatDelegate.MODE_NIGHT_*), já resolvendo o horário. */
    fun nightMode(p: Prefs): Int = when (p.themeMode) {
        MODE_SCHEDULE -> if (scheduleIsDark(p)) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO
        AppCompatDelegate.MODE_NIGHT_YES, AppCompatDelegate.MODE_NIGHT_NO -> p.themeMode
        else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
    }

    /**
     * Aplica o modo. No Android 12+ o próprio sistema guarda o modo do app, e assim a
     * janela de abertura já nasce clara/escura certa; antes disso, AppCompat.
     * Sem efeito (nem recriação) se o modo não mudou.
     */
    fun apply(ctx: Context) {
        val mode = nightMode(Prefs.get(ctx))
        if (Build.VERSION.SDK_INT >= 31) {
            val um = ctx.getSystemService(UiModeManager::class.java)
            val sys = when (mode) {
                AppCompatDelegate.MODE_NIGHT_YES -> UiModeManager.MODE_NIGHT_YES
                AppCompatDelegate.MODE_NIGHT_NO -> UiModeManager.MODE_NIGHT_NO
                else -> UiModeManager.MODE_NIGHT_AUTO
            }
            if (AppCompatDelegate.getDefaultNightMode() != AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM) {
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
            }
            runCatching { um?.setApplicationNightMode(sys) }
        } else if (AppCompatDelegate.getDefaultNightMode() != mode) {
            AppCompatDelegate.setDefaultNightMode(mode)
        }
    }

    fun isNight(ctx: Context) =
        (ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    /** Tema que vale agora para esta Activity (claro ou escuro, conforme a configuração). */
    fun current(a: Activity): Theme {
        val p = Prefs.get(a)
        return if (isNight(a)) dark(p.darkTheme) else light(p.lightTheme)
    }

    /** Próxima virada do horário (ms a partir de agora), ou -1 fora do modo por horário. */
    fun msToNextBoundary(p: Prefs): Long {
        if (p.themeMode != MODE_SCHEDULE || p.scheduleStart == p.scheduleEnd) return -1
        val c = Calendar.getInstance()
        val now = c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE)
        val secs = c.get(Calendar.SECOND) * 1000L + c.get(Calendar.MILLISECOND)
        fun until(t: Int) = ((t - now + 1440) % 1440).let { if (it == 0) 1440 else it }
        val next = minOf(until(p.scheduleStart), until(p.scheduleEnd))
        return next * 60_000L - secs + 500
    }

    fun hhmm(min: Int) = "%02d:%02d".format(min / 60, min % 60)
}
