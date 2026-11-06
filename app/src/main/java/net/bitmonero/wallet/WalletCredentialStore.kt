package net.bitmonero.wallet

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Persists the wallet's own randomly-generated password (see
 * WalletSession.generateRandomPassword) so the wallet can be reopened
 * after the app process dies -- without this, the password only ever
 * existed in a local variable in MainActivity's composition and was lost
 * the moment the process was killed, permanently locking the wallet out
 * (surfaced as a native null-pointer crash in getAddress() on cold start:
 * hasWallet() was true but nothing ever called open(), so every wallet2
 * call went through a null Wallet*).
 *
 * AES-256-GCM via EncryptedSharedPreferences, key held in Android
 * Keystore -- same protection level as AppLock's stored passphrase hash,
 * appropriate since this value is exactly as sensitive as the wallet
 * itself (whoever has it can open the wallet file).
 */
object WalletCredentialStore {
    private const val FILE = "bitmonero_wallet_credentials"

    private fun prefs(context: Context) =
        EncryptedSharedPreferences.create(
            context, FILE,
            MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )

    fun savePassword(context: Context, walletName: String, password: String) {
        prefs(context).edit().putString(walletName, password).apply()
    }

    fun getPassword(context: Context, walletName: String): String? =
        prefs(context).getString(walletName, null)

    fun clear(context: Context, walletName: String) {
        prefs(context).edit().remove(walletName).apply()
    }
}
