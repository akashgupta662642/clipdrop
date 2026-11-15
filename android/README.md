# ClipDrop for Android

Kotlin, Jetpack Compose (Material 3), coroutines and Flow, Hilt. Min SDK 29 (Android 10), target SDK 37 (Android 17).

## Modules

```
:app ──► :engine ──► :protocol
 │          │            │
 │          │            └─ frames, message types, codec. Pure Kotlin/JVM.
 │          └─ sessions, pairing and transfer state machines, echo guard.
 │             Pure Kotlin/JVM, behind small interfaces for sockets, files and storage.
 └─ Android adapters (AndroidKeyStore, NsdManager, ClipboardManager, MediaStore, Room,
    DataStore), the foreground service and the Compose UI.
```

`:protocol` and `:engine` have no Android imports. They run in plain JVM unit tests, and
the CI interop harness (`/interop`) runs the same code against the Windows implementation,
so the interop test exercises what ships. `docs/PROTOCOL.md` is the source of truth for both.

## Build

Requirements: JDK 17 or newer, and the Android SDK with platform 37 (`platforms;android-37.0`).

```bash
./gradlew build                  # compile, unit tests, lint, debug + release APKs
./gradlew :protocol:test         # protocol unit tests only
./gradlew :app:installDebug      # install on a connected device or emulator
```
