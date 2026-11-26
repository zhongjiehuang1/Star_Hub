using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Automation;
using Microsoft.UI.Xaml.Controls;
using StarHub.Core;
using StarHub.Platform;

namespace StarHub.Windows;

public sealed partial class MainWindow
{
    private async void SettingsClicked(object sender, RoutedEventArgs args) => await TryAsync(ShowSettingsAsync);
    private async void MemoryClicked(object sender, RoutedEventArgs args) => await TryAsync(ShowMemoryAsync);
    private async void TasksClicked(object sender, RoutedEventArgs args) => await TryAsync(ShowTasksAsync);

    private async Task ShowSettingsAsync()
    {
        string[] languageCodes = ["system", "en", "zh-Hans"];
        var language = new ComboBox
        {
            Header = "Language / 界面语言", ItemsSource = new[] { T("System"), "English", "简体中文" },
            SelectedIndex = Math.Max(0, Array.IndexOf(languageCodes, settings.Language))
        };
        string[] themes = ["System", "Light", "Dark"];
        var theme = new ComboBox
        {
            Header = T("Appearance"), ItemsSource = themes.Select(T).ToArray(),
            SelectedIndex = Math.Max(0, Array.IndexOf(themes, settings.Theme))
        };
        var options = new StackPanel { Spacing = 10 };
        options.Children.Add(language);
        options.Children.Add(theme);
        options.Children.Add(new HyperlinkButton
        {
            Content = T("Check for updates"),
            NavigateUri = new Uri("https://starhub.zhongjiehuang.com/downloads/")
        });
        var dialog = Dialog(T("Settings"), options);
        dialog.PrimaryButtonText = T("Save settings");
        dialog.SecondaryButtonText = T("Advanced tools…");
        dialog.CloseButtonText = T("Cancel");
        switch (await dialog.ShowAsync())
        {
            case ContentDialogResult.Secondary:
                SettingsButton.ContextFlyout.ShowAt(SettingsButton);
                break;
            case ContentDialogResult.Primary:
                var updated = settings with
                {
                    Theme = themes[Math.Max(0, theme.SelectedIndex)],
                    Language = languageCodes[Math.Max(0, language.SelectedIndex)]
                };
                bool languageChanged = updated.Language != settings.Language;
                store.SavePreferences(updated);
                settings = updated;
                ApplyTheme();
                if (languageChanged)
                {
                    ApplyLanguage();
                    RefreshProjects(settings.OpenProjectId);
                    if (request is null) PaintTurns();
                }
                Say(T("Settings saved."));
                break;
        }
    }

    private async Task ShowModelConnectionsAsync()
    {
        var body = new StackPanel { Spacing = 9 };
        var openAi = new ComboBox
        {
            Header = T("Default cloud model"), HorizontalAlignment = HorizontalAlignment.Stretch,
            ItemsSource = ModelCatalog.OpenAiModels, DisplayMemberPath = nameof(CatalogModel.Name),
            SelectedItem = ModelCatalog.OpenAiModels.FirstOrDefault(model => model.Id == settings.OpenAiModel)
        };
        var openAiCustom = Field(T("Custom OpenAI model (optional)"), openAi.SelectedItem is null ? settings.OpenAiModel : "", 160);
        openAi.SelectionChanged += (_, _) => { if (openAi.SelectedItem is not null) openAiCustom.Text = ""; };
        var openAiKey = new PasswordBox
        {
            Header = credentials.Read(ChatEngine.OpenAiCredential) is null ? T("OpenAI API key") : T("Replace stored OpenAI API key"),
            MaxLength = 1200
        };
        var removeOpenAi = new CheckBox { Content = T("Remove stored OpenAI key") };
        if (accountProfile is null)
        {
            body.Children.Add(openAiKey);
            body.Children.Add(removeOpenAi);
            body.Children.Add(openAi);
        }

        var ollamaModel = Field(T("Ollama model installed on this PC"), settings.OllamaModel, 160);
        var ollamaPort = Field(T("Ollama port on 127.0.0.1"), settings.OllamaPort.ToString(), 5);
        AutomationProperties.SetAutomationId(ollamaModel, "OllamaModel");
        AutomationProperties.SetAutomationId(ollamaPort, "OllamaPort");

        var compatibleBase = Field(T("Compatible API HTTPS base URL"), settings.CompatibleEndpoint, 400);
        compatibleBase.PlaceholderText = "https://api.example.com/v1";
        var compatibleModel = Field(T("Compatible API model"), settings.CompatibleModel, 160);
        var compatibleKey = new PasswordBox { Header = T("Compatible API key (optional)"), MaxLength = 1200 };
        var removeCompatible = new CheckBox { Content = T("Remove key for this endpoint") };
        var localConnections = new StackPanel { Spacing = 9 };
        foreach (var field in new FrameworkElement[] { ollamaModel, ollamaPort, compatibleBase, compatibleModel, compatibleKey, removeCompatible })
            localConnections.Children.Add(field);
        if (accountProfile is null) body.Children.Add(openAiCustom);
        body.Children.Add(new Expander
        {
            Header = T("Advanced model connections"), Content = localConnections, HorizontalAlignment = HorizontalAlignment.Stretch
        });
        var feedback = Label("");
        body.Children.Add(feedback);
        var dialog = Dialog(T("Model connections"), Scroller(body));
        dialog.PrimaryButtonText = T("Save settings");
        dialog.PrimaryButtonClick += (_, args) =>
        {
            try
            {
                if (!int.TryParse(ollamaPort.Text, out int port) || port is < 1 or > 65535)
                    throw new StarHubException("Enter an Ollama port between 1 and 65,535.");
                var address = compatibleBase.Text.Trim();
                Uri? endpoint = address.Length > 0 ? ChatEngine.CompatibleEndpoint(address) : null;
                if (endpoint is null && (compatibleModel.Text.Trim().Length > 0 || compatibleKey.Password.Length > 0))
                    throw new StarHubException("Enter the compatible provider’s base URL.");
                if (accountProfile is null)
                    SaveKey(ChatEngine.OpenAiCredential, openAiKey.Password, removeOpenAi.IsChecked == true);
                if (endpoint is not null)
                    SaveKey(ChatEngine.CompatibleCredential(endpoint), compatibleKey.Password, removeCompatible.IsChecked == true);
                var updated = settings with
                {
                    OpenAiModel = accountProfile is not null ? settings.OpenAiModel
                        : openAiCustom.Text.Trim().Length > 0 ? openAiCustom.Text.Trim()
                        : (openAi.SelectedItem as CatalogModel)?.Id ?? ModelCatalog.OpenAiModels[0].Id,
                    OllamaPort = port, OllamaModel = ollamaModel.Text.Trim(), CompatibleModel = compatibleModel.Text.Trim(),
                    CompatibleEndpoint = endpoint?.AbsoluteUri ?? ""
                };
                store.SavePreferences(updated);
                settings = updated;
            }
            catch (Exception problem)
            {
                args.Cancel = true;
                feedback.Text = DialogError(problem);
            }
        };
        if (await dialog.ShowAsync() != ContentDialogResult.Primary) return;
        ReloadModelChoices();
        UpdateReasoning();
        UpdateTools();
        UpdateRoute();
        Say(T("Settings saved."));
    }

    private void SaveKey(string target, string input, bool remove)
    {
        if (remove) credentials.Remove(target);
        else if (!string.IsNullOrWhiteSpace(input)) credentials.Save(target, input);
    }

    private async Task<string> RefreshAccountMemoryAsync(DesktopAccountProfile profile, bool? enabled = null)
    {
        try
        {
            await new AccountMemorySync(store).SyncAsync(profile, CancellationToken.None, enabled);
            return WorkText("账号记忆已同步。","Account memory is up to date.");
        }
        catch (HttpRequestException e) when (e.StatusCode == System.Net.HttpStatusCode.Unauthorized)
        {
            return WorkText("请在账号菜单中刷新网站连接。本机的修改已保留。","Refresh the website connection in your account menu. Your local changes are saved.");
        }
        catch (Exception e) when (e is IOException or HttpRequestException or OperationCanceledException or Microsoft.Data.Sqlite.SqliteException or System.Text.Json.JsonException)
        {
            return WorkText("暂时无法同步。本机的修改已保留，联网后会重试。","Working from this PC. Saved changes will sync when the connection returns.");
        }
    }

    private async Task ShowMemoryAsync()
    {
        var profile = accountProfile;
        string? owner = profile?.CacheOwner;
        string L(string zh,string en) => WorkText(zh,en);
        var body = new StackPanel { Spacing = 8 };
        body.Children.Add(Label(profile is null ? T("Star Hub recalls related notes and saves notes you explicitly ask it to remember.")
            : profile.MemoryToken is null ? L("请在账号菜单中刷新网站连接，以启用记忆同步。","Refresh the website connection in your account menu to sync memory.")
            : L("与此账号的网页版共享记忆。对话、文件和设备读数保存在本机。","Memory is shared with this account on the web. Chats, files and device readings stay on this PC.")));
        var toggle = new Button();
        if (profile?.MemoryToken is not null) body.Children.Add(toggle);
        var search = Field(T("Find a memory"), "", 240);
        var list = new ListView { Height = 168, DisplayMemberPath = nameof(MemoryNote.Text) };
        var entry = Field(L("记忆内容","Note"), "", owner is null ? 1000 : 120, multiline: true);
        var feedback = Label("");
        var conflicts = Label("");
        var discard = new Button { Content=L("采用网站上的版本","Use website version") };
        var actions = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 8 };
        var add = new Button { Content = T("Add") };
        var update = new Button { Content = T("Update selected") };
        var remove = new Button { Content = T("Delete") };
        foreach (var button in new[] { add, update, remove }) actions.Children.Add(button);
        foreach (var element in new UIElement[] { search, list, entry, actions, feedback, conflicts, discard }) body.Children.Add(element);

        void Refresh()
        {
            var state = owner is null ? null : store.AccountNotes(owner);
            list.ItemsSource = state is null ? store.Memories(search.Text) : state.Notes(search.Text);
            add.IsEnabled = owner is null || state!.Enabled;
            update.IsEnabled = add.IsEnabled && list.SelectedItem is not null;
            remove.IsEnabled = list.SelectedItem is not null;
            toggle.Content = state?.Enabled == true ? L("关闭账号记忆","Turn off account memory") : L("开启账号记忆","Turn on account memory");
            var drafts = state?.Pending.Where(e => e.Conflict).ToArray() ?? [];
            discard.Visibility = conflicts.Visibility = drafts.Length > 0 ? Visibility.Visible : Visibility.Collapsed;
            conflicts.Text = L("这些修改需要核对。可以编辑草稿，或采用网站版本。","Review these changes. Edit the draft or use the website version.") +
                "\n" + string.Join("\n", drafts.Select(e => e.After ?? e.Before));
        }
        bool closed=false;
        async Task Sync(bool? value=null)
        {
            if (profile?.MemoryToken is null) return;
            toggle.IsEnabled=false;
            string message=await RefreshAccountMemoryAsync(profile,value);
            if (closed) return;
            feedback.Text=message;
            Refresh();
            toggle.IsEnabled=true;
        }
        void Change(Action action)
        {
            try
            {
                action();
                entry.Text = "";
                Refresh();
                feedback.Text = L("已保存在本机。","Saved on this PC.");
                _ = Sync();
            }
            catch (Exception problem) { feedback.Text = DialogError(problem); }
        }
        void Save(string? id)
        {
            if (owner is null) store.SaveMemory(entry.Text,id);
            else store.ChangeAccountNote(owner,id ?? Guid.NewGuid().ToString(),entry.Text);
        }
        search.TextChanged += (_, _) => Refresh();
        list.SelectionChanged += (_, _) =>
        {
            var note = list.SelectedItem as MemoryNote;
            update.IsEnabled = add.IsEnabled && note is not null;
            remove.IsEnabled = note is not null;
            if (note is not null) entry.Text = note.Text;
        };
        toggle.Click += async (_,_) => await Sync(!store.AccountNotes(owner!).Enabled);
        discard.Click += (_,_) => Change(() => store.DiscardConflicts(owner!));
        add.Click += (_, _) => Change(() => Save(null));
        update.Click += (_, _) => { if (list.SelectedItem is MemoryNote note) Change(() => Save(note.Id)); };
        remove.Click += (_, _) => { if (list.SelectedItem is MemoryNote note) Change(() => {
            if (owner is null) store.RemoveMemory(note.Id); else store.ChangeAccountNote(owner,note.Id,null);
        }); };
        Refresh();
        _ = Sync();
        try { await Dialog(L("长期记忆","Long-term memory"), Scroller(body)).ShowAsync(); }
        finally { closed=true; }
    }

    private async Task ShowTasksAsync()
    {
        var models = ConnectedModels(includeCloudCatalog: false).ToArray();
        var body = new StackPanel { Spacing = 10 };
        var worker = Label("");
        var startAtSignIn = new CheckBox { Content = T("Start the worker at Windows sign-in"), IsChecked = WindowsHost.StartsAtSignIn() };
        var startButton = new Button { Content = T("Start worker") };
        var workerRow = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 8 };
        workerRow.Children.Add(startAtSignIn);
        workerRow.Children.Add(startButton);
        body.Children.Add(worker);
        body.Children.Add(workerRow);
        body.Children.Add(Label(T("Tasks run in your Windows session while the PC is awake. The worker can continue after you close this window.")));

        var form = new StackPanel { Spacing = 7 };
        var name = Field(T("Name"), "", 80);
        var prompt = Field(T("Task prompt"), "", 30000, multiline: true);
        var model = new ComboBox
        {
            Header = T("Model"), ItemsSource = models, HorizontalAlignment = HorizontalAlignment.Stretch,
            SelectedItem = models.FirstOrDefault(choice => choice.Engine == settings.Provider) ?? models.FirstOrDefault()
        };
        var minutes = Field(T("Repeat every (minutes)"), "1440", 5);
        var search = new CheckBox { Content = T("Allow OpenAI web search for this task") };
        model.SelectionChanged += (_, _) =>
        {
            search.IsEnabled = model.SelectedItem is ModelChoice { Engine: Provider.OpenAI };
            if (!search.IsEnabled) search.IsChecked = false;
        };
        search.IsEnabled = model.SelectedItem is ModelChoice { Engine: Provider.OpenAI };
        var runNow = new CheckBox { Content = T("Run as soon as I save") };
        var addSchedule = new Button { Content = T("Save schedule"), IsEnabled = models.Length > 0 };
        if (models.Length == 0) form.Children.Add(Label(T("Connect a model in Settings to send AI messages.")));
        foreach (var element in new UIElement[] { name, prompt, model, minutes, search, runNow, addSchedule })
            form.Children.Add(element);
        var editor = new Expander { Header = T("New recurring task"), Content = form, HorizontalAlignment = HorizontalAlignment.Stretch };
        body.Children.Add(editor);

        var list = new ListView { Height = 130, DisplayMemberPath = nameof(TaskSchedule.Name) };
        var next = Label("");
        var taskActions = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 6 };
        var pause = new Button { Content = T("Pause") };
        var remove = new Button { Content = T("Delete task and runs") };
        var refresh = new Button { Content = T("Refresh") };
        foreach (var button in new[] { pause, remove, refresh }) taskActions.Children.Add(button);
        var runs = new ComboBox { Header = T("Recent runs"), HorizontalAlignment = HorizontalAlignment.Stretch };
        var result = new StackPanel { Spacing = 6 };
        var feedback = Label("");
        foreach (var element in new UIElement[] { list, next, taskActions, runs, result, feedback }) body.Children.Add(element);

        void ShowRun()
        {
            result.Children.Clear();
            if (runs.SelectedIndex < 0 || runs.Tag is not IReadOnlyList<TaskRun> history) return;
            var last = history[runs.SelectedIndex];
            if (last.Detail.Length > 0) result.Children.Add(Label(last.Detail));
            if (last.Result.Length == 0) return;
            MarkdownView.AddBlocks(result, last.Result, Brush("SideRailBrush"));
            AddSources(result, last.Sources);
            var save = new Button { Content = T("Save result…") };
            save.Click += async (_, _) => await TryAsync(() => SaveTextAsync(last.Result, "StarHub task result"));
            result.Children.Add(save);
        }

        void Select()
        {
            var selected = list.SelectedItem as TaskSchedule;
            pause.IsEnabled = remove.IsEnabled = selected is not null;
            pause.Content = selected?.Enabled == false ? "Resume" : "Pause";
            next.Text = selected is null ? "" : selected.Enabled
                ? $"Next run · {DateTimeOffset.FromUnixTimeMilliseconds(selected.NextRun).ToLocalTime():g}"
                : "Paused";
            var log = selected is null ? [] : store.TaskRuns(selected.Id);
            runs.Tag = log;
            runs.ItemsSource = log.Select(item =>
                $"{DateTimeOffset.FromUnixTimeMilliseconds(item.Started).ToLocalTime():g} · {item.State}").ToArray();
            runs.SelectedIndex = log.Count > 0 ? 0 : -1;
            ShowRun();
        }

        void Refresh(string? id = null)
        {
            id ??= (list.SelectedItem as TaskSchedule)?.Id;
            var schedules = store.Schedules();
            list.ItemsSource = schedules;
            list.SelectedItem = schedules.FirstOrDefault(item => item.Id == id) ?? schedules.FirstOrDefault();
            worker.Text = WindowsHost.IsRunning("worker.lock") ? "Background worker is running" : "Background worker is stopped";
            Select();
        }

        void Change(Action action)
        {
            try { action(); }
            catch (Exception problem) { feedback.Text = DialogError(problem); }
        }

        list.SelectionChanged += (_, _) => Change(Select);
        runs.SelectionChanged += (_, _) => Change(ShowRun);
        refresh.Click += (_, _) => Change(() => Refresh());
        startAtSignIn.Click += (_, _) => Change(() =>
        {
            WindowsHost.SetStartAtSignIn(startAtSignIn.IsChecked == true);
            if (startAtSignIn.IsChecked == true && !WindowsHost.IsRunning("worker.lock")) WindowsHost.StartWorker();
            feedback.Text = startAtSignIn.IsChecked == true ? "The worker will start at sign-in." : "Sign-in startup is disabled.";
        });
        startButton.Click += (_, _) => Change(() =>
        {
            WindowsHost.StartWorker();
            feedback.Text = "Worker launch requested. Refresh to check its status.";
            Refresh();
        });
        addSchedule.Click += (_, _) => Change(() =>
        {
            if (!int.TryParse(minutes.Text, out int interval) || interval is < 15 or > 43200)
                throw new StarHubException("Enter an interval between 15 and 43,200 minutes.");
            if (model.SelectedItem is not ModelChoice choice) throw new StarHubException(T("Connect a model in Settings to send AI messages."));
            var selected = choice.Engine;
            bool useSearch = search.IsChecked == true;
            _ = engine.Resolve(CloudSettings, new ChatOptions(selected, WebSearch: useSearch), prompt.Text);
            string id = Guid.NewGuid().ToString("N");
            var nextRun = DateTimeOffset.UtcNow.AddMinutes(runNow.IsChecked == true ? 0 : interval);
            store.AddSchedule(new TaskSchedule(id, name.Text, prompt.Text, selected, useSearch, interval,
                nextRun.ToUnixTimeMilliseconds(), true));
            name.Text = prompt.Text = "";
            editor.IsExpanded = false;
            feedback.Text = "Schedule saved.";
            Refresh(id);
            if (!WindowsHost.IsRunning("worker.lock")) WindowsHost.StartWorker();
        });
        pause.Click += (_, _) => Change(() =>
        {
            if (list.SelectedItem is not TaskSchedule item) return;
            store.SetScheduleEnabled(item.Id, !item.Enabled);
            if (!item.Enabled && !WindowsHost.IsRunning("worker.lock")) WindowsHost.StartWorker();
            Refresh(item.Id);
        });
        remove.Click += (_, _) => Change(() =>
        {
            if (list.SelectedItem is not TaskSchedule item) return;
            feedback.Text = store.RemoveSchedule(item.Id) ? "Schedule deleted." : "Wait for the active run to finish.";
            Refresh();
        });

        Refresh();
        await Dialog(T("Scheduled tasks"), Scroller(body)).ShowAsync();
    }

    private static TextBox Field(string header, string text, int limit, bool multiline = false) => new()
    {
        Header = header, Text = text, MaxLength = limit, AcceptsReturn = multiline,
        TextWrapping = multiline ? TextWrapping.Wrap : TextWrapping.NoWrap, MinHeight = multiline ? 90 : 0
    };

    private static TextBlock Label(string text) => new() { Text = text, TextWrapping = TextWrapping.Wrap };

    private static ScrollViewer Scroller(FrameworkElement content) => new()
    {
        MaxHeight = 490, Content = content, VerticalScrollBarVisibility = ScrollBarVisibility.Auto,
        HorizontalScrollBarVisibility = ScrollBarVisibility.Disabled
    };

    private static string DialogError(Exception problem)
    {
        if (problem is StarHubException) return problem.Message;
        WindowsHost.Log("desktop.log", $"{problem.GetType().Name} (0x{problem.HResult:X8}).");
        return "Star Hub could not save or load this item. See the local desktop log.";
    }
}
