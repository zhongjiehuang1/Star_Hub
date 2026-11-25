using System.Diagnostics;
using Microsoft.UI.Xaml;
using StarHub.Platform;

namespace StarHub.Windows;

public partial class App : Application
{
    private Window? window;
    private FileStream? appLock;

    public App()
    {
        UnhandledException += (_, args) => WindowsHost.Log("desktop.log",
            $"Unhandled {args.Exception?.GetType().Name} (0x{args.Exception?.HResult:X8}).");
        InitializeComponent();
    }

    protected override void OnLaunched(LaunchActivatedEventArgs args)
    {
        appLock = WindowsHost.AcquireLock("desktop.lock");
        if (appLock is null)
        {
            foreach (var process in Process.GetProcessesByName("StarHub"))
            {
                using (process)
                    if (process.Id != Environment.ProcessId && process.MainWindowHandle != 0)
                        TrayIcon.Restore(process.MainWindowHandle);
            }
            Exit();
            return;
        }

        window = new MainWindow();
        window.Closed += (_, _) => appLock.Dispose();
        window.Activate();
    }
}
