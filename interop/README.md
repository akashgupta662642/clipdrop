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
4. runs `clipdrop-interop ping`, which reconnects as an already-paired device and pings three times.

CI runs the same script on `windows-latest` (`.github/workflows/interop.yml`), so the host side uses SChannel, as it does on a real PC.

## Coming in later milestones

* Exchanging a text clip in both directions (milestone 3).
* Transferring a 50 MB file, dropping the connection mid-transfer, and resuming it (milestone 4).
