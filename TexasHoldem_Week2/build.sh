#!/usr/bin/env sh
set -eu
cd "$(dirname "$0")"
mkdir -p bin dist
javac --release 17 -encoding UTF-8 -d bin src/*.java
jar cfe dist/week2-server.jar ServerMain -C bin .
jar cfe dist/week2-client.jar ClientMain -C bin .
