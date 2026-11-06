package net.bitmonero.wallet

import androidx.biometric.BiometricManager
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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

    val biometricSupported = remember {
        BiometricManager.from(context).canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) ==
            BiometricManager.BIOMETRIC_SUCCESS
    }

    fun refreshNodes() {
        nodes = NodeStore.list(context)
        activeNode = NodeStore.active(context)
    }

    Column(modifier = Modifier.fillMaxSize().padding(24.dp)) {
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
        Text("Nodes", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        Text(
            "The wallet connects to whichever node is selected. No third-party node is built in by default -- only your own (127.0.0.1) until you add one.",
            style = MaterialTheme.typography.bodySmall
        )
        Spacer(Modifier.height(12.dp))

        LazyColumn(modifier = Modifier.weight(1f)) {
            items(nodes.size) { i ->
                val node = nodes[i]
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        RadioButton(
                            selected = node == activeNode,
                            onClick = { NodeStore.setActive(context, node); refreshNodes() }
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
            "Changing the active node applies the next time the wallet connects (e.g. after restarting the app).",
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
