package app.index.files.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * AES-256-GCM. A chave-mestra vive no Android Keystore (nunca sai do hardware/TEE) e só
 * cifra o arquivo do cofre; dentro dele vai uma chave de dados aleatória, usada para o
 * índice e as miniaturas das pastas protegidas (cifrar cada miniatura no Keystore custaria
 * uma chamada ao sistema por imagem).
 *
 * Formato: IV (12 bytes) + texto cifrado com a tag (16 bytes).
 */
object Crypto {
    private const val ALIAS = "index_vault"
    private const val IV = 12
    private const val TAG_BITS = 128

    private fun keystore() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    @Synchronized
    fun masterKey(): SecretKey {
        val ks = keystore()
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    /** Apaga a chave-mestra (redefinir app): tudo o que ela cifrou fica ilegível. */
    fun deleteMasterKey() {
        runCatching { keystore().deleteEntry(ALIAS) }
    }

    fun newDataKey(): ByteArray = ByteArray(32).also { SecureRandom().nextBytes(it) }

    fun dataKey(raw: ByteArray): SecretKey = SecretKeySpec(raw, "AES")

    fun encrypt(key: SecretKey, plain: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        if (key is SecretKeySpec) {
            // Chave de dados: o IV é nosso.
            val iv = ByteArray(IV).also { SecureRandom().nextBytes(it) }
            c.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
            return iv + c.doFinal(plain)
        }
        // Keystore gera o IV (não aceita IV escolhido por padrão).
        c.init(Cipher.ENCRYPT_MODE, key)
        return c.iv + c.doFinal(plain)
    }

    fun decrypt(key: SecretKey, data: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, data, 0, IV))
        return c.doFinal(data, IV, data.size - IV)
    }
}
