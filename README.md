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

## ⚠️ Release signing

There is **no release signing config yet**. A debug-signed build must not be
distributed as a real release — a debug key cannot be rotated and becomes the
app's permanent identity. A proper release requires a keystore whose
passphrase is generated with a CSPRNG into a file (never hand-typed).

## License

Monero-derived; see the upstream Monero project license.
