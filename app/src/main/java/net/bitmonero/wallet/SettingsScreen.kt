package net.bitmonero.wallet

import androidx.biometric.BiometricManager
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

@Composable
fun SettingsScreen(session: WalletSession, onBack: () -> Unit, onBackup: () -> Unit) {
    val context = LocalContext.current
    var lockEnabled by remember { mutableStateOf(AppLock.isEnabled(context)) }
    var lockType by remember { mutableStateOf(AppLock.lockType(context)) }
    var biometricEnabled by remember { mutableStateOf(AppLock.isBiometricEnabled(context)) }
    var showSetCredential by remember { mutableStateOf<AppLock.LockType?>(null) }
    var nodes by remember { mutableStateOf(NodeStore.list(context)) }
    var activeNode by remember { mutableStateOf(NodeStore.active(context)) }
    var newNode by remember { mutableStateOf("") }
    var nodeError by remember { mutableStateOf<String?>(null) }
    var rescanRequested by remember { mutableStateOf(false) }
    var nodeMode by remember { mutableStateOf(NodeStore.mode(context)) }
    var localPort by remember { mutableStateOf(NodeStore.localRpcPort(context).toString()) }
    var pruned by remember { mutableStateOf(NodeStore.pruned(context)) }
    var nodeStatus by remember { mutableStateOf(FullNodeController.status(context)) }
    val fullNodeSupported = remember { FullNodeController.isSupported }

    // While the on-device node is selected, poll its sync progress for the
    // status line below.
    LaunchedEffect(nodeMode) {
        while (nodeMode == NodeMode.FULL_NODE && fullNodeSupported) {
            nodeStatus = FullNodeController.status(context)
            kotlinx.coroutines.delay(2000)
        }
    }

    val biometricSupported = remember {
        BiometricManager.from(context).canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) ==
            BiometricManager.BIOMETRIC_SUCCESS
    }

    fun refreshNodes() {
        nodes = NodeStore.list(context)
        activeNode = NodeStore.active(context)
    }

    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp)) {
        Text("Settings", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(24.dp))

        Text("App lock", style = MaterialTheme.typography.titleMedium)
        if (!lockEnabled) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { showSetCredential = AppLock.LockType.PIN }) { Text("Set PIN") }
                OutlinedButton(onClick = { showSetCredential = AppLock.LockType.PASSWORD }) { Text("Set password") }
            }
        } else {
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text("Locked with ${if (lockType == AppLock.LockType.PIN) "PIN" else "password"}")
                Spacer(Modifier.width(12.dp))
                TextButton(onClick = {
                    AppLock.disable(context)
                    lockEnabled = false
                    lockType = null
                    biometricEnabled = false
                }) { Text("Turn off") }
            }
        }

        showSetCredential?.let { type ->
            SetCredentialDialog(
                type = type,
                onConfirm = { value ->
                    AppLock.setCredential(context, type, value)
                    lockEnabled = true
                    lockType = type
                    showSetCredential = null
                },
                onCancel = { showSetCredential = null }
            )
        }

        if (lockEnabled && biometricSupported) {
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Switch(
                    checked = biometricEnabled,
                    onCheckedChange = {
                        biometricEnabled = it
                        AppLock.setBiometricEnabled(context, it)
                    }
                )
                Spacer(Modifier.width(8.dp))
                Text("Also allow biometric unlock")
            }
        }

        Spacer(Modifier.height(32.dp))
        Spacer(Modifier.height(32.dp))
        Text("Backup", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        Text(
            "View your seed phrase and private keys again, or export your wallet files.",
            style = MaterialTheme.typography.bodySmall
        )
        Spacer(Modifier.height(8.dp))
        Button(onClick = onBackup) { Text("Backup wallet") }

        Spacer(Modifier.height(32.dp))
        Text("Sync", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        Text(
            "A wallet only scans blocks from the point it was created onward. If a payment to one of your addresses was mined before that, it won't show up until you rescan from the beginning.",
            style = MaterialTheme.typography.bodySmall
        )
        Spacer(Modifier.height(8.dp))
        Button(onClick = {
            runCatching { session.rescanFromGenesis() }
            rescanRequested = true
        }) { Text("Rescan blockchain from genesis") }
        if (rescanRequested) {
            Spacer(Modifier.height(4.dp))
            Text("Rescan started -- this can take a while; watch the sync progress on the home screen.", style = MaterialTheme.typography.bodySmall)
        }

        Spacer(Modifier.height(32.dp))
        Text("Node mode", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        Text(
            "Choose how the wallet reaches the network. A remote node is fast and light but learns which transactions you query. Running a full node on this device is the most private option -- nothing about your wallet leaves the phone -- but it downloads and validates the blockchain (tens of GB, significant battery and data).",
            style = MaterialTheme.typography.bodySmall
        )
        Spacer(Modifier.height(8.dp))

        fun selectMode(mode: NodeMode) {
            if (mode == nodeMode) return
            nodeMode = mode
            NodeStore.setMode(context, mode)
            runCatching { NodeConnector.applyModeChange(context, session) }
            nodeStatus = FullNodeController.status(context)
        }

        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            RadioButton(selected = nodeMode == NodeMode.REMOTE, onClick = { selectMode(NodeMode.REMOTE) })
            Text("Remote node")
        }
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            RadioButton(selected = nodeMode == NodeMode.FULL_NODE, onClick = { selectMode(NodeMode.FULL_NODE) })
            Column {
                Text("Run a full node on this device")
                Text(
                    "Maximum privacy. Uses storage, battery and data.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }

        if (nodeMode == NodeMode.FULL_NODE) {
            Spacer(Modifier.height(8.dp))
            if (!fullNodeSupported) {
                Text(
                    "This build does not include the full-node engine, so no node will actually run. The wallet will try to reach a node on 127.0.0.1:${localPort} -- it stays disconnected unless you run one yourself. Install a build that bundles the node engine to use this mode fully.",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall
                )
            } else {
                val st = nodeStatus
                val line = when {
                    !st.running -> "Node stopped"
                    st.targetHeight > 0L && st.height < st.targetHeight ->
                        "Syncing: ${st.height} / ${st.targetHeight}"
                    st.running -> "Node running -- height ${st.height}"
                    else -> st.message
                }
                Text(line, style = MaterialTheme.typography.bodySmall)
            }

            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Switch(
                    checked = pruned,
                    onCheckedChange = {
                        pruned = it
                        NodeStore.setPruned(context, it)
                    }
                )
                Spacer(Modifier.width(8.dp))
                Column {
                    Text("Pruned blockchain")
                    Text(
                        "Keeps about a third of the data and still fully validates. Recommended on a phone. Takes effect next time the node starts.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }

            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = localPort,
                onValueChange = { localPort = it.filter(Char::isDigit).take(5) },
                label = { Text("Local RPC port") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(4.dp))
            TextButton(onClick = {
                val p = localPort.toIntOrNull()
                if (p != null && NodeStore.setLocalRpcPort(context, p)) {
                    nodeError = null
                    runCatching { NodeConnector.applyModeChange(context, session) }
                } else {
                    nodeError = "Port must be 1-65535"
                }
            }) { Text("Apply port") }
        }

        Spacer(Modifier.height(32.dp))
        Text("Remote nodes", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        Text(
            "Used in Remote node mode: the wallet connects to whichever node is selected. A few public BitMonero nodes are listed to start with, but public nodes come and go -- if none connect, add your own node below (host:port).",
            style = MaterialTheme.typography.bodySmall
        )
        Spacer(Modifier.height(12.dp))

        // Plain Column (not LazyColumn): the whole screen is already in a
        // verticalScroll, and nesting a lazy vertical list inside it throws
        // "infinite height" constraints. The node list is short, so a simple
        // forEach is correct here -- and keeps the Add-node field below it
        // reachable (the old weight(1f) LazyColumn pushed it off-screen, which
        // is why the screen "wouldn't scroll").
        nodes.forEach { node ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    RadioButton(
                        selected = node == activeNode,
                        onClick = {
                            NodeStore.setActive(context, node)
                            refreshNodes()
                            // Reconnect immediately rather than only on the
                            // next app launch -- "I picked a node and nothing
                            // happened" was a real complaint.
                            if (nodeMode == NodeMode.REMOTE) {
                                runCatching { NodeConnector.connect(context, session) }
                            }
                        }
                    )
                    Text(node)
                }
                if (nodes.size > 1) {
                    TextButton(onClick = { NodeStore.remove(context, node); refreshNodes() }) {
                        Text("Remove")
                    }
                }
            }
        }

        Spacer(Modifier.height(8.dp))
        nodeError?.let {
            Text(it, color = MaterialTheme.colorScheme.error)
            Spacer(Modifier.height(4.dp))
        }
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            OutlinedTextField(
                value = newNode,
                onValueChange = { newNode = it; nodeError = null },
                modifier = Modifier.weight(1f),
                singleLine = true,
                label = { Text("host:port") }
            )
            Spacer(Modifier.width(8.dp))
            Button(onClick = {
                if (NodeStore.add(context, newNode)) {
                    newNode = ""
                    refreshNodes()
                } else {
                    nodeError = "Enter as host:port, e.g. 203.0.113.5:48081"
                }
            }) { Text("Add") }
        }

        Spacer(Modifier.height(16.dp))
        Text(
            "Selecting a node reconnects the wallet right away. If it stays on \"Not connected\", that node is unreachable -- try another.",
            style = MaterialTheme.typography.bodySmall
        )

        Spacer(Modifier.height(16.dp))
        TextButton(onClick = onBack) { Text("Back") }
    }
}

@Composable
private fun SetCredentialDialog(type: AppLock.LockType, onConfirm: (String) -> Unit, onCancel: () -> Unit) {
    var value by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    val isPin = type == AppLock.LockType.PIN
    val minLength = if (isPin) 4 else 4
    val mismatch = confirm.isNotEmpty() && value != confirm
    val valid = value.length >= minLength && value == confirm && (!isPin || value.all(Char::isDigit))

    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(if (isPin) "Set a PIN" else "Set a password") },
        text = {
            Column {
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = if (isPin) it.filter(Char::isDigit) else it },
                    label = { Text(if (isPin) "PIN (min 4 digits)" else "Password (min 4 chars)") },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = if (isPin) KeyboardType.NumberPassword else KeyboardType.Password),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = confirm,
                    onValueChange = { confirm = if (isPin) it.filter(Char::isDigit) else it },
                    label = { Text("Confirm") },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = if (isPin) KeyboardType.NumberPassword else KeyboardType.Password),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                if (mismatch) {
                    Spacer(Modifier.height(4.dp))
                    Text("Doesn't match", color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(value) }, enabled = valid) { Text("Set") }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } }
    )
}
