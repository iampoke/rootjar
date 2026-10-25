#!/usr/bin/env bash
# Builds build/server.jar (+ test helpers) and runs the unit checks. Needs only a JDK (17+); no Maven, no network.
set -euo pipefail
cd "$(dirname "$0")"
OUT=build
rm -rf "$OUT"; mkdir -p "$OUT/classes" "$OUT/test-classes" "$OUT/dummy-classes"

# Use javac/jar when installed; otherwise fall back to the compiler modules inside the JDK runtime.
if command -v javac >/dev/null 2>&1; then
  JAVAC=(javac); JAR=(jar)
else
  JAVAC=(java --module jdk.compiler/com.sun.tools.javac.Main); JAR=(java --module jdk.jartool/sun.tools.jar.Main)
fi
# -source/-target 17 so the jar runs on Java 17 or newer (the sources use no API newer than 17).
FLAGS=(-source 17 -target 17 -encoding UTF-8 -Xlint:-options)

"${JAVAC[@]}" "${FLAGS[@]}" -d "$OUT/classes" $(find src/main/java -name '*.java')
# Bundle the panel UI inside the jar (Frontend serves it on first run if no index.html sits next to the jar).
cp index.html "$OUT/classes/index.html"
printf 'Main-Class: rootjar.Main\nImplementation-Version: 0.2.0-M3\n' > "$OUT/manifest.txt"
"${JAR[@]}" --create --file "$OUT/server.jar" --manifest "$OUT/manifest.txt" -C "$OUT/classes" .

"${JAVAC[@]}" "${FLAGS[@]}" -cp "$OUT/classes" -d "$OUT/test-classes" $(find src/test/java -name '*.java')
# Windows needs ';' as the classpath separator; everything else uses ':'.
case "$(uname -s)" in
  MINGW*|MSYS*|CYGWIN*) CP_SEP=";" ;;
  *) CP_SEP=":" ;;
esac
java -cp "$OUT/classes$CP_SEP$OUT/test-classes" rootjar.ChecksMain

"${JAVAC[@]}" "${FLAGS[@]}" -d "$OUT/dummy-classes" test-support/DummyServer.java
printf 'Main-Class: DummyServer\n' > "$OUT/dummy-manifest.txt"
"${JAR[@]}" --create --file "$OUT/dummy.jar" --manifest "$OUT/dummy-manifest.txt" -C "$OUT/dummy-classes" .
echo "built: $OUT/server.jar ($(wc -c < "$OUT/server.jar") bytes)"
