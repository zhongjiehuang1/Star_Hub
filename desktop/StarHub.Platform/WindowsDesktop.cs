using System.Diagnostics;
using System.Runtime.InteropServices;
using System.Text;
using FlaUI.Core.AutomationElements;
using FlaUI.Core.Definitions;
using FlaUI.UIA3;
using StarHub.Core;

namespace StarHub.Platform;

internal static class WindowsDesktop
{
    internal sealed record Window(nint Handle, int ProcessId, string Title)
    {
        public string Id => $"{ProcessId}:{Handle.ToInt64():X}";
    }

    private sealed record Control(string Id, string Name, string Type, string? Value,
        bool CanInvoke, bool CanEdit, AutomationElement Element);

    internal static IReadOnlyList<Window> Windows()
    {
        var result = new List<Window>();
        EnumWindows((handle, _) =>
        {
            if (!IsWindowVisible(handle)) return true;
            GetWindowThreadProcessId(handle, out uint process);
            if (process == 0 || process == Environment.ProcessId) return true;
            var title = new StringBuilder(512);
            if (GetWindowText(handle, title, title.Capacity) == 0) return true;
            result.Add(new(handle, (int)process, title.ToString()));
            return true;
        }, 0);
        return result;
    }

    internal static PcTaskResult List() => new(true, "Read the open application windows.",
        new { windows = Windows().Take(100).Select(window => new { window.Id, window.Title }) });

    internal static PcTaskResult Focus(string selector)
    {
        var window = FindWindow(selector);
        if (IsIconic(window.Handle)) ShowWindow(window.Handle, 9);
        _ = SetForegroundWindow(window.Handle);
        bool focused = GetForegroundWindow() == window.Handle;
        return new(focused, focused ? $"Focused window: {window.Title}" : $"Windows did not bring this window forward: {window.Title}");
    }

    internal static PcTaskResult Inspect(string selector, CancellationToken token)
    {
        var window = FindWindow(selector);
        using var automation = new UIA3Automation();
        var controls = Controls(automation, window, token).Take(100).Select(control =>
            new { control.Id, control.Name, control.Type, control.Value, control.CanInvoke, control.CanEdit }).ToArray();
        return new(true, $"Read visible controls: {window.Title}", new { window.Id, window.Title, controls });
    }

    internal static PcTaskResult Apply(string selector, string controlId, string? text, CancellationToken token)
    {
        var window = FindWindow(selector);
        using var automation = new UIA3Automation();
        var control = Controls(automation, window, token).FirstOrDefault(candidate => candidate.Id == controlId)
            ?? throw new StarHubException("The selected control changed. Inspect this window again.");
        token.ThrowIfCancellationRequested();
        if (text is not null)
        {
            if (!control.CanEdit || !control.Element.Patterns.Value.TryGetPattern(out var field))
                return new(false, $"This control does not expose editable text: {control.Name}");
            field.SetValue(text);
            bool matches = Normalize(field.Value.ValueOrDefault ?? "") == Normalize(text);
            return new(matches, matches ? $"Entered and verified text in {window.Title}: {control.Name}" :
                $"Windows did not confirm the entered text in {window.Title}: {control.Name}");
        }
        if (control.Element.Patterns.Invoke.TryGetPattern(out var command))
        {
            command.Invoke();
            return new(true, $"Invoked {control.Name} in {window.Title}. Verify the resulting window or state.");
        }
        if (control.Element.Patterns.Toggle.TryGetPattern(out var toggle))
        {
            var before = toggle.ToggleState.Value;
            toggle.Toggle();
            bool changed = before != toggle.ToggleState.Value;
            return new(changed, changed ? $"Verified the changed toggle: {control.Name}" : $"Windows did not confirm a change: {control.Name}");
        }
        if (control.Element.Patterns.SelectionItem.TryGetPattern(out var selection))
        {
            selection.Select();
            bool selected = selection.IsSelected.ValueOrDefault;
            return new(selected, selected ? $"Verified the selection: {control.Name}" : $"Windows did not confirm selection: {control.Name}");
        }
        return new(false, $"This control does not expose a supported action: {control.Name}");
    }

    private static IEnumerable<Control> Controls(UIA3Automation automation, Window window, CancellationToken token)
    {
        var root = automation.FromHandle(window.Handle);
        if (root.Properties.ProcessId.ValueOrDefault != window.ProcessId)
            throw new StarHubException("The selected Windows application changed.");
        var walker = automation.TreeWalkerFactory.GetControlViewWalker();
        var parents = new Queue<AutomationElement>();
        parents.Enqueue(root);
        int visited = 0;
        while (parents.Count > 0 && visited < 700)
        {
            for (var element = walker.GetFirstChild(parents.Dequeue()); element is not null && visited < 700;
                element = walker.GetNextSibling(element))
            {
                token.ThrowIfCancellationRequested();
                visited++;
                if (element.Properties.ProcessId.ValueOrDefault != window.ProcessId || element.Properties.IsOffscreen.ValueOrDefault)
                    continue;
                parents.Enqueue(element);
                if (!element.Properties.IsEnabled.ValueOrDefault || element.Properties.IsPassword.ValueOrDefault ||
                    element.Properties.RuntimeId.ValueOrDefault is not { Length: > 0 } runtime) continue;
                var name = element.Properties.Name.ValueOrDefault ?? "";
                var type = element.ControlType;
                bool canInvoke = element.Patterns.Invoke.IsSupported || element.Patterns.Toggle.IsSupported || element.Patterns.SelectionItem.IsSupported;
                bool hasValue = element.Patterns.Value.TryGetPattern(out var value);
                bool canEdit = hasValue && value is not null && !value.IsReadOnly.ValueOrDefault;
                string? current = hasValue ? value!.Value.ValueOrDefault :
                    type == ControlType.Document && element.Patterns.Text.TryGetPattern(out var reader)
                        ? reader.DocumentRange.GetText(4_000) : null;
                if (name.Length == 0 && !canInvoke && !canEdit && current is null) continue;
                yield return new(string.Join('.', runtime), Limit(name, 300), type.ToString(), current is null ? null : Limit(current, 4_000),
                    canInvoke, canEdit, element);
            }
        }
    }

    private static Window FindWindow(string selector)
    {
        var windows = Windows();
        var matches = windows.Where(window => window.Id.Equals(selector, StringComparison.OrdinalIgnoreCase) ||
            window.Title.Equals(selector, StringComparison.OrdinalIgnoreCase)).ToArray();
        if (matches.Length == 0 && !string.IsNullOrWhiteSpace(selector))
            matches = windows.Where(window => window.Title.Contains(selector, StringComparison.OrdinalIgnoreCase)).ToArray();
        return matches.Length switch
        {
            1 => matches[0],
            0 => throw new StarHubException("That application window is not open."),
            _ => throw new StarHubException("Several windows match. Use a window ID from the window list.")
        };
    }

    private static string Normalize(string text) => text.Replace("\r\n", "\n", StringComparison.Ordinal);
    private static string Limit(string text, int count) => text.Length <= count ? text : text[..count];

    private delegate bool Visit(nint handle, nint param);
    [DllImport("user32.dll")] private static extern bool EnumWindows(Visit callback, nint param);
    [DllImport("user32.dll")] private static extern bool IsWindowVisible(nint window);
    [DllImport("user32.dll")] private static extern bool IsIconic(nint window);
    [DllImport("user32.dll")] private static extern bool ShowWindow(nint window, int command);
    [DllImport("user32.dll")] private static extern bool SetForegroundWindow(nint window);
    [DllImport("user32.dll")] private static extern nint GetForegroundWindow();
    [DllImport("user32.dll")] private static extern uint GetWindowThreadProcessId(nint window, out uint process);
    [DllImport("user32.dll", CharSet = CharSet.Unicode)] private static extern int GetWindowText(nint window, StringBuilder title, int size);
}
