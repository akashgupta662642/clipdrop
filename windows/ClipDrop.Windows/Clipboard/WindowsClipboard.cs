using System.Buffers.Binary;
using System.IO;
using System.Runtime.InteropServices;
using System.Windows;
using ClipDrop.Core.Clipboard;
using WpfClipboard = System.Windows.Clipboard;

namespace ClipDrop.Windows.Clipboard;

/// <summary>
/// Reads and writes the Windows clipboard on the UI (STA) thread. Another app may briefly hold the
/// clipboard open, so each operation retries up to 5 times, 50 ms apart, on CLIPBRD_E_CANT_OPEN.
/// </summary>
internal sealed class WindowsClipboard
{
    private const int ClipboardCantOpen = unchecked((int)0x800401D0);
    private const int Attempts = 5;
    private static readonly TimeSpan RetryDelay = TimeSpan.FromMilliseconds(50);

    private uint _ownWriteSequence;

    /// <summary>True if the clipboard still holds what this app last wrote, so the change was our own.</summary>
    public bool IsOwnWrite => _ownWriteSequence != 0 && NativeMethods.GetClipboardSequenceNumber() == _ownWriteSequence;

    public static Task<LocalClip> ReadAsync() => WithRetryAsync<LocalClip>(() =>
    {
        IDataObject? data = WpfClipboard.GetDataObject();
        if (data is null || data.GetFormats().Length == 0)
        {
            return new LocalClip.Empty();
        }

        if (!WpfClipboard.ContainsText())
        {
            return new LocalClip.NotText();
        }

        bool excluded = ClipboardExclusion.IsExcluded(data.GetDataPresent, format => ReadDword(data, format));
        return new LocalClip.TextClip(WpfClipboard.GetText(), excluded);
    });

    public async Task WriteTextAsync(string text)
    {
        await WithRetryAsync(() =>
        {
            WpfClipboard.SetText(text);
            return true;
        }).ConfigureAwait(true);
        _ownWriteSequence = NativeMethods.GetClipboardSequenceNumber();
    }

    private static async Task<T> WithRetryAsync<T>(Func<T> operation)
    {
        for (int attempt = 1; ; attempt++)
        {
            try
            {
                return operation();
            }
            catch (COMException e) when (e.HResult == ClipboardCantOpen && attempt < Attempts)
            {
                await Task.Delay(RetryDelay).ConfigureAwait(true); // stay on the STA thread
            }
        }
    }

    /// <summary>The DWORD stored in a registered format such as CanIncludeInClipboardHistory, or null.</summary>
    private static int? ReadDword(IDataObject data, string format)
    {
        if (!data.GetDataPresent(format) || data.GetData(format) is not MemoryStream stream || stream.Length < 4)
        {
            return null;
        }

        return BinaryPrimitives.ReadInt32LittleEndian(stream.ToArray());
    }
}
