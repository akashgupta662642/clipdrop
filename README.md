# ClipDrop

Sync the clipboard and send files between an Android phone and a Windows PC over your local network. End-to-end encrypted, with no account, cloud server or internet connection.

> **Status:** early development (milestone 3 of 7). A phone and PC pair over TLS by QR code and sync text clips: PC to phone automatically, phone to PC from a tile, the notification or the app. File transfer is next; images come later.

## How it works

* **Pair once.** Scan a QR code shown on the PC. The QR code carries the PC's certificate fingerprint and a one-time token, so the phone knows exactly which PC to trust, and the PC knows the phone saw its screen.
* **PC to phone.** Copy text or an image on the PC and it appears on the phone's clipboard automatically.
* **Phone to PC.** One tap, from a Quick Settings tile, the notification or the app. Android only lets an app read the clipboard while it's in the foreground, so this direction is always a deliberate action.
* **Files and folders.** Send them in either direction, with progress, cancel and resume.

All traffic is TLS with mutual certificate authentication and pinned fingerprints. TLS 1.3 is used where available; Windows 10 falls back to TLS 1.2 with ECDHE + AES-GCM only. [docs/PROTOCOL.md](docs/PROTOCOL.md) specifies the wire protocol that both apps implement.

## Repository layout

| Path | Contents |
|---|---|
| [`android/`](android/) | Kotlin + Jetpack Compose app. `:protocol` and `:engine` are pure Kotlin/JVM modules; `:app` is the Android app. |
| [`windows/`](windows/) | C# / .NET 10 WPF tray app. `ClipDrop.Core` has no UI; `ClipDrop.Windows` is the shell. |
| [`interop/`](interop/) | Command-line harnesses that make the two implementations talk to each other in CI. |
| [`docs/`](docs/) | The protocol specification and the golden test vectors both test suites consume. |

The two platforms share no code. They agree only through `docs/PROTOCOL.md` and the test vectors.

## Building

* **Android:** JDK 17+ and the Android SDK (platform 37). See [android/README.md](android/README.md).
* **Windows:** the .NET 10 SDK. See [windows/README.md](windows/README.md).

## Roadmap

1. **Spec and skeleton.** Protocol spec, both projects building, CI. *(done)*
2. **Protocol core and pairing.** Codec, golden vectors, identity, TLS with pinning, QR pairing, heartbeat. *(done, pending a hardware test)*
3. **Text clipboard,** in both directions.
4. **File transfer,** with resume.
5. **Images, history, devices, settings,** mDNS discovery and reconnect.
6. **Polish:** onboarding, accessibility, dark mode, icons.
7. **Release:** signed APK, Windows installer, documentation.

## Licence

[MIT](LICENSE)
