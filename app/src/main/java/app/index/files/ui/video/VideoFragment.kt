package app.index.files.ui.video

import android.annotation.SuppressLint
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.SurfaceView
import android.view.View
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.text.CueGroup
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.SubtitleView
import app.index.files.R
import app.index.files.data.FileItem
import app.index.files.data.FileSource
import app.index.files.data.Prefs
import app.index.files.ui.FileOpener
import app.index.files.ui.MainActivity
import java.io.File
import java.util.Locale
import kotlin.math.abs

/**
 * Player de vídeo embutido (Media3/ExoPlayer). Toque mostra/esconde os controles; dois toques à
 * direita/esquerda avançam/voltam (tempos nas Configurações; toques seguidos somam); segurar
 * acelera para 2x enquanto o dedo estiver na tela; a linha do tempo tem ajuste fino ([FineSeekBar]).
 * Trilhas de áudio e de legenda (embutidas e arquivos .srt/.vtt/.ass/.ssa/.ttml com o mesmo nome).
 */
class VideoFragment : Fragment(R.layout.fragment_video), MainActivity.KeyTarget {

    private class Session(val item: FileItem, val source: FileSource, val uri: Uri, val subs: List<MediaItem.SubtitleConfiguration>)

    private var session: Session? = null
    private var player: ExoPlayer? = null

    private lateinit var root: View
    private lateinit var frame: AspectRatioFrameLayout
    private lateinit var subtitles: SubtitleView
    private lateinit var controls: View
    private lateinit var top: View
    private lateinit var bottom: View
    private lateinit var playBtn: ImageButton
    private lateinit var audioBtn: View
    private lateinit var subsBtn: View
    private lateinit var seek: FineSeekBar
    private lateinit var posText: TextView
    private lateinit var durText: TextView
    private lateinit var hint: TextView
    private lateinit var badge: TextView
    private lateinit var loading: ProgressBar
    private lateinit var prefs: Prefs

    private var controlsShown = true
    private var fast = false
    private var wasPlaying = false
    private var lastSeekAt = 0L

    /** Toques seguidos depois de um toque duplo: continuam avançando/voltando no mesmo lado. */
    private var seekTapUntil = 0L
    private var seekTapSum = 0
    private var seekTapDir = 0

    private var savedLightStatus = false
    private var savedLightNav = false

    private val tick = object : Runnable {
        override fun run() {
            updateProgress()
            root.postDelayed(this, 250)
        }
    }
    private val autoHide = Runnable { if (player?.isPlaying == true && !seek.scrubbing) setControls(false) }
    private val hideBadge = Runnable { if (!fast) badge.visibility = View.GONE }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val s = pending
        pending = null
        if (s == null) {
            // Recriado pelo sistema sem a sessão em memória: fecha.
            view.post { close() }
            return
        }
        session = s
        prefs = Prefs.get(requireContext())
        root = view
        frame = view.findViewById(R.id.video_frame)
        subtitles = view.findViewById(R.id.video_subtitles)
        controls = view.findViewById(R.id.video_controls)
        top = view.findViewById(R.id.video_top)
        bottom = view.findViewById(R.id.video_bottom)
        playBtn = view.findViewById(R.id.video_play)
        audioBtn = view.findViewById(R.id.video_audio)
        subsBtn = view.findViewById(R.id.video_subs)
        seek = view.findViewById(R.id.video_seek)
        posText = view.findViewById(R.id.video_pos)
        durText = view.findViewById(R.id.video_dur)
        hint = view.findViewById(R.id.video_scrub_hint)
        badge = view.findViewById(R.id.video_badge)
        loading = view.findViewById(R.id.video_loading)
        view.findViewById<TextView>(R.id.video_name).text = s.item.name
        audioBtn.visibility = View.GONE
        subsBtn.visibility = View.GONE

        view.findViewById<View>(R.id.video_back).setOnClickListener { close() }
        playBtn.setOnClickListener { togglePlay() }
        audioBtn.setOnClickListener { chooseTrack(C.TRACK_TYPE_AUDIO) }
        subsBtn.setOnClickListener { chooseTrack(C.TRACK_TYPE_TEXT) }
        seek.listener = scrubListener
        setupGestures()

        ViewCompat.setOnApplyWindowInsetsListener(view) { _, wi ->
            val b = wi.getInsetsIgnoringVisibility(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            top.setPadding(b.left, b.top, b.right, top.paddingBottom)
            val d = resources.displayMetrics.density
            bottom.setPadding(b.left + (16 * d).toInt(), bottom.paddingTop, b.right + (16 * d).toInt(), b.bottom + (8 * d).toInt())
            wi
        }
        ViewCompat.requestApplyInsets(view)

        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = close()
        })

        val win = requireActivity().window
        val ctl = WindowCompat.getInsetsController(win, win.decorView)
        savedLightStatus = ctl.isAppearanceLightStatusBars
        savedLightNav = ctl.isAppearanceLightNavigationBars
        ctl.isAppearanceLightStatusBars = false
        ctl.isAppearanceLightNavigationBars = false
        ctl.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        view.requestFocus()

        startPlayer(s, view.findViewById(R.id.video_surface))
        setControls(true)
        root.post(tick)
    }

    private fun startPlayer(s: Session, surface: SurfaceView) {
        val ctx = requireContext()
        // Sem decodificador por hardware para o formato: tenta o próximo (inclusive o de software).
        val renderers = DefaultRenderersFactory(ctx).setEnableDecoderFallback(true)
        val p = ExoPlayer.Builder(ctx, renderers).build()
        p.setAudioAttributes(
            AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(),
            true,
        )
        p.setHandleAudioBecomingNoisy(true) // fone desconectado: pausa
        p.setVideoSurfaceView(surface)
        p.addListener(playerListener)
        p.setMediaItem(MediaItem.Builder().setUri(s.uri).setSubtitleConfigurations(s.subs).build())
        p.prepare()
        p.playWhenReady = true
        player = p
    }

    private val playerListener = object : Player.Listener {
        override fun onVideoSizeChanged(vs: VideoSize) {
            frame.setAspectRatio(if (vs.height == 0 || vs.width == 0) 0f else vs.width * vs.pixelWidthHeightRatio / vs.height)
        }

        override fun onCues(cueGroup: CueGroup) = subtitles.setCues(cueGroup.cues)

        override fun onPlaybackStateChanged(state: Int) {
            loading.visibility = if (state == Player.STATE_BUFFERING) View.VISIBLE else View.GONE
            if (state == Player.STATE_ENDED) setControls(true)
            renderPlay()
            updateProgress()
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            root.keepScreenOn = isPlaying
            renderPlay()
            if (isPlaying) scheduleHide() else root.removeCallbacks(autoHide)
        }

        override fun onTracksChanged(tracks: Tracks) {
            audioBtn.visibility = if (count(tracks, C.TRACK_TYPE_AUDIO) > 1) View.VISIBLE else View.GONE
            subsBtn.visibility = if (count(tracks, C.TRACK_TYPE_TEXT) > 0) View.VISIBLE else View.GONE
        }

        override fun onPlayerError(error: PlaybackException) {
            val s = session ?: return
            if (view == null) return
            AlertDialog.Builder(requireContext())
                .setTitle(R.string.video_error_title)
                .setMessage(R.string.video_error_msg)
                .setPositiveButton(R.string.video_open_other) { _, _ ->
                    // A tela da pasta por baixo abre o outro app (este player fecha em seguida).
                    val host = parentFragmentManager.findFragmentById(R.id.content) ?: this@VideoFragment
                    FileOpener.open(host, s.source, s.item, chooser = true)
                    close()
                }
                .setNegativeButton(R.string.close) { _, _ -> close() }
                .setOnCancelListener { close() }
                .show()
        }
    }

    private fun count(tracks: Tracks, type: Int) =
        tracks.groups.filter { it.type == type }.sumOf { g -> (0 until g.length).count { g.isTrackSupported(it) } }

    // ---- Controles ----

    private fun togglePlay() {
        val p = player ?: return
        if (p.playbackState == Player.STATE_ENDED) {
            p.seekTo(0)
            p.play()
        } else if (p.isPlaying) {
            p.pause()
            setControls(true)
        } else {
            p.play()
        }
    }

    private fun renderPlay() {
        val playing = player?.isPlaying == true || (player?.playWhenReady == true && player?.playbackState == Player.STATE_BUFFERING)
        playBtn.setImageResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play)
    }

    private fun setControls(show: Boolean) {
        controlsShown = show
        controls.visibility = if (show) View.VISIBLE else View.GONE
        val win = activity?.window ?: return
        val ctl = WindowCompat.getInsetsController(win, win.decorView)
        if (show) ctl.show(WindowInsetsCompat.Type.systemBars()) else ctl.hide(WindowInsetsCompat.Type.systemBars())
        if (show) scheduleHide()
    }

    private fun scheduleHide() {
        root.removeCallbacks(autoHide)
        if (controlsShown) root.postDelayed(autoHide, 3000)
    }

    private fun updateProgress() {
        val p = player ?: return
        val dur = p.duration.takeIf { it != C.TIME_UNSET } ?: 0L
        seek.duration = dur
        seek.buffered = p.bufferedPosition
        if (!seek.scrubbing) {
            seek.position = p.currentPosition
            posText.text = time(p.currentPosition)
        }
        durText.text = if (dur > 0) time(dur) else ""
    }

    private fun showBadge(text: String, persistent: Boolean = false) {
        badge.text = text
        badge.visibility = View.VISIBLE
        root.removeCallbacks(hideBadge)
        if (!persistent) root.postDelayed(hideBadge, 700)
    }

    // ---- Gestos ----

    @SuppressLint("ClickableViewAccessibility")
    private fun setupGestures() {
        val detector = GestureDetector(requireContext(), object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true

            override fun onSingleTapUp(e: MotionEvent): Boolean {
                // Logo depois de um toque duplo, cada toque no mesmo lado avança/volta mais.
                if (SystemClock.uptimeMillis() < seekTapUntil && sideOf(e) == seekTapDir) {
                    tapSeek(seekTapDir)
                    return true
                }
                return false
            }

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                if (SystemClock.uptimeMillis() < seekTapUntil) return true
                setControls(!controlsShown)
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                seekTapSum = 0
                tapSeek(sideOf(e))
                return true
            }

            override fun onLongPress(e: MotionEvent) {
                val p = player ?: return
                if (!p.isPlaying) return
                fast = true
                p.setPlaybackSpeed(2f)
                showBadge(getString(R.string.video_fast), persistent = true)
            }
        })
        root.setOnTouchListener { _, e ->
            detector.onTouchEvent(e)
            if ((e.actionMasked == MotionEvent.ACTION_UP || e.actionMasked == MotionEvent.ACTION_CANCEL) && fast) {
                fast = false
                player?.setPlaybackSpeed(1f)
                badge.visibility = View.GONE
            }
            true
        }
    }

    private fun sideOf(e: MotionEvent) = if (e.x < root.width / 2f) -1 else 1

    private fun tapSeek(dir: Int) {
        val p = player ?: return
        val step = if (dir > 0) prefs.seekForwardSec else prefs.seekBackSec
        if (seekTapDir != dir) seekTapSum = 0
        seekTapDir = dir
        seekTapSum += step
        seekTapUntil = SystemClock.uptimeMillis() + 700
        p.setSeekParameters(SeekParameters.CLOSEST_SYNC)
        val dur = p.duration.takeIf { it != C.TIME_UNSET } ?: Long.MAX_VALUE
        p.seekTo((p.currentPosition + dir * step * 1000L).coerceIn(0L, dur))
        showBadge(getString(if (dir > 0) R.string.video_forward else R.string.video_back, seekTapSum))
        updateProgress()
    }

    private val scrubListener = object : FineSeekBar.Listener {
        override fun onScrubStart() {
            val p = player ?: return
            root.removeCallbacks(autoHide)
            wasPlaying = p.isPlaying
            p.pause()
        }

        override fun onScrub(positionMs: Long, level: Int) {
            posText.text = time(positionMs)
            hint.text = if (level == 0) time(positionMs) else getString(R.string.video_fine, timeTenths(positionMs), 1 shl level)
            hint.visibility = View.VISIBLE
            val now = SystemClock.uptimeMillis()
            if (now - lastSeekAt < 80) return
            lastSeekAt = now
            val p = player ?: return
            // Ajuste fino: quadro exato; arrasto normal: quadro-chave mais perto (mais rápido).
            p.setSeekParameters(if (level > 0) SeekParameters.EXACT else SeekParameters.CLOSEST_SYNC)
            p.seekTo(positionMs)
        }

        override fun onScrubEnd(positionMs: Long) {
            val p = player ?: return
            hint.visibility = View.INVISIBLE
            p.setSeekParameters(SeekParameters.EXACT)
            p.seekTo(positionMs)
            if (wasPlaying) p.play()
            scheduleHide()
        }
    }

    // ---- Trilhas ----

    private class Choice(val group: Tracks.Group, val index: Int)

    private fun chooseTrack(type: Int) {
        val p = player ?: return
        val choices = ArrayList<Choice>()
        val labels = ArrayList<String>()
        var checked = -1
        if (type == C.TRACK_TYPE_TEXT) labels.add(getString(R.string.video_subs_off))
        var n = 0
        for (g in p.currentTracks.groups) {
            if (g.type != type) continue
            for (i in 0 until g.length) {
                if (!g.isTrackSupported(i)) continue
                n++
                if (g.isTrackSelected(i)) checked = labels.size
                choices.add(Choice(g, i))
                labels.add(describe(g.getTrackFormat(i), type, n))
            }
        }
        if (type == C.TRACK_TYPE_TEXT && checked < 0) checked = 0
        root.removeCallbacks(autoHide)
        AlertDialog.Builder(requireContext())
            .setTitle(if (type == C.TRACK_TYPE_AUDIO) R.string.video_audio_track else R.string.video_subtitle_track)
            .setSingleChoiceItems(labels.toTypedArray(), checked) { d, which ->
                val b = p.trackSelectionParameters.buildUpon()
                val c = if (type == C.TRACK_TYPE_TEXT) choices.getOrNull(which - 1) else choices.getOrNull(which)
                if (c == null) {
                    b.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                } else {
                    b.setTrackTypeDisabled(type, false)
                    b.setOverrideForType(TrackSelectionOverride(c.group.mediaTrackGroup, c.index))
                }
                p.trackSelectionParameters = b.build()
                d.dismiss()
            }
            .setOnDismissListener { scheduleHide() }
            .show()
    }

    /** "Português · 5.1 · AC-3", "Inglês · SRT", "Faixa 2". */
    private fun describe(f: Format, type: Int, n: Int): String {
        val parts = ArrayList<String>()
        f.language?.takeIf { it.isNotBlank() && it != C.LANGUAGE_UNDETERMINED }?.let { lang ->
            val name = Locale.forLanguageTag(lang).getDisplayName(Locale.getDefault())
            parts.add(name.replaceFirstChar { it.titlecase(Locale.getDefault()) }.ifBlank { lang })
        }
        f.label?.takeIf { it.isNotBlank() && parts.none { p -> p.equals(it, true) } }?.let(parts::add)
        if (type == C.TRACK_TYPE_AUDIO) {
            when (f.channelCount) {
                1 -> parts.add("mono")
                2 -> parts.add(getString(R.string.video_stereo))
                6 -> parts.add("5.1")
                8 -> parts.add("7.1")
            }
        }
        codecName(f)?.let(parts::add)
        if (parts.isEmpty()) parts.add(getString(R.string.video_track_n, n))
        return parts.joinToString(" · ")
    }

    private fun codecName(f: Format): String? = when (f.sampleMimeType ?: f.codecs) {
        MimeTypes.AUDIO_AAC -> "AAC"
        MimeTypes.AUDIO_AC3 -> "AC-3"
        MimeTypes.AUDIO_E_AC3, MimeTypes.AUDIO_E_AC3_JOC -> "E-AC-3"
        MimeTypes.AUDIO_OPUS -> "Opus"
        MimeTypes.AUDIO_VORBIS -> "Vorbis"
        MimeTypes.AUDIO_MPEG -> "MP3"
        MimeTypes.AUDIO_FLAC -> "FLAC"
        MimeTypes.AUDIO_DTS, MimeTypes.AUDIO_DTS_HD -> "DTS"
        MimeTypes.AUDIO_TRUEHD -> "TrueHD"
        MimeTypes.APPLICATION_SUBRIP -> "SRT"
        MimeTypes.TEXT_SSA -> "ASS"
        MimeTypes.TEXT_VTT -> "VTT"
        MimeTypes.APPLICATION_TTML -> "TTML"
        MimeTypes.APPLICATION_PGS -> "PGS"
        MimeTypes.APPLICATION_VOBSUB -> "VobSub"
        MimeTypes.APPLICATION_CEA608, MimeTypes.APPLICATION_CEA708 -> "CC"
        MimeTypes.APPLICATION_TX3G -> "TX3G"
        else -> null
    }

    // ---- Teclado (tablet) ----

    override fun onKey(e: KeyEvent): Boolean {
        when (e.keyCode) {
            KeyEvent.KEYCODE_SPACE, KeyEvent.KEYCODE_K, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> togglePlay()
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_J -> tapSeek(-1)
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_L -> tapSeek(1)
            KeyEvent.KEYCODE_ESCAPE -> close()
            else -> return false
        }
        return true
    }

    // ---- Ciclo de vida ----

    override fun onStop() {
        super.onStop()
        player?.pause() // saiu da tela (outro app, bloqueio): não toca escondido
    }

    override fun onDestroyView() {
        super.onDestroyView()
        cleanup()
    }

    fun close() {
        val fm = parentFragmentManager
        if (!fm.isStateSaved && isAdded) fm.beginTransaction().remove(this).commit()
        cleanup()
    }

    private var cleaned = false

    private fun cleanup() {
        if (cleaned || session == null) return
        cleaned = true
        session = null
        if (::root.isInitialized) {
            root.removeCallbacks(tick)
            root.removeCallbacks(autoHide)
            root.removeCallbacks(hideBadge)
            root.keepScreenOn = false
        }
        player?.release()
        player = null
        val win = activity?.window ?: return
        val ctl = WindowCompat.getInsetsController(win, win.decorView)
        ctl.show(WindowInsetsCompat.Type.systemBars())
        ctl.isAppearanceLightStatusBars = savedLightStatus
        ctl.isAppearanceLightNavigationBars = savedLightNav
    }

    companion object {
        private const val TAG = "video"
        private var pending: Session? = null

        private val SUB_MIME = mapOf(
            "srt" to MimeTypes.APPLICATION_SUBRIP,
            "vtt" to MimeTypes.TEXT_VTT,
            "ass" to MimeTypes.TEXT_SSA,
            "ssa" to MimeTypes.TEXT_SSA,
            "ttml" to MimeTypes.APPLICATION_TTML,
        )

        /**
         * Abre [item] no player. [siblings]: os outros itens da pasta (para achar legendas com o
         * mesmo nome). false = não dá para tocar aqui (ex.: Drive sem tamanho): quem chama abre fora.
         */
        fun open(act: FragmentActivity, item: FileItem, source: FileSource, siblings: List<FileItem>): Boolean {
            val fm = act.supportFragmentManager
            if (fm.isStateSaved) return false
            if (fm.findFragmentByTag(TAG) != null) return true
            val uri = uriFor(item, source) ?: return false
            pending = Session(item, source, uri, subtitlesFor(item, source, siblings))
            fm.beginTransaction().replace(R.id.viewer_host, VideoFragment(), TAG).commit()
            return true
        }

        fun find(act: FragmentActivity): VideoFragment? =
            act.supportFragmentManager.findFragmentByTag(TAG) as? VideoFragment

        /** Arquivo pelo caminho direto; Drive pelo link de streaming do próprio app; SAF pela Uri do documento. */
        private fun uriFor(item: FileItem, source: FileSource): Uri? {
            if (item.id.startsWith("/")) File(item.id).takeIf { it.isFile }?.let { return Uri.fromFile(it) }
            if (source.cacheTag.endsWith("drive")) return if (item.size > 0) runCatching { source.shareUri(item) }.getOrNull() else null
            return runCatching { source.uriFor(item) }.getOrNull()
        }

        /** "Filme.srt", "Filme.pt-BR.srt", "Filme.en.forced.ass" ao lado de "Filme.mkv". */
        private fun subtitlesFor(item: FileItem, source: FileSource, siblings: List<FileItem>): List<MediaItem.SubtitleConfiguration> {
            val base = item.name.substringBeforeLast('.')
            return siblings.mapNotNull { s ->
                val mime = SUB_MIME[s.ext] ?: return@mapNotNull null
                if (s.isDir || s.size == 0L) return@mapNotNull null
                val stem = s.name.substringBeforeLast('.')
                if (stem != base && !stem.startsWith("$base.")) return@mapNotNull null
                val uri = uriFor(s, source) ?: return@mapNotNull null
                val tag = stem.removePrefix(base).trim('.', ' ', '_', '-')
                val lang = tag.substringBefore('.').takeIf { it.length in 2..8 && it.all { c -> c.isLetter() || c == '-' } }
                MediaItem.SubtitleConfiguration.Builder(uri)
                    .setMimeType(mime)
                    .setLanguage(lang)
                    .setLabel(tag.ifEmpty { null })
                    .build()
            }
        }

        /** Com décimos, para o ajuste fino: 2:03.4. */
        fun timeTenths(ms: Long) = time(ms) + "." + (abs(ms) / 100 % 10)

        /** 1:02:03 ou 2:03. */
        fun time(ms: Long): String {
            val t = abs(ms) / 1000
            val h = t / 3600
            val m = t / 60 % 60
            val s = t % 60
            return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
        }
    }
}
