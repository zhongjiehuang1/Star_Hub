using System.ComponentModel;
using System.Diagnostics;
using System.Text;
using Microsoft.Win32;
using StarHub.Core;

namespace StarHub.Platform;

internal static class WindowsPrograms
{
    private const string PowerShell = @"WindowsPowerShell\v1.0\powershell.exe";
    private sealed record ProgramEntry(string Name, string Target);
    private static readonly HashSet<string> ExecutableFiles = new(StringComparer.OrdinalIgnoreCase)
        { ".exe", ".com", ".bat", ".cmd", ".msi", ".ps1", ".vbs", ".js", ".lnk", ".url", ".scr" };

    internal static async Task<PcTaskResult> TerminalAsync(bool requireWindowsTerminal, CancellationToken token)
    {
        try
        {
            var windowsTerminal = await LaunchAsync("wt.exe", "Windows Terminal", null, token).ConfigureAwait(false);
            if (windowsTerminal.Succeeded || requireWindowsTerminal) return windowsTerminal;
        }
        catch (Win32Exception error) when (!requireWindowsTerminal && error.NativeErrorCode is 2 or 3 or 1155 or 1920) { }
        return await ConsoleAsync("powershell", token).ConfigureAwait(false);
    }

    internal static Task<PcTaskResult> ConsoleAsync(string shell, CancellationToken token)
    {
        bool powershell = shell == "powershell";
        return LaunchAsync(Path.Combine(Environment.SystemDirectory, powershell ? PowerShell : "cmd.exe"),
            powershell ? "Windows PowerShell" : "Command Prompt", null, token);
    }

    internal static PcTaskResult Find(string query)
    {
        var matches = Installed().Where(entry => entry.Name.Contains(query, StringComparison.OrdinalIgnoreCase))
            .Select(entry => entry.Name).Distinct(StringComparer.OrdinalIgnoreCase).Take(60).ToArray();
        return new(true, $"Found {matches.Length} matching applications in Windows Start.", new { apps = matches });
    }

    internal static Task<PcTaskResult> OpenInstalledAsync(string name, CancellationToken token)
    {
        if (string.IsNullOrWhiteSpace(name))
            return Task.FromResult(new PcTaskResult(false, "Specify the name of the application to open."));
        var matches = Installed().Where(app => app.Name.Contains(name, StringComparison.OrdinalIgnoreCase)).ToArray();
        var exact = matches.FirstOrDefault(app => app.Name.Equals(name, StringComparison.OrdinalIgnoreCase));
        if (exact is not null || matches.Length == 1)
        {
            var entry = exact ?? matches[0];
            return LaunchAsync(entry.Target, entry.Name, null, token);
        }
        return Task.FromResult(matches.Length == 0
            ? new PcTaskResult(false, $"Windows Start did not list an application called {name}.")
            : new PcTaskResult(false, $"Several applications matched {name}. Choose a specific app.",
                new { apps = matches.Select(entry => entry.Name).Distinct().Take(15).ToArray() }));
    }

    internal static async Task<PcTaskResult> OpenFileAsync(string path, CancellationToken token)
    {
        var attributes = File.GetAttributes(path);
        if ((attributes & FileAttributes.Directory) != 0)
            return await LaunchAsync(Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.Windows), "explorer.exe"),
                Path.GetFileName(path), path, token).ConfigureAwait(false);
        if (ExecutableFiles.Contains(Path.GetExtension(path)))
            return new(false, $"This path is an executable or shortcut: {path}. Use an explicit command to run it.");
        return await LaunchAsync(path, Path.GetFileName(path), null, token).ConfigureAwait(false);
    }

    internal static async Task<PcTaskResult> LaunchAsync(string executable, string label, string? argument, CancellationToken token)
    {
        token.ThrowIfCancellationRequested();
        var before = WindowsDesktop.Windows().Select(window => window.Id).ToHashSet();
        var request = new ProcessStartInfo(executable) { UseShellExecute = true };
        if (argument is not null) request.ArgumentList.Add(argument);
        using var process = Process.Start(request);
        string titlePart = Path.GetFileNameWithoutExtension(label);
        for (int check = 0; check < 28; check++)
        {
            token.ThrowIfCancellationRequested();
            var windows = WindowsDesktop.Windows();
            var target = windows.FirstOrDefault(window => (process is not null && window.ProcessId == process.Id) ||
                titlePart.Length > 0 && window.Title.Contains(titlePart, StringComparison.OrdinalIgnoreCase) &&
                (!before.Contains(window.Id) || check >= 10));
            if (target is not null)
                return new(true, $"Verified an open window for {label}.", new { target.Id, target.Title });
            if (process?.HasExited == true && process.ExitCode != 0) break;
            await Task.Delay(150, token).ConfigureAwait(false);
        }
        if (process?.HasExited == true && process.ExitCode != 0)
            return new(false, $"Windows could not open {label} (exit code {process.ExitCode}).");
        return new(false, $"Windows accepted the request to open {label}. The window could not be confirmed.");
    }

    internal static async Task<PcTaskResult> RunCommandAsync(string shell, string command, string directory, CancellationToken token)
    {
        if (string.IsNullOrWhiteSpace(command)) throw new StarHubException("Enter the command to run.");
        bool powershell = shell == "powershell";
        if (!powershell && shell != "cmd") throw new StarHubException("Choose PowerShell or Command Prompt.");
        var start = new ProcessStartInfo(Path.Combine(Environment.SystemDirectory, powershell ? PowerShell : "cmd.exe"))
        {
            WorkingDirectory = directory, UseShellExecute = false, RedirectStandardOutput = true,
            RedirectStandardError = true, CreateNoWindow = true
        };
        if (powershell)
            foreach (var value in new[] { "-NoLogo", "-NoProfile", "-NonInteractive", "-Command", command })
                start.ArgumentList.Add(value);
        else start.Arguments = "/d /c " + command;
        token.ThrowIfCancellationRequested();
        using var process = Process.Start(start) ?? throw new StarHubException("Windows did not start the command.");
        var output = CaptureAsync(process.StandardOutput, token);
        var errors = CaptureAsync(process.StandardError, token);
        try
        {
            await process.WaitForExitAsync(token).ConfigureAwait(false);
            string text = await output.ConfigureAwait(false), error = await errors.ConfigureAwait(false);
            int exitCode = process.ExitCode;
            return new(exitCode == 0, $"{(powershell ? "PowerShell" : "Command Prompt")} · exit {exitCode}",
                new PcCommandOutput(text, error, exitCode));
        }
        catch (OperationCanceledException)
        {
            try { if (!process.HasExited) process.Kill(entireProcessTree: true); }
            catch (Exception error) when (error is InvalidOperationException or Win32Exception) { }
            await ((Task)Task.WhenAll(output, errors)).ConfigureAwait(ConfigureAwaitOptions.SuppressThrowing);
            throw;
        }
    }

    private static async Task<string> CaptureAsync(StreamReader stream, CancellationToken token)
    {
        const int limit = 32_000;
        var result = new StringBuilder();
        char[] buffer = new char[4096];
        int count;
        bool truncated = false;
        while ((count = await stream.ReadAsync(buffer.AsMemory(), token).ConfigureAwait(false)) > 0)
        {
            int keep = Math.Min(count, limit - result.Length);
            result.Append(buffer, 0, keep);
            truncated |= keep != count;
        }
        if (truncated) result.AppendLine().Append("[Output preview]");
        return result.ToString();
    }

    private static IReadOnlyList<ProgramEntry> Installed()
    {
        var apps = new List<ProgramEntry>();
        foreach (var folder in new[] { Environment.SpecialFolder.Programs, Environment.SpecialFolder.CommonPrograms })
        {
            string path = Environment.GetFolderPath(folder);
            if (!Directory.Exists(path)) continue;
            var options = new EnumerationOptions
            {
                RecurseSubdirectories = true, IgnoreInaccessible = true, AttributesToSkip = FileAttributes.ReparsePoint
            };
            apps.AddRange(Directory.EnumerateFiles(path, "*.lnk", options).Select(file => new ProgramEntry(Path.GetFileNameWithoutExtension(file), file)));
        }
        foreach (var hive in new[] { Registry.CurrentUser, Registry.LocalMachine })
        {
            using var appPaths = hive.OpenSubKey(@"Software\Microsoft\Windows\CurrentVersion\App Paths");
            if (appPaths is null) continue;
            foreach (string id in appPaths.GetSubKeyNames())
            {
                using var app = appPaths.OpenSubKey(id);
                if (app?.GetValue(null) is not string file || !Path.IsPathFullyQualified(file) || !File.Exists(file)) continue;
                string? name = FileVersionInfo.GetVersionInfo(file).FileDescription;
                apps.Add(new(string.IsNullOrWhiteSpace(name) ? Path.GetFileNameWithoutExtension(id) : name, file));
                if (!string.Equals(name, Path.GetFileNameWithoutExtension(id), StringComparison.OrdinalIgnoreCase))
                    apps.Add(new(Path.GetFileNameWithoutExtension(id), file));
            }
        }
        return apps.DistinctBy(entry => entry.Name, StringComparer.OrdinalIgnoreCase).ToArray();
    }
}

public sealed record PcCommandOutput(string Output, string Error, int ExitCode);
