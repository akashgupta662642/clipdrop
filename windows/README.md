# ClipDrop for Windows

C# on .NET 10 (LTS), WPF, running as a system-tray app. Windows 10 version 1809 or later.

## Projects

| Project | Target | Role |
|---|---|---|
| `ClipDrop.Core` | `net10.0` | No UI. Protocol codec, identity and TLS, networking, transfers, storage. Platform-specific pieces (such as DPAPI) sit behind interfaces. |
| `ClipDrop.Windows` | `net10.0-windows10.0.17763.0` | WPF shell: tray icon, clipboard monitor, views (MVVM). Builds `ClipDrop.exe`. |
| `ClipDrop.Core.Tests` | `net10.0` | xUnit v3 tests for `ClipDrop.Core`, including the shared golden vectors in `docs/test-vectors`. |

`docs/PROTOCOL.md` is the source of truth. The Android app implements the same protocol independently.

## Build

Requirements: the .NET 10 SDK (`global.json` pins the 10.0 feature bands).

```bash
dotnet build ClipDrop.slnx
dotnet test --solution ClipDrop.slnx
dotnet run --project ClipDrop.Windows
```

Tests run on Microsoft.Testing.Platform, which `global.json` opts into. The .NET 10 SDK
no longer runs xUnit v3 through the older VSTest bridge.

Package versions are managed centrally in `Directory.Packages.props`. Shared compiler
settings, including nullable reference types, warnings as errors and the recommended
analyser set, are in `Directory.Build.props`.
