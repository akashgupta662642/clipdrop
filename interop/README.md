# Interop harnesses

Two small command-line programs, one per platform. They let CI check that the Kotlin and .NET implementations of [`docs/PROTOCOL.md`](../docs/PROTOCOL.md) work together, not just each on its own.

| Harness | Built from | Role |
|---|---|---|
| [`kotlin/`](kotlin/): `clipdrop-interop` | the Android `:protocol` and `:engine` modules, on a plain JVM | client (phone) |
| [`dotnet/`](dotnet/): `clipdrop-interop-host` | `ClipDrop.Core` | host (PC) |

Each harness runs the same session, pairing and TLS code as its shipping app. Only the identity storage differs: a PKCS#12 file instead of the AndroidKeyStore on the client, and the same DPAPI-protected file on the host.

## Running

```bash
interop/run.sh
```

You need a JDK 17+ and the .NET 10 SDK. On Windows, run it from Git Bash. The script:

1. builds both harnesses;
2. starts the host, which opens pairing mode and writes its `clipdrop://pair` URI to a file;
3. runs `clipdrop-interop pair`, which pairs with the host using that URI and pings it;
4. runs `clipdrop-interop ping`, which reconnects as an already-paired device and pings three times;
5. runs `clipdrop-interop clip`, which sends a clip of awkward text (accents, emoji outside the BMP, CJK, CRLF, quotes and escapes). The host replies with the same text plus a prefix, and the client checks that it came back unchanged.

CI runs the same script on `windows-latest` (`.github/workflows/interop.yml`), so the host side uses SChannel, as it does on a real PC.

## Testing the real Windows app

`clipdrop-interop session` stands in for a phone when you test the tray app by hand:

1. Start the app in developer mode: `ClipDrop.exe --loopback-only`. It listens on 127.0.0.1 only and keeps its state in `%LOCALAPPDATA%\ClipDrop\Developer`, apart from your real pairings.
2. Click **Pair a new device**. The window shows the pairing link as text; save it to a file.
3. Pair: `clipdrop-interop pair --state <dir> --uri-file <file>`.
4. Send a clip and watch for clips copied on the PC: `clipdrop-interop session --state <dir> --send "hello" --listen 60`. Each clip that arrives prints as `RECEIVED <text>`.

This prints clip text to the console. That's fine for test strings, but don't copy anything private while it's listening.

## Coming in later milestones

* Transferring a 50 MB file, dropping the connection mid-transfer, and resuming it (milestone 4).
