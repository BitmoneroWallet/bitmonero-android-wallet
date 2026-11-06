# NativeWallet's `external fun` methods are called by name+signature from
# JNI (bitmonero_jni.cpp's Java_net_bitmonero_wallet_NativeWallet_* symbols
# are generated from this exact class/package path). R8 already keeps
# native methods by default, but keep the whole class explicitly so a
# rename of a non-native helper here can't accidentally break the mapping.
-keep class net.bitmonero.wallet.NativeWallet { *; }
-keepclasseswithmembernames class * {
    native <methods>;
}
