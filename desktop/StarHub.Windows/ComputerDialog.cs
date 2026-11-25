using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using StarHub.Core;
using StarHub.Platform;

namespace StarHub.Windows;

public sealed partial class MainWindow
{
    private async void ComputerUseClicked(object sender, RoutedEventArgs args) => await TryAsync(ShowComputerUseAsync);

    private async Task ShowComputerUseAsync()
    {
        var body = new StackPanel { Spacing = 8 };
        var windows = new ComboBox { Header = T("Notepad window"), HorizontalAlignment = HorizontalAlignment.Stretch };
        var models = new ComboBox
        {
            Header = T("Computer Use model"), ItemsSource = ModelCatalog.ComputerModels,
            SelectedItem = ModelCatalog.Terra
        };
        var open = new Button { Content = T("Open Notepad") };
        var list = new Button { Content = T("Refresh windows") };
        var captureButton = new Button { Content = T("Capture window on this PC") };
        var ask = new Button { Content = T("Send capture and propose edit") };
        var apply = new Button { Content = T("Apply proposed edit to Notepad") };
        var verify = new Button { Content = T("Send new capture to verify") };
        var task = Field(T("Describe the desired text edit"), "", 3000, multiline: true);
        var preview = new Image { MaxHeight = 240, Stretch = Microsoft.UI.Xaml.Media.Stretch.Uniform };
        var suggested = Field(T("Proposed text"), "", 0, multiline: true);
        suggested.IsReadOnly = true;
        suggested.MaxHeight = 170;
        suggested.Visibility = Visibility.Collapsed;
        var status = Label(T("Preview a Notepad window before sending its contents to OpenAI. You control when a proposed edit is applied."));
        var windowButtons = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 7 };
        windowButtons.Children.Add(open);
        windowButtons.Children.Add(list);
        foreach (var item in new UIElement[] { windows, windowButtons, models, task, captureButton, preview, ask, suggested, apply, verify, status })
            body.Children.Add(item);

        NotepadCapture? frame = null;
        ComputerSession? session = null;
        ComputerProposal? proposal = null;
        bool busy = false, applied = false;
        using var lifetime = new CancellationTokenSource();
        Task? pending = null;

        void Controls()
        {
            windows.IsEnabled = open.IsEnabled = list.IsEnabled = !busy;
            models.IsEnabled = !busy && session is null;
            captureButton.IsEnabled = !busy && windows.SelectedItem is NotepadWindow;
            ask.IsEnabled = !busy && frame is not null;
            apply.Visibility = proposal?.CanEdit == true && !applied ? Visibility.Visible : Visibility.Collapsed;
            apply.IsEnabled = !busy;
            verify.Visibility = proposal?.ScreenshotOnly == true || applied ? Visibility.Visible : Visibility.Collapsed;
            verify.IsEnabled = !busy;
        }

        void ClearCapture()
        {
            frame = null;
            session = null;
            proposal = null;
            applied = false;
            preview.Source = null;
            suggested.Visibility = Visibility.Collapsed;
            Controls();
        }

        async Task WorkAsync(Func<Task> operation, string message)
        {
            if (busy) return;
            busy = true;
            Controls();
            status.Text = message;
            try { await operation(); }
            catch (OperationCanceledException) { status.Text = "The Computer Use request ended or timed out."; }
            catch (Exception error) { status.Text = DialogError(error); }
            finally { busy = false; Controls(); }
        }

        async Task ListAsync()
        {
            var windowsFound = await Task.Run(NotepadDesktop.Windows, lifetime.Token);
            windows.ItemsSource = windowsFound;
            windows.SelectedIndex = windowsFound.Count > 0 ? 0 : -1;
            status.Text = windowsFound.Count > 0 ? "Select the Notepad document to work with." : "Open or restore a Notepad window, then refresh.";
        }

        async Task ObserveAsync(NotepadWindow window)
        {
            frame = await Task.Run(() => NotepadDesktop.Capture(window), lifetime.Token);
            preview.Source = await PngSourceAsync(frame.Png, 700);
        }

        void Present(ComputerProposal result)
        {
            proposal = result;
            applied = false;
            suggested.Visibility = result.CanEdit ? Visibility.Visible : Visibility.Collapsed;
            suggested.Text = result.CanEdit ? result.Actions.Single(action => action.Type == "type").Text ?? "" : "";
            status.Text = result.CanEdit ? "Review the replacement text before applying it to the unsaved document."
                : result.ScreenshotOnly ? "The model requested a window capture."
                : result.CallId is null ? result.Message
                : "The model requested an action outside the Notepad text area. Refine the task and send another proposal.";
        }

        windows.SelectionChanged += (_, _) => ClearCapture();
        models.SelectionChanged += (_, _) =>
        {
            session = null;
            proposal = null;
            applied = false;
            suggested.Visibility = Visibility.Collapsed;
            Controls();
        };
        open.Click += (_, _) => pending = WorkAsync(async () =>
        {
            NotepadDesktop.OpenNotepad();
            await Task.Delay(1500, lifetime.Token);
            await ListAsync();
        }, "Opening Notepad…");
        list.Click += (_, _) => pending = WorkAsync(ListAsync, "Listing open Notepad windows…");
        captureButton.Click += (_, _) => pending = WorkAsync(async () =>
        {
            if (windows.SelectedItem is not NotepadWindow window) return;
            if (!applied && proposal?.ScreenshotOnly != true) ClearCapture();
            await ObserveAsync(window);
            status.Text = "Review the local capture before sending it to OpenAI.";
        }, "Capturing the selected Notepad window…");
        ask.Click += (_, _) => pending = WorkAsync(async () =>
        {
            if (frame is null) return;
            proposal = null;
            applied = false;
            var currentSession = new ComputerSession(providerClient, MediaKey(), (string)models.SelectedItem);
            Present(await currentSession.StartAsync(task.Text, frame.Png, lifetime.Token));
            session = currentSession;
        }, "Requesting a Computer Use proposal…");
        apply.Click += (_, _) => pending = WorkAsync(async () =>
        {
            if (frame is not { } captured || proposal is not { CanEdit: true } edit || applied) return;
            await Task.Run(() => NotepadDesktop.Apply(captured, edit), lifetime.Token);
            applied = true;
            try
            {
                await ObserveAsync(captured.Window);
                status.Text = "Windows confirmed the text change. Review the capture before sending it back.";
            }
            catch (Exception error)
            {
                frame = null;
                status.Text = "Windows confirmed the edit. Capture the window again to request model verification.";
                StarHub.Platform.WindowsHost.Log("desktop.log", $"Computer Use post-edit capture: {error.GetType().Name}.");
            }
        }, "Applying the reviewed text to Notepad…");
        verify.Click += (_, _) => pending = WorkAsync(async () =>
        {
            if (session is null || (!applied && proposal?.ScreenshotOnly != true)) return;
            if (frame is null) throw new StarHubException("Capture the current Notepad window again.");
            var current = await Task.Run(() => NotepadDesktop.Capture(frame.Window), lifetime.Token);
            if (current.Bounds != frame.Bounds || current.EditorText != frame.EditorText)
                throw new StarHubException("The Notepad window changed. Capture it before continuing.");
            var active = session;
            proposal = null;
            applied = false;
            Present(await active.SubmitScreenshotAsync(frame.Png, lifetime.Token));
        }, "Sending the displayed capture for verification…");

        await ListAsync();
        Controls();
        await Dialog(T("Computer Use · Notepad"), Scroller(body)).ShowAsync();
        lifetime.Cancel();
        if (pending is not null) await pending;
    }
}
