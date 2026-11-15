using System.Buffers.Binary;
using System.Globalization;

namespace ClipDrop.Core.Protocol;

/// <summary>
/// Binary layout of a TRANSFER_CHUNK payload (PROTOCOL.md §8.1): a 16-byte transfer ID in network
/// byte order, a big-endian u64 offset, then the data.
/// </summary>
public static class ChunkHeader
{
    public const int Size = ProtocolConstants.ChunkHeaderSize;

    /// <summary>Encodes a complete chunk payload: header followed by <paramref name="data"/>.</summary>
    public static byte[] EncodePayload(string transferId, long offset, ReadOnlySpan<byte> data)
    {
        ArgumentNullException.ThrowIfNull(transferId);
        if (!Validation.IsUuid(transferId))
        {
            throw new ArgumentException("transferId must be a lowercase UUID.", nameof(transferId));
        }

        ArgumentOutOfRangeException.ThrowIfNegative(offset);
        ArgumentOutOfRangeException.ThrowIfGreaterThan(offset, ProtocolConstants.MaxJsonInteger);
        if (data.Length is < 1 or > ProtocolConstants.MaxChunkData)
        {
            throw new ArgumentException($"Chunk data must be 1..{ProtocolConstants.MaxChunkData} bytes.", nameof(data));
        }

        byte[] payload = new byte[Size + data.Length];
        // Guid.ToByteArray() is mixed-endian; the wire wants RFC 9562 network byte order.
        Guid.ParseExact(transferId, "D").TryWriteBytes(payload.AsSpan(0, 16), bigEndian: true, out _);
        BinaryPrimitives.WriteInt64BigEndian(payload.AsSpan(16, 8), offset);
        data.CopyTo(payload.AsSpan(Size));
        return payload;
    }

    /// <summary>Decodes a chunk payload, enforcing the decode-time rules of §8.1.</summary>
    /// <remarks>The returned data is a slice of <paramref name="payload"/>, not a copy.</remarks>
    public static TransferChunk DecodePayload(ReadOnlyMemory<byte> payload)
    {
        if (payload.Length < Size + 1 || payload.Length > Size + ProtocolConstants.MaxChunkData)
        {
            throw new ProtocolException(
                ErrorCode.ProtocolError,
                $"TRANSFER_CHUNK payload of {payload.Length} bytes is outside {Size + 1}..{Size + ProtocolConstants.MaxChunkData}.");
        }

        ReadOnlySpan<byte> span = payload.Span;
        string transferId = new Guid(span[..16], bigEndian: true).ToString("D", CultureInfo.InvariantCulture);
        ulong offset = BinaryPrimitives.ReadUInt64BigEndian(span[16..Size]);
        if (offset > ProtocolConstants.MaxJsonInteger)
        {
            throw new ProtocolException(ErrorCode.ProtocolError, "TRANSFER_CHUNK offset is outside 0..2^53-1.");
        }

        return new TransferChunk(transferId, (long)offset, payload[Size..]);
    }
}
