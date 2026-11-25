using System.Diagnostics;
using System.Net.NetworkInformation;
using System.Runtime.InteropServices;
using System.Security.Principal;
using Microsoft.Win32;
using StarHub.Core;

namespace StarHub.Platform;

public static class WindowsHost
{
    private const string RunKey = @"Software\Microsoft\Windows\CurrentVersion\Run";
    private const string RunName = "StarHubWorker";
    private const string DesktopRunName = "StarHubDesktop";
    public const string WorkerLockName = "worker.lock";
    public static string DataPath => Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "StarHub");
    public static string WorkerPath => Path.Combine(AppContext.BaseDirectory, "worker", "StarHub.Worker.exe");

    public static string PdfOutputFolder(PdfDestination destination)
    {
        if (destination == PdfDestination.Desktop)
            return Environment.GetFolderPath(Environment.SpecialFolder.DesktopDirectory);
        if (destination == PdfDestination.Documents)
            return Environment.GetFolderPath(Environment.SpecialFolder.MyDocuments);

        var downloads = new Guid("374DE290-123F-4565-9164-39C4925E467B");
        int result = SHGetKnownFolderPath(ref downloads, 0, nint.Zero, out nint name);
        try { return result == 0 ? Marshal.PtrToStringUni(name) ?? "" : ""; }
        finally { if (name != nint.Zero) Marshal.FreeCoTaskMem(name); }
    }

    public static PcWorkspace TaskWorkspace()
    {
        string desktop = PdfOutputFolder(PdfDestination.Desktop);
        string root = Directory.Exists(desktop) ? desktop : Environment.GetFolderPath(Environment.SpecialFolder.UserProfile);
        return new PcWorkspace(root, allowDrivePaths: true);
    }

    public static string TaskPath(string path)
    {
        int end = path.IndexOfAny(['/', '\\']);
        string prefix = end < 0 ? path : path[..end];
        string? folder = prefix.ToLowerInvariant() switch
        {
            "desktop" or "桌面" => PdfOutputFolder(PdfDestination.Desktop),
            "downloads" or "下载" => PdfOutputFolder(PdfDestination.Downloads),
            "documents" or "文档" => PdfOutputFolder(PdfDestination.Documents),
            "home" or "~" => Environment.GetFolderPath(Environment.SpecialFolder.UserProfile),
            _ => null
        };
        if (folder is null) return path;
        if (folder.Length == 0) throw new StarHubException($"Windows could not find the {prefix} folder.");
        return end < 0 ? folder : Path.Combine(folder, path[(end + 1)..].TrimStart('/', '\\'));
    }

    [DllImport("shell32.dll", ExactSpelling = true)]
    private static extern int SHGetKnownFolderPath(ref Guid id, uint flags, nint token, out nint path);
    public static string StopEventName
    {
        get
        {
            using var identity = WindowsIdentity.GetCurrent();
            return @"Local\StarHub.Worker.Stop." + identity.User?.Value;
        }
    }

    public static bool StartsAtSignIn()
    {
        using var key = Registry.CurrentUser.OpenSubKey(RunKey);
        return string.Equals(key?.GetValue(RunName) as string, $"\"{WorkerPath}\"", StringComparison.OrdinalIgnoreCase);
    }

    public static void SetStartAtSignIn(bool enabled)
    {
        using var key = Registry.CurrentUser.CreateSubKey(RunKey);
        if (enabled)
        {
            CheckWorker();
            key.SetValue(RunName, $"\"{WorkerPath}\"", RegistryValueKind.String);
        }
        else key.DeleteValue(RunName, throwOnMissingValue: false);
    }

    private static string DesktopPath => Path.Combine(AppContext.BaseDirectory, "StarHub.exe");

    public static bool DesktopStartsAtSignIn()
    {
        using var key = Registry.CurrentUser.OpenSubKey(RunKey);
        return string.Equals(key?.GetValue(DesktopRunName) as string, $"\"{DesktopPath}\"", StringComparison.OrdinalIgnoreCase);
    }

    public static void SetDesktopStartAtSignIn(bool enabled)
    {
        using var key = Registry.CurrentUser.CreateSubKey(RunKey);
        if (enabled)
        {
            if (!File.Exists(DesktopPath)) throw new StarHubException("Install Star Hub to start its desktop at sign-in.");
            key.SetValue(DesktopRunName, $"\"{DesktopPath}\"", RegistryValueKind.String);
        }
        else key.DeleteValue(DesktopRunName, throwOnMissingValue: false);
    }

    public static void StartWorker()
    {
        CheckWorker();
        using var process = Process.Start(new ProcessStartInfo(WorkerPath)
        {
            UseShellExecute = false, CreateNoWindow = true
        });
    }

    public static void EnsureWorkerRunning()
    {
        if (!IsRunning(WorkerLockName)) StartWorker();
    }

    public static bool IsRunning(string fileName)
    {
        string path = Path.Combine(DataPath, fileName);
        if (!File.Exists(path)) return false;
        try
        {
            using var stream = File.Open(path, FileMode.Open, FileAccess.ReadWrite, FileShare.None);
            return false;
        }
        catch (IOException error) when (IsSharingViolation(error)) { return true; }
    }

    public static FileStream? AcquireLock(string fileName)
    {
        Directory.CreateDirectory(DataPath);
        try { return File.Open(Path.Combine(DataPath, fileName), FileMode.OpenOrCreate, FileAccess.ReadWrite, FileShare.None); }
        catch (IOException error) when (IsSharingViolation(error)) { return null; }
    }

    private static bool IsSharingViolation(IOException error) => (error.HResult & 0xffff) is 32 or 33;

    private static void CheckWorker()
    {
        if (!File.Exists(WorkerPath)) throw new StarHubException("Install Star Hub to use its background worker.");
    }

    public static void Log(string fileName, string message)
    {
        try
        {
            Directory.CreateDirectory(DataPath);
            string path = Path.Combine(DataPath, fileName);
            if (File.Exists(path) && new FileInfo(path).Length > 256_000) File.Delete(path);
            File.AppendAllText(path, $"{DateTimeOffset.UtcNow:O} {message}{Environment.NewLine}");
        }
        catch (IOException) { }
        catch (UnauthorizedAccessException) { }
    }

    public static string DeviceSnapshot()
    {
        using var cpuKey = Registry.LocalMachine.OpenSubKey(@"HARDWARE\DESCRIPTION\System\CentralProcessor\0");
        string ram = ReadPhysicalMemory() is { } memory
            ? $"{memory.Available / 1_073_741_824.0:F1} GiB free / {memory.Total / 1_073_741_824.0:F1} GiB"
            : "Unavailable";
        var drive = new DriveInfo(Path.GetPathRoot(DataPath)!);
        var gpu = new DisplayDevice { Size = Marshal.SizeOf<DisplayDevice>() };
        var names = new List<string>();
        for (uint i = 0; i < 16 && EnumDisplayDevices(null, i, ref gpu, 0); i++)
            if ((gpu.Flags & 0x00000008) == 0 && !string.IsNullOrWhiteSpace(gpu.DeviceString))
                names.Add(gpu.DeviceString);
        return $"""
                Windows {Environment.OSVersion.Version} ({RuntimeInformation.OSArchitecture})
                CPU: {cpuKey?.GetValue("ProcessorNameString") ?? "Unknown"} · {Environment.ProcessorCount} logical cores
                Display: {string.Join(", ", names.Distinct().DefaultIfEmpty("Unavailable"))}
                Memory: {ram}
                Data drive: {drive.AvailableFreeSpace / 1_073_741_824.0:F1} GiB free / {drive.TotalSize / 1_073_741_824.0:F1} GiB
                Network connected: {NetworkInterface.GetIsNetworkAvailable()}
                """;
    }

    internal static (ulong Total, ulong Available)? ReadPhysicalMemory()
    {
        var memory = new MemoryStatus { Length = (uint)Marshal.SizeOf<MemoryStatus>() };
        return GlobalMemoryStatusEx(ref memory) ? (memory.Total, memory.Available) : null;
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct MemoryStatus
    {
        public uint Length, Load;
        public ulong Total, Available, TotalPageFile, AvailablePageFile, TotalVirtual, AvailableVirtual, AvailableExtendedVirtual;
    }

    [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)]
    private struct DisplayDevice
    {
        public int Size;
        [MarshalAs(UnmanagedType.ByValTStr, SizeConst = 32)] public string? DeviceName;
        [MarshalAs(UnmanagedType.ByValTStr, SizeConst = 128)] public string? DeviceString;
        public uint Flags;
        [MarshalAs(UnmanagedType.ByValTStr, SizeConst = 128)] public string? DeviceId;
        [MarshalAs(UnmanagedType.ByValTStr, SizeConst = 128)] public string? DeviceKey;
    }

    [DllImport("kernel32", SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool GlobalMemoryStatusEx(ref MemoryStatus status);

    [DllImport("user32", EntryPoint = "EnumDisplayDevicesW", CharSet = CharSet.Unicode)]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool EnumDisplayDevices(string? device, uint index, ref DisplayDevice info, uint flags);
}
