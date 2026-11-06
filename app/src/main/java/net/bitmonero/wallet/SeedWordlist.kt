package net.bitmonero.wallet

import android.content.Context

/**
 * Monero's actual English mnemonic word list (1625 words), extracted
 * directly from the BitMonero source tree (src/mnemonics/english.h) rather
 * than retyped -- a hand-copied list would risk silently missing a word
 * from wallet2's real word set, which would make a correctly-spelled seed
 * word unselectable in the picker.
 */
object SeedWordlist {
    @Volatile private var words: List<String>? = null

    fun load(context: Context): List<String> {
        words?.let { return it }
        synchronized(this) {
            words?.let { return it }
            val loaded = context.resources.openRawResource(R.raw.english_wordlist)
                .bufferedReader()
                .readLines()
                .filter { it.isNotBlank() }
            words = loaded
            return loaded
        }
    }

    /** Prefix match, case-insensitive -- the standard seed-entry UX pattern. */
    fun matching(context: Context, prefix: String): List<String> {
        if (prefix.isEmpty()) return emptyList()
        val p = prefix.lowercase()
        return load(context).filter { it.startsWith(p) }
    }
}
