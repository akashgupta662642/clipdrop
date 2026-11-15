using System.Net;
using System.Net.NetworkInformation;
using System.Net.Sockets;

namespace ClipDrop.Core.Net;

/// <summary>
/// The IPv4 addresses that go in the pairing QR code (PROTOCOL.md §6). A PC often has several
/// adapters and the host can't know which one the phone shares, so it lists the plausible ones,
/// best first: adapters with a default gateway, Wi-Fi before wired, virtual adapters left out.
/// </summary>
public static class LocalAddresses
{
    private static readonly string[] VirtualMarkers =
    [
        "virtual", "hyper-v", "vethernet", "vmware", "virtualbox", "wsl", "docker",
        "loopback", "bluetooth", "tap-", "npcap", "pseudo", "teredo", "isatap",
    ];

    /// <summary>A network adapter, reduced to what ranking needs.</summary>
    public sealed record Adapter(
        string Name,
        string Description,
        NetworkInterfaceType Type,
        OperationalStatus Status,
        IReadOnlyList<IPAddress> Addresses,
        bool HasGateway);

    /// <summary>The candidate addresses of this machine right now.</summary>
    public static IReadOnlyList<string> Current() =>
        Rank(NetworkInterface.GetAllNetworkInterfaces().Select(Describe).ToList());

    /// <summary>Orders and filters adapters' addresses; at most 16, the limit of the pairing URI.</summary>
    public static IReadOnlyList<string> Rank(IReadOnlyList<Adapter> adapters)
    {
        ArgumentNullException.ThrowIfNull(adapters);
        List<Adapter> usable = [.. adapters.Where(a => a.Status == OperationalStatus.Up && a.Type is not (NetworkInterfaceType.Loopback or NetworkInterfaceType.Tunnel))];
        List<Adapter> physical = [.. usable.Where(a => !LooksVirtual(a))];

        // If every adapter looks virtual (some VPN setups), offering something beats offering nothing.
        IEnumerable<Adapter> chosen = (physical.Count > 0 ? physical : usable)
            .OrderByDescending(a => a.HasGateway)
            .ThenBy(a => a.Type == NetworkInterfaceType.Wireless80211 ? 0 : 1);

        return [.. chosen
            .SelectMany(a => a.Addresses)
            .Where(ip => ip.AddressFamily == AddressFamily.InterNetwork && !IPAddress.IsLoopback(ip) && !IsLinkLocal(ip))
            .Select(ip => ip.ToString())
            .Distinct(StringComparer.Ordinal)
            .Take(Protocol.PairingUri.MaxHosts)];
    }

    private static Adapter Describe(NetworkInterface nic)
    {
        IPInterfaceProperties properties = nic.GetIPProperties();
        return new Adapter(
            nic.Name,
            nic.Description,
            nic.NetworkInterfaceType,
            nic.OperationalStatus,
            [.. properties.UnicastAddresses.Select(u => u.Address)],
            properties.GatewayAddresses.Any(g => g.Address.AddressFamily == AddressFamily.InterNetwork && !g.Address.Equals(IPAddress.Any)));
    }

    private static bool LooksVirtual(Adapter adapter) =>
        VirtualMarkers.Any(marker =>
            adapter.Name.Contains(marker, StringComparison.OrdinalIgnoreCase) ||
            adapter.Description.Contains(marker, StringComparison.OrdinalIgnoreCase));

    /// <summary>169.254.0.0/16: self-assigned, never useful to a phone.</summary>
    private static bool IsLinkLocal(IPAddress ip)
    {
        byte[] bytes = ip.GetAddressBytes();
        return bytes[0] == 169 && bytes[1] == 254;
    }
}
