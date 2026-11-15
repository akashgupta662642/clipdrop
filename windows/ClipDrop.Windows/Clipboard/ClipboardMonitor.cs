using System.ComponentModel;
using System.Windows.Interop;
using System.Windows.Threading;

namespace ClipDrop.Windows.Clipboard;

/// <summary>
/// Raises <see cref="Changed"/> after the clipboard changes. A hidden message-only window registers
/// with AddClipboardFormatListener and receives WM_CLIPBOARDUPDATE. Updates are debounced by 100 ms,
/// because one copy can raise several (an app may set each format separately).
/// </summary>
internal sealed class ClipboardMonitor : IDisposable
{
    private static readonly TimeSpan Debounce = TimeSpan.FromMilliseconds(100);
    private readonly HwndSource _window;
    private readonly DispatcherTimer _debounce;

    public ClipboardMonitor(Dispatcher dispatcher)
    {
        _window = new HwndSource(new HwndSourceParameters("ClipDrop clipboard monitor")
        {
            ParentWindow = NativeMethods.HwndMessage,
            WindowStyle = 0,
        });
        _window.AddHook(WndProc);
        if (!NativeMethods.AddClipboardFormatListener(_window.Handle))
        {
            throw new Win32Exception();
        }

        _debounce = new DispatcherTimer(DispatcherPriority.Normal, dispatcher) { Interval = Debounce };
        _debounce.Tick += (_, _) =>
        {
            _debounce.Stop();
            Changed?.Invoke(this, EventArgs.Empty);
        };
    }

    /// <summary>Raised on the UI thread, at most once per burst of updates.</summary>
    public event EventHandler? Changed;

    public void Dispose()
    {
        _debounce.Stop();
        NativeMethods.RemoveClipboardFormatListener(_window.Handle);
        _window.RemoveHook(WndProc);
        _window.Dispose();
    }

    private nint WndProc(nint hwnd, int msg, nint wParam, nint lParam, ref bool handled)
    {
        if (msg == NativeMethods.WmClipboardUpdate)
        {
            _debounce.Stop();
            _debounce.Start();
            handled = true;
        }

        return 0;
    }
}
