using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using StarHub.Core;

namespace StarHub.Windows;

public sealed partial class MainWindow
{
    private bool installingModel;
    private int modelPercent;

    private void UpdateOfflineSetup()
    {
        OfflineSetup.Visibility = localQwen.IsInstalled ? Visibility.Collapsed : Visibility.Visible;
        OfflineSetup.IsEnabled = !installingModel;
        OfflineSetup.Content = installingModel
            ? WorkText($"正在安装千问 · {modelPercent}%", $"Installing Qwen · {modelPercent}%")
            : WorkText("安装离线千问 · 491 MB", "Set up offline chat · 491 MB");
        ToolTipService.SetToolTip(OfflineSetup, WorkText("联网下载一次，以后断网也能聊天。", "Download once while online to chat without internet."));
    }

    private async void InstallQwenClicked(object sender, RoutedEventArgs args) => await TryAsync(async () =>
    {
        if (installingModel || localQwen.IsInstalled) return;
        installingModel = true;
        modelPercent = 0;
        UpdateOfflineSetup();
        try
        {
            var progress = new Progress<int>(value => { modelPercent = value; if (installingModel) UpdateOfflineSetup(); });
            await localQwen.Download.InstallAsync(progress, windowLifetime.Token);
            ReloadModelChoices();
            Say(WorkText("千问已就绪。断网后也可以在这里直接聊天。", "Qwen is ready. You can chat here even without internet."));
        }
        catch (OperationCanceledException) when (windowLifetime.IsCancellationRequested) { }
        catch (HttpRequestException)
        {
            Say(WorkText("模型下载未完成。联网后再点一次即可继续。", "Download interrupted. Click again when online to continue."));
        }
        catch (IOException)
        {
            Say(WorkText("无法保存模型。请检查可用磁盘空间后再试。", "The model could not be saved. Check the available disk space and try again."));
        }
        finally
        {
            installingModel = false;
            if (!windowLifetime.IsCancellationRequested) UpdateOfflineSetup();
        }
    });

    private void SetMemoryReconnect(bool needed)
    {
        if (!windowLifetime.IsCancellationRequested) MemoryReconnect.Visibility = needed ? Visibility.Visible : Visibility.Collapsed;
    }

    private void MemoryNetworkChanged(object? sender, System.Net.NetworkInformation.NetworkAvailabilityEventArgs args)
    {
        if (args.IsAvailable) DispatcherQueue.TryEnqueue(() => { if (!windowLifetime.IsCancellationRequested) ResumeMemoryConnection(); });
    }

    private void ResumeMemoryConnection()
    {
        var profile = accountProfile;
        SetMemoryReconnect(profile is not null && !profile.CanRestoreMemory);
        if (profile?.CanRestoreMemory == true) _ = RefreshAccountMemoryAsync(profile);
    }

    private async void ReconnectMemoryClicked(object sender, RoutedEventArgs args) => await TryAsync(async () =>
    {
        if (accountProfile is { } current) await ConnectDesktopAccountAsync(current);
    });
}
