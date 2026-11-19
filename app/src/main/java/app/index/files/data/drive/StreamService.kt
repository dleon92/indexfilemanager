package app.index.files.data.drive

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import app.index.files.R

/**
 * Mantém o app em primeiro plano enquanto outro app (VLC, player nativo…) lê um
 * arquivo do Drive por streaming. Sem isso o Android 17 corta a rede do processo
 * ~20 s depois que o player assume a tela (processo vai para "cache").
 *
 * Iniciado no toque do arquivo (o app ainda está visível, condição exigida pelo
 * sistema). Fica ativo enquanto algum player estiver com um stream aberto, então
 * pausas longas funcionam. Para quando o último stream é fechado (o VLC só fecha
 * quando o processo dele termina), pelo botão "Parar" ou, por segurança, após
 * [CAP_MS] sem nenhuma leitura.
 */
class StreamService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private val check = object : Runnable {
        override fun run() {
            val now = SystemClock.elapsedRealtime()
            val reason = when {
                openCount == 0 && now - lastChange > GRACE_MS -> "nenhum stream aberto"
                now - lastActivity > CAP_MS -> "sem leituras há ${CAP_MS / 3600_000} h"
                else -> null
            }
            if (reason != null) {
                Log.i(TAG, "encerrando serviço de streaming: $reason")
                stopSelf()
            } else {
                handler.postDelayed(this, CHECK_MS)
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        ServiceCompat.startForeground(
            this,
            NOTIF_ID,
            notification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
        )
        handler.removeCallbacks(check)
        handler.postDelayed(check, CHECK_MS)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(check)
        super.onDestroy()
    }

    private fun notification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, getString(R.string.stream_channel), NotificationManager.IMPORTANCE_LOW),
            )
        }
        val stop = PendingIntent.getService(
            this,
            0,
            Intent(this, StreamService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_stream)
            .setContentTitle(getString(R.string.stream_notif_title))
            .setContentText(getString(R.string.stream_notif_text))
            .setOngoing(true)
            .setSilent(true)
            .addAction(0, getString(R.string.stream_stop), stop)
            .build()
    }

    companion object {
        private const val TAG = "Drive"
        private const val CHANNEL = "stream"
        private const val NOTIF_ID = 1
        private const val ACTION_STOP = "stop"
        /** Tolerância sem stream aberto (o player ainda vai abrir, ou reabre ao trocar de faixa). */
        private const val GRACE_MS = 15_000L
        /** Teto de segurança para um player que nunca solta o arquivo. */
        private const val CAP_MS = 2 * 3600_000L
        private const val CHECK_MS = 5_000L

        @Volatile private var lastActivity = 0L
        @Volatile private var lastChange = 0L
        @Volatile private var openCount = 0

        /** Chamado a cada leitura do stream. */
        fun touch() {
            lastActivity = SystemClock.elapsedRealtime()
        }

        @Synchronized
        fun streamOpened() {
            openCount++
            lastChange = SystemClock.elapsedRealtime()
            touch()
        }

        @Synchronized
        fun streamClosed() {
            openCount = maxOf(0, openCount - 1)
            lastChange = SystemClock.elapsedRealtime()
        }

        /** Chamar com o app visível (no toque do arquivo). */
        fun start(ctx: Context) {
            touch()
            lastChange = SystemClock.elapsedRealtime()
            try {
                ctx.startForegroundService(Intent(ctx, StreamService::class.java))
            } catch (e: Exception) {
                // Ex.: ForegroundServiceStartNotAllowedException; o streaming funciona, só sem a garantia de rede.
                Log.w(TAG, "não foi possível iniciar o serviço de streaming", e)
            }
        }
    }
}
