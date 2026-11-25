using Markdig;
using Markdig.Extensions.Tables;
using Markdig.Syntax;
using Markdig.Syntax.Inlines;
using Microsoft.UI.Text;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Documents;
using Microsoft.UI.Xaml.Media;
using StarHub.Core;
using XamlInline = Microsoft.UI.Xaml.Documents.Inline;

namespace StarHub.Windows;

internal static class MarkdownView
{
    private static readonly MarkdownPipeline Pipeline = new MarkdownPipelineBuilder().UsePipeTables().UseAutoLinks().Build();
    private static readonly FontFamily Monospace = new("Cascadia Mono, Consolas");

    public static void AddBlocks(Panel panel, string markdown, Brush surface)
    {
        var blocks = Markdig.Markdown.Parse(markdown, Pipeline);
        foreach (var block in blocks) Render(panel, block, surface);
    }

    private static void Render(Panel panel, Markdig.Syntax.Block block, Brush surface)
    {
        switch (block)
        {
            case HeadingBlock heading:
                panel.Children.Add(Text(heading.Inline, heading.Level <= 2 ? 22 : 17, bold: true));
                break;
            case ParagraphBlock paragraph:
                panel.Children.Add(Text(paragraph.Inline));
                break;
            case CodeBlock code:
                panel.Children.Add(new TextBox
                {
                    Text = code.Lines.ToString(), IsReadOnly = true, FontFamily = Monospace, AcceptsReturn = true,
                    Background = surface, Padding = new Thickness(10), TextWrapping = TextWrapping.Wrap,
                    BorderThickness = new Thickness(0), MaxHeight = 400
                });
                break;
            case ListBlock list:
                int number = int.TryParse(list.OrderedStart, out var start) ? start : 1;
                foreach (var item in list.OfType<ListItemBlock>())
                {
                    var row = new Grid { ColumnSpacing = 8 };
                    row.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto, MinWidth = 20 });
                    row.ColumnDefinitions.Add(new ColumnDefinition());
                    row.Children.Add(new TextBlock { Text = list.IsOrdered ? $"{number++}." : "•" });
                    var content = new StackPanel { Spacing = 4 };
                    foreach (var child in item) Render(content, child, surface);
                    Grid.SetColumn(content, 1);
                    row.Children.Add(content);
                    panel.Children.Add(row);
                }
                break;
            case QuoteBlock quote:
                var quoted = new StackPanel { Spacing = 6 };
                foreach (var child in quote) Render(quoted, child, surface);
                panel.Children.Add(new Border { Child = quoted, Background = surface, Padding = new Thickness(12) });
                break;
            case Table table:
                panel.Children.Add(MakeTable(table, surface));
                break;
            case ThematicBreakBlock:
                panel.Children.Add(new Border { Background = surface, Height = 1 });
                break;
            case HtmlBlock html:
                panel.Children.Add(new TextBlock { Text = html.Lines.ToString(), TextWrapping = TextWrapping.Wrap });
                break;
        }
    }

    private static ScrollViewer MakeTable(Table table, Brush surface)
    {
        var grid = new Grid { ColumnSpacing = 1, RowSpacing = 1 };
        int count = Math.Min(20, table.OfType<TableRow>().Select(row => row.Count).DefaultIfEmpty(0).Max());
        for (int i = 0; i < count; i++)
            grid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(count < 4 ? 182 : 145) });
        foreach (var row in table.OfType<TableRow>())
        {
            int index = grid.RowDefinitions.Count;
            grid.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
            int column = 0;
            foreach (var cell in row.OfType<TableCell>().Take(count))
            {
                var content = new StackPanel();
                foreach (var paragraph in cell.OfType<ParagraphBlock>())
                    content.Children.Add(Text(paragraph.Inline, 14, row.IsHeader));
                var border = new Border { Background = surface, Child = content, Padding = new Thickness(8) };
                Grid.SetRow(border, index);
                Grid.SetColumn(border, column++);
                grid.Children.Add(border);
            }
        }
        return new ScrollViewer
        {
            Content = grid, HorizontalScrollBarVisibility = ScrollBarVisibility.Auto,
            HorizontalScrollMode = ScrollMode.Enabled, VerticalScrollMode = ScrollMode.Disabled
        };
    }

    private static TextBlock Text(ContainerInline? inline, double size = 15, bool bold = false)
    {
        var block = new TextBlock { IsTextSelectionEnabled = true, TextWrapping = TextWrapping.Wrap, FontSize = size };
        if (bold) block.FontWeight = FontWeights.SemiBold;
        if (inline is not null)
            foreach (var child in inline) Append(child, block.Inlines.Add);
        return block;
    }

    private static void Append(Markdig.Syntax.Inlines.Inline inline, Action<XamlInline> add)
    {
        switch (inline)
        {
            case LiteralInline literal:
                add(new Run { Text = literal.Content.ToString() });
                break;
            case CodeInline code:
                add(new Run { Text = code.Content, FontFamily = Monospace });
                break;
            case LineBreakInline line:
                add(line.IsHard ? new LineBreak() : new Run { Text = " " });
                break;
            case HtmlEntityInline entity:
                add(new Run { Text = entity.Transcoded.ToString() });
                break;
            case HtmlInline html:
                add(new Run { Text = html.Tag });
                break;
            case AutolinkInline link when !link.IsEmail && ProviderClient.TryWebUri(link.Url, out var uri):
                var auto = new Hyperlink { NavigateUri = uri };
                auto.Inlines.Add(new Run { Text = link.Url });
                add(auto);
                break;
            case LinkInline link when !link.IsImage && link.Url is not null && ProviderClient.TryWebUri(link.Url, out var uri):
                var anchor = new Hyperlink { NavigateUri = uri };
                foreach (var child in link) Append(child, anchor.Inlines.Add);
                add(anchor);
                break;
            case EmphasisInline em:
                Span span = em.DelimiterCount == 2 ? new Bold() : new Italic();
                foreach (var child in em) Append(child, span.Inlines.Add);
                add(span);
                break;
            case ContainerInline container:
                foreach (var child in container) Append(child, add);
                break;
            case AutolinkInline raw:
                add(new Run { Text = raw.Url });
                break;
        }
    }
}
