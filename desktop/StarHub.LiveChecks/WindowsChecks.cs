#if WINDOWS
using System.Diagnostics;
using Microsoft.Data.Sqlite;
using StarHub.Core;
using StarHub.Platform;

internal static class WindowsChecks
{
    public static void VerifyDeviceAlert()
    {
        string directory = Path.Combine(Path.GetTempPath(), "starhub-notification-check-" + Guid.NewGuid().ToString("N"));
        try
        {
            var store = new LocalStore(directory);
            var now = DateTimeOffset.UtcNow;
            for (int i = 7; i >= 0; i--)
                store.RecordDeviceReading(new DeviceReading(new DeviceSample(now.AddSeconds(-15 * i), 95, 62),
                    "CPU", "Memory", "Graphics", "Storage", "Network", "Power", "Processes"));
            var alert = DeviceAlerts.Check(store, now, chinese: false)
                ?? throw new StarHubException("A sustained synthetic CPU load did not produce an alert.");
            if (store.LatestDeviceAlert()?.Message != alert.Message || !TaskNotification.PostDeviceAlert(alert with
                { Title = "Star Hub · Test notification", Message = "Windows test fixture. " + alert.Message }))
                throw new StarHubException("The local alert was not saved or accepted by Windows notifications.");
            Console.WriteLine("Windows accepted a local device-performance test notification.");
        }
        finally
        {
            SqliteConnection.ClearAllPools();
            if (Directory.Exists(directory)) Directory.Delete(directory, recursive: true);
        }
    }

    public static async Task VerifyAsync(string key, ProviderClient client)
    {
        using var timeout = new CancellationTokenSource(TimeSpan.FromMinutes(3));
        VerifyCredentialManager();
        await VerifyNotepadAsync(key, client, timeout.Token);
        foreach (var operation in Enum.GetValues<AdministratorOperation>())
        {
            if (await AdministratorTools.RunAsync(operation, timeout.Token) != 0)
                throw new StarHubException($"The Windows administrator operation did not complete: {operation}.");
            Console.WriteLine($"Windows administrator launch passed with process exit 0: {operation}.");
        }
    }

    public static async Task VerifyPcTaskAsync(string key, ProviderClient client)
    {
        string directory = Path.Combine(Path.GetTempPath(), "starhub-pc-task-check-" + Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(directory);
        var runningNotepads = new HashSet<int>();
        foreach (var process in Process.GetProcessesByName("notepad"))
            using (process) runningNotepads.Add(process.Id);
        using var timeout = new CancellationTokenSource(TimeSpan.FromMinutes(4));
        const string sourceText = "Star Hub isolated task fixture. Reference = CITRINE-7192.";
        static bool IsFixtureTitle(string title) => title.StartsWith("记录 - ", StringComparison.Ordinal) ||
            title.StartsWith("记录.txt - ", StringComparison.Ordinal);
        try
        {
            string source = Path.Combine(directory, "draft.txt");
            File.WriteAllText(source, sourceText);
            var host = new WindowsPcTaskHost(new PcWorkspace(directory), maintenanceAllowed: false);
            var agent = new PcTaskAgent(client);
            var goal = """
                帮我把这个文件夹里的 draft.txt 原样复制到 交付/记录.txt。请先建好交付目录，
                保留原稿，然后用记事本打开记录.txt，确认副本内容和原稿一致。
                """;
            var run = await agent.RunAsync(key, goal, ModelCatalog.Luna, true, false, host.ExecuteAsync,
                step => Console.WriteLine($"{step.Tool}: {step.Succeeded} {step.Summary}"), timeout.Token);
            string target = Path.Combine(directory, "交付", "记录.txt");
            if (!run.Finished || !run.Steps.Any(step => step is { Tool: "open_text", Succeeded: true } &&
                    step.Summary.StartsWith("Verified an open", StringComparison.Ordinal)) ||
                !File.Exists(target) || File.ReadAllText(target) != sourceText || File.ReadAllText(source) != sourceText)
                throw new StarHubException("The real Chinese Windows task did not preserve, copy and open the expected file.");
            Console.WriteLine($"Chinese Luna task passed: exact bytes and an open Notepad window verified on {Environment.OSVersion.Version}.");

            var elevatedHost = new WindowsPcTaskHost(null, maintenanceAllowed: true);
            Console.WriteLine("Windows will request administrator permission for the DNS cache test.");
            var maintenance = await agent.RunAsync(key, "请清空本机的 DNS 解析缓存，完成后告诉我执行结果。",
                ModelCatalog.Luna, false, true, elevatedHost.ExecuteAsync,
                step => Console.WriteLine($"{step.Tool}: {step.Succeeded} {step.Summary}"), timeout.Token);
            if (!maintenance.Finished || !maintenance.Steps.Any(step => step is { Tool: "windows_maintenance", Succeeded: true }))
                throw new StarHubException("The Windows administrator task did not complete.");
            Console.WriteLine("Chinese Luna task passed: Windows administrator authorization and a verified process exit code.");
        }
        finally
        {
            foreach (var process in Process.GetProcessesByName("notepad"))
                using (process)
                    if (!runningNotepads.Contains(process.Id) && IsFixtureTitle(process.MainWindowTitle))
                        process.CloseMainWindow();
            if (Directory.Exists(directory)) Directory.Delete(directory, recursive: true);
        }
    }

    private static void VerifyCredentialManager()
    {
        var storage = new WindowsCredentials();
        string target = "StarHub/WindowsValidation/" + Guid.NewGuid().ToString("N");
        const string fixture = "synthetic-credential-for-test";
        try
        {
            storage.Save(target, fixture);
            if (storage.Read(target) != fixture) throw new StarHubException("Windows Credential Manager did not return the test value.");
        }
        finally { storage.Remove(target); }
        if (storage.Read(target) is not null) throw new StarHubException("Windows Credential Manager did not remove the test value.");
        Console.WriteLine("Windows Credential Manager passed: stored, retrieved, and removed a synthetic value.");
    }

    private static async Task VerifyNotepadAsync(string key, ProviderClient client, CancellationToken token)
    {
        var existingProcesses = Process.GetProcessesByName("notepad");
        var known = new HashSet<int>();
        foreach (var process in existingProcesses) { known.Add(process.Id); process.Dispose(); }
        NotepadDesktop.OpenNotepad();
        NotepadWindow? window = null;
        try
        {
            for (int attempt = 0; attempt < 30 && window is null; attempt++)
            {
                window = NotepadDesktop.Windows().FirstOrDefault(item => !known.Contains(item.ProcessId));
                if (window is null) await Task.Delay(400, token);
            }
            if (window is null) throw new StarHubException("The Windows runner did not open a fresh Notepad window.");
            var empty = NotepadDesktop.Capture(window);
            const string baseline = "The window contains only a synthetic Star Hub verification document.";
            var seed = new ComputerProposal("fixture", [new ComputerAction("type", Text: baseline)], "", false);
            NotepadDesktop.Apply(empty, seed);
            var capture = NotepadDesktop.Capture(window);
            if (!capture.EditorText.Contains(baseline, StringComparison.Ordinal))
                throw new StarHubException("Windows did not expose the synthetic Notepad text.");

            try
            {
                NotepadDesktop.Apply(empty, seed);
                throw new StarHubException("A stale capture was incorrectly accepted.");
            }
            catch (StarHubException error) when (error.Message.Contains("changed", StringComparison.Ordinal)) { }

            var session = new ComputerSession(client, key);
            const string expected = "STARHUB-DESKTOP-VALIDATED";
            var result = await session.StartAsync(
                $"In this isolated Notepad window, replace all the unsaved text with exactly {expected}. Leave the text unsaved.", capture.Png, token);
            for (int step = 0; step < 2 && result.ScreenshotOnly; step++)
                result = await session.SubmitScreenshotAsync(capture.Png, token);

            if (!result.CanEdit || !string.Equals(expected, result.Actions.Single(action => action.Type == "type").Text?.Trim(),
                StringComparison.Ordinal))
                throw new StarHubException("OpenAI did not return the expected bounded Notepad edit.");
            NotepadDesktop.Apply(capture, result);
            var updated = NotepadDesktop.Capture(window);
            if (!updated.EditorText.Equals(expected, StringComparison.Ordinal))
                throw new StarHubException("The actual Notepad document did not contain the approved edit.");

            var confirmation = await session.SubmitScreenshotAsync(updated.Png, token);
            for (int step = 0; step < 2 && confirmation.ScreenshotOnly; step++)
                confirmation = await session.SubmitScreenshotAsync(updated.Png, token);
            if (confirmation.CallId is not null || string.IsNullOrWhiteSpace(confirmation.Message))
                throw new StarHubException("OpenAI did not conclude the Notepad task after reviewing its screenshot.");
            Console.WriteLine("OpenAI Computer Use passed: real Windows capture, scoped editor action, updated Notepad text, and screenshot verification.");
        }
        finally
        {
            if (window is not null && !known.Contains(window.ProcessId))
            {
                try { using var owned = Process.GetProcessById(window.ProcessId); owned.Kill(); }
                catch (ArgumentException) { }
            }
        }
    }
}
#endif
