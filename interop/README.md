# Interop harnesses

Two small command-line programs, one per platform. They let CI check that the Kotlin and .NET implementations of [`docs/PROTOCOL.md`](../docs/PROTOCOL.md) work together, not just each on its own.

| Harness | Built from | Runs |
|---|---|---|
| `kotlin/` | the Android `:protocol` and `:engine` modules on a plain JVM | the client (phone) role |
| `dotnet/` | `ClipDrop.Core` | the host (PC) role |

Both harnesses run the same session, pairing and transfer code as the shipping apps. Only the platform adapters differ, for example a PKCS#12 identity instead of the AndroidKeyStore.

The CI interop job, which arrives in milestone 2, runs both harnesses on `localhost`. When complete, it will:

1. pair them;
2. exchange a text clip in both directions;
3. transfer a 50 MB file;
4. drop the connection in the middle of the transfer, then resume it.

The harnesses are added in milestone 2.
