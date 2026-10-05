#!/usr/bin/env bash
# Build the dashhome Android debug APK inside Docker - no Android SDK or
# JDK on the host, only a Docker daemon.
#
#   Usage:     ./android/scripts/build-apk-docker.sh   (from the repo root)
#   Output:    dist/dashhome-android-debug.apk
#   Overrides: DASHHOME_DOCKER_IMAGE=<image>   (default dashhome-apk-builder:1)
#              OUT_DIR=<dir>                    (default dist)
#
# The first run downloads the Android SDK (~500 MB) into the image and the
# Gradle distribution via the wrapper; subsequent runs reuse the named
# gradle-cache volume for a fast incremental build. The debug signing
# keystore (~/.android/debug.keystore, AGP's default) is persisted in the
# named keystore volume as well: without it every build container gets a
# freshly generated key, and each APK is signed with a different
# certificate, so on-device updates fail with a signature conflict
# ("package conflicts with an existing package").
set -euo pipefail
# The script lives in <repo root>/android/scripts/; build from the repo root
# (the Docker context), with the Dockerfile referenced under android/.
cd "$(dirname "$0")/../.."

IMAGE="${DASHHOME_DOCKER_IMAGE:-dashhome-apk-builder:1}"
VOLUME="${DASHHOME_GRADLE_VOLUME:-dashhome-gradle-cache}"
KEYSTORE_VOLUME="${DASHHOME_KEYSTORE_VOLUME:-dashhome-android-keystore}"
OUT_DIR="${OUT_DIR:-dist}"
APK_NAME="dashhome-android-debug.apk"

if ! docker info >/dev/null 2>&1; then
  echo "error: cannot reach the Docker daemon (docker info failed)." >&2
  echo "       Start Docker, or on Linux add your user to the 'docker' group." >&2
  exit 1
fi

mkdir -p "$OUT_DIR"
docker volume create "$VOLUME" >/dev/null
# AGP writes the debug signing keystore to ${user.home}/.android/debug.keystore;
# the build containers run as root, so pin that path to the keystore volume
# for stable signatures across builds (see the header note).
docker volume create "$KEYSTORE_VOLUME" >/dev/null

echo ">> Building Docker image $IMAGE (toolchain: JDK 17, Android SDK 35, build-tools 35.0.0)..."
docker build -t "$IMAGE" -f android/Dockerfile .

cid="$(docker create -v "$VOLUME":/home/gradle/.gradle -v "$KEYSTORE_VOLUME":/root/.android "$IMAGE")"
trap 'docker rm -f "$cid" >/dev/null 2>&1 || true' EXIT

echo ">> Running gradle assembleDebug in the container..."
docker start -a "$cid" || true
code="$(docker inspect -f '{{.State.ExitCode}}' "$cid")"
if [ "$code" != "0" ]; then
  echo ">> Gradle build FAILED (exit code $code) - see the build log above." >&2
  exit "$code"
fi

docker cp "$cid:/src/android/app/build/outputs/apk/debug/app-debug.apk" "$OUT_DIR/$APK_NAME"
echo ">> Done. APK: $(pwd)/$OUT_DIR/$APK_NAME"
