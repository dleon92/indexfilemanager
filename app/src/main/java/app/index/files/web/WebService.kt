package app.index.files.web

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import app.index.files.Bg
import app.index.files.R
import app.index.files.data.Prefs
import app.index.files.security.AppLock
import app.index.files.security.PasswordRec
import org.json.JSONObject
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * "Acesso pelo computador": mantém o servidor web ligado em primeiro plano (tipo dataSync),
 * com o Wi-Fi e a CPU acordados enquanto estiver ligado. Desliga sozinho depois de
 * [IDLE_MS] sem nenhum pedido, pelo botão da notificação ou pela tela do app.
 */
class WebService : Service() {

    private var server: WebServer? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private var mdns: MdnsResponder? = null
    private val idleCheck = object : Runnable {
        override fun run() {
            if (SystemClock.elapsedRealtime() - lastActivity > IDLE_MS) {
                Log.i(TAG, "desligado por inatividade")
                stopSelf()
                return
            }
            Bg.main.postDelayed(this, 60_000)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        ServiceCompat.startForeground(this, NOTIF_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        if (server == null) {
            val s = WebServer(applicationContext, ::checkPassword) { lastActivity = SystemClock.elapsedRealtime() }
            try {
                port = s.start(Prefs.get(this).webPort)
                server = s
            } catch (e: Exception) {
                Log.w(TAG, "não foi possível abrir a porta", e)
                error = true
                stopSelf()
                return START_NOT_STICKY
            }
            lastActivity = SystemClock.elapsedRealtime()
            acquireLocks()
            mdns = MdnsResponder().also { m -> Bg.run({ m.start() }) {} }
            running = true
            error = false
            notifyListeners()
            getSystemService(NotificationManager::class.java).notify(NOTIF_ID, notification())
            Bg.main.postDelayed(idleCheck, 60_000)
        }
        return START_NOT_STICKY
    }

    /** Android 15+: limite diário do tipo dataSync. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        Log.w(TAG, "tempo de dataSync esgotado")
        stopSelf()
    }

    override fun onDestroy() {
        Bg.main.removeCallbacks(idleCheck)
        server?.stop()
        server = null
        mdns?.let { m -> Bg.run({ m.stop() }) {} }
        mdns = null
        runCatching { multicastLock?.takeIf { it.isHeld }?.release() }
        runCatching { wifiLock?.takeIf { it.isHeld }?.release() }
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        running = false
        notifyListeners()
        super.onDestroy()
    }

    @Suppress("DEPRECATION")
    private fun acquireLocks() {
        val wm = applicationContext.getSystemService(WifiManager::class.java)
        val mode = if (Build.VERSION.SDK_INT >= 29) WifiManager.WIFI_MODE_FULL_LOW_LATENCY else WifiManager.WIFI_MODE_FULL_HIGH_PERF
        wifiLock = runCatching { wm.createWifiLock(mode, "Index:web").apply { setReferenceCounted(false); acquire() } }.getOrNull()
        // Sem isso o Wi-Fi descarta os pacotes multicast e o nome index.local não responde.
        multicastLock = runCatching { wm.createMulticastLock("Index:mdns").apply { setReferenceCounted(false); acquire() } }.getOrNull()
        val pm = getSystemService(PowerManager::class.java)
        wakeLock = runCatching { pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Index:web").apply { setReferenceCounted(false); acquire(6 * 60 * 60 * 1000L) } }.getOrNull()
    }

    /** 0 = certa; -1 = errada; >0 = esperar esse tanto (ms) depois de muitos erros. */
    private fun checkPassword(pw: CharArray): Long {
        val rec = password(this)
        if (rec == null) {
            pw.fill('\u0000')
            return -1
        }
        val wait = rec.waitRemainingMs()
        if (wait > 0) {
            pw.fill('\u0000')
            return wait
        }
        val ok = rec.verify(pw)
        if (ok) return 0
        val w = rec.waitRemainingMs()
        return if (w > 0) w else -1
    }

    private fun notification() = NotificationCompat.Builder(this, channel(this))
        .setSmallIcon(R.drawable.ic_stat_stream)
        .setContentTitle(getString(R.string.web_notif_title))
        .setContentText(addresses().firstOrNull()?.let { "http://$it:$port" } ?: getString(R.string.web_no_network))
        .setOngoing(true)
        .setContentIntent(
            packageManager.getLaunchIntentForPackage(packageName)?.let {
                PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            },
        )
        .addAction(
            0, getString(R.string.web_turn_off),
            PendingIntent.getService(this, 1, Intent(this, WebService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE),
        )
        .build()

    companion object {
        private const val TAG = "Index.Web"
        private const val CHANNEL = "web"
        private const val NOTIF_ID = 4
        private const val ACTION_STOP = "stop"
        const val IDLE_MS = 30 * 60 * 1000L

        @Volatile var running = false
            private set
        @Volatile var error = false
            private set
        @Volatile var port = 0
            private set
        @Volatile private var lastActivity = 0L

        private val listeners = LinkedHashSet<() -> Unit>()
        fun addListener(l: () -> Unit) { listeners.add(l) }
        fun removeListener(l: () -> Unit) { listeners.remove(l) }
        private fun notifyListeners() = Bg.main.post { listeners.toList().forEach { it() } }

        fun start(ctx: Context) {
            error = false
            val app = ctx.applicationContext
            app.startForegroundService(Intent(app, WebService::class.java))
        }

        fun stop(ctx: Context) {
            val app = ctx.applicationContext
            app.stopService(Intent(app, WebService::class.java))
        }

        /** Endereços IPv4 da rede local (Wi-Fi, roteador do celular), Wi-Fi primeiro. */
        private val LAN_PREFIXES = listOf("wlan", "swlan", "ap", "softap", "eth", "rndis", "usb")

        fun isLanInterface(name: String) = LAN_PREFIXES.any { name.startsWith(it) }

        /** Endereço fixo (mDNS), que não muda quando o roteador troca o IP. */
        val fixedHost get() = MdnsResponder.HOST

        fun addresses(): List<String> = runCatching {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback && !it.isVirtual }
                // Só redes locais de verdade: Wi-Fi, roteador do celular, cabo/USB. Fica de fora
                // dados móveis (rmnet/ccmni: 10.x da operadora), VPN (tun), Wi-Fi Direct (p2p) etc.
                .filter { ni -> isLanInterface(ni.name) }
                .sortedBy { if (it.name.startsWith("wlan")) 0 else if (it.name.startsWith("ap") || it.name.startsWith("swlan")) 1 else 2 }
                .flatMap { ni -> ni.inetAddresses.toList().filterIsInstance<Inet4Address>().filter { it.isSiteLocalAddress } }
                .map { it.hostAddress.orEmpty() }
                .filter { it.isNotEmpty() }
                .distinct()
        }.getOrDefault(emptyList())

        fun hasPassword(ctx: Context) = Prefs.get(ctx).webPassword != null

        private var cached: PasswordRec? = null

        fun password(ctx: Context): PasswordRec? = synchronized(this) {
            cached ?: Prefs.get(ctx).webPassword?.let { json ->
                runCatching { PasswordRec.fromJson(JSONObject(json)) }.getOrNull()?.also { rec ->
                    val p = Prefs.get(ctx)
                    rec.onChange = { p.webPassword = rec.toJson().toString() }
                    cached = rec
                }
            }
        }

        /** Lento (PBKDF2): fora da main. Zera [pw]. */
        fun setPassword(ctx: Context, pw: CharArray) {
            val rec = PasswordRec.create(pw, AppLock.KIND_PASSWORD)
            synchronized(this) {
                Prefs.get(ctx).webPassword = rec.toJson().toString()
                cached = null
            }
        }

        private fun channel(ctx: Context): String {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL) == null) {
                nm.createNotificationChannel(NotificationChannel(CHANNEL, ctx.getString(R.string.web_channel), NotificationManager.IMPORTANCE_LOW))
            }
            return CHANNEL
        }
    }
}
