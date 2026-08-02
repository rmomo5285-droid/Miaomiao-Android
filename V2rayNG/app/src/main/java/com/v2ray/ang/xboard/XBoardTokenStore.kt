package com.v2ray.ang.xboard

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.tencent.mmkv.MMKV
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

interface XBoardTokenStore {
    fun readToken(): String?
    fun writeToken(token: String)
    fun clear()
}

/**
 * Stores only AES-GCM ciphertext and its IV in MMKV. The AES key never leaves Android Keystore.
 */
class AndroidKeystoreTokenStore(
    private val storage: MMKV = MMKV.mmkvWithID(STORAGE_ID, MMKV.MULTI_PROCESS_MODE),
) : XBoardTokenStore {
    @Synchronized
    override fun readToken(): String? {
        val encrypted = storage.decodeBytes(KEY_CIPHERTEXT) ?: return null
        val iv = storage.decodeBytes(KEY_IV) ?: return null
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
            cipher.doFinal(encrypted).toString(Charsets.UTF_8).takeIf(String::isNotBlank)
        } catch (_: Exception) {
            clearStoredValue()
            null
        }
    }

    @Synchronized
    override fun writeToken(token: String) {
        require(token.isNotBlank()) { "Authentication token must not be blank" }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val encrypted = cipher.doFinal(token.toByteArray(Charsets.UTF_8))

        val ivWritten = storage.encode(KEY_IV, cipher.iv)
        val ciphertextWritten = ivWritten && storage.encode(KEY_CIPHERTEXT, encrypted)
        if (!ciphertextWritten) {
            clearStoredValue()
            throw IllegalStateException("Encrypted token could not be stored")
        }
    }

    @Synchronized
    override fun clear() {
        clearStoredValue()
    }

    private fun clearStoredValue() {
        storage.removeValuesForKeys(arrayOf(KEY_CIPHERTEXT, KEY_IV))
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }

        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE).run {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true)
                    .build(),
            )
            generateKey()
        }
    }

    private companion object {
        const val STORAGE_ID = "MIAOMIAO_XBOARD_AUTH"
        const val KEY_CIPHERTEXT = "TOKEN_CIPHERTEXT"
        const val KEY_IV = "TOKEN_IV"
        const val KEY_ALIAS = "miaomiao_xboard_token_v1"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_TAG_BITS = 128
    }
}
