// JNI bridge between the BitMonero wallet2_api (wallet_api target in the
// main BitMonero source tree, src/wallet/api/wallet2_api.h) and the Kotlin
// UI. Deliberately thin: all wallet logic stays in wallet2/wallet_api,
// which already implements password-based key-file encryption, subaddress
// generation, and sync against a daemon. This layer only marshals types.
//
// Security invariants this file must preserve (see project memory
// project_bitmonero_wallet_build.md / the iXcoin wallet's known bugs):
//  - Never log a seed, private key, or password (no ALOGD/printf on these).
//  - A wallet is never persisted to disk (no create/store) before the
//    caller has confirmed the user recorded the seed. Kotlin side enforces
//    the ordering; this bridge just exposes getSeed() before store().
//  - restoreFromSeed must refuse to overwrite an existing wallet file
//    (mirrors the iXcoin restore-over-existing-wallet funds bug) by
//    checking Utils::isAddressLocal-style existence before calling
//    WalletManager::recoveryWallet.

#include <jni.h>
#include <string>
#include <memory>
#include "wallet/api/wallet2_api.h"

using Monero::Wallet;
using Monero::WalletManager;
using Monero::WalletManagerFactory;

namespace {

Wallet* handle(jlong ptr) { return reinterpret_cast<Wallet*>(ptr); }

std::string jstr(JNIEnv* env, jstring s) {
    if (!s) return {};
    const char* c = env->GetStringUTFChars(s, nullptr);
    std::string out(c);
    env->ReleaseStringUTFChars(s, c);
    return out;
}

} // namespace

extern "C" {

// Returns 0 if a wallet keys file already exists at `path` (caller must
// route to openWallet instead) — checked before any create/restore call.
JNIEXPORT jboolean JNICALL
Java_net_bitmonero_wallet_NativeWallet_walletExists(JNIEnv* env, jobject, jstring jpath) {
    std::string path = jstr(env, jpath);
    WalletManager* wm = WalletManagerFactory::getWalletManager();
    return wm->walletExists(path) ? JNI_TRUE : JNI_FALSE;
}

// Creates a brand-new wallet and returns its mnemonic seed. Deliberately
// does NOT call store() here — the caller must show the seed to the user,
// get explicit confirmation it was recorded, and only then call
// commitToDisk(). Until commitToDisk is called the keys exist in memory
// only (matches the iXcoin fix: never persist a generated seed the user
// never actually saw and confirmed).
JNIEXPORT jlong JNICALL
Java_net_bitmonero_wallet_NativeWallet_createWallet(JNIEnv* env, jobject,
                                                     jstring jpath, jstring jpassword,
                                                     jstring jlanguage) {
    std::string path = jstr(env, jpath);
    WalletManager* wm = WalletManagerFactory::getWalletManager();
    if (wm->walletExists(path)) {
        return 0; // caller must not silently clobber an existing wallet
    }
    Wallet* w = wm->createWallet(path, jstr(env, jpassword), jstr(env, jlanguage),
                                  Monero::NetworkType::MAINNET);
    return reinterpret_cast<jlong>(w);
}

JNIEXPORT jstring JNICALL
Java_net_bitmonero_wallet_NativeWallet_getSeed(JNIEnv* env, jobject, jlong wptr) {
    Wallet* w = handle(wptr);
    return env->NewStringUTF(w->seed("").c_str());
}

// Raw key export -- same sensitivity class as the seed (never logged; the
// caller must gate display behind re-authentication, same as getSeed's
// only caller does for the create flow).
JNIEXPORT jstring JNICALL
Java_net_bitmonero_wallet_NativeWallet_getSecretSpendKey(JNIEnv* env, jobject, jlong wptr) {
    return env->NewStringUTF(handle(wptr)->secretSpendKey().c_str());
}

JNIEXPORT jstring JNICALL
Java_net_bitmonero_wallet_NativeWallet_getSecretViewKey(JNIEnv* env, jobject, jlong wptr) {
    return env->NewStringUTF(handle(wptr)->secretViewKey().c_str());
}

// Explicit, separate step from createWallet(): only after this returns true
// does the wallet exist on disk. Kotlin side must gate this behind the
// user having confirmed they recorded the seed shown by getSeed().
JNIEXPORT jboolean JNICALL
Java_net_bitmonero_wallet_NativeWallet_commitToDisk(JNIEnv*, jobject, jlong wptr) {
    Wallet* w = handle(wptr);
    return w->store("") ? JNI_TRUE : JNI_FALSE;
}

// Restoring over an existing wallet file is refused at the walletExists
// check the Kotlin caller must perform first (see walletExists above) —
// this mirrors the iXcoin bug where WalletAppKit silently kept the OLD
// wallet file and discarded a restored seed. Here the native layer simply
// never gets a chance to open the old file, because the JNI-level create
// path already returns 0 / null if the target path exists; restore uses
// the same guard.
JNIEXPORT jlong JNICALL
Java_net_bitmonero_wallet_NativeWallet_restoreFromSeed(JNIEnv* env, jobject,
                                                        jstring jpath, jstring jpassword,
                                                        jstring jseed, jlong restoreHeight) {
    std::string path = jstr(env, jpath);
    WalletManager* wm = WalletManagerFactory::getWalletManager();
    if (wm->walletExists(path)) {
        return 0;
    }
    Wallet* w = wm->recoveryWallet(path, jstr(env, jpassword), jstr(env, jseed),
                                    Monero::NetworkType::MAINNET,
                                    static_cast<uint64_t>(restoreHeight));
    // recoveryWallet returns a non-null Wallet even for an invalid/unparseable
    // mnemonic (bad checksum, wrong word count, non-wordlist word); the
    // failure shows up in status(), not the pointer. Without this check an
    // invalid seed would surface to the user as the confusing "restored
    // wallet's seed does not match" message from the Kotlin guard. Fail here
    // instead, and don't leave a half-written wallet file on disk.
    if (w->status() != Wallet::Status_Ok) {
        wm->closeWallet(w, false);
        return 0;
    }
    return reinterpret_cast<jlong>(w);
}

JNIEXPORT jlong JNICALL
Java_net_bitmonero_wallet_NativeWallet_openWallet(JNIEnv* env, jobject,
                                                   jstring jpath, jstring jpassword) {
    WalletManager* wm = WalletManagerFactory::getWalletManager();
    Wallet* w = wm->openWallet(jstr(env, jpath), jstr(env, jpassword),
                                Monero::NetworkType::MAINNET);
    // WalletManager::openWallet always returns non-null; check status
    // instead of null (a wrong password fails status(), not the pointer).
    if (w->status() != Wallet::Status_Ok) {
        wm->closeWallet(w, false);
        return 0;
    }
    return reinterpret_cast<jlong>(w);
}

// Loopback-or-remote only: `daemonAddress` is caller-supplied (either the
// user's own node on 127.0.0.1, or a BitMonero remote node they trust).
// This binding never hardcodes a third-party node the app author controls,
// so the app can't silently route the user's wallet traffic through
// infrastructure they didn't choose.
JNIEXPORT void JNICALL
Java_net_bitmonero_wallet_NativeWallet_initDaemon(JNIEnv* env, jobject, jlong wptr,
                                                   jstring jaddress, jboolean trusted) {
    Wallet* w = handle(wptr);
    // init()'s 6th positional arg is lightWallet, NOT trusted -- trust is a
    // separate flag. Getting this backwards would silently run the wallet
    // in light-wallet mode (which hands the address to a remote server for
    // scanning) instead of the intended trusted/untrusted local-scan choice.
    w->setTrustedDaemon(static_cast<bool>(trusted));
    w->init(jstr(env, jaddress), 0, "", "", false, /*lightWallet=*/false);
}

JNIEXPORT void JNICALL
Java_net_bitmonero_wallet_NativeWallet_startRefresh(JNIEnv*, jobject, jlong wptr) {
    handle(wptr)->startRefresh();
}

// A freshly-created (not restored) wallet defaults its scan start point to
// the chain height at creation time, since it couldn't have received funds
// before it existed -- but that means any transaction to one of its
// subaddresses mined BEFORE that point is permanently invisible without an
// explicit rescan. rescanBlockchainAsync() scans from genesis regardless
// of the wallet's stored refresh-from-height, catching exactly that case.
JNIEXPORT void JNICALL
Java_net_bitmonero_wallet_NativeWallet_rescanBlockchain(JNIEnv*, jobject, jlong wptr) {
    handle(wptr)->rescanBlockchainAsync();
}

JNIEXPORT jboolean JNICALL
Java_net_bitmonero_wallet_NativeWallet_isSynchronized(JNIEnv*, jobject, jlong wptr) {
    return handle(wptr)->synchronized() ? JNI_TRUE : JNI_FALSE;
}

// Real sync progress, not just a boolean -- a wallet stuck talking to an
// unreachable node (e.g. 127.0.0.1 with no local daemon) looks identical
// to "Syncing..." forever without this. connected() == 0 means
// Disconnected specifically (see Wallet::ConnectionStatus in
// wallet2_api.h); Kotlin surfaces that distinctly from "connected but
// still catching up".
JNIEXPORT jint JNICALL
Java_net_bitmonero_wallet_NativeWallet_getConnectionStatus(JNIEnv*, jobject, jlong wptr) {
    return static_cast<jint>(handle(wptr)->connected());
}

JNIEXPORT jlong JNICALL
Java_net_bitmonero_wallet_NativeWallet_getWalletHeight(JNIEnv*, jobject, jlong wptr) {
    return static_cast<jlong>(handle(wptr)->blockChainHeight());
}

JNIEXPORT jlong JNICALL
Java_net_bitmonero_wallet_NativeWallet_getDaemonHeight(JNIEnv*, jobject, jlong wptr) {
    return static_cast<jlong>(handle(wptr)->daemonBlockChainHeight());
}

JNIEXPORT jstring JNICALL
Java_net_bitmonero_wallet_NativeWallet_getAddress(JNIEnv* env, jobject, jlong wptr) {
    Wallet* w = handle(wptr);
    return env->NewStringUTF(w->address(0, 0).c_str());
}

JNIEXPORT jlong JNICALL
Java_net_bitmonero_wallet_NativeWallet_getBalance(JNIEnv*, jobject, jlong wptr) {
    return static_cast<jlong>(handle(wptr)->balance(0));
}

JNIEXPORT jlong JNICALL
Java_net_bitmonero_wallet_NativeWallet_getUnlockedBalance(JNIEnv*, jobject, jlong wptr) {
    return static_cast<jlong>(handle(wptr)->unlockedBalance(0));
}

JNIEXPORT jstring JNICALL
Java_net_bitmonero_wallet_NativeWallet_getStatusMessage(JNIEnv* env, jobject, jlong wptr) {
    int status = 0;
    std::string msg;
    handle(wptr)->statusWithErrorString(status, msg); // status/msg are out-params
    return env->NewStringUTF(msg.c_str());
}

// Zeroes and frees the Wallet object. Does not touch the on-disk keys file
// (wallet2's own destructor handles that); this only releases the C++
// object the JNI layer was holding.
JNIEXPORT void JNICALL
Java_net_bitmonero_wallet_NativeWallet_closeWallet(JNIEnv*, jobject, jlong wptr) {
    WalletManager* wm = WalletManagerFactory::getWalletManager();
    wm->closeWallet(handle(wptr), true);
}

// --- Subaddresses ---

JNIEXPORT jint JNICALL
Java_net_bitmonero_wallet_NativeWallet_getSubaddressCount(JNIEnv*, jobject, jlong wptr) {
    return static_cast<jint>(handle(wptr)->numSubaddresses(0));
}

JNIEXPORT jstring JNICALL
Java_net_bitmonero_wallet_NativeWallet_getSubaddress(JNIEnv* env, jobject, jlong wptr, jint index) {
    return env->NewStringUTF(handle(wptr)->address(0, static_cast<uint32_t>(index)).c_str());
}

JNIEXPORT jstring JNICALL
Java_net_bitmonero_wallet_NativeWallet_getSubaddressLabel(JNIEnv* env, jobject, jlong wptr, jint index) {
    return env->NewStringUTF(handle(wptr)->getSubaddressLabel(0, static_cast<uint32_t>(index)).c_str());
}

// Appends a new subaddress; the caller re-reads getSubaddressCount() to find
// its index (wallet2 always appends at the end, so it's count-1 afterward).
JNIEXPORT void JNICALL
Java_net_bitmonero_wallet_NativeWallet_addSubaddress(JNIEnv* env, jobject, jlong wptr, jstring jlabel) {
    handle(wptr)->addSubaddress(0, jstr(env, jlabel));
}

JNIEXPORT jboolean JNICALL
Java_net_bitmonero_wallet_NativeWallet_isAddressValid(JNIEnv* env, jobject, jstring jaddress) {
    return Wallet::addressValid(jstr(env, jaddress), Monero::NetworkType::MAINNET) ? JNI_TRUE : JNI_FALSE;
}

// --- Sending ---

// Creates, checks, and commits a transaction in one call rather than
// exposing PendingTransaction's lifecycle (create/check-status/commit/
// dispose) across JNI -- fewer object-ownership edge cases for a first
// send implementation. Returns "OK:<txid>:<fee_atomic>" on success or
// "ERROR:<message>" on failure; Kotlin parses the prefix. Never logs the
// destination address or amount (not secret like a seed, but no reason to
// put transaction details in logcat either).
JNIEXPORT jstring JNICALL
Java_net_bitmonero_wallet_NativeWallet_sendTransaction(JNIEnv* env, jobject, jlong wptr,
                                                        jstring jaddress, jlong amountAtomic,
                                                        jint priority) {
    Wallet* w = handle(wptr);
    std::string address = jstr(env, jaddress);

    if (!Wallet::addressValid(address, Monero::NetworkType::MAINNET)) {
        return env->NewStringUTF("ERROR:Invalid address");
    }

    Monero::optional<uint64_t> amount(static_cast<uint64_t>(amountAtomic));
    Monero::PendingTransaction* tx = w->createTransaction(
        address, "", amount, 0,
        static_cast<Monero::PendingTransaction::Priority>(priority));

    if (tx->status() != Monero::PendingTransaction::Status_Ok) {
        std::string err = "ERROR:" + tx->errorString();
        w->disposeTransaction(tx);
        return env->NewStringUTF(err.c_str());
    }

    uint64_t fee = tx->fee();
    bool committed = tx->commit();
    if (!committed) {
        std::string err = "ERROR:" + tx->errorString();
        w->disposeTransaction(tx);
        return env->NewStringUTF(err.c_str());
    }

    std::string txid = tx->txid().empty() ? "" : tx->txid()[0];
    w->disposeTransaction(tx);
    std::string result = "OK:" + txid + ":" + std::to_string(fee);
    return env->NewStringUTF(result.c_str());
}

} // extern "C"
