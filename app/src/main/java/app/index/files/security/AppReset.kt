package app.index.files.security

import android.app.Activity
import android.content.Context
import android.content.Intent
import app.index.files.data.AppDb
import app.index.files.data.drive.DriveAuth
import java.io.File

/**
 * "Redefinir app" (esqueci a senha): apaga configurações, bloqueio, índice, cache de
 * miniaturas, pastas protegidas e o login do Drive, e libera as pastas autorizadas. Os arquivos do usuário
 * nunca são tocados: só o que o Index guarda em seu próprio espaço.
 */
object AppReset {

    /** Bloqueante (rede e disco): fora da main thread. */
    fun wipe(ctx: Context) {
        val app = ctx.applicationContext
        // Revoga o acesso ao Drive (sem rede, só esquece o token).
        runCatching { DriveAuth.signOut(app) }
        val cr = app.contentResolver
        for (p in cr.persistedUriPermissions) {
            val flags = (if (p.isReadPermission) Intent.FLAG_GRANT_READ_URI_PERMISSION else 0) or
                (if (p.isWritePermission) Intent.FLAG_GRANT_WRITE_URI_PERMISSION else 0)
            runCatching { cr.releasePersistableUriPermission(p.uri, flags) }
        }
        runCatching { AppDb.get(app).close() }
        app.deleteDatabase("index.db")
        app.cacheDir.listFiles()?.forEach { it.deleteRecursively() }
        File(app.filesDir, "thumbs").deleteRecursively()
        // Pastas protegidas: cofre, índice e miniaturas cifradas, e a chave do Keystore.
        runCatching { Vault.wipe(app) }
        // Todas as SharedPreferences (configurações, bloqueio…).
        File(app.dataDir, "shared_prefs").listFiles()?.forEach { f ->
            app.getSharedPreferences(f.name.removeSuffix(".xml"), Context.MODE_PRIVATE).edit().clear().commit()
        }
    }

    /** Reinicia o app do zero (novo processo, sem nada em memória). */
    fun restart(activity: Activity) {
        val i = activity.packageManager.getLaunchIntentForPackage(activity.packageName) ?: return
        activity.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        activity.finishAffinity()
        Runtime.getRuntime().exit(0)
    }
}
