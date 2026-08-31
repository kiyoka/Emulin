#!/usr/bin/env bash
# Shared Java command selection for AArch64 software and Apple HVF smokes.

configure_aarch64_emulin_runtime() {
    local root=$1
    AARCH64_TEST_BACKEND=${EMULIN_BACKEND:-software}
    AARCH64_JAVA_COMMAND=(java)

    if [ "$AARCH64_TEST_BACKEND" = native ]; then
        local signed_runtime=${EMULIN_HVF_JAVA:-$root/target/aarch64-hvf-java}
        local simd_shim=${EMULIN_HVF_SHIM:-$root/target/native/libemulin-hvf-simd.dylib}
        test -x "$signed_runtime/bin/java" || {
            echo "missing signed HVF Java runtime: $signed_runtime" >&2
            return 2
        }
        test -f "$simd_shim" || {
            echo "missing HVF SIMD shim: $simd_shim" >&2
            return 2
        }
        AARCH64_JAVA_COMMAND=(
            "$signed_runtime/bin/java"
            --enable-native-access=ALL-UNNAMED
            "-Demulin.hvf.simd-shim=$simd_shim"
        )
    fi
}
