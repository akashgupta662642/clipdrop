using System.Globalization;
using Microsoft.Data.Sqlite;

namespace ClipDrop.Core.Storage;

/// <summary>A device this PC has paired with. Identity is the certificate fingerprint (PROTOCOL.md §3).</summary>
public sealed record PairedDevice(string DeviceId, string Name, string Platform, DateTimeOffset PairedAt, DateTimeOffset? LastSeenAt = null);

/// <summary>Persistent list of paired devices. Implementations are thread-safe.</summary>
public interface IPairedDeviceStore
{
    PairedDevice? Find(string deviceId);

    IReadOnlyList<PairedDevice> All();

    void Upsert(PairedDevice device);

    bool Remove(string deviceId);
}

/// <summary>SQLite-backed <see cref="IPairedDeviceStore"/>. One short-lived pooled connection per call.</summary>
public sealed class SqlitePairedDeviceStore : IPairedDeviceStore
{
    private readonly string _connectionString;

    public SqlitePairedDeviceStore(string databasePath)
    {
        _connectionString = new SqliteConnectionStringBuilder { DataSource = databasePath, Pooling = true }.ToString();
        using SqliteConnection connection = Open();
        using SqliteCommand command = connection.CreateCommand();
        command.CommandText = """
            CREATE TABLE IF NOT EXISTS paired_devices (
                device_id    TEXT PRIMARY KEY NOT NULL,
                name         TEXT NOT NULL,
                platform     TEXT NOT NULL,
                paired_at    INTEGER NOT NULL,
                last_seen_at INTEGER NULL
            );
            """;
        command.ExecuteNonQuery();
    }

    public PairedDevice? Find(string deviceId)
    {
        using SqliteConnection connection = Open();
        using SqliteCommand command = connection.CreateCommand();
        command.CommandText = "SELECT device_id, name, platform, paired_at, last_seen_at FROM paired_devices WHERE device_id = $id";
        command.Parameters.AddWithValue("$id", deviceId);
        using SqliteDataReader reader = command.ExecuteReader();
        return reader.Read() ? Read(reader) : null;
    }

    public IReadOnlyList<PairedDevice> All()
    {
        using SqliteConnection connection = Open();
        using SqliteCommand command = connection.CreateCommand();
        command.CommandText = "SELECT device_id, name, platform, paired_at, last_seen_at FROM paired_devices ORDER BY paired_at";
        using SqliteDataReader reader = command.ExecuteReader();
        var devices = new List<PairedDevice>();
        while (reader.Read())
        {
            devices.Add(Read(reader));
        }

        return devices;
    }

    public void Upsert(PairedDevice device)
    {
        ArgumentNullException.ThrowIfNull(device);
        using SqliteConnection connection = Open();
        using SqliteCommand command = connection.CreateCommand();
        command.CommandText = """
            INSERT INTO paired_devices (device_id, name, platform, paired_at, last_seen_at)
            VALUES ($id, $name, $platform, $pairedAt, $lastSeenAt)
            ON CONFLICT(device_id) DO UPDATE SET
                name = excluded.name, platform = excluded.platform, last_seen_at = excluded.last_seen_at
            """;
        command.Parameters.AddWithValue("$id", device.DeviceId);
        command.Parameters.AddWithValue("$name", device.Name);
        command.Parameters.AddWithValue("$platform", device.Platform);
        command.Parameters.AddWithValue("$pairedAt", device.PairedAt.ToUnixTimeMilliseconds());
        command.Parameters.AddWithValue("$lastSeenAt", (object?)device.LastSeenAt?.ToUnixTimeMilliseconds() ?? DBNull.Value);
        command.ExecuteNonQuery();
    }

    public bool Remove(string deviceId)
    {
        using SqliteConnection connection = Open();
        using SqliteCommand command = connection.CreateCommand();
        command.CommandText = "DELETE FROM paired_devices WHERE device_id = $id";
        command.Parameters.AddWithValue("$id", deviceId);
        return command.ExecuteNonQuery() > 0;
    }

    private SqliteConnection Open()
    {
        var connection = new SqliteConnection(_connectionString);
        connection.Open();
        return connection;
    }

    private static PairedDevice Read(SqliteDataReader reader) => new(
        reader.GetString(0),
        reader.GetString(1),
        reader.GetString(2),
        DateTimeOffset.FromUnixTimeMilliseconds(reader.GetInt64(3)),
        reader.IsDBNull(4) ? null : DateTimeOffset.FromUnixTimeMilliseconds(reader.GetInt64(4)));

    public override string ToString() => string.Create(CultureInfo.InvariantCulture, $"SqlitePairedDeviceStore({_connectionString})");
}
