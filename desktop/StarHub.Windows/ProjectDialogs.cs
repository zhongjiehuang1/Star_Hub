using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Automation;
using Microsoft.UI.Xaml.Controls;
using StarHub.Core;

namespace StarHub.Windows;

public sealed partial class MainWindow
{
    private Project? currentProject;
    private bool refreshingProjects;
    private sealed record ProjectMode(string Name, Func<Task> Open);

    private void RefreshProjects(string? id)
    {
        refreshingProjects = true;
        try
        {
            var chat = new Project("", T("All-purpose chat"));
            var projects = store.Projects();
            object[] entries =
            [
                chat, new ProjectMode(T("Generate or edit an image…"), ShowImagesAsync),
                new ProjectMode(T("Transcribe an audio file…"), ShowTranscribeAudioAsync),
                new ProjectMode(T("Live voice…"), ShowLiveVoiceAsync), .. projects
            ];
            ProjectPicker.ItemsSource = entries;
            ProjectPicker.SelectedItem = projects.FirstOrDefault(project => project.Id == id) ?? chat;
            currentProject = (ProjectPicker.SelectedItem as Project) is { Id.Length: > 0 } active ? active : null;
        }
        finally { refreshingProjects = false; }
    }

    private async void ProjectChanged(object sender, SelectionChangedEventArgs args)
    {
        if (loading || refreshingProjects || request is not null) return;
        if (ProjectPicker.SelectedItem is ProjectMode mode)
        {
            RefreshProjects(currentProject?.Id);
            await TryAsync(mode.Open);
            return;
        }
        Try(() =>
        {
            currentProject = (ProjectPicker.SelectedItem as Project) is { Id.Length: > 0 } project ? project : null;
            RememberProject();
            HistoryFilter.Text = "";
            NewConversation();
            RefreshHistory();
            UpdateRoute();
        });
    }

    private void RememberProject()
    {
        settings = settings with { OpenProjectId = currentProject?.Id };
        store.SavePreferences(settings);
    }

    private async void ProjectsClicked(object sender, RoutedEventArgs args)
    {
        if (request is null) await TryAsync(ShowProjectsAsync);
    }

    private async Task ShowProjectsAsync()
    {
        var body = new StackPanel { Spacing = 8 };
        var projects = new ComboBox { Header = T("Project"), HorizontalAlignment = HorizontalAlignment.Stretch };
        var name = Field(T("Project name"), "", 90);
        var create = new Button { Content = T("Create project") };
        var rename = new Button { Content = T("Rename selected") };
        var docs = new ListView { Height = 155 };
        var add = new Button { Content = T("Import or replace document…") };
        var remove = new Button { Content = T("Remove selected document") };
        var search = Field(T("Search the selected project"), "", 400);
        var preview = Label("");
        var status = Label("");
        var names = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 8 };
        names.Children.Add(create);
        names.Children.Add(rename);
        var actions = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 8 };
        actions.Children.Add(add);
        actions.Children.Add(remove);
        AutomationProperties.SetName(docs, "Knowledge base documents");
        foreach (var item in new UIElement[] { projects, name, names, docs, actions, search, preview, status })
            body.Children.Add(item);

        Project? Selected() => projects.SelectedItem as Project;

        void RefreshDocuments()
        {
            var project = Selected();
            docs.ItemsSource = project is null ? Array.Empty<ProjectDocument>() : store.ProjectDocuments(project.Id);
            rename.IsEnabled = add.IsEnabled = search.IsEnabled = project is not null;
            remove.IsEnabled = false;
            UpdatePreview();
        }

        void UpdatePreview()
        {
            var project = Selected();
            preview.Text = project is null || string.IsNullOrWhiteSpace(search.Text) ? "" : string.Join("\n\n",
                store.SearchProject(project.Id, search.Text).Take(3).Select(excerpt =>
                    $"{excerpt.Reference.Document} · section {excerpt.Reference.Section}\n{excerpt.Text[..Math.Min(220, excerpt.Text.Length)]}"));
        }

        void ListProjects(string? id)
        {
            var values = store.Projects();
            projects.ItemsSource = values;
            projects.SelectedItem = values.FirstOrDefault(item => item.Id == id) ?? values.FirstOrDefault();
            RefreshDocuments();
        }

        void Change(Action action)
        {
            try { action(); }
            catch (Exception error) { status.Text = DialogError(error); }
        }

        projects.SelectionChanged += (_, _) => Change(() =>
        {
            name.Text = Selected()?.Name ?? "";
            RefreshDocuments();
        });
        docs.SelectionChanged += (_, _) => remove.IsEnabled = docs.SelectedItem is ProjectDocument;
        search.TextChanged += (_, _) => Change(UpdatePreview);
        create.Click += (_, _) => Change(() =>
        {
            var project = store.CreateProject(name.Text);
            ListProjects(project.Id);
            status.Text = "Project created.";
        });
        rename.Click += (_, _) => Change(() =>
        {
            if (Selected() is not { } project) return;
            store.RenameProject(project.Id, name.Text);
            ListProjects(project.Id);
            status.Text = "Project renamed.";
        });
        add.Click += async (_, _) =>
        {
            if (Selected() is not { } project) return;
            add.IsEnabled = false;
            try
            {
                if (await ChooseFileAsync(KnowledgeDocument.Extensions) is not { } file) return;
                status.Text = "Indexing the document on this PC…";
                using var input = await file.OpenStreamForReadAsync();
                var content = await KnowledgeDocument.ReadAsync(file.Name, input);
                var saved = await Task.Run(() => store.SaveProjectDocument(project.Id, file.Name, content));
                status.Text = $"Indexed {saved.Name} · {saved.Sections} sections.";
                RefreshDocuments();
            }
            catch (Exception error) { status.Text = DialogError(error); }
            finally { add.IsEnabled = Selected() is not null; }
        };
        remove.Click += (_, _) => Change(() =>
        {
            if (Selected() is not { } project || docs.SelectedItem is not ProjectDocument document) return;
            store.DeleteProjectDocument(project.Id, document.Id);
            status.Text = "Removed from the project index.";
            RefreshDocuments();
        });
        ListProjects(currentProject?.Id);
        var dialog = Dialog(T("Projects and knowledge base"), Scroller(body));
        dialog.PrimaryButtonText = T("Open selected project");
        var result = await dialog.ShowAsync();
        string? id = result == ContentDialogResult.Primary && Selected() is { } selected ? selected.Id : currentProject?.Id;
        bool changed = id != currentProject?.Id;
        RefreshProjects(id);
        RememberProject();
        if (changed)
        {
            HistoryFilter.Text = "";
            NewConversation();
            RefreshHistory();
        }
        UpdateRoute();
    }
}
