#!/usr/bin/env bash
# Build the dashhome Android release APK inside Docker - no Android SDK or
# JDK on the host, only a Docker daemon.
#
#   Usage:     ./scripts/build-apk-docker.sh          (from the repo root)
#   Output:    android/dist/dashhome-android-release.apk
#   Overrides: DASHHOME_DOCKER_IMAGE=<image>   (default dashhome-apk-builder:1)
#              OUT_DIR=<dir>                    (default android/dist)
#              DASHHOME_RELEASE_SIGNING_DIR=<dir>
#                                                 (default $HOME/dashhome-signing)
#
# The first run downloads the Android SDK (~500 MB) into the image and the
# Gradle distribution via the wrapper; subsequent runs reuse the named
# gradle-cache volume for a fast incremental build.
#
# Every published APK is signed with the ONE permanent release key (the
# dashhome-signing directory on the build host - referenced by pointer only,
# the key material never lives in this repo or in the builder image): the
# script mounts the directory read-only at /release and exports
# DASHHOME_RELEASE_KEYSTORE / DASHHOME_RELEASE_KEYSTORE_PROPS at the
# in-container paths; app/build.gradle.kts signs the release build from
# those. Without the directory the build refuses to run - the signature
# must never depend on a regenerated per-build key (a different
# certificate per build makes on-device updates fail with a signature
# conflict, "package conflicts with an existing package", and trips Play
# Protect first-sight warnings on every release).
set -euo pipefail
# The script lives in <repo root>/scripts/; build from the repo root
# (the Docker context), with the Dockerfile referenced under android/.
cd "$(dirname "$0")/.."

IMAGE="${DASHHOME_DOCKER_IMAGE:-dashhome-apk-builder:1}"
VOLUME="${DASHHOME_GRADLE_VOLUME:-android_gradle-cache}"
RELEASE_SIGNING_DIR="${DASHHOME_RELEASE_SIGNING_DIR:-$HOME/dashhome-signing}"
OUT_DIR="${OUT_DIR:-android/dist}"
APK_NAME="dashhome-android-release.apk"

if ! docker info >/dev/null 2>&1; then
  echo "error: cannot reach the Docker daemon (docker info failed)." >&2
  echo "       Start Docker, or on Linux add your user to the 'docker' group." >&2
  exit 1
fi

# The permanent release key must be present: the published APK is signed
# with it and never with a regenerated key (see the header note).
for f in "dashhome-release.jks" "keystore.properties"; do
  if [ ! -f "$RELEASE_SIGNING_DIR/$f" ]; then
    echo "error: release signing key not found: $RELEASE_SIGNING_DIR/$f" >&2
    echo "       Every published APK is signed with the permanent dashhome" >&2
    echo "       release key; it is never regenerated. Restore the signing" >&2
    echo "       directory or point DASHHOME_RELEASE_SIGNING_DIR at it." >&2
    exit 1
  fi
done

mkdir -p "$OUT_DIR"
docker volume create "$VOLUME" >/dev/null

echo ">> Building Docker image $IMAGE (toolchain: JDK 17, Android SDK 35, build-tools 35.0.0)..."
docker build -t "$IMAGE" -f android/Dockerfile .

# The signing dir is mounted read-only at /release; the gradle build reads
# the key + passwords from the exported env vars (in-container paths).
cid="$(docker create \
  -v "$VOLUME":/home/gradle/.gradle \
  -v "$RELEASE_SIGNING_DIR":/release:ro \
  -e DASHHOME_RELEASE_KEYSTORE=/release/dashhome-release.jks \
  -e DASHHOME_RELEASE_KEYSTORE_PROPS=/release/keystore.properties \
  "$IMAGE")"
trap 'docker rm -f "$cid" >/dev/null 2>&1 || true' EXIT

echo ">> Running gradle assembleRelease in the container (signed with the permanent release key)..."
docker start -a "$cid" || true
code="$(docker inspect -f '{{.State.ExitCode}}' "$cid")"
if [ "$code" != "0" ]; then
  echo ">> Gradle build FAILED (exit code $code) - see the build log above." >&2
  exit "$code"
fi

docker cp "$cid:/src/android/app/build/outputs/apk/release/app-release.apk" "$OUT_DIR/$APK_NAME"
echo ">> Done. APK: $(pwd)/$OUT_DIR/$APK_NAME"
