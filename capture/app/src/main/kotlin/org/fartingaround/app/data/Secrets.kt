package org.fartingaround.app.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Keeps the site token and the ElevenLabs key from sitting on the phone as
 * plain text. They are encrypted with a key that lives in the Android
 * Keystore: the phone's secure hardware holds it, it never leaves the device,
 * and only this app can ask for it to be used.
 *
 * What is stored is "k1:" + base64(nonce + ciphertext). AES-256-GCM, a fresh
 * nonce for every value.
 */
object Secrets {
	private const val ALIAS = "fartingaround-settings"
	private const val MARK = "k1:"
	private const val NONCE_BYTES = 12

	private fun key(): SecretKey {
		val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
		(store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
		val spec = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
			.setBlockModes(KeyProperties.BLOCK_MODE_GCM)
			.setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
			.setKeySize(256)
			.build()
		return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply { init(spec) }.generateKey()
	}

	/** Encrypts [plain]. Null when the phone's keystore can't be used; the caller decides what then. */
	fun seal(plain: String): String? = runCatching {
		val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
		val sealed = cipher.iv + cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
		MARK + Base64.encodeToString(sealed, Base64.NO_WRAP)
	}.getOrNull()

	/**
	 * Decrypts what [seal] made. Null when it can't be read: not ours, damaged,
	 * or the key is gone (the app's data was restored onto another phone). The
	 * owner then types the value in again; nothing else depends on it.
	 */
	fun open(sealed: String): String? = runCatching {
		if (!sealed.startsWith(MARK)) return null
		val bytes = Base64.decode(sealed.substring(MARK.length), Base64.NO_WRAP)
		if (bytes.size <= NONCE_BYTES) return null
		val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
			init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes, 0, NONCE_BYTES))
		}
		String(cipher.doFinal(bytes, NONCE_BYTES, bytes.size - NONCE_BYTES), Charsets.UTF_8)
	}.getOrNull()
}
