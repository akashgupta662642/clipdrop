using System.Text.Json;

namespace ClipDrop.Core.Tests.Protocol;

/// <summary>Loads the shared golden vectors from docs/test-vectors (copied next to the test binary).</summary>
internal static class TestVectors
{
    private static readonly Dictionary<string, JsonElement> Cache = [];
    private static readonly Lock CacheLock = new();

    public static JsonElement File(string name)
    {
        lock (CacheLock)
        {
            if (!Cache.TryGetValue(name, out JsonElement root))
            {
                string path = Path.Combine(AppContext.BaseDirectory, "TestVectors", name);
                using JsonDocument document = JsonDocument.Parse(System.IO.File.ReadAllBytes(path));
                root = document.RootElement.Clone();
                Cache[name] = root;
            }

            return root;
        }
    }

    public static JsonElement Vector(string file, string name) =>
        File(file).GetProperty("vectors").EnumerateArray().Single(v => v.GetProperty("name").GetString() == name);

    public static TheoryData<string> Names(string file)
    {
        var names = new TheoryData<string>();
        foreach (JsonElement vector in File(file).GetProperty("vectors").EnumerateArray())
        {
            names.Add(vector.GetProperty("name").GetString()!);
        }

        return names;
    }

    public static string? OptionalString(this JsonElement element, string property) =>
        element.TryGetProperty(property, out JsonElement value) ? value.GetString() : null;
}
