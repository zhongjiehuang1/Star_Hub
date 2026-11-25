using System.Globalization;
using System.Text.RegularExpressions;
using Markdig;
using Markdig.Extensions.Tables;
using Markdig.Syntax;
using Markdig.Syntax.Inlines;
using PdfSharp;
using PdfSharp.Drawing;
using PdfSharp.Fonts;
using PdfSharp.Pdf;
using PdfSharp.Pdf.IO;

namespace StarHub.Core;

public static class LocalPdf
{
    private const string FontFamily = "Noto Sans SC";
    private static readonly MarkdownPipeline MarkdownStyle = new MarkdownPipelineBuilder().UsePipeTables().Build();
    private static readonly Lazy<bool> FontReady = new(() =>
    {
        GlobalFontSettings.FontResolver = new DocumentFont();
        return true;
    });

    public static (string Folder, string? Name) OutputLocation(PcWorkspace workspace, string requested)
    {
        string path = workspace.LocalPath(requested, allowRoot: true);
        return !Directory.Exists(path) && Path.GetExtension(path).Equals(".pdf", StringComparison.OrdinalIgnoreCase)
            ? (workspace.Folder(Path.GetDirectoryName(path)!), Path.GetFileNameWithoutExtension(path))
            : (workspace.Folder(path), null);
    }

    public static string Write(string directory, string markdown, bool chinese, CancellationToken token, string? preferredName = null)
    {
        if (string.IsNullOrWhiteSpace(markdown) || markdown.Length > 80_000)
            throw new StarHubException("A PDF needs between 1 and 80,000 characters of content.");
        var parsed = Markdown.Parse(markdown, MarkdownStyle);
        var firstTitle = parsed.OfType<HeadingBlock>().FirstOrDefault(heading => heading.Level == 1);
        var title = firstTitle is null ? (chinese ? "Star Hub 文档" : "Star Hub document") : InlineText(firstTitle.Inline).Trim();
        if (string.IsNullOrWhiteSpace(title)) title = chinese ? "Star Hub 文档" : "Star Hub document";
        var target = Path.GetFullPath(directory);
        _ = FontReady.Value;

        string staged = Path.Combine(target, $".starhub-{Guid.NewGuid():N}.tmp");
        bool ownsTemporaryFile = false;
        try
        {
            token.ThrowIfCancellationRequested();
            using (var file = new FileStream(staged, FileMode.CreateNew, FileAccess.Write, FileShare.None))
            {
                ownsTemporaryFile = true;
                using var pdf = new PdfDocument();
                pdf.Info.Title = title;
                pdf.Info.Creator = "Star Hub";
                using (var layout = new Layout(pdf))
                {
                    layout.Paragraph(title, 25, emphasis: true, spaceAfter: 25);
                    foreach (var block in parsed)
                    {
                        token.ThrowIfCancellationRequested();
                        if (block != firstTitle) layout.Draw(block);
                    }
                }
                pdf.Save(file, closeStream: false);
                file.Flush(flushToDisk: true);
            }
            Verify(staged);
            return Publish(staged, target, preferredName ?? Name(title), token);
        }
        finally
        {
            if (ownsTemporaryFile && File.Exists(staged)) File.Delete(staged);
        }
    }

    public static string Copy(string source, string destination, CancellationToken token, string? preferredName = null)
    {
        Verify(source);
        var target = Path.GetFullPath(destination);
        var name = preferredName ?? Path.GetFileNameWithoutExtension(source);
        if (Path.GetFullPath(source).Equals(Path.Combine(target, name + ".pdf"), OperatingSystem.IsWindows()
                ? StringComparison.OrdinalIgnoreCase : StringComparison.Ordinal)) return source;

        string staged = Path.Combine(target, $".starhub-{Guid.NewGuid():N}.tmp");
        bool ownsTemporaryFile = false;
        try
        {
            using (var input = File.OpenRead(source))
            using (var output = new FileStream(staged, FileMode.CreateNew, FileAccess.Write, FileShare.None))
            {
                ownsTemporaryFile = true;
                input.CopyTo(output);
                output.Flush(flushToDisk: true);
            }
            Verify(staged);
            return Publish(staged, target, name, token);
        }
        finally
        {
            if (ownsTemporaryFile && File.Exists(staged)) File.Delete(staged);
        }
    }

    private static void Verify(string file)
    {
        using var pdf = PdfReader.Open(file, PdfDocumentOpenMode.Import);
        if (pdf.PageCount == 0) throw new StarHubException("The generated PDF could not be verified.");
    }

    private static string Publish(string staged, string directory, string name, CancellationToken token)
    {
        if (string.IsNullOrWhiteSpace(name) || name.Any(c => char.IsControl(c) || "<>:\"/\\|?*".Contains(c)))
            throw new StarHubException("Use a valid PDF filename.");
        for (int index = 1; index <= 999; index++)
        {
            token.ThrowIfCancellationRequested();
            string file = Path.Combine(directory, index == 1 ? name + ".pdf" : $"{name} ({index}).pdf");
            try
            {
                File.Move(staged, file, overwrite: false);
                return file;
            }
            catch (IOException) when (File.Exists(file) || Directory.Exists(file)) { }
        }
        throw new StarHubException("Choose another folder. There are too many files with the same name.");
    }

    private static string Name(string title)
    {
        const string invalid = "<>:\"/\\|?*";
        var valid = new string(title.Where(character => !char.IsControl(character) && !invalid.Contains(character)).ToArray()).Trim(' ', '.');
        var shortName = new StringInfo(valid);
        if (shortName.LengthInTextElements > 64) valid = shortName.SubstringByTextElements(0, 64).TrimEnd('.', ' ');
        return $"Star Hub - {(valid.Length == 0 ? "Document" : valid)}";
    }

    private static string InlineText(ContainerInline? container)
    {
        if (container is null) return "";
        return string.Concat(container.Select(inline => inline switch
        {
            LiteralInline text => text.Content.ToString(),
            CodeInline code => code.Content,
            LineBreakInline line => line.IsHard ? "\n" : " ",
            HtmlEntityInline entity => entity.Transcoded.ToString(),
            AutolinkInline link => link.Url,
            ContainerInline nested => InlineText(nested),
            _ => ""
        }));
    }

    private sealed class DocumentFont : IFontResolver
    {
        public FontResolverInfo? ResolveTypeface(string familyName, bool bold, bool italic) =>
            familyName.Equals(FontFamily, StringComparison.OrdinalIgnoreCase) ? new FontResolverInfo(FontFamily) : null;

        public byte[]? GetFont(string faceName) => faceName == FontFamily
            ? File.ReadAllBytes(Path.Combine(AppContext.BaseDirectory, "Assets", "NotoSansSC-Regular.ttf"))
            : null;
    }

    private sealed class Layout : IDisposable
    {
        private const double Margin = 50;
        private const double Footer = 52;
        private static readonly XSolidBrush Ink = new(XColor.FromArgb(42, 39, 53));
        private static readonly XSolidBrush Accent = new(XColor.FromArgb(68, 58, 121));
        private static readonly XSolidBrush TableHeader = new(XColor.FromArgb(233, 230, 246));
        private static readonly XSolidBrush TableShade = new(XColor.FromArgb(247, 246, 251));
        private static readonly XPen Rule = new(XColor.FromArgb(216, 213, 229), 0.55);
        private static readonly Regex Words = new(@"[\u0021-\u007e\u00a1-\u024f]+|\s+|[\uD800-\uDBFF][\uDC00-\uDFFF]|.");
        private readonly PdfDocument document;
        private readonly Dictionary<double, XFont> fonts = new();
        private XGraphics canvas = null!;
        private double width, height, top;

        public Layout(PdfDocument document)
        {
            this.document = document;
            NewPage();
        }

        public void Draw(Block block, double inset = 0)
        {
            inset = Math.Min(inset, width - 114);
            switch (block)
            {
                case HeadingBlock heading:
                    Ensure(44);
                    Paragraph(InlineText(heading.Inline), heading.Level <= 2 ? 15 : 12, true, 10, inset);
                    break;
                case ParagraphBlock para:
                    Paragraph(InlineText(para.Inline), inset: inset);
                    break;
                case CodeBlock code:
                    Paragraph(code.Lines.ToString(), 9.5, inset: inset + 9);
                    break;
                case ListBlock list:
                    int index = int.TryParse(list.OrderedStart, out int start) ? start : 1;
                    foreach (var item in list.OfType<ListItemBlock>())
                    {
                        string prefix = list.IsOrdered ? $"{index++}.  " : "•  ";
                        bool first = true;
                        foreach (var child in item)
                        {
                            if (first && child is ParagraphBlock paragraph)
                                Paragraph(prefix + InlineText(paragraph.Inline), inset: inset + 10, spaceAfter: 6);
                            else Draw(child, inset + 10);
                            first = false;
                        }
                    }
                    top += 5;
                    break;
                case Table table:
                    DrawTable(table, inset);
                    break;
                case QuoteBlock quote:
                    foreach (var child in quote) Draw(child, inset + 12);
                    break;
                case ThematicBreakBlock:
                    Ensure(17);
                    canvas.DrawLine(Rule, Margin + inset, top + 5, Margin + width, top + 5);
                    top += 17;
                    break;
                case HtmlBlock html:
                    Paragraph(html.Lines.ToString(), inset: inset);
                    break;
            }
        }

        public void Paragraph(string text, double size = 10.8, bool emphasis = false, double spaceAfter = 10, double inset = 0)
        {
            var font = Font(size);
            double lineHeight = size * 1.65;
            foreach (var line in Wrap(text, font, width - inset))
            {
                Ensure(lineHeight);
                canvas.DrawString(line, font, emphasis ? Accent : Ink, new XRect(Margin + inset, top, width - inset, lineHeight), XStringFormats.TopLeft);
                top += lineHeight;
            }
            top += spaceAfter;
        }

        private void DrawTable(Table table, double inset)
        {
            var rows = table.OfType<TableRow>().ToArray();
            int columns = rows.Select(row => row.Count).DefaultIfEmpty().Max();
            if (columns == 0) return;
            if (columns > 8)
            {
                foreach (var row in rows)
                    Paragraph(string.Join("  |  ", row.OfType<TableCell>().Select(CellText)), inset: inset);
                return;
            }

            double tableWidth = width - inset;
            double unitWidth = tableWidth / (columns >= 3 ? columns + 0.85 : columns);
            double firstWidth = tableWidth - unitWidth * (columns - 1);
            var font = Font(columns >= 6 ? 8.5 : 10);
            const double lineHeight = 17, padding = 7;

            string[][] CellLines(TableRow row) => Enumerable.Range(0, columns).Select(index =>
                Wrap(index < row.Count && row[index] is TableCell cell ? CellText(cell) : "",
                    font, (index == 0 ? firstWidth : unitWidth) - padding * 2).ToArray()).ToArray();
            var header = rows[0].IsHeader ? CellLines(rows[0]) : null;
            int headerLines = header?.Max(cell => cell.Length) ?? 0;

            // Oversize headers use the same page splitting as other rows.
            bool repeatHeader = headerLines is > 0 and <= 6 && rows.Length > 1;
            if (repeatHeader) Ensure(headerLines * lineHeight + padding * 2 + lineHeight + padding * 2);
            for (int rowIndex = 0; rowIndex < rows.Length; rowIndex++)
            {
                string[][] lines = rowIndex == 0 && header is not null ? header : CellLines(rows[rowIndex]);
                int remaining = lines.Max(cell => cell.Length);
                int offset = 0;
                while (remaining > 0)
                {
                    if (top + lineHeight + padding * 2 > height - Footer)
                    {
                        NewPage();
                        if (repeatHeader && rowIndex > 0) PaintRow(header!, 0, headerLines, true, false);
                    }
                    int count = Math.Min(remaining, (int)((height - Footer - top - padding * 2) / lineHeight));
                    PaintRow(lines, offset, count, rows[rowIndex].IsHeader, rowIndex % 2 == 0);
                    remaining -= count;
                    offset += count;
                    if (remaining > 0)
                    {
                        NewPage();
                        if (repeatHeader && rowIndex > 0) PaintRow(header!, 0, headerLines, true, false);
                    }
                }
            }
            top += 19;

            void PaintRow(string[][] lines, int offset, int count, bool isHeader, bool shade)
            {
                double rowHeight = count * lineHeight + padding * 2;
                double x = Margin + inset;
                if (isHeader || shade) canvas.DrawRectangle(isHeader ? TableHeader : TableShade, x, top, tableWidth, rowHeight);
                for (int column = 0; column < columns; column++)
                {
                    double cellWidth = column == 0 ? firstWidth : unitWidth;
                    bool alignRight = column < table.ColumnDefinitions.Count &&
                        table.ColumnDefinitions[column].Alignment == TableColumnAlign.Right;
                    for (int index = 0; index < count && offset + index < lines[column].Length; index++)
                    {
                        canvas.DrawString(lines[column][offset + index], font, isHeader ? Accent : Ink,
                            new XRect(x + padding, top + padding + index * lineHeight, cellWidth - padding * 2, lineHeight),
                            alignRight ? XStringFormats.TopRight : XStringFormats.TopLeft);
                    }
                    x += cellWidth;
                }
                top += rowHeight;
                canvas.DrawLine(Rule, Margin + inset, top, Margin + width, top);
            }
        }

        private static string CellText(TableCell cell) => string.Join(" ", cell.OfType<ParagraphBlock>().Select(block => InlineText(block.Inline)));

        private IEnumerable<string> Wrap(string text, XFont font, double limit)
        {
            foreach (var paragraph in text.Replace("\r", "").Split('\n'))
            {
                string line = "";
                foreach (Match match in Words.Matches(paragraph))
                {
                    string piece = match.Value;
                    if (line.Length == 0 && string.IsNullOrWhiteSpace(piece)) continue;
                    if (canvas.MeasureString(line + piece, font).Width <= limit)
                    {
                        line += piece;
                        continue;
                    }
                    if (line.Length > 0) yield return line.TrimEnd();
                    line = "";
                    if (string.IsNullOrWhiteSpace(piece)) continue;
                    var glyphs = StringInfo.GetTextElementEnumerator(piece);
                    while (glyphs.MoveNext())
                    {
                        string glyph = glyphs.GetTextElement();
                        if (line.Length > 0 && canvas.MeasureString(line + glyph, font).Width > limit)
                        {
                            yield return line;
                            line = "";
                        }
                        line += glyph;
                    }
                }
                yield return line.TrimEnd();
            }
        }

        private XFont Font(double size)
        {
            if (!fonts.TryGetValue(size, out var font))
                fonts[size] = font = new XFont(FontFamily, size, XFontStyleEx.Regular, new XPdfFontOptions(PdfFontEncoding.Unicode));
            return font;
        }

        private void Ensure(double room)
        {
            if (top + room > height - Footer) NewPage();
        }

        private void NewPage()
        {
            canvas?.Dispose();
            var page = document.AddPage();
            page.Size = PageSize.A4;
            canvas = XGraphics.FromPdfPage(page);
            width = page.Width.Point - Margin * 2;
            height = page.Height.Point;
            top = 53;
            canvas.DrawLine(Rule, Margin, height - 42, Margin + width, height - 42);
            canvas.DrawString(document.PageCount.ToString(CultureInfo.InvariantCulture), Font(8.5), Accent,
                new XRect(Margin, height - 38, width, 14), XStringFormats.TopRight);
        }

        public void Dispose() => canvas.Dispose();
    }
}
