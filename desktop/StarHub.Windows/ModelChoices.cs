using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using StarHub.Core;

namespace StarHub.Windows;

public sealed partial class MainWindow
{
    private bool openAiReady;
    private DesktopAccountProfile? accountProfile;
    private ModelChoice? accountChoice;
    private ModelChoice[] modelChoices = [];
    private ModelChoice? chosenModel;
    private string? reasoningEffort;

    private sealed record ModelChoice(Provider Engine, string Id, string Label)
    {
        public override string ToString() => Label;
    }

    private Provider SelectedProvider => chosenModel?.Engine ?? Provider.Automatic;
    private Preferences CloudSettings => accountProfile is { } profile ? settings with { OpenAiModel = profile.Model } : settings;
    private Preferences ChatSettings => chosenModel is { Engine: Provider.OpenAI } selection
        ? CloudSettings with { OpenAiModel = selection.Id } : CloudSettings;

    private IEnumerable<ModelChoice> ConnectedModels(bool includeCloudCatalog)
    {
        if (openAiReady)
        {
            var models = includeCloudCatalog ? ModelCatalog.OpenAiModels : [];
            foreach (var model in models) yield return new(Provider.OpenAI, model.Id, model.Name);
            string cloudModel = CloudSettings.OpenAiModel;
            if (!models.Any(model => model.Id == cloudModel))
                yield return new(Provider.OpenAI, cloudModel, ModelName(cloudModel));
        }
        if (!string.IsNullOrWhiteSpace(settings.OllamaModel))
            yield return new(Provider.Ollama, settings.OllamaModel, settings.OllamaModel + " · " + T("On this PC"));
        if (!string.IsNullOrWhiteSpace(settings.CompatibleModel) && !string.IsNullOrWhiteSpace(settings.CompatibleEndpoint))
            yield return new(Provider.Compatible, settings.CompatibleModel, settings.CompatibleModel + " · " + T("Custom connection"));
    }

    private void ReloadModelChoices()
    {
        openAiReady = accountProfile is { } linked ? linked.ApiKey is not null : credentials.Read(ChatEngine.OpenAiCredential) is not null;
        modelChoices = [new(Provider.Automatic, "", T("Automatic")), .. ConnectedModels(includeCloudCatalog: true)];
        var desired = settings.ChatOpenAiModel ?? settings.OpenAiModel;
        if (accountProfile is not null)
        {
            chosenModel = modelChoices.FirstOrDefault(choice => choice.Engine == accountChoice?.Engine && choice.Id == accountChoice.Id) ??
                modelChoices[0];
            return;
        }
        chosenModel = modelChoices.FirstOrDefault(choice => choice.Engine == settings.Provider &&
            (choice.Engine != Provider.OpenAI || choice.Id == desired))
            ?? modelChoices.FirstOrDefault(choice => choice.Engine == settings.Provider && choice.Id == settings.OpenAiModel)
            ?? modelChoices[0];
    }

    private string[] ReasoningOptions(ModelChoice? model) => openAiReady && model?.Engine is (Provider.OpenAI or Provider.Automatic)
        ? ModelCatalog.OpenAiModels.FirstOrDefault(item => item.Id == (model.Engine == Provider.OpenAI ? model.Id : CloudSettings.OpenAiModel))?.Reasoning ?? []
        : [];

    private void UpdateReasoning()
    {
        if (reasoningEffort is { } effort && !ReasoningOptions(chosenModel).Contains(effort))
            reasoningEffort = null;
    }

    private async void ModelSettingsClicked(object sender, RoutedEventArgs args) => await TryAsync(async () =>
    {
        var model = new ComboBox { Header = T("Model"), ItemsSource = modelChoices, SelectedItem = chosenModel, HorizontalAlignment = HorizontalAlignment.Stretch };
        var reasoning = new ComboBox { Header = T("Reasoning effort"), HorizontalAlignment = HorizontalAlignment.Stretch };
        void ApplyOptions()
        {
            var options = ReasoningOptions(model.SelectedItem as ModelChoice);
            reasoning.ItemsSource = new[] { T("Default effort") }.Concat(options).ToArray();
            reasoning.SelectedIndex = Math.Max(0, Array.IndexOf(options, reasoningEffort) + 1);
            reasoning.Visibility = options.Length > 0 ? Visibility.Visible : Visibility.Collapsed;
        }
        model.SelectionChanged += (_, _) => ApplyOptions();
        ApplyOptions();
        var body = new StackPanel { Spacing = 10 };
        body.Children.Add(model);
        body.Children.Add(reasoning);
        var dialog = Dialog(T("Model and reasoning…"), body);
        dialog.PrimaryButtonText = T("Save settings");
        dialog.SecondaryButtonText = T("Model connections…");
        var result = await dialog.ShowAsync();
        if (result == ContentDialogResult.Secondary)
        {
            await ShowModelConnectionsAsync();
            return;
        }
        if (result != ContentDialogResult.Primary || model.SelectedItem is not ModelChoice selected) return;
        if (accountProfile is not null) accountChoice = selected;
        else
        {
            var updated = settings with { Provider = selected.Engine,
                ChatOpenAiModel = selected.Engine == Provider.OpenAI ? selected.Id : settings.ChatOpenAiModel };
            store.SavePreferences(updated);
            settings = updated;
        }
        chosenModel = selected;
        reasoningEffort = reasoning.SelectedIndex > 0 ? reasoning.SelectedItem as string : null;
        UpdateTools();
        UpdateRoute();
    });

    private static string ModelName(string id) =>
        ModelCatalog.OpenAiModels.FirstOrDefault(model => model.Id == id)?.Name ?? id;
}
