package net.bitmonero.wallet

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log

/**
 * Keeps the embedded full node ([NativeDaemon]) alive while the app is
 * backgrounded. An initial blockchain sync takes hours; without a foreground
 * service Android would reclaim the process and the sync would never
 * progress. The persistent notification is both a requirement of
 * foreground-service APIs and honest UX: the user can see, and stop, a
 * process that is using their bandwidth and battery.
 *
 * The service only ever runs when this build bundles the daemon library;
 * [FullNodeController.ensureRunning] gates that before starting it.
 */
class FullNodeService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null
    @Volatile private var started = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopNode()
                stopSelf()
                return START_NOT_STICKY
            }
            else -> startNode()
        }
        // START_STICKY: if the OS kills us under memory pressure, restart so
        // the node resumes syncing when resources free up.
        return START_STICKY
    }

    private fun startNode() {
        startForeground(NOTIFICATION_ID, buildNotification("Starting full node…"))
        if (started) return
        started = true

        acquireWakeLock()

        // Start on a dedicated thread: nativeStart blocks until the node has
        // spun up its own threads, and we must not block the main thread.
        Thread({
            val dir = FullNodeController.dataDir(this).absolutePath
            val port = NodeStore.localRpcPort(this)
            val pruned = NodeStore.pruned(this)
            Log.i(TAG, "Starting node: dir=$dir port=$port pruned=$pruned")
            val ok = runCatching {
                NativeDaemon.nativeStart(
                    dataDir = dir,
                    rpcBindIp = NodeStore.LOCAL_NODE_HOST,
                    rpcBindPort = port,
                    pruned = pruned,
                    testnet = false
                )
            }.getOrElse { e ->
                Log.e(TAG, "nativeStart threw", e); false
            }
            if (!ok) {
                Log.e(TAG, "Node failed to start; stopping service")
                started = false
                releaseWakeLock()
                stopSelf()
            }
        }, "bitmonero-node").start()
    }

    private fun stopNode() {
        if (started) {
            started = false
            runCatching { NativeDaemon.nativeStop() }.onFailure { Log.w(TAG, "nativeStop threw", it) }
        }
        releaseWakeLock()
    }

    override fun onDestroy() {
        stopNode()
        super.onDestroy()
    }

    private fun acquireWakeLock() {
        if (wakeLock != null) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "bitmonero:node").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    private fun buildNotification(text: String): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val mgr = getSystemService(NotificationManager::class.java)
            if (mgr.getNotificationChannel(CHANNEL_ID) == null) {
                mgr.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "Full node", NotificationManager.IMPORTANCE_LOW).apply {
                        description = "Running an on-device BitMonero node"
                        setShowBadge(false)
                    }
                )
            }
        }
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION") Notification.Builder(this)
        }
        return builder
            .setContentTitle("BitMonero full node")
            .setContentText(text)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val TAG = "FullNodeService"
        private const val CHANNEL_ID = "bitmonero_full_node"
        private const val NOTIFICATION_ID = 0xB17
        const val ACTION_START = "net.bitmonero.wallet.action.START_NODE"
        const val ACTION_STOP = "net.bitmonero.wallet.action.STOP_NODE"
    }
}
