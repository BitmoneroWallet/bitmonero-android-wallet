package net.bitmonero.wallet

import android.content.Context

/**
 * Single place that turns the user's node preference ([NodeStore.mode]) into
 * an actual wallet connection. MainActivity used to call
 * `session.connect(NodeStore.active(ctx), trusted = true)` in three spots;
 * routing all three through here means full-node mode (start the on-device
 * node, connect over loopback) and remote mode behave consistently and the
 * mode logic lives in exactly one function.
 */
object NodeConnector {

    /**
     * Connects [session] using the current node preference. In full-node
     * mode this also asks [FullNodeController] to start the on-device node
     * (a no-op in a remote-only build, where it just points the wallet at
     * the local RPC port -- the home screen will honestly report "not
     * connected" until a node actually runs there).
     *
     * [session] must already have an open wallet.
     */
    fun connect(context: Context, session: WalletSession) {
        if (NodeStore.mode(context) == NodeMode.FULL_NODE) {
            FullNodeController.ensureRunning(context)
        }
        // A local full node is ours and therefore trusted; remote nodes keep
        // the trusted = true the app already used for the selected node.
        session.connect(NodeStore.resolvedDaemonAddress(context), trusted = true)
    }

    /**
     * Applies a mode change the user just made in Settings to the live
     * wallet: (re)connect to the right daemon, and stop the on-device node
     * when leaving full-node mode so it isn't left syncing in the
     * background.
     */
    fun applyModeChange(context: Context, session: WalletSession) {
        if (NodeStore.mode(context) != NodeMode.FULL_NODE) {
            FullNodeController.stop(context)
        }
        connect(context, session)
    }
}
