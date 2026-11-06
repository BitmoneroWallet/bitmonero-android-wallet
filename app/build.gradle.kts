plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "net.bitmonero.wallet"
    compileSdk = 34

    defaultConfig {
        applicationId = "net.bitmonero.wallet"
        // 24, not depends' ANDROID_API=21 -- modern boost/openssl/sodium as
        // built by contrib/depends assume some post-21 bionic symbols.
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0-dev"

        ndk {
            // arm64-v8a: the RandomX/LLD CONDBR19 blocker (see
            // project_bitmonero_wallet_build.md) is resolved by hiding
            // symbol visibility + --gc-sections, which lets the linker
            // eliminate the full-node-only PoW-validation code path a
            // wallet never calls -- shrinking the binary enough to bring
            // the one remaining internal branch back into range.
            // Verified by disassembly, not just "it links".
            // x86_64: kept for emulator testing.
            abiFilters += "arm64-v8a"
            abiFilters += "x86_64"
        }
    }

    // NOT using externalNativeBuild here on purpose: our native build goes
    // through contrib/depends' own custom toolchain.cmake (which knows
    // where depends staged boost/openssl/sodium/etc for each Android ABI),
    // not the NDK's default android.toolchain.cmake that AGP would drive
    // automatically. Mixing the two risks a silent toolchain conflict.
    // Instead the .so (built standalone via
    // android/app/src/main/cpp/CMakeLists.txt against the depends
    // toolchain) plus its one runtime dependency, libc++_shared.so, are
    // copied straight into src/main/jniLibs/<abi>/, which Gradle bundles
    // automatically with no CMake integration needed at Gradle-build time.

    // NOT configured yet: a real release signing config needs a keystore
    // whose passphrase is generated straight into a file with a CSPRNG
    // (openssl rand / keytool -genkeypair against a pre-written passphrase
    // file), never hand-typed -- see the iXcoin lesson on transcribed
    // signing passphrases. Do not add a signingConfig here until that
    // keystore exists; a debug-signed release build must never be
    // published (debug signing can't be rotated -- it IS the app's
    // identity forever once installed by anyone).
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    packaging {
        jniLibs {
            // Same reasoning as the iXcoin daemon packaging: only relevant
            // if we ever bundle monerod itself rather than just wallet_api;
            // left here as a reminder for the full-node-mode follow-up.
            useLegacyPackaging = false
        }
    }

    buildFeatures {
        compose = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.compose.material3:material3:1.2.1")
    implementation(platform("androidx.compose:compose-bom:2024.09.00"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // QR: zxing core for generating the receive-address QR (pure Java, no
    // camera needed); zxing-android-embedded for the send-screen scanner
    // (bundles a ready-made scanning Activity, avoids hand-rolling
    // CameraX + decode plumbing for tonight's scope).
    implementation("com.google.zxing:core:3.5.3")
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")

    // App-lock: BiometricPrompt for fingerprint/face unlock, plus
    // EncryptedSharedPreferences (Android Keystore-backed AES) for the
    // passphrase-lock fallback -- the wallet's own password is never
    // stored in plain SharedPreferences.
    implementation("androidx.biometric:biometric:1.1.0")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    // BiometricPrompt requires a FragmentActivity, not just ComponentActivity.
    implementation("androidx.fragment:fragment-ktx:1.8.4")
}
