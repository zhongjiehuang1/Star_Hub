using System.Globalization;
using System.IO.Compression;
using System.Text;
using DocumentFormat.OpenXml;
using DocumentFormat.OpenXml.Packaging;
using DocumentFormat.OpenXml.Spreadsheet;
using Microsoft.VisualBasic.FileIO;

namespace StarHub.Core;

internal sealed record InputRow(int Number, string[] Values);
internal sealed record InputTable(string Location, IReadOnlyList<InputRow> Rows);
internal sealed record SheetFormula(string Expression, decimal Value);

internal static class OpenXmlTables
{
    private const int RowLimit = 12000;
    private const int ColumnLimit = 160;
    private const long CompressedLimit = 12 * 1024 * 1024;

    public static IEnumerable<InputTable> Read(string file, CancellationToken token, bool sourceOnly = false)
    {
        var info = new FileInfo(file);
        if (info.Length > CompressedLimit) throw new StarHubException("A source table exceeds 12 MiB.");
        if (info.Extension.Equals(".csv", StringComparison.OrdinalIgnoreCase))
        {
            using var reader = new TextFieldParser(file, new UTF8Encoding(false, true), detectEncoding: true);
            reader.SetDelimiters(",");
            reader.HasFieldsEnclosedInQuotes = true;
            var rows = new List<InputRow>();
            while (!reader.EndOfData)
            {
                token.ThrowIfCancellationRequested();
                int line = (int)reader.LineNumber;
                string[] values = reader.ReadFields() ?? [];
                if (values.Length > ColumnLimit || rows.Count >= RowLimit)
                    throw new StarHubException("A source table exceeds 12,000 rows or 160 columns.");
                rows.Add(new(line, values));
            }
            yield return new(info.Name, rows);
            yield break;
        }

        using var stream = File.OpenRead(file);
        using (var archive = new ZipArchive(stream, ZipArchiveMode.Read, leaveOpen: true))
        {
            if (archive.Entries.Count > 300 || archive.Entries.Sum(entry => entry.Length) > 60 * 1024 * 1024)
                throw new StarHubException("The spreadsheet expands beyond the size supported for a local task.");
        }
        stream.Position = 0;
        using var document = SpreadsheetDocument.Open(stream, false);
        if (sourceOnly && document.PackageProperties.Subject == CostWorkbook.Marker) yield break;
        var workbook = document.WorkbookPart ?? throw new InvalidDataException("The spreadsheet has no workbook.");
        var strings = workbook.SharedStringTablePart?.SharedStringTable?.Elements<SharedStringItem>()
            .Select(item => item.InnerText).ToArray() ?? [];
        var sheets = workbook.Workbook?.Sheets ?? throw new InvalidDataException("The spreadsheet has no sheets.");
        foreach (var sheet in sheets.Elements<Sheet>())
        {
            if (sheet.State?.Value == SheetStateValues.Hidden || sheet.State?.Value == SheetStateValues.VeryHidden) continue;
            if (workbook.GetPartById(sheet.Id!.Value!) is not WorksheetPart part) continue;
            var rows = new List<InputRow>();
            var data = part.Worksheet?.GetFirstChild<SheetData>() ?? throw new InvalidDataException("A worksheet has no table.");
            foreach (var row in data.Elements<Row>())
            {
                token.ThrowIfCancellationRequested();
                if (rows.Count >= RowLimit) throw new StarHubException("A source table exceeds 12,000 rows.");
                var values = new List<string>();
                foreach (var cell in row.Elements<Cell>())
                {
                    int column = cell.CellReference?.Value is { } reference ? ColumnIndex(reference) : values.Count;
                    if (column < 0 || column >= ColumnLimit) throw new StarHubException("A source table exceeds 160 columns.");
                    while (values.Count <= column) values.Add("");
                    string value = cell.CellValue?.Text ?? cell.InlineString?.InnerText ?? "";
                    values[column] = cell.DataType?.Value == CellValues.SharedString &&
                        int.TryParse(value, NumberStyles.None, CultureInfo.InvariantCulture, out int index) &&
                        index < strings.Length ? strings[index] :
                        cell.DataType?.Value == CellValues.Error ? "" : value;
                }
                if (values.Any(value => !string.IsNullOrWhiteSpace(value)))
                    rows.Add(new((int)(row.RowIndex?.Value ?? (uint)(rows.Count + 1)), values.ToArray()));
            }
            yield return new(info.Name + " / " + sheet.Name!.Value, rows);
        }
    }

    private static int ColumnIndex(string reference)
    {
        int column = 0;
        foreach (char character in reference)
        {
            if (character is not (>= 'A' and <= 'Z')) break;
            column = column * 26 + character - 'A' + 1;
            if (column > ColumnLimit) break;
        }
        return column - 1;
    }

    public static WorkbookPart Start(SpreadsheetDocument document)
    {
        var workbook = document.AddWorkbookPart();
        workbook.Workbook = new Workbook(new Sheets());
        return workbook;
    }

    public static void Write(WorkbookPart workbook, string name, IEnumerable<object?[]> rows)
    {
        var book = workbook.Workbook ?? throw new InvalidDataException("The spreadsheet has no workbook.");
        var sheets = book.Sheets!;
        if (sheets.Elements<Sheet>().SingleOrDefault(sheet => sheet.Name?.Value == name) is { } previous)
        {
            workbook.DeletePart(previous.Id!.Value!);
            previous.Remove();
        }
        var worksheet = workbook.AddNewPart<WorksheetPart>();
        var data = new SheetData();
        uint rowIndex = 0;
        var widths = new List<double>();
        foreach (var cells in rows)
        {
            var row = new Row { RowIndex = ++rowIndex };
            for (int column = 0; column < cells.Length; column++)
            {
                while (widths.Count <= column) widths.Add(10);
                if (cells[column] is not { } value) continue;
                var cell = new Cell { CellReference = ColumnName(column) + rowIndex.ToString(CultureInfo.InvariantCulture) };
                if (value is string text)
                {
                    cell.DataType = CellValues.InlineString;
                    cell.InlineString = new InlineString(new Text(text) { Space = SpaceProcessingModeValues.Preserve });
                    int characters = text.Length + text.Count(character => character > 255);
                    widths[column] = Math.Max(widths[column], Math.Min(52, characters + 3));
                }
                else
                {
                    var number = value is SheetFormula formula ? formula.Value : Convert.ToDecimal(value, CultureInfo.InvariantCulture);
                    if (value is SheetFormula calculated) cell.CellFormula = new CellFormula(calculated.Expression);
                    cell.CellValue = new CellValue(number.ToString(CultureInfo.InvariantCulture));
                }
                row.Append(cell);
            }
            data.Append(row);
        }
        var columns = new Columns(widths.Select((width, column) => new Column
        {
            Min = (uint)column + 1, Max = (uint)column + 1, Width = width, CustomWidth = true
        }));
        worksheet.Worksheet = widths.Count == 0 ? new Worksheet(data) : new Worksheet(columns, data);
        sheets.Append(new Sheet
        {
            Id = workbook.GetIdOfPart(worksheet), Name = name,
            SheetId = sheets.Elements<Sheet>().Select(sheet => sheet.SheetId!.Value).DefaultIfEmpty().Max() + 1
        });
        worksheet.Worksheet.Save();
        book.Save();
    }

    private static string ColumnName(int index) => index < 26 ? ((char)('A' + index)).ToString()
        : ColumnName(index / 26 - 1) + (char)('A' + index % 26);
}
