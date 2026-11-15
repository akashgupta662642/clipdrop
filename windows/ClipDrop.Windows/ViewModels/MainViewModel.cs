using System.Collections.ObjectModel;
using System.Globalization;
using System.Windows.Media;
using System.Windows.Threading;
using ClipDrop.Core.Net;
using ClipDrop.Core.Pairing;
using ClipDrop.Core.Protocol;
using ClipDrop.Core.Storage;
using ClipDrop.Windows.Services;
using CommunityToolkit.Mvvm.ComponentModel;
using CommunityToolkit.Mvvm.Input;

namespace ClipDrop.Windows.ViewModels;

/// <summary>A paired device as the window shows it.</summary>
public sealed partial class DeviceRow(string deviceId, string name) : ObservableObject
{
    public string DeviceId { get; } = deviceId;

    public string Name { get; } = name;

    [ObservableProperty]
    public partial string Status { get; set; } = "Offline";
}

/// <summary>
/// The main window: this PC's identity, paired devices with their live status, and pairing with a
/// QR code (PROTOCOL.md §10.1). Host events arrive on thread-pool threads and are marshalled here.
/// </summary>
public sealed partial class MainViewModel : ObservableObject, IDisposable
{
    private readonly HostRuntime _runtime;
    private readonly Dispatcher _dispatcher;
    private readonly DispatcherTimer _countdown;
    private PairingInvitation? _invitation;

    public MainViewModel(HostRuntime runtime, Dispatcher dispatcher)
    {
        _runtime = runtime;
        _dispatcher = dispatcher;
        _countdown = new DispatcherTimer(TimeSpan.FromSeconds(1), DispatcherPriority.Normal, (_, _) => UpdateCountdown(), dispatcher);

        DeviceName = runtime.Settings.DeviceName;
        IReadOnlyList<string> addresses = runtime.PairingAddresses();
        NetworkSummary = runtime.LoopbackOnly
            ? $"Developer mode: listening on 127.0.0.1 only, port {runtime.Server.Port}"
            : addresses.Count == 0
                ? $"Listening on port {runtime.Server.Port}. No network found: connect this PC to Wi-Fi or Ethernet."
                : $"Listening on {string.Join(", ", addresses)}, port {runtime.Server.Port}";

        runtime.Server.DevicePaired += (_, device) => OnUi(() => OnDevicePaired(device));
        runtime.Server.SessionStarted += (_, session) => OnUi(RefreshDevices);
        runtime.Server.SessionEnded += (_, session) => OnUi(RefreshDevices);
        runtime.Pairing.StateChanged += (_, _) => OnUi(OnPairingStateChanged);
        RefreshDevices();
    }

    public ObservableCollection<DeviceRow> Devices { get; } = [];

    [ObservableProperty]
    public partial string DeviceName { get; set; }

    [ObservableProperty]
    public partial string NetworkSummary { get; set; }

    [ObservableProperty]
    public partial bool HasDevices { get; set; }

    [ObservableProperty]
    public partial bool IsPairing { get; set; }

    [ObservableProperty]
    public partial ImageSource? PairingQrCode { get; set; }

    [ObservableProperty]
    public partial string PairingStatus { get; set; } = string.Empty;

    /// <summary>The pairing link as text, shown only in developer mode for <c>adb</c> testing with an emulator.</summary>
    [ObservableProperty]
    public partial string? DeveloperPairingLink { get; set; }

    [RelayCommand]
    private void PairDevice()
    {
        IReadOnlyList<string> hosts = _runtime.PairingAddresses();
        if (hosts.Count == 0)
        {
            IsPairing = true;
            StopShowingCode("This PC isn't connected to a network. Connect it to the same Wi-Fi as your phone, then try again.");
            return;
        }

        _invitation = _runtime.Pairing.Open();
        var uri = new PairingUri(hosts, _runtime.Server.Port, _runtime.Identity.DeviceId, _invitation.Token, DeviceName);
        PairingQrCode = QrCodes.Render(uri.ToUriString());
        DeveloperPairingLink = _runtime.LoopbackOnly ? uri.ToUriString() : null;
        IsPairing = true;
        UpdateCountdown();
        _countdown.Start();
    }

    [RelayCommand]
    private void ClosePairing()
    {
        _runtime.Pairing.Close();
        StopShowingCode(string.Empty);
        IsPairing = false;
    }

    public void Dispose() => _countdown.Stop();

    private void UpdateCountdown()
    {
        if (_invitation is null)
        {
            return;
        }

        TimeSpan left = _invitation.ExpiresAt - DateTimeOffset.UtcNow;
        if (left <= TimeSpan.Zero)
        {
            // The coordinator still answers a late phone with expired_token for a short grace period.
            StopShowingCode("The code expired. Click Pair device to show a new one.");
            return;
        }

        PairingStatus = string.Create(CultureInfo.InvariantCulture, $"Scan this code with ClipDrop on your phone. It expires in {(int)left.TotalMinutes}:{left.Seconds:00}.");
    }

    private void OnDevicePaired(PairedDevice device)
    {
        StopShowingCode($"Paired with {device.Name}.");
        RefreshDevices();
    }

    private void OnPairingStateChanged()
    {
        // Pairing mode closed without a success while the code was showing: too many wrong codes.
        if (!_runtime.Pairing.IsOpen && _invitation is not null && DateTimeOffset.UtcNow < _invitation.ExpiresAt)
        {
            StopShowingCode("Pairing stopped after too many wrong attempts. Click Pair device to try again.");
        }
    }

    /// <summary>Hides the code (pairing succeeded, expired, failed or was closed) and shows <paramref name="status"/>.</summary>
    private void StopShowingCode(string status)
    {
        _countdown.Stop();
        _invitation = null;
        PairingQrCode = null;
        DeveloperPairingLink = null;
        PairingStatus = status;
    }

    private void RefreshDevices()
    {
        HashSet<string> online = [.. _runtime.Server.Sessions.Select(s => s.Device.DeviceId)];
        Devices.Clear();
        foreach (PairedDevice device in _runtime.Devices.All())
        {
            Devices.Add(new DeviceRow(device.DeviceId, device.Name)
            {
                Status = online.Contains(device.DeviceId)
                    ? "Connected"
                    : device.LastSeenAt is { } seen ? $"Offline · last seen {seen.ToLocalTime():g}" : "Offline",
            });
        }

        HasDevices = Devices.Count > 0;
    }

    private void OnUi(Action action) => _dispatcher.BeginInvoke(action);
}
