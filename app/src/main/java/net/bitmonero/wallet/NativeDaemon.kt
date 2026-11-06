package net.bitmonero.wallet

import android.util.Log

/**
 * JNI binding to an embedded BitMonero full node (monerod), declared 1:1
 * with bitmonero_daemon_jni.cpp.
 *
 * This is the OPTIONAL counterpart to [NativeWallet]. The wallet library
 * (libbitmonero_wallet_jni.so) is always bundled; the daemon library
 * (libbitmonero_daemon_jni.so) is a separate, much larger artifact that
 * links the full node + RandomX PoW code and is only present in builds that
 * ship the "full node" capability. See the android README for how to build
 * and bundle it.
 *
 * CRITICAL: the library is loaded LAZILY and failure is swallowed. Unlike
 * [NativeWallet] -- which does System.loadLibrary in its static initializer
 * because the app cannot function without the wallet core -- loading the
 * daemon lib must never crash the app. A build without the daemon .so is a
 * perfectly valid remote-only wallet; [isAvailable] simply returns false and
 * the UI degrades gracefully (Settings shows the full-node option as
 * unavailable rather than offering a toggle that would crash on use).
 *
 * Never call any of the `external` functions below without first checking
 * [isAvailable]; doing so when the lib failed to load throws
 * UnsatisfiedLinkError.
 */
object NativeDaemon {
    private const val TAG = "NativeDaemon"

    /**
     * Attempts the load exactly once, on first access, and caches the
     * result. A missing library (remote-only build) yields false; so does a
     * library present but missing a symbol (an incompatible daemon build),
     * which we also treat as "not available" rather than letting it blow up
     * at call time.
     */
    val isAvailable: Boolean by lazy {
        try {
            System.loadLibrary("bitmonero_daemon_jni")
            Log.i(TAG, "Embedded full-node library loaded")
            true
        } catch (e: UnsatisfiedLinkError) {
            Log.i(TAG, "Embedded full-node library not bundled in this build; remote-only")
            false
        } catch (e: Throwable) {
            Log.w(TAG, "Embedded full-node library failed to load", e)
            false
        }
    }

    /**
     * Starts monerod in-process on a background native thread.
     *
     * @param dataDir     writable directory for the blockchain DB + logs
     *                    (app-private storage, e.g. getExternalFilesDir).
     * @param rpcBindIp   usually 127.0.0.1 -- the node must NOT be reachable
     *                    off-device unless the user explicitly opts in.
     * @param rpcBindPort loopback RPC port the wallet connects to.
     * @param pruned      run a pruned node (far smaller on-disk footprint).
     * @param testnet     select the test network.
     * @return true if the node began starting; false if it was already
     *         running or start was rejected. Being "started" does not mean
     *         "synced" -- poll [targetHeight]/[height].
     */
    external fun nativeStart(
        dataDir: String,
        rpcBindIp: String,
        rpcBindPort: Int,
        pruned: Boolean,
        testnet: Boolean
    ): Boolean

    /** Requests a clean shutdown and blocks until the node has stopped. */
    external fun nativeStop()

    external fun nativeIsRunning(): Boolean

    /** Current locally-validated blockchain height, or 0 if not started. */
    external fun nativeHeight(): Long

    /** Best height seen from the P2P network, or 0 if unknown yet. */
    external fun nativeTargetHeight(): Long

    /** One-line human-readable status ("Starting", "Syncing 1234/5678", ...). */
    external fun nativeStatus(): String
}
