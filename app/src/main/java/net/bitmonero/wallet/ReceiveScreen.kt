package net.bitmonero.wallet

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp

@Composable
fun ReceiveScreen(session: WalletSession, onBack: () -> Unit) {
    val clipboard = LocalClipboardManager.current

    var subaddresses by remember { mutableStateOf(runCatching { session.listSubaddresses() }.getOrDefault(emptyList())) }
    var selected by remember { mutableStateOf(subaddresses.firstOrNull()) }
    var showList by remember { mutableStateOf(false) }

    val current = selected?.address ?: ""
    val qrBitmap = remember(current) {
        if (current.isEmpty()) null else runCatching { QrCode.generate(current) }.getOrNull()
    }

    Column(modifier = Modifier.fillMaxSize().padding(24.dp)) {
        Text("Receive", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(16.dp))

        qrBitmap?.let {
            Image(
                bitmap = it.asImageBitmap(),
                contentDescription = "Address QR code",
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
            )
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Text(current, modifier = Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall)
        }
        Spacer(Modifier.height(8.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { clipboard.setText(AnnotatedString(current)) }) { Text("Copy") }

            OutlinedButton(onClick = {
                val label = "Address ${subaddresses.size + 1}"
                val created = session.generateNewAddress(label)
                subaddresses = session.listSubaddresses()
                selected = created
            }) { Text("Generate new address") }

            TextButton(onClick = { showList = !showList }) { Text(if (showList) "Hide addresses" else "All addresses") }
        }

        if (showList) {
            Spacer(Modifier.height(12.dp))
            LazyColumn(modifier = Modifier.weight(1f)) {
                items(subaddresses.size) { i ->
                    val s = subaddresses[i]
                    Row(
                        modifier = Modifier.fillMaxWidth()
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = s.address == current, onClick = { selected = s; showList = false })
                        Column {
                            Text(if (s.label.isNotBlank()) s.label else "Address ${s.index}", style = MaterialTheme.typography.labelMedium)
                            Text(s.address.take(20) + "…", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        } else {
            Spacer(Modifier.weight(1f))
        }

        TextButton(onClick = onBack) { Text("Back") }
    }
}
