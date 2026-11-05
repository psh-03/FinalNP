#!/usr/bin/env bash
set -euo pipefail
cd -- "$(dirname -- "$0")"
mkdir -p build/server build/client dist
if command -v javac >/dev/null 2>&1; then
  compiler=(javac)
else
  compiler=(java com.sun.tools.javac.Main)
fi
"${compiler[@]}" --release 17 -encoding UTF-8 -Xlint:all -d build/server PokerServer/src/poker/server/ServerMain.java
"${compiler[@]}" --release 17 -encoding UTF-8 -Xlint:all -d build/client PokerClient/src/poker/client/ClientMain.java
java sun.tools.jar.Main cfe dist/week1-server.jar poker.server.ServerMain -C build/server .
java sun.tools.jar.Main cfe dist/week1-client.jar poker.client.ClientMain -C build/client .
