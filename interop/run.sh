#!/usr/bin/env bash
# Interop test: the Kotlin client harness (Android engine on a JVM) pairs with the .NET host
# harness (ClipDrop.Core) over TLS on localhost, then reconnects as a paired device and pings.
#
# Usage: interop/run.sh           (needs a JDK 17+ and the .NET 10 SDK)
# A later milestone adds a resumed 50 MB file transfer.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
WORK="$(mktemp -d)"
HOST_LOG="$WORK/host.log"
URI_FILE="$WORK/pair-uri.txt"

echo "==> Building the .NET host harness"
dotnet build "$ROOT/interop/dotnet/ClipDrop.Interop.Host.csproj" -c Release -o "$WORK/host-bin" --nologo -v quiet

echo "==> Building the Kotlin client harness"
(cd "$ROOT/android" && ./gradlew :interop-harness:installDist --quiet)
CLIENT="$ROOT/interop/kotlin/build/install/clipdrop-interop/bin/clipdrop-interop"

echo "==> Starting the host"
dotnet "$WORK/host-bin/clipdrop-interop-host.dll" --state "$WORK/host" --pair-uri-file "$URI_FILE" --lifetime 300 >"$HOST_LOG" 2>&1 &
HOST_PID=$!
cleanup() {
    kill "$HOST_PID" 2>/dev/null || true
    echo "--- host log ---"
    cat "$HOST_LOG" || true
}
trap cleanup EXIT

for _ in $(seq 1 120); do
    [[ -s "$URI_FILE" ]] && break
    kill -0 "$HOST_PID" 2>/dev/null || { echo "host exited early"; exit 1; }
    sleep 0.5
done
[[ -s "$URI_FILE" ]] || { echo "host never opened pairing"; exit 1; }

echo "==> Pairing"
"$CLIENT" pair --state "$WORK/client" --uri-file "$URI_FILE"

echo "==> Reconnecting as a paired device and pinging"
"$CLIENT" ping --state "$WORK/client" --count 3

echo "==> Exchanging a text clip both ways"
"$CLIENT" clip --state "$WORK/client"

grep -q "^PAIRED " "$HOST_LOG" || { echo "host didn't record the pairing"; exit 1; }
grep -q "^CLIP_RECEIVED " "$HOST_LOG" || { echo "host didn't receive the clip"; exit 1; }
echo "==> Interop OK"
