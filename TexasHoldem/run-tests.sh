#!/usr/bin/env bash
set -euo pipefail
cd -- "$(dirname -- "$0")"
bash build.sh
mkdir -p build/test-classes
if command -v javac >/dev/null 2>&1; then compiler=(javac); else compiler=(java com.sun.tools.javac.Main); fi
"${compiler[@]}" --release 17 -encoding UTF-8 -cp build/classes -d build/test-classes tests/poker/server/*.java tests/poker/client/*.java
java -cp build/classes:build/test-classes poker.server.SmokeTests
java -Djava.awt.headless=true -cp build/classes:build/test-classes poker.client.RenderPreview
if command -v python3 >/dev/null 2>&1; then python3 tests/network_test.py; else echo "Python 3 missing: optional TCP integration test skipped."; fi
