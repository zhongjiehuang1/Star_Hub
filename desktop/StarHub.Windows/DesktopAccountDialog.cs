using System.Diagnostics;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Automation;
using Microsoft.UI.Xaml.Controls;
using StarHub.Core;

namespace StarHub.Windows;

public sealed partial class MainWindow
{
    private void UpdateAccountLabel() => DesktopAccountLabel.Text = accountProfile?.Name ??
        T(accountProfile is null ? "Sign in to Star Hub" : "Star Hub account");

    private async void DesktopAccountClicked(object sender, RoutedEventArgs args) => await TryAsync(ManageDesktopAccountAsync);

    private async Task ManageDesktopAccountAsync()
    {
        if (accountProfile is null)
        {
            await ConnectDesktopAccountAsync();
            return;
        }

        var original = accountProfile;
        var details = new StackPanel { Spacing = 8 };
        details.Children.Add(Label(original.Email));
        var profileName = Field(T("Display name on this PC"), original.DesktopDisplayName ?? original.Name ?? "", 120);
        AutomationProperties.SetAutomationId(profileName, "DesktopDisplayName");
        details.Children.Add(profileName);
        details.Children.Add(Label(T("Clear this field to use your website display name.")));
        var refresh = new Button { Content = T("Refresh from website") };
        details.Children.Add(refresh);
        details.Children.Add(Label(accountProfile.ApiKey is null
            ? T("Save an OpenAI key on the website, then refresh this connection.")
            : ModelName(accountProfile.Model)));
        details.Children.Add(Label(T("Conversations and files stay on this PC.")));
        var dialog = Dialog(accountProfile.Name ?? T("Star Hub account"), details);
        dialog.PrimaryButtonText = T("Save name");
        dialog.SecondaryButtonText = T("Disconnect account");
        bool refreshAccount = false;
        refresh.Click += (_, _) => { refreshAccount = true; dialog.Hide(); };
        var result = await dialog.ShowAsync();
        if (refreshAccount) await ConnectDesktopAccountAsync();
        if (result == ContentDialogResult.Primary)
        {
            var value = profileName.Text.Trim();
            var profile = original.WithDesktopName(original.DesktopDisplayName is null && value == original.Name ? null : value);
            desktopAccount.Save(profile);
            ApplyDesktopAccount(profile);
        }
        if (result == ContentDialogResult.Secondary)
        {
            desktopAccount.Disconnect();
            ApplyDesktopAccount(null);
            Say(T("Star Hub account disconnected."));
        }
    }

    private async Task ConnectDesktopAccountAsync()
    {
        using var cancellation = new CancellationTokenSource();
        var dialog = Dialog(T("Sign in to Star Hub"), Label(T("Finish connecting in your browser. Conversations and files stay on this PC.")));
        dialog.CloseButtonText = T("Cancel");
        var shown = dialog.ShowAsync().AsTask();
        var connect = new DesktopAccountClient().ConnectAsync(uri =>
        {
            using var process = Process.Start(new ProcessStartInfo(uri.AbsoluteUri) { UseShellExecute = true });
        }, cancellation.Token);
        try
        {
            if (await Task.WhenAny(connect, shown) == shown) cancellation.Cancel();
            var profile = await connect;
            cancellation.Token.ThrowIfCancellationRequested();
            profile = profile.WithDesktopName(string.Equals(profile.Email, accountProfile?.Email, StringComparison.OrdinalIgnoreCase)
                ? accountProfile?.DesktopDisplayName : null);
            desktopAccount.Save(profile);
            ApplyDesktopAccount(profile);
            Say(T(profile.ApiKey is null ? "Save an OpenAI key on the website, then refresh this connection." : "Star Hub account connected."));
        }
        catch (OperationCanceledException) when (cancellation.IsCancellationRequested) { }
        finally
        {
            if (!shown.IsCompleted) dialog.Hide();
            await shown;
        }
    }

    private void ApplyDesktopAccount(DesktopAccountProfile? profile)
    {
        accountProfile = profile;
        accountChoice = null;
        UpdateAccountLabel();
        loading = true;
        try { ReloadModelChoices(); }
        finally { loading = false; }
        UpdateReasoning();
        UpdateTools();
        UpdateRoute();
    }
}
