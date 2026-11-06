package net.bitmonero.wallet

/**
 * Thin JNI declarations matching bitmonero_jni.cpp 1:1. No logic lives
 * here on purpose -- see that file for the security invariants (seed
 * never persisted before user confirmation, restore never overwrites an
 * existing wallet file, daemon address is never hardcoded).
 */
object NativeWallet {
    init {
        System.loadLibrary("bitmonero_wallet_jni")
    }

    external fun walletExists(path: String): Boolean

    /** Returns 0 if [path] already has a wallet (caller must use openWallet). */
    external fun createWallet(path: String, password: String, language: String): Long

    external fun getSeed(walletPtr: Long): String
    external fun getSecretSpendKey(walletPtr: Long): String
    external fun getSecretViewKey(walletPtr: Long): String

    /** Only call after the user has confirmed they recorded the seed. */
    external fun commitToDisk(walletPtr: Long): Boolean

    /** Returns 0 if [path] already exists -- never silently overwrites. */
    external fun restoreFromSeed(path: String, password: String, seed: String, restoreHeight: Long): Long

    /** Returns 0 on a wrong password or any open failure. */
    external fun openWallet(path: String, password: String): Long

    external fun initDaemon(walletPtr: Long, daemonAddress: String, trusted: Boolean)
    external fun startRefresh(walletPtr: Long)
    external fun rescanBlockchain(walletPtr: Long)
    external fun isSynchronized(walletPtr: Long): Boolean

    /** 0=Disconnected, 1=Connected, 2=WrongVersion (Wallet::ConnectionStatus). */
    external fun getConnectionStatus(walletPtr: Long): Int
    external fun getWalletHeight(walletPtr: Long): Long
    external fun getDaemonHeight(walletPtr: Long): Long
    external fun getAddress(walletPtr: Long): String
    external fun getBalance(walletPtr: Long): Long
    external fun getUnlockedBalance(walletPtr: Long): Long
    external fun getStatusMessage(walletPtr: Long): String
    external fun closeWallet(walletPtr: Long)

    external fun getSubaddressCount(walletPtr: Long): Int
    external fun getSubaddress(walletPtr: Long, index: Int): String
    external fun getSubaddressLabel(walletPtr: Long, index: Int): String
    external fun addSubaddress(walletPtr: Long, label: String)
    external fun isAddressValid(address: String): Boolean

    /** Returns "OK:<txid>:<fee_atomic>" or "ERROR:<message>" -- parse the prefix. */
    external fun sendTransaction(walletPtr: Long, address: String, amountAtomic: Long, priority: Int): String
}
