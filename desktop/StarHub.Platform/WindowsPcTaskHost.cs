using System.ComponentModel;
using System.Diagnostics;
using System.Runtime.InteropServices;
using System.Text;
using System.Text.Json;
using StarHub.Core;

namespace StarHub.Platform;

public sealed class WindowsPcTaskHost(PcWorkspace? workspace, bool maintenanceAllowed, bool localMode = false,
    Action<AdministratorOperation>? beforeMaintenance = null)
{
    public async Task<PcTaskResult> ExecuteAsync(string action, JsonElement args, CancellationToken token)
    {
        string Value(string name) => PcTaskArguments.Text(args, name);
        PcWorkspace Folder() => workspace ?? throw new StarHubException("A local folder is unavailable.");
        string PathValue(string name) => localMode ? WindowsHost.TaskPath(Value(name)) : Value(name);
        string Requested() => args.TryGetProperty("path", out var path) && path.ValueKind == JsonValueKind.String
            ? $" ({path.GetString()})" : "";

        token.ThrowIfCancellationRequested();
        try
        {
            return action switch
            {
                "list_files" => Folder().List(PathValue("path")),
                "find_files" when localMode => Folder().Find(PathValue("path"), Value("name"), token),
                "read_file" or "read_text" when localMode => await Folder().ReadFileAsync(PathValue("path"), token),
                "read_text" => Folder().ReadText(PathValue("path")),
                "file_info" when localMode => Folder().Inspect(PathValue("path")),
                "make_folder" => Folder().MakeFolder(PathValue("path")),
                "write_text" => await Folder().WriteTextAsync(PathValue("path"), PcTaskArguments.Text(args, "text", 48 * 1024), token),
                "replace_text" when localMode => await Folder().ReplaceTextAsync(PathValue("path"),
                    PcTaskArguments.Text(args, "text", 48 * 1024), Value("sha256"), token),
                "copy_file" or "move_file" =>
                    await Folder().TransferAsync(PathValue("path"), PathValue("destination"), action == "move_file", token),
                "open_text" => await LaunchProgramAsync("notepad.exe", Folder().TextFile(PathValue("path")), Value("path"), token),
                "open_file" when localMode => await WindowsPrograms.OpenFileAsync(Folder().LocalPath(PathValue("path"), allowRoot: true), token),
                "open_folder" => OpenExplorer(Folder().Folder(PathValue("path")), PathValue("path")),
                "write_pdf" when localMode => WritePdf(Folder(), PathValue("path"), PcTaskArguments.Text(args, "markdown", 80_000), token),
                "open_app" => await OpenAppAsync(Value("app"), token),
                "find_apps" when localMode => WindowsPrograms.Find(Value("name")),
                "list_windows" when localMode => WindowsDesktop.List(),
                "inspect_window" when localMode => WindowsDesktop.Inspect(Value("window"), token),
                "focus_window" when localMode => WindowsDesktop.Focus(Value("window")),
                "invoke_control" when localMode => WindowsDesktop.Apply(Value("window"), Value("control"), null, token),
                "set_control_text" when localMode => WindowsDesktop.Apply(Value("window"), Value("control"),
                    PcTaskArguments.Text(args, "text", 48 * 1024), token),
                "run_command" when localMode => await WindowsPrograms.RunCommandAsync(Value("shell"),
                    PcTaskArguments.Text(args, "command", 24_000), Folder().Folder("."), token),
                "system_information" => new(true, "Read current Windows device information.", new { device = WindowsHost.DeviceSnapshot() }),
                "windows_maintenance" when maintenanceAllowed => await MaintainAsync(Value("operation"), token),
                _ => new(false, "This Windows operation is outside the scope of the task.")
            };
        }
        catch (StarHubException error) { return new(false, error.Message); }
        catch (DecoderFallbackException) { return new(false, "The file is not readable text in a supported encoding." + Requested()); }
        catch (UnauthorizedAccessException) { return new(false, "Windows denied access to this resource." + Requested()); }
        catch (FileNotFoundException) { return new(false, "Windows could not find the file." + Requested()); }
        catch (DirectoryNotFoundException) { return new(false, "Windows could not find the folder." + Requested()); }
        catch (IOException error) { return new(false, $"Windows could not complete the file operation (error 0x{error.HResult:X8})." + Requested()); }
        catch (COMException error) { return new(false, $"Windows could not access the application control (error 0x{error.HResult:X8})."); }
        catch (Win32Exception error) { return new(false, $"Windows could not start the operation (error {error.NativeErrorCode})."); }
    }

    public static Task<PcTaskResult> OpenAppAsync(string app, CancellationToken token) => app.Trim().ToLowerInvariant() switch
    {
        "notepad" => LaunchProgramAsync("notepad.exe", null, "Notepad", token),
        "paint" => LaunchProgramAsync("mspaint.exe", null, "Paint", token),
        "task_manager" => LaunchProgramAsync("taskmgr.exe", null, "Task Manager", token),
        "calculator" => OpenSettingsPageAsync("ms-calculator:", "Calculator"),
        "file_explorer" => Task.FromResult(OpenExplorer()),
        "windows_settings" => OpenSettingsPageAsync("ms-settings:", "Windows Settings"),
        "network_settings" => OpenSettingsPageAsync("ms-settings:network", "network settings"),
        "installed_apps" => OpenSettingsPageAsync("ms-settings:appsfeatures", "installed apps"),
        "terminal" or "终端" => WindowsPrograms.TerminalAsync(false, token),
        "windows_terminal" or "windows terminal" or "windows 终端" => WindowsPrograms.TerminalAsync(true, token),
        "powershell" or "windows powershell" => WindowsPrograms.ConsoleAsync("powershell", token),
        "cmd" or "command prompt" or "命令提示符" => WindowsPrograms.ConsoleAsync("cmd", token),
        _ => WindowsPrograms.OpenInstalledAsync(app.Trim(), token)
    };

    private static PcTaskResult WritePdf(PcWorkspace workspace, string path, string markdown, CancellationToken token)
    {
        var (directory, name) = LocalPdf.OutputLocation(workspace, path);
        var file = LocalPdf.Write(directory, markdown, markdown.Any(character => character is >= '\u3400' and <= '\u9fff'), token, name);
        return new(true, $"Saved and verified PDF: {file}", new { path = file }, file);
    }

    private static async Task<PcTaskResult> LaunchProgramAsync(string name, string? path, string label, CancellationToken token)
    {
        var start = new ProcessStartInfo(Path.Combine(Environment.SystemDirectory, name)) { UseShellExecute = name == "taskmgr.exe" };
        if (path is not null) start.ArgumentList.Add(path);
        using var process = Process.Start(start);
        if (process is null) return new(false, $"Windows did not start {label}.");

        // Windows 10 exposes a native main window. On other builds a packaged app can take over the launch.
        for (int check = 0; check < 34; check++)
        {
            process.Refresh();
            if (process.MainWindowHandle != nint.Zero) return new(true, $"Verified an open window for {label}.");
            if (process.HasExited) break;
            await Task.Delay(150, token);
        }
        if (name == "notepad.exe" && (path is null ? NotepadDesktop.Windows().Count > 0 :
            NotepadDesktop.Windows().Any(window => window.Title.Contains(Path.GetFileName(path), StringComparison.OrdinalIgnoreCase))))
            return new(true, $"Verified an open Notepad window for {label}.");
        if (process.HasExited && process.ExitCode != 0) return new(false, $"Windows could not open {label}.");
        return new(true, $"Windows accepted the request to open {label}. The window could not be confirmed.");
    }

    private static PcTaskResult OpenExplorer(string? path = null, string? relative = null)
    {
        var start = new ProcessStartInfo(Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.Windows), "explorer.exe"))
            { UseShellExecute = true };
        if (path is not null) start.ArgumentList.Add(path);
        using var process = Process.Start(start);
        return new(true, relative is null ? "Windows accepted the request to open File Explorer."
            : $"Windows accepted the request to show {relative} in File Explorer.");
    }

    private static async Task<PcTaskResult> OpenSettingsPageAsync(string address, string label)
    {
        bool accepted = await global::Windows.System.Launcher.LaunchUriAsync(new Uri(address));
        return new(accepted, accepted ? $"Windows accepted the request to open {label}." : $"Windows could not open {label}.");
    }

    private async Task<PcTaskResult> MaintainAsync(string operation, CancellationToken token)
    {
        var command = operation switch
        {
            "flush_dns" => AdministratorOperation.FlushDnsCache,
            "check_image" => AdministratorOperation.CheckWindowsImage,
            _ => throw new StarHubException("This Windows maintenance operation is not available.")
        };
        beforeMaintenance?.Invoke(command);
        int code = await AdministratorTools.RunAsync(command, token);
        return new(code == 0, $"{AdministratorTools.Label(command)} · Windows exit code {code}.");
    }
}
