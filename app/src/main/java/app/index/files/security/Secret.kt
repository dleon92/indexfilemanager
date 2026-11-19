package app.index.files.security

import android.util.Base64
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom

/** Uma senha verificável (do app, do acesso às pastas protegidas ou de uma pasta). */
interface Secret {
    val kind: Int
    val biometric: Boolean
    fun waitRemainingMs(): Long
    /** Lento (PBKDF2): fora da main thread. Zera [pw]. */
    fun verify(pw: CharArray): Boolean
}

/**
 * Senha própria guardada dentro do cofre (já cifrado): só o hash PBKDF2-SHA256 com salt,
 * mesmas regras de tentativas da senha do app (5 erros ⇒ 5 min por erro). [onChange]
 * persiste o cofre depois de cada tentativa.
 */
class PasswordRec(
    override var kind: Int,
    private var salt: ByteArray,
    private var hash: ByteArray,
    private var iter: Int,
    override var biometric: Boolean,
    private var fails: Int = 0,
    private var waitUntil: Long = 0L,
) : Secret {
    var onChange: (() -> Unit)? = null

    override fun waitRemainingMs(): Long {
        val now = System.currentTimeMillis()
        if (waitUntil <= now) return 0L
        return minOf(waitUntil - now, AppLock.FAIL_WAIT_MS)
    }

    @Synchronized
    override fun verify(pw: CharArray): Boolean {
        try {
            if (waitRemainingMs() > 0) return false
            val ok = MessageDigest.isEqual(hash, AppLock.pbkdf2(pw, salt, iter))
            if (ok) {
                fails = 0
                waitUntil = 0L
            } else {
                fails++
                if (fails >= AppLock.FAILURES_BEFORE_WAIT) waitUntil = System.currentTimeMillis() + AppLock.FAIL_WAIT_MS
            }
            onChange?.invoke()
            return ok
        } finally {
            pw.fill('\u0000')
        }
    }

    fun toJson(): JSONObject = JSONObject()
        .put("kind", kind)
        .put("salt", b64(salt))
        .put("hash", b64(hash))
        .put("iter", iter)
        .put("bio", biometric)
        .put("fails", fails)
        .put("wait", waitUntil)

    companion object {
        /** Cria a partir da senha digitada (lento: fora da main). Zera [pw]. */
        fun create(pw: CharArray, kind: Int, biometric: Boolean = false): PasswordRec {
            val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
            try {
                return PasswordRec(kind, salt, AppLock.pbkdf2(pw, salt, AppLock.HASH_ITERATIONS), AppLock.HASH_ITERATIONS, biometric)
            } finally {
                pw.fill('\u0000')
            }
        }

        fun fromJson(o: JSONObject) = PasswordRec(
            kind = o.getInt("kind"),
            salt = unb64(o.getString("salt")),
            hash = unb64(o.getString("hash")),
            iter = o.getInt("iter"),
            biometric = o.optBoolean("bio"),
            fails = o.optInt("fails"),
            waitUntil = o.optLong("wait"),
        )

        private fun b64(b: ByteArray) = Base64.encodeToString(b, Base64.NO_WRAP)
        private fun unb64(s: String) = Base64.decode(s, Base64.NO_WRAP)
    }
}
