using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Automation;
using Microsoft.UI.Xaml.Controls;
using StarHub.Core;
using StarHub.Platform;
using Windows.Storage;
using Windows.System;

namespace StarHub.Windows;

public sealed partial class MainWindow
{
    private async Task RunRecyclePdfAsync(string prompt, string? file, string? projectId)
    {
        await RunComputerTaskAsync(prompt, projectId, LocalPcModel, async (_, token, _) =>
        {
            var result = file is null
                ? new PcTaskResult(false, "This conversation has no saved PDF to move to the Recycle Bin.")
                : await WindowsRecycleBin.RecyclePdfAsync(file, token);
            string name = file is null ? "" : "\n\n" + MarkdownStep(Path.GetFileName(file));
            string code = result.Succeeded ? "" : result.Evidence is int hresult ? $"0x{hresult:X8}"
                : file is null ? "missing_saved_pdf" : "not_recycled";
            return new PcTaskRun(T(result.Summary) + name, [], result.Succeeded, Detail: "", DiagnosticCode: code);
        }, "pdf_recycle");
        if (file is not null && !File.Exists(file))
        {
            PaintTurns();
            ScrollToBottom();
        }
    }

    private async Task RunPdfRequestAsync(string prompt, PdfRequest pdf)
    {
        string SayInDocumentLanguage(string chinese, string english) => pdf.Chinese ? chinese : english;
        var input = attachment;
        bool operate = settings.OperateComputer;
        var turn = store.StartTurn(conversationId, prompt, pdf.NeedsTopic ? PdfRequests.PromptModel : PdfRequests.DocumentModel,
            input, currentProject?.Id, localOnly: true);
        using var diagnostic = DesktopDiagnostic.Start(accountProfile, pdf.NeedsTopic ? "pdf_brief" : "pdf_create");
        using var cancellation = new CancellationTokenSource(TimeSpan.FromMinutes(5));
        request = cancellation;
        SetSending(true);
        lastDeviceAnalysis = false;
        Welcome.Visibility = Visibility.Collapsed;
        var output = AddTurn(turn);
        Composer.Text = "";
        SetAttachment(null);
        RefreshHistory();
        ScrollToBottom();
        string destination = "";
        try
        {
            if (pdf.NeedsTopic)
            {
                turn = turn with
                {
                    State = TurnState.Completed,
                    Reply = SayInDocumentLanguage("PDF 里要放什么内容？也可以说「随便生成一个」。",
                        "What should the PDF contain? You can also ask for any example.")
                };
            }
            else
            {
                string? name = null;
                if (!operate)
                {
                    destination = Path.Combine(store.DirectoryPath, "exports");
                    Directory.CreateDirectory(destination);
                }
                else if (pdf.OutputPath is { } path)
                {
                    destination = path;
                    (destination, name) = LocalPdf.OutputLocation(WindowsHost.TaskWorkspace(), path);
                }
                else destination = WindowsHost.PdfOutputFolder(pdf.Destination);
                if (!Path.IsPathFullyQualified(destination) || !Directory.Exists(destination))
                    throw new StarHubException(SayInDocumentLanguage(
                        "Windows 找不到所选文件夹。请指定桌面、下载或文档文件夹。",
                        "Windows could not find that folder. You can specify Desktop, Downloads, or Documents."));
                if (pdf.PreviousFile is { } source && !File.Exists(source))
                    throw new StarHubException(SayInDocumentLanguage(
                        "之前的 PDF 已移动或删除。你可以告诉我要重新生成什么内容。",
                        "The earlier PDF has been moved or deleted. Tell me what to regenerate."));
                string? markdown = pdf.Markdown;
                if (markdown is null && pdf.PreviousFile is null)
                {
                    Say(T("Drafting the PDF…"));
                    markdown = await engine.DraftPdfAsync(pdf, ChatSettings, SelectedProvider, input, cancellation.Token);
                }
                Say(T("Saving the PDF on this PC…"));
                var saved = await Task.Run(() => pdf.PreviousFile is { } previous
                    ? LocalPdf.Copy(previous, destination, cancellation.Token, name)
                    : LocalPdf.Write(destination, markdown!, pdf.Chinese, cancellation.Token, name), cancellation.Token);
                turn = turn with { State = TurnState.Completed, GeneratedPdf = saved };
            }
        }
        catch (OperationCanceledException) when (cancellation.IsCancellationRequested)
        {
            turn = turn with
            {
                State = TurnState.Cancelled,
                Detail = SayInDocumentLanguage("已停止生成 PDF。", "PDF creation stopped.")
            };
        }
        catch (UnauthorizedAccessException)
        {
            diagnostic?.Finish(TurnState.Failed, "access_denied");
            turn = turn with
            {
                State = TurnState.Failed,
                Detail = string.Format(SayInDocumentLanguage(
                    "Windows 拒绝写入 {0}。可以指定另一个文件夹，例如「存到下载文件夹」。",
                    "Windows denied write access to {0}. You can choose a different folder, such as Downloads."), destination)
            };
        }
        catch (IOException error)
        {
            diagnostic?.Finish(TurnState.Failed, $"0x{error.HResult:X8}");
            WindowsHost.Log("desktop.log", $"PDF write: {error.GetType().Name} (0x{error.HResult:X8}).");
            turn = turn with
            {
                State = TurnState.Failed,
                Detail = string.Format(SayInDocumentLanguage(
                    "Windows 无法将 PDF 保存到 {0}。请检查文件夹是否可用，或指定另一个位置。",
                    "Windows could not save the PDF to {0}. Check the folder or choose another location."), destination)
            };
        }
        catch (Exception error)
        {
            diagnostic?.Finish(TurnState.Failed, $"0x{error.HResult:X8}");
            string message = error is StarHubException ? T(error.Message) : SayInDocumentLanguage(
                "PDF 生成失败，文件还未保存。", "PDF creation failed before the file was saved.");
            WindowsHost.Log("desktop.log", $"PDF creation: {error.GetType().Name} (0x{error.HResult:X8}).");
            turn = turn with { State = TurnState.Failed, Detail = message };
        }
        finally
        {
            try
            {
                diagnostic?.Finish(turn.State);
                store.FinishTurn(turn);
                ShowResult(output, turn);
                Say("");
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

    private void ShowPdfFile(StackPanel panel, string file)
    {
        var name = Label(Path.GetFileName(file));
        AutomationProperties.SetAutomationId(name, "GeneratedPdfName");
        var location = Label(file);
        location.Opacity = 0.8;
        location.IsTextSelectionEnabled = true;
        bool export = string.Equals(Path.GetDirectoryName(file), Path.Combine(store.DirectoryPath, "exports"), StringComparison.OrdinalIgnoreCase);

        var open = new Button { Content = T("Open PDF") };
        AutomationProperties.SetAutomationId(open, "OpenGeneratedPdf");
        var folder = new Button { Content = T("Show in folder") };
        open.Click += async (_, _) => await TryAsync(async () =>
        {
            if (!File.Exists(file)) throw new StarHubException(T("This PDF was moved or deleted."));
            if (!await Launcher.LaunchFileAsync(await StorageFile.GetFileFromPathAsync(file)))
                throw new StarHubException(T("Windows could not open this PDF."));
        });
        folder.Click += async (_, _) => await TryAsync(async () =>
        {
            string directory = Path.GetDirectoryName(file)!;
            var destination = await StorageFolder.GetFolderFromPathAsync(directory);
            if (!File.Exists(file))
            {
                await Launcher.LaunchFolderAsync(destination);
                return;
            }
            var options = new FolderLauncherOptions();
            options.ItemsToSelect.Add(await StorageFile.GetFileFromPathAsync(file));
            await Launcher.LaunchFolderAsync(destination, options);
        });
        var save = new Button { Content = T("Save PDF…") };
        save.Click += async (_, _) => await TryAsync(async () =>
        {
            if (!File.Exists(file)) throw new StarHubException(T("This PDF was moved or deleted."));
            var picker = new global::Windows.Storage.Pickers.FileSavePicker { SuggestedFileName = Path.GetFileNameWithoutExtension(file) };
            WinRT.Interop.InitializeWithWindow.Initialize(picker, hwnd);
            picker.FileTypeChoices.Add("PDF", [".pdf"]);
            if (await picker.PickSaveFileAsync() is { } target && !string.Equals(target.Path, file, StringComparison.OrdinalIgnoreCase))
            {
                var source = await StorageFile.GetFileFromPathAsync(file);
                await source.CopyAndReplaceAsync(target);
            }
        });
        var buttons = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 6 };
        buttons.Children.Add(open);
        buttons.Children.Add(export ? save : folder);
        var card = new StackPanel { Spacing = 7 };
        card.Children.Add(name);
        if (!export) card.Children.Add(location);
        if (File.Exists(file)) card.Children.Add(buttons);
        else card.Children.Add(Label(T("This PDF is no longer at its saved location.")));
        panel.Children.Add(new Border
        {
            Child = card, Background = Brush("ComposerBrush"), CornerRadius = new CornerRadius(13), Padding = new Thickness(12)
        });
    }
}
