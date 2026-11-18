#!/bin/sh
# 로컬(워크스페이스)에서 APK 빌드 — GitHub Actions 없이 확인용.
# 첫 실행 전에 .toolchain/ 에 JDK 17 + Android SDK(platform-34, build-tools 34.0.0) + Gradle 8.7 이 있어야 한다.
set -e
HERE=$(cd "$(dirname "$0")" && pwd)
T="$HERE/.toolchain"
export JAVA_HOME="$T/jdk-17.0.21+9"
export ANDROID_HOME="$T/sdk"
export ANDROID_SDK_ROOT="$T/sdk"
export GRADLE_USER_HOME="$T/gradle-home"
"$T/gradle-8.7/bin/gradle" -p "$HERE" assembleDebug --no-daemon --console=plain
echo "APK: $HERE/app/build/outputs/apk/debug/app-debug.apk"
