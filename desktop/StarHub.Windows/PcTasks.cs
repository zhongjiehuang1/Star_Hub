using System.Diagnostics;
using System.Runtime.InteropServices;
using Microsoft.UI.Windowing;
using Microsoft.UI.Xaml;
using StarHub.Core;
using StarHub.Platform;

namespace StarHub.Windows;

public sealed partial class MainWindow
{
    private const string LocalPcModel = "Star Hub · Windows";
    private const string PcHelp = """
        **Windows tasks**

        - Open Terminal, Task Manager, or an installed application
        - Read a local file, copy it to another folder, or save a PDF
        - Describe the result you want, and Star Hub will plan and verify the steps
        """;
    private int foregroundOperations;
    private bool showingClosePrompt;

    private void ConfirmClosing(AppWindow sender, AppWindowClosingEventArgs args)
    {
        if (showingClosePrompt)
        {
            args.Cancel = true;
            return;
        }

        bool playback = speechPlayer?.PlaybackSession.PlaybackState is
            global::Windows.Media.Playback.MediaPlaybackState.Playing or global::Windows.Media.Playback.MediaPlaybackState.Buffering;
        bool foreground = request is not null || foregroundOperations > 0 || playback || VoiceBusy;
        bool unsentDraft = !string.IsNullOrWhiteSpace(Composer.Text) || attachment is not null;
        bool background = false;
        try { background = WindowsHost.IsRunning(WindowsHost.WorkerLockName) && store.HasRunningTasks(); }
        catch (Exception error) { WindowsHost.Log("desktop.log", $"Close check: {error.GetType().Name}."); }
        if (!foreground && !unsentDraft && !background) return;

        var messages = new List<string>();
        if (foreground) messages.Add(T("Closing will stop work in this window."));
        if (unsentDraft) messages.Add(T("Your unsent draft will be lost."));
        if (background) messages.Add(T("Scheduled tasks will continue in the background."));
        messages.Add(T("Close Star Hub?"));
        args.Cancel = true;
        showingClosePrompt = true;
        try
        {
            const uint yesNoWarningDefaultNo = 0x00000134;
            args.Cancel = MessageBox(hwnd, string.Join("\n\n", messages), "Star Hub", yesNoWarningDefaultNo) != 6;
        }
        finally { showingClosePrompt = false; }
    }

    private async Task RunPcPlanAsync(string prompt, IReadOnlyList<PcOperation> plan, string? projectId)
    {
        bool showHelp = prompt.Equals("/pc", StringComparison.OrdinalIgnoreCase);
        if (plan.Count == 0 && !showHelp)
            throw new StarHubException(T("This Windows task contains an unsupported step. Try /pc open notepad or /pc 打开任务管理器."));

        var turn = store.StartTurn(conversationId, prompt, LocalPcModel, attachment, projectId, localOnly: true);
        using var diagnostic = DesktopDiagnostic.Start(accountProfile, "computer_plan");
        using var cancellation = new CancellationTokenSource();
        request = cancellation;
        SetSending(true);
        lastDeviceAnalysis = false;
        Welcome.Visibility = Visibility.Collapsed;
        var panel = AddTurn(turn);
        Composer.Text = "";
        SetAttachment(null);
        RefreshHistory();
        ScrollToBottom();
        var results = new List<string>();
        try
        {
            if (showHelp) results.Add(T(PcHelp));
            foreach (var operation in plan)
            {
                cancellation.Token.ThrowIfCancellationRequested();
                Say(T("Running a Windows task…"));
                results.Add(await ExecutePcOperationAsync(operation, cancellation.Token));
                diagnostic?.Step(operation.ToString().ToLowerInvariant(), true);
            }
            turn = turn with { State = TurnState.Completed };
        }
        catch (OperationCanceledException) when (cancellation.IsCancellationRequested)
        {
            turn = turn with { State = TurnState.Cancelled, Detail = T("Remaining Windows steps were cancelled.") };
        }
        catch (Exception error)
        {
            string detail = error is StarHubException ? T(error.Message) : T("Windows could not complete this step.");
            WindowsHost.Log("desktop.log", $"Windows task: {error.GetType().Name} (0x{error.HResult:X8}).");
            diagnostic?.Finish(TurnState.Failed, $"0x{error.HResult:X8}");
            turn = turn with { State = TurnState.Failed, Detail = detail };
        }
        finally
        {
            try
            {
                diagnostic?.Finish(turn.State);
                turn = turn with { Reply = string.Join("\n\n", results) };
                store.FinishTurn(turn);
                ShowResult(panel, turn);
                Say(turn.Detail);
                ScrollToBottom();
            }
            finally
            {
                request = null;
                SetSending(false);
            }
        }
    }

    private async Task<string> ExecutePcOperationAsync(PcOperation operation, CancellationToken token)
    {
        switch (operation)
        {
            case PcOperation.DeviceSnapshot:
                return await Task.Run(WindowsHost.DeviceSnapshot, token);
            case PcOperation.EnableStartup:
            case PcOperation.DisableStartup:
                bool enabled = operation == PcOperation.EnableStartup;
                WindowsHost.SetDesktopStartAtSignIn(enabled);
                if (WindowsHost.DesktopStartsAtSignIn() != enabled)
                    throw new StarHubException("Windows could not save the startup setting.");
                return T(enabled ? "Star Hub will open when you sign in." : "Star Hub will no longer open at sign-in.");
            case PcOperation.FlushDns:
            case PcOperation.CheckImage:
                var maintenance = operation == PcOperation.FlushDns
                    ? AdministratorOperation.FlushDnsCache : AdministratorOperation.CheckWindowsImage;
                ShowAdministratorRequest(maintenance);
                var exitCode = await Task.Run(() => AdministratorTools.RunAsync(maintenance, token), token);
                if (exitCode != 0) throw new StarHubException(string.Format(T("Windows returned exit code {0}."), exitCode));
                return T(operation == PcOperation.FlushDns ? "Windows cleared its DNS cache." : "Windows finished checking the image. Exit code 0.");
            case PcOperation.NetworkSettings:
            case PcOperation.InstalledApps:
                var page = operation == PcOperation.NetworkSettings ? "ms-settings:network" : "ms-settings:appsfeatures";
                if (!await global::Windows.System.Launcher.LaunchUriAsync(new Uri(page)))
                    throw new StarHubException("Windows could not open Settings.");
                return T(operation == PcOperation.NetworkSettings ? "Opened Windows network settings." : "Opened installed apps in Windows Settings.");
            case PcOperation.Notepad:
                NotepadDesktop.OpenNotepad();
                return T("Launched Notepad.");
            case PcOperation.Calculator:
            case PcOperation.Paint:
            case PcOperation.FileExplorer:
            case PcOperation.TaskManager:
            case PcOperation.WindowsSettings:
            case PcOperation.Terminal:
            case PcOperation.WindowsTerminal:
            case PcOperation.PowerShell:
            case PcOperation.CommandPrompt:
                var (app, status) = operation switch
                {
                    PcOperation.Calculator => ("calculator", "Opened Calculator."),
                    PcOperation.Paint => ("paint", "Opened Paint."),
                    PcOperation.FileExplorer => ("file_explorer", "Opened File Explorer."),
                    PcOperation.TaskManager => ("task_manager", "Launched Task Manager."),
                    PcOperation.Terminal => ("terminal", ""),
                    PcOperation.WindowsTerminal => ("windows_terminal", ""),
                    PcOperation.PowerShell => ("powershell", ""),
                    PcOperation.CommandPrompt => ("cmd", ""),
                    _ => ("windows_settings", "Opened Windows Settings.")
                };
                var opened = await WindowsPcTaskHost.OpenAppAsync(app, token);
                if (!opened.Succeeded) throw new StarHubException(WindowsResultText(opened.Summary));
                if (operation == PcOperation.TaskManager || status.Length == 0) return WindowsResultText(opened.Summary);
                return T(status);
            default:
                var (program, argument, result) = operation switch
                {
                    PcOperation.Downloads => ("explorer.exe", "shell:Downloads", "Opened Downloads."),
                    PcOperation.Documents => ("explorer.exe", "shell:Personal", "Opened Documents."),
                    PcOperation.Desktop => ("explorer.exe", "shell:Desktop", "Opened the Desktop folder."),
                    _ => throw new StarHubException("Windows does not support this action.")
                };
                var start = new ProcessStartInfo(Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.Windows), program)) { UseShellExecute = true };
                if (argument.Length > 0) start.ArgumentList.Add(argument);
                using (Process.Start(start)) { }
                return T(result);
        }
    }

    [DllImport("user32.dll", EntryPoint = "MessageBoxW", CharSet = CharSet.Unicode)]
    private static extern int MessageBox(nint owner, string text, string title, uint options);
}
