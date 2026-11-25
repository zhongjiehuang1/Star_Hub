using System.Text;
using Microsoft.UI.Dispatching;
using Microsoft.UI.Input;
using Microsoft.UI.Windowing;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Input;
using StarHub.Core;
using StarHub.Platform;
using Windows.ApplicationModel.DataTransfer;
using Windows.Graphics;
using Windows.Storage;
using Windows.Storage.Pickers;
using Windows.UI.Core;
using VirtualKey = Windows.System.VirtualKey;

namespace StarHub.Windows;

public sealed partial class MainWindow : Window
{
    private readonly LocalStore store;
    private readonly WindowsCredentials credentials = new();
    private readonly DesktopAccountStore desktopAccount = new();
    private readonly ProviderClient providerClient = new();
    private readonly ChatEngine engine;
    private readonly nint hwnd;
    private readonly TrayIcon? tray;
    private Preferences settings;
    private string conversationId = Guid.NewGuid().ToString("N");
    private bool selectingHistory, loading, narrow, lastDeviceAnalysis, composing;
    private int compositionRevision;
    private Attachment? attachment;
    private CancellationTokenSource? request;

    public MainWindow()
    {
        store = new LocalStore(WindowsHost.DataPath);
        settings = store.ReadPreferences();
        if (!settings.UseMemory)
        {
            settings = settings with { UseMemory = true };
            store.SavePreferences(settings);
        }
        store.MarkInterruptedChats();
        accountProfile = desktopAccount.ReadProfile();
        engine = new ChatEngine(store, desktopAccount, providerClient);
        loading = true;
        InitializeComponent();
        hwnd = WinRT.Interop.WindowNative.GetWindowHandle(this);
        AppWindow.SetIcon(Path.Combine(AppContext.BaseDirectory, "starhub.ico"));
        const int preferredWidth = 1100;
        const int preferredHeight = 780;
        var display = DisplayArea.GetFromWindowId(AppWindow.Id, DisplayAreaFallback.Nearest);
        if (display is { } area)
        {
            var workArea = area.WorkArea;
            var bounds = area.OuterBounds;
            int width = Math.Min(preferredWidth, workArea.Width);
            int height = Math.Min(preferredHeight, workArea.Height);
            AppWindow.MoveAndResize(new RectInt32(
                bounds.X + workArea.X + (workArea.Width - width) / 2,
                bounds.Y + workArea.Y + (workArea.Height - height) / 2,
                width, height));
        }
        else
        {
            AppWindow.Resize(new SizeInt32(preferredWidth, preferredHeight));
        }
        if (AppWindow.Presenter is OverlappedPresenter presenter)
        {
            presenter.PreferredMinimumWidth = 390;
            presenter.PreferredMinimumHeight = 420;
        }
        tray = TrayIcon.Create(hwnd);
        AppWindow.Closing += ConfirmClosing;
        Closed += (_, _) =>
        {
            request?.Cancel();
            DisposeVoiceRecording();
            tray?.Dispose();
            StopReading();
            speechPlayer?.Dispose();
            providerClient.Dispose();
        };
        OperateComputer.IsChecked = settings.OperateComputer;
        ApplyLanguage();
        ReloadModelChoices();
        RefreshProjects(settings.OpenProjectId);
        loading = false;
        ApplyTheme();
        RefreshHistory();
        UpdateReasoning();
        UpdateTools();
        UpdateRoute();
        Try(WindowsHost.EnsureWorkerRunning);
    }

    private void ApplyTheme() => Shell.RequestedTheme = settings.Theme switch
    {
        "Light" => ElementTheme.Light,
        "Dark" => ElementTheme.Dark,
        _ => ElementTheme.Default
    };

    private ChatOptions Options(string prompt)
    {
        var options = new ChatOptions(SelectedProvider, reasoningEffort, ProjectId: currentProject?.Id,
            AllowLocalDeviceAnalysis: settings.OperateComputer);
        if (ChatEngine.UsesDeviceAnalysis(prompt, options, attachment, lastDeviceAnalysis)) return options;
        bool canUseOpenAi = openAiReady && SelectedProvider is (Provider.OpenAI or Provider.Automatic);
        bool analyze = canUseOpenAi && attachment is { Name: var name } &&
            Path.GetExtension(name).ToLowerInvariant() is ".csv" or ".tsv" or ".xlsx" &&
            System.Text.RegularExpressions.Regex.IsMatch(prompt, @"统计|数据分析|画图|趋势|\banaly[sz]e\b|\bchart\b",
                System.Text.RegularExpressions.RegexOptions.IgnoreCase | System.Text.RegularExpressions.RegexOptions.CultureInvariant);
        return options with { WebSearch = canUseOpenAi && WebSearch.IsChecked == true, DataAnalysis = analyze };
    }

    private void UpdateTools()
    {
        WebSearch.IsEnabled = openAiReady && SelectedProvider is (Provider.OpenAI or Provider.Automatic);
        if (!WebSearch.IsEnabled) WebSearch.IsChecked = false;
    }

    private void OperateComputerClicked(object sender, RoutedEventArgs args)
    {
        if (loading || request is not null) return;
        Try(() =>
        {
            var updated = settings with { OperateComputer = OperateComputer.IsChecked == true };
            store.SavePreferences(updated);
            settings = updated;
            UpdateInputMode();
            Composer.Focus(FocusState.Programmatic);
        });
        OperateComputer.IsChecked = settings.OperateComputer;
    }

    private void UpdateInputMode()
    {
        Composer.PlaceholderText = T(settings.OperateComputer ? "Tell this PC what to do" : "Type a message");
        StartAConversation.Text = T(settings.OperateComputer ? "What would you like to do?" : "What would you like to explore?");
        AskAQuestionOrAddAFile.Text = T(settings.OperateComputer ? "Type or speak. Try “open Notepad”." : "Type, speak, or attach a file.");
        ToolTipService.SetToolTip(OperateComputer, T(settings.OperateComputer
            ? "Computer operation is on" : "Ordinary chat is on"));
    }

    private void ComposerChanged(object sender, TextChangedEventArgs args) { if (!loading) UpdateRoute(); }

    private void UpdateRoute()
    {
        if (!loading) SendButton.IsEnabled = request is null && !VoiceBusy && Composer.Text.Trim().Length > 0;
    }

    private void ComposerToolsSizeChanged(object sender, SizeChangedEventArgs args)
    {
        bool compact = args.NewSize.Width < 430;
        Grid.SetColumnSpan(ComposerOptions, compact ? 2 : 1);
        Grid.SetRow(ComposerActions, compact ? 1 : 0);
        Grid.SetColumn(ComposerActions, compact ? 0 : 1);
        Grid.SetColumnSpan(ComposerActions, compact ? 2 : 1);
        ComposerTools.RowSpacing = compact ? 4 : 0;
    }

    private void RefreshHistory()
    {
        selectingHistory = true;
        var entries = store.Conversations(HistoryFilter.Text, currentProject?.Id);
        History.ItemsSource = entries;
        History.SelectedItem = entries.FirstOrDefault(chat => chat.Id == conversationId);
        selectingHistory = false;
        UpdateConversationHeader();
    }

    private void HistoryChanged(object sender, SelectionChangedEventArgs args)
    {
        if (loading || selectingHistory || request is not null || VoiceBusy || History.SelectedItem is not Conversation chat) return;
        Try(() =>
        {
            conversationId = chat.Id;
            Composer.Text = "";
            SetAttachment(null);
            Say("");
            PaintTurns();
            if (narrow) Workspace.IsPaneOpen = false;
        });
    }

    private void SearchHistoryChanged(object sender, TextChangedEventArgs args) { if (!loading) Try(RefreshHistory); }

    private void PaintTurns()
    {
        MessageLog.Children.Clear();
        var turns = store.ReadTurns(conversationId);
        lastDeviceAnalysis = turns.Count > 0 && turns[^1].Model == ChatEngine.DeviceAnalysisModel;
        foreach (var turn in turns) ShowResult(AddTurn(turn), turn);
        UpdateConversationHeader();
        Welcome.Visibility = turns.Count == 0 ? Visibility.Visible : Visibility.Collapsed;
        UpdateRoute();
        ScrollToBottom();
    }

    private void UpdateConversationHeader()
    {
        var saved = MessageLog.Children.Count > 0 ? store.FindConversation(conversationId) : null;
        ChatTitle.Text = saved?.Title ?? T("New conversation");
        ConversationActions.Visibility = saved is null ? Visibility.Collapsed : Visibility.Visible;
        ConversationActions.IsEnabled = request is null && !VoiceBusy;
    }

    private void NewConversation()
    {
        if (request is not null || VoiceBusy) return;
        conversationId = Guid.NewGuid().ToString("N");
        selectingHistory = true;
        History.SelectedIndex = -1;
        selectingHistory = false;
        Composer.Text = "";
        Say("");
        SetAttachment(null);
        PaintTurns();
        if (narrow) Workspace.IsPaneOpen = false;
        Composer.Focus(FocusState.Programmatic);
    }

    private void NewClicked(object sender, RoutedEventArgs args) => Try(NewConversation);
    private void NewShortcut(KeyboardAccelerator sender, KeyboardAcceleratorInvokedEventArgs args)
    {
        args.Handled = true;
        Try(NewConversation);
    }

    private void ToggleSidebar(object sender, RoutedEventArgs args) => Workspace.IsPaneOpen = !Workspace.IsPaneOpen;
    private void ShellSizeChanged(object sender, SizeChangedEventArgs args)
    {
        double width = args.NewSize.Width;
        bool compact = width < 760;
        bool tight = width < 570;
        Workspace.OpenPaneLength = Math.Min(246, width * 0.8);
        ComposerArea.Padding = tight ? new Thickness(10, 6, 10, 10) : new Thickness(20, 8, 20, 18);
        Transcript.Padding = tight ? new Thickness(12, 14, 12, 10) : new Thickness(24, 24, 24, 14);
        ResizeTranscript();
        Composer.MaxHeight = Math.Clamp(args.NewSize.Height * 0.25, 70, 150);
        if (narrow == compact) return;
        narrow = compact;
        Workspace.DisplayMode = compact ? SplitViewDisplayMode.Overlay : SplitViewDisplayMode.Inline;
        Workspace.IsPaneOpen = !compact;
    }

    private void TranscriptSizeChanged(object sender, SizeChangedEventArgs args) => ResizeTranscript();

    private void ResizeTranscript()
    {
        if (TranscriptContent is null || Transcript.ActualWidth <= 0) return;
        TranscriptContent.Width = Math.Max(1, Math.Min(780, Transcript.ActualWidth - Transcript.Padding.Left - Transcript.Padding.Right - 16));
    }

    private async void AttachClicked(object sender, RoutedEventArgs args)
    {
        await TryAsync(async () =>
        {
            if (await ChooseFileAsync(Attachments.Extensions) is not { } file) return;
            using var input = await file.OpenStreamForReadAsync();
            SetAttachment(await Attachments.ReadAsync(file.Name, input));
            Say("");
        });
    }

    private void SetAttachment(Attachment? file)
    {
        attachment = file;
        FileLabel.Text = file?.Name ?? "";
        FileRow.Visibility = file is null ? Visibility.Collapsed : Visibility.Visible;
        UpdateRoute();
    }

    private void DetachClicked(object sender, RoutedEventArgs args) => SetAttachment(null);

    private async void SendClicked(object sender, RoutedEventArgs args) => await TryAsync(SendAsync);

    private void ComposerCompositionStarted(TextBox sender, TextCompositionStartedEventArgs args)
    {
        composing = true;
        compositionRevision++;
    }

    private void ComposerCompositionEnded(TextBox sender, TextCompositionEndedEventArgs args)
    {
        int revision = compositionRevision;
        DispatcherQueue.TryEnqueue(DispatcherQueuePriority.Low, () =>
        {
            if (revision == compositionRevision) composing = false;
        });
    }

    private async void ComposerKeyDown(object sender, KeyRoutedEventArgs args)
    {
        if (args.Key != VirtualKey.Enter || composing ||
            (InputKeyboardSource.GetKeyStateForCurrentThread(VirtualKey.Shift) & CoreVirtualKeyStates.Down) != 0) return;
        args.Handled = true;
        if (!args.KeyStatus.WasKeyDown) await TryAsync(SendAsync);
    }

    private async void StopClicked(object sender, RoutedEventArgs args)
    {
        if (request is not null) request.Cancel();
        else if (voiceRecording is not null) await TryAsync(() => FinishVoiceAsync(submit: false, discard: true));
    }

    private async Task SendAsync()
    {
        string prompt = Composer.Text.Trim();
        if (request is not null || VoiceBusy || prompt.Length == 0) return;
        if (narrow) Workspace.IsPaneOpen = false;
        var recent = store.ReadTurns(conversationId, maxRecent: 10);
        var options = Options(prompt);
        var plan = PcCommands.Parse(prompt);
        var direct = PcDirect.Parse(prompt);
        bool device = ChatEngine.UsesDeviceAnalysis(prompt, options, attachment, lastDeviceAnalysis);
        if (direct is null && plan is null && !device && PdfRequests.Parse(prompt, recent, attachment is not null) is { } pdf)
        {
            await RunPdfRequestAsync(prompt, pdf);
            return;
        }

        bool followsComputerTask = recent.LastOrDefault()?.Model is { } prior &&
            (prior == LocalPcModel || prior.StartsWith("Windows · ", StringComparison.Ordinal));
        bool computerRequest = plan is not null || direct is not null || PcCommands.HasPrefix(prompt) ||
            PcCommands.MayReferToComputer(prompt, followsComputerTask);
        if (settings.OperateComputer)
        {
            if (plan is { Count: > 0 } || prompt.Equals("/pc", StringComparison.OrdinalIgnoreCase))
            {
                await RunPcPlanAsync(prompt, plan ?? [], options.ProjectId);
                return;
            }
            if (direct is not null)
            {
                await RunDirectPcTaskAsync(prompt, direct, options.ProjectId);
                return;
            }
            if (!device && attachment is null && computerRequest)
            {
                await RunNaturalPcTaskAsync(prompt, options.ProjectId, recent, options.WebSearch);
                return;
            }
        }
        else if (attachment is null && (plan is not null || direct is not null || PcCommands.HasPrefix(prompt) ||
                 PcCommands.LooksLikeGoal(prompt) ||
                 ChatEngine.UsesDeviceAnalysis(prompt, options with { AllowLocalDeviceAnalysis = true, WebSearch = false }, null, lastDeviceAnalysis)))
        {
            ShowComputerModeHint(prompt, options.ProjectId);
            return;
        }

        using var cancellation = new CancellationTokenSource();
        request = cancellation;
        SetSending(true);
        Say(device ? "Reading local device data…" : "Waiting for the model…");
        var buffer = new StringBuilder();
        var timer = DispatcherQueue.CreateTimer();
        timer.Interval = TimeSpan.FromMilliseconds(75);
        StackPanel? resultPanel = null;
        var streaming = new TextBlock { TextWrapping = TextWrapping.Wrap, IsTextSelectionEnabled = true };

        void PaintDelta()
        {
            string text;
            lock (buffer)
            {
                if (buffer.Length == 0) return;
                text = buffer.ToString();
                buffer.Clear();
            }
            bool follow = Transcript.ScrollableHeight - Transcript.VerticalOffset < 65;
            streaming.Text += text;
            if (follow) ScrollToBottom();
            Say(T("Receiving reply…"));
        }

        timer.Tick += (_, _) => PaintDelta();
        try
        {
            var turn = await engine.SendAsync(conversationId, prompt, attachment, ChatSettings, options, started =>
            {
                lastDeviceAnalysis = started.Model == ChatEngine.DeviceAnalysisModel;
                Welcome.Visibility = Visibility.Collapsed;
                resultPanel = AddTurn(started);
                resultPanel.Children.Add(streaming);
                Composer.Text = "";
                SetAttachment(null);
                RefreshHistory();
                ScrollToBottom();
                timer.Start();
            }, text => { lock (buffer) buffer.Append(text); }, cancellation.Token);
            timer.Stop();
            if (resultPanel is not null) ShowResult(resultPanel, turn);
            Say(turn.State == TurnState.Completed ? "" : turn.Detail);
            if (Transcript.ScrollableHeight - Transcript.VerticalOffset < 100) ScrollToBottom();
        }
        finally
        {
            timer.Stop();
            request = null;
            SetSending(false);
            Composer.Focus(FocusState.Programmatic);
        }
    }

    private void SetSending(bool sending)
    {
        bool busy = sending || VoiceBusy;
        NewButton.IsEnabled = History.IsEnabled = ProjectPicker.IsEnabled = AttachButton.IsEnabled =
            DesktopAccountButton.IsEnabled = ManageProjects.IsEnabled = !busy;
        SettingsButton.IsEnabled = Device.IsEnabled = !VoiceBusy;
        OperateComputer.IsEnabled = !busy;
        if (busy) WebSearch.IsEnabled = false;
        else UpdateTools();
        ConversationActions.IsEnabled = !busy;
        VoiceInput.IsEnabled = !sending && !voiceTransition;
        Composer.IsReadOnly = VoiceBusy;
        StopButton.Visibility = sending || voiceRecording is not null && !voiceTransition ? Visibility.Visible : Visibility.Collapsed;
        SendButton.Visibility = StopButton.Visibility == Visibility.Visible ? Visibility.Collapsed : Visibility.Visible;
        UpdateVoiceLabel();
        UpdateRoute();
    }

    private StackPanel AddTurn(Turn turn)
    {
        var prompt = new TextBlock
        {
            Text = turn.Prompt + (turn.FileName is null ? "" : $"\n\nAttached · {turn.FileName}"),
            TextWrapping = TextWrapping.Wrap, IsTextSelectionEnabled = true
        };
        var message = new StackPanel { Spacing = 10 };
        var bubble = new Border
        {
            Background = Brush("UserMessageBrush"), Padding = new Thickness(15, 11, 15, 11),
            CornerRadius = new CornerRadius(18), Child = prompt, HorizontalAlignment = HorizontalAlignment.Right,
            Margin = new Thickness(44, 0, 0, 8)
        };
        message.Children.Add(bubble);
        var title = new TextBlock { Text = turn.Model, FontWeight = Microsoft.UI.Text.FontWeights.SemiBold, FontSize = 13 };
        message.Children.Add(title);
        var result = new StackPanel { Spacing = 8 };
        message.Children.Add(result);
        MessageLog.Children.Add(message);
        return result;
    }

    private void ShowResult(StackPanel panel, Turn turn)
    {
        panel.Children.Clear();
        if (turn.GeneratedPdf is { } pdf) ShowPdfFile(panel, pdf);
        if (turn.Reply.Length > 0)
        {
            MarkdownView.AddBlocks(panel, turn.Reply, Brush("SideRailBrush"));
            AddSources(panel, turn.Sources);
            if (turn.ProjectSources is { Count: > 0 } projectSources)
                panel.Children.Add(Label("Project excerpts · " + string.Join(" · ",
                    projectSources.Select(item => $"{item.Document}, section {item.Section}"))));
            var actions = new MenuFlyout();
            var copy = new MenuFlyoutItem { Text = T("Copy") };
            copy.Click += (_, _) =>
            {
                var data = new DataPackage();
                data.SetText(turn.Reply);
                Clipboard.SetContent(data);
            };
            var save = new MenuFlyoutItem { Text = T("Save reply…") };
            save.Click += async (_, _) => await TryAsync(() => SaveTextAsync(turn.Reply, "StarHub reply"));
            actions.Items.Add(copy);
            actions.Items.Add(save);
            var speak = new MenuFlyoutItem { Text = T("Read aloud") };
            speak.Click += async (_, _) => await TryAsync(() => SpeakAsync(turn.Reply));
            actions.Items.Add(speak);
            panel.Children.Add(new Button { Content = T("Reply actions"), Flyout = actions });
        }
        if (turn.Detail.Length > 0) panel.Children.Add(new TextBlock { Text = turn.Detail, TextWrapping = TextWrapping.Wrap });
    }

    private void AddSources(StackPanel panel, IReadOnlyList<Source> sources)
    {
        if (sources.Count == 0) return;
        panel.Children.Add(new TextBlock { Text = T("Sources"), FontWeight = Microsoft.UI.Text.FontWeights.SemiBold });
        foreach (var source in sources)
        {
            if (!ProviderClient.TryWebUri(source.Url, out var uri)) continue;
            panel.Children.Add(new HyperlinkButton
            {
                NavigateUri = uri,
                Content = new TextBlock { Text = $"{source.Title} · {uri!.Host}", TextWrapping = TextWrapping.Wrap }
            });
        }
    }

    private void ScrollToBottom() => DispatcherQueue.TryEnqueue(DispatcherQueuePriority.Low,
        () => Transcript.ChangeView(0, double.MaxValue, null, disableAnimation: true));

    private async void ExportClicked(object sender, RoutedEventArgs args)
    {
        await TryAsync(async () =>
        {
            var result = new StringBuilder().AppendLine("# " + ChatTitle.Text);
            foreach (var turn in store.ReadTurns(conversationId))
            {
                result.AppendLine().AppendLine("## You").AppendLine(turn.Prompt);
                if (turn.FileName is { } file) result.AppendLine($"Attached file · {file}");
                result.AppendLine().AppendLine("## " + turn.Model).AppendLine(turn.Reply);
                if (turn.Detail.Length > 0) result.AppendLine(turn.Detail);
                foreach (var source in turn.Sources) result.AppendLine("- " + source.Url);
                foreach (var source in turn.ProjectSources ?? [])
                    result.AppendLine($"- Project excerpt · {source.Document}, section {source.Section}");
            }
            await SaveTextAsync(result.ToString(), "StarHub conversation");
        });
    }

    private async void RenameConversationClicked(object sender, RoutedEventArgs args)
    {
        await TryAsync(async () =>
        {
            if (request is not null || VoiceBusy || store.FindConversation(conversationId) is not { } chat) return;
            var name = Field(T("Conversation title"), chat.Title, 120);
            Microsoft.UI.Xaml.Automation.AutomationProperties.SetAutomationId(name, "ConversationTitleEdit");
            var dialog = Dialog(T("Rename conversation"), name);
            dialog.PrimaryButtonText = T("Save");
            dialog.CloseButtonText = T("Cancel");
            dialog.IsPrimaryButtonEnabled = !string.IsNullOrWhiteSpace(name.Text);
            name.TextChanged += (_, _) => dialog.IsPrimaryButtonEnabled = !string.IsNullOrWhiteSpace(name.Text);
            if (await dialog.ShowAsync() != ContentDialogResult.Primary) return;
            store.RenameConversation(chat.Id, name.Text);
            RefreshHistory();
        });
    }

    private async void DeleteClicked(object sender, RoutedEventArgs args)
    {
        await TryAsync(async () =>
        {
            if (request is not null || VoiceBusy || store.FindConversation(conversationId) is not { } chat) return;
            var content = new StackPanel { Spacing = 9 };
            content.Children.Add(Label(chat.Title));
            content.Children.Add(Label(T("This removes the conversation and its attachments from this PC.")));
            var dialog = Dialog(T("Delete this conversation?"), content);
            dialog.CloseButtonText = T("Cancel");
            dialog.PrimaryButtonText = T("Delete");
            if (await dialog.ShowAsync() != ContentDialogResult.Primary) return;
            store.DeleteConversation(chat.Id);
            if (conversationId == chat.Id) NewConversation();
            RefreshHistory();
        });
    }

    private async Task SaveTextAsync(string text, string name)
    {
        var picker = new FileSavePicker { SuggestedFileName = name };
        WinRT.Interop.InitializeWithWindow.Initialize(picker, hwnd);
        picker.FileTypeChoices.Add("Markdown", [".md"]);
        picker.FileTypeChoices.Add("Text", [".txt"]);
        if (await picker.PickSaveFileAsync() is { } file)
        {
            await FileIO.WriteTextAsync(file, text);
            Say($"Saved {file.Name}.");
        }
    }

    private Microsoft.UI.Xaml.Media.Brush Brush(string key)
    {
        var accessibility = new global::Windows.UI.ViewManagement.AccessibilitySettings();
        string theme = accessibility.HighContrast ? "HighContrast" : Shell.ActualTheme == ElementTheme.Dark ? "Dark" : "Light";
        var dictionary = (ResourceDictionary)Application.Current.Resources.ThemeDictionaries[theme];
        return (Microsoft.UI.Xaml.Media.Brush)dictionary[key];
    }

    private void Say(string message)
    {
        StatusText.Text = message;
        StatusText.Visibility = message.Length == 0 ? Visibility.Collapsed : Visibility.Visible;
    }

    private ContentDialog Dialog(string title, object body) => new()
    {
        XamlRoot = Shell.XamlRoot, Title = title, Content = body, CloseButtonText = T("Close"),
        RequestedTheme = Shell.RequestedTheme, CornerRadius = new CornerRadius(20)
    };

    private void Try(Action action)
    {
        try { action(); }
        catch (Exception error) { Report(error); }
    }

    private async Task TryAsync(Func<Task> action)
    {
        foregroundOperations++;
        try { await action(); }
        catch (Exception error) { Report(error); }
        finally { foregroundOperations--; }
    }

    private void Report(Exception error)
    {
        Say(error is StarHubException ? error.Message : "Star Hub could not complete this operation. See the local desktop log.");
        WindowsHost.Log("desktop.log", $"{error.GetType().Name} (0x{error.HResult:X8}).");
    }
}
