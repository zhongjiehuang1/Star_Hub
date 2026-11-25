using Microsoft.UI.Text;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Automation;
using Microsoft.UI.Xaml.Controls;
using StarHub.Core;
using StarHub.Platform;

namespace StarHub.Windows;

public sealed partial class MainWindow
{
    private async void DeviceMonitorClicked(object sender, RoutedEventArgs args) => await TryAsync(ShowDeviceMonitorAsync);

    private async Task ShowDeviceMonitorAsync()
    {
        Try(WindowsHost.EnsureWorkerRunning);
        using var stop = new CancellationTokenSource();
        var ink = Brush("BrandBrush");
        var rule = Brush("RuleBrush");
        var sampleTime = Label(T("Waiting for background readings…"));
        AutomationProperties.SetAutomationId(sampleTime, "DeviceSampleTime");
        var duration = new ComboBox { ItemsSource = new[] { T("Last 5 minutes"), T("Last 30 minutes") }, SelectedIndex = 0 };
        AutomationProperties.SetName(duration, T("Chart time window"));

        var cpu = new DeviceTrend("CPU", sample => sample.CpuPercent, T, ink, rule);
        var memory = new DeviceTrend("Memory", sample => sample.MemoryPercent, T, ink, rule);
        var disk = new DeviceTrend("Disk activity", sample => sample.DiskBusyPercent, T, ink, rule);
        var gpu = new DeviceTrend("GPU", sample => sample.GpuPercent, T, ink, rule);
        var throughput = new DeviceTrend("Disk transfer speed", sample => sample.DiskReadBytesPerSecond, T, ink, rule,
            DataRate, "Read", new("Write", sample => sample.DiskWriteBytesPerSecond));
        var latency = new DeviceTrend("Disk response time", sample => sample.DiskLatencyMs, T, ink, rule, Milliseconds);
        var volume = new DeviceTrend("System drive free space", sample => sample.SystemDriveFreePercent, T, ink, rule);
        var traffic = new DeviceTrend("Network traffic", sample => sample.ReceiveBytesPerSecond, T, ink, rule,
            DataRate, "Download", new("Upload", sample => sample.SendBytesPerSecond));
        var temperature = new DeviceTrend("Temperature (ACPI)", sample => sample.ThermalCelsius, T, ink, rule, Celsius);
        var battery = new DeviceTrend("Battery charge", sample => sample.BatteryPercent, T, ink, rule);
        DeviceTrend[] trends = [cpu, memory, disk, gpu, throughput, latency, volume, traffic, temperature, battery];

        var metrics = new Grid { ColumnSpacing = 10, RowSpacing = 10 };
        metrics.ColumnDefinitions.Add(new());
        metrics.ColumnDefinitions.Add(new());
        DeviceTrend[] overview = [cpu, memory, disk, gpu];
        for (int index = 0; index < overview.Length; index++)
        {
            metrics.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
            metrics.Children.Add(DeviceCard(overview[index]));
        }
        void LayoutMetrics(bool compact)
        {
            for (int index = 0; index < metrics.Children.Count; index++)
            {
                var card = (Border)metrics.Children[index];
                Grid.SetColumnSpan(card, compact ? 2 : 1);
                Grid.SetColumn(card, compact ? 0 : index % 2);
                Grid.SetRow(card, compact ? index : index / 2);
            }
        }
        LayoutMetrics(true);
        metrics.SizeChanged += (_, args) => LayoutMetrics(args.NewSize.Width < 365);

        var details = new StackPanel { Spacing = 8 };
        var report = new StackPanel { Spacing = 6 };
        var alertSection = AddSection("Latest performance alert", report);
        report.Children.Add(Label(T("No performance alerts recorded.")));

        DeviceReading? latest = null;
        IReadOnlyList<DeviceSample> history = [];
        var updateExpanded = new List<Action>();

        Expander AddSection(string name, FrameworkElement content)
        {
            var section = new Expander
            {
                Header = T(name), Content = content, HorizontalAlignment = HorizontalAlignment.Stretch,
                HorizontalContentAlignment = HorizontalAlignment.Stretch
            };
            details.Children.Add(section);
            return section;
        }

        void AddReadings(string name, Action<StackPanel, DeviceReading> draw, params DeviceTrend[] graphs)
        {
            var content = new StackPanel { Spacing = 11 };
            foreach (var graph in graphs) content.Children.Add(DeviceCard(graph));
            var values = new StackPanel { Spacing = 8 };
            values.Children.Add(Label(T("Waiting for readings")));
            content.Children.Add(values);
            var section = AddSection(name, content);
            DateTimeOffset? rendered = null;
            void Render()
            {
                if (latest is not { } reading || rendered == reading.Sample.SampledAt) return;
                rendered = reading.Sample.SampledAt;
                values.Children.Clear();
                draw(values, reading);
                if (values.Children.Count == 0) values.Children.Add(Label(T("Reading unavailable")));
            }
            section.Expanding += (_, _) => Render();
            updateExpanded.Add(() => { if (section.IsExpanded) Render(); });
        }

        AddReadings("Processor", DrawProcessor);
        AddReadings("Memory details", DrawMemory);
        AddReadings("Graphics", DrawGraphics);
        AddReadings("Storage and disk activity", DrawStorage, volume, throughput, latency);
        AddReadings("Network adapters", DrawNetwork, traffic);
        AddReadings("Power and temperatures", DrawPower, battery, temperature);
        AddReadings("Processes", DrawProcesses);
        var inventoryPanel = new StackPanel { Spacing = 8 };
        inventoryPanel.Children.Add(Label(T("Waiting for readings")));
        var hardware = AddSection("Hardware and firmware", inventoryPanel);
        AddReadings("Technical readings", (panel, reading) =>
        {
            foreach (var (title, value) in new[] { ("Processor", reading.Processor), ("Memory", reading.Memory),
                ("Graphics", reading.Graphics), ("Storage and disk activity", reading.Storage), ("Network adapters", reading.Network),
                ("Power and temperatures", reading.Power), ("Processes", reading.Processes) })
            {
                var text = Label(value);
                text.IsTextSelectionEnabled = true;
                panel.Children.Add(DeviceCard(DeviceGroup(T(title), text)));
            }
        });

        var body = new StackPanel { Spacing = 12 };
        body.Children.Add(sampleTime);
        body.Children.Add(duration);
        body.Children.Add(metrics);
        body.Children.Add(details);
        var scroll = Scroller(body);
        void ResizePanel()
        {
            body.Width = Math.Clamp(Shell.ActualWidth - 100, 220, 480);
            scroll.MaxHeight = Math.Clamp(Shell.ActualHeight - 150, 100, 580);
        }
        ResizePanel();
        void ResizeOnWindowChange(object sender, SizeChangedEventArgs args) => ResizePanel();
        var dialog = Dialog(T("Device monitor"), scroll);
        dialog.CloseButtonText = T("Close");
        Task sampling = Task.CompletedTask;
        Task? inventory = null;

        TimeSpan Window() => duration.SelectedIndex == 1 ? DeviceTrend.LongRange : DeviceTrend.DefaultRange;
        void DrawTrends()
        {
            if (latest is { } reading)
                foreach (var trend in trends) trend.Update(reading.Sample, history, Window());
        }
        duration.SelectionChanged += (_, _) => DrawTrends();
        hardware.Expanding += (_, _) => inventory ??= ReadInventoryAsync();

        async Task ReadInventoryAsync()
        {
            try
            {
                var result = await Task.Run(() => WindowsDeviceMonitor.ReadInventory(stop.Token), stop.Token);
                if (stop.IsCancellationRequested) return;
                inventoryPanel.Children.Clear();
                foreach (var group in result.GroupBy(item => item.Category))
                    inventoryPanel.Children.Add(DeviceCard(DeviceGroup(T(group.Key), group.Select(item =>
                        (FrameworkElement)Label(item.Available ? item.Details : T("Reading unavailable"))).ToArray())));
            }
            catch (OperationCanceledException) when (stop.IsCancellationRequested) { }
            catch (Exception error)
            {
                inventoryPanel.Children.Clear();
                inventoryPanel.Children.Add(Label(T("Hardware inventory is unavailable.")));
                WindowsHost.Log("desktop.log", $"Device inventory: {error.GetType().Name} (0x{error.HResult:X8}).");
            }
        }

        async Task ReadSamplesAsync()
        {
            DateTimeOffset? displayedAlert = null;
            DateTimeOffset nextHistoryRead = DateTimeOffset.MinValue;
            while (!stop.IsCancellationRequested)
            {
                try
                {
                    var now = DateTimeOffset.UtcNow;
                    bool refresh = now >= nextHistoryRead;
                    var (reading, alert, samples) = await Task.Run(() =>
                    {
                        var current = store.LatestDeviceReading();
                        var end = current?.Sample.SampledAt ?? now;
                        return (current, refresh ? store.LatestDeviceAlert() : null,
                            refresh ? store.ReadDeviceHistory(end - DeviceTrend.LongRange, end) : null);
                    }, stop.Token);
                    if (stop.IsCancellationRequested) break;
                    if (samples is not null)
                    {
                        history = samples;
                        nextHistoryRead = now + LocalStore.DeviceHistoryInterval;
                    }
                    if (refresh && alert?.CreatedAt != displayedAlert)
                    {
                        displayedAlert = alert?.CreatedAt;
                        report.Children.Clear();
                        alertSection.Header = alert is null ? T("Latest performance alert") : "⚠  " + T("Latest performance alert");
                        if (alert is null) report.Children.Add(Label(T("No performance alerts recorded.")));
                        else
                        {
                            report.Children.Add(Label($"{alert.CreatedAt.ToLocalTime():g} · {alert.Title}"));
                            MarkdownView.AddBlocks(report, alert.Report, Brush("SideRailBrush"));
                        }
                    }
                    if (reading is not null)
                    {
                        var timestamp = reading.Sample.SampledAt;
                        bool live = now - timestamp <= LocalStore.DeviceLiveAge;
                        sampleTime.Text = (live ? "●  " : "○  ") + string.Format(live
                            ? T("Updated at {0} · Local readings") : T("Last sample {0} · Waiting for the collector"), timestamp.ToLocalTime().ToString("T"));
                        sampleTime.Foreground = live ? ink : null;
                        bool redraw = latest?.Sample.SampledAt != timestamp || samples is not null;
                        latest = reading;
                        if (redraw)
                        {
                            DrawTrends();
                            foreach (var update in updateExpanded) update();
                        }
                    }
                }
                catch (OperationCanceledException) when (stop.IsCancellationRequested) { break; }
                catch (Exception error)
                {
                    if (stop.IsCancellationRequested) break;
                    sampleTime.Text = T("Live readings are unavailable.");
                    sampleTime.Foreground = null;
                    WindowsHost.Log("desktop.log", $"Device monitor: {error.GetType().Name} (0x{error.HResult:X8}).");
                }
                try { await Task.Delay(LocalStore.DeviceRefreshInterval, stop.Token); }
                catch (OperationCanceledException) when (stop.IsCancellationRequested) { break; }
            }
        }

        dialog.Opened += (_, _) => sampling = ReadSamplesAsync();
        dialog.Closing += (_, _) => stop.Cancel();
        void StopOnWindowClose(object sender, WindowEventArgs args) => stop.Cancel();
        Shell.SizeChanged += ResizeOnWindowChange;
        Closed += StopOnWindowClose;
        try { await dialog.ShowAsync(); }
        finally
        {
            Shell.SizeChanged -= ResizeOnWindowChange;
            Closed -= StopOnWindowClose;
            stop.Cancel();
            await Task.WhenAll(sampling, inventory ?? Task.CompletedTask);
        }
    }

    private Border DeviceCard(FrameworkElement child) => new()
    {
        Child = child, CornerRadius = new CornerRadius(14), Padding = new Thickness(11), Background = Brush("ComposerBrush")
    };

    private static StackPanel DeviceGroup(string title, params FrameworkElement[] children)
    {
        var group = new StackPanel { Spacing = 6 };
        group.Children.Add(new TextBlock { Text = title, FontWeight = FontWeights.SemiBold, TextWrapping = TextWrapping.Wrap });
        foreach (var child in children) group.Children.Add(child);
        return group;
    }

    private FrameworkElement DeviceMeter(string title, double? value, string? details = null)
    {
        var group = new StackPanel { Spacing = 4 };
        var label = Label(title + " · " + (details ?? (value is { } reading ? $"{reading:F0}%" : T("Reading unavailable"))));
        group.Children.Add(label);
        if (value is { } percent && double.IsFinite(percent))
        {
            var bar = new ProgressBar { Maximum = 100, Value = Math.Clamp(percent, 0, 100), Height = 6, CornerRadius = new CornerRadius(3) };
            AutomationProperties.SetName(bar, title);
            group.Children.Add(bar);
        }
        return group;
    }

    private void DrawProcessor(StackPanel panel, DeviceReading reading)
    {
        if (reading.Breakdown is not { } values) return;
        var info = DeviceGroup(T("Processor"),
            Label(string.Format(T("{0} logical processors"), values.LogicalProcessors)),
            Label(string.Format(T("Uptime {0}"), DeviceDuration(values.UptimeSeconds))));
        if (values.FrequencyMHz is { } mhz) info.Children.Add(Label(string.Format(T("Speed {0:N2} GHz"), mhz / 1000)));
        panel.Children.Add(DeviceCard(info));
        if (values.Cores.Length == 0) return;
        var cores = DeviceGroup(T("Logical processor load"));
        foreach (var core in values.Cores) cores.Children.Add(DeviceMeter(core.Name, core.Percent));
        panel.Children.Add(DeviceCard(cores));
    }

    private void DrawMemory(StackPanel panel, DeviceReading reading)
    {
        if (reading.Breakdown?.Memory is not { } memory) return;
        if (memory.TotalBytes is { } total && memory.AvailableBytes is { } available)
            panel.Children.Add(DeviceCard(DeviceMeter(T("Physical memory used"), Percent(total - available, total),
                string.Format(T("{0} of {1} · {2} available"), ByteSize(total - available), ByteSize(total), ByteSize(available)))));
        if (memory.CommitBytes is { } commit && memory.CommitLimitBytes is { } limit)
            panel.Children.Add(DeviceCard(DeviceGroup(T("Memory reserved by Windows and apps"),
                DeviceMeter(T("Committed memory"), Percent(commit, limit), $"{ByteSize(commit)} / {ByteSize(limit)}"),
                Label(string.Format(T("Peak {0}"), ByteSize(memory.CommitPeakBytes))))));
        if (memory.CacheBytes is not null)
        {
            double? max = memory.TotalBytes;
            panel.Children.Add(DeviceCard(DeviceGroup(T("Windows memory use"),
                DeviceMeter(T("System cache"), Percent(memory.CacheBytes, max), ByteSize(memory.CacheBytes)),
                DeviceMeter(T("Paged kernel memory"), Percent(memory.PagedPoolBytes, max), ByteSize(memory.PagedPoolBytes)),
                DeviceMeter(T("Nonpaged kernel memory"), Percent(memory.NonPagedPoolBytes, max), ByteSize(memory.NonPagedPoolBytes)))));
        }
    }

    private void DrawGraphics(StackPanel panel, DeviceReading reading)
    {
        if (reading.Breakdown?.Gpus is not { } gpus) return;
        for (int index = 0; index < gpus.Length; index++)
        {
            var gpu = gpus[index];
            panel.Children.Add(DeviceCard(DeviceGroup($"{T("GPU")} {index + 1}",
                DeviceMeter(T("Busiest engine"), gpu.Percent),
                Label(T("Dedicated graphics memory") + " · " + ByteSize(gpu.DedicatedBytes)),
                Label(T("Shared graphics memory") + " · " + ByteSize(gpu.SharedBytes)))));
        }
    }

    private void DrawStorage(StackPanel panel, DeviceReading reading)
    {
        if (reading.Breakdown is not { } values) return;
        foreach (var volume in values.Volumes)
            panel.Children.Add(DeviceCard(DeviceMeter(volume.Name, Percent(volume.TotalBytes - volume.AvailableBytes, volume.TotalBytes),
                string.Format(T("{0} free of {1}"), ByteSize(volume.AvailableBytes), ByteSize(volume.TotalBytes)))));
        foreach (var disk in values.Disks)
            panel.Children.Add(DeviceCard(DeviceGroup($"{T("Physical disk")} {disk.Name}",
                DeviceMeter(T("Disk activity"), disk.BusyPercent),
                Label("↓ " + T("Read") + " · " + DisplayRate(disk.ReadRate)),
                Label("↑ " + T("Write") + " · " + DisplayRate(disk.WriteRate)),
                Label(T("Disk response time") + " · " + (disk.LatencyMs is { } delay ? Milliseconds(delay) : T("Reading unavailable"))))));
    }

    private void DrawNetwork(StackPanel panel, DeviceReading reading)
    {
        if (reading.Breakdown?.Network is not { } adapters) return;
        foreach (var adapter in adapters)
        {
            var status = Label((adapter.Connected ? "●  " : "○  ") + T(adapter.Connected ? "Connected" : "Not connected"));
            if (adapter.Connected) status.Foreground = Brush("BrandBrush");
            var group = DeviceGroup(adapter.Name, status);
            if (adapter.Connected)
            {
                if (adapter.LinkBitsPerSecond is { } speed) group.Children.Add(Label(string.Format(T("Link speed {0:N0} Mb/s"), speed / 1_000_000.0)));
                group.Children.Add(Label("↓ " + T("Download") + " · " + DisplayRate(adapter.ReceiveRate)));
                group.Children.Add(Label("↑ " + T("Upload") + " · " + DisplayRate(adapter.SendRate)));
            }
            panel.Children.Add(DeviceCard(group));
        }
    }

    private void DrawPower(StackPanel panel, DeviceReading reading)
    {
        if (reading.Breakdown is not { } values) return;
        string supply = T(reading.Sample.AcConnected switch { true => "Plugged in", false => "Unplugged", _ => "Power source unavailable" });
        var power = DeviceGroup("⏻  " + supply);
        switch (values.Power?.HasBattery)
        {
            case false: power.Children.Add(Label(T("No system battery"))); break;
            case true:
                power.Children.Add(DeviceMeter(T("Battery charge"), reading.Sample.BatteryPercent));
                if (values.Power.Charging == true) power.Children.Add(Label(T("Charging")));
                if (values.Power.SecondsRemaining is { } remaining)
                    power.Children.Add(Label(string.Format(T("About {0} remaining"), DeviceDuration(remaining))));
                if (values.Power.SaverActive) power.Children.Add(Label(T("Battery saver is on")));
                break;
            default: power.Children.Add(Label(T("Battery reading unavailable"))); break;
        }
        panel.Children.Add(DeviceCard(power));
        foreach (var zone in values.Temperatures)
            panel.Children.Add(DeviceCard(DeviceGroup(zone.Name, Label(Celsius(zone.Celsius)))));
    }

    private void DrawProcesses(StackPanel panel, DeviceReading reading)
    {
        var processes = reading.Sample.TopProcesses ?? [];
        if (processes.Any(process => process.CpuPercent.HasValue))
        {
            var cpu = DeviceGroup(T("Top processes by CPU"));
            foreach (var process in processes.Where(process => process.CpuPercent.HasValue).OrderByDescending(process => process.CpuPercent).Take(5))
                cpu.Children.Add(DeviceMeter(process.Name, process.CpuPercent, $"{process.CpuPercent:F1}%"));
            panel.Children.Add(DeviceCard(cpu));
        }
        if (processes.Length > 0)
        {
            var memory = DeviceGroup(T("Top processes by memory"));
            var top = processes.OrderByDescending(process => process.WorkingSetBytes).Take(5).ToArray();
            long largest = top[0].WorkingSetBytes;
            foreach (var process in top)
                memory.Children.Add(DeviceMeter(process.Name, Percent(process.WorkingSetBytes, largest), ByteSize(process.WorkingSetBytes)));
            panel.Children.Add(DeviceCard(memory));
        }
        if (reading.Breakdown?.ProcessCounts is { } counts)
            panel.Children.Add(Label(string.Format(T("{0:N0} processes · {1:N0} threads · {2:N0} handles"),
                counts.Processes, counts.Threads, counts.Handles)));
    }

    private string DeviceDuration(long seconds)
    {
        var span = TimeSpan.FromSeconds(seconds);
        return span.TotalDays >= 1 ? string.Format(T("{0} d {1} h"), (int)span.TotalDays, span.Hours)
            : string.Format(T("{0} h {1} min"), span.Hours, span.Minutes);
    }

    private string ByteSize(double? size) => size is { } bytes ? WindowsDeviceMonitor.FormatBytes(bytes) : T("Reading unavailable");
    private string DisplayRate(double? rate) => rate is { } bytes ? DataRate(bytes) : T("Reading unavailable");
    private static double? Percent(double? value, double? maximum) => maximum > 0 ? 100 * value / maximum : null;
    private static string DataRate(double bytes) => WindowsDeviceMonitor.FormatBytes(bytes) + "/s";
    private static string Milliseconds(double value) => $"{value:0.##} ms";
    private static string Celsius(double value) => $"{value:0.#} °C";
}
