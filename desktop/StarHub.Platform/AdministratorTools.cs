using System.ComponentModel;
using System.Diagnostics;
using StarHub.Core;

namespace StarHub.Platform;

public enum AdministratorOperation { FlushDnsCache, CheckWindowsImage }

public static class AdministratorTools
{
    public static string Label(AdministratorOperation operation) => operation switch
    {
        AdministratorOperation.FlushDnsCache => "Clear the Windows DNS resolver cache",
        AdministratorOperation.CheckWindowsImage => "Check the Windows image health",
        _ => throw new StarHubException("Choose a supported Windows maintenance operation.")
    };

    private static (string Executable, string[] Arguments) Command(AdministratorOperation operation) => operation switch
    {
        AdministratorOperation.FlushDnsCache => ("ipconfig.exe", ["/flushdns"]),
        AdministratorOperation.CheckWindowsImage => ("dism.exe", ["/Online", "/Cleanup-Image", "/CheckHealth"]),
        _ => throw new StarHubException("Choose a supported Windows maintenance operation.")
    };

    public static async Task<int> RunAsync(AdministratorOperation operation, CancellationToken token = default)
    {
        var (executable, arguments) = Command(operation);
        var system = Environment.SystemDirectory;
        var start = new ProcessStartInfo(Path.Combine(system, executable))
        {
            UseShellExecute = true, Verb = "runas", WorkingDirectory = system
        };
        foreach (var argument in arguments) start.ArgumentList.Add(argument);
        try
        {
            token.ThrowIfCancellationRequested();
            using var process = Process.Start(start) ?? throw new StarHubException("Windows could not launch the maintenance tool.");
            await process.WaitForExitAsync(token).ConfigureAwait(false);
            WindowsHost.Log("desktop.log", $"Windows maintenance {operation} · exit {process.ExitCode}.");
            return process.ExitCode;
        }
        catch (Win32Exception error) when (error.NativeErrorCode == 1223)
        {
            throw new StarHubException("Windows administrator approval was cancelled.");
        }
    }
}
