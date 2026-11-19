package app.index.files.ui

import android.app.TimePickerDialog
import android.os.Bundle
import android.text.format.DateFormat
import android.text.format.Formatter
import android.view.View
import android.widget.CompoundButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatDelegate
import androidx.fragment.app.Fragment
import app.index.files.Bg
import app.index.files.R
import app.index.files.data.Prefs
import app.index.files.data.Roots
import app.index.files.security.AppLock
import app.index.files.thumbs.ThumbnailManager

/** Configurações. Layout próprio, sem androidx.preference. */
class SettingsFragment : Fragment(R.layout.fragment_settings) {

    private lateinit var prefs: Prefs

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        prefs = Prefs.get(requireContext())

        bindSecurity(view)

        bindAppearance(view)
        bindPlayers(view)

        // --- Navegação ---
        val viewModes = arrayOf(getString(R.string.view_grid), getString(R.string.view_list))
        choiceRow(view, R.id.row_view_mode, R.string.default_view, viewModes, { if (prefs.viewGrid) 0 else 1 }) {
            prefs.viewGrid = it == 0
        }
        // Tamanho mínimo da miniatura: as colunas saem da largura da tela (também por pinça).
        val sizes = GridZoom.STEPS.map { if (it >= GridZoom.FILL) getString(R.string.thumb_size_fill) else getString(R.string.thumb_size_value, it) }.toTypedArray()
        choiceRow(view, R.id.row_columns, R.string.columns, sizes, {
            GridZoom.STEPS.indexOfFirst { it >= prefs.thumbDp }.let { if (it < 0) GridZoom.STEPS.size - 1 else it }
        }) {
            prefs.thumbDp = GridZoom.STEPS[it]
        }
        val sorts = arrayOf(
            getString(R.string.sort_name),
            getString(R.string.sort_size),
            getString(R.string.sort_type),
            getString(R.string.sort_date),
        )
        choiceRow(view, R.id.row_sort, R.string.default_sort, sorts, { prefs.sortBy }) { prefs.sortBy = it }
        switch(view, R.id.sort_desc, !prefs.sortAsc) { prefs.sortAsc = !it }
        switch(view, R.id.folders_first, prefs.foldersFirst) { prefs.foldersFirst = it }
        switch(view, R.id.show_hidden, prefs.showHidden) { prefs.showHidden = it }
        switch(view, R.id.internal_viewer, prefs.internalViewer) { prefs.internalViewer = it }
        switch(view, R.id.internal_docs, prefs.internalDocs) { prefs.internalDocs = it }
        switch(view, R.id.internal_player, prefs.internalPlayer) { prefs.internalPlayer = it }
        val seekOpts = intArrayOf(5, 10, 15, 30, 60)
        val seekLabels = seekOpts.map { getString(R.string.seconds_n, it) }.toTypedArray()
        choiceRow(view, R.id.row_seek_forward, R.string.seek_forward_title, seekLabels,
            { seekOpts.indexOf(prefs.seekForwardSec).coerceAtLeast(0) }) { prefs.seekForwardSec = seekOpts[it] }
        choiceRow(view, R.id.row_seek_back, R.string.seek_back_title, seekLabels,
            { seekOpts.indexOf(prefs.seekBackSec).coerceAtLeast(0) }) { prefs.seekBackSec = seekOpts[it] }
        bindStartFolder(view)
        // Sobre: versão instalada (do pacote, sem BuildConfig).
        val ctx = requireContext()
        val version = runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull().orEmpty()
        view.findViewById<TextView>(R.id.about_version).text = getString(R.string.about_version, version)

        // --- Seleção aleatória ---
        val randomRow = view.findViewById<View>(R.id.row_random)
        randomRow.findViewById<TextView>(R.id.title).setText(R.string.random_defaults)
        val randomSummary = randomRow.findViewById<TextView>(R.id.summary)
        randomSummary.text = RandomUi.summary(requireContext())
        randomRow.setOnClickListener {
            RandomUi.panel(requireContext(), subfolders = true, positive = R.string.save) {
                if (this.view != null) randomSummary.text = RandomUi.summary(requireContext())
            }
        }

        // --- Miniaturas ---
        val tm = ThumbnailManager.get(requireContext())
        val sizeRow = view.findViewById<View>(R.id.row_thumb_size)
        sizeRow.findViewById<TextView>(R.id.title).setText(R.string.thumb_cache_size)
        val sizeText = sizeRow.findViewById<TextView>(R.id.summary)
        refreshSize(tm, sizeText)

        val limits = intArrayOf(200, 500, 1024, 0)
        val limitNames = arrayOf("200 MB", "500 MB", "1 GB", getString(R.string.unlimited))
        choiceRow(view, R.id.row_thumb_limit, R.string.thumb_cache_limit, limitNames, {
            limits.indexOf(prefs.thumbLimitMb).coerceAtLeast(0)
        }) {
            prefs.thumbLimitMb = limits[it]
            Bg.run({ tm.disk.trimIfNeeded() }) { refreshSize(tm, sizeText) }
        }

        val locations = arrayOf(getString(R.string.thumb_loc_cache), getString(R.string.thumb_loc_files))
        choiceRow(view, R.id.row_thumb_location, R.string.thumb_location, locations, {
            if (prefs.thumbsInFiles) 1 else 0
        }) {
            val inFiles = it == 1
            if (inFiles != prefs.thumbsInFiles) Bg.run({ tm.relocate(inFiles) }) { refreshSize(tm, sizeText) }
        }

        // Pré-carga (fase 10): Wi-Fi e prazos de liberação da RAM.
        switch(view, R.id.preload_wifi, prefs.preloadWifiOnly) { prefs.preloadWifiOnly = it }
        val minutes = intArrayOf(1, 2, 5, 10)
        val minuteNames = resources.getStringArray(R.array.release_minutes)
        choiceRow(view, R.id.row_release_leave, R.string.release_leave, minuteNames, {
            minutes.indexOf(prefs.releaseLeaveMin).coerceAtLeast(0)
        }) { prefs.releaseLeaveMin = minutes[it] }
        choiceRow(view, R.id.row_release_bg, R.string.release_bg, minuteNames, {
            minutes.indexOf(prefs.releaseBgMin).coerceAtLeast(0)
        }) { prefs.releaseBgMin = minutes[it] }
        choiceRow(view, R.id.row_release_idle, R.string.release_idle, minuteNames, {
            minutes.indexOf(prefs.releaseIdleMin).coerceAtLeast(0)
        }) { prefs.releaseIdleMin = minutes[it] }

        val clearRow = view.findViewById<View>(R.id.row_thumb_clear)
        clearRow.findViewById<TextView>(R.id.title).setText(R.string.thumb_clear)
        clearRow.findViewById<TextView>(R.id.summary).setText(R.string.thumb_clear_hint)
        clearRow.setOnClickListener {
            AlertDialog.Builder(requireContext())
                .setTitle(R.string.thumb_clear)
                .setMessage(R.string.thumb_clear_confirm)
                .setPositiveButton(R.string.clear) { _, _ ->
                    Bg.run({ tm.clearAll() }) { refreshSize(tm, sizeText) }
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }

        // --- Google Drive ---
        val acct = view.findViewById<View>(R.id.row_drive_account)
        acct.findViewById<TextView>(R.id.title).setText(R.string.drive_account)
        renderAccount(acct)
        acct.setOnClickListener {
            val email = prefs.driveEmail
            if (!prefs.driveConnected) {
                (activity as MainActivity).connectDrive()
                return@setOnClickListener
            }
            AlertDialog.Builder(requireContext())
                .setTitle(R.string.drive_disconnect)
                .setMessage(getString(R.string.drive_disconnect_confirm, email ?: ""))
                .setPositiveButton(R.string.drive_disconnect) { _, _ -> disconnect(acct) }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
        parentFragmentManager.setFragmentResultListener(MainActivity.ROOTS_CHANGED, viewLifecycleOwner) { _, _ ->
            renderAccount(acct)
        }
        // Índices = Prefs.OPEN_STREAM / OPEN_DOWNLOAD / OPEN_DRIVE.
        val openModes = arrayOf(
            getString(R.string.drive_open_stream),
            getString(R.string.drive_open_download),
            getString(R.string.drive_open_link),
        )
        choiceRow(view, R.id.row_drive_open, R.string.drive_open_mode, openModes, { prefs.driveOpenMode }) {
            prefs.driveOpenMode = it
        }
    }

    // --- Aparência ---

    private fun bindAppearance(view: View) {
        val group = view.findViewById<RadioGroup>(R.id.theme_group)
        val start = view.findViewById<View>(R.id.row_schedule_start)
        val end = view.findViewById<View>(R.id.row_schedule_end)
        val lightRow = view.findViewById<View>(R.id.row_light_theme)
        val darkRow = view.findViewById<View>(R.id.row_dark_theme)
        start.findViewById<TextView>(R.id.title).setText(R.string.schedule_start)
        end.findViewById<TextView>(R.id.title).setText(R.string.schedule_end)
        lightRow.findViewById<TextView>(R.id.title).setText(R.string.light_theme)
        darkRow.findViewById<TextView>(R.id.title).setText(R.string.dark_theme)

        fun render() {
            val sched = prefs.themeMode == Themes.MODE_SCHEDULE
            start.visibility = if (sched) View.VISIBLE else View.GONE
            end.visibility = if (sched) View.VISIBLE else View.GONE
            start.findViewById<TextView>(R.id.summary).text = Themes.hhmm(prefs.scheduleStart)
            end.findViewById<TextView>(R.id.summary).text = Themes.hhmm(prefs.scheduleEnd)
            lightRow.findViewById<TextView>(R.id.summary).text = Themes.light(prefs.lightTheme).name
            darkRow.findViewById<TextView>(R.id.summary).text = Themes.dark(prefs.darkTheme).name
        }

        group.check(
            when (prefs.themeMode) {
                AppCompatDelegate.MODE_NIGHT_NO -> R.id.theme_light
                AppCompatDelegate.MODE_NIGHT_YES -> R.id.theme_dark
                Themes.MODE_SCHEDULE -> R.id.theme_schedule
                else -> R.id.theme_system
            },
        )
        group.setOnCheckedChangeListener { _, id ->
            prefs.themeMode = when (id) {
                R.id.theme_light -> AppCompatDelegate.MODE_NIGHT_NO
                R.id.theme_dark -> AppCompatDelegate.MODE_NIGHT_YES
                R.id.theme_schedule -> Themes.MODE_SCHEDULE
                else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            }
            render()
            // Recria a Activity só se o modo efetivo mudar.
            main.onThemeClock()
        }

        fun time(row: View, get: () -> Int, set: (Int) -> Unit) = row.setOnClickListener {
            val m = get()
            TimePickerDialog(requireContext(), { _, h, min ->
                set(h * 60 + min)
                render()
                main.onThemeClock()
            }, m / 60, m % 60, DateFormat.is24HourFormat(requireContext())).show()
        }
        time(start, { prefs.scheduleStart }) { prefs.scheduleStart = it }
        time(end, { prefs.scheduleEnd }) { prefs.scheduleEnd = it }

        lightRow.setOnClickListener {
            ThemeUi.pick(requireContext(), R.string.light_theme, Themes.LIGHT, prefs.lightTheme) {
                prefs.lightTheme = it
                render()
                main.onThemeChoiceChanged()
            }
        }
        darkRow.setOnClickListener {
            ThemeUi.pick(requireContext(), R.string.dark_theme, Themes.DARK, prefs.darkTheme) {
                prefs.darkTheme = it
                render()
                main.onThemeChoiceChanged()
            }
        }
        render()
    }

    // --- "Abrir com..." ---

    private fun bindPlayers(view: View) {
        bindAppList(view, R.id.row_players, R.string.players_title, R.string.players_none, OpenWith::videoPlayers,
            { prefs.openWithApps }, { prefs.openWithApps = it })
        bindAppList(view, R.id.row_image_viewers, R.string.image_viewers_title, R.string.image_viewers_none, OpenWith::imageViewers,
            { prefs.openWithImageApps }, { prefs.openWithImageApps = it })
        bindAppList(view, R.id.row_share_apps, R.string.share_apps_title, R.string.share_apps_none, OpenWith::shareTargets,
            { prefs.shareApps }, { prefs.shareApps = it })
    }

    /** Quais apps aparecem no "Abrir com..." para um tipo (nenhum marcado = todos os apps). */
    private fun bindAppList(
        view: View, rowId: Int, titleRes: Int, noneRes: Int,
        query: (android.content.Context) -> List<OpenWith.App>,
        get: () -> Set<String>, set: (Set<String>) -> Unit,
    ) {
        val row = view.findViewById<View>(rowId)
        row.findViewById<TextView>(R.id.title).setText(titleRes)
        val summary = row.findViewById<TextView>(R.id.summary)
        val app = requireContext().applicationContext

        fun render(players: List<OpenWith.App>?) {
            val chosen = get()
            summary.text = when {
                chosen.isEmpty() -> getString(R.string.players_all)
                players == null -> resources.getQuantityString(R.plurals.players_count, chosen.size, chosen.size)
                else -> players.filter { it.pkg in chosen }.joinToString(", ") { it.label }
                    .ifEmpty { getString(R.string.players_all) }
            }
        }
        render(null)
        Bg.run({ query(app) }) { res -> if (view.isAttachedToWindow) render(res.getOrNull()) }

        row.setOnClickListener {
            Bg.run({ query(app) }) { res ->
                if (!isAdded) return@run
                val players = res.getOrDefault(emptyList())
                if (players.isEmpty()) {
                    toast(noneRes)
                    return@run
                }
                val chosen = get().toMutableSet()
                val names = players.map { it.label }.toTypedArray()
                val checked = BooleanArray(players.size) { players[it].pkg in chosen }
                AlertDialog.Builder(requireContext())
                    .setTitle(titleRes)
                    .setMultiChoiceItems(names, checked) { _, i, on -> checked[i] = on }
                    .setPositiveButton(R.string.save) { _, _ ->
                        // Marcados entre os instalados; os de fora da lista (desinstalados) saem.
                        set(players.filterIndexed { i, _ -> checked[i] }.map { it.pkg }.toSet())
                        render(players)
                    }
                    .setNeutralButton(R.string.players_reset) { _, _ ->
                        set(emptySet())
                        render(players)
                    }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
            }
        }
    }

    // --- Segurança ---

    private val main get() = requireActivity() as MainActivity

    private fun bindSecurity(view: View) {
        val ctx = requireContext()
        val enabled = view.findViewById<CompoundButton>(R.id.lock_enabled)
        val method = view.findViewById<View>(R.id.row_lock_method)
        val change = view.findViewById<View>(R.id.row_lock_change)
        val bio = view.findViewById<CompoundButton>(R.id.lock_biometric)
        val relock = view.findViewById<View>(R.id.row_lock_relock)
        val secure = view.findViewById<CompoundButton>(R.id.lock_secure)
        val reset = view.findViewById<View>(R.id.row_reset)

        method.findViewById<TextView>(R.id.title).setText(R.string.lock_method)
        change.findViewById<TextView>(R.id.title).setText(R.string.lock_change)
        reset.findViewById<TextView>(R.id.title).apply {
            setText(R.string.reset_title)
            // Ação destrutiva: título na cor de aviso do tema.
            setTextColor(requireContext().getColor(R.color.warning))
        }
        reset.findViewById<TextView>(R.id.summary).setText(R.string.reset_summary)

        // A chave só reflete o estado salvo; ligar/desligar passa pelos fluxos abaixo.
        enabled.setOnClickListener {
            enabled.isChecked = AppLock.enabled(ctx)
            if (AppLock.enabled(ctx)) {
                main.auth.confirmCurrent {
                    AppLock.disable(ctx)
                    main.applySecureFlag()
                    renderSecurity()
                    toast(R.string.lock_disabled)
                }
            } else {
                chooseMethod()
            }
        }
        method.setOnClickListener { main.auth.confirmCurrent { chooseMethod() } }
        change.setOnClickListener {
            main.auth.confirmCurrent {
                main.auth.setupPassword(onDone = { renderSecurity(); toast(R.string.lock_changed) })
            }
        }
        bio.setOnClickListener {
            val want = bio.isChecked
            bio.isChecked = !want
            main.auth.confirmCurrent {
                AppLock.setBiometric(ctx, want)
                renderSecurity()
            }
        }
        val relockNames = resources.getStringArray(R.array.lock_relock_options)
        choiceRow(view, R.id.row_lock_relock, R.string.lock_relock, relockNames, { AppLock.relockIndex(ctx) }) {
            AppLock.setRelockIndex(ctx, it)
        }
        secure.setOnCheckedChangeListener { _, on ->
            if (on == AppLock.secureSetting(ctx)) return@setOnCheckedChangeListener
            AppLock.setSecure(ctx, on)
            main.applySecureFlag()
        }
        reset.setOnClickListener {
            AlertDialog.Builder(ctx)
                .setTitle(R.string.reset_title)
                .setMessage(R.string.reset_msg)
                .setPositiveButton(R.string.reset_confirm) { _, _ -> main.auth.confirmCurrent { main.resetApp() } }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
        renderSecurity()
    }

    private fun renderSecurity() {
        val view = view ?: return
        val ctx = requireContext()
        val on = AppLock.enabled(ctx)
        view.findViewById<CompoundButton>(R.id.lock_enabled).isChecked = on
        view.findViewById<View>(R.id.lock_options).visibility = if (on) View.VISIBLE else View.GONE
        if (!on) return
        val own = AppLock.method(ctx) == AppLock.METHOD_OWN
        val pin = AppLock.kind(ctx) == AppLock.KIND_PIN
        view.findViewById<View>(R.id.row_lock_method).findViewById<TextView>(R.id.summary).setText(
            when {
                !own -> R.string.lock_method_device
                pin -> R.string.lock_method_pin
                else -> R.string.lock_method_password
            },
        )
        view.findViewById<View>(R.id.row_lock_change).apply {
            visibility = if (own) View.VISIBLE else View.GONE
            findViewById<TextView>(R.id.summary).setText(if (pin) R.string.lock_change_pin_hint else R.string.lock_change_password_hint)
        }
        view.findViewById<CompoundButton>(R.id.lock_biometric).apply {
            visibility = if (own && AppLock.biometricAvailable(ctx)) View.VISIBLE else View.GONE
            isChecked = AppLock.biometric(ctx)
        }
        view.findViewById<CompoundButton>(R.id.lock_secure).isChecked = AppLock.secureSetting(ctx)
    }

    /** Escolha do método ao ativar (ou trocar): bloqueio do celular ou senha própria. */
    private fun chooseMethod() {
        val ctx = requireContext()
        val secure = AppLock.deviceSecure(ctx)
        val opts = arrayOf(
            getString(if (secure) R.string.lock_choose_device else R.string.lock_choose_device_off),
            getString(R.string.lock_choose_own),
        )
        AlertDialog.Builder(ctx)
            .setTitle(R.string.lock_choose_title)
            .setItems(opts) { _, which ->
                if (which == 1) {
                    main.auth.setupPassword(onDone = { enabledDone() })
                } else if (!secure) {
                    AlertDialog.Builder(ctx)
                        .setTitle(R.string.lock_device_none_title)
                        .setMessage(R.string.lock_device_none_msg)
                        .setPositiveButton(R.string.ok, null)
                        .show()
                } else {
                    AlertDialog.Builder(ctx)
                        .setTitle(R.string.lock_choose_device_title)
                        .setMessage(R.string.lock_device_warning)
                        .setPositiveButton(R.string.lock_device_use) { _, _ ->
                            main.auth.device { ok ->
                                if (ok) {
                                    AppLock.enableDevice(ctx)
                                    enabledDone()
                                }
                            }
                        }
                        .setNegativeButton(R.string.cancel, null)
                        .show()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun enabledDone() {
        if (!isAdded) return
        main.applySecureFlag()
        renderSecurity()
        toast(R.string.lock_enabled_toast)
    }

    private fun toast(res: Int) = Toast.makeText(requireContext(), res, Toast.LENGTH_SHORT).show()

    private fun renderAccount(row: View) {
        val summary = row.findViewById<TextView>(R.id.summary)
        summary.text = if (prefs.driveConnected) {
            getString(R.string.drive_connected_as, prefs.driveEmail ?: "?")
        } else {
            getString(R.string.drive_not_connected)
        }
    }

    private fun disconnect(row: View) {
        val app = requireContext().applicationContext
        row.isEnabled = false
        Bg.run({ Roots.driveSignedOut(app) }) {
            if (view == null) return@run
            row.isEnabled = true
            renderAccount(row)
            parentFragmentManager.setFragmentResult(MainActivity.ROOTS_CHANGED, Bundle.EMPTY)
        }
    }

    private fun refreshSize(tm: ThumbnailManager, target: TextView) {
        target.setText(R.string.calculating)
        val app = requireContext().applicationContext
        Bg.run({ tm.disk.size() }) { res ->
            if (view == null) return@run
            target.text = Formatter.formatShortFileSize(app, res.getOrDefault(0L))
        }
    }

    override fun onResume() {
        super.onResume()
        requireActivity().setTitle(R.string.settings)
    }

    private fun switch(root: View, id: Int, value: Boolean, onChange: (Boolean) -> Unit) {
        val sw = root.findViewById<CompoundButton>(id)
        sw.isChecked = value
        sw.setOnCheckedChangeListener { _, on -> onChange(on) }
    }

    /**
     * "Pasta inicial": lista de pastas (padrão), Recentes ou uma pasta raiz. Subpastas se definem
     * pelo ⋮ da própria pasta ("Definir como pasta inicial"); a atual aparece como opção extra.
     */
    private fun bindStartFolder(view: View) {
        val row = view.findViewById<View>(R.id.row_start_folder)
        val summary = row.findViewById<TextView>(R.id.summary)
        row.findViewById<TextView>(R.id.title).setText(R.string.start_folder)
        val app = requireContext().applicationContext

        class Opt(val label: String, val root: Long, val ids: List<String> = emptyList(), val names: List<String> = emptyList())

        fun options(roots: List<app.index.files.data.Root>): List<Opt> {
            val out = ArrayList<Opt>()
            out.add(Opt(getString(R.string.start_folder_none), 0L))
            if (android.os.Build.VERSION.SDK_INT >= 30 && prefs.recentAdded) out.add(Opt(getString(R.string.recent_title), Roots.RECENT_ID))
            if (prefs.driveRecentAdded) out.add(Opt(getString(R.string.drive_recent_title), Roots.DRIVE_RECENT_ID))
            for (r in roots) out.add(Opt(r.alias, r.id))
            // Subpasta escolhida pelo ⋮ da pasta: mostrada com o caminho.
            val cur = prefs.startRoot
            val curIds = prefs.startIds
            val curNames = prefs.startNames
            if (curIds.size > 1 && out.any { it.root == cur }) {
                out.add(Opt(curNames.joinToString(" › "), cur, curIds, curNames))
            }
            return out
        }

        fun index(opts: List<Opt>): Int {
            val cur = prefs.startRoot
            val sub = prefs.startIds.size > 1
            return opts.indexOfFirst { it.root == cur && (it.ids.size > 1) == sub }.coerceAtLeast(0)
        }

        Bg.run({ Roots.all(app) }) { res ->
            if (!isAdded) return@run
            val opts = options(res.getOrDefault(emptyList()))
            summary.text = opts[index(opts)].label
            row.setOnClickListener {
                val labels = opts.map { it.label }.toTypedArray()
                AlertDialog.Builder(requireContext())
                    .setTitle(R.string.start_folder)
                    .setSingleChoiceItems(labels, index(opts)) { d, which ->
                        val o = opts[which]
                        prefs.setStart(o.root, o.ids, o.names)
                        summary.text = o.label
                        d.dismiss()
                    }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
            }
        }
    }

    private fun choiceRow(
        root: View,
        rowId: Int,
        title: Int,
        options: Array<String>,
        current: () -> Int,
        onPick: (Int) -> Unit,
    ) {
        val row = root.findViewById<View>(rowId)
        val summary = row.findViewById<TextView>(R.id.summary)
        row.findViewById<TextView>(R.id.title).setText(title)
        summary.text = options[current().coerceIn(0, options.size - 1)]
        row.setOnClickListener {
            AlertDialog.Builder(requireContext())
                .setTitle(title)
                .setSingleChoiceItems(options, current()) { d, which ->
                    onPick(which)
                    summary.text = options[which]
                    d.dismiss()
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
    }
}
