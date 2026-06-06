#!/usr/bin/env bash
# Helper for building/installing the AudioScope app entirely inside Docker.
# Your host stays clean — no Android SDK, no Gradle, no adb installed locally.
#
#   ./sq.sh image      Build the toolchain Docker image (one time, ~3 GB)
#   ./sq.sh build      Compile the debug APK -> app/build/outputs/apk/debug/
#   ./sq.sh devices    List adb devices (phone must be plugged in, USB debugging on)
#   ./sq.sh install    Install the built APK onto the connected phone
#   ./sq.sh run        build + install + launch + stream logs
#   ./sq.sh logcat     Stream the app's logs from the phone
#   ./sq.sh shell      Open a shell inside the container
#   ./sq.sh nuke       Remove the image, build cache and gradle volume (full teardown)
set -euo pipefail

IMAGE="sq-audio-build"
GRADLE_VOL="sq-gradle-cache"
ADB_VOL="sq-adb-keys"
PKG="eu.labrago.audioscope"
APK="app/build/outputs/apk/debug/app-debug.apk"
PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# docker run with the project mounted + persistent gradle cache + signing keystore.
# Mounting /root/.android keeps the SAME debug keystore across builds, so rebuilt
# APKs keep a stable signature and `adb install -r` can update in place.
run_build() { # no device needed
  docker run --rm -t \
    -v "${PROJECT_DIR}:/workspace" \
    -v "${GRADLE_VOL}:/root/.gradle" \
    -v "${ADB_VOL}:/root/.android" \
    "${IMAGE}" "$@"
}

# docker run with USB passed through so adb can see the phone
run_usb() {
  docker run --rm -t --privileged \
    -v /dev/bus/usb:/dev/bus/usb \
    -v "${PROJECT_DIR}:/workspace" \
    -v "${GRADLE_VOL}:/root/.gradle" \
    -v "${ADB_VOL}:/root/.android" \
    "${IMAGE}" "$@"
}

case "${1:-help}" in
  image)
    docker build -t "${IMAGE}" "${PROJECT_DIR}"
    ;;
  test)
    run_build gradle --no-daemon test
    ;;
  build)

    run_build gradle --no-daemon assembleDebug
    echo "APK -> ${APK}"
    ;;
  devices)
    run_usb adb devices -l
    ;;
  install)
    run_usb adb install -r "${APK}"
    ;;
  run)
    run_build gradle --no-daemon assembleDebug
    run_usb bash -c "adb install -r '${APK}' \
      && adb shell am start -n '${PKG}/.MainActivity' \
      && adb logcat -c \
      && adb logcat --pid=\$(adb shell pidof -s '${PKG}') AudioScope:V *:S"
    ;;
  logcat)
    run_usb bash -c "adb logcat --pid=\$(adb shell pidof -s '${PKG}') AudioScope:V *:S"
    ;;
  shell)
    run_usb bash
    ;;
  nuke)
    docker rmi "${IMAGE}" 2>/dev/null || true
    docker volume rm "${GRADLE_VOL}" "${ADB_VOL}" 2>/dev/null || true
    docker builder prune -f
    echo "Toolchain removed. Host is clean."
    ;;
  *)
    sed -n '2,14p' "${BASH_SOURCE[0]}"
    ;;
esac
