using System.Security;
using StarHub.Core;
using Windows.Data.Xml.Dom;
using Windows.UI.Notifications;

namespace StarHub.Platform;

public static class TaskNotification
{
    // The installed Start menu shortcut uses the same AppUserModelID.
    private const string AppId = "StarHub.Desktop";
    private static int posting;

    public static void Post(TurnState state)
    {
        if (state is TurnState.Cancelled or TurnState.Interrupted || Interlocked.Exchange(ref posting, 1) != 0) return;
        _ = Task.Run(() =>
        {
            try
            {
                string description = state == TurnState.Completed
                    ? "A scheduled task completed. Open Scheduled tasks to see the result."
                    : "A scheduled task needs attention. Open Scheduled tasks to see its status.";
                Show("Star Hub", description);
            }
            finally { Volatile.Write(ref posting, 0); }
        });
    }

    public static bool PostDeviceAlert(DeviceAlert alert) => Show(alert.Title, alert.Message, device: true);

    private static bool Show(string title, string description, bool device = false)
    {
        try
        {
            var xml = new XmlDocument();
            xml.LoadXml($"""
                <toast><visual><binding template="ToastGeneric">
                <text>{SecurityElement.Escape(title)}</text><text>{SecurityElement.Escape(description)}</text>
                </binding></visual></toast>
                """);
            var notification = new ToastNotification(xml);
            if (device)
            {
                notification.Group = "device";
                notification.Tag = "performance";
            }
            ToastNotificationManager.CreateToastNotifier(AppId).Show(notification);
            return true;
        }
        catch (Exception error)
        {
            WindowsHost.Log("worker.log", $"Notifications unavailable (0x{error.HResult:X8}).");
            return false;
        }
    }
}
