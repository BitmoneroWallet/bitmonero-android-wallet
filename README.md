# BitMonero Wallet — Android

A native Android Monero wallet (Jetpack Compose UI + the Monero `wallet_api`
core compiled for `arm64-v8a` / `x86_64`).

- **Application ID:** `net.bitmonero.wallet`
- **minSdk:** 24 · **targetSdk / compileSdk:** 34
- **versionName:** `0.1.0-dev` (release tag `v0.0.1`)

## What's in this repo

This repository holds the **Android app source** only. The native Monero
libraries (`jniLibs/*.so`) and CMake build output are produced from the
Monero core via `contrib/depends` and are intentionally **not committed**
(see `.gitignore`); they are large and fully regenerable.

```
android/
  app/
    src/main/java/net/bitmonero/wallet/   Kotlin / Compose UI
    src/main/cpp/                          JNI bridge + CMakeLists
    src/main/res/                          resources, icons
  build.gradle.kts, settings.gradle.kts
```

## Building

The app bundles prebuilt `wallet_api` `.so` files into
`app/src/main/jniLibs/<abi>/`. Build those from Monero core against the
`contrib/depends` Android toolchain, drop them in, then:

```
./gradlew assembleDebug      # debug APK
./gradlew assembleRelease    # requires a real signing keystore (see below)
```

## Node mode: remote or on-device full node

Settings → **Node mode** lets the user choose how the wallet reaches the
network:

- **Remote node** (default) — connect to one of the user-managed nodes in
  Settings → Remote nodes. Fast and light; the node learns which outputs you
  query.
- **Run a full node on this device** — run BitMonero's `monerod` on the phone
  and connect the wallet over loopback (`127.0.0.1`). Maximum privacy (nothing
  about the wallet leaves the device) at the cost of storage, battery and
  data. Pruned mode is on by default (tens of GB instead of hundreds, still
  full validation).

### Architecture (app side — complete)

| Piece | Role |
|-------|------|
| `NodeStore` | persists mode, local RPC port, pruned flag |
| `NodeConnector` | resolves mode → daemon address; single connect path |
| `NativeDaemon` | JNI binding to the node; **lazily loaded, never crashes if absent** |
| `FullNodeController` | data dir, capability gate, start/stop, status |
| `FullNodeService` | foreground service so an initial sync survives backgrounding |
| `SettingsScreen` | mode selector, pruned toggle, port, live sync status |

The app is fully functional without the node engine: `NativeDaemon.isAvailable`
returns `false`, full-node mode is clearly marked unavailable, and the wallet
stays a remote-node wallet.

### Making the node actually run (native follow-up)

Running a real on-device node needs the optional native library
`libbitmonero_daemon_jni.so`, which is **not** bundled in the default build.
Unlike the wallet lib, a full node must keep the RandomX PoW path, which is
exactly what trips the LLD/CONDBR19 linker limit the wallet lib sidesteps with
`--gc-sections`. See `app/src/main/cpp/bitmonero_daemon_jni.cpp` and
`app/src/main/cpp/CMakeLists.daemon.txt` for the native contract, the build
outline, and the RandomX/LLD note. Build it per ABI, drop the `.so` into
`app/src/main/jniLibs/<abi>/`, and the in-app option becomes fully functional
with no Kotlin changes.

## ⚠️ Release signing

There is **no release signing config yet**. A debug-signed build must not be
distributed as a real release — a debug key cannot be rotated and becomes the
app's permanent identity. A proper release requires a keystore whose
passphrase is generated with a CSPRNG into a file (never hand-typed).

## License

Monero-derived; see the upstream Monero project license.
