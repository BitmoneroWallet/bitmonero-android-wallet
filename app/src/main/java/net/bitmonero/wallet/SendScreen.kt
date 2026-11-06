package net.bitmonero.wallet

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val COIN = 1_000_000_000_000.0

@Composable
fun SendScreen(session: WalletSession, onDone: () -> Unit, onBack: () -> Unit) {
    var address by remember { mutableStateOf("") }
    var amountText by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }
    var isError by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    // zxing-android-embedded launches its own scanning Activity and hands
    // the decoded text back through this contract -- no camera/decode code
    // of our own to get wrong.
    val scanLauncher = rememberLauncherForActivityResult(ScanContract()) { res ->
        res.contents?.let { address = it.trim() }
    }

    val amountAtomic = amountText.toDoubleOrNull()?.let { (it * COIN).toLong() }
    val addressLooksValid = address.isNotBlank() && runCatching { session.isAddressValid(address) }.getOrDefault(false)

    Column(modifier = Modifier.fillMaxSize().padding(24.dp)) {
        Text("Send", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(16.dp))

        OutlinedTextField(
            value = address,
            onValueChange = { address = it.trim(); result = null },
            label = { Text("Recipient address") },
            // No visualTransformation/singleLine restriction needed for
            // paste to work -- long-press paste is a platform TextField
            // behavior, not something to wire up by hand.
            modifier = Modifier.fillMaxWidth(),
            isError = address.isNotBlank() && !addressLooksValid,
            supportingText = {
                if (address.isNotBlank() && !addressLooksValid) Text("Not a valid BitMonero address")
            }
        )
        Spacer(Modifier.height(8.dp))
        OutlinedButton(
            onClick = {
                scanLauncher.launch(ScanOptions().setOrientationLocked(true).setBeepEnabled(false))
            },
            modifier = Modifier.fillMaxWidth()
        ) { Text("Scan QR code") }

        Spacer(Modifier.height(16.dp))
        OutlinedTextField(
            value = amountText,
            onValueChange = { amountText = it.filter { c -> c.isDigit() || c == '.' } },
            label = { Text("Amount (BMR)") },
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(Modifier.height(16.dp))
        result?.let {
            Text(it, color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(8.dp))
        }

        Button(
            onClick = {
                val amount = amountAtomic ?: return@Button
                sending = true
                result = null
                scope.launch {
                    val outcome = withContext(Dispatchers.Default) {
                        // createTransaction/commit both do real network I/O
                        // (fetching outputs, broadcasting) -- must not run
                        // on the main thread.
                        session.send(address, amount)
                    }
                    sending = false
                    when (outcome) {
                        is WalletSession.SendResult.Success -> {
                            isError = false
                            result = "Sent. Fee: ${"%.6f".format(outcome.feeAtomic / COIN)} BMR"
                        }
                        is WalletSession.SendResult.Failure -> {
                            isError = true
                            result = outcome.message
                        }
                    }
                }
            },
            enabled = !sending && addressLooksValid && amountAtomic != null && amountAtomic > 0,
            modifier = Modifier.fillMaxWidth()
        ) { Text(if (sending) "Sending…" else "Send") }

        Spacer(Modifier.height(8.dp))
        TextButton(onClick = onBack) { Text("Back") }
    }
}
