using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using StarHub.Platform;

namespace StarHub.Windows;

public sealed partial class MainWindow
{
    private async void AdministratorClicked(object sender, RoutedEventArgs args) => await TryAsync(ShowAdministratorToolsAsync);

    private async Task ShowAdministratorToolsAsync()
    {
        var operations = Enum.GetValues<AdministratorOperation>();
        var options = new ComboBox { Header = T("Windows maintenance operation"), ItemsSource = operations.Select(AdministratorTools.Label).ToArray() };
        options.SelectedIndex = 0;
        var description = Label("Clears cached DNS lookups. Windows rebuilds the cache when applications resolve names.");
        options.SelectionChanged += (_, _) => description.Text = options.SelectedIndex == 0
            ? "Clears cached DNS lookups. Windows rebuilds the cache when applications resolve names."
            : "Uses Windows DISM to check the component store for a previously detected problem.";
        var launch = new Button { Content = T("Run with Windows administrator approval") };
        var status = Label("");
        var panel = new StackPanel { Spacing = 12 };
        panel.Children.Add(options);
        panel.Children.Add(description);
        panel.Children.Add(launch);
        panel.Children.Add(status);
        using var lifetime = new CancellationTokenSource();
        Task? pending = null;
        launch.Click += (_, _) => pending = RunAsync();

        async Task RunAsync()
        {
            if (!launch.IsEnabled || options.SelectedIndex < 0) return;
            var operation = operations[options.SelectedIndex];
            options.IsEnabled = launch.IsEnabled = false;
            status.Text = "Waiting for Windows…";
            try
            {
                int code = await AdministratorTools.RunAsync(operation, lifetime.Token);
                status.Text = code == 0 ? "The Windows tool completed successfully."
                    : $"The Windows tool exited with code {code}. Check its console output.";
            }
            catch (OperationCanceledException) when (lifetime.IsCancellationRequested) { }
            catch (Exception error) { status.Text = DialogError(error); }
            finally { options.IsEnabled = launch.IsEnabled = true; }
        }

        await Dialog(T("Windows maintenance"), panel).ShowAsync();
        lifetime.Cancel();
        if (pending is not null) await pending;
    }
}
