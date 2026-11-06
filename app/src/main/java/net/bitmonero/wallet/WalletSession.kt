package net.bitmonero.wallet

import android.content.Context
import java.io.File
import java.security.SecureRandom

/**
 * Owns the safe-ordering rules around wallet creation/restore. This class
 * exists specifically so a UI screen can't accidentally call the native
 * layer in the wrong order and reproduce either of the two known classes
 * of wallet bugs from the iXcoin project:
 *
 *  1. A generated seed getting discarded because something persisted the
 *     wallet before the user ever saw/confirmed the seed.
 *  2. A restore silently keeping an old wallet's keys because the wallet
 *     file already existed on disk (the iXcoin "different seed phrase,
 *     same wallet addresses" funds bug).
 *
 * Wallet files live under Context.filesDir (internal, app-private storage)
 * rather than external/shared storage, so no other app and no
 * "Files"-style UI can see them, and they're covered by the manifest's
 * allowBackup=false + data_extraction_rules regardless.
 */
class WalletSession(private val context: Context) {

    private var walletPtr: Long = 0
    private var pendingSeed: String? = null
    private var pendingPath: String? = null
    private var activePath: String? = null

    private fun walletDir(): File =
        File(context.filesDir, "wallets").apply { mkdirs() }

    fun walletPath(name: String): String = File(walletDir(), name).absolutePath

    fun hasWallet(name: String): Boolean = NativeWallet.walletExists(walletPath(name))

    /**
     * Step 1 of creating a new wallet: generates keys in memory and returns
     * the seed to show the user. Nothing is written to disk yet.
     */
    fun beginCreate(name: String, password: String): Result<String> {
        val path = walletPath(name)
        if (NativeWallet.walletExists(path)) {
            return Result.failure(IllegalStateException("A wallet named '$name' already exists"))
        }
        val ptr = NativeWallet.createWallet(path, password, "English")
        if (ptr == 0L) {
            return Result.failure(IllegalStateException("createWallet failed (already exists?)"))
        }
        walletPtr = ptr
        pendingPath = path
        val seed = NativeWallet.getSeed(ptr)
        pendingSeed = seed
        return Result.success(seed)
    }

    /**
     * Step 2: only called after the UI has made the user re-enter or
     * explicitly acknowledge the seed shown by beginCreate(). This is the
     * only path that actually writes the wallet keys file.
     */
    fun confirmSeedRecordedAndPersist(): Result<Unit> {
        val ptr = walletPtr
        val seed = pendingSeed
        if (ptr == 0L || seed == null) {
            return Result.failure(IllegalStateException("No pending wallet to commit"))
        }
        val ok = NativeWallet.commitToDisk(ptr)
        pendingSeed = null // never keep the seed in memory longer than needed
        if (ok) activePath = pendingPath
        return if (ok) Result.success(Unit) else Result.failure(IllegalStateException("store() failed"))
    }

    /** Abandons an unconfirmed create -- e.g. the user backed out of the seed screen. */
    fun discardPending() {
        if (walletPtr != 0L) {
            NativeWallet.closeWallet(walletPtr)
        }
        walletPtr = 0
        pendingSeed = null
        pendingPath?.let { File(it).delete(); File("$it.keys").delete() }
        pendingPath = null
    }

    fun restore(name: String, password: String, seed: String, restoreHeight: Long): Result<Unit> {
        val path = walletPath(name)
        if (NativeWallet.walletExists(path)) {
            // Refuse outright rather than silently opening the old wallet
            // and discarding the supplied seed -- this exact silent
            // behavior was the iXcoin funds bug.
            return Result.failure(
                IllegalStateException("A wallet named '$name' already exists; delete or rename it before restoring")
            )
        }
        val ptr = NativeWallet.restoreFromSeed(path, password, seed, restoreHeight)
        if (ptr == 0L) {
            return Result.failure(IllegalStateException("restore failed"))
        }
        // Guard against a mismatch between the supplied seed and what the
        // wallet actually derived, the same class of bug as iXcoin's restore
        // path -- verify before treating this as a live wallet. Compare on
        // NORMALIZED word lists (lowercased, whitespace collapsed) rather than
        // raw strings: the native seed() always returns the canonical 25-word
        // form, and the user's input may differ only by casing, extra spaces,
        // or the trailing checksum word. A purely textual `!=` false-rejected
        // correct seeds, which read to the user as "the wallet won't take my
        // seed phrase".
        fun normalize(s: String) = s.trim().lowercase().split(Regex("\\s+")).filter { it.isNotBlank() }
        val derivedWords = normalize(NativeWallet.getSeed(ptr))
        val enteredWords = normalize(seed)
        val n = minOf(derivedWords.size, enteredWords.size)
        val entropyMatches = n > 0 && derivedWords.take(n) == enteredWords.take(n)
        // Accept when the shared words all match and the lengths differ by at
        // most one (the optional checksum word); reject anything else.
        val seedConsistent = entropyMatches &&
            kotlin.math.abs(derivedWords.size - enteredWords.size) <= 1
        if (!seedConsistent) {
            NativeWallet.closeWallet(ptr)
            File(path).delete(); File("$path.keys").delete()
            return Result.failure(IllegalStateException("Restored wallet's seed does not match the seed entered -- refusing to use it"))
        }
        walletPtr = ptr
        activePath = path
        return Result.success(Unit)
    }

    fun open(name: String, password: String): Result<Unit> {
        val ptr = NativeWallet.openWallet(walletPath(name), password)
        if (ptr == 0L) {
            return Result.failure(IllegalStateException("wrong password or corrupt wallet file"))
        }
        walletPtr = ptr
        activePath = walletPath(name)
        return Result.success(Unit)
    }

    fun connect(daemonAddress: String, trusted: Boolean) {
        require(walletPtr != 0L) { "no open wallet" }
        NativeWallet.initDaemon(walletPtr, daemonAddress, trusted)
        NativeWallet.startRefresh(walletPtr)
    }

    fun isSynchronized(): Boolean = walletPtr != 0L && NativeWallet.isSynchronized(walletPtr)

    /** Rescans from genesis -- catches funds sent to a subaddress before this
     * wallet's own creation-time scan start point (see rescanBlockchain's
     * native-side comment). Takes real time; caller should show progress. */
    fun rescanFromGenesis() {
        require(walletPtr != 0L) { "no open wallet" }
        NativeWallet.rescanBlockchain(walletPtr)
    }

    enum class ConnectionState { DISCONNECTED, CONNECTED, WRONG_VERSION }

    data class SyncStatus(val connection: ConnectionState, val walletHeight: Long, val daemonHeight: Long)

    fun syncStatus(): SyncStatus {
        if (walletPtr == 0L) return SyncStatus(ConnectionState.DISCONNECTED, 0, 0)
        val conn = when (NativeWallet.getConnectionStatus(walletPtr)) {
            1 -> ConnectionState.CONNECTED
            2 -> ConnectionState.WRONG_VERSION
            else -> ConnectionState.DISCONNECTED
        }
        return SyncStatus(conn, NativeWallet.getWalletHeight(walletPtr), NativeWallet.getDaemonHeight(walletPtr))
    }
    fun address(): String = NativeWallet.getAddress(walletPtr)
    fun balanceAtomic(): Long = NativeWallet.getBalance(walletPtr)
    fun unlockedBalanceAtomic(): Long = NativeWallet.getUnlockedBalance(walletPtr)
    fun statusMessage(): String = NativeWallet.getStatusMessage(walletPtr)

    data class Subaddress(val index: Int, val address: String, val label: String)

    fun listSubaddresses(): List<Subaddress> {
        val count = NativeWallet.getSubaddressCount(walletPtr)
        return (0 until count).map { i ->
            Subaddress(i, NativeWallet.getSubaddress(walletPtr, i), NativeWallet.getSubaddressLabel(walletPtr, i))
        }
    }

    /** Returns the newly created subaddress. */
    fun generateNewAddress(label: String = ""): Subaddress {
        NativeWallet.addSubaddress(walletPtr, label)
        val newIndex = NativeWallet.getSubaddressCount(walletPtr) - 1
        return Subaddress(newIndex, NativeWallet.getSubaddress(walletPtr, newIndex), label)
    }

    fun isAddressValid(address: String): Boolean = NativeWallet.isAddressValid(address)

    sealed interface SendResult {
        data class Success(val txid: String, val feeAtomic: Long) : SendResult
        data class Failure(val message: String) : SendResult
    }

    /** Priority: 0=default, 1=low, 2=medium, 3=high (matches wallet2_api's PendingTransaction::Priority). */
    fun send(address: String, amountAtomic: Long, priority: Int = 1): SendResult {
        val raw = NativeWallet.sendTransaction(walletPtr, address, amountAtomic, priority)
        return when {
            raw.startsWith("OK:") -> {
                val parts = raw.removePrefix("OK:").split(":")
                SendResult.Success(txid = parts.getOrElse(0) { "" }, feeAtomic = parts.getOrNull(1)?.toLongOrNull() ?: 0L)
            }
            raw.startsWith("ERROR:") -> SendResult.Failure(raw.removePrefix("ERROR:"))
            else -> SendResult.Failure("unrecognized native response")
        }
    }

    // --- Backup / export -- all of this is exactly as sensitive as a
    // seed and must be gated behind re-authentication by the caller,
    // same as the create-flow's seed display; nothing here does that
    // gating itself since it's a UI-layer concern.

    /** Re-derives the seed for an already-open wallet (distinct from
     * beginCreate's one-time pendingSeed, which is cleared after commit). */
    fun currentSeed(): String = NativeWallet.getSeed(walletPtr)
    fun secretSpendKey(): String = NativeWallet.getSecretSpendKey(walletPtr)
    fun secretViewKey(): String = NativeWallet.getSecretViewKey(walletPtr)

    /** The wallet's own two on-disk files (cache + keys), for the caller to
     * share/export via a FileProvider. Both are still wallet2's own
     * password-encrypted format -- exporting them doesn't hand over
     * plaintext keys the way currentSeed()/secretSpendKey() do, but it does
     * leave the app sandbox, so still gate this the same way. */
    fun walletFiles(): List<File>? {
        val path = activePath ?: return null
        val cache = File(path)
        val keys = File("$path.keys")
        return if (cache.exists() && keys.exists()) listOf(cache, keys) else null
    }

    fun close() {
        if (walletPtr != 0L) {
            NativeWallet.closeWallet(walletPtr)
            walletPtr = 0
        }
    }

    companion object {
        /** 25-word mnemonic entropy is generated natively by wallet2; this
         * is only used for the wallet-file password field itself, which
         * wallet2 needs even though the mnemonic is the real backup. */
        fun generateRandomPassword(): String {
            val bytes = ByteArray(32)
            SecureRandom().nextBytes(bytes)
            return bytes.joinToString("") { "%02x".format(it) }
        }
    }
}
