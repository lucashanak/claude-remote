#!/usr/bin/env bash
set -euo pipefail

# build-linux.sh — Build a portable Linux app-image for Claude Remote and
# package it as dist/claude-remote-linux-x64.tar.gz.
#
# Requirements:
#   JDK 21 with jpackage. Set JAVA_HOME if not using the default below.

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"

# Default JDK location for the dev box (JDK 21.0.5 Temurin).
# Override by exporting JAVA_HOME before running this script.
: "${JAVA_HOME:=${HOME}/jdks/jdk-21.0.5+11}"

if [[ ! -x "${JAVA_HOME}/bin/jpackage" ]]; then
    echo "ERROR: jpackage not found at ${JAVA_HOME}/bin/jpackage" >&2
    echo "  Export JAVA_HOME pointing to a JDK 21 that includes jpackage." >&2
    exit 1
fi

export PATH="${JAVA_HOME}/bin:${PATH}"
echo "JAVA_HOME=${JAVA_HOME}  (jpackage $(jpackage --version))"

cd "${REPO_ROOT}"

echo "==> :desktopApp:createDistributable"
./gradlew :desktopApp:createDistributable

APP_IMAGE_DIR="${REPO_ROOT}/desktopApp/build/compose/binaries/main/app"
if [[ ! -d "${APP_IMAGE_DIR}" ]]; then
    echo "ERROR: expected output not found: ${APP_IMAGE_DIR}" >&2
    exit 1
fi
echo "App-image: ${APP_IMAGE_DIR}"

mkdir -p "${REPO_ROOT}/dist"
TARBALL="${REPO_ROOT}/dist/claude-remote-linux-x64.tar.gz"
tar -czf "${TARBALL}" -C "${APP_IMAGE_DIR}" .
echo "Artifact:  ${TARBALL}  ($(du -sh "${TARBALL}" | cut -f1))"

echo ""
echo "Next steps — on the Manjaro machine:"
echo "  1. scp dist/claude-remote-linux-x64.tar.gz packaging/manjaro/PKGBUILD packaging/manjaro/claude-remote.desktop user@manjaro:"
echo "  2. cd ~ && makepkg -si"
echo "  3. claude-remote"
