// =============================================================================
// bitmonero_daemon_jni.cpp -- JNI bridge for the OPTIONAL embedded full node.
//
// STATUS: reference implementation / follow-up work. This file is NOT built
// by the default android build (CMakeLists.txt builds only the wallet JNI
// lib). It defines the native contract that NativeDaemon.kt binds to, and
// sketches how to boot monerod in-process so a developer can finish and
// bundle libbitmonero_daemon_jni.so.
//
// WHY IT'S SEPARATE FROM THE WALLET LIB
// -------------------------------------
// The wallet lib (bitmonero_jni.cpp) is deliberately linked so the linker can
// garbage-collect the full-node PoW path -- get_block_longhash(), and
// transitively RandomX's AArch64 JIT -- because a remote-node wallet never
// calls it, and keeping it tripped LLD's CONDBR19 thunk-range limitation (see
// CMakeLists.txt). A full node is exactly the code that path IS needed for, so
// it cannot share the wallet lib's --gc-sections trick; it must be its own .so
// that links the daemon targets and resolves the RandomX relocation issue head
// on (e.g. RANDOMX_FLAG_JIT off on arm64, or the upstream LLD workaround).
// That is the substantive remaining work; the Kotlin layer above is complete
// and already degrades gracefully while this .so is absent
// (NativeDaemon.isAvailable == false).
//
// HOW TO BUILD (outline)
// ----------------------
//   * Build the BitMonero daemon targets (cryptonote_core, p2p, rpc,
//     daemonize) for each Android ABI against the contrib/depends toolchain,
//     the same way wallet_api is built for bitmonero_jni.cpp.
//   * Compile this file into a SHARED lib `bitmonero_daemon_jni`, linking
//     those targets (see CMakeLists.daemon.txt alongside this file).
//   * Drop the resulting libbitmonero_daemon_jni.so (+ its deps) into
//     app/src/main/jniLibs/<abi>/. Gradle bundles it automatically and
//     NativeDaemon.isAvailable flips to true -- no Kotlin changes needed.
// =============================================================================

#include <jni.h>
#include <atomic>
#include <mutex>
#include <string>
#include <android/log.h>

#define LOG_TAG "bitmonero_daemon_jni"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// When the real daemon targets are linked, replace the stubbed body of each
// function below with calls into the node. The intended shape:
//
//   #include "daemon/daemon.h"            // daemonize::t_daemon
//   #include "cryptonote_core/cryptonote_core.h"
//   #include "rpc/core_rpc_server.h"
//
// A single process-wide node instance, guarded by a mutex, started on its own
// std::thread so nativeStart() returns promptly. nativeHeight() reads
// core.get_current_blockchain_height(); nativeTargetHeight() reads
// core.get_target_blockchain_height(); nativeStop() calls daemon.stop().

namespace {
    std::mutex g_mutex;
    std::atomic<bool> g_running{false};
    std::atomic<long long> g_height{0};
    std::atomic<long long> g_target_height{0};
    std::string g_status = "Stopped";

    std::string jstr(JNIEnv* env, jstring s) {
        if (!s) return {};
        const char* c = env->GetStringUTFChars(s, nullptr);
        std::string out(c ? c : "");
        if (c) env->ReleaseStringUTFChars(s, c);
        return out;
    }
}

extern "C" {

JNIEXPORT jboolean JNICALL
Java_net_bitmonero_wallet_NativeDaemon_nativeStart(
        JNIEnv* env, jobject /*thiz*/,
        jstring dataDir, jstring rpcBindIp, jint rpcBindPort,
        jboolean pruned, jboolean testnet) {
    std::lock_guard<std::mutex> lk(g_mutex);
    if (g_running.load()) return JNI_FALSE;

    const std::string dir = jstr(env, dataDir);
    const std::string ip = jstr(env, rpcBindIp);
    LOGI("nativeStart dir=%s rpc=%s:%d pruned=%d testnet=%d",
         dir.c_str(), ip.c_str(), (int)rpcBindPort, (int)pruned, (int)testnet);

    // TODO(full-node): construct boost::program_options vm with
    //   --data-dir=dir, --rpc-bind-ip=ip, --rpc-bind-port=rpcBindPort,
    //   --no-igd, --hide-my-port, (--prune-blockchain if pruned),
    //   (--testnet if testnet); build cryptonote::core + nodetool::node_server
    //   + cryptonote::core_rpc_server via daemonize::t_daemon, run it on a
    //   detached std::thread, and have that thread update g_height /
    //   g_target_height / g_status as it syncs.
    //
    // Until that is wired, report failure so FullNodeService stops cleanly and
    // the UI shows the node did not start (rather than faking a running node).
    g_status = "Full-node engine not implemented in this build";
    return JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_net_bitmonero_wallet_NativeDaemon_nativeStop(JNIEnv* /*env*/, jobject /*thiz*/) {
    std::lock_guard<std::mutex> lk(g_mutex);
    if (!g_running.load()) return;
    // TODO(full-node): daemon.stop(); join the node thread.
    g_running = false;
    g_status = "Stopped";
    LOGI("nativeStop");
}

JNIEXPORT jboolean JNICALL
Java_net_bitmonero_wallet_NativeDaemon_nativeIsRunning(JNIEnv* /*env*/, jobject /*thiz*/) {
    return g_running.load() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jlong JNICALL
Java_net_bitmonero_wallet_NativeDaemon_nativeHeight(JNIEnv* /*env*/, jobject /*thiz*/) {
    return (jlong)g_height.load();
}

JNIEXPORT jlong JNICALL
Java_net_bitmonero_wallet_NativeDaemon_nativeTargetHeight(JNIEnv* /*env*/, jobject /*thiz*/) {
    return (jlong)g_target_height.load();
}

JNIEXPORT jstring JNICALL
Java_net_bitmonero_wallet_NativeDaemon_nativeStatus(JNIEnv* env, jobject /*thiz*/) {
    std::lock_guard<std::mutex> lk(g_mutex);
    return env->NewStringUTF(g_status.c_str());
}

} // extern "C"
