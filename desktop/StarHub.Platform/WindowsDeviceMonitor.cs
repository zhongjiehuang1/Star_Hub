using System.ComponentModel;
using System.Diagnostics;
using System.Management;
using System.Net.NetworkInformation;
using System.Runtime.InteropServices;
using StarHub.Core;

namespace StarHub.Platform;

public sealed class WindowsDeviceMonitor : IDisposable
{
    private const string Unavailable = "Unavailable from Windows";
    private const string Waiting = "Waiting for a second reading";
    private static readonly string[] ByteUnits = ["B", "KiB", "MiB", "GiB", "TiB", "PiB"];
    private readonly WindowsCounters counters = new();
    private readonly IntPtr processors, frequency, diskRead, diskWrite, diskIdle, diskLatency, diskTransfers,
        gpuEngines, gpuDedicated, gpuShared, temperatures;
    private readonly uint logicalCpuCount = Math.Max(1u, GetActiveProcessorCount(ushort.MaxValue));
    private (long Idle, long Kernel, long User)? previousCpu;
    private Dictionary<string, (long Rx, long Tx)> previousNetwork = new();
    private Dictionary<(int Id, long Started), TimeSpan> previousProcesses = new();
    private long previousSample;

    public WindowsDeviceMonitor()
    {
        processors = counters.Add(@"\Processor Information(*)\% Processor Time");
        frequency = counters.Add(@"\Processor Information(*)\Processor Frequency");
        diskRead = counters.Add(@"\PhysicalDisk(*)\Disk Read Bytes/sec");
        diskWrite = counters.Add(@"\PhysicalDisk(*)\Disk Write Bytes/sec");
        diskIdle = counters.Add(@"\PhysicalDisk(*)\% Idle Time");
        diskLatency = counters.Add(@"\PhysicalDisk(*)\Avg. Disk sec/Transfer");
        diskTransfers = counters.Add(@"\PhysicalDisk(*)\Disk Transfers/sec");
        gpuEngines = counters.Add(@"\GPU Engine(*)\Utilization Percentage");
        gpuDedicated = counters.Add(@"\GPU Adapter Memory(*)\Dedicated Usage");
        gpuShared = counters.Add(@"\GPU Adapter Memory(*)\Shared Usage");
        temperatures = counters.Add(@"\Thermal Zone Information(*)\Temperature");
    }

    public DeviceReading Capture()
    {
        long timestamp = Stopwatch.GetTimestamp();
        double seconds = previousSample == 0 ? 0 : Stopwatch.GetElapsedTime(previousSample, timestamp).TotalSeconds;
        if (seconds > 10) seconds = 0;
        previousSample = timestamp;
        counters.Collect();

        bool hasInterval = seconds > 0;
        var cpu = WindowsCounters.Read(hasInterval ? processors : IntPtr.Zero);
        double? fallback = ReadCpuTimes();
        double? usage = cpu.TryGetValue("_Total", out double total) ? Math.Clamp(total, 0, 100) : hasInterval ? fallback : null;
        var (processor, cores, clock, uptime) = ReadProcessor(cpu, usage);
        var (memoryUse, available, memory, memoryComposition, counts) = ReadMemory();
        var (graphics, gpuUsage, graphicsAdapters) = ReadGraphics(hasInterval);
        var storage = ReadStorage(hasInterval);
        var (network, received, sent, adapters) = ReadNetwork(seconds);
        var (power, temperature, ac, battery, powerStatus, thermalZones) = ReadPower();
        var (processes, top) = ReadProcesses(seconds, counts);
        var sample = new DeviceSample(DateTimeOffset.UtcNow, usage, memoryUse, available, storage.Busy, storage.Latency,
            storage.Read, storage.Write, received, sent, gpuUsage, temperature, storage.SystemFree, ac, top, battery);
        var breakdown = new DeviceBreakdown(cores, logicalCpuCount, clock, uptime, memoryComposition,
            graphicsAdapters, storage.Volumes, storage.Disks, adapters, powerStatus, thermalZones, counts);
        return new DeviceReading(sample, processor, memory, graphics, storage.Details, network, power, processes, breakdown);
    }

    private double? ReadCpuTimes()
    {
        if (!GetSystemTimes(out long idle, out long kernel, out long user)) return null;
        var previous = previousCpu;
        previousCpu = (idle, kernel, user);
        if (previous is not { } last) return null;
        long elapsed = (kernel - last.Kernel) + (user - last.User);
        long idleTime = idle - last.Idle;
        return elapsed > 0 && idleTime >= 0 && idleTime <= elapsed ? 100.0 * (elapsed - idleTime) / elapsed : null;
    }

    private (string Details, DeviceCpuCore[] Cores, double? Clock, long Uptime) ReadProcessor(
        Dictionary<string, double> cpu, double? usage)
    {
        long uptime = Environment.TickCount64 / 1000;
        var values = new List<string> { $"{logicalCpuCount} logical processors · Uptime {FormatDuration(TimeSpan.FromSeconds(uptime))}" };
        values.Add(usage.HasValue ? $"Total usage {usage.Value:F0}%" : Waiting);
        double? clock = WindowsCounters.Read(frequency).TryGetValue("_Total", out double rate) && rate > 0 ? rate : null;
        if (clock.HasValue) values.Add($"Reported processor frequency {clock:N0} MHz");
        var cores = cpu.Where(item => !item.Key.EndsWith("_Total", StringComparison.OrdinalIgnoreCase))
            .Select(item => new DeviceCpuCore(item.Key, Math.Clamp(item.Value, 0, 100))).ToArray();
        values.AddRange(cores.Select(core => $"{core.Name}: {core.Percent:F0}%").Chunk(8).Select(line => string.Join("   ", line)));
        return (Join(values), cores, clock, uptime);
    }

    private static (double? Percent, ulong? Available, string Memory, DeviceMemoryComposition Composition, DeviceProcessCounts? Counts) ReadMemory()
    {
        var values = new List<string>();
        double? percent = null;
        ulong? total = null, available = null;
        if (WindowsHost.ReadPhysicalMemory() is { } physical && physical.Total > 0 && physical.Available <= physical.Total)
        {
            ulong used = physical.Total - physical.Available;
            total = physical.Total;
            available = physical.Available;
            percent = 100.0 * used / physical.Total;
            values.Add($"{FormatBytes(used)} in use / {FormatBytes(physical.Total)} installed usable ({percent:F0}%)");
            values.Add($"Available {FormatBytes(physical.Available)}");
        }
        uint size = (uint)Marshal.SizeOf<PerformanceInformation>();
        if (!GetPerformanceInfo(out var info, size))
            return (percent, available, Join(values), new(total, available, null, null, null, null, null, null), null);
        double BytesForPages(nuint pages) => (double)pages * info.PageSize;
        var composition = new DeviceMemoryComposition(total, available, BytesForPages(info.CommitTotal),
            BytesForPages(info.CommitLimit), BytesForPages(info.CommitPeak), BytesForPages(info.SystemCache),
            BytesForPages(info.KernelPaged), BytesForPages(info.KernelNonpaged));
        values.Add($"Commit {FormatBytes(composition.CommitBytes!.Value)} / {FormatBytes(composition.CommitLimitBytes!.Value)} · Peak {FormatBytes(composition.CommitPeakBytes!.Value)}");
        values.Add($"System cache {FormatBytes(composition.CacheBytes!.Value)} · Paged pool {FormatBytes(composition.PagedPoolBytes!.Value)} · Nonpaged pool {FormatBytes(composition.NonPagedPoolBytes!.Value)}");
        return (percent, available, Join(values), composition, new(info.ProcessCount, info.ThreadCount, info.HandleCount));
    }

    private (string Details, double? Busiest, DeviceGpuAdapter[] Adapters) ReadGraphics(bool hasInterval)
    {
        var usage = WindowsCounters.Read(hasInterval ? gpuEngines : IntPtr.Zero);
        var dedicated = WindowsCounters.Read(gpuDedicated);
        var shared = WindowsCounters.Read(gpuShared);
        var engines = new Dictionary<string, double>(StringComparer.OrdinalIgnoreCase);
        foreach (var (instance, percent) in usage)
        {
            int luid = instance.IndexOf("luid_", StringComparison.OrdinalIgnoreCase);
            if (luid >= 0) engines[instance[luid..]] = engines.GetValueOrDefault(instance[luid..]) + Math.Max(0, percent);
        }
        var groups = engines.Where(item => item.Key.Contains("_eng_", StringComparison.Ordinal))
            .ToLookup(item => item.Key[..item.Key.IndexOf("_eng_", StringComparison.Ordinal)], item => item.Value, StringComparer.OrdinalIgnoreCase);
        var values = new List<string>();
        var adapters = new List<DeviceGpuAdapter>();
        foreach (var id in groups.Select(group => group.Key).Union(dedicated.Keys, StringComparer.OrdinalIgnoreCase)
                     .Union(shared.Keys, StringComparer.OrdinalIgnoreCase).Order(StringComparer.OrdinalIgnoreCase))
        {
            var fields = new List<string>();
            var adapter = new DeviceGpuAdapter(id.Replace("luid_", "", StringComparison.OrdinalIgnoreCase),
                groups.Contains(id) ? Math.Clamp(groups[id].Max(), 0, 100) : null,
                Nonnegative(dedicated, id), Nonnegative(shared, id));
            adapters.Add(adapter);
            if (adapter.Percent is { } percent) fields.Add($"Busiest engine {percent:F0}%");
            if (adapter.DedicatedBytes is { } local) fields.Add($"Dedicated {FormatBytes(local)}");
            if (adapter.SharedBytes is { } system) fields.Add($"Shared {FormatBytes(system)}");
            if (fields.Count > 0) values.Add($"GPU adapter {adapter.Name} · {string.Join(" · ", fields)}");
        }
        double? busiest = groups.Count > 0 ? Math.Clamp(groups.SelectMany(group => group).Max(), 0, 100) : null;
        return (Join(values), busiest, adapters.ToArray());
    }

    private (string Details, double? Busy, double? Latency, double? Read, double? Write, double? SystemFree,
        DeviceVolume[] Volumes, DeviceDisk[] Disks) ReadStorage(bool hasInterval)
    {
        var values = new List<string>();
        var volumes = new List<DeviceVolume>();
        var activity = new List<DeviceDisk>();
        string? systemDrive = Path.GetPathRoot(Environment.SystemDirectory);
        double? systemFree = null;
        foreach (var volume in DriveInfo.GetDrives().Where(drive => drive.DriveType is DriveType.Fixed or DriveType.Removable))
        {
            try
            {
                if (!volume.IsReady) continue;
                long available = volume.AvailableFreeSpace, total = volume.TotalSize;
                if (total > 0 && available >= 0 && available <= total) volumes.Add(new(volume.Name, available, total));
                values.Add($"{volume.Name} {volume.DriveFormat} · {FormatBytes(available)} available / {FormatBytes(total)} total");
                if (volume.Name.Equals(systemDrive, StringComparison.OrdinalIgnoreCase) && total > 0 && available >= 0)
                    systemFree = Math.Clamp(100.0 * available / total, 0, 100);
            }
            catch (Exception error) when (error is IOException or UnauthorizedAccessException) { }
        }
        var reads = WindowsCounters.Read(hasInterval ? diskRead : IntPtr.Zero);
        var writes = WindowsCounters.Read(hasInterval ? diskWrite : IntPtr.Zero);
        var idle = WindowsCounters.Read(hasInterval ? diskIdle : IntPtr.Zero);
        var latency = WindowsCounters.Read(hasInterval ? diskLatency : IntPtr.Zero);
        var transfers = WindowsCounters.Read(hasInterval ? diskTransfers : IntPtr.Zero);
        var disks = reads.Keys.Union(writes.Keys, StringComparer.OrdinalIgnoreCase).Union(idle.Keys, StringComparer.OrdinalIgnoreCase);
        var busyTimes = new List<double>();
        var delays = new List<double>();
        foreach (var disk in disks.Order(StringComparer.OrdinalIgnoreCase))
        {
            double? readRate = Nonnegative(reads, disk), writeRate = Nonnegative(writes, disk);
            string read = readRate is { } r ? $"{FormatBytes(r)}/s" : "—";
            string write = writeRate is { } w ? $"{FormatBytes(w)}/s" : "—";
            double? busy = null, delay = null;
            string line = $"{(disk == "_Total" ? "All physical disks" : $"Disk {disk}")} · Read {read} · Write {write}";
            if (idle.TryGetValue(disk, out double idleTime) && idleTime >= 0)
            {
                double usage = 100 - Math.Clamp(idleTime, 0, 100);
                busy = usage;
                busyTimes.Add(usage);
                line += $" · Active {usage:F0}%";
            }
            if (latency.TryGetValue(disk, out double wait) && wait >= 0 && transfers.GetValueOrDefault(disk) > 0)
            {
                double milliseconds = wait * 1000;
                delay = milliseconds;
                delays.Add(milliseconds);
                line += $" · I/O latency {milliseconds:F1} ms";
            }
            if (disk != "_Total") activity.Add(new(disk, busy, delay, readRate, writeRate));
            values.Add(line);
        }
        return (Join(values), busyTimes.Count == 0 ? null : busyTimes.Max(), delays.Count == 0 ? null : delays.Max(),
            TotalRate(reads), TotalRate(writes), systemFree, volumes.ToArray(), activity.ToArray());
    }

    private static double? Nonnegative(Dictionary<string, double> values, string key)
        => values.TryGetValue(key, out double value) && double.IsFinite(value) && value >= 0 ? value : null;

    private static double? TotalRate(Dictionary<string, double> rates)
    {
        if (rates.TryGetValue("_Total", out double total) && total >= 0) return total;
        var valid = rates.Values.Where(rate => rate >= 0).ToArray();
        return valid.Length > 0 ? valid.Sum() : null;
    }

    private (string Details, double? Received, double? Sent, DeviceNetworkAdapter[] Adapters) ReadNetwork(double seconds)
    {
        var totals = new Dictionary<string, (long Rx, long Tx)>();
        var values = new List<string>();
        double? received = null, sent = null;
        var adapters = new List<DeviceNetworkAdapter>();
        try
        {
            foreach (var nic in NetworkInterface.GetAllNetworkInterfaces().Where(nic => nic.NetworkInterfaceType != NetworkInterfaceType.Loopback)
                         .OrderByDescending(nic => nic.OperationalStatus == OperationalStatus.Up).ThenBy(nic => nic.Name))
            {
                string line = $"{nic.Name} · {nic.NetworkInterfaceType} · {nic.OperationalStatus}";
                bool connected = nic.OperationalStatus == OperationalStatus.Up;
                long? speed = null;
                double? receiveRate = null, sendRate = null;
                try
                {
                    if (connected)
                    {
                        long link = nic.Speed;
                        if (link > 0)
                        {
                            speed = link;
                            line += $" · Link {link / 1_000_000.0:0.#} Mb/s";
                        }
                        var stats = nic.GetIPStatistics();
                        totals[nic.Id] = (stats.BytesReceived, stats.BytesSent);
                        if (seconds > 0 && previousNetwork.TryGetValue(nic.Id, out var last) &&
                            stats.BytesReceived >= last.Rx && stats.BytesSent >= last.Tx)
                        {
                            double rx = (stats.BytesReceived - last.Rx) / seconds, tx = (stats.BytesSent - last.Tx) / seconds;
                            receiveRate = rx;
                            sendRate = tx;
                            received = received.GetValueOrDefault() + rx;
                            sent = sent.GetValueOrDefault() + tx;
                            line += $" · ↓ {FormatBytes(rx)}/s · ↑ {FormatBytes(tx)}/s";
                        }
                        var addresses = nic.GetIPProperties().UnicastAddresses.Select(item => item.Address.ToString()).Take(3).ToArray();
                        if (addresses.Length > 0) line += $"{Environment.NewLine}  {string.Join(" · ", addresses)}";
                    }
                }
                catch (Exception error) when (error is NetworkInformationException or NotSupportedException) { }
                values.Add(line);
                adapters.Add(new(nic.Name, connected, speed, receiveRate, sendRate));
            }
        }
        catch (NetworkInformationException) { }
        previousNetwork = totals;
        return (Join(values), received, sent, adapters.ToArray());
    }

    private (string Details, double? Temperature, bool? AcConnected, double? Battery, DevicePower? Status,
        DeviceTemperature[] Temperatures) ReadPower()
    {
        var values = new List<string>();
        bool? ac = null;
        double? battery = null;
        DevicePower? status = null;
        if (GetSystemPowerStatus(out var power))
        {
            ac = power.Ac is 0 or 1 ? power.Ac == 1 : null;
            values.Add(power.Ac is 0 ? "AC disconnected" : power.Ac is 1 ? "AC connected" : "AC status unavailable");
            bool? hasBattery = power.BatteryFlag == 255 ? null : (power.BatteryFlag & 128) == 0;
            if (hasBattery is null && power.BatteryPercent <= 100) hasBattery = true;
            bool? charging = hasBattery == true && power.BatteryFlag != 255 ? (power.BatteryFlag & 8) != 0 : null;
            uint? remaining = hasBattery == true && power.BatterySeconds != uint.MaxValue ? power.BatterySeconds : null;
            status = new DevicePower(hasBattery, charging, remaining, power.BatterySaver == 1);
            if (hasBattery == false) values.Add("No system battery");
            if (hasBattery == true && power.BatteryPercent <= 100)
            {
                battery = power.BatteryPercent;
                values.Add($"Battery {battery}%{(charging == true ? " · charging" : "")}");
                if (remaining is { } seconds) values.Add($"{FormatDuration(TimeSpan.FromSeconds(seconds))} remaining");
            }
            if (status.SaverActive) values.Add("Battery saver active");
        }
        var thermal = WindowsCounters.Read(temperatures).Where(item => item.Value is >= 223 and <= 473)
            .Select(item => new DeviceTemperature(item.Key, item.Value - 273.15)).ToArray();
        values.AddRange(thermal.Length == 0 ? [$"ACPI temperatures · {Unavailable.ToLowerInvariant()}"] :
            thermal.Select(item => $"ACPI zone {item.Name} · {item.Celsius:F1} °C"));
        return (Join(values), thermal.Length == 0 ? null : thermal.Max(item => item.Celsius), ac, battery, status, thermal);
    }

    private (string Details, DeviceProcessUsage[] Top) ReadProcesses(double seconds, DeviceProcessCounts? counts)
    {
        var current = new Dictionary<(int Id, long Started), TimeSpan>();
        var samples = new List<DeviceProcessUsage>();
        foreach (var process in Process.GetProcesses())
        {
            using (process)
            {
                try
                {
                    if (process.Id == 0) continue;
                    var key = (process.Id, process.StartTime.ToFileTimeUtc());
                    var cpu = process.TotalProcessorTime;
                    current[key] = cpu;
                    double? percent = seconds > 0 && previousProcesses.TryGetValue(key, out var old) && cpu >= old
                        ? Math.Clamp((cpu - old).TotalSeconds / (seconds * logicalCpuCount) * 100, 0, 100) : null;
                    samples.Add(new DeviceProcessUsage(process.Id, process.ProcessName, percent, process.WorkingSet64));
                }
                catch (Exception error) when (error is InvalidOperationException or Win32Exception or NotSupportedException) { }
            }
        }
        previousProcesses = current;
        var values = new List<string> { counts is { } info
            ? $"{info.Processes:N0} processes · {info.Threads:N0} threads · {info.Handles:N0} handles" : Unavailable };
        var top = samples.OrderByDescending(item => item.CpuPercent ?? -1).ThenByDescending(item => item.WorkingSetBytes).Take(5)
            .Concat(samples.OrderByDescending(item => item.WorkingSetBytes).Take(5)).DistinctBy(item => item.Id).ToArray();
        foreach (var entry in top)
        {
            string load = entry.CpuPercent.HasValue ? $"{entry.CpuPercent.Value:F1}% CPU" : "CPU —";
            values.Add($"{entry.Name} ({entry.Id}) · {load} · {FormatBytes(entry.WorkingSetBytes)} working set");
        }
        return (Join(values), top);
    }

    public static DeviceInventoryEntry[] ReadInventory(CancellationToken token)
    {
        var values = new List<DeviceInventoryEntry> { new("Windows", $"{Environment.OSVersion.Version} · {RuntimeInformation.OSArchitecture}") };
        if (GetFirmwareType(out uint firmware) && firmware is 1 or 2)
            values.Add(new("Firmware mode", firmware == 2 ? "UEFI" : "BIOS"));
        AddInventory(values, token, "Windows", "Caption", "Win32_OperatingSystem", row => Value(row, "Caption"));
        AddInventory(values, token, "Computer", "Manufacturer,Model", "Win32_ComputerSystem", row => Parts(row, "Manufacturer", "Model"));
        AddInventory(values, token, "Processor", "Name,NumberOfCores,NumberOfLogicalProcessors,MaxClockSpeed", "Win32_Processor", row =>
            string.Join(" · ", Value(row, "Name"), $"{Value(row, "NumberOfCores")} cores / {Value(row, "NumberOfLogicalProcessors")} threads",
                $"Reported maximum {Value(row, "MaxClockSpeed")} MHz"));
        AddInventory(values, token, "Baseboard", "Manufacturer,Product", "Win32_BaseBoard", row => Parts(row, "Manufacturer", "Product"));
        AddInventory(values, token, "BIOS", "Manufacturer,SMBIOSBIOSVersion", "Win32_BIOS", row => Parts(row, "Manufacturer", "SMBIOSBIOSVersion"));
        AddInventory(values, token, "RAM module", "DeviceLocator,Manufacturer,Capacity,ConfiguredClockSpeed", "Win32_PhysicalMemory", row =>
            string.Join(" · ", Parts(row, "DeviceLocator", "Manufacturer"), Capacity(row, "Capacity"), RatedMemorySpeed(row)));
        AddInventory(values, token, "Graphics adapter", "Name,DriverVersion,CurrentHorizontalResolution,CurrentVerticalResolution,CurrentRefreshRate", "Win32_VideoController", row =>
        {
            string mode = row["CurrentHorizontalResolution"] is null || row["CurrentVerticalResolution"] is null ? ""
                : $" · {Value(row, "CurrentHorizontalResolution")} × {Value(row, "CurrentVerticalResolution")} @ {Value(row, "CurrentRefreshRate")} Hz";
            return $"{Value(row, "Name")} · Driver {Value(row, "DriverVersion")}{mode}";
        });
        AddInventory(values, token, "Physical disk", "Index,Model,InterfaceType,Size,Status", "Win32_DiskDrive", row =>
            $"{Value(row, "Index")} · {Parts(row, "Model", "InterfaceType")} · {Capacity(row, "Size")} · {Value(row, "Status")}");
        return values.ToArray();
    }

    private static void AddInventory(List<DeviceInventoryEntry> values, CancellationToken token, string label, string columns, string className,
        Func<ManagementBaseObject, string> format)
    {
        token.ThrowIfCancellationRequested();
        int before = values.Count;
        try
        {
            var query = new ObjectQuery($"SELECT {columns} FROM {className}");
            var options = new System.Management.EnumerationOptions { Rewindable = false, Timeout = TimeSpan.FromSeconds(2) };
            using var searcher = new ManagementObjectSearcher(new ManagementScope(@"root\cimv2"), query, options);
            using var results = searcher.Get();
            foreach (ManagementBaseObject item in results)
            {
                using (item)
                {
                    token.ThrowIfCancellationRequested();
                    values.Add(new(label, format(item)));
                }
            }
        }
        catch (Exception error) when (error is ManagementException or COMException or UnauthorizedAccessException) { }
        if (values.Count == before) values.Add(new(label, "", false));
    }

    private static string Value(ManagementBaseObject item, string field) => item[field]?.ToString()?.Trim() is { Length: > 0 } text ? text : "—";
    private static string Parts(ManagementBaseObject item, params string[] fields) => string.Join(" ", fields.Select(field => Value(item, field)));
    private static string Capacity(ManagementBaseObject item, string field) => item[field] is ulong value && value > 0 ? FormatBytes(value) : "—";
    private static string RatedMemorySpeed(ManagementBaseObject item) => item["ConfiguredClockSpeed"] is uint speed && speed > 0 ? $"{speed:N0} MT/s" : "Speed unavailable";

    private static string Join(List<string> values) => values.Count == 0 ? Unavailable : string.Join(Environment.NewLine, values);

    public static string FormatBytes(double count)
    {
        int unit = 0;
        while (count >= 1024 && unit < ByteUnits.Length - 1) { count /= 1024; unit++; }
        return $"{count.ToString(unit == 0 ? "N0" : "N1")} {ByteUnits[unit]}";
    }

    private static string FormatDuration(TimeSpan duration) => duration.TotalDays >= 1
        ? $"{(int)duration.TotalDays}d {duration:hh\\:mm\\:ss}" : duration.ToString(@"hh\:mm\:ss");

    public void Dispose() => counters.Dispose();

    [StructLayout(LayoutKind.Sequential)]
    private struct PerformanceInformation
    {
        public uint Size;
        public nuint CommitTotal, CommitLimit, CommitPeak, PhysicalTotal, PhysicalAvailable, SystemCache,
            KernelTotal, KernelPaged, KernelNonpaged, PageSize;
        public uint HandleCount, ProcessCount, ThreadCount;
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct PowerStatus
    {
        public byte Ac, BatteryFlag, BatteryPercent, BatterySaver;
        public uint BatterySeconds, FullBatterySeconds;
    }

    [DllImport("kernel32.dll")]
    private static extern uint GetActiveProcessorCount(ushort group);

    [DllImport("kernel32.dll")]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool GetSystemTimes(out long idle, out long kernel, out long user);

    [DllImport("kernel32.dll", EntryPoint = "K32GetPerformanceInfo")]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool GetPerformanceInfo(out PerformanceInformation information, uint size);

    [DllImport("kernel32.dll")]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool GetSystemPowerStatus(out PowerStatus status);

    [DllImport("kernel32.dll")]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool GetFirmwareType(out uint firmwareType);
}
