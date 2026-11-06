package net.bitmonero.wallet

import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import java.io.File

/**
 * High-level control of the on-device full node. Owns the decisions the raw
 * [NativeDaemon] binding shouldn't: where the blockchain lives, whether the
 * capability exists at all, and starting/stopping the node via a foreground
 * [FullNodeService] so Android doesn't kill it the moment the app is
 * backgrounded (a node that only runs while the UI is foregrounded would
 * never finish an initial sync).
 *
 * Everything here is a no-op-with-a-log when [NativeDaemon.isAvailable] is
 * false, so callers never need to special-case a remote-only build.
 */
object FullNodeController {
    private const val TAG = "FullNodeController"

    /** True only when this build actually bundles the node engine. */
    val isSupported: Boolean get() = NativeDaemon.isAvailable

    /**
     * Blockchain + node data directory. Deliberately under
     * getExternalFilesDir (still app-private on modern Android, auto-removed
     * on uninstall) rather than filesDir: a pruned mainnet chain is tens of
     * GB and internal storage is often the smaller partition. Falls back to
     * internal storage if external is unavailable.
     */
    fun dataDir(context: Context): File {
        val base = context.getExternalFilesDir(null) ?: context.filesDir
        return File(base, "node").apply { mkdirs() }
    }

    /**
     * Ensures the node is running if the user has chosen full-node mode and
     * this build supports it. Safe to call on every wallet connect.
     *
     * @return true if a node is (being) started; false if full-node mode is
     *         off, unsupported in this build, or already running is handled
     *         internally.
     */
    fun ensureRunning(context: Context): Boolean {
        if (NodeStore.mode(context) != NodeMode.FULL_NODE) return false
        if (!isSupported) {
            Log.w(TAG, "Full-node mode selected but daemon library isn't bundled; " +
                "wallet will try the local RPC port and show 'not connected' until a node runs")
            return false
        }
        val svc = Intent(context, FullNodeService::class.java).apply {
            action = FullNodeService.ACTION_START
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(svc)
        } else {
            context.startService(svc)
        }
        return true
    }

    /** Stops the node (e.g. the user switched back to a remote node). */
    fun stop(context: Context) {
        if (!isSupported) return
        context.startService(Intent(context, FullNodeService::class.java).apply {
            action = FullNodeService.ACTION_STOP
        })
    }

    /** Snapshot of node progress for the UI; all zero/empty when not running. */
    data class Status(
        val supported: Boolean,
        val running: Boolean,
        val height: Long,
        val targetHeight: Long,
        val message: String
    )

    fun status(context: Context): Status {
        if (!isSupported) {
            return Status(supported = false, running = false, height = 0, targetHeight = 0,
                message = "Full-node engine not included in this build")
        }
        val running = runCatching { NativeDaemon.nativeIsRunning() }.getOrDefault(false)
        return Status(
            supported = true,
            running = running,
            height = runCatching { NativeDaemon.nativeHeight() }.getOrDefault(0L),
            targetHeight = runCatching { NativeDaemon.nativeTargetHeight() }.getOrDefault(0L),
            message = runCatching { NativeDaemon.nativeStatus() }.getOrDefault(if (running) "Running" else "Stopped")
        )
    }
}
