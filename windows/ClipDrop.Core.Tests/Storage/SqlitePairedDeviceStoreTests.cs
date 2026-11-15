using ClipDrop.Core.Storage;
using Microsoft.Data.Sqlite;

namespace ClipDrop.Core.Tests.Storage;

public sealed class SqlitePairedDeviceStoreTests : IDisposable
{
    private readonly string _path = Path.Combine(Path.GetTempPath(), $"clipdrop-{Guid.NewGuid():N}.db");
    private readonly DateTimeOffset _pairedAt = DateTimeOffset.FromUnixTimeMilliseconds(1_763_200_000_000);

    public void Dispose()
    {
        SqliteConnection.ClearAllPools();
        File.Delete(_path);
    }

    [Fact]
    public void Stores_finds_lists_and_removes_devices()
    {
        var store = new SqlitePairedDeviceStore(_path);
        var phone = new PairedDevice(new string('A', 43), "Pixel 9", "android", _pairedAt);
        var tablet = new PairedDevice(new string('B', 43), "Tab", "android", _pairedAt.AddMinutes(1));

        store.Upsert(phone);
        store.Upsert(tablet);

        Assert.Equal(phone, store.Find(phone.DeviceId));
        Assert.Equal([phone, tablet], store.All());
        Assert.True(store.Remove(phone.DeviceId));
        Assert.False(store.Remove(phone.DeviceId));
        Assert.Null(store.Find(phone.DeviceId));
    }

    [Fact]
    public void Upsert_updates_name_and_last_seen_but_keeps_the_pairing_time()
    {
        var store = new SqlitePairedDeviceStore(_path);
        var device = new PairedDevice(new string('A', 43), "Old name", "android", _pairedAt);
        store.Upsert(device);
        store.Upsert(device with { Name = "New name", PairedAt = _pairedAt.AddDays(9), LastSeenAt = _pairedAt.AddDays(1) });

        PairedDevice stored = store.Find(device.DeviceId)!;
        Assert.Equal("New name", stored.Name);
        Assert.Equal(_pairedAt, stored.PairedAt);
        Assert.Equal(_pairedAt.AddDays(1), stored.LastSeenAt);
    }

    [Fact]
    public void Data_survives_reopening()
    {
        new SqlitePairedDeviceStore(_path).Upsert(new PairedDevice(new string('C', 43), "PC", "windows", _pairedAt));
        Assert.Single(new SqlitePairedDeviceStore(_path).All());
    }
}
