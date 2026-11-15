using System.Windows;
using ClipDrop.Windows.Services;
using ClipDrop.Windows.ViewModels;

namespace ClipDrop.Windows;

/// <summary>
/// Starts the host (identity, storage, TLS server) and shows the main window. The tray icon,
/// single-instance handling and clipboard sync arrive in later milestones.
/// </summary>
#pragma warning disable CA1001 // App lives as long as the process; OnExit disposes what it owns.
public partial class App : Application
#pragma warning restore CA1001
{
    private HostRuntime? _runtime;
    private MainViewModel? _viewModel;

    protected override void OnStartup(StartupEventArgs e)
    {
        base.OnStartup(e);
        // --loopback-only: developer option for emulator testing; needs no firewall exception.
        _runtime = HostRuntime.Start(loopbackOnly: e.Args.Contains("--loopback-only", StringComparer.OrdinalIgnoreCase));
        _viewModel = new MainViewModel(_runtime, Dispatcher);
        MainWindow = new MainWindow { DataContext = _viewModel };
        MainWindow.Show();
    }

    protected override void OnExit(ExitEventArgs e)
    {
        _viewModel?.Dispose();
        _runtime?.DisposeAsync().AsTask().GetAwaiter().GetResult();
        base.OnExit(e);
    }
}
