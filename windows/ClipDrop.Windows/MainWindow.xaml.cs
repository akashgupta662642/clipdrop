using System.ComponentModel;
using System.Windows;

namespace ClipDrop.Windows;

public partial class MainWindow : Window
{
    public MainWindow()
    {
        InitializeComponent();
    }

    /// <summary>Closing hides the window to the tray; only Quit in the tray menu ends the app.</summary>
    protected override void OnClosing(CancelEventArgs e)
    {
        if (Application.Current is App { IsQuitting: false })
        {
            e.Cancel = true;
            Hide();
        }

        base.OnClosing(e);
    }
}
