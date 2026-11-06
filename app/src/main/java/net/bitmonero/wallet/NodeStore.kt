package net.bitmonero.wallet

import android.content.Context

/**
 * Persists the list of daemon addresses the wallet can connect to, and
 * which one is active.
 *
 * Default set includes specific third-party BitMonero nodes -- a
 * deliberate, informed choice made by the user (2026-09-20), who supplied
 * these exact addresses and has been testing against them directly, not
 * something baked in unilaterally. Only one (178.128.25.34:48081) is
 * confirmed reachable as of this writing; the others are kept because
 * that reachability can change. 127.0.0.1 (the user's own node) stays
 * first in the raw default list, but DEFAULT_ACTIVE is explicitly set to
 * the confirmed-reachable node -- defaulting "active" to
 * alphabetically-first previously silently selected 127.0.0.1 with no
 * local daemon running, which was the exact cause of a real "stuck on
 * Syncing forever" bug found 2026-09-20.
 *
 * Stored in plain (non-encrypted) SharedPreferences: node addresses are
 * not secret, unlike the wallet password/keys, which never go through
 * this class.
 */
/**
 * How the wallet reaches a daemon.
 *
 *  - [REMOTE]: connect to one of the user-managed remote nodes (the
 *    original behaviour).
 *  - [FULL_NODE]: run a full BitMonero node (monerod) on this device and
 *    connect the wallet to it over loopback. This is the privacy-maximal
 *    option -- no third party learns which outputs are yours -- at the cost
 *    of bandwidth, storage and battery. Running the node requires the
 *    optional native daemon library (libbitmonero_daemon_jni.so); when it
 *    isn't bundled, [NodeConnector] falls back to simply pointing the wallet
 *    at a local daemon address, and the UI makes the missing engine clear
 *    (see [NativeDaemon.isAvailable]).
 */
enum class NodeMode { REMOTE, FULL_NODE }

object NodeStore {
    private const val PREFS = "bitmonero_nodes"
    private const val KEY_NODES = "nodes"
    private const val KEY_ACTIVE = "active"
    private const val KEY_MODE = "node_mode"
    private const val KEY_LOCAL_PORT = "local_rpc_port"
    private const val KEY_PRUNED = "local_pruned"

    /** Loopback host the wallet uses for an on-device full node. */
    const val LOCAL_NODE_HOST = "127.0.0.1"

    /**
     * Default local RPC port. Matches the :48081 BitMonero nodes in
     * [DEFAULT_NODES] so a user who already ran their own node on the
     * standard port connects without reconfiguring.
     */
    const val DEFAULT_LOCAL_RPC_PORT = 48081

    private val DEFAULT_NODES = setOf(
        "bmr.rpc.wattxchange.app:80",  // self-hosted node, Cloudflare -> restricted RPC (48083)
        "127.0.0.1:48081",             // your own node, if running locally on-device
        "178.128.25.34:48081",
        "51.38.236.156:48082",
        "136.248.244.92:48080",
        "69.42.222.20:20015"
    )

    // Default to the self-hosted, Cloudflare-fronted RPC node so a fresh
    // install connects out of the box. It is plain HTTP on :80 -- Cloudflare
    // proxies to the node's restricted RPC (48083), and the wallet permits
    // cleartext (network_security_config.xml), so no in-app TLS is needed.
    //
    // NOTE: 43.230.201.59:48080 (nestex) was removed -- that is a P2P/levin
    // port, not an HTTP RPC port, so a wallet can never sync from it.
    private const val DEFAULT_ACTIVE = "bmr.rpc.wattxchange.app:80"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun list(context: Context): List<String> {
        val stored = prefs(context).getStringSet(KEY_NODES, null)
        return if (stored.isNullOrEmpty()) DEFAULT_NODES.sorted() else stored.sorted()
    }

    fun active(context: Context): String {
        val a = prefs(context).getString(KEY_ACTIVE, null)
        val nodes = list(context)
        if (a != null && nodes.contains(a)) return a
        // Fall back to the known-reachable default rather than
        // alphabetically-first, which happened to be the useless 127.0.0.1.
        return if (nodes.contains(DEFAULT_ACTIVE)) DEFAULT_ACTIVE else nodes.first()
    }

    fun setActive(context: Context, node: String) {
        prefs(context).edit().putString(KEY_ACTIVE, node).apply()
    }

    /** Returns false without adding if [node] fails basic host:port validation. */
    fun add(context: Context, node: String): Boolean {
        val trimmed = node.trim()
        if (!isValidAddress(trimmed)) return false
        val current = prefs(context).getStringSet(KEY_NODES, null)?.toMutableSet()
            ?: DEFAULT_NODES.toMutableSet()
        current.add(trimmed)
        prefs(context).edit().putStringSet(KEY_NODES, current).apply()
        return true
    }

    fun remove(context: Context, node: String) {
        val current = prefs(context).getStringSet(KEY_NODES, null)?.toMutableSet()
            ?: DEFAULT_NODES.toMutableSet()
        current.remove(node)
        if (current.isEmpty()) current.add(DEFAULT_ACTIVE)
        prefs(context).edit().putStringSet(KEY_NODES, current).apply()
        if (active(context) == node) {
            setActive(context, if (current.contains(DEFAULT_ACTIVE)) DEFAULT_ACTIVE else current.sorted().first())
        }
    }

    private fun isValidAddress(s: String): Boolean {
        val parts = s.split(":")
        if (parts.size != 2) return false
        val port = parts[1].toIntOrNull() ?: return false
        return parts[0].isNotBlank() && port in 1..65535
    }

    // --- Node mode (remote vs. on-device full node) -------------------------

    /** Defaults to [NodeMode.REMOTE] -- existing wallets keep their behaviour. */
    fun mode(context: Context): NodeMode =
        when (prefs(context).getString(KEY_MODE, null)) {
            NodeMode.FULL_NODE.name -> NodeMode.FULL_NODE
            else -> NodeMode.REMOTE
        }

    fun setMode(context: Context, mode: NodeMode) {
        prefs(context).edit().putString(KEY_MODE, mode.name).apply()
    }

    fun localRpcPort(context: Context): Int =
        prefs(context).getInt(KEY_LOCAL_PORT, DEFAULT_LOCAL_RPC_PORT)

    /** Returns false (and stores nothing) for an out-of-range port. */
    fun setLocalRpcPort(context: Context, port: Int): Boolean {
        if (port !in 1..65535) return false
        prefs(context).edit().putInt(KEY_LOCAL_PORT, port).apply()
        return true
    }

    /**
     * Whether the on-device node prunes the blockchain. Defaults to true:
     * a pruned node needs roughly a third of the storage (tens of GB rather
     * than hundreds) and is still a full-validation node -- the only sane
     * default on a phone.
     */
    fun pruned(context: Context): Boolean =
        prefs(context).getBoolean(KEY_PRUNED, true)

    fun setPruned(context: Context, pruned: Boolean) {
        prefs(context).edit().putBoolean(KEY_PRUNED, pruned).apply()
    }

    /** host:port the wallet uses when talking to the on-device full node. */
    fun localNodeAddress(context: Context): String =
        "$LOCAL_NODE_HOST:${localRpcPort(context)}"

    /**
     * The daemon address the wallet should connect to given the current
     * mode, and whether to treat it as trusted. A local node is always
     * trusted (it's ours); remote nodes are treated as trusted here to
     * preserve the pre-existing behaviour (MainActivity already passed
     * trusted = true for the selected node).
     */
    fun resolvedDaemonAddress(context: Context): String =
        when (mode(context)) {
            NodeMode.FULL_NODE -> localNodeAddress(context)
            NodeMode.REMOTE -> active(context)
        }
}
