#!/usr/bin/env bash
set -euo pipefail
cd -- "$(dirname -- "$0")"
rm -rf build/classes
mkdir -p build/classes dist
if command -v javac >/dev/null 2>&1; then
  compiler=(javac)
else
  compiler=(java com.sun.tools.javac.Main)
fi
"${compiler[@]}" --release 17 -encoding UTF-8 -Xlint:all -d build/classes \
  PokerCommon/src/poker/common/*.java PokerServer/src/poker/server/*.java PokerClient/src/poker/client/*.java
java sun.tools.jar.Main cfe dist/poker-server.jar poker.server.ServerMain \
  -C build/classes poker/common -C build/classes poker/server
java sun.tools.jar.Main cfe dist/poker-client.jar poker.client.ClientMain \
  -C build/classes poker/common -C build/classes poker/client
echo "Built dist/poker-server.jar and dist/poker-client.jar (Java 17+)."
