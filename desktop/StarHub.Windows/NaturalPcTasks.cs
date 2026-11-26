using System.Text.Json;
using System.Text.RegularExpressions;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using StarHub.Core;
using StarHub.Platform;

namespace StarHub.Windows;

public sealed partial class MainWindow
{
    private void ShowComputerModeHint(string prompt, string? projectId)
    {
        using var diagnostic = DesktopDiagnostic.Start(accountProfile, "computer_request");
        diagnostic?.Finish(TurnState.Completed, "computer_mode_off");
        var turn = store.StartTurn(conversationId, prompt, LocalPcModel, attachment, projectId, localOnly: true) with
        {
            State = TurnState.Completed,
            Reply = T("Turn on **Operate this PC** next to the attachment button to run this request.")
        };
        Welcome.Visibility = Visibility.Collapsed;
        store.FinishTurn(turn);
        ShowResult(AddTurn(turn), turn);
        Composer.Text = "";
        SetAttachment(null);
        RefreshHistory();
        ScrollToBottom();
        Composer.Focus(FocusState.Programmatic);
    }

    private Task RunDirectPcTaskAsync(string prompt, PcDirectAction action, string? projectId) =>
        RunComputerTaskAsync(prompt, projectId, LocalPcModel, async (_, token, _) =>
        {
            var result = await Task.Run(() => new WindowsPcTaskHost(WindowsHost.TaskWorkspace(), false, localMode: true)
                .ExecuteAsync(action.Tool, action.Arguments, token), token);
            string reply = result.Evidence switch
            {
                PcFileContent file => CodePreview(file.Text),
                PcCommandOutput output => CodePreview(string.Join("\n", new[] { output.Output, output.Error }
                    .Where(text => !string.IsNullOrWhiteSpace(text)))),
                { } evidence when action.Tool == "list_files" => FileList(JsonSerializer.SerializeToElement(evidence)),
                _ => ""
            };
            return new PcTaskRun(MarkdownStep(WindowsResultText(result.Summary)) + reply, [], result.Succeeded,
                Detail: result.Succeeded ? null : "Check the Windows result above.");
        }, action.Tool);

    private Task RunNaturalPcTaskAsync(string prompt, string? projectId, IReadOnlyList<Turn> recent, bool webSearch)
    {
        string goal = PcCommands.HasPrefix(prompt) ? prompt[3..].Trim() : prompt;
        if (goal.Length is 0 or > 6000)
            throw new StarHubException(T("Describe the Windows task in up to 6,000 characters."));
        string model = PcTaskAgent.SelectModel(goal, preference: null);
        bool maintenance = PcCommands.Parse(goal) is { } operations &&
            operations.Any(operation => operation is PcOperation.FlushDns or PcOperation.CheckImage);
        var context = string.Join("\n\n", recent.TakeLast(4).Select(turn =>
        {
            string reply = turn.LocalOnly ? turn.GeneratedPdf ?? "" : turn.Reply;
            return $"User: {ClipContext(turn.Prompt, 900)}\nResult: {ClipContext(reply, 1200)}";
        }));
        return RunComputerTaskAsync(prompt, projectId, $"Windows · {ModelName(model)}", async (_, token, onStep) =>
        {
            string key = MediaKey();
            var host = new WindowsPcTaskHost(WindowsHost.TaskWorkspace(), maintenance, localMode: true,
                beforeMaintenance: operation => DispatcherQueue.TryEnqueue(() => ShowAdministratorRequest(operation)));
            return await new PcTaskAgent(providerClient).RunAsync(key, goal, model, folderGranted: true, maintenance,
                (tool, args, ct) => Task.Run(() => host.ExecuteAsync(tool, args, ct), ct),
                onStep, token, useDrivePaths: true, recent: context, webSearch: webSearch);
        }, "computer_task");
    }

    private async Task RunComputerTaskAsync(string prompt, string? projectId, string model,
        Func<string, CancellationToken, Action<PcTaskStep>, Task<PcTaskRun>> execute, string operation)
    {
        var turn = store.StartTurn(conversationId, prompt, model, attachment, projectId, localOnly: true);
        using var diagnostic = DesktopDiagnostic.Start(accountProfile, operation);
        using var cancellation = new CancellationTokenSource(TimeSpan.FromMinutes(4));
        request = cancellation;
        SetSending(true);
        lastDeviceAnalysis = false;
        Welcome.Visibility = Visibility.Collapsed;
        var panel = AddTurn(turn);
        Composer.Text = "";
        SetAttachment(null);
        RefreshHistory();
        ScrollToBottom();
        Say(T("Running a Windows task…"));

        var steps = new List<PcTaskStep>();
        try
        {
            var result = await execute(turn.Id, cancellation.Token, step =>
            {
                steps.Add(step);
                diagnostic?.Step(step.Tool, step.Succeeded);
                DispatcherQueue.TryEnqueue(() =>
                {
                    if (request != cancellation) return;
                    panel.Children.Add(Label(StepLabel(step)));
                    if (Transcript.ScrollableHeight - Transcript.VerticalOffset < 100) ScrollToBottom();
                });
            });
            bool verified = result.Finished && steps.All(step => step.Succeeded);
            turn = turn with
            {
                Reply = result.Reply, Sources = result.Sources ?? [], LocalWork = result.Work,
                State = verified ? TurnState.Completed : TurnState.Partial,
                Detail = result.Detail is { } detail ? T(detail)
                    : verified ? "" : T("Review the Windows steps for anything that could not be verified.")
            };
            diagnostic?.Finish(turn.State, result.DiagnosticCode);
        }
        catch (OperationCanceledException) when (cancellation.IsCancellationRequested)
        {
            turn = turn with { State = TurnState.Cancelled, Detail = T("Remaining Windows steps were cancelled.") };
        }
        catch (Exception error)
        {
            diagnostic?.Finish(TurnState.Failed, $"0x{error.HResult:X8}");
            turn = turn with { State = TurnState.Failed, Detail = DialogError(error) };
        }
        finally
        {
            try
            {
                diagnostic?.Finish(turn.State);
                string trail = steps.Count == 0 ? "" : $"\n\n**{T("Windows task steps")}**\n" +
                    string.Join("\n", steps.Select(step => "- " + MarkdownStep(StepLabel(step))));
                turn = turn with
                {
                    Reply = (turn.Reply + trail).Trim(),
                    GeneratedPdf = steps.LastOrDefault(step => step.Succeeded && step.Pdf is not null)?.Pdf
                };
                store.FinishTurn(turn);
                ShowResult(panel, turn);
                Say(turn.Detail);
                ScrollToBottom();
            }
            finally
            {
                request = null;
                SetSending(false);
                Composer.Focus(FocusState.Programmatic);
            }
        }
    }

    private void ShowAdministratorRequest(AdministratorOperation operation) =>
        Say(string.Format(T("Windows administrator approval is needed to {0}."), T(AdministratorTools.Label(operation))));

    private string FileList(JsonElement evidence)
    {
        var lines = evidence.GetProperty("entries").EnumerateArray().Select(entry =>
            entry.GetProperty("name").GetString() + (entry.GetProperty("directory").GetBoolean() ? "/" : ""));
        return CodePreview(string.Join("\n", lines)) +
            (evidence.GetProperty("truncated").GetBoolean() ? "\n\n" + T("Showing the first 150 entries.") : "");
    }

    private static string ClipContext(string value, int limit) => value.Length <= limit ? value : value[..limit];

    private static string CodePreview(string text)
    {
        if (string.IsNullOrEmpty(text)) return "";
        int longest = Regex.Matches(text, "`+").Select(match => match.Length).DefaultIfEmpty(0).Max();
        string fence = new('`', Math.Max(3, longest + 1));
        return $"\n\n{fence}\n{text}\n{fence}";
    }

    private string StepLabel(PcTaskStep step) => step.Tool == "report_plan"
        ? T("Plan") + " · " + step.Summary
        : (step.Succeeded ? "✓ " : "× ") + WindowsResultText(step.Summary);

    private string WindowsResultText(string value)
    {
        string translated = T(value);
        foreach (string prefix in new[] { "Read ", "Listed folder: ", "Copied and verified: ", "Moved and verified: ",
            "Created and verified folder: ", "Opened ", "Verified an open window for ", "Started and verified ", "Saved and verified PDF: " })
        {
            if (value.StartsWith(prefix, StringComparison.Ordinal)) return T(prefix) + value[prefix.Length..];
        }
        return translated;
    }

    private static string MarkdownStep(string summary) =>
        summary.Replace("\n", " ").Replace("\r", " ").Replace("\\", "\\\\")
            .Replace("[", "\\[").Replace("]", "\\]").Replace("<", "\\<").Replace(">", "\\>")
            .Replace("`", "\\`").Replace("*", "\\*").Replace("_", "\\_");
}
