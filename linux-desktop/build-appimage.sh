#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "$0")" && pwd)"
REPO_DIR="$(cd -- "$SCRIPT_DIR/.." && pwd)"
BUILD_DIR="$SCRIPT_DIR/build"
CLASS_DIR="$BUILD_DIR/classes"
INPUT_DIR="$BUILD_DIR/package-input"
JPACKAGE_DIR="$BUILD_DIR/jpackage"
APP_DIR="$BUILD_DIR/AppDir"
DIST_DIR="$SCRIPT_DIR/dist"
APP_NAME="MusicBeatSP"
APP_VERSION="2.1.0"
MAIN_CLASS="com.samuel.musicbeat.desktop.MusicBeatDesktop"
JAVA_BIN="${JAVA_HOME:-}/bin"
if [[ ! -x "$JAVA_BIN/javac" ]]; then JAVA_BIN=""; fi
JAVAC="${JAVA_BIN:+$JAVA_BIN/}javac"
JAR="${JAVA_BIN:+$JAVA_BIN/}jar"
JPACKAGE="${JAVA_BIN:+$JAVA_BIN/}jpackage"

for tool in "$JAVAC" "$JAR" "$JPACKAGE"; do
  if ! command -v "$tool" >/dev/null 2>&1; then
    echo "Missing JDK 17 tool: $tool" >&2
    exit 1
  fi
done

rm -rf "$CLASS_DIR" "$INPUT_DIR" "$JPACKAGE_DIR" "$APP_DIR"
mkdir -p "$CLASS_DIR" "$INPUT_DIR" "$JPACKAGE_DIR" "$APP_DIR/usr/bin" "$APP_DIR/usr/lib/musicbeatsp" \
  "$APP_DIR/usr/share/applications" "$APP_DIR/usr/share/icons/hicolor/256x256/apps" "$DIST_DIR"

mapfile -t SOURCES < <(find "$SCRIPT_DIR/src" -name '*.java' -type f | sort)
"$JAVAC" --release 17 -encoding UTF-8 -d "$CLASS_DIR" "${SOURCES[@]}"
"$JAR" --create --file "$BUILD_DIR/musicbeatsp-linux.jar" \
  --main-class "$MAIN_CLASS" -C "$CLASS_DIR" .
cp "$BUILD_DIR/musicbeatsp-linux.jar" "$INPUT_DIR/"

"$JPACKAGE" --type app-image --name "$APP_NAME" --app-version "$APP_VERSION" \
  --vendor "MusicBeatSP" --input "$INPUT_DIR" --main-jar musicbeatsp-linux.jar \
  --main-class "$MAIN_CLASS" --dest "$JPACKAGE_DIR" \
  --icon "$REPO_DIR/Logo.png" --description "MusicBeatSP offline local music player"

cp -a "$JPACKAGE_DIR/$APP_NAME/." "$APP_DIR/usr/lib/musicbeatsp/"
ln -s ../lib/musicbeatsp/bin/$APP_NAME "$APP_DIR/usr/bin/musicbeatsp"
cp "$REPO_DIR/Logo.png" "$APP_DIR/usr/share/icons/hicolor/256x256/apps/musicbeatsp.png"
cp "$REPO_DIR/Logo.png" "$APP_DIR/musicbeatsp.png"
ln -s usr/share/icons/hicolor/256x256/apps/musicbeatsp.png "$APP_DIR/.DirIcon"

cat > "$APP_DIR/AppRun" <<'APPRUN'
#!/usr/bin/env bash
HERE="$(dirname -- "$(readlink -f -- "$0")")"
exec "$HERE/usr/bin/musicbeatsp" "$@"
APPRUN
chmod +x "$APP_DIR/AppRun"

cat > "$APP_DIR/musicbeat.desktop" <<'DESKTOP'
[Desktop Entry]
Name=MusicBeatSP
Comment=Offline local music library and player
Exec=musicbeatsp %U
Icon=musicbeatsp
Terminal=false
Type=Application
Categories=AudioVideo;Audio;Player;
X-AppImage-Version=2.1.0
DESKTOP

APPIMAGETOOL="${APPIMAGETOOL_PATH:-$(command -v appimagetool || true)}"
if [[ -z "$APPIMAGETOOL" ]]; then
  echo "AppImage tooling is missing. Set APPIMAGETOOL_PATH or install appimagetool." >&2
  echo "The AppDir is ready at: $APP_DIR" >&2
  exit 2
fi

OUTPUT="$DIST_DIR/MusicBeatSP-Linux-x86_64-$APP_VERSION.AppImage"
if [[ "$APPIMAGETOOL" == *.AppImage ]]; then
  ARCH=x86_64 "$APPIMAGETOOL" --appimage-extract-and-run "$APP_DIR" "$OUTPUT"
else
  ARCH=x86_64 "$APPIMAGETOOL" "$APP_DIR" "$OUTPUT"
fi
chmod +x "$OUTPUT"
echo "Created $OUTPUT"
