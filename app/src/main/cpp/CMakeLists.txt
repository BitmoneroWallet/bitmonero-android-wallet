# Builds the JNI bridge together with the BitMonero source tree's own
# wallet_api target (src/wallet/api), so the Android .so is compiled from
# the exact same consensus/wallet2 code as the Linux/Windows builds rather
# than a separately-vendored copy that could drift out of sync.
cmake_minimum_required(VERSION 3.10)
project(bitmonero_wallet_jni)

set(BITMONERO_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/../../../../../src"
    CACHE PATH "Path to the BitMonero source tree (contains top-level CMakeLists.txt)")

# Pull in the whole BitMonero build so the wallet_api target (and its
# transitive deps: wallet, cryptonote_core, mnemonics, ...) is available.
# ANDROID / BUILD_GUI_DEPS=OFF keep this to just the libs we need, not the
# daemon/simplewallet executables.
set(BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(STATIC ON CACHE BOOL "" FORCE)

# Function/data-level sections so --gc-sections (below) can drop dead code
# at function granularity, not just whole translation units. This is what
# lets the linker discard get_block_longhash()/get_altblock_longhash()
# (src/cryptonote_core/cryptonote_tx_utils.cpp) -- full-node PoW validation
# functions a remote-node wallet never calls, confirmed by grep: their only
# callers are blockchain.cpp/cryptonote_core.cpp, nothing under src/wallet/
# -- and, transitively, RandomX's AArch64 JIT assembly entirely, which is
# what actually hits LLD's CONDBR19 thunk limitation. This avoids the
# problem structurally instead of patching RandomX's PoW-critical assembly.
set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -ffunction-sections -fdata-sections")
set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -ffunction-sections -fdata-sections")

# This links as a SHARED library, so by default every global C++ symbol
# (get_block_longhash included) gets default visibility and stays in the
# .so's dynamic symbol table as a potential external entry point --
# --gc-sections can't drop anything exported, regardless of whether our
# own JNI code calls it. Hiding everything except the JNI entry points
# (bitmonero_jni.cpp's JNIEXPORT macros already force default visibility
# on just those) is what actually lets dead-code elimination work through
# a whole static-library closure like this one. Standard practice for JNI
# libraries generally, not a BitMonero-specific hack.
set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -fvisibility=hidden")
set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -fvisibility=hidden -fvisibility-inlines-hidden")

add_subdirectory(${BITMONERO_ROOT} bitmonero_build)

add_library(bitmonero_wallet_jni SHARED
    bitmonero_jni.cpp)

target_include_directories(bitmonero_wallet_jni PRIVATE
    ${BITMONERO_ROOT}/src
    ${BITMONERO_ROOT}/contrib/epee/include)

target_link_libraries(bitmonero_wallet_jni PRIVATE
    wallet_api
    log)


# wallet_api is EXCLUDE_FROM_ALL upstream (it's normally opt-in); force it
# to actually build as part of this target's dependency graph.
set_property(TARGET wallet_api PROPERTY EXCLUDE_FROM_ALL FALSE)

target_link_options(bitmonero_wallet_jni PRIVATE -Wl,--gc-sections -Wl,--exclude-libs,ALL)
