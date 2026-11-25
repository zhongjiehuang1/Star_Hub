using System.Diagnostics;
using System.Drawing;
using System.Drawing.Imaging;
using System.Runtime.InteropServices;
using System.Text;
using FlaUI.Core.AutomationElements;
using FlaUI.Core.Definitions;
using FlaUI.UIA3;
using StarHub.Core;

namespace StarHub.Platform;

public sealed record NotepadWindow(nint Handle, int ProcessId, string Title)
{
    public override string ToString() => Title;
}

public sealed record NotepadCapture(NotepadWindow Window, Rectangle Bounds, byte[] Png, string EditorText);

public static class NotepadDesktop
{
    private const int MaxDocument = 12_000;

    public static IReadOnlyList<NotepadWindow> Windows()
    {
        var results = new List<NotepadWindow>();
        EnumWindows((handle, _) =>
        {
            if (!IsWindowVisible(handle) || IsIconic(handle)) return true;
            GetWindowThreadProcessId(handle, out uint pid);
            if (pid == 0 || !TrustedNotepad((int)pid)) return true;
            var title = new StringBuilder(260);
            _ = ReadTitle(handle, title, title.Capacity);
            if (title.Length != 0) results.Add(new NotepadWindow(handle, (int)pid, title.ToString()));
            return true;
        }, 0);
        return results.OrderBy(window => window.Title, StringComparer.CurrentCultureIgnoreCase).ToArray();
    }

    public static void OpenNotepad()
    {
        string executable = Path.Combine(Environment.SystemDirectory, "notepad.exe");
        using var process = Process.Start(new ProcessStartInfo(executable) { UseShellExecute = true });
    }

    public static NotepadCapture Capture(NotepadWindow window)
    {
        var bounds = Validate(window);
        using var automation = new UIA3Automation();
        var editors = Editors(automation, window);
        if (editors.Length != 1)
            throw new StarHubException("Open a single visible, editable document in this Notepad window.");
        string current = EditorText(editors[0]);
        using var bitmap = new Bitmap(bounds.Width, bounds.Height, PixelFormat.Format32bppRgb);
        using (var graphics = Graphics.FromImage(bitmap))
        {
            var dc = graphics.GetHdc();
            try
            {
                if (!PrintWindow(window.Handle, dc, 2)) throw new StarHubException("Windows could not capture this Notepad window.");
            }
            finally { graphics.ReleaseHdc(dc); }
        }
        using var bytes = new MemoryStream();
        bitmap.Save(bytes, ImageFormat.Png);
        if (Validate(window) != bounds) throw new StarHubException("The Notepad window moved. Capture it again.");
        if (EditorText(editors[0]) != current)
            throw new StarHubException("The Notepad document changed. Capture it again.");
        return new NotepadCapture(window, bounds, bytes.ToArray(), current);
    }

    public static void Apply(NotepadCapture capture, ComputerProposal proposal)
    {
        if (!proposal.CanEdit) throw new StarHubException("The action batch is outside the Notepad editor scope.");
        var window = capture.Window;
        var bounds = Validate(window);
        if (bounds != capture.Bounds) throw new StarHubException("The Notepad window moved or resized. Capture it again.");
        using var automation = new UIA3Automation();
        var editors = Editors(automation, window);
        if (editors.Length != 1 || EditorText(editors[0]) != capture.EditorText)
            throw new StarHubException("The Notepad document changed. Capture it again.");
        var editor = editors[0];

        // Resolve every proposed click before editing. Coordinates never leave this document.
        foreach (var action in proposal.Actions.Where(action => action.Type == "click"))
        {
            if (action.X < 0 || action.Y < 0 || action.X >= bounds.Width || action.Y >= bounds.Height
                || !editor.BoundingRectangle.Contains(bounds.Left + action.X, bounds.Top + action.Y))
                throw new StarHubException("The proposed click was outside the Notepad text area.");
        }
        var text = proposal.Actions.Single(action => action.Type == "type").Text!;
        if (Validate(window) != capture.Bounds || EditorText(editor) != capture.EditorText)
            throw new StarHubException("The Notepad document changed. Capture it again.");
        SetEditorText(window, editor, text);
        if (Normalize(EditorText(editor)) != Normalize(text))
            throw new StarHubException("Windows did not confirm the proposed text in Notepad.");
    }

    private static Rectangle Validate(NotepadWindow target)
    {
        GetWindowThreadProcessId(target.Handle, out uint pid);
        if (pid == 0 || pid != target.ProcessId || !IsWindowVisible(target.Handle) || IsIconic(target.Handle)
            || IsHungAppWindow(target.Handle) || !TrustedNotepad(target.ProcessId) || !GetWindowRect(target.Handle, out var rect))
            throw new StarHubException("Restore or reopen the selected Notepad window.");
        var bounds = Rectangle.FromLTRB(rect.Left, rect.Top, rect.Right, rect.Bottom);
        if (bounds.Width < 180 || bounds.Height < 120 || bounds.Width > 2048 || bounds.Height > 1600)
            throw new StarHubException("Resize Notepad between 180 × 120 and 2048 × 1600 pixels to capture it.");
        return bounds;
    }

    private static bool TrustedNotepad(int pid)
    {
        try
        {
            using var process = Process.GetProcessById(pid);
            string? image = process.MainModule?.FileName;
            if (image is null || !string.Equals(Path.GetFileName(image), "notepad.exe", StringComparison.OrdinalIgnoreCase)) return false;
            string system = Environment.SystemDirectory;
            string windows = Environment.GetFolderPath(Environment.SpecialFolder.Windows);
            string packages = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ProgramFiles), "WindowsApps");
            return string.Equals(image, Path.Combine(system, "notepad.exe"), StringComparison.OrdinalIgnoreCase)
                || string.Equals(image, Path.Combine(windows, "notepad.exe"), StringComparison.OrdinalIgnoreCase)
                || image.StartsWith(Path.Combine(packages, "Microsoft.WindowsNotepad_"), StringComparison.OrdinalIgnoreCase);
        }
        catch (Exception error) when (error is ArgumentException or InvalidOperationException or System.ComponentModel.Win32Exception)
        { return false; }
    }

    private static AutomationElement[] Editors(UIA3Automation automation, NotepadWindow window)
    {
        var root = automation.FromHandle(window.Handle);
        return root.FindAllDescendants(condition => condition.ByControlType(ControlType.Edit).Or(condition.ByControlType(ControlType.Document)))
            .Where(element => element.Properties.ProcessId.ValueOrDefault == window.ProcessId &&
                element.Properties.IsEnabled.ValueOrDefault && !element.Properties.IsOffscreen.ValueOrDefault &&
                !element.Properties.IsPassword.ValueOrDefault && HasEditor(element)).ToArray();
    }

    private static bool HasEditor(AutomationElement element)
    {
        if (element.Patterns.Value.TryGetPattern(out var value) && !value.IsReadOnly.ValueOrDefault) return true;
        return NativeEditor(element.Properties.NativeWindowHandle.ValueOrDefault);
    }

    private static string EditorText(AutomationElement editor)
    {
        string text;
        if (editor.Patterns.Value.TryGetPattern(out var value))
            text = value.Value.ValueOrDefault ?? "";
        else if (editor.Patterns.Text.TryGetPattern(out var reader))
            text = reader.DocumentRange.GetText(MaxDocument + 1);
        else
        {
            var buffer = new StringBuilder(MaxDocument + 2);
            var handle = editor.Properties.NativeWindowHandle.ValueOrDefault;
            if (!NativeEditor(handle) || ReadNative(handle, 0x000D, buffer.Capacity, buffer, 2, 1200, out _) == 0)
                throw new StarHubException("Windows could not read the selected editor.");
            text = buffer.ToString();
        }
        if (text.Length > MaxDocument) throw new StarHubException("Use a Notepad document of up to 12,000 characters.");
        return text;
    }

    private static void SetEditorText(NotepadWindow window, AutomationElement editor, string text)
    {
        if (editor.Patterns.Value.TryGetPattern(out var value) && !value.IsReadOnly.ValueOrDefault)
        {
            value.SetValue(text);
            return;
        }
        var handle = editor.Properties.NativeWindowHandle.ValueOrDefault;
        GetWindowThreadProcessId(handle, out uint pid);
        if (!NativeEditor(handle) || pid != window.ProcessId || GetAncestor(handle, 2) != window.Handle
            || SetNative(handle, 0x000C, 0, text, 2, 1200, out var changed) == 0 || changed == 0)
            throw new StarHubException("Windows blocked the text change in the selected editor.");
    }

    private static bool NativeEditor(nint handle)
    {
        var windowClass = new StringBuilder(100);
        if (handle == 0 || GetClassName(handle, windowClass, windowClass.Capacity) == 0) return false;
        return windowClass.ToString() is "Edit" or "RichEditD2DPT" or "RichEditD2DPT2";
    }

    private static string Normalize(string value) => value.Replace("\r\n", "\n").TrimEnd('\r', '\n');

    private delegate bool Visit(nint handle, nint param);
    [StructLayout(LayoutKind.Sequential)]
    private struct Rect { public int Left, Top, Right, Bottom; }
    [DllImport("user32")] private static extern bool EnumWindows(Visit callback, nint param);
    [DllImport("user32")] private static extern bool IsWindowVisible(nint window);
    [DllImport("user32")] private static extern bool IsIconic(nint window);
    [DllImport("user32")] private static extern bool IsHungAppWindow(nint window);
    [DllImport("user32")] private static extern bool GetWindowRect(nint window, out Rect rect);
    [DllImport("user32")] private static extern bool PrintWindow(nint window, nint dc, uint flags);
    [DllImport("user32")] private static extern uint GetWindowThreadProcessId(nint window, out uint pid);
    [DllImport("user32")] private static extern nint GetAncestor(nint window, uint flags);
    [DllImport("user32", CharSet = CharSet.Unicode, EntryPoint = "GetWindowTextW")]
    private static extern int ReadTitle(nint window, StringBuilder text, int size);
    [DllImport("user32", CharSet = CharSet.Unicode, EntryPoint = "GetClassNameW")]
    private static extern int GetClassName(nint window, StringBuilder text, int size);
    [DllImport("user32", CharSet = CharSet.Unicode, EntryPoint = "SendMessageTimeoutW")]
    private static extern nint ReadNative(nint window, uint message, nint limit, StringBuilder text, uint flags, uint timeout, out nint result);
    [DllImport("user32", CharSet = CharSet.Unicode, EntryPoint = "SendMessageTimeoutW")]
    private static extern nint SetNative(nint window, uint message, nint zero, string text, uint flags, uint timeout, out nint result);
}
