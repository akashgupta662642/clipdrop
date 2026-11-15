using System.Security.Cryptography;
using System.Text;
using ClipDrop.Core.Protocol;

namespace ClipDrop.Core.Clipboard;

/// <summary>
/// Remembers the hashes of recent clips so a clip received from a peer is never sent straight back
/// (PROTOCOL.md §11.3). Line endings are normalised to <c>\n</c> first: Windows and Android disagree
/// about CRLF, and a clip must still be recognised after a round trip. Thread-safe.
/// </summary>
public sealed class EchoGuard(int capacity = ProtocolConstants.EchoHistory)
{
    private readonly LinkedList<string> _recent = new();
    private readonly Lock _lock = new();

    public void Remember(string text)
    {
        string hash = HashOf(text);
        lock (_lock)
        {
            _recent.Remove(hash);
            _recent.AddLast(hash);
            while (_recent.Count > capacity)
            {
                _recent.RemoveFirst();
            }
        }
    }

    public bool IsEcho(string text)
    {
        string hash = HashOf(text);
        lock (_lock)
        {
            return _recent.Contains(hash);
        }
    }

    private static string HashOf(string text) =>
        Convert.ToHexStringLower(SHA256.HashData(Encoding.UTF8.GetBytes(text.Replace("\r\n", "\n", StringComparison.Ordinal))));
}

/// <summary>What the local clipboard held when it changed or the user asked to send it.</summary>
public abstract record LocalClip
{
    /// <param name="Text">The clipboard text.</param>
    /// <param name="Excluded">True if the copying app asked to keep it out of clipboard monitors (§11.3).</param>
    public sealed record TextClip(string Text, bool Excluded) : LocalClip;

    /// <summary>An image or other non-text clip; images arrive in a later milestone.</summary>
    public sealed record NotText : LocalClip;

    public sealed record Empty : LocalClip;
}

/// <summary>Why an outgoing clip wasn't sent.</summary>
public enum SkipReason
{
    Empty,
    Excluded,
    Echo,
    TooLarge,
    NotText,
}

/// <summary>The decision for one outgoing clip: a message to send, or why not.</summary>
public sealed record OutgoingClip(ClipText? Message, SkipReason? Skipped)
{
    public static OutgoingClip Send(ClipText message) => new(message, null);

    public static OutgoingClip Skip(SkipReason reason) => new(null, reason);
}

/// <summary>
/// Platform-independent clipboard rules (PROTOCOL.md §11.1, §11.3): what may be sent, and what to
/// remember about what arrives. The Windows shell reads and writes the real clipboard.
/// </summary>
public sealed class ClipboardSync(EchoGuard echoGuard, TimeProvider timeProvider)
{
    public OutgoingClip Prepare(LocalClip clip) => clip switch
    {
        LocalClip.Empty => OutgoingClip.Skip(SkipReason.Empty),
        LocalClip.NotText => OutgoingClip.Skip(SkipReason.NotText),
        LocalClip.TextClip { Text: var text } when string.IsNullOrWhiteSpace(text) => OutgoingClip.Skip(SkipReason.Empty),
        LocalClip.TextClip { Excluded: true } => OutgoingClip.Skip(SkipReason.Excluded),
        LocalClip.TextClip { Text: var text } when echoGuard.IsEcho(text) => OutgoingClip.Skip(SkipReason.Echo),
        LocalClip.TextClip { Text: var text } => Build(text),
        _ => throw new ArgumentOutOfRangeException(nameof(clip)),
    };

    /// <summary>Call when a clip arrives from a peer, before writing it to the local clipboard.</summary>
    public void OnReceived(ClipText clip)
    {
        ArgumentNullException.ThrowIfNull(clip);
        echoGuard.Remember(clip.Text);
    }

    /// <summary>
    /// Call once <paramref name="clip"/> has actually been sent. Remembering it stops loops when another
    /// program copies the same text back onto the clipboard. Not done in <see cref="Prepare"/>: a send
    /// that found no peer must not make the next attempt look like an echo.
    /// </summary>
    public void OnSent(ClipText clip)
    {
        ArgumentNullException.ThrowIfNull(clip);
        echoGuard.Remember(clip.Text);
    }

    private OutgoingClip Build(string text)
    {
        var message = new ClipText(Guid.NewGuid().ToString("D"), text, timeProvider.GetUtcNow().ToUnixTimeMilliseconds());
        return MessageCodec.FitsInFrame(message) ? OutgoingClip.Send(message) : OutgoingClip.Skip(SkipReason.TooLarge);
    }
}

/// <summary>
/// The Windows conventions an app uses to keep a clip away from clipboard monitors and history,
/// as password managers do (PROTOCOL.md §11.3).
/// </summary>
public static class ClipboardExclusion
{
    public const string ExcludeFromMonitorProcessing = "ExcludeClipboardContentFromMonitorProcessing";
    public const string ClipboardViewerIgnore = "Clipboard Viewer Ignore";
    public const string CanIncludeInClipboardHistory = "CanIncludeInClipboardHistory";
    public const string CanUploadToCloudClipboard = "CanUploadToCloudClipboard";

    /// <param name="hasFormat">Whether the clipboard offers a format with this name.</param>
    /// <param name="readDword">The DWORD stored in a format, or null if absent or unreadable.</param>
    public static bool IsExcluded(Func<string, bool> hasFormat, Func<string, int?> readDword)
    {
        ArgumentNullException.ThrowIfNull(hasFormat);
        ArgumentNullException.ThrowIfNull(readDword);
        return hasFormat(ExcludeFromMonitorProcessing)
            || hasFormat(ClipboardViewerIgnore)
            || readDword(CanIncludeInClipboardHistory) == 0
            || readDword(CanUploadToCloudClipboard) == 0;
    }
}
