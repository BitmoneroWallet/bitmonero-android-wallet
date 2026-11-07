package net.bitmonero.wallet

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.fragment.app.FragmentActivity
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val WALLET_NAME = "primary"

sealed interface Screen {
    data object Locked : Screen
    data object Welcome : Screen
    data class ShowSeed(val seed: String) : Screen
    data class ConfirmSeed(val seed: String) : Screen
    data class RestoreEntry(val error: String? = null) : Screen
    /** The signed-in app shell with bottom tabs: 0=Wallet, 1=Send, 2=Receive, 3=Settings. */
    data class Main(val tab: Int = 0) : Screen
    data object Backup : Screen
    data class Error(val message: String) : Screen
}

class MainActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val session = WalletSession(applicationContext)

        // Opens the existing wallet with its stored password and connects
        // it, or explains why it can't (rather than letting Home reach
        // wallet2 through a never-opened, null Wallet* -- exactly the bug
        // that shipped tonight: hasWallet()==true routed straight to Home,
        // nothing had called open(), and every native call crashed).
        fun openExistingWalletOrError(): Screen {
            val password = WalletCredentialStore.getPassword(this, WALLET_NAME)
                ?: return Screen.Error("This wallet's password wasn't found on this device, so it can't be reopened. Restore it from its 25-word seed instead.")
            return session.open(WALLET_NAME, password).fold(
                onSuccess = {
                    NodeConnector.connect(this, session)
                    Screen.Main()
                },
                onFailure = { e -> Screen.Error(e.message ?: "failed to open wallet") }
            )
        }

        setContent {
            BitMoneroTheme {
                // Locks on cold start whenever a lock is configured. Does NOT
                // re-lock on resume-from-background yet -- a real release
                // needs an onPause/onStop hook for that; noted rather than
                // silently only covering the cold-start case.
                var screen by remember {
                    mutableStateOf<Screen>(
                        when {
                            AppLock.isEnabled(this@MainActivity) -> Screen.Locked
                            session.hasWallet(WALLET_NAME) -> openExistingWalletOrError()
                            else -> Screen.Welcome
                        }
                    )
                }
                var pendingCreatePassword by remember { mutableStateOf("") }
                val scope = rememberCoroutineScope()

                Surface(modifier = Modifier.fillMaxSize()) {
                    when (val s = screen) {
                        is Screen.Locked -> LockScreen(
                            activity = this@MainActivity,
                            onUnlocked = {
                                screen = if (session.hasWallet(WALLET_NAME)) openExistingWalletOrError() else Screen.Welcome
                            }
                        )

                        is Screen.Welcome -> WelcomeScreen(
                            onCreate = {
                                val password = WalletSession.generateRandomPassword()
                                pendingCreatePassword = password
                                session.beginCreate(WALLET_NAME, password).fold(
                                    onSuccess = { seed -> screen = Screen.ShowSeed(seed) },
                                    onFailure = { e -> screen = Screen.Error(e.message ?: "create failed") }
                                )
                            },
                            onRestore = { screen = Screen.RestoreEntry() }
                        )

                        is Screen.ShowSeed -> ShowSeedScreen(
                            seed = s.seed,
                            onConfirmed = { screen = Screen.ConfirmSeed(s.seed) },
                            onBack = {
                                session.discardPending()
                                screen = Screen.Welcome
                            }
                        )

                        is Screen.ConfirmSeed -> ConfirmSeedScreen(
                            expectedSeed = s.seed,
                            onVerified = {
                                session.confirmSeedRecordedAndPersist().fold(
                                    onSuccess = {
                                        WalletCredentialStore.savePassword(this@MainActivity, WALLET_NAME, pendingCreatePassword)
                                        NodeConnector.connect(this@MainActivity, session)
                                        screen = Screen.Main()
                                    },
                                    onFailure = { e -> screen = Screen.Error(e.message ?: "commit failed") }
                                )
                            },
                            onBack = { screen = Screen.ShowSeed(s.seed) }
                        )

                        is Screen.RestoreEntry -> RestoreScreen(
                            error = s.error,
                            onRestore = { seed, password, height ->
                                scope.launch {
                                    val result = withContext(Dispatchers.Default) {
                                        session.restore(WALLET_NAME, password, seed, height)
                                    }
                                    result.fold(
                                        onSuccess = {
                                            WalletCredentialStore.savePassword(this@MainActivity, WALLET_NAME, password)
                                            NodeConnector.connect(this@MainActivity, session)
                                            screen = Screen.Main()
                                        },
                                        onFailure = { e -> screen = Screen.RestoreEntry(e.message) }
                                    )
                                }
                            },
                            onBack = { screen = Screen.Welcome }
                        )

                        is Screen.Main -> MainScaffold(
                            session = session,
                            startTab = s.tab,
                            onBackup = { screen = Screen.Backup }
                        )

                        is Screen.Backup -> BackupScreen(
                            session = session,
                            activity = this@MainActivity,
                            onBack = { screen = Screen.Main(3) }
                        )

                        is Screen.Error -> ErrorScreen(s.message) { screen = Screen.Welcome }
                    }
                }
            }
        }
    }
}

@Composable
fun WelcomeScreen(onCreate: () -> Unit, onRestore: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Image(
            painter = painterResource(R.mipmap.ic_launcher_foreground),
            contentDescription = null,
            modifier = Modifier.size(120.dp)
        )
        Text("BitMonero", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
        Text("Private. Yours.", style = MaterialTheme.typography.bodyMedium, color = Brand.textDim)
        Spacer(Modifier.height(40.dp))
        Button(onClick = onCreate, modifier = Modifier.fillMaxWidth()) { Text("Create new wallet") }
        Spacer(Modifier.height(12.dp))
        OutlinedButton(onClick = onRestore, modifier = Modifier.fillMaxWidth()) { Text("Restore from seed") }
    }
}

@Composable
fun ShowSeedScreen(seed: String, onConfirmed: () -> Unit, onBack: () -> Unit) {
    Column(modifier = Modifier.fillMaxSize().padding(24.dp)) {
        Text("Write down these 25 words", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        Text("This is the only way to recover this wallet. It is not stored anywhere else and cannot be shown again after this step.")
        Spacer(Modifier.height(16.dp))
        Card(modifier = Modifier.fillMaxWidth()) {
            Text(seed, modifier = Modifier.padding(16.dp))
        }
        Spacer(Modifier.height(24.dp))
        Button(onClick = onConfirmed, modifier = Modifier.fillMaxWidth()) { Text("I've written it down") }
        TextButton(onClick = onBack) { Text("Cancel") }
    }
}

@Composable
fun ConfirmSeedScreen(expectedSeed: String, onVerified: () -> Unit, onBack: () -> Unit) {
    val expectedWords = remember(expectedSeed) { expectedSeed.trim().split(Regex("\\s+")) }
    var mismatch by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize().padding(24.dp)) {
        Text("Re-enter your seed to confirm", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        Text("Pick each word from the list as you type it -- this is the same seed you just wrote down.")
        if (mismatch) {
            Spacer(Modifier.height(8.dp))
            Text("That word doesn't match what you wrote down. Check your notes and try again.",
                color = MaterialTheme.colorScheme.error)
        }
        Spacer(Modifier.height(16.dp))
        SeedWordPicker(
            totalWords = expectedWords.size,
            modifier = Modifier.weight(1f),
            // Reject a wrong word immediately rather than only at the end --
            // catches a transcription mistake at the word it happened on,
            // not 20 words later.
            validateNext = { index, word -> word == expectedWords.getOrNull(index) },
            onInvalidWord = { mismatch = true },
            onWordAccepted = { mismatch = false },
            onComplete = { onVerified() }
        )
        TextButton(onClick = onBack) { Text("Back") }
    }
}

/**
 * Word-by-word seed entry: as the user types letters, narrows to Monero
 * wordlist entries starting with what's typed so far, tap to accept. Built
 * for BitMonero specifically (not carried over from the iXcoin wallet,
 * which never had a 25-word Electrum-style seed to enter in the first
 * place -- Bitcoin/BIP39 seed entry there worked differently).
 */
@Composable
fun SeedWordPicker(
    totalWords: Int,
    modifier: Modifier = Modifier,
    validateNext: ((index: Int, word: String) -> Boolean)? = null,
    onInvalidWord: (() -> Unit)? = null,
    onWordAccepted: (() -> Unit)? = null,
    onComplete: (words: List<String>) -> Unit
) {
    val context = LocalContext.current
    var confirmed by remember { mutableStateOf(listOf<String>()) }
    var input by remember { mutableStateOf("") }
    val candidates = remember(input) {
        if (input.isEmpty()) emptyList() else SeedWordlist.matching(context, input).take(8)
    }

    fun accept(word: String) {
        if (validateNext != null && validateNext(confirmed.size, word) == false) {
            onInvalidWord?.invoke()
            input = ""
            return
        }
        onWordAccepted?.invoke()
        val next = confirmed + word
        confirmed = next
        input = ""
        if (next.size == totalWords) onComplete(next)
    }

    Column(modifier = modifier.fillMaxWidth()) {
        Text("${confirmed.size} of $totalWords words", style = MaterialTheme.typography.labelMedium)
        Spacer(Modifier.height(8.dp))

        if (confirmed.isNotEmpty()) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(confirmed.size) { i ->
                    AssistChip(onClick = {}, enabled = false, label = { Text("${i + 1}. ${confirmed[i]}") })
                }
            }
            Spacer(Modifier.height(12.dp))
        }

        if (confirmed.size < totalWords) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it.lowercase().filter(Char::isLetter) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    capitalization = KeyboardCapitalization.None
                ),
                label = { Text("Word ${confirmed.size + 1}") },
                // Exact single match + user hits done/space also accepts,
                // so typing the whole word still works without a tap.
                trailingIcon = if (candidates.size == 1 && candidates[0] == input) {
                    { TextButton(onClick = { accept(candidates[0]) }) { Text("Add") } }
                } else null
            )
            Spacer(Modifier.height(8.dp))
            FlowRowCandidates(candidates = candidates, onPick = ::accept)
        } else {
            TextButton(onClick = {
                confirmed = confirmed.dropLast(1)
            }) { Text("Remove last word") }
        }
    }
}

@Composable
private fun FlowRowCandidates(candidates: List<String>, onPick: (String) -> Unit) {
    // Simple wrapping row without pulling in the experimental FlowRow API --
    // 8 candidates max fit comfortably across 2-3 lines on a phone width.
    val rows = candidates.chunked(3)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        rows.forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEach { word ->
                    SuggestionChip(onClick = { onPick(word) }, label = { Text(word) })
                }
            }
        }
    }
}

@Composable
fun RestoreScreen(error: String?, onRestore: (seed: String, password: String, height: Long) -> Unit, onBack: () -> Unit) {
    val password by remember { mutableStateOf(WalletSession.generateRandomPassword()) }
    var height by remember { mutableStateOf("0") }
    var seedText by remember { mutableStateOf("") }
    var wordByWord by remember { mutableStateOf(false) }
    var pickedWords by remember { mutableStateOf<List<String>?>(null) }

    // Pasted input is normalized: lowercased and split on ANY run of
    // whitespace, so newlines, tabs, or double spaces from a paste all work.
    val pastedWords = remember(seedText) {
        seedText.trim().lowercase().split(Regex("\\s+")).filter { it.isNotBlank() }
    }
    val effectiveSeed: String? = if (wordByWord) pickedWords?.joinToString(" ")
        else pastedWords.joinToString(" ").ifBlank { null }
    val wordCount = if (wordByWord) (pickedWords?.size ?: 0) else pastedWords.size
    // Monero mnemonics are 25 words (24 legacy without the checksum word, 13
    // for some light wallets). Accept a tolerant range and let the native
    // layer be the real validator rather than second-guessing it here.
    val canRestore = effectiveSeed != null && wordCount in 12..26

    Column(modifier = Modifier.fillMaxSize().padding(24.dp)) {
        Text("Restore wallet", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(16.dp))
        error?.let {
            Text(it, color = MaterialTheme.colorScheme.error)
            Spacer(Modifier.height(8.dp))
        }
        OutlinedTextField(
            value = height, onValueChange = { height = it.filter(Char::isDigit) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text("Restore height (0 = from genesis)") }
        )
        Spacer(Modifier.height(16.dp))

        if (!wordByWord) {
            Text(
                "Paste or type your whole seed phrase, with the words separated by spaces.",
                style = MaterialTheme.typography.bodySmall
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = seedText,
                onValueChange = { seedText = it },
                modifier = Modifier.fillMaxWidth().weight(1f),
                label = { Text("Seed phrase") },
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    capitalization = KeyboardCapitalization.None,
                    autoCorrect = false
                )
            )
            Spacer(Modifier.height(4.dp))
            Text("$wordCount words entered", style = MaterialTheme.typography.labelMedium)
            TextButton(onClick = { wordByWord = true }) { Text("Enter word-by-word instead") }
        } else {
            SeedWordPicker(
                totalWords = 25,
                modifier = Modifier.weight(1f),
                onComplete = { words -> pickedWords = words }
            )
            TextButton(onClick = { wordByWord = false }) { Text("Paste the whole phrase instead") }
        }

        Spacer(Modifier.height(16.dp))
        Button(
            onClick = { onRestore(effectiveSeed!!, password, height.toLongOrNull() ?: 0L) },
            enabled = canRestore,
            modifier = Modifier.fillMaxWidth()
        ) { Text("Restore") }
        TextButton(onClick = onBack) { Text("Back") }
    }
}

/**
 * Signed-in app shell: a top bar with the BitMonero mark, a bottom tab bar
 * (Wallet / Send / Receive / Settings), and the selected tab's content.
 * Modeled on the WATTx wallet's bottom-tab layout, minus the staking and
 * other non-Monero tabs.
 */
@Composable
fun MainScaffold(session: WalletSession, startTab: Int, onBackup: () -> Unit) {
    var tab by rememberSaveable { mutableStateOf(startTab) }
    // (label, glyph) per tab.
    val tabs = listOf("Wallet" to "👛", "Send" to "↗", "Receive" to "↙", "Settings" to "⚙")

    Scaffold(
        containerColor = Brand.canvas,
        topBar = { BmrTopBar() },
        bottomBar = {
            NavigationBar(containerColor = Brand.card, tonalElevation = 0.dp) {
                tabs.forEachIndexed { i, (label, glyph) ->
                    NavigationBarItem(
                        selected = tab == i,
                        onClick = { tab = i },
                        icon = { Text(glyph, fontSize = 20.sp) },
                        label = { Text(label) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = Brand.orange,
                            selectedTextColor = Brand.orange,
                            indicatorColor = Brand.cardAlt,
                            unselectedIconColor = Brand.textDim,
                            unselectedTextColor = Brand.textDim,
                        )
                    )
                }
            }
        }
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            when (tab) {
                0 -> WalletTab(session, onSend = { tab = 1 }, onReceive = { tab = 2 })
                1 -> SendScreen(session, onDone = { tab = 0 }, onBack = { tab = 0 })
                2 -> ReceiveScreen(session, onBack = { tab = 0 })
                else -> SettingsScreen(session, onBack = { tab = 0 }, onBackup = onBackup)
            }
        }
    }
}

@Composable
private fun BmrTopBar() {
    Row(
        modifier = Modifier.fillMaxWidth().background(Brand.canvas)
            .padding(start = 18.dp, end = 18.dp, top = 14.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Image(
            painter = painterResource(R.mipmap.ic_launcher_foreground),
            contentDescription = null,
            modifier = Modifier.size(40.dp)
        )
        Text("BitMonero", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
    }
}

/** Rounded, outlined dark card used throughout the redesigned screens. */
@Composable
fun BrandCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier
            .clip(RoundedCornerShape(16.dp))
            .background(Brand.card)
            .border(1.dp, Brand.line, RoundedCornerShape(16.dp))
            .padding(18.dp),
        content = content
    )
}

@Composable
fun WalletTab(session: WalletSession, onSend: () -> Unit, onReceive: () -> Unit) {
    var address by remember { mutableStateOf("") }
    var balance by remember { mutableStateOf(0L) }
    var unlocked by remember { mutableStateOf(0L) }
    var synced by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<WalletSession.SyncStatus?>(null) }

    LaunchedEffect(Unit) {
        while (true) {
            address = runCatching { session.address() }.getOrDefault("")
            balance = runCatching { session.balanceAtomic() }.getOrDefault(0L)
            unlocked = runCatching { session.unlockedBalanceAtomic() }.getOrDefault(0L)
            synced = runCatching { session.isSynchronized() }.getOrDefault(false)
            status = runCatching { session.syncStatus() }.getOrNull()
            kotlinx.coroutines.delay(2000)
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)) {
        // Balance hero card (orange gradient)
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp))
                .background(Brush.linearGradient(listOf(BmrOrange, BmrOrangeDark)))
                .padding(24.dp)
        ) {
            Text("Total balance", color = Color.White.copy(alpha = .85f), style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(6.dp))
            Text(
                formatAtomicUnits(unlocked),
                color = Color.White,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold
            )
            if (balance != unlocked) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "Locked: ${formatAtomicUnits(balance - unlocked)}",
                    color = Color.White.copy(alpha = .85f),
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }

        Spacer(Modifier.height(14.dp))
        StatusPill(synced = synced, status = status)

        Spacer(Modifier.height(18.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            Button(onClick = onSend, modifier = Modifier.weight(1f)) { Text("Send") }
            OutlinedButton(onClick = onReceive, modifier = Modifier.weight(1f)) { Text("Receive") }
        }

        Spacer(Modifier.height(18.dp))
        BrandCard(Modifier.fillMaxWidth()) {
            Text("Your primary address", color = Brand.textDim, style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(8.dp))
            SelectionContainerText(address.ifBlank { "…" })
        }
    }
}

/** Small colored-dot + text connection/sync indicator. */
@Composable
fun StatusPill(synced: Boolean, status: WalletSession.SyncStatus?) {
    val (dot, text) = when {
        status == null -> Brand.amber to "Connecting…"
        status.connection == WalletSession.ConnectionState.DISCONNECTED ->
            Brand.red to "Not connected — check Settings ▸ Nodes"
        status.connection == WalletSession.ConnectionState.WRONG_VERSION ->
            Brand.red to "Node RPC version mismatch — try another node"
        synced -> Brand.green to "Synchronized · height ${status.walletHeight}"
        else -> Brand.amber to "Syncing ${status.walletHeight} / ${status.daemonHeight}"
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(9.dp).clip(CircleShape).background(dot))
        Spacer(Modifier.width(8.dp))
        Text(text, color = Brand.textDim, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
fun SelectionContainerText(text: String) {
    androidx.compose.foundation.text.selection.SelectionContainer {
        Text(text, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
fun ErrorScreen(message: String, onDismiss: () -> Unit) {
    Column(modifier = Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center) {
        Text("Something went wrong", style = MaterialTheme.typography.titleLarge)
        Text(message)
        Spacer(Modifier.height(16.dp))
        Button(onClick = onDismiss) { Text("OK") }
    }
}

/** BitMonero uses Monero's 12-decimal atomic unit convention (COIN = 10^12). */
private fun formatAtomicUnits(atomic: Long): String {
    val coin = 1_000_000_000_000.0
    return "%.6f BMR".format(atomic / coin)
}
