using System.IO;
using System.Net;
using System.Reflection;
using ClipDrop.Core.Identity;
using ClipDrop.Core.Net;
using ClipDrop.Core.Pairing;
using ClipDrop.Core.Protocol;
using ClipDrop.Core.Storage;

namespace ClipDrop.Windows.Services;

/// <summary>
/// Composition root for the PC side: data folder, identity, stores, pairing and the host server.
/// Everything with logic lives in ClipDrop.Core; this class only wires it together.
/// </summary>
public sealed class HostRuntime : IAsyncDisposable
{
    private HostRuntime(string dataDirectory, SettingsStore settingsStore, AppSettings settings, HostIdentity identity, IPairedDeviceStore devices, PairingCoordinator pairing, HostServer server, bool loopbackOnly)
    {
        LoopbackOnly = loopbackOnly;
        DataDirectory = dataDirectory;
        SettingsStore = settingsStore;
        Settings = settings;
        Identity = identity;
        Devices = devices;
        Pairing = pairing;
        Server = server;
    }

    /// <summary>Developer mode: listening on 127.0.0.1 only.</summary>
    public bool LoopbackOnly { get; }

    public string DataDirectory { get; }

    public SettingsStore SettingsStore { get; }

    public AppSettings Settings { get; private set; }

    public HostIdentity Identity { get; }

    public IPairedDeviceStore Devices { get; }

    public PairingCoordinator Pairing { get; }

    public HostServer Server { get; }

    public static string AppVersion =>
        typeof(HostRuntime).Assembly.GetCustomAttribute<AssemblyInformationalVersionAttribute>()?.InformationalVersion.Split('+')[0] ?? "0.0.0";

    /// <summary>Loads (or creates) everything under %LOCALAPPDATA%\ClipDrop and starts listening.</summary>
    /// <param name="loopbackOnly">Developer option: listen on 127.0.0.1 only (Android emulators reach it as 10.0.2.2).</param>
    public static HostRuntime Start(bool loopbackOnly = false)
    {
        string dataDirectory = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "ClipDrop");
        if (loopbackOnly)
        {
            // Developer mode keeps its own identity and pairings, so emulators never show up as real devices.
            dataDirectory = Path.Combine(dataDirectory, "Developer");
        }

        Directory.CreateDirectory(dataDirectory);

        var settingsStore = new SettingsStore(Path.Combine(dataDirectory, "settings.json"));
        AppSettings settings = settingsStore.Load(Environment.MachineName);
        HostIdentity identity = new IdentityStore(dataDirectory, new DpapiSecretProtector(), TimeProvider.System).LoadOrCreate(settings.DeviceName);
        var devices = new SqlitePairedDeviceStore(Path.Combine(dataDirectory, "clipdrop.db"));
        var pairing = new PairingCoordinator(TimeProvider.System);

        HostRuntime? runtime = null;
        var server = new HostServer(identity, devices, pairing, () => runtime?.Settings.DeviceName ?? settings.DeviceName, AppVersion, TimeProvider.System);
        runtime = new HostRuntime(dataDirectory, settingsStore, settings, identity, devices, pairing, server, loopbackOnly);

        // §4.1: the default port, then the last one we used, then an ephemeral port.
        server.Start(
            settings.LastPort is int last ? [ProtocolConstants.DefaultPort, last] : [ProtocolConstants.DefaultPort],
            loopbackOnly ? IPAddress.Loopback : IPAddress.Any);
        if (settings.LastPort != server.Port)
        {
            runtime.UpdateSettings(settings with { LastPort = server.Port });
        }

        return runtime;
    }

    /// <summary>
    /// Addresses for the pairing QR code (§6). In developer mode they are the ones that reach 127.0.0.1:
    /// 10.0.2.2 from an Android emulator, and 127.0.0.1 itself.
    /// </summary>
    public IReadOnlyList<string> PairingAddresses() => LoopbackOnly ? ["10.0.2.2", "127.0.0.1"] : LocalAddresses.Current();

    public void UpdateSettings(AppSettings settings)
    {
        Settings = settings;
        SettingsStore.Save(settings);
    }

    public async ValueTask DisposeAsync()
    {
        Pairing.Close();
        await Server.DisposeAsync().ConfigureAwait(false);
        Identity.Dispose();
    }
}
