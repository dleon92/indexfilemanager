package app.index.files.security

import android.content.Context
import android.os.SystemClock
import android.util.Base64
import android.util.Log
import app.index.files.data.Root
import app.index.files.data.Roots
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import javax.crypto.SecretKey

/** Uma pasta protegida (item C). [ids]/[names]: caminho da raiz até ela (inclusive). */
class VaultEntry(
    val id: String,
    val rootId: Long,
    /** Origem para comparar ids (autoridade SAF ou "drive"). */
    val tag: String,
    val ids: List<String>,
    val names: List<String>,
    var method: Int,
    var password: PasswordRec?,
) {
    val folderId get() = ids.last()
    val name get() = names.last()
    val isRoot get() = ids.size == 1
    /** Caminho exibido na lista das pastas protegidas (só lá). */
    val path get() = names.joinToString(" / ")
    /** Caminho relativo da pasta dentro da raiz (para o índice comum ao remover a proteção). */
    val relPath get() = names.drop(1).joinToString("/")

    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("root", rootId)
        .put("tag", tag)
        .put("ids", JSONArray(ids))
        .put("names", JSONArray(names))
        .put("method", method)
        .apply { password?.let { put("pw", it.toJson()) } }

    companion object {
        fun fromJson(o: JSONObject): VaultEntry {
            val ids = o.getJSONArray("ids")
            val names = o.getJSONArray("names")
            return VaultEntry(
                id = o.getString("id"),
                rootId = o.getLong("root"),
                tag = o.getString("tag"),
                ids = List(ids.length()) { ids.getString(it) },
                names = List(names.length()) { names.getString(it) },
                method = o.getInt("method"),
                password = o.optJSONObject("pw")?.let(PasswordRec::fromJson),
            )
        }
    }
}

/**
 * Cofre das pastas protegidas. Tudo num arquivo só (`files/vault/vault.bin`), cifrado com
 * AES-GCM pela chave-mestra do Keystore: a lista das pastas, as senhas próprias (hash) e a
 * chave de dados do índice/miniaturas delas. Sem pastas protegidas o arquivo nem existe e
 * nada toca no Keystore (abertura do app sem custo extra).
 *
 * As pastas ficam ocultas em todo o Index ([isHidden]); só aparecem na tela "Pastas
 * protegidas", depois da autenticação, e só durante a sessão.
 */
object Vault {
    const val METHOD_APP = 0
    const val METHOD_DEVICE = 1
    const val METHOD_OWN = 2

    /** "Ocultar de novo após": imediatamente, 1, 5, 15 min fora do app. */
    val RELOCK_MS = longArrayOf(0L, 60_000L, 300_000L, 900_000L)
    private const val DEFAULT_RELOCK = 2
    private const val TAG = "Vault"

    private class State(
        val entries: List<VaultEntry>,
        val hidden: Set<String>,
        val dataKey: ByteArray?,
        val gate: PasswordRec?,
    )

    @Volatile private var state: State? = null
    private val roots = HashMap<Long, String>()

    // Sessão (só na memória).
    @Volatile private var gateOpen = false
    private val open = HashSet<String>()
    @Volatile private var hiddenAt = 0L

    fun dir(ctx: Context) = File(ctx.applicationContext.filesDir, "vault")
    private fun file(ctx: Context) = File(dir(ctx), "vault.bin")

    // ---- Carregar / salvar ----

    private fun st(ctx: Context): State = state ?: synchronized(this) {
        state ?: load(ctx).also { state = it }
    }

    private fun load(ctx: Context): State {
        val f = file(ctx)
        if (!f.exists()) return State(emptyList(), emptySet(), null, null)
        return try {
            val json = JSONObject(String(Crypto.decrypt(Crypto.masterKey(), f.readBytes()), Charsets.UTF_8))
            val arr = json.optJSONArray("entries") ?: JSONArray()
            val entries = List(arr.length()) { VaultEntry.fromJson(arr.getJSONObject(it)) }
            val dk = json.optString("dk").takeIf { it.isNotEmpty() }?.let { Base64.decode(it, Base64.NO_WRAP) }
            val gate = json.optJSONObject("gate")?.let(PasswordRec::fromJson)
            build(ctx, entries, dk, gate)
        } catch (e: Exception) {
            // Chave apagada (ex.: dados restaurados em outro aparelho): o conteúdo é irrecuperável.
            Log.e(TAG, "cofre ilegível", e)
            State(emptyList(), emptySet(), null, null)
        }
    }

    private fun build(ctx: Context, entries: List<VaultEntry>, dk: ByteArray?, gate: PasswordRec?): State {
        val save = { save(ctx) }
        gate?.onChange = save
        entries.forEach { it.password?.onChange = save }
        return State(entries, entries.mapTo(HashSet()) { "${it.tag}|${it.folderId}" }, dk, gate)
    }

    @Synchronized
    private fun save(ctx: Context) {
        val s = st(ctx)
        val json = JSONObject()
            .put("v", 1)
            .put("entries", JSONArray().apply { s.entries.forEach { put(it.toJson()) } })
        s.dataKey?.let { json.put("dk", Base64.encodeToString(it, Base64.NO_WRAP)) }
        s.gate?.let { json.put("gate", it.toJson()) }
        val d = dir(ctx).apply { mkdirs() }
        val tmp = File(d, "vault.tmp")
        tmp.writeBytes(Crypto.encrypt(Crypto.masterKey(), json.toString().toByteArray(Charsets.UTF_8)))
        if (!tmp.renameTo(file(ctx))) {
            tmp.delete()
            throw java.io.IOException("não foi possível salvar o cofre")
        }
    }

    @Synchronized
    private fun update(ctx: Context, entries: List<VaultEntry> = st(ctx).entries, gate: PasswordRec? = st(ctx).gate) {
        val dk = st(ctx).dataKey ?: Crypto.newDataKey()
        state = build(ctx, entries, dk, gate)
        save(ctx)
    }

    // ---- Consultas (rápidas; usadas em toda listagem) ----

    fun entries(ctx: Context): List<VaultEntry> = st(ctx).entries
    fun entry(ctx: Context, id: String): VaultEntry? = st(ctx).entries.firstOrNull { it.id == id }
    fun isEmpty(ctx: Context) = st(ctx).entries.isEmpty()

    private fun tagFor(ctx: Context, rootId: Long): String? = synchronized(roots) {
        roots[rootId] ?: Roots.get(ctx, rootId)?.let { tagOf(it) }?.also { roots[rootId] = it }
    }

    fun tagOf(r: Root): String = when {
        r.isDrive -> Roots.DRIVE
        r.isFile -> Roots.FILE
        r.isRecent -> Roots.RECENT
        else -> r.uri.authority ?: "local"
    }

    /** A pasta [id] (dentro da raiz [rootId]) é protegida: some de listagens, índice e busca. */
    fun isHidden(ctx: Context, rootId: Long, id: String): Boolean {
        val h = st(ctx).hidden
        if (h.isEmpty()) return false
        val tag = tagFor(ctx, rootId) ?: return false
        return "$tag|$id" in h
    }

    /** Mesmo que [isHidden], pela origem ([tagOf]) em vez da raiz. */
    fun isHiddenTag(ctx: Context, tag: String, id: String): Boolean {
        val h = st(ctx).hidden
        return h.isNotEmpty() && "$tag|$id" in h
    }

    /**
     * Raiz oculta: ela mesma protegida ou, no armazenamento local (ids são caminhos), dentro
     * de uma pasta protegida (pastas permitidas sobrepostas).
     */
    fun isHiddenRoot(ctx: Context, r: Root): Boolean {
        val s = st(ctx)
        if (s.hidden.isEmpty()) return false
        val tag = tagOf(r)
        if ("$tag|${r.docId}" in s.hidden) return true
        if (r.isDrive) return false
        return s.entries.any { it.tag == tag && r.docId.startsWith(it.folderId.trimEnd('/') + "/") }
    }

    /**
     * Caminhos absolutos (com "/" no fim) das pastas protegidas do armazenamento do aparelho,
     * para listas que não passam pelas raízes (pasta virtual Recentes). Drive fica de fora.
     */
    fun protectedPaths(ctx: Context): List<String> = st(ctx).entries.mapNotNull { e ->
        localPath(e.tag, e.folderId)?.trimEnd('/')?.plus("/")
    }

    /** Caminho absoluto de uma pasta do armazenamento do aparelho (pelo caminho ou SAF); null no Drive. */
    private fun localPath(tag: String, id: String): String? = when (tag) {
        Roots.FILE -> id
        "com.android.externalstorage.documents" -> {
            val vol = id.substringBefore(':', "")
            val rel = id.substringAfter(':', "")
            if (vol.isEmpty()) null
            else (if (vol == "primary") android.os.Environment.getExternalStorageDirectory().absolutePath else "/storage/$vol") + if (rel.isEmpty()) "" else "/$rel"
        }
        else -> null
    }

    /**
     * A pasta [dirId] (da raiz [rootId]) tem uma pasta protegida dentro: apagá-la levaria a
     * protegida junto. Compara pela origem e, no aparelho, pelo caminho (a mesma pasta pode ter
     * sido adicionada pelo SAF e pelo caminho).
     */
    fun containsProtected(ctx: Context, rootId: Long, dirId: String): Boolean {
        val es = st(ctx).entries
        if (es.isEmpty()) return false
        val tag = tagFor(ctx, rootId) ?: return false
        if (es.any { e -> e.tag == tag && e.folderId != dirId && (dirId in e.ids || (tag != Roots.DRIVE && e.folderId.startsWith("$dirId/"))) }) return true
        val path = localPath(tag, dirId)?.trimEnd('/')?.plus("/") ?: return false
        return protectedPaths(ctx).any { it.startsWith(path) && it != path }
    }

    fun dataKey(ctx: Context): SecretKey = Crypto.dataKey(st(ctx).dataKey ?: error("cofre sem chave"))

    // ---- Alterações ----

    fun protect(ctx: Context, r: Root, ids: List<String>, names: List<String>, method: Int, password: PasswordRec?): VaultEntry {
        val e = VaultEntry(UUID.randomUUID().toString(), r.id, tagOf(r), ids, names, method, password)
        update(ctx, entries = entries(ctx) + e)
        return e
    }

    fun unprotect(ctx: Context, id: String) {
        update(ctx, entries = entries(ctx).filter { it.id != id })
        synchronized(open) { open.remove(id) }
        File(dir(ctx), id).deleteRecursively()
    }

    /**
     * Pasta local renomeada ou movida pelo Index: no armazenamento local o id é o caminho, então
     * pastas protegidas dentro dela mudam de id. Atualiza o cofre para continuarem ocultas.
     */
    fun remap(ctx: Context, rootId: Long, oldId: String, newId: String, newName: String) {
        val tag = tagFor(ctx, rootId) ?: return
        if (tag == Roots.DRIVE || oldId == newId) return
        var changed = false
        val out = entries(ctx).map { e ->
            if (e.tag != tag) return@map e
            val idx = e.ids.indexOfFirst { it == oldId || it.startsWith("$oldId/") }
            if (idx < 0) return@map e
            changed = true
            val ids = e.ids.map { if (it == oldId) newId else if (it.startsWith("$oldId/")) newId + it.substring(oldId.length) else it }
            val names = e.names.toMutableList()
            if (e.ids[idx] == oldId) names[idx] = newName
            VaultEntry(e.id, e.rootId, e.tag, ids, names, e.method, e.password)
        }
        if (changed) update(ctx, entries = out)
    }

    /**
     * Pasta [deletedId] apagada (ou movida por cópia): as pastas protegidas que eram ela ou
     * estavam dentro dela deixaram de existir. Tira do cofre e devolve os ids removidos.
     */
    fun forgetDeleted(ctx: Context, rootId: Long, deletedId: String): List<String> {
        val tag = tagFor(ctx, rootId) ?: return emptyList()
        val gone = entries(ctx).filter { e ->
            e.tag == tag && (deletedId in e.ids || (tag != Roots.DRIVE && e.folderId.startsWith("$deletedId/")))
        }
        if (gone.isEmpty()) return emptyList()
        val ids = gone.mapTo(HashSet()) { it.id }
        update(ctx, entries = entries(ctx).filter { it.id !in ids })
        synchronized(open) { open.removeAll(ids) }
        for (id in ids) File(dir(ctx), id).deleteRecursively()
        return ids.toList()
    }

    /** Pasta com o bloqueio do celular, mas o celular ficou sem bloqueio: passa para senha própria. */
    fun setPassword(ctx: Context, id: String, rec: PasswordRec) {
        entry(ctx, id)?.let {
            it.method = METHOD_OWN
            it.password = rec
        }
        update(ctx)
    }

    // ---- Acesso à lista ("portão") ----

    /**
     * Como a lista é aberta: senha do app (se o bloqueio do app estiver ativo), senão o
     * bloqueio do celular, senão uma senha própria das pastas protegidas.
     */
    fun gateMethod(ctx: Context): Int = when {
        AppLock.enabled(ctx) -> METHOD_APP
        AppLock.deviceSecure(ctx) -> METHOD_DEVICE
        else -> METHOD_OWN
    }

    fun gatePassword(ctx: Context): PasswordRec? = st(ctx).gate

    fun setGatePassword(ctx: Context, rec: PasswordRec) = update(ctx, gate = rec)

    // ---- Sessão ----

    fun openGate() {
        gateOpen = true
    }

    fun gateIsOpen() = gateOpen

    fun unlock(id: String) = synchronized(open) { open.add(id) }

    fun isOpen(id: String) = synchronized(open) { id in open }

    /** Alguma pasta protegida (ou a lista) está visível: a tela não pode ir para os recentes. */
    fun anyOpen() = gateOpen || synchronized(open) { open.isNotEmpty() }

    fun lock(id: String) = synchronized(open) { open.remove(id) }

    /** Telas abertas por pasta (navegador e subpastas): a pasta tranca quando a última fecha. */
    private val users = HashMap<String, Int>()

    fun retain(id: String) = synchronized(open) { users[id] = (users[id] ?: 0) + 1 }

    /** True se era a última tela: a pasta foi trancada. */
    fun release(id: String): Boolean = synchronized(open) {
        val n = (users[id] ?: 1) - 1
        if (n > 0) {
            users[id] = n
            false
        } else {
            users.remove(id)
            open.remove(id)
            true
        }
    }

    fun lockAll() {
        gateOpen = false
        synchronized(open) { open.clear() }
    }

    fun relockIndex(ctx: Context) = ctx.getSharedPreferences("lock", Context.MODE_PRIVATE)
        .getInt("vault_relock", DEFAULT_RELOCK).coerceIn(0, RELOCK_MS.size - 1)

    fun setRelockIndex(ctx: Context, i: Int) =
        ctx.getSharedPreferences("lock", Context.MODE_PRIVATE).edit().putInt("vault_relock", i).apply()

    fun onHidden() {
        hiddenAt = if (AppLock.inOwnFlow()) 0L else SystemClock.elapsedRealtime()
    }

    /** Volta ao app: true se as pastas foram ocultadas de novo (tempo esgotado). */
    fun onShown(ctx: Context): Boolean {
        val was = hiddenAt
        hiddenAt = 0L
        if (!anyOpen() || was == 0L) return false
        if (SystemClock.elapsedRealtime() - was < RELOCK_MS[relockIndex(ctx)]) return false
        lockAll()
        return true
    }

    /** Redefinir app. */
    fun wipe(ctx: Context) {
        state = null
        lockAll()
        synchronized(roots) { roots.clear() }
        dir(ctx).deleteRecursively()
        Crypto.deleteMasterKey()
    }
}
