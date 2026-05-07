#!/usr/bin/env bash
set -euo pipefail

PROJECT_ROOT="$(cd "$(dirname "$0")" && pwd)"
SRC_DIR="$PROJECT_ROOT/src"
BUILD_DIR="$PROJECT_ROOT/build"
CLASSES_DIR="$BUILD_DIR/classes"
JAR_OUT="$BUILD_DIR/staircast-rp.jar"

if [ -f "$PROJECT_ROOT/build.local" ]; then
    # shellcheck disable=SC1091
    source "$PROJECT_ROOT/build.local"
fi

if [ -z "${PZ_DIR:-}" ] || [ ! -f "$PZ_DIR/projectzomboid.jar" ]; then
    echo "[build] ERROR: Project Zomboid install not found. Set PZ_DIR in build.local." >&2
    exit 1
fi

if [ -z "${JDK_DIR:-}" ] || [ ! -x "$JDK_DIR/bin/javac.exe" ]; then
    echo "[build] ERROR: JDK not found. Set JDK_DIR in build.local." >&2
    exit 1
fi

# Abort if PZ is running so the install rm-rf doesn't half-deploy the mod folder.
if [ -z "${SKIP_PZ_CHECK:-}" ] && command -v tasklist >/dev/null 2>&1; then
    if tasklist //FI "IMAGENAME eq ProjectZomboid64.exe" //FO CSV //NH 2>/dev/null | grep -qi ProjectZomboid64; then
        echo "[build] ERROR: Project Zomboid is running. Close it before building." >&2
        echo "        Override with SKIP_PZ_CHECK=1 if you know what you're doing." >&2
        exit 1
    fi
fi

: "${MOD_INSTALL_ROOT:=$USERPROFILE/Zomboid/mods/StaircastRP}"

PZ_JAR="$PZ_DIR/projectzomboid.jar"
ZB_JAR="$PZ_DIR/ZombieBuddy.jar"
JAVAC="$JDK_DIR/bin/javac.exe"
JAR="$JDK_DIR/bin/jar.exe"

rm -rf "$BUILD_DIR"
mkdir -p "$CLASSES_DIR"

echo "[build] Compiling..."
mapfile -t SOURCES < <(find "$SRC_DIR" -name '*.java')

"$JAVAC" \
    --release 17 \
    -classpath "$PZ_JAR;$ZB_JAR" \
    -d "$CLASSES_DIR" \
    "${SOURCES[@]}"

echo "[build] Packaging jar..."
"$JAR" --create --file "$JAR_OUT" -C "$CLASSES_DIR" .

echo "[build] Staging mod directory..."
STAGE="$BUILD_DIR/stage/StaircastRP"
rm -rf "$STAGE"
mkdir -p "$STAGE/42.13/media/java/client"
cp "$PROJECT_ROOT/mod_files/mod.info" "$STAGE/mod.info"
cp "$PROJECT_ROOT/mod_files/42.13/mod.info" "$STAGE/42.13/mod.info"
cp "$JAR_OUT" "$STAGE/42.13/media/java/client/staircast-rp.jar"
mkdir -p "$STAGE/common"

echo "[build] Installing to $MOD_INSTALL_ROOT"
rm -rf "$MOD_INSTALL_ROOT"
mkdir -p "$(dirname "$MOD_INSTALL_ROOT")"
cp -r "$STAGE" "$MOD_INSTALL_ROOT"

echo "[build] Done."
echo "       Jar:     $JAR_OUT"
echo "       Install: $MOD_INSTALL_ROOT"
