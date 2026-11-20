package app.index.files.data

import android.content.Context
import android.content.SharedPreferences
import androidx.appcompat.app.AppCompatDelegate

/** Preferências globais do app (SharedPreferences puro). */
class Prefs private constructor(private val sp: SharedPreferences) {

    /** AppCompatDelegate.MODE_NIGHT_NO / _YES / _FOLLOW_SYSTEM, ou Themes.MODE_SCHEDULE. */
    var themeMode: Int
        get() = sp.getInt("theme_mode", AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        set(v) = sp.edit().putInt("theme_mode", v).apply()

    /** Id do tema usado no modo claro (Themes.LIGHT). */
    var lightTheme: String
        get() = sp.getString("light_theme", null) ?: "papel"
        set(v) = sp.edit().putString("light_theme", v).apply()

    /** Id do tema usado no modo escuro. Quem usava "preto real" começa no OLED Puro. */
    var darkTheme: String
        get() = sp.getString("dark_theme", null) ?: if (sp.getBoolean("pure_black", false)) "oled" else "grafite"
        set(v) = sp.edit().putString("dark_theme", v).remove("pure_black").apply()

    /** Modo "por horário": início e fim do escuro, em minutos desde a meia-noite. */
    var scheduleStart: Int
        get() = sp.getInt("schedule_start", 19 * 60)
        set(v) = sp.edit().putInt("schedule_start", v).apply()

    var scheduleEnd: Int
        get() = sp.getInt("schedule_end", 7 * 60)
        set(v) = sp.edit().putInt("schedule_end", v).apply()

    /** Senha do acesso pelo computador (JSON do PasswordRec: só salt e hash). */
    var webPassword: String?
        get() = sp.getString("web_password", null)
        set(v) = sp.edit().putString("web_password", v).apply()

    /** Porta preferida do acesso pelo computador (se ocupada, tenta as seguintes). */
    var webPort: Int
        get() = sp.getInt("web_port", 8080)
        set(v) = sp.edit().putInt("web_port", v).apply()

    var viewGrid: Boolean
        get() = sp.getBoolean("view_grid", true)
        set(v) = sp.edit().putBoolean("view_grid", v).apply()

    /**
     * Largura mínima da miniatura na grade, em dp (fase 12): as colunas saem da largura
     * disponível. Quem tinha "N colunas" começa no tamanho equivalente num celular.
     */
    var thumbDp: Int
        get() = sp.getInt("thumb_dp", -1).takeIf { it > 0 } ?: when (sp.getInt("grid_columns", 3)) {
            2 -> 160
            4 -> 96
            5 -> 80
            6 -> 64
            else -> 112
        }
        set(v) = sp.edit().putInt("thumb_dp", v).remove("grid_columns").apply()

    var sortBy: Int
        get() = sp.getInt("sort_by", SortSpec.NAME)
        set(v) = sp.edit().putInt("sort_by", v).apply()

    var sortAsc: Boolean
        get() = sp.getBoolean("sort_asc", true)
        set(v) = sp.edit().putBoolean("sort_asc", v).apply()

    var foldersFirst: Boolean
        get() = sp.getBoolean("folders_first", true)
        set(v) = sp.edit().putBoolean("folders_first", v).apply()

    var showHidden: Boolean
        get() = sp.getBoolean("show_hidden", false)
        set(v) = sp.edit().putBoolean("show_hidden", v).apply()

    /** Limite do cache de thumbs em MB; 0 = ilimitado. */
    var thumbLimitMb: Int
        get() = sp.getInt("thumb_limit_mb", 500)
        set(v) = sp.edit().putInt("thumb_limit_mb", v).apply()

    /** true = filesDir (o sistema não apaga); false = cacheDir. */
    var thumbsInFiles: Boolean
        get() = sp.getBoolean("thumbs_in_files", false)
        set(v) = sp.edit().putBoolean("thumbs_in_files", v).apply()

    // ---- Pré-carga de miniaturas (fase 10) ----

    /** Drive: pré-carregar miniaturas só com Wi-Fi. */
    var preloadWifiOnly: Boolean
        get() = sp.getBoolean("preload_wifi_only", true)
        set(v) = sp.edit().putBoolean("preload_wifi_only", v).apply()

    /** Liberar a RAM da pré-carga depois de sair da lista sem voltar (minutos). */
    var releaseLeaveMin: Int
        get() = sp.getInt("release_leave_min", 1)
        set(v) = sp.edit().putInt("release_leave_min", v).apply()

    /** … com o app em segundo plano (minutos). */
    var releaseBgMin: Int
        get() = sp.getInt("release_bg_min", 2)
        set(v) = sp.edit().putInt("release_bg_min", v).apply()

    /** … sem interação (minutos). */
    var releaseIdleMin: Int
        get() = sp.getInt("release_idle_min", 5)
        set(v) = sp.edit().putInt("release_idle_min", v).apply()

    /** Conta Google conectada ao Drive (o token em si fica só em memória / Play Services). */
    var driveConnected: Boolean
        get() = sp.getBoolean("drive_connected", false)
        set(v) = sp.edit().putBoolean("drive_connected", v).apply()

    var driveEmail: String?
        get() = sp.getString("drive_email", null)
        set(v) = sp.edit().putString("drive_email", v).apply()

    /** Pacotes que aparecem no "Abrir com..." (vazio = todos os apps que abrem o tipo). */
    /** Fotos abrem no visualizador do próprio app (desligado: app externo, como antes). */
    var internalViewer: Boolean
        get() = sp.getBoolean("internal_viewer", true)
        set(v) = sp.edit().putBoolean("internal_viewer", v).apply()

    /** Como salvar a digitalização: 0 = PDF, 1 = imagens JPG, 2 = os dois. */
    var scanFormat: Int
        get() = sp.getInt("scan_format", 0)
        set(v) = sp.edit().putInt("scan_format", v).apply()

    /** Vídeos no player do app (falso: app externo, como antes). */
    var internalPlayer: Boolean
        get() = sp.getBoolean("internal_player", true)
        set(v) = sp.edit().putBoolean("internal_player", v).apply()

    /** Segundos que dois toques avançam (direita) e voltam (esquerda) no player. */
    var seekForwardSec: Int
        get() = sp.getInt("seek_forward", 10)
        set(v) = sp.edit().putInt("seek_forward", v).apply()
    var seekBackSec: Int
        get() = sp.getInt("seek_back", 10)
        set(v) = sp.edit().putInt("seek_back", v).apply()

    /** PDFs, Office, LibreOffice e textos no leitor do app (falso: app externo, como antes). */
    var internalDocs: Boolean
        get() = sp.getBoolean("internal_docs", true)
        set(v) = sp.edit().putBoolean("internal_docs", v).apply()

    /**
     * Pastas virtuais "Recentes" e "Recentes do Drive" adicionadas pelo usuário (Adicionar pasta).
     * Nada vem de fábrica. Quem atualiza de uma versão anterior mantém as que já via (a antiga
     * opção "Mostrar a pasta Recentes" com a permissão ou a conta já liberadas).
     */
    var recentAdded: Boolean
        get() = if (sp.contains("recent_added")) sp.getBoolean("recent_added", false)
        else (sp.getBoolean("show_recent", true) && Roots.hasAllFiles()).also { recentAdded = it }
        set(v) = sp.edit().putBoolean("recent_added", v).apply()

    var driveRecentAdded: Boolean
        get() = if (sp.contains("drive_recent_added")) sp.getBoolean("drive_recent_added", false)
        else (sp.getBoolean("show_recent", true) && driveConnected).also { driveRecentAdded = it }
        set(v) = sp.edit().putBoolean("drive_recent_added", v).apply()

    /** Filtro da pasta Recentes: tipo ([RecentSource.ALL]...) e período em dias. */
    var recentType: Int
        get() = sp.getInt("recent_type", 0)
        set(v) = sp.edit().putInt("recent_type", v).apply()
    var recentDays: Int
        get() = sp.getInt("recent_days", 30)
        set(v) = sp.edit().putInt("recent_days", v).apply()

    /**
     * Pasta aberta ao iniciar o app: raiz ([Roots.RECENT_ID] = Recentes; 0 = nenhuma, abre a
     * lista de pastas) e, para uma subpasta, os ids e nomes do caminho desde a raiz.
     */
    var startRoot: Long
        get() = sp.getLong("start_root", 0L)
        set(v) = sp.edit().putLong("start_root", v).apply()
    var startIds: List<String>
        get() = sp.getString("start_ids", null)?.split(SEP)?.filter { it.isNotEmpty() } ?: emptyList()
        set(v) = sp.edit().putString("start_ids", v.joinToString(SEP)).apply()
    var startNames: List<String>
        get() = sp.getString("start_names", null)?.split(SEP)?.filter { it.isNotEmpty() } ?: emptyList()
        set(v) = sp.edit().putString("start_names", v.joinToString(SEP)).apply()

    fun setStart(root: Long, ids: List<String> = emptyList(), names: List<String> = emptyList()) {
        sp.edit().putLong("start_root", root)
            .putString("start_ids", ids.joinToString(SEP))
            .putString("start_names", names.joinToString(SEP))
            .apply()
    }

    /** Visualizadores de imagem do "Abrir com..." (vazio = todos). */
    var openWithImageApps: Set<String>
        get() = sp.getStringSet("open_with_image_apps", null)?.toSet() ?: emptySet()
        set(v) = sp.edit().putStringSet("open_with_image_apps", HashSet(v)).apply()

    /** Apps da lista "Compartilhar" (vazio = todos). */
    var shareApps: Set<String>
        get() = sp.getStringSet("share_apps", null)?.toSet() ?: emptySet()
        set(v) = sp.edit().putStringSet("share_apps", HashSet(v)).apply()

    var openWithApps: Set<String>
        get() = sp.getStringSet("open_with_apps", null)?.toSet() ?: emptySet()
        set(v) = sp.edit().putStringSet("open_with_apps", HashSet(v)).apply()

    /** Como abrir arquivos do Drive: [OPEN_STREAM], [OPEN_DOWNLOAD] ou [OPEN_DRIVE]. */
    var driveOpenMode: Int
        get() = sp.getInt("drive_open_mode", OPEN_STREAM)
        set(v) = sp.edit().putInt("drive_open_mode", v).apply()

    // ---- Seleção aleatória (fase 9) ----

    /** Quantos arquivos sortear (5, 10, 20, 50, 100 ou outro valor de 1 a [RandomPick.MAX]). */
    var randomCount: Int
        get() = sp.getInt("random_count", 10).coerceIn(1, RandomPick.MAX)
        set(v) = sp.edit().putInt("random_count", v.coerceIn(1, RandomPick.MAX)).apply()

    /** [RandomPick.BOTH], [RandomPick.PHOTOS] ou [RandomPick.VIDEOS]. */
    var randomType: Int
        get() = sp.getInt("random_type", RandomPick.BOTH)
        set(v) = sp.edit().putInt("random_type", v).apply()

    /** Na navegação: sortear também entre os arquivos das subpastas (pelo índice). */
    var randomSubfolders: Boolean
        get() = sp.getBoolean("random_subfolders", false)
        set(v) = sp.edit().putBoolean("random_subfolders", v).apply()

    /** Padrão global; cada pasta pode ter o seu (AppDb.folderSort). */
    fun defaultSort() = SortSpec(sortBy, sortAsc, foldersFirst)

    companion object {
        const val OPEN_STREAM = 0
        const val OPEN_DOWNLOAD = 1
        const val OPEN_DRIVE = 2

        /** Separador dos caminhos da pasta inicial (não aparece em ids nem em nomes). */
        private const val SEP = "\u001F"

        @Volatile private var instance: Prefs? = null

        fun get(ctx: Context): Prefs = instance ?: synchronized(this) {
            instance ?: Prefs(
                ctx.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE),
            ).also { instance = it }
        }
    }
}
