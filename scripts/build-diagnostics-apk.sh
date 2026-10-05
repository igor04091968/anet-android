#!/bin/sh
# Build the diagnostic package on the USB workspace; no device/server access.
set -eu
build_number=${1:-3}
case "$build_number" in ''|*[!0-9]*) echo 'Expected a numeric build number' >&2; exit 2;; esac
project_dir=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
native_dir=${ANET_NATIVE_SOURCE:-"$project_dir/../anet-vpn"}
ndk_dir=${ANDROID_NDK_HOME:-/usr/lib/android-sdk/ndk/25.2.9519653}
export ANDROID_NDK_HOME="$ndk_dir"
export JAVA_HOME=${JAVA_HOME:-/usr/lib/jvm/java-21-openjdk-amd64}
export GITHUB_RUN_NUMBER="$build_number"
mkdir -p "$project_dir/dist/1.0.$build_number"
exec 9>"$project_dir/dist/.diagnostics-build.lock"
flock -n 9 || { echo 'Another diagnostic APK build is running' >&2; exit 1; }
cd "$native_dir"
cargo ndk -t arm64-v8a -t armeabi-v7a --platform 24 build -p anet-mobile --release
# These helpers have pinned Go dependencies and no operator credentials.
sh tools/dpi-helper/build-android.sh
for mapping in arm64-v8a:aarch64-linux-android armeabi-v7a:armv7-linux-androideabi; do
    abi=${mapping%%:*}
    target=${mapping#*:}
    mkdir -p "$project_dir/app/src/main/jniLibs/$abi"
    cp "$native_dir/target/$target/release/libanet_mobile.so" "$project_dir/app/src/main/jniLibs/$abi/"
    cp "$native_dir/tools/dpi-helper/dist/$abi/libanet_dpi.so" "$project_dir/app/src/main/jniLibs/$abi/"
    "$ndk_dir/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip" --strip-unneeded "$project_dir/app/src/main/jniLibs/$abi/libanet_mobile.so"
done
cd "$project_dir"
./gradlew :app:assembleDebug
cp app/build/outputs/apk/debug/app-debug.apk "dist/1.0.$build_number/anet-1.0.$build_number.apk"
