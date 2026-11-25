using System.Runtime.InteropServices;

namespace StarHub.Windows;

internal sealed class TrayIcon : IDisposable
{
    private const uint Callback = 0x8000 + 7;
    private readonly nint hwnd, icon;
    private readonly Subclass callback;
    private bool added;

    private TrayIcon(nint hwnd, nint icon)
    {
        this.hwnd = hwnd;
        this.icon = icon;
        callback = Receive;
        if (!SetWindowSubclass(hwnd, callback, 1, 0)) return;
        var data = Data();
        added = ShellNotify(0, ref data);
        if (!added) RemoveWindowSubclass(hwnd, callback, 1);
    }

    public static TrayIcon? Create(nint hwnd)
    {
        var path = Path.Combine(AppContext.BaseDirectory, "starhub.ico");
        var icon = LoadImage(0, path, 1, 0, 0, 0x0010 | 0x0040);
        if (icon == 0) return null;
        var tray = new TrayIcon(hwnd, icon);
        if (tray.added) return tray;
        tray.Dispose();
        return null;
    }

    private NotifyData Data() => new()
    {
        Size = (uint)Marshal.SizeOf<NotifyData>(), Window = hwnd, Id = 1,
        Flags = 1 | 2 | 4, Message = Callback, Icon = icon, Tip = "Star Hub"
    };

    private nint Receive(nint window, uint message, nint wParam, nint lParam, nuint id, nint data)
    {
        if (message == Callback)
        {
            if ((int)lParam == 0x0203) Restore(hwnd);
            if ((int)lParam == 0x0205) ShowMenu();
        }
        return DefSubclassProc(window, message, wParam, lParam);
    }

    private void ShowMenu()
    {
        var menu = CreatePopupMenu();
        if (menu == 0) return;
        try
        {
            AppendMenu(menu, 0, 1, "Open Star Hub");
            AppendMenu(menu, 0, 2, "Exit");
            GetCursorPos(out var point);
            SetForegroundWindow(hwnd);
            switch (TrackPopupMenu(menu, 0x0100 | 0x0002, point.X, point.Y, 0, hwnd, 0))
            {
                case 1: Restore(hwnd); break;
                case 2: PostMessage(hwnd, 0x0010, 0, 0); break;
            }
            PostMessage(hwnd, 0, 0, 0);
        }
        finally { DestroyMenu(menu); }
    }

    public static void Restore(nint hwnd)
    {
        if (IsIconic(hwnd)) ShowWindow(hwnd, 9);
        SetForegroundWindow(hwnd);
    }

    public void Dispose()
    {
        if (added)
        {
            var data = Data();
            ShellNotify(2, ref data);
            RemoveWindowSubclass(hwnd, callback, 1);
            added = false;
        }
        DestroyIcon(icon);
    }

    private delegate nint Subclass(nint window, uint message, nint wParam, nint lParam, nuint id, nint data);

    [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)]
    private struct NotifyData
    {
        public uint Size;
        public nint Window;
        public uint Id, Flags, Message;
        public nint Icon;
        [MarshalAs(UnmanagedType.ByValTStr, SizeConst = 128)] public string? Tip;
        public uint State, StateMask;
        [MarshalAs(UnmanagedType.ByValTStr, SizeConst = 256)] public string? Info;
        public uint Version;
        [MarshalAs(UnmanagedType.ByValTStr, SizeConst = 64)] public string? InfoTitle;
        public uint InfoFlags;
        public Guid Guid;
        public nint BalloonIcon;
    }
    [StructLayout(LayoutKind.Sequential)]
    private struct Point { public int X, Y; }

    [DllImport("comctl32")] private static extern bool SetWindowSubclass(nint hwnd, Subclass callback, nuint id, nint data);
    [DllImport("comctl32")] private static extern bool RemoveWindowSubclass(nint hwnd, Subclass callback, nuint id);
    [DllImport("comctl32")] private static extern nint DefSubclassProc(nint hwnd, uint msg, nint wParam, nint lParam);
    [DllImport("shell32", EntryPoint = "Shell_NotifyIconW")] private static extern bool ShellNotify(uint op, ref NotifyData data);
    [DllImport("user32", EntryPoint = "LoadImageW", CharSet = CharSet.Unicode)] private static extern nint LoadImage(nint instance, string path, uint type, int width, int height, uint flags);
    [DllImport("user32")] private static extern bool DestroyIcon(nint icon);
    [DllImport("user32")] private static extern bool ShowWindow(nint hwnd, int command);
    [DllImport("user32")] private static extern bool IsIconic(nint hwnd);
    [DllImport("user32")] private static extern bool SetForegroundWindow(nint hwnd);
    [DllImport("user32")] private static extern nint CreatePopupMenu();
    [DllImport("user32", EntryPoint = "AppendMenuW", CharSet = CharSet.Unicode)] private static extern bool AppendMenu(nint menu, uint flags, nuint item, string text);
    [DllImport("user32")] private static extern bool GetCursorPos(out Point point);
    [DllImport("user32")] private static extern uint TrackPopupMenu(nint menu, uint flags, int x, int y, int reserved, nint hwnd, nint rect);
    [DllImport("user32")] private static extern bool DestroyMenu(nint menu);
    [DllImport("user32", EntryPoint = "PostMessageW")] private static extern bool PostMessage(nint hwnd, uint msg, nint wParam, nint lParam);
}
