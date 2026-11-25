using System.Runtime.InteropServices;

namespace StarHub.Platform;

internal sealed class WindowsCounters : IDisposable
{
    private const uint MoreData = 0x800007D2;
    private const uint FormatDouble = 0x00000200;
    private const uint NoScale = 0x00001000;
    private const uint NoCap100 = 0x00008000;
    private IntPtr query;

    public WindowsCounters()
    {
        if (PdhOpenQuery(null, 0, out var opened) == 0) query = opened;
    }

    public IntPtr Add(string path) => query != IntPtr.Zero &&
        PdhAddEnglishCounter(query, path, 0, out var counter) == 0 ? counter : IntPtr.Zero;

    public void Collect()
    {
        if (query != IntPtr.Zero) PdhCollectQueryData(query);
    }

    public static Dictionary<string, double> Read(IntPtr counter)
    {
        var values = new Dictionary<string, double>(StringComparer.OrdinalIgnoreCase);
        if (counter == IntPtr.Zero) return values;
        for (int attempt = 0; attempt < 2; attempt++)
        {
            uint size = 0;
            if (PdhGetFormattedCounterArray(counter, FormatDouble | NoScale | NoCap100, ref size, out _, IntPtr.Zero) != MoreData ||
                size is 0 or > 8_388_608) return values;
            IntPtr buffer = Marshal.AllocHGlobal(checked((int)size));
            try
            {
                uint result = PdhGetFormattedCounterArray(counter, FormatDouble | NoScale | NoCap100, ref size, out uint count, buffer);
                if (result == MoreData) continue;
                if (result != 0) return values;
                int itemSize = Marshal.SizeOf<CounterItem>();
                int itemCount = (int)Math.Min(count, size / (uint)itemSize);
                for (int i = 0; i < itemCount; i++)
                {
                    var item = Marshal.PtrToStructure<CounterItem>(IntPtr.Add(buffer, i * itemSize));
                    if (item.Value.Status is 0 or 1 && double.IsFinite(item.Value.Number) &&
                        Marshal.PtrToStringUni(item.Name) is { Length: > 0 } name)
                        values[name] = item.Value.Number;
                }
                return values;
            }
            finally { Marshal.FreeHGlobal(buffer); }
        }
        return values;
    }

    public void Dispose()
    {
        if (query == IntPtr.Zero) return;
        PdhCloseQuery(query);
        query = IntPtr.Zero;
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct CounterValue
    {
        public uint Status;
        public double Number;
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct CounterItem
    {
        public IntPtr Name;
        public CounterValue Value;
    }

    [DllImport("pdh.dll", EntryPoint = "PdhOpenQueryW", CharSet = CharSet.Unicode, ExactSpelling = true)]
    private static extern uint PdhOpenQuery(string? source, nuint userData, out IntPtr query);

    [DllImport("pdh.dll", EntryPoint = "PdhAddEnglishCounterW", CharSet = CharSet.Unicode, ExactSpelling = true)]
    private static extern uint PdhAddEnglishCounter(IntPtr query, string path, nuint userData, out IntPtr counter);

    [DllImport("pdh.dll")]
    private static extern uint PdhCollectQueryData(IntPtr query);

    [DllImport("pdh.dll", EntryPoint = "PdhGetFormattedCounterArrayW", ExactSpelling = true)]
    private static extern uint PdhGetFormattedCounterArray(IntPtr counter, uint format, ref uint size, out uint count, IntPtr buffer);

    [DllImport("pdh.dll")]
    private static extern uint PdhCloseQuery(IntPtr query);
}
