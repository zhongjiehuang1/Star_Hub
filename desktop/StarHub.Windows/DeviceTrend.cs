using Microsoft.UI.Text;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Automation;
using Microsoft.UI.Xaml.Automation.Peers;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Media;
using Microsoft.UI.Xaml.Shapes;
using StarHub.Core;
using Windows.Foundation;
using Path = Microsoft.UI.Xaml.Shapes.Path;

namespace StarHub.Windows;

internal sealed class DeviceTrend : StackPanel
{
    public static readonly TimeSpan DefaultRange = TimeSpan.FromMinutes(5);
    public static readonly TimeSpan LongRange = TimeSpan.FromMinutes(30);

    internal sealed record Series(string Label, Func<DeviceSample, double?> Read);
    private sealed record LineSeries(Series Series, Path Trace, TextBlock Label);
    private readonly Canvas plot = new() { Height = 58, IsHitTestVisible = false };
    private readonly LineSeries[] lines;
    private readonly TextBlock startLabel, scale;
    private readonly bool isPercent;
    private readonly Func<double, string> formatValue, formatAxis;
    private readonly Func<string, string> translate;
    private IReadOnlyList<DeviceSample> history = [];
    private DeviceSample? latest;
    private TimeSpan range = DefaultRange;

    public DeviceTrend(string title, Func<DeviceSample, double?> read, Func<string, string> translate, Brush ink, Brush rule,
        Func<double, string>? format = null, string? firstLabel = null, Series? second = null, Func<double, string>? axisFormat = null)
    {
        this.translate = translate;
        isPercent = format is null;
        formatValue = format ?? (value => value.ToString("F0") + "%");
        formatAxis = axisFormat ?? formatValue;
        Spacing = 4;
        var heading = new TextBlock { Text = translate(title), TextWrapping = TextWrapping.Wrap, FontSize = 14 };
        Children.Add(heading);
        lines = second is null ? [CreateLine(new(firstLabel ?? title, read), false)] :
            [CreateLine(new(firstLabel ?? title, read), false), CreateLine(second, true)];
        foreach (var line in lines)
        {
            line.Label.Text = lines.Length > 1 ? translate(line.Series.Label) + " —" : "—";
            Children.Add(line.Label);
        }
        AutomationProperties.SetAutomationId(lines[0].Label, "Device" + title);

        var top = new Line { Stroke = rule, StrokeThickness = 1, Y1 = 1, Y2 = 1 };
        var mid = new Line { Stroke = rule, StrokeThickness = 1, Y1 = plot.Height / 2, Y2 = plot.Height / 2, Opacity = 0.6 };
        var bottom = new Line { Stroke = rule, StrokeThickness = 1, Y1 = plot.Height - 1, Y2 = plot.Height - 1 };
        plot.Children.Add(top);
        plot.Children.Add(mid);
        plot.Children.Add(bottom);
        foreach (var line in lines) plot.Children.Add(line.Trace);
        AutomationProperties.SetAccessibilityView(plot, AccessibilityView.Raw);
        plot.SizeChanged += (_, args) =>
        {
            top.X2 = mid.X2 = bottom.X2 = args.NewSize.Width;
            Draw();
        };
        Children.Add(plot);

        var axis = new Grid { ColumnDefinitions = { new(), new() } };
        startLabel = AxisLabel(translate("5 min ago"));
        axis.Children.Add(startLabel);
        var now = AxisLabel(translate("Latest"));
        now.HorizontalAlignment = HorizontalAlignment.Right;
        Grid.SetColumn(now, 1);
        axis.Children.Add(now);
        Children.Add(axis);
        scale = AxisLabel(translate("Waiting for readings"));
        Children.Add(scale);

        LineSeries CreateLine(Series series, bool dashed)
        {
            var trace = new Path { Stroke = ink, StrokeThickness = 2, StrokeLineJoin = PenLineJoin.Round };
            if (dashed)
            {
                trace.StrokeDashArray = new DoubleCollection { 3, 2 };
                trace.Opacity = 0.65;
            }
            return new LineSeries(series, trace, new TextBlock
            {
                TextWrapping = TextWrapping.Wrap, FontSize = second is null ? 22 : 13,
                FontWeight = FontWeights.SemiBold
            });
        }
    }

    private static TextBlock AxisLabel(string text) => new()
    {
        Text = text, TextWrapping = TextWrapping.Wrap, FontSize = 11, Opacity = 0.78
    };

    public void Update(DeviceSample sample, IReadOnlyList<DeviceSample> samples, TimeSpan window)
    {
        latest = sample;
        history = samples;
        range = window;
        startLabel.Text = translate(window == LongRange ? "30 min ago" : "5 min ago");
        for (int index = 0; index < lines.Length; index++)
        {
            var line = lines[index];
            string current = Value(line.Series, sample) is { } value ? formatValue(value) : "—";
            bool hasLegend = lines.Length > 1;
            string name = translate(line.Series.Label);
            line.Label.Text = hasLegend ? (index == 0 ? "━ " : "╌ ") + name + " · " + current : current;
            AutomationProperties.SetName(line.Label, name + " · " + (current == "—" ? translate("Reading unavailable") : current));
        }
        Draw();
    }

    private static double? Value(Series series, DeviceSample sample) =>
        series.Read(sample) is { } value && double.IsFinite(value) ? value : null;

    private void Draw()
    {
        if (latest is null || plot.ActualWidth < 4) return;
        var start = latest.SampledAt - range;
        var samples = history.Where(sample => sample.SampledAt >= start && sample.SampledAt < latest.SampledAt).Append(latest).ToArray();
        var readings = lines.SelectMany(line => samples.Select(sample => Value(line.Series, sample))).OfType<double>().ToArray();
        if (readings.Length == 0)
        {
            foreach (var line in lines) line.Trace.Data = null;
            scale.Text = translate("No readings in this interval");
            return;
        }
        double peak = readings.Max();
        double lower = isPercent ? 0 : Math.Min(0, Math.Floor(readings.Min()));
        double upper = isPercent ? 100 : UpperBound(peak);
        scale.Text = lines.Length == 1
            ? string.Format(translate("Peak {0} · Scale {1}–{2}"), formatValue(peak), formatAxis(lower), formatAxis(upper))
            : string.Format(translate("Scale {0}–{1}"), formatAxis(lower), formatAxis(upper));
        foreach (var line in lines)
        {
            var geometry = new PathGeometry();
            PathFigure? segment = null;
            DateTimeOffset? previous = null;
            foreach (var sample in samples)
            {
                if (Value(line.Series, sample) is not { } value)
                {
                    segment = null;
                    continue;
                }
                double x = 2 + (plot.ActualWidth - 4) * (sample.SampledAt - start).TotalSeconds / range.TotalSeconds;
                double y = plot.Height - 2 - (plot.Height - 4) * Math.Clamp((value - lower) / (upper - lower), 0, 1);
                var point = new Point(x, y);
                if (segment is null || previous is { } timestamp && sample.SampledAt - timestamp > LocalStore.DeviceHistoryInterval * 2)
                {
                    segment = new PathFigure { StartPoint = point };
                    segment.Segments.Add(new LineSegment { Point = new Point(x + 1, y) });
                    geometry.Figures.Add(segment);
                }
                else
                {
                    segment.Segments.Add(new LineSegment { Point = point });
                }
                previous = sample.SampledAt;
            }
            line.Trace.Data = geometry;
        }
    }

    private static double UpperBound(double maximum)
    {
        if (maximum <= 0) return 1;
        double magnitude = Math.Pow(10, Math.Floor(Math.Log10(maximum)));
        return magnitude * Math.Ceiling(maximum / magnitude);
    }
}
