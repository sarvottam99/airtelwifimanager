#!/usr/bin/env sh
set -eu
VERSION=8.9
GRADLE_HOME_DIR="${GRADLE_USER_HOME:-$HOME/.gradle}/bootstrap/gradle-$VERSION"
if [ ! -x "$GRADLE_HOME_DIR/bin/gradle" ]; then
  CACHE_DIR="${GRADLE_USER_HOME:-$HOME/.gradle}/bootstrap"
  mkdir -p "$CACHE_DIR"
  ZIP="$CACHE_DIR/gradle-$VERSION-bin.zip"
  if [ ! -f "$ZIP" ]; then
    if command -v curl >/dev/null 2>&1; then
      curl -fL --retry 3 "https://services.gradle.org/distributions/gradle-$VERSION-bin.zip" -o "$ZIP"
    elif command -v wget >/dev/null 2>&1; then
      wget -O "$ZIP" "https://services.gradle.org/distributions/gradle-$VERSION-bin.zip"
    else
      echo "Install curl or wget to download Gradle $VERSION." >&2
      exit 1
    fi
  fi
  command -v unzip >/dev/null 2>&1 || { echo "Install unzip to extract Gradle." >&2; exit 1; }
  unzip -q -o "$ZIP" -d "$CACHE_DIR"
fi
exec "$GRADLE_HOME_DIR/bin/gradle" "$@"
