package net.bitmonero.wallet

import android.content.Intent
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

/**
 * Everything on this screen -- seed, spend key, view key, the raw wallet
 * files -- is exactly as sensitive as the initial seed-creation flow.
 * Gated behind re-authentication (whatever AppLock is already configured,
 * or an explicit warning if none is) before any of it renders, same
 * standard the rest of the app holds itself to.
 */
@Composable
fun BackupScreen(session: WalletSession, activity: FragmentActivity, onBack: () -> Unit) {
    var authenticated by remember { mutableStateOf(false) }

    if (!authenticated) {
        ReauthGate(activity = activity, onAuthenticated = { authenticated = true }, onBack = onBack)
    } else {
        BackupContent(session = session, onBack = onBack)
    }
}

@Composable
private fun ReauthGate(activity: FragmentActivity, onAuthenticated: () -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    val lockConfigured = AppLock.isEnabled(context)
    val lockType = AppLock.lockType(context)
    val isPin = lockType == AppLock.LockType.PIN
    var attempt by remember { mutableStateOf("") }
    var error by remember { mutableStateOf(false) }
    var confirmedNoLock by remember { mutableStateOf(false) }

    val biometricAvailable = remember {
        lockConfigured && AppLock.isBiometricEnabled(context) &&
            BiometricManager.from(context).canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) ==
            BiometricManager.BIOMETRIC_SUCCESS
    }

    fun promptBiometric() {
        val prompt = BiometricPrompt(activity, ContextCompat.getMainExecutor(context),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    onAuthenticated()
                }
            })
        prompt.authenticate(
            BiometricPrompt.PromptInfo.Builder()
                .setTitle("Confirm it's you")
                .setNegativeButtonText(if (isPin) "Use PIN" else "Use password")
                .build()
        )
    }

    Column(modifier = Modifier.fillMaxSize().padding(24.dp)) {
        Text("Confirm your identity", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        Text("This screen shows your seed and private keys -- anyone who sees them can spend your funds.")
        Spacer(Modifier.height(24.dp))

        if (lockConfigured) {
            OutlinedTextField(
                value = attempt,
                onValueChange = { attempt = if (isPin) it.filter(Char::isDigit) else it; error = false },
                label = { Text(if (isPin) "PIN" else "Password") },
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = if (isPin) KeyboardType.NumberPassword else KeyboardType.Password),
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            if (error) {
                Spacer(Modifier.height(8.dp))
                Text("Wrong ${if (isPin) "PIN" else "password"}", color = MaterialTheme.colorScheme.error)
            }
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = { if (AppLock.verifyCredential(context, attempt)) onAuthenticated() else error = true },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Confirm") }
            if (biometricAvailable) {
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = { promptBiometric() }) { Text("Use biometric instead") }
            }
        } else {
            // No app lock set up -- can't re-verify a credential that
            // doesn't exist, so require an explicit, unskippable
            // acknowledgement instead of silently showing secrets.
            Text(
                "You haven't set up a PIN or password, so this can't verify it's really you. Anyone holding this unlocked phone can see this screen.",
                color = MaterialTheme.colorScheme.error
            )
            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = confirmedNoLock, onCheckedChange = { confirmedNoLock = it })
                Text("I understand and want to continue")
            }
            Spacer(Modifier.height(16.dp))
            Button(onClick = onAuthenticated, enabled = confirmedNoLock, modifier = Modifier.fillMaxWidth()) {
                Text("Continue")
            }
        }

        Spacer(Modifier.height(16.dp))
        TextButton(onClick = onBack) { Text("Cancel") }
    }
}

@Composable
private fun BackupContent(session: WalletSession, onBack: () -> Unit) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var revealed by remember { mutableStateOf<String?>(null) } // "seed" | "spend" | "view" | null
    var exportError by remember { mutableStateOf<String?>(null) }

    fun copy(label: String, value: String) {
        clipboard.setText(AnnotatedString(value))
    }

    Column(modifier = Modifier.fillMaxSize().padding(24.dp)) {
        Text("Backup", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        Text(
            "Anyone with your seed phrase or spend key has full control of this wallet -- treat them like cash, never a screenshot or cloud note.",
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall
        )
        Spacer(Modifier.height(24.dp))

        SecretRow(
            label = "Seed phrase (25 words)",
            revealed = revealed == "seed",
            onToggle = { revealed = if (revealed == "seed") null else "seed" },
            value = { session.currentSeed() },
            onCopy = { copy("seed", session.currentSeed()) }
        )
        Spacer(Modifier.height(16.dp))
        SecretRow(
            label = "Secret spend key",
            revealed = revealed == "spend",
            onToggle = { revealed = if (revealed == "spend") null else "spend" },
            value = { session.secretSpendKey() },
            onCopy = { copy("spend key", session.secretSpendKey()) }
        )
        Spacer(Modifier.height(16.dp))
        SecretRow(
            label = "Secret view key",
            revealed = revealed == "view",
            onToggle = { revealed = if (revealed == "view") null else "view" },
            value = { session.secretViewKey() },
            onCopy = { copy("view key", session.secretViewKey()) }
        )

        Spacer(Modifier.height(32.dp))
        Text("Wallet files", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        Text(
            "These stay password-encrypted with wallet2's own encryption, but once shared they leave the app's protected storage -- only send them somewhere you control.",
            style = MaterialTheme.typography.bodySmall
        )
        Spacer(Modifier.height(8.dp))
        Button(onClick = {
            val files = session.walletFiles()
            if (files == null) {
                exportError = "Wallet files not found"
                return@Button
            }
            val uris = files.map { f ->
                FileProvider.getUriForFile(context, "net.bitmonero.wallet.fileprovider", f)
            }
            val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = "application/octet-stream"
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(intent, "Back up wallet files"))
        }) { Text("Export wallet files") }
        exportError?.let {
            Spacer(Modifier.height(4.dp))
            Text(it, color = MaterialTheme.colorScheme.error)
        }

        Spacer(Modifier.height(24.dp))
        TextButton(onClick = onBack) { Text("Done") }
    }
}

@Composable
private fun SecretRow(label: String, revealed: Boolean, onToggle: () -> Unit, value: () -> String, onCopy: () -> Unit) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(label, style = MaterialTheme.typography.titleSmall)
            TextButton(onClick = onToggle) { Text(if (revealed) "Hide" else "Show") }
        }
        if (revealed) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Text(value(), modifier = Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall)
            }
            TextButton(onClick = onCopy) { Text("Copy") }
        }
    }
}
