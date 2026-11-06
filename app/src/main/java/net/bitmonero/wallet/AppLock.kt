package net.bitmonero.wallet

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * App-level lock: a passphrase and/or biometric gate shown before the
 * wallet UI is reachable. This is a UI gate, not a hardware-bound crypto
 * boundary -- BiometricPrompt success just unlocks the screen here, it
 * isn't tied to a Keystore CryptoObject the way a "sign this transaction
 * with biometric confirmation" flow would be. Worth being precise about:
 * it stops someone picking up an unlocked phone from opening the wallet,
 * it does not add cryptographic protection to the wallet file itself
 * (wallet2's own password + Android's app-sandbox + allowBackup=false
 * already cover that, independent of whether this lock is even enabled).
 *
 * The passphrase is stored as a salted SHA-256 hash inside
 * EncryptedSharedPreferences (AES-256-GCM, key held in Android Keystore),
 * never in plain prefs and never as the plaintext passphrase itself.
 */
object AppLock {
    private const val FILE = "bitmonero_lock"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_TYPE = "type" // "PIN" or "PASSWORD"
    private const val KEY_SALT = "salt"
    private const val KEY_HASH = "hash"
    private const val KEY_BIOMETRIC = "biometric_enabled"

    enum class LockType { PIN, PASSWORD }

    private fun prefs(context: Context): SharedPreferences {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedSharedPreferences.create(
            context, FILE, masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    fun isEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ENABLED, false)
    fun isBiometricEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_BIOMETRIC, false)

    fun lockType(context: Context): LockType? =
        prefs(context).getString(KEY_TYPE, null)?.let { runCatching { LockType.valueOf(it) }.getOrNull() }

    /** Minimum length is enforced by the caller (UI) per type -- 4 digits for PIN, 4 chars for password. */
    fun setCredential(context: Context, type: LockType, value: String) {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val hash = hash(value, salt)
        prefs(context).edit()
            .putBoolean(KEY_ENABLED, true)
            .putString(KEY_TYPE, type.name)
            .putString(KEY_SALT, salt.joinToString("") { "%02x".format(it) })
            .putString(KEY_HASH, hash)
            .apply()
    }

    fun verifyCredential(context: Context, attempt: String): Boolean {
        val p = prefs(context)
        val saltHex = p.getString(KEY_SALT, null) ?: return false
        val expected = p.getString(KEY_HASH, null) ?: return false
        val salt = saltHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        return hash(attempt, salt) == expected
    }

    fun setBiometricEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_BIOMETRIC, enabled).apply()
    }

    /** Turns the lock off entirely (PIN/password + biometric). */
    fun disable(context: Context) {
        prefs(context).edit().clear().apply()
    }

    private fun hash(input: String, salt: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(salt)
        val bytes = digest.digest(input.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
