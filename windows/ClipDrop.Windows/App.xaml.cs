using System.Windows;
using ClipDrop.Core.Clipboard;
using ClipDrop.Windows.Clipboard;
using ClipDrop.Windows.Services;
using ClipDrop.Windows.ViewModels;
using H.NotifyIcon;

namespace ClipDrop.Windows;

/// <summary>
/// A tray app: one instance per user, the window hides instead of closing, and Quit in the tray menu
/// ends the process. Starts the host (identity, storage, TLS server) and clipboard sync.
/// </summary>
#pragma warning disable CA1001 // App lives as long as the process; OnExit disposes what it owns.
public partial class App : Application
#pragma warning restore CA1001
{
    private SingleInstance? _instance;
    private HostRuntime? _runtime;
    private ClipboardBridge? _clipboard;
    private MainViewModel? _viewModel;
    private TaskbarIcon? _tray;

    /// <summary>True once Quit was chosen; until then, closing the window only hides it.</summary>
    public bool IsQuitting { get; private set; }

    protected override void OnStartup(StartupEventArgs e)
    {
        base.OnStartup(e);
        // --loopback-only: developer option for emulator testing; needs no firewall exception.
        bool loopbackOnly = e.Args.Contains("--loopback-only", StringComparer.OrdinalIgnoreCase);

        _instance = SingleInstance.TryAcquire(loopbackOnly ? "ClipDrop.Developer" : "ClipDrop", () => Dispatcher.InvokeAsync(ShowMainWindow));
        if (_instance is null)
        {
            Shutdown(); // Another instance was asked to show its window.
            return;
        }

        _runtime = HostRuntime.Start(loopbackOnly);
        _clipboard = new ClipboardBridge(_runtime, Dispatcher, Notify);
        _viewModel = new MainViewModel(_runtime, Dispatcher, SendClipboardAsync);
        _viewModel.ShowWindowRequested += (_, _) => ShowMainWindow();
        _viewModel.QuitRequested += (_, _) => Quit();
        _viewModel.NotificationRequested += (_, n) => Notify(n.Title, n.Message);

        _tray = (TaskbarIcon)FindResource("TrayIcon");
        _tray.DataContext = _viewModel;
        _tray.ForceCreate(enablesEfficiencyMode: false);

        MainWindow = new MainWindow { DataContext = _viewModel };
        ShowMainWindow();
    }

    protected override void OnExit(ExitEventArgs e)
    {
        _tray?.Dispose();
        _clipboard?.Dispose();
        _viewModel?.Dispose();
        _runtime?.DisposeAsync().AsTask().GetAwaiter().GetResult();
        _instance?.Dispose();
        base.OnExit(e);
    }

    private void ShowMainWindow()
    {
        if (MainWindow is null)
        {
            return;
        }

        MainWindow.Show();
        if (MainWindow.WindowState == WindowState.Minimized)
        {
            MainWindow.WindowState = WindowState.Normal;
        }

        MainWindow.Activate();
    }

    private void Quit()
    {
        IsQuitting = true;
        MainWindow?.Close();
        Shutdown();
    }

    private void Notify(string title, string message) => _tray?.ShowNotification(title, message);

    private async Task<string> SendClipboardAsync()
    {
        SendSummary summary = await _clipboard!.SendNowAsync().ConfigureAwait(true);
        return summary switch
        {
            { Skipped: SkipReason.Empty } => "The clipboard is empty.",
            { Skipped: SkipReason.NotText } => "Only text can be sent for now.",
            { Skipped: SkipReason.Excluded } => "Not sent: the app that copied it asked to keep it private.",
            { Skipped: SkipReason.Echo } => "That came from your phone, so it's already there.",
            { Skipped: SkipReason.TooLarge } => "Too large to send as text (over 1 MB).",
            { Recipients: 0 } => "No phone is connected.",
            { Recipients: 1 } => "Sent to your phone.",
            _ => $"Sent to {summary.Recipients} devices.",
        };
    }
}
