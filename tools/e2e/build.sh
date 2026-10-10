#!/bin/bash
# Builds SiftE2E.jar (test-only) against the local Canvas server jar and the SiftCore jar.
# Usage: tools/e2e/build.sh <server-dir> <SiftCore.jar> <jdk-home>
set -euo pipefail
SERVER=${1:?server dir with versions/ and libraries/}
SIFTCORE=${2:?path to SiftCore jar}
JDK=${3:?jdk 25 home}
HERE=$(cd "$(dirname "$0")" && pwd)
CP="$SIFTCORE:$(find "$SERVER/versions" -name '*.jar' | head -1)"
for j in $(find "$SERVER/libraries" -name '*.jar' | sort); do CP="$CP:$j"; done
OUT=$(mktemp -d)
unset JAVA_TOOL_OPTIONS
"$JDK/bin/javac" --release 25 -proc:none -nowarn -cp "$CP" -d "$OUT" $(find "$HERE/src" -name '*.java')
cp "$HERE/resources/paper-plugin.yml" "$OUT/"
"$JDK/bin/jar" --create --file "$HERE/SiftE2E.jar" -C "$OUT" .
rm -rf "$OUT"
echo "built $HERE/SiftE2E.jar"
