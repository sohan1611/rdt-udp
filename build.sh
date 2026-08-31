#!/usr/bin/env bash
# Portable build+test, for machines without make (e.g. Git Bash on Windows).
# Equivalent to "make test".
set -euo pipefail
cd "$(dirname "$0")"
mkdir -p build/classes
javac -Xlint:all -d build/classes $(find src/main/java src/test/java -name "*.java")
for t in rdt.PacketTest rdt.ChannelTest rdt.NetEmSmokeTest; do
  echo "== $t"
  java -cp build/classes "$t"
done
