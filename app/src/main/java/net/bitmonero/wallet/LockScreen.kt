package net.bitmonero.wallet

import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

@Composable
fun LockScreen(activity: FragmentActivity, onUnlocked: () -> Unit) {
    val context = LocalContext.current
    var attempt by remember { mutableStateOf("") }
    var error by remember { mutableStateOf(false) }
    val lockType = remember { AppLock.lockType(context) }
    val isPin = lockType == AppLock.LockType.PIN
    val biometricAvailable = remember {
        AppLock.isBiometricEnabled(context) &&
            BiometricManager.from(context).canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) ==
            BiometricManager.BIOMETRIC_SUCCESS
    }

    fun promptBiometric() {
        val executor = ContextCompat.getMainExecutor(context)
        val prompt = BiometricPrompt(activity, executor, object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                onUnlocked()
            }
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                // User can still fall back to the passphrase field below;
                // this isn't a hard failure state.
            }
        })
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Unlock BitMonero Wallet")
            .setNegativeButtonText("Use passphrase")
            .build()
        prompt.authenticate(info)
    }

    LaunchedEffect(Unit) {
        if (biometricAvailable) promptBiometric()
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("Locked", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(24.dp))
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
            Text(if (isPin) "Wrong PIN" else "Wrong password", color = MaterialTheme.colorScheme.error)
        }
        Spacer(Modifier.height(16.dp))
        Button(
            onClick = {
                if (AppLock.verifyCredential(context, attempt)) {
                    onUnlocked()
                } else {
                    error = true
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) { Text("Unlock") }

        if (biometricAvailable) {
            Spacer(Modifier.height(12.dp))
            TextButton(onClick = { promptBiometric() }) { Text("Use biometric instead") }
        }
    }
}
