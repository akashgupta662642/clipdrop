using System.Text.Json;
using System.Text.Json.Serialization;

namespace ClipDrop.Core.Storage;

/// <summary>User settings. More arrive with the settings screen in a later milestone.</summary>
public sealed record AppSettings(string DeviceName, int? LastPort = null);

/// <summary>Settings as JSON in the app's data folder. A missing or unreadable file means defaults.</summary>
public sealed class SettingsStore(string path)
{
    public AppSettings Load(string defaultDeviceName)
    {
        try
        {
            if (File.Exists(path) && JsonSerializer.Deserialize(File.ReadAllBytes(path), SettingsJsonContext.Default.AppSettings) is { } settings)
            {
                return string.IsNullOrWhiteSpace(settings.DeviceName) ? settings with { DeviceName = defaultDeviceName } : settings;
            }
        }
        catch (JsonException)
        {
            // A corrupt settings file shouldn't stop the app; start from defaults.
        }

        return new AppSettings(defaultDeviceName);
    }

    public void Save(AppSettings settings)
    {
        Directory.CreateDirectory(Path.GetDirectoryName(Path.GetFullPath(path))!);
        string temporary = path + ".tmp";
        File.WriteAllBytes(temporary, JsonSerializer.SerializeToUtf8Bytes(settings, SettingsJsonContext.Default.AppSettings));
        File.Move(temporary, path, overwrite: true);
    }
}

[JsonSourceGenerationOptions(WriteIndented = true, PropertyNamingPolicy = JsonKnownNamingPolicy.CamelCase)]
[JsonSerializable(typeof(AppSettings))]
internal sealed partial class SettingsJsonContext : JsonSerializerContext;
