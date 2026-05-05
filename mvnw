#!/bin/sh
# Maven Wrapper startup script for UN*X — bootstraps Maven without a local install
set -e

MAVEN_PROJECTBASEDIR="$(cd "$(dirname "$0")" && pwd)"
WRAPPER_PROPERTIES="$MAVEN_PROJECTBASEDIR/.mvn/wrapper/maven-wrapper.properties"

DISTRIBUTION_URL=$(grep "^distributionUrl" "$WRAPPER_PROPERTIES" | cut -d'=' -f2-)
DIST_ZIP_NAME=$(basename "$DISTRIBUTION_URL")
MAVEN_CACHE="$HOME/.m2/wrapper/dists"
DIST_ZIP="$MAVEN_CACHE/$DIST_ZIP_NAME"

if [ ! -f "$DIST_ZIP" ]; then
  echo "Downloading Maven ($DIST_ZIP_NAME)..."
  mkdir -p "$MAVEN_CACHE"
  if command -v curl > /dev/null; then
    curl -fsSL -o "$DIST_ZIP" "$DISTRIBUTION_URL"
  elif command -v wget > /dev/null; then
    wget -q -O "$DIST_ZIP" "$DISTRIBUTION_URL"
  else
    echo "ERROR: Neither curl nor wget is available." >&2
    exit 1
  fi
  unzip -qo "$DIST_ZIP" -d "$MAVEN_CACHE"
  echo "Maven downloaded."
fi

# The zip extracts a single top-level directory; find it dynamically
MAVEN_HOME=$(unzip -Z1 "$DIST_ZIP" | head -1 | cut -d'/' -f1)
MAVEN_EXE="$MAVEN_CACHE/$MAVEN_HOME/bin/mvn"

if [ ! -f "$MAVEN_EXE" ]; then
  unzip -qo "$DIST_ZIP" -d "$MAVEN_CACHE"
fi

exec "$MAVEN_EXE" "$@"
