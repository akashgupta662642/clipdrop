using System.Text;
using System.Text.Json;
using System.Text.Json.Serialization;

namespace ClipDrop.Core.Protocol;

/// <summary>
/// Source-generated System.Text.Json metadata for every JSON message. Reading is strict about JSON
/// kinds (no quoted numbers, required constructor parameters, nullable annotations respected).
/// Writing goes through <see cref="CanonicalStringConverter"/> so strings come out in the canonical
/// form of PROTOCOL.md §8.2.
/// </summary>
[JsonSourceGenerationOptions(
    PropertyNamingPolicy = JsonKnownNamingPolicy.CamelCase,
    DefaultIgnoreCondition = JsonIgnoreCondition.WhenWritingNull,
    NumberHandling = JsonNumberHandling.Strict,
    UnmappedMemberHandling = JsonUnmappedMemberHandling.Skip,
    RespectNullableAnnotations = true,
    RespectRequiredConstructorParameters = true,
    Converters = [typeof(CanonicalStringConverter)])]
[JsonSerializable(typeof(Hello))]
[JsonSerializable(typeof(HelloAck))]
[JsonSerializable(typeof(PairRequest))]
[JsonSerializable(typeof(PairResult))]
[JsonSerializable(typeof(Unpair))]
[JsonSerializable(typeof(Ping))]
[JsonSerializable(typeof(Pong))]
[JsonSerializable(typeof(ClipText))]
[JsonSerializable(typeof(TransferOffer))]
[JsonSerializable(typeof(TransferAccept))]
[JsonSerializable(typeof(TransferReject))]
[JsonSerializable(typeof(TransferEnd))]
[JsonSerializable(typeof(TransferResult))]
[JsonSerializable(typeof(TransferCancel))]
[JsonSerializable(typeof(ErrorMessage))]
internal sealed partial class ProtocolJsonContext : JsonSerializerContext;

/// <summary>
/// Writes strings with the minimal escaping of PROTOCOL.md §8.2. The built-in encoders all escape
/// more than the spec allows (non-ASCII, HTML characters, or characters outside the BMP), so this
/// converter writes the escaped text itself through <see cref="Utf8JsonWriter.WriteRawValue(ReadOnlySpan{byte}, bool)"/>.
/// </summary>
internal sealed class CanonicalStringConverter : JsonConverter<string>
{
    public override string Read(ref Utf8JsonReader reader, Type typeToConvert, JsonSerializerOptions options) =>
        reader.TokenType == JsonTokenType.String
            ? reader.GetString()!
            : throw new JsonException("Expected a JSON string.");

    public override void Write(Utf8JsonWriter writer, string value, JsonSerializerOptions options)
    {
        ArgumentNullException.ThrowIfNull(writer);
        writer.WriteRawValue(Encode(value), skipInputValidation: true);
    }

    /// <summary>The canonical JSON string literal for <paramref name="value"/>, quotes included, as UTF-8.</summary>
    internal static byte[] Encode(string value)
    {
        var builder = new StringBuilder(value.Length + 2);
        builder.Append('"');
        foreach (char c in value)
        {
            switch (c)
            {
                case '"': builder.Append("\\\""); break;
                case '\\': builder.Append("\\\\"); break;
                case '\b': builder.Append("\\b"); break;
                case '\t': builder.Append("\\t"); break;
                case '\n': builder.Append("\\n"); break;
                case '\f': builder.Append("\\f"); break;
                case '\r': builder.Append("\\r"); break;
                case < ' ': builder.Append("\\u00").Append(((int)c).ToString("x2", System.Globalization.CultureInfo.InvariantCulture)); break;
                default: builder.Append(c); break;
            }
        }

        builder.Append('"');
        return Encoding.UTF8.GetBytes(builder.ToString());
    }
}
