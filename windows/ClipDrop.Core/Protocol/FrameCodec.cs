using System.Buffers;
using System.Buffers.Binary;

namespace ClipDrop.Core.Protocol;

/// <summary>Reads and writes <c>[u32 length][u8 type][payload]</c> frames (PROTOCOL.md §7).</summary>
public static class FrameCodec
{
    /// <summary>Reads one frame.</summary>
    /// <exception cref="EndOfStreamException">The stream ended cleanly before a new frame started.</exception>
    /// <exception cref="TruncatedFrameException">The stream ended inside a frame.</exception>
    /// <exception cref="ProtocolException">
    /// <see cref="ErrorCode.FrameTooLarge"/> for a length of 0 or above the limit, detected before any
    /// payload buffer is allocated.
    /// </exception>
    public static async ValueTask<Frame> ReadFrameAsync(Stream stream, CancellationToken cancellationToken = default)
    {
        ArgumentNullException.ThrowIfNull(stream);

        byte[] header = ArrayPool<byte>.Shared.Rent(ProtocolConstants.LengthPrefixSize);
        uint length;
        try
        {
            int read = await stream
                .ReadAtLeastAsync(header.AsMemory(0, ProtocolConstants.LengthPrefixSize), ProtocolConstants.LengthPrefixSize, throwOnEndOfStream: false, cancellationToken)
                .ConfigureAwait(false);
            if (read == 0)
            {
                throw new EndOfStreamException("Stream ended between frames.");
            }

            if (read < ProtocolConstants.LengthPrefixSize)
            {
                throw new TruncatedFrameException("Stream ended inside a frame length.");
            }

            length = BinaryPrimitives.ReadUInt32BigEndian(header);
        }
        finally
        {
            ArrayPool<byte>.Shared.Return(header);
        }

        if (length is < 1 or > ProtocolConstants.MaxFrameLength)
        {
            throw new ProtocolException(ErrorCode.FrameTooLarge, $"Frame length {length} is outside 1..{ProtocolConstants.MaxFrameLength}.");
        }

        // The frame owns its payload, so the body gets an exact-size array rather than a pooled one.
        byte[] body = new byte[length];
        int bodyRead = await stream.ReadAtLeastAsync(body, body.Length, throwOnEndOfStream: false, cancellationToken).ConfigureAwait(false);
        if (bodyRead < body.Length)
        {
            throw new TruncatedFrameException("Stream ended inside a frame body.");
        }

        return new Frame(body[0], body.AsMemory(1));
    }

    /// <summary>Encodes <paramref name="frame"/> as wire bytes, length prefix included.</summary>
    public static byte[] Encode(Frame frame)
    {
        ArgumentNullException.ThrowIfNull(frame);
        byte[] bytes = new byte[ProtocolConstants.LengthPrefixSize + frame.Length];
        WriteTo(frame, bytes);
        return bytes;
    }

    /// <summary>Writes one frame through a pooled buffer.</summary>
    public static async ValueTask WriteFrameAsync(Stream stream, Frame frame, CancellationToken cancellationToken = default)
    {
        ArgumentNullException.ThrowIfNull(stream);
        ArgumentNullException.ThrowIfNull(frame);

        int size = ProtocolConstants.LengthPrefixSize + frame.Length;
        byte[] buffer = ArrayPool<byte>.Shared.Rent(size);
        try
        {
            WriteTo(frame, buffer);
            await stream.WriteAsync(buffer.AsMemory(0, size), cancellationToken).ConfigureAwait(false);
        }
        finally
        {
            ArrayPool<byte>.Shared.Return(buffer);
        }
    }

    private static void WriteTo(Frame frame, Span<byte> destination)
    {
        BinaryPrimitives.WriteUInt32BigEndian(destination, (uint)frame.Length);
        destination[ProtocolConstants.LengthPrefixSize] = frame.TypeCode;
        frame.Payload.Span.CopyTo(destination[(ProtocolConstants.LengthPrefixSize + 1)..]);
    }
}
