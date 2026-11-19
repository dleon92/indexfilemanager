package app.index.files.data.drive

import android.accounts.Account
import android.content.Context
import android.os.SystemClock
import android.util.Log
import app.index.files.data.Prefs
import com.google.android.gms.auth.GoogleAuthUtil
import com.google.android.gms.auth.api.identity.AuthorizationClient
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Tasks
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit

/** O usuário precisa (re)conectar a conta Google pela interface. */
class AuthRequired : IOException("conexão com o Google necessária")

/**
 * Login do Drive só com o AuthorizationClient (play-services-auth), escopo drive
 * (leitura e escrita, desde a fase 7; antes drive.readonly — quem já tinha conectado
 * confirma a conta uma vez). O token fica em memória (~45 min); o Play Services guarda a
 * autorização, então pedir de novo em segundo plano não mostra nada ao usuário.
 */
object DriveAuth {
    const val SCOPE = "https://www.googleapis.com/auth/drive"
    private const val TTL_MS = 45 * 60 * 1000L
    private const val TAG = "Drive"

    private var token: String? = null
    private var tokenAt = 0L

    fun client(ctx: Context): AuthorizationClient = Identity.getAuthorizationClient(ctx)

    /** Com [email], o Play Services usa a mesma conta sem perguntar. */
    fun request(email: String?): AuthorizationRequest {
        val b = AuthorizationRequest.builder().setRequestedScopes(listOf(Scope(SCOPE)))
        if (!email.isNullOrEmpty()) b.setAccount(Account(email, "com.google"))
        return b.build()
    }

    fun connected(ctx: Context) = Prefs.get(ctx).driveConnected

    /** Token válido (rede/Play Services; chamar fora da main thread). */
    @Synchronized
    fun token(ctx: Context): String {
        val prefs = Prefs.get(ctx)
        if (!prefs.driveConnected) throw AuthRequired()
        token?.let { if (SystemClock.elapsedRealtime() - tokenAt < TTL_MS) return it }
        val res = try {
            Tasks.await(client(ctx).authorize(request(prefs.driveEmail)), 30, TimeUnit.SECONDS)
        } catch (e: ExecutionException) {
            Log.w(TAG, "authorize falhou", e.cause)
            throw IOException("autorização falhou", e.cause)
        }
        if (res.hasResolution()) throw AuthRequired()
        return store(res) ?: throw AuthRequired()
    }

    @Synchronized
    private fun store(res: AuthorizationResult): String? {
        val t = res.accessToken ?: return null
        token = t
        tokenAt = SystemClock.elapsedRealtime()
        return t
    }

    /** 401: descarta o token (aqui e no cache do Play Services) para o próximo pedido gerar outro. */
    fun invalidate(ctx: Context, bad: String) {
        synchronized(this) { if (token == bad) token = null }
        try {
            GoogleAuthUtil.clearToken(ctx, bad)
        } catch (e: Exception) {
            Log.w(TAG, "clearToken", e)
        }
    }

    /** Depois do fluxo interativo: guarda o token e a conta (e-mail via API "about"). Fora da main. */
    fun onAuthorized(ctx: Context, res: AuthorizationResult) {
        store(res) ?: throw AuthRequired()
        val prefs = Prefs.get(ctx)
        prefs.driveConnected = true
        prefs.driveEmail = runCatching { DriveApi.email(ctx) }.getOrNull() ?: prefs.driveEmail
    }

    /** Desconecta: revoga o acesso no Google e esquece a conta. Fora da main. */
    fun signOut(ctx: Context) {
        val prefs = Prefs.get(ctx)
        val t = runCatching { token(ctx) }.getOrNull()
        synchronized(this) { token = null }
        prefs.driveConnected = false
        prefs.driveEmail = null
        if (t == null) return
        runCatching { GoogleAuthUtil.clearToken(ctx, t) }
        try {
            val c = URL("https://oauth2.googleapis.com/revoke").openConnection() as HttpURLConnection
            c.requestMethod = "POST"
            c.doOutput = true
            c.connectTimeout = 10_000
            c.readTimeout = 10_000
            c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            c.outputStream.use { it.write(("token=" + URLEncoder.encode(t, "UTF-8")).toByteArray()) }
            Log.i(TAG, "revoke: ${c.responseCode}")
            c.disconnect()
        } catch (e: Exception) {
            Log.w(TAG, "revoke falhou (sem rede?)", e)
        }
    }
}
