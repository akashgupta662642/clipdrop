using System.Windows.Threading;
using ClipDrop.Core.Clipboard;
using ClipDrop.Core.Net;
using ClipDrop.Core.Protocol;
using ClipDrop.Windows.Services;

namespace ClipDrop.Windows.Clipboard;

/// <summary>Result of sending the PC's clipboard, for the tray notification.</summary>
internal sealed record SendSummary(int Recipients, SkipReason? Skipped);

/// <summary>
/// Connects the Windows clipboard to the sessions (PROTOCOL.md §5.4, §5.5):
/// <list type="bullet">
/// <item>a copy on this PC goes to every connected phone, when auto-sync is on;</item>
/// <item>a clip from a phone goes onto the clipboard, with a brief notification.</item>
/// </list>
/// Our own writes are recognised by clipboard sequence number and, as a second guard, by the echo guard.
/// </summary>
internal sealed class ClipboardBridge : IDisposable
{
    private readonly HostRuntime _runtime;
    private readonly Dispatcher _dispatcher;
    private readonly Action<string, string> _notify;
    private readonly ClipboardMonitor _monitor;
    private readonly WindowsClipboard _clipboard = new();
    private readonly ClipboardSync _sync = new(new EchoGuard(), TimeProvider.System);

    /// <param name="notify">Shows a brief notification: title, then message.</param>
    public ClipboardBridge(HostRuntime runtime, Dispatcher dispatcher, Action<string, string> notify)
    {
        _runtime = runtime;
        _dispatcher = dispatcher;
        _notify = notify;
        _monitor = new ClipboardMonitor(dispatcher);
        _monitor.Changed += OnLocalClipboardChanged;
        runtime.Server.MessageReceived += OnMessage;
    }

    /// <summary>Sends the current clipboard now, whatever the auto-sync setting (the tray's Send action).</summary>
    public async Task<SendSummary> SendNowAsync()
    {
        OutgoingClip outgoing = _sync.Prepare(await WindowsClipboard.ReadAsync().ConfigureAwait(true));
        if (outgoing.Message is null)
        {
            return new SendSummary(0, outgoing.Skipped);
        }

        return new SendSummary(await _runtime.Server.SendToAllAsync(outgoing.Message).ConfigureAwait(true), null);
    }

    public void Dispose()
    {
        _runtime.Server.MessageReceived -= OnMessage;
        _monitor.Changed -= OnLocalClipboardChanged;
        _monitor.Dispose();
    }

    private async void OnLocalClipboardChanged(object? sender, EventArgs e)
    {
        // async void: an event handler. Every await is guarded, so nothing escapes to the dispatcher.
        try
        {
            if (_clipboard.IsOwnWrite || !_runtime.Settings.AutoSyncToPhone || _runtime.Server.Sessions.Count == 0)
            {
                return;
            }

            OutgoingClip outgoing = _sync.Prepare(await WindowsClipboard.ReadAsync().ConfigureAwait(true));
            if (outgoing.Message is not null)
            {
                await _runtime.Server.SendToAllAsync(outgoing.Message).ConfigureAwait(true);
            }
        }
        catch (Exception ex) when (ex is System.Runtime.InteropServices.COMException or System.IO.IOException)
        {
            // The clipboard stayed locked, or the phone went away mid-send: skip this copy.
        }
    }

    private void OnMessage(object? sender, SessionMessage e)
    {
        if (e.Message is not ClipText clip)
        {
            return;
        }

        _dispatcher.InvokeAsync(async () =>
        {
            _sync.OnReceived(clip);
            try
            {
                await _clipboard.WriteTextAsync(clip.Text).ConfigureAwait(true);
                _notify("Clipboard updated", $"Copied from {e.Session.Device.Name}");
            }
            catch (System.Runtime.InteropServices.COMException)
            {
                _notify("Clipboard busy", $"Couldn't copy the text from {e.Session.Device.Name}: another app is holding the clipboard.");
            }
        });
    }
}
