using System.Text;
using System.Text.RegularExpressions;

namespace StarHub.Core;

internal sealed record DeviceQuestion(bool Chinese, bool Slowdown, TimeSpan Window, bool LatestAlert = false, bool IncludeProcesses = false);

internal static class DeviceDiagnostics
{
    private const RegexOptions PatternOptions = RegexOptions.IgnoreCase | RegexOptions.CultureInvariant;
    private static readonly Regex Computer = new(@"电脑|计算机|本机|这台机器|\b(?:pc|computer|laptop|workstation|this device|this machine)\b", PatternOptions);
    private static readonly Regex ExplicitLocal = new(@"我的电脑|我这台|这台电脑|本机|这台机器|\b(?:my|this)\s+(?:pc|computer|laptop|workstation|device|machine)\b", PatternOptions);
    private static readonly Regex GeneralTopic = new(@"推荐|选购|买哪|购买|是什么|原理|定义|跑分对比|\b(?:recommend|buy|shopping|definition|comparison|benchmark|what is)\b", PatternOptions);
    private static readonly Regex Performance = new(@"变慢|卡顿|卡了|很卡|好卡|卡住|跑得怎么样|运行得怎么样|运行状况|运行状态|运行如何|健康状态|性能|占用|负载|温度|电池|磁盘|内存|\b(?:running|doing|performance|healthy|health|slow(?:er|down)?|lag(?:ging|gy)?|sluggish|resources|usage|load|temperature)\b", PatternOptions);
    private static readonly Regex Slow = new(@"变慢|卡顿|卡了|很卡|好卡|卡住|慢了|瓶颈|\b(?:slow(?:er|down)?|lag(?:ging|gy)?|sluggish|bottleneck)\b|slowing .*down", PatternOptions);
    private static readonly Regex Metric = new(@"CPU|GPU|处理器|显卡|内存|显存|磁盘|硬盘|网卡|网络|温度|电池|进程|\b(?:memory|ram|disk|storage|network|battery|processes)\b", PatternOptions);
    private static readonly Regex Reading = new(@"现在|当前|最近|过去|占用|用量|读数|多少|怎么样|是否|为何|为什么|还高|多少度|\b(?:current(?:ly)?|right now|usage|load|utili[sz]ation|reading|past|last|how much|why)\b", PatternOptions);
    private static readonly Regex MetricFollowUp = new(@"^(?:那|那么|what about\s+)?\s*(?:CPU|GPU|处理器|显卡|内存|显存|磁盘|硬盘|网卡|网络|温度|电池|进程|memory|ram|disk|storage|network|battery|processes)\s*(?:呢|怎么样|如何|why|too)?\s*[?？。]?\s*$", PatternOptions);
    private static readonly Regex SlowFollowUp = new(@"^(?:why is it (?:so )?slow(?:er)?|what(?: is|'s) slowing it down)[?!. ]*$", PatternOptions);
    private static readonly Regex SimpleComplaint = new(@"^(?:(?:现在|最近|怎么|为何|为什么|为啥)\s*)+(?:这么|这么的|突然|越来越|又)?\s*(?:慢|变慢|很慢|卡顿|这么卡|卡|卡住)(?:了|呢|啊)?\s*[?？。!！]*$", PatternOptions);
    private static readonly Regex TimeWindow = new(@"(?:过去|最近|近)\s*(\d{1,3}|一|二|两|三|七|半)\s*个?\s*(小时|钟头|分钟|天)|\b(?:last|past|previous)\s+(\d{1,3})\s*(days?|hours?|hrs?|minutes?|mins?)\b", PatternOptions);
    private static readonly Regex AlertQuestion = new(@"(?:设备|性能|资源|电脑).{0,8}(?:提醒|告警|异常通知)|\b(?:performance|device|resource)\s+(?:alert|warning)\b", PatternOptions);
    private static readonly Regex LocalStatus = new(@"(?:这台|我的|我这台)(?:电脑|计算机|机器)\s*(?:现在|当前)?\s*(?:怎么样|如何|状态)|(?:本机|电脑|计算机)\s*(?:现在|当前)\s*(?:怎么样|如何|状态)", PatternOptions);
    private static readonly Regex TaskManagerInspection = new(@"任务管理器.{0,12}(?:看|查|检查|分析)|(?:查看|检查|分析).{0,6}任务管理器|\b(?:check|look in|inspect|see)\s+(?:the\s+)?task manager\b", PatternOptions);

    public static DeviceQuestion? Parse(string prompt, bool followsDevice)
    {
        if (prompt.Length == 0) return null;
        bool chinese = prompt.Any(character => character is >= '\u3400' and <= '\u9fff');
        if (AlertQuestion.IsMatch(prompt)) return new DeviceQuestion(chinese, true, TimeSpan.Zero, LatestAlert: true);
        if (GeneralTopic.IsMatch(prompt) && !ExplicitLocal.IsMatch(prompt)) return null;
        var span = TimeWindow.Match(prompt);
        bool timeFollowUp = span.Success && span.Index == 0 && prompt[span.Length..].Trim(' ', '?', '？', '。', '的', '呢').Length == 0;
        bool slowdown = Slow.IsMatch(prompt) || SimpleComplaint.IsMatch(prompt);
        bool inspection = TaskManagerInspection.IsMatch(prompt);
        bool inferredDevice = LocalStatus.IsMatch(prompt) || inspection ||
            (Computer.IsMatch(prompt) && (Performance.IsMatch(prompt) || slowdown)) ||
            SimpleComplaint.IsMatch(prompt) ||
            (Metric.IsMatch(prompt) && Reading.IsMatch(prompt)) ||
            (followsDevice && (MetricFollowUp.IsMatch(prompt) || SlowFollowUp.IsMatch(prompt) || timeFollowUp));
        if (!inferredDevice) return null;

        int minutes = 15;
        if (span.Success)
        {
            string number = span.Groups[1].Success ? span.Groups[1].Value : span.Groups[3].Value;
            string unit = span.Groups[2].Success ? span.Groups[2].Value : span.Groups[4].Value.ToLowerInvariant();
            int multiplier = unit is "天" || unit[0] == 'd' ? 1440 : unit is "小时" or "钟头" || unit[0] == 'h' ? 60 : 1;
            minutes = number switch
            {
                "半" => multiplier / 2,
                "一" => multiplier,
                "二" or "两" => 2 * multiplier,
                "三" => 3 * multiplier,
                "七" => 7 * multiplier,
                _ when int.TryParse(number, out int count) => count * multiplier,
                _ => minutes
            };
        }
        return new DeviceQuestion(chinese, slowdown, TimeSpan.FromMinutes(Math.Clamp(minutes, 1, (int)LocalStore.DeviceHistoryRetention.TotalMinutes)),
            IncludeProcesses: inspection);
    }

    public static string Answer(LocalStore store, DeviceQuestion question, DateTimeOffset now)
    {
        string T(string chinese, string english) => question.Chinese ? chinese : english;
        if (question.LatestAlert)
        {
            var alert = store.LatestDeviceAlert();
            return alert is null
                ? T("本机目前没有保留的设备性能提醒。", "There is currently no saved device performance alert on this PC.")
                : T($"最近一次性能提醒记录于 {Time(alert.CreatedAt)}。", $"The latest performance alert was recorded at {Time(alert.CreatedAt)}.")
                    + "\n\n" + alert.Report;
        }
        var recentStart = now - question.Window;
        var evidence = store.ReadDeviceEvidence(recentStart - TimeSpan.FromHours(24), now);
        var recent = evidence.History.Where(sample => sample.SampledAt >= recentStart).ToList();
        var baseline = evidence.History.Where(sample => sample.SampledAt < recentStart).ToArray();
        var latest = evidence.Latest;
        var live = latest is not null && latest.SampledAt <= now + LocalStore.DeviceRefreshInterval &&
            now - latest.SampledAt <= LocalStore.DeviceLiveAge ? latest : null;
        if (live is not null && recent.All(sample => sample.SampledAt != live.SampledAt)) recent.Add(live);
        if (recent.Count == 0 && live is null)
        {
            string last = latest is null ? "" : T($"最后一次采样在 {Time(latest.SampledAt)}。", $"The last reading was at {Time(latest.SampledAt)}. ");
            return last + T("目前没有可用的实时读数或所查时段的样本，暂时无法判断电脑状态。设备面板可查看采集状态。",
                "There are no live readings or samples in the requested window. Open Device to check the collector status.");
        }

        var report = new StringBuilder();
        report.AppendLine(question.Slowdown
            ? T("**电脑变慢的本机诊断**", "**Local slowdown diagnosis**")
            : T("**本机设备状态**", "**Local device status**"));
        report.AppendLine();
        if (live is not null)
            report.AppendLine(T($"当前读数采于 {Time(live.SampledAt)}。", $"Live sample taken at {Time(live.SampledAt)}."));
        else
            report.AppendLine(T("实时读数已过期，以下只分析该时段内的样本。", "The live reading is stale. The assessment below uses only samples in the requested window."));
        int windowMinutes = (int)question.Window.TotalMinutes;
        string span = recent.Count > 0 ? $"{Time(recent[0].SampledAt)}–{Time(recent[^1].SampledAt)}" : "—";
        report.AppendLine(T($"近 {windowMinutes} 分钟有 {recent.Count} 个采样点（{span}）。",
            $"{recent.Count} samples in the last {windowMinutes} min ({span})."));

        var metrics = new[]
        {
            new MetricSeries(DevicePressure.Cpu, "CPU", sample => sample.CpuPercent, 85, Points),
            new MetricSeries(DevicePressure.Memory, T("内存使用率", "Memory used"), sample => sample.MemoryPercent, 90, Points),
            new MetricSeries(DevicePressure.DiskActivity, T("最忙磁盘活动", "Busiest disk activity"), sample => sample.DiskBusyPercent, 85, Points),
            new MetricSeries(DevicePressure.Disk, T("磁盘 I/O 延迟", "Disk I/O latency"), sample => sample.DiskLatencyMs, 25, value => $"{value:F0} ms"),
            new MetricSeries(DevicePressure.Gpu, T("GPU 最忙引擎", "Busiest GPU engine"), sample => sample.GpuPercent, 90, Points)
        };
        var likely = new List<string>();
        var advice = new HashSet<string>();
        void Consider(DevicePressure pressure, string reason)
        {
            likely.Add(reason);
            advice.Add(DeviceAlerts.Advice(pressure, question.Chinese));
        }
        foreach (var metric in metrics)
        {
            double? current = live is null ? null : metric.Value(live);
            var readings = Values(recent, metric.Value);
            if (current is null && readings.Length == 0) continue;
            var fields = new List<string>();
            if (current.HasValue) fields.Add(T($"当前 {metric.Format(current.Value)}", $"now {metric.Format(current.Value)}"));
            if (readings.Length > 0)
            {
                double average = readings.Average();
                fields.Add(T($"近段均值 {metric.Format(average)}、采样峰值 {metric.Format(readings.Max())}",
                    $"recent mean {metric.Format(average)}, sampled peak {metric.Format(readings.Max())}"));
            }
            var older = Values(baseline, metric.Value);
            if (readings.Length >= 3 && older.Length >= 8)
                fields.Add(T($"较早样本均值 {metric.Format(older.Average())}", $"earlier mean {metric.Format(older.Average())}"));
            report.AppendLine($"- {metric.Name} · {string.Join(T("，", ", "), fields)}");
            int high = readings.Count(value => value >= metric.High);
            if (readings.Length >= 4 && high * 2 >= readings.Length)
                Consider(metric.Pressure, T($"{metric.Name}在 {high}/{readings.Length} 个有效样本中偏高", $"{metric.Name} was high in {high}/{readings.Length} available samples"));
            else if (current >= metric.High)
                Consider(metric.Pressure, T($"{metric.Name}当前偏高，尚未确认持续", $"{metric.Name} is high now, with persistence unconfirmed"));
            else if (high >= 2)
                Consider(metric.Pressure, T($"{metric.Name}曾出现高值", $"{metric.Name} had intermittent high readings"));
        }

        if (live?.AvailableMemoryBytes is { } available)
            report.AppendLine(T($"- 可用内存 · {available / 1_073_741_824.0:F1} GiB", $"- Available memory · {available / 1_073_741_824.0:F1} GiB"));
        if (live?.SystemDriveFreePercent is { } free)
        {
            report.AppendLine(T($"- 系统盘可用 · {Points(free)}", $"- System drive free · {Points(free)}"));
            if (free < 10) Consider(DevicePressure.StorageSpace, T("系统盘可用空间低于 10%", "System drive has less than 10% free space"));
        }
        if (live?.ThermalCelsius is { } thermal)
        {
            report.AppendLine(T($"- 可见的最高 ACPI 温区 · {thermal:F1} °C", $"- Highest available ACPI zone temperature · {thermal:F1} °C"));
            if (thermal >= 90) Consider(DevicePressure.Thermal, T("当前可见温区读数偏高", "The current reported thermal-zone reading is high"));
        }
        if (live is { } reading && (reading.ReceiveBytesPerSecond.HasValue || reading.SendBytesPerSecond.HasValue))
            report.AppendLine(T($"- 网卡收发合计 · ↓ {Rate(reading.ReceiveBytesPerSecond)} / ↑ {Rate(reading.SendBytesPerSecond)}",
                $"- Adapter traffic total · ↓ {Rate(reading.ReceiveBytesPerSecond)} / ↑ {Rate(reading.SendBytesPerSecond)}"));
        if (live is { } disks && (disks.DiskReadBytesPerSecond.HasValue || disks.DiskWriteBytesPerSecond.HasValue))
            report.AppendLine(T($"- 磁盘读写合计 · 读 {Rate(disks.DiskReadBytesPerSecond)} / 写 {Rate(disks.DiskWriteBytesPerSecond)}",
                $"- Disk throughput total · read {Rate(disks.DiskReadBytesPerSecond)} / write {Rate(disks.DiskWriteBytesPerSecond)}"));
        if (live?.AcConnected is { } ac)
            report.AppendLine(ac ? T("- 已连接外部电源", "- AC power connected") : T("- 未连接外部电源", "- AC power disconnected"));

        if (question.IncludeProcesses && live?.TopProcesses is { Length: > 0 } processes)
        {
            report.AppendLine().AppendLine(T("**当前最活跃的进程（来自 Windows 本机采集）**", "**Most active processes from Windows readings**"));
            foreach (var process in processes.OrderByDescending(item => item.CpuPercent).ThenByDescending(item => item.WorkingSetBytes).Take(5))
            {
                string usage = process.CpuPercent is { } load ? $"{load:F1}%" : "—";
                string name = process.Name.Replace("`", "");
                report.AppendLine($"- `{name}` · CPU {usage} · {T("内存", "Memory")} {process.WorkingSetBytes / 1048576.0:N0} MiB");
            }
        }
        if (DeviceAlerts.DescribeProcess(DevicePressure.Cpu, recent.Where(sample => sample.CpuPercent >= 75), question.Chinese) is { } cpu)
            report.AppendLine($"- {cpu}");
        if (DeviceAlerts.DescribeProcess(DevicePressure.Memory, recent.Where(sample => sample.MemoryPercent >= 90), question.Chinese) is { } memory)
            report.AppendLine($"- {memory}");
        report.AppendLine();
        if (likely.Count > 0)
        {
            report.AppendLine(T("**可能的瓶颈**", "**Potential bottlenecks**"));
            foreach (string item in likely) report.AppendLine($"- {item}");
            report.AppendLine(T("这些读数能定位压力所在，但还不足以确认单一根因。", "These readings identify where pressure was observed. They do not establish a single root cause."));
            if (question.Slowdown)
            {
                report.AppendLine();
                report.AppendLine(T("**怎么处理**", "**What to do**"));
                foreach (string step in advice) report.AppendLine($"- {step}");
            }
        }
        else
            report.AppendLine(recent.Count < 4
                ? T("历史样本还少，暂时无法判断变慢是否持续。", "There are too few historical samples to tell whether a slowdown persists.")
                : T("现有样本未发现明显的资源压力，变慢原因还无法确定。", "The available samples show no strong resource pressure. The cause of a slowdown is still uncertain."));
        return report.ToString().TrimEnd();
    }

    private static double[] Values(IEnumerable<DeviceSample> samples, Func<DeviceSample, double?> selector) =>
        samples.Select(selector).OfType<double>().Where(double.IsFinite).ToArray();

    private static string Points(double value) => $"{value:F0}%";
    private static string Time(DateTimeOffset time) => time.ToLocalTime().ToString("MM-dd HH:mm:ss");
    private static string Rate(double? rate) => rate.HasValue ? $"{rate.Value / 1024:F1} KiB/s" : "—";
    private sealed record MetricSeries(DevicePressure Pressure, string Name, Func<DeviceSample, double?> Value, double High, Func<double, string> Format);
}
