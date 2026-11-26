using System.Globalization;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Automation;
using Microsoft.UI.Xaml.Controls;
using StarHub.Core;
using StarHub.Platform;
using Windows.Storage;
using Windows.Storage.Pickers;
using Windows.System;

namespace StarHub.Windows;

public sealed partial class MainWindow
{
    private string WorkText(string chinese, string english) => settings.UsesChineseInterface() ? chinese : english;

    private async Task RunLocalWorkAsync(string prompt, WorkKind kind, string? projectId, bool startup)
    {
        WorkCheckpoint? previous = null;
        WorkRequest plan;
        if (kind is WorkKind.ProductSummary or WorkKind.Resume)
        {
            previous = store.LatestWork(conversationId, projectId, unfinished: kind == WorkKind.Resume);
            if (previous is null && startup)
                previous = store.LatestWork(null, projectId, unfinished: kind == WorkKind.Resume);
            if (previous is null)
                throw new StarHubException(kind == WorkKind.Resume
                    ? WorkText("这段对话没有可续做的任务。", "There is no interrupted task in this conversation.")
                    : WorkText("这段对话还没有做过成本工作簿。", "Create a cost workbook in this conversation first."));
            conversationId = previous.ConversationId;
            RefreshHistory();
            PaintTurns();
            plan = kind == WorkKind.Resume ? previous.Request
                : LocalWork.Plan(kind, previous.Receipt!.Path, null, "");
        }
        else
        {
            string? source = LocalWork.SourcePath(prompt);
            if (source is null && kind == WorkKind.CostWorkbook && attachmentPath is { } attached &&
                Path.GetExtension(attached).Equals(".csv", StringComparison.OrdinalIgnoreCase))
                source = attached;
            if (source is null)
            {
                Say(kind == WorkKind.PhotoArchive
                    ? WorkText("选择要整理的照片文件夹。", "Choose the folder containing the photos.")
                    : WorkText("选择包含领料和工时表的文件夹。", "Choose the folder containing material and work logs."));
                var picker = new FolderPicker { SuggestedStartLocation = PickerLocationId.DocumentsLibrary };
                picker.FileTypeFilter.Add("*");
                WinRT.Interop.InitializeWithWindow.Initialize(picker, hwnd);
                if (await picker.PickSingleFolderAsync() is not { } folder) return;
                source = folder.Path;
            }
            string? target = LocalWork.RequestedOutput(prompt);
            plan = LocalWork.Plan(kind, source, target is null ? null : WindowsHost.TaskPath(target),
                WindowsHost.PdfOutputFolder(PdfDestination.Documents));
        }

        string designation = plan.Kind switch
        {
            WorkKind.CostWorkbook => "cost_workbook",
            WorkKind.ProductSummary => "product_summary",
            _ => "photo_archive"
        };
        await RunComputerTaskAsync(prompt, projectId, LocalPcModel, async (id, token, onStep) =>
        {
            var job = store.SaveWork(id, conversationId, plan, kind == WorkKind.Resume ? previous!.OperationId : null);
            var receipt = await Task.Run(() => LocalWork.Execute(job, token, count =>
                onStep(new("verify_photos", true, WorkText($"已核对 {count} 张来源照片。", $"Verified {count} source photos.")))), token);
            store.CompleteWork(job.OperationId, receipt);
            string message = plan.Kind switch
            {
                WorkKind.CostWorkbook => WorkText($"已生成并核验 {receipt.Count} 个产品的成本表。",
                    $"Created and verified a cost workbook for {receipt.Count} products."),
                WorkKind.ProductSummary => WorkText($"已在同一工作簿中核验 {receipt.Count} 个产品的汇总。",
                    $"Verified the summary for {receipt.Count} products in the same workbook."),
                _ => WorkText($"已按拍摄日期整理 {receipt.Count} 张来源照片。", $"Archived {receipt.Count} source photos by capture date.")
            };
            if (receipt.NeedsReview != 0)
                message += receipt.Directory
                    ? WorkText($" 其中 {receipt.NeedsReview} 张缺少拍摄日期，已放入 Unknown date。", $" {receipt.NeedsReview} without a capture date are in Unknown date.")
                    : WorkText($" 有 {receipt.NeedsReview} 项需复核，见工作簿的 Review 表。", $" See the Review sheet for {receipt.NeedsReview} items to check.");
            return new(message, [], receipt.Directory || receipt.NeedsReview == 0, Detail: "", Work: receipt);
        }, designation);
    }

    private void ShowWorkFile(StackPanel panel, WorkReceipt work)
    {
        var details = new StackPanel { Spacing = 7 };
        var location = Label(work.Path);
        location.IsTextSelectionEnabled = true;
        details.Children.Add(location);
        if (work.Directory ? Directory.Exists(work.Path) : File.Exists(work.Path))
        {
            var open = new Button { Content = work.Directory ? WorkText("打开照片归档", "Open photo archive") : WorkText("打开 Excel", "Open Excel workbook") };
            AutomationProperties.SetAutomationId(open, work.Directory ? "OpenPhotoArchive" : "OpenCostWorkbook");
            open.Click += async (_, _) => await TryAsync(async () =>
            {
                bool accepted = work.Directory
                    ? await Launcher.LaunchFolderAsync(await StorageFolder.GetFolderFromPathAsync(work.Path))
                    : await Launcher.LaunchFileAsync(await StorageFile.GetFileFromPathAsync(work.Path));
                if (!accepted) throw new StarHubException(WorkText("Windows 无法打开这项结果。", "Windows could not open this result."));
            });
            details.Children.Add(open);
            if (!work.Directory)
            {
                var lesson = new Button { Content = WorkText("跟着这张表学核算", "Learn with this workbook") };
                AutomationProperties.SetAutomationId(lesson, "PracticeCostAllocation");
                lesson.Click += async (_, _) => await TryAsync(() => ShowWorkbookLessonAsync(work.Path));
                details.Children.Add(lesson);
            }
        }
        else details.Children.Add(Label(WorkText("请检查文件的保存位置。", "Check the saved location.")));
        panel.Children.Add(new Border
        {
            Child = details, Background = Brush("ComposerBrush"), CornerRadius = new CornerRadius(13), Padding = new Thickness(12)
        });
    }

    private async Task ShowCostLessonAsync()
    {
        string chat = conversationId;
        string title = WorkText("分摊车间费用", "Allocate shop costs");
        var steps = new StackPanel { Spacing = 12, MaxWidth = 520 };
        steps.Children.Add(Label(WorkText("车间共同费用 1,200 元，合计 200 机器小时。", "Shared shop cost: 1,200 yuan over 200 machine hours.")));
        var progress = new ProgressBar { Minimum = 0, Maximum = 200, Value = 120 };
        AutomationProperties.SetName(progress, WorkText("A 产品的机器工时", "Machine hours for product A"));
        var example = Label(WorkText("A 用了 120 小时，分 720 元。B 用了 80 小时，分 480 元。",
            "A used 120 hours and gets 720 yuan. B used 80 hours and gets 480 yuan."));
        steps.Children.Add(progress);
        steps.Children.Add(example);
        steps.Children.Add(Label(WorkText("轮到你。A 改成 150 小时，B 是 50 小时。各分多少元？",
            "Your turn. A uses 150 hours and B uses 50 hours. How much should each product get?")));
        var first = new TextBox { Header = WorkText("A 产品（元）", "Product A (yuan)") };
        var second = new TextBox { Header = WorkText("B 产品（元）", "Product B (yuan)") };
        AutomationProperties.SetAutomationId(first, "AllocationA");
        AutomationProperties.SetAutomationId(second, "AllocationB");
        steps.Children.Add(first);
        steps.Children.Add(second);
        var feedback = Label(store.LessonComplete(chat) ? WorkText("这道题已经做对过。", "You completed this exercise earlier.") : "");
        AutomationProperties.SetAutomationId(feedback, "AllocationFeedback");
        var check = new Button { Content = WorkText("核对", "Check") };
        AutomationProperties.SetAutomationId(check, "CheckAllocation");
        check.Click += (_, _) => Try(() =>
        {
            bool correct = decimal.TryParse(first.Text, NumberStyles.Number, CultureInfo.CurrentCulture, out var a) &&
                decimal.TryParse(second.Text, NumberStyles.Number, CultureInfo.CurrentCulture, out var b) && a == 900m && b == 300m;
            feedback.Text = correct
                ? WorkText("核对通过。按机器小时分摊，A 分 900 元，B 分 300 元，合计 1,200 元。",
                    "Correct. A gets 900 yuan and B gets 300 yuan, for a total of 1,200 yuan.")
                : WorkText("再试一次。先算每机器小时多少钱，再乘各自工时。",
                    "Try again. Find the cost per machine hour, then multiply it by each product's hours.");
            if (correct)
            {
                store.SaveLessonCompletion(chat);
                progress.Value = 150;
                example.Text = WorkText("这次 A 用 150 小时，B 用 50 小时。", "This time A uses 150 hours and B uses 50 hours.");
            }
        });
        steps.Children.Add(check);
        steps.Children.Add(feedback);
        await Dialog(title, new ScrollViewer { Content = steps, MaxHeight = 490 }).ShowAsync();
    }
}
