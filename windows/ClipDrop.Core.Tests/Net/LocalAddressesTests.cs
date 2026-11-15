using System.Net;
using System.Net.NetworkInformation;
using ClipDrop.Core.Net;
using ClipDrop.Core.Storage;

namespace ClipDrop.Core.Tests.Net;

public class LocalAddressesTests
{
    private static LocalAddresses.Adapter Adapter(
        string name,
        string address,
        bool gateway = true,
        NetworkInterfaceType type = NetworkInterfaceType.Ethernet,
        OperationalStatus status = OperationalStatus.Up,
        string? description = null) =>
        new(name, description ?? name, type, status, [IPAddress.Parse(address)], gateway);

    [Fact]
    public void Adapters_with_a_gateway_come_first_and_wifi_before_wired()
    {
        IReadOnlyList<string> ranked = LocalAddresses.Rank(
        [
            Adapter("Ethernet 2", "10.0.0.7", gateway: false),
            Adapter("Ethernet", "192.168.1.30"),
            Adapter("Wi-Fi", "192.168.1.20", type: NetworkInterfaceType.Wireless80211),
        ]);
        Assert.Equal(["192.168.1.20", "192.168.1.30", "10.0.0.7"], ranked);
    }

    [Fact]
    public void Virtual_down_loopback_and_link_local_addresses_are_left_out()
    {
        IReadOnlyList<string> ranked = LocalAddresses.Rank(
        [
            Adapter("vEthernet (WSL)", "172.20.0.1", description: "Hyper-V Virtual Ethernet Adapter"),
            Adapter("VirtualBox Host-Only", "192.168.56.1"),
            Adapter("Ethernet 3", "192.168.9.9", status: OperationalStatus.Down),
            Adapter("Loopback", "127.0.0.1", type: NetworkInterfaceType.Loopback),
            Adapter("Ethernet 4", "169.254.10.10"),
            Adapter("Wi-Fi", "192.168.1.20", type: NetworkInterfaceType.Wireless80211),
        ]);
        Assert.Equal(["192.168.1.20"], ranked);
    }

    [Fact]
    public void Falls_back_to_virtual_adapters_when_nothing_else_exists()
    {
        Assert.Equal(["10.8.0.2"], LocalAddresses.Rank([Adapter("VPN", "10.8.0.2", description: "TAP-Windows Adapter V9")]));
    }

    [Fact]
    public void Lists_at_most_sixteen_addresses()
    {
        var many = Enumerable.Range(1, 20).Select(i => Adapter($"Ethernet {i}", $"10.0.0.{i}")).ToList();
        Assert.Equal(16, LocalAddresses.Rank(many).Count);
    }
}

public sealed class SettingsStoreTests : IDisposable
{
    private readonly string _path = Path.Combine(Path.GetTempPath(), $"clipdrop-settings-{Guid.NewGuid():N}.json");

    public void Dispose() => File.Delete(_path);

    [Fact]
    public void Missing_or_corrupt_files_give_defaults()
    {
        var store = new SettingsStore(_path);
        Assert.Equal(new AppSettings("My PC"), store.Load("My PC"));

        File.WriteAllText(_path, "{ not json");
        Assert.Equal(new AppSettings("My PC"), store.Load("My PC"));
    }

    [Fact]
    public void Saved_settings_load_back()
    {
        var store = new SettingsStore(_path);
        store.Save(new AppSettings("Office PC", 50123));
        Assert.Equal(new AppSettings("Office PC", 50123), store.Load("ignored"));
    }
}
