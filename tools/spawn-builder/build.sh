#!/bin/bash
# Builds SiftBuild.jar (one-off spawn builder) against a Canvas server directory.
# Usage: tools/spawn-builder/build.sh <server-dir> <jdk-home>
set -euo pipefail
SERVER=${1:?server dir with versions/ and libraries/}
JDK=${2:?jdk 25 home}
HERE=$(cd "$(dirname "$0")" && pwd)
CP="$(find "$SERVER/versions" -name '*.jar' | head -1)"
for j in $(find "$SERVER/libraries" -name '*.jar' | sort); do CP="$CP:$j"; done
OUT=$(mktemp -d)
unset JAVA_TOOL_OPTIONS
"$JDK/bin/javac" --release 25 -proc:none -nowarn -cp "$CP" -d "$OUT" $(find "$HERE/src" -name '*.java')
cp "$HERE/resources/paper-plugin.yml" "$OUT/"
"$JDK/bin/jar" --create --file "$HERE/SiftBuild.jar" -C "$OUT" .
rm -rf "$OUT"
echo "built $HERE/SiftBuild.jar"
