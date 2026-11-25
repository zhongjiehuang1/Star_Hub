using System.Text;

namespace StarHub.Core;

[Flags]
internal enum DevicePressure
{
    None = 0, Cpu = 1, Memory = 2, Disk = 4, StorageSpace = 8, Gpu = 16, Thermal = 32, DiskActivity = 64
}

public static class DeviceAlerts
{
    public static readonly TimeSpan CheckInterval = TimeSpan.FromSeconds(15);
    private static readonly TimeSpan WatchWindow = TimeSpan.FromMinutes(2);
    private static readonly TimeSpan BaselineWindow = TimeSpan.FromHours(2);

    private sealed record Rule(DevicePressure Kind, string Chinese, string English, Func<DeviceSample, double?> Value,
        double Absolute, double Floor, double Increase, double Recovery, string Unit)
    {
        public string Format(double value) => $"{value:F0}{Unit}";
    }

    private readonly record struct Observation(DeviceSample Sample, double Value);
    private sealed record Candidate(Rule Rule, double Current, Observation[] Observations);
    private sealed record Finding(Rule Rule, double Current, Observation[] High, int Count, double? Baseline);

    private static readonly Rule[] Rules =
    [
        new(DevicePressure.Cpu, "CPU 占用", "CPU load", sample => sample.CpuPercent, 90, 75, 25, 68, "%"),
        new(DevicePressure.Memory, "内存占用", "Memory use", sample => sample.MemoryPercent, 93, 85, 16, 80, "%"),
        new(DevicePressure.Disk, "磁盘 I/O 延迟", "Disk I/O latency",
            sample => sample.DiskLatencyMs ?? (sample.DiskBusyPercent <= 10 ? 0 : null), 55, 30, 20, 18, " ms"),
        new(DevicePressure.DiskActivity, "最忙磁盘活动", "Busiest disk activity", sample => sample.DiskBusyPercent,
            96, 85, 25, 70, "%"),
        new(DevicePressure.StorageSpace, "系统盘占用", "System drive filled", sample => 100 - sample.SystemDriveFreePercent,
            95, 95, 0, 92, "%"),
        new(DevicePressure.Gpu, "GPU 最忙引擎", "Busiest GPU engine", sample => sample.GpuPercent, 97, 85, 25, 75, "%"),
        new(DevicePressure.Thermal, "最高可见 ACPI 温区", "Highest available ACPI zone temperature",
            sample => sample.ThermalCelsius, 90, 83, 12, 78, " °C")
    ];

    public static DeviceAlert? Check(LocalStore store, DateTimeOffset now, bool chinese)
    {
        var start = now - WatchWindow;
        var evidence = store.ReadDeviceEvidence(start, now);
        if (evidence.Latest is not { } latest || latest.SampledAt > now || now - latest.SampledAt > LocalStore.DeviceLiveAge)
            return null;

        var recent = evidence.History.ToList();
        if (recent.All(sample => sample.SampledAt != latest.SampledAt)) recent.Add(latest);
        var candidates = new List<Candidate>();
        var recovered = DevicePressure.None;
        foreach (var rule in Rules)
        {
            if (rule.Value(latest) is not { } current || !double.IsFinite(current)) continue;
            var observations = Observations(recent, rule);
            if (!HasCoverage(observations)) continue;
            if (current < rule.Recovery && Enough(observations, value => value < rule.Recovery))
                recovered |= rule.Kind;
            if (current >= rule.Floor && Enough(observations, value => value >= rule.Floor))
                candidates.Add(new Candidate(rule, current, observations));
        }

        var active = DevicePressure.None;
        var findings = new List<Finding>();
        if (candidates.Count > 0)
        {
            var history = store.ReadDeviceEvidence(now - BaselineWindow, start.AddMilliseconds(-1)).History;
            foreach (var candidate in candidates)
            {
                var rule = candidate.Rule;
                var earlier = Observations(history, rule).Select(item => item.Value).Order().ToArray();
                double? typical = earlier.Length >= 12 ? (earlier[(earlier.Length - 1) / 2] + earlier[earlier.Length / 2]) / 2 : null;
                double threshold = rule.Increase > 0 && typical.HasValue
                    ? Math.Min(rule.Absolute, Math.Max(rule.Floor, typical.Value + rule.Increase)) : rule.Absolute;
                if (candidate.Current < threshold) continue;
                var high = candidate.Observations.Where(item => item.Value >= threshold).ToArray();
                if (high.Length * 4 < candidate.Observations.Length * 3) continue;
                active |= rule.Kind;
                findings.Add(new Finding(rule, candidate.Current, high, candidate.Observations.Length, typical));
            }
        }
        return store.UpdateDeviceAlert(now, active, recovered, () => Describe(now, findings, chinese));
    }

    private static Observation[] Observations(IEnumerable<DeviceSample> samples, Rule rule) => samples
        .Select(sample => (Sample: sample, Value: rule.Value(sample)))
        .Where(item => item.Value.HasValue && double.IsFinite(item.Value.Value))
        .Select(item => new Observation(item.Sample, item.Value.GetValueOrDefault())).ToArray();

    private static bool HasCoverage(Observation[] observations)
    {
        if (observations.Length < 6 || observations[^1].Sample.SampledAt - observations[0].Sample.SampledAt < TimeSpan.FromSeconds(75))
            return false;
        for (int i = 1; i < observations.Length; i++)
            if (observations[i].Sample.SampledAt - observations[i - 1].Sample.SampledAt > TimeSpan.FromSeconds(40)) return false;
        return true;
    }

    private static bool Enough(Observation[] observations, Func<double, bool> meets) =>
        observations.Count(item => meets(item.Value)) * 4 >= observations.Length * 3;

    private static DeviceAlert Describe(DateTimeOffset now, List<Finding> findings, bool chinese)
    {
        string T(string zh, string en) => chinese ? zh : en;
        string Title(Rule rule) => T(rule.Chinese, rule.English);
        var primary = findings[0];
        string name = Title(primary.Rule), value = primary.Rule.Format(primary.Current);
        string title = T("Star Hub · 检测到持续的资源压力", "Star Hub · Sustained device pressure");
        string prior = primary.Baseline is { } basis
            ? T($"，此前中位值 {primary.Rule.Format(basis)}", $", earlier median {primary.Rule.Format(basis)}") : "";
        string message = T($"{name}持续偏高，当前 {value}{prior}。{Advice(primary.Rule.Kind, true)}",
            $"{name} remains high at {value}{prior}. {Advice(primary.Rule.Kind, false)}");
        var body = new StringBuilder();
        body.AppendLine(T("**可能影响响应的资源压力**", "**Resource pressure that could affect responsiveness**"));
        body.AppendLine();
        body.AppendLine(T("比较了最近两分钟的连续读数和此前最多两小时的历史样本。", "Compared continuous readings from the last two minutes with up to two hours of earlier samples."));
        foreach (var finding in findings)
        {
            var rule = finding.Rule;
            string baseline = finding.Baseline is { } typical
                ? T($"，此前中位值 {rule.Format(typical)}", $", earlier median {rule.Format(typical)}")
                : T("，同类历史基线样本不足", ", too few earlier readings for a baseline");
            body.AppendLine($"- {Title(rule)} · " + T(
                $"当前 {rule.Format(finding.Current)}，{finding.High.Length}/{finding.Count} 个有效样本偏高{baseline}。",
                $"now {rule.Format(finding.Current)}, high in {finding.High.Length}/{finding.Count} available readings{baseline}."));
            if (DescribeProcess(rule.Kind, finding.High.Select(item => item.Sample), chinese) is { } process)
                body.AppendLine($"  - {process}");
        }
        body.AppendLine();
        body.AppendLine(T("**怎么处理**", "**What to do**"));
        foreach (string advice in findings.Select(finding => Advice(finding.Rule.Kind, chinese)).Distinct())
            body.AppendLine($"- {advice}");
        body.AppendLine();
        body.AppendLine(T("这些读数是可能的原因。处理后可再询问设备状态，比较变化。", "These readings point to possible causes. Ask about device status again after acting to compare the readings."));
        return new DeviceAlert(now, title, message, body.ToString().TrimEnd());
    }

    internal static string? DescribeProcess(DevicePressure pressure, IEnumerable<DeviceSample> samples, bool chinese)
    {
        if (pressure is not (DevicePressure.Cpu or DevicePressure.Memory)) return null;
        var processes = samples.SelectMany(sample => sample.TopProcesses ?? []);
        var item = pressure == DevicePressure.Cpu
            ? processes.Where(process => process.CpuPercent >= 10).OrderByDescending(process => process.CpuPercent).FirstOrDefault()
            : processes.OrderByDescending(process => process.WorkingSetBytes).FirstOrDefault();
        if (item is null) return null;
        string name = item.Name.Replace('`', '\'').Replace('\r', ' ').Replace('\n', ' ');
        string who = $"`{name}` (PID {item.Id})";
        if (pressure == DevicePressure.Cpu)
            return chinese ? $"高负载样本中，{who} 曾占整机 CPU 的 {item.CpuPercent:F1}%。"
                : $"In a high-load sample, {who} used {item.CpuPercent:F1}% of the machine's CPU.";
        double workingSet = item.WorkingSetBytes / 1_073_741_824.0;
        return chinese ? $"内存高占用样本中，{who} 的工作集曾达到 {workingSet:F1} GiB。"
            : $"In a high-memory sample, {who} had a {workingSet:F1} GiB working set.";
    }

    internal static string Advice(DevicePressure pressure, bool chinese) => (pressure, chinese) switch
    {
        (DevicePressure.Cpu, true) => "保存工作后，打开任务管理器按 CPU 排序，暂停不急的高占用任务，再看占用是否下降。",
        (DevicePressure.Cpu, false) => "Save your work, sort Task Manager by CPU, and pause nonessential high-CPU work to see whether usage falls.",
        (DevicePressure.Memory, true) => "在任务管理器按内存排序，保存并关闭用不到的高内存应用或标签页，再查看可用内存。",
        (DevicePressure.Memory, false) => "Sort Task Manager by memory, save and close unused high-memory apps or tabs, then check available memory.",
        (DevicePressure.Disk or DevicePressure.DiskActivity, true) => "在任务管理器查看磁盘占用，尝试暂停非紧急的同步、复制或安装，再复查读数。",
        (DevicePressure.Disk or DevicePressure.DiskActivity, false) => "Check disk use in Task Manager, pause nonessential sync, copies, or installs, and recheck the readings.",
        (DevicePressure.StorageSpace, true) => "在 Windows 设置的存储页核对大文件和临时文件，确认用途后再腾出系统盘空间。",
        (DevicePressure.StorageSpace, false) => "Review large and temporary files in Windows Storage settings before freeing space on the system drive.",
        (DevicePressure.Gpu, true) => "在任务管理器按 GPU 排序，确认是否是正在运行的游戏、渲染或本地模型，暂停不急的任务后复查。",
        (DevicePressure.Gpu, false) => "Sort Task Manager by GPU, identify the game, render, or local model using it, and pause nonessential work before rechecking.",
        (_, true) => "检查散热口和风扇是否通畅，暂时降低高负载任务，再复查该温区的读数。",
        _ => "Check vents and fans, ease heavy workloads briefly, and recheck the reported thermal-zone reading."
    };
}
