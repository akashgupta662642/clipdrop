using System.Runtime.InteropServices;

namespace ClipDrop.Windows.Clipboard;

/// <summary>The few user32 clipboard functions WPF doesn't wrap.</summary>
internal static partial class NativeMethods
{
    /// <summary>WM_CLIPBOARDUPDATE: sent to every registered listener after the clipboard changes.</summary>
    public const int WmClipboardUpdate = 0x031D;

    /// <summary>HWND_MESSAGE: parent for a message-only window (invisible, no taskbar entry).</summary>
    public static readonly nint HwndMessage = -3;

    [LibraryImport("user32.dll", SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    public static partial bool AddClipboardFormatListener(nint hwnd);

    [LibraryImport("user32.dll", SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    public static partial bool RemoveClipboardFormatListener(nint hwnd);

    /// <summary>Changes every time the clipboard contents change; used to recognise our own writes.</summary>
    [LibraryImport("user32.dll")]
    public static partial uint GetClipboardSequenceNumber();
}
