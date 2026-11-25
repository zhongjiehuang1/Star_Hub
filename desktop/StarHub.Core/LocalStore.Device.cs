using System.Text.Json;
using Microsoft.Data.Sqlite;

namespace StarHub.Core;

public sealed record DeviceProcessUsage(int Id, string Name, double? CpuPercent, long WorkingSetBytes);

public sealed record DeviceSample(DateTimeOffset SampledAt, double? CpuPercent, double? MemoryPercent,
    ulong? AvailableMemoryBytes = null, double? DiskBusyPercent = null, double? DiskLatencyMs = null,
    double? DiskReadBytesPerSecond = null, double? DiskWriteBytesPerSecond = null,
    double? ReceiveBytesPerSecond = null, double? SendBytesPerSecond = null, double? GpuPercent = null,
    double? ThermalCelsius = null, double? SystemDriveFreePercent = null, bool? AcConnected = null,
    DeviceProcessUsage[]? TopProcesses = null, double? BatteryPercent = null);

public sealed record DeviceReading(DeviceSample Sample, string Processor, string Memory, string Graphics,
    string Storage, string Network, string Power, string Processes, DeviceBreakdown? Breakdown = null);

public sealed record DeviceCpuCore(string Name, double Percent);
public sealed record DeviceMemoryComposition(ulong? TotalBytes, ulong? AvailableBytes, double? CommitBytes,
    double? CommitLimitBytes, double? CommitPeakBytes, double? CacheBytes, double? PagedPoolBytes, double? NonPagedPoolBytes);
public sealed record DeviceGpuAdapter(string Name, double? Percent, double? DedicatedBytes, double? SharedBytes);
public sealed record DeviceVolume(string Name, long AvailableBytes, long TotalBytes);
public sealed record DeviceDisk(string Name, double? BusyPercent, double? LatencyMs, double? ReadRate, double? WriteRate);
public sealed record DeviceNetworkAdapter(string Name, bool Connected, long? LinkBitsPerSecond, double? ReceiveRate, double? SendRate);
public sealed record DeviceTemperature(string Name, double Celsius);
public sealed record DevicePower(bool? HasBattery, bool? Charging, uint? SecondsRemaining, bool SaverActive);
public sealed record DeviceProcessCounts(uint Processes, uint Threads, uint Handles);
public sealed record DeviceBreakdown(DeviceCpuCore[] Cores, uint LogicalProcessors, double? FrequencyMHz, long UptimeSeconds,
    DeviceMemoryComposition Memory, DeviceGpuAdapter[] Gpus, DeviceVolume[] Volumes, DeviceDisk[] Disks,
    DeviceNetworkAdapter[] Network, DevicePower? Power, DeviceTemperature[] Temperatures, DeviceProcessCounts? ProcessCounts);
public sealed record DeviceInventoryEntry(string Category, string Details, bool Available = true);

internal sealed record DeviceEvidence(DeviceSample? Latest, IReadOnlyList<DeviceSample> History);

public sealed partial class LocalStore
{
    public static readonly TimeSpan DeviceRefreshInterval = TimeSpan.FromSeconds(2);
    public static readonly TimeSpan DeviceLiveAge = TimeSpan.FromSeconds(12);
    internal static readonly TimeSpan DeviceHistoryRetention = TimeSpan.FromDays(7);
    private const int DeviceHistorySeconds = 15;
    public static TimeSpan DeviceHistoryInterval => TimeSpan.FromSeconds(DeviceHistorySeconds);
    private const string DeviceSchema = """
        ALTER TABLE turns ADD COLUMN local_only INTEGER NOT NULL DEFAULT 0;
        CREATE TABLE device_latest (
            id INTEGER PRIMARY KEY CHECK(id = 1), content TEXT NOT NULL
        );
        CREATE TABLE device_history (
            slot INTEGER PRIMARY KEY, sampled INTEGER NOT NULL, content TEXT NOT NULL
        );
        """;

    public void RecordDeviceReading(DeviceReading reading)
    {
        var sample = reading.Sample;
        long slot = DeviceSlot(sample.SampledAt);
        using var connection = Open();
        using var transaction = connection.BeginTransaction(deferred: false);
        using var latest = Command(connection, """
            INSERT INTO device_latest VALUES (1, $reading) ON CONFLICT(id) DO UPDATE SET content = excluded.content
            """, transaction, ("$reading", JsonSerializer.Serialize(reading)));
        latest.ExecuteNonQuery();
        using var history = Command(connection, """
            INSERT INTO device_history VALUES ($slot, $sampled, $sample) ON CONFLICT(slot) DO NOTHING
            """, transaction, ("$slot", slot), ("$sampled", sample.SampledAt.ToUnixTimeMilliseconds()),
            ("$sample", JsonSerializer.Serialize(sample)));
        if (history.ExecuteNonQuery() != 0)
        {
            var oldest = sample.SampledAt - DeviceHistoryRetention;
            using var prune = Command(connection, """
                DELETE FROM device_history WHERE slot < $old;
                DELETE FROM device_alerts WHERE created < $oldAlert;
                """, transaction, ("$old", DeviceSlot(oldest)), ("$oldAlert", oldest.ToUnixTimeMilliseconds()));
            prune.ExecuteNonQuery();
        }
        transaction.Commit();
    }

    public DeviceReading? LatestDeviceReading()
    {
        using var connection = Open();
        using var command = Command(connection, "SELECT content FROM device_latest WHERE id = 1");
        return command.ExecuteScalar() is string json ? JsonSerializer.Deserialize<DeviceReading>(json) : null;
    }

    internal DeviceEvidence ReadDeviceEvidence(DateTimeOffset since, DateTimeOffset through)
    {
        using var connection = Open();
        using var transaction = connection.BeginTransaction(deferred: true);
        using var latest = Command(connection, "SELECT content FROM device_latest WHERE id = 1", transaction);
        var current = latest.ExecuteScalar() is string json ? JsonSerializer.Deserialize<DeviceReading>(json)?.Sample : null;
        return new DeviceEvidence(current, ReadDeviceHistory(connection, transaction, since, through));
    }

    public IReadOnlyList<DeviceSample> ReadDeviceHistory(DateTimeOffset since, DateTimeOffset through)
    {
        using var connection = Open();
        return ReadDeviceHistory(connection, null, since, through);
    }

    private static IReadOnlyList<DeviceSample> ReadDeviceHistory(SqliteConnection connection,
        SqliteTransaction? transaction, DateTimeOffset since, DateTimeOffset through)
    {
        using var history = Command(connection, """
            SELECT content FROM device_history WHERE slot BETWEEN $first AND $last AND sampled BETWEEN $since AND $through
            ORDER BY slot
            """, transaction, ("$first", DeviceSlot(since)), ("$last", DeviceSlot(through)),
            ("$since", since.ToUnixTimeMilliseconds()), ("$through", through.ToUnixTimeMilliseconds()));
        using var rows = history.ExecuteReader();
        var samples = new List<DeviceSample>();
        while (rows.Read())
        {
            if (JsonSerializer.Deserialize<DeviceSample>(rows.GetString(0)) is { } sample) samples.Add(sample);
        }
        return samples;
    }

    internal bool LastTurnWasDeviceAnalysis(string conversationId)
    {
        using var connection = Open();
        using var command = Command(connection, """
            SELECT local_only AND model = $model FROM turns WHERE conversation_id = $id ORDER BY created DESC, rowid DESC LIMIT 1
            """, values: [("$id", conversationId), ("$model", ChatEngine.DeviceAnalysisModel)]);
        return command.ExecuteScalar() is 1L;
    }

    private static long DeviceSlot(DateTimeOffset date) => date.ToUnixTimeSeconds() / DeviceHistorySeconds;
}
