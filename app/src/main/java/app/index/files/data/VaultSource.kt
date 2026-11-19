package app.index.files.data

import app.index.files.security.VaultEntry

/**
 * Origem de uma pasta protegida: mesma pasta local/Drive de sempre, mas marcada para que as
 * miniaturas vão para o armazenamento cifrado do cofre, nunca para o cache comum.
 */
class VaultSource(val inner: FileSource, val entry: VaultEntry) : FileSource by inner {
    override val cacheTag: String = "vault:" + inner.cacheTag
}
