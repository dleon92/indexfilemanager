package app.index.files.security

import android.app.KeyguardManager
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.hardware.biometrics.BiometricManager
import android.os.Build
import android.os.SystemClock
import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * Bloqueio do app (seção 9 + itens B e D do contexto).
 *
 * Dois métodos: o bloqueio do próprio celular (PIN/padrão/senha do aparelho e biometria)
 * ou uma senha própria do Index (PIN de 4–8 dígitos ou senha alfanumérica), com biometria
 * opcional. A senha nunca é guardada: só o hash PBKDF2WithHmacSHA256 com salt aleatório,
 * comparado em tempo constante. Tudo fica em SharedPreferences "lock", separado das
 * configurações.
 *
 * O estado "desbloqueado" vive só na memória do processo: um processo novo sempre começa
 * bloqueado.
 */
object AppLock {
    const val METHOD_DEVICE = 0
    const val METHOD_OWN = 1
    const val KIND_PIN = 0
    const val KIND_PASSWORD = 1

    /** Opções de "bloquear de novo após": imediatamente, 30 s, 1 min, 5 min. */
    val RELOCK_MS = longArrayOf(0L, 30_000L, 60_000L, 300_000L)

    const val PIN_MIN = 4
    const val PIN_MAX = 8
    const val PASSWORD_MIN = 4
    const val PASSWORD_MAX = 64

    private const val ITERATIONS = 120_000
    private const val KEY_BITS = 256
    private const val FREE_FAILURES = 5
    /** Espera fixa de 5 min a cada tentativa errada a partir da 5ª. */
    private const val WAIT_MS = 300_000L

    @Volatile private var unlocked = false
    @Volatile private var hiddenAt = 0L
    /** Quando o app abriu por conta própria uma tela do sistema (seletor de pastas, login…). */
    @Volatile private var ownFlowAt = -OWN_FLOW_MS
    private const val OWN_FLOW_MS = 3_000L

    private fun sp(ctx: Context): SharedPreferences =
        ctx.applicationContext.getSharedPreferences("lock", Context.MODE_PRIVATE)

    // ---- Configuração ----

    fun enabled(ctx: Context) = sp(ctx).getBoolean("enabled", false)
    fun method(ctx: Context) = sp(ctx).getInt("method", METHOD_DEVICE)
    fun kind(ctx: Context) = sp(ctx).getInt("kind", KIND_PIN)

    fun biometric(ctx: Context) = sp(ctx).getBoolean("biometric", false)
    fun setBiometric(ctx: Context, on: Boolean) = sp(ctx).edit().putBoolean("biometric", on).apply()

    fun relockIndex(ctx: Context) = sp(ctx).getInt("relock", 0).coerceIn(0, RELOCK_MS.size - 1)
    fun setRelockIndex(ctx: Context, i: Int) = sp(ctx).edit().putInt("relock", i).apply()

    /** FLAG_SECURE: oculta o conteúdo em apps recentes e bloqueia capturas. */
    fun secureScreen(ctx: Context) = enabled(ctx) && sp(ctx).getBoolean("secure", true)
    fun secureSetting(ctx: Context) = sp(ctx).getBoolean("secure", true)
    fun setSecure(ctx: Context, on: Boolean) = sp(ctx).edit().putBoolean("secure", on).apply()

    /** Ativa com o bloqueio do celular (a pessoa acabou de se autenticar nele). */
    fun enableDevice(ctx: Context) {
        sp(ctx).edit()
            .putBoolean("enabled", true)
            .putInt("method", METHOD_DEVICE)
            .remove("hash").remove("salt").remove("iter")
            .putBoolean("biometric", false)
            .apply()
        unlocked = true
    }

    /** Define a senha própria e ativa o bloqueio com ela. Lento (PBKDF2): fora da main thread. */
    fun enableOwn(ctx: Context, password: CharArray, kind: Int) {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val hash = pbkdf2(password, salt, ITERATIONS)
        password.fill('\u0000')
        sp(ctx).edit()
            .putBoolean("enabled", true)
            .putInt("method", METHOD_OWN)
            .putInt("kind", kind)
            .putString("salt", b64(salt))
            .putString("hash", b64(hash))
            .putInt("iter", ITERATIONS)
            .putInt("fails", 0)
            .putLong("wait_until", 0L)
            .commit()
        unlocked = true
    }

    /** Desativa e apaga tudo (hash, salt, preferências do bloqueio). */
    fun disable(ctx: Context) {
        sp(ctx).edit().clear().commit()
        unlocked = true
    }

    // ---- Sessão ----

    fun needsUnlock(ctx: Context) = enabled(ctx) && !unlocked

    fun markUnlocked(ctx: Context) {
        unlocked = true
        sp(ctx).edit().putInt("fails", 0).putLong("wait_until", 0L).apply()
    }

    /**
     * O app vai abrir uma tela do sistema que volta para ele (seletor de pastas, conta
     * Google, senha do celular): a saída logo em seguida não conta para o rebloqueio.
     * Abrir um arquivo em outro app conta (é sair do Index).
     */
    fun beginOwnFlow() {
        ownFlowAt = SystemClock.elapsedRealtime()
    }

    /**
     * Um prompt de autenticação está aberto. A tela de PIN/padrão do aparelho é outra
     * Activity: sair para ela não pode contar como "saiu do app".
     */
    @Volatile var promptActive = false

    /** A saída do app agora é para uma tela nossa (prompt, seletor do sistema): não conta. */
    fun inOwnFlow() = promptActive || SystemClock.elapsedRealtime() - ownFlowAt < OWN_FLOW_MS

    /** A Activity saiu da tela (onStop). */
    fun onHidden() {
        hiddenAt = if (inOwnFlow()) 0L else SystemClock.elapsedRealtime()
    }

    /** A Activity voltou (onStart): rebloqueia se passou do tempo configurado. */
    fun onShown(ctx: Context) {
        val was = hiddenAt
        hiddenAt = 0L
        if (was > 0 && SystemClock.elapsedRealtime() - was >= RELOCK_MS[relockIndex(ctx)]) unlocked = false
    }

    // ---- Senha própria ----

    /** Tempo restante de espera após tentativas erradas (0 = pode tentar). */
    fun waitRemainingMs(ctx: Context): Long {
        val until = sp(ctx).getLong("wait_until", 0L)
        val now = System.currentTimeMillis()
        if (until <= now) return 0L
        // Relógio voltado para trás: limita ao maior intervalo possível.
        return minOf(until - now, WAIT_MS)
    }

    /**
     * Confere a senha própria. Lento (PBKDF2): fora da main thread. A partir do 5º erro,
     * cada erro impõe 5 min de espera; durante a espera nem calcula (retorna false).
     */
    fun verify(ctx: Context, password: CharArray): Boolean {
        try {
            if (waitRemainingMs(ctx) > 0) return false
            val p = sp(ctx)
            val salt = p.getString("salt", null)?.let(::unb64) ?: return false
            val hash = p.getString("hash", null)?.let(::unb64) ?: return false
            val iter = p.getInt("iter", ITERATIONS)
            val ok = MessageDigest.isEqual(hash, pbkdf2(password, salt, iter))
            if (ok) {
                markUnlocked(ctx)
            } else {
                val fails = p.getInt("fails", 0) + 1
                val e = p.edit().putInt("fails", fails)
                if (fails >= FREE_FAILURES) {
                    e.putLong("wait_until", System.currentTimeMillis() + WAIT_MS)
                }
                e.commit()
            }
            return ok
        } finally {
            password.fill('\u0000')
        }
    }

    /** A senha do app como [Secret] (diálogos de senha genéricos do [app.index.files.ui.Auth]). */
    fun secret(ctx: Context): Secret = object : Secret {
        override val kind get() = this@AppLock.kind(ctx)
        override val biometric get() = this@AppLock.biometric(ctx)
        override fun waitRemainingMs() = this@AppLock.waitRemainingMs(ctx)
        override fun verify(pw: CharArray) = this@AppLock.verify(ctx, pw)
    }

    internal const val HASH_ITERATIONS = ITERATIONS
    internal const val FAILURES_BEFORE_WAIT = FREE_FAILURES
    internal const val FAIL_WAIT_MS = WAIT_MS

    internal fun pbkdf2(password: CharArray, salt: ByteArray, iterations: Int): ByteArray {
        val spec = PBEKeySpec(password, salt, iterations, KEY_BITS)
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    private fun b64(b: ByteArray) = Base64.encodeToString(b, Base64.NO_WRAP)
    private fun unb64(s: String) = Base64.decode(s, Base64.NO_WRAP)

    // ---- Recursos do aparelho ----

    /** O celular tem PIN, padrão ou senha de tela. */
    fun deviceSecure(ctx: Context) = ctx.getSystemService(KeyguardManager::class.java).isDeviceSecure

    /** Biometria forte cadastrada (BiometricPrompt da plataforma, API 28+). */
    fun biometricAvailable(ctx: Context): Boolean = when {
        Build.VERSION.SDK_INT >= 30 -> ctx.getSystemService(BiometricManager::class.java)
            .canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) == BiometricManager.BIOMETRIC_SUCCESS
        Build.VERSION.SDK_INT == 29 -> @Suppress("DEPRECATION")
            (ctx.getSystemService(BiometricManager::class.java).canAuthenticate() == BiometricManager.BIOMETRIC_SUCCESS)
        Build.VERSION.SDK_INT == 28 -> ctx.packageManager.hasSystemFeature(PackageManager.FEATURE_FINGERPRINT)
        else -> false
    }
}
