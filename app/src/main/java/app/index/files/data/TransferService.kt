package app.index.files.data

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import app.index.files.R

/**
 * Mantém o app em primeiro plano (tipo dataSync) enquanto uma operação com arquivos roda,
 * para cópias, movimentações e envios continuarem com o app em segundo plano ou a tela
 * apagada (sem isso o Android corta a rede do processo em segundo plano).
 *
 * Ativo só durante a operação: [start] no começo (o app está visível, como o sistema exige)
 * e [stop] no fim. A notificação mostra só o tipo de operação e o andamento, nunca nomes
 * de arquivos (pode aparecer na tela de bloqueio).
 */
class TransferService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            onCancel?.invoke()
            return START_NOT_STICKY
        }
        // startForeground sempre (exigência do startForegroundService), mesmo que a operação
        // já tenha terminado antes de o serviço subir: aí sai em seguida.
        ServiceCompat.startForeground(this, NOTIF_ID, build(this), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        synchronized(Companion) {
            foregrounded = true
            if (!active) {
                foregrounded = false
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    /** Android 15+: limite diário do tipo dataSync atingido. Cancela a operação e sai. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        Log.w(TAG, "tempo de dataSync esgotado: cancelando a operação")
        onCancel?.invoke()
        synchronized(Companion) { foregrounded = false }
        stopSelf()
    }

    companion object {
        private const val TAG = "Index"
        private const val CHANNEL = "transfer"
        private const val NOTIF_ID = 2
        private const val NOTIF_RESULT_ID = 3
        private const val ACTION_CANCEL = "cancel"

        @Volatile private var active = false
        /** O serviço já chamou startForeground (pode ser parado de fora). */
        @Volatile private var foregrounded = false
        @Volatile private var title = ""
        @Volatile private var text = ""
        @Volatile private var percent = -1
        @Volatile private var lastNotify = 0L

        /** Chamado pelo botão "Cancelar" da notificação. */
        @Volatile var onCancel: (() -> Unit)? = null

        /** Começo da operação (com o app visível). */
        fun start(ctx: Context, title: String, onCancel: () -> Unit) {
            this.title = title
            text = ""
            percent = -1
            synchronized(this) {
                this.onCancel = onCancel
                active = true
            }
            val app = ctx.applicationContext
            app.getSystemService(NotificationManager::class.java).cancel(NOTIF_RESULT_ID)
            try {
                app.startForegroundService(Intent(app, TransferService::class.java))
            } catch (e: Exception) {
                // Sem o serviço a operação segue, só sem garantia em segundo plano.
                Log.w(TAG, "não foi possível iniciar o serviço de transferência", e)
            }
        }

        /** Andamento (no máximo 1 atualização por segundo na notificação). */
        fun progress(ctx: Context, text: String, percent: Int) {
            this.text = text
            this.percent = percent
            val now = SystemClock.elapsedRealtime()
            if (!active || now - lastNotify < 1000) return
            lastNotify = now
            val app = ctx.applicationContext
            runCatching { app.getSystemService(NotificationManager::class.java).notify(NOTIF_ID, build(app)) }
        }

        /** Fim da operação. [result] = texto do resultado (aparece se o app não estiver na tela). */
        fun stop(ctx: Context, result: String?) {
            val app = ctx.applicationContext
            synchronized(this) {
                active = false
                onCancel = null
                // Antes do startForeground o serviço se encerra sozinho (pará-lo daqui faria o sistema derrubar o app).
                if (foregrounded) {
                    foregrounded = false
                    app.stopService(Intent(app, TransferService::class.java))
                }
            }
            if (result != null) {
                val n = NotificationCompat.Builder(app, channel(app))
                    .setSmallIcon(R.drawable.ic_stat_stream)
                    .setContentTitle(result)
                    .setAutoCancel(true)
                    .setContentIntent(launch(app))
                    .build()
                runCatching { app.getSystemService(NotificationManager::class.java).notify(NOTIF_RESULT_ID, n) }
            }
        }

        private fun channel(ctx: Context): String {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL, ctx.getString(R.string.transfer_channel), NotificationManager.IMPORTANCE_LOW),
                )
            }
            return CHANNEL
        }

        private fun launch(ctx: Context): PendingIntent? =
            ctx.packageManager.getLaunchIntentForPackage(ctx.packageName)?.let {
                PendingIntent.getActivity(ctx, 0, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            }

        private fun build(ctx: Context): Notification {
            val cancel = PendingIntent.getService(
                ctx, 1,
                Intent(ctx, TransferService::class.java).setAction(ACTION_CANCEL),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            return NotificationCompat.Builder(ctx, channel(ctx))
                .setSmallIcon(R.drawable.ic_stat_stream)
                .setContentTitle(title)
                .setContentText(text)
                .setProgress(100, percent.coerceAtLeast(0), percent < 0)
                .setOngoing(true)
                .setSilent(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(launch(ctx))
                .addAction(0, ctx.getString(R.string.cancel), cancel)
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
                .build()
        }
    }
}
