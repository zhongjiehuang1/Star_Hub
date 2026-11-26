using System.Globalization;
using DocumentFormat.OpenXml;
using DocumentFormat.OpenXml.Packaging;
using DocumentFormat.OpenXml.Spreadsheet;

namespace StarHub.Core;

internal static class CostWorkbook
{
    private const string Detail = "Cost detail";
    private const string Summary = "Product summary";
    private const string Review = "Review";
    internal const string Marker = "Star Hub production cost";
    private const string Products = "product,productcode,item,sku,产品,产品名称,产品编号,产品编码,品名,物料编码";
    private const string Quantities = "quantity,qty,materialquantity,数量,领料数量,领用数量,耗用数量";
    private const string Prices = "unitprice,price,单价,材料单价";
    private const string Materials = "materialamount,materialcost,amount,领料金额,材料金额,材料成本,金额";
    private const string Hours = "hours,machinehours,workhours,工时,机器工时,机时,实际工时,生产工时";
    private const string Units = "units,output,outputquantity,completedunits,产量,完工数量,完工产量,合格产量";
    private const string Wages = "wages,labor,laborcost,labourcost,工资,人工费用,人工成本,直接人工";
    private const string Shared = "overhead,sharedoverhead,manufacturingoverhead,制造费用,共同费用,待分摊金额";
    private static readonly StringComparer ProductComparer = StringComparer.OrdinalIgnoreCase;

    private sealed record Material(string Product, decimal? Quantity, decimal? UnitPrice, decimal Amount, bool Calculated, string Source);
    private sealed record WorkLog(string Product, decimal Hours, decimal? Units, decimal? Wages, string Source);
    private sealed record Expense(decimal Amount, string Source);

    public static WorkReceipt Create(string input, string output, string id, CancellationToken token)
    {
        if (File.Exists(output)) return Verify(output, id, false, token);
        string[] files = File.Exists(input) ? [input] :
            Directory.EnumerateFiles(input, "*", new EnumerationOptions { AttributesToSkip = FileAttributes.ReparsePoint })
            .Where(file => !Path.GetFileName(file).StartsWith("~$", StringComparison.Ordinal) &&
                Path.GetExtension(file).ToLowerInvariant() is ".xlsx" or ".csv")
            .Where(file => !string.Equals(file, output, StringComparison.OrdinalIgnoreCase))
            .Take(41).Order(StringComparer.OrdinalIgnoreCase).ToArray();
        if (files.Length > 40) throw new StarHubException("Choose a folder with up to 40 source tables for one production period.");
        var material = new List<Material>();
        var work = new List<WorkLog>();
        var expenses = new List<Expense>();
        var flags = new List<object?[]>();

        foreach (string file in files)
        {
            bool recognized = false;
            foreach (var table in OpenXmlTables.Read(file, token, sourceOnly: true))
            {
                if (table.Rows.Count == 0) continue;
                var headers = table.Rows[0].Values.Select(Key).ToArray();
                int product = Column(headers, Products), quantity = Column(headers, Quantities), price = Column(headers, Prices);
                int amount = Column(headers, Materials), hours = Column(headers, Hours), units = Column(headers, Units);
                int labor = Column(headers, Wages), shared = Column(headers, Shared);
                bool haveMaterial = product >= 0 && (quantity >= 0 && price >= 0 || amount >= 0 && hours < 0 && shared < 0);
                bool haveWork = product >= 0 && hours >= 0;
                bool haveOverhead = product < 0 && shared >= 0;
                if (!haveMaterial && !haveWork && !haveOverhead) continue;
                recognized = true;
                foreach (var row in table.Rows.Skip(1))
                {
                    token.ThrowIfCancellationRequested();
                    if (material.Count + work.Count + expenses.Count + flags.Count >= 11000)
                        throw new StarHubException("Choose a smaller batch of up to 11,000 source records.");
                    if (row.Values.All(string.IsNullOrWhiteSpace)) continue;
                    string source = table.Location + "#" + row.Number.ToString(CultureInfo.InvariantCulture);
                    string item = Read(row, product);
                    if ((haveMaterial || haveWork) && string.IsNullOrWhiteSpace(item))
                    {
                        flags.Add([source, "Missing product code. Check the originating document."]);
                        continue;
                    }
                    if (haveMaterial)
                    {
                        decimal? qty = Number(row, quantity), unitPrice = Number(row, price), recorded = Number(row, amount);
                        decimal? computed = qty is not null && unitPrice is >= 0 ? Money(qty.Value * unitPrice.Value) : null;
                        if (recorded is not null) recorded = Money(recorded.Value);
                        if (recorded is null && computed is null || recorded is not null && computed is not null && recorded != computed ||
                            Read(row, amount).Length > 0 && recorded is null)
                            flags.Add([source, "Material amount is missing, invalid or differs from quantity × unit price."]);
                        else material.Add(new(item, qty, unitPrice, (recorded ?? computed)!.Value, recorded is null, source));
                    }
                    if (haveWork)
                    {
                        decimal? time = Number(row, hours), produced = Number(row, units), wages = Number(row, labor);
                        if (time is null or < 0) flags.Add([source, "Missing or invalid machine hours. Ask the production team to check the log."]);
                        else
                        {
                            if (Read(row, units).Length != 0 && produced is null or < 0)
                                flags.Add([source, "Invalid completed units."]);
                            if (Read(row, labor).Length != 0 && wages is null or < 0)
                                flags.Add([source, "Invalid labor amount."]);
                            work.Add(new(item, time.Value, produced is >= 0 ? produced : null, wages is >= 0 ? Money(wages.Value) : null, source));
                        }
                    }
                    if (haveOverhead)
                    {
                        if (Number(row, shared) is not { } expense || expense < 0)
                            flags.Add([source, "Missing or invalid shared manufacturing expense."]);
                        else expenses.Add(new(Money(expense), source));
                    }
                }
            }
            if (!recognized) flags.Add([Path.GetFileName(file), "No recognized production data. Check the column headers."]);
        }
        if (material.Count + work.Count == 0)
            throw new StarHubException("No usable material or production rows. Check the selected folder and column headers.");
        var rows = CostRows(material, work, expenses, flags);

        TaskFiles.CommitFile(output, id, token, file =>
        {
            using var package = SpreadsheetDocument.Create(file, SpreadsheetDocumentType.Workbook);
            package.PackageProperties.Subject = Marker;
            package.PackageProperties.Identifier = id;
            var book = OpenXmlTables.Start(package);
            OpenXmlTables.Write(book, Detail, rows);
            OpenXmlTables.Write(book, "Materials", WithHeader(["Product", "Quantity", "Unit price", "Amount", "Source"],
                material.Select((line, index) => new object?[] { line.Product, line.Quantity, line.UnitPrice,
                    line.Calculated ? new SheetFormula($"ROUND(B{index + 2}*C{index + 2},2)", line.Amount) : line.Amount, line.Source })));
            OpenXmlTables.Write(book, "Work logs", WithHeader(["Product", "Machine hours", "Completed units", "Labor", "Source"],
                work.Select(line => new object?[] { line.Product, line.Hours, line.Units, line.Wages, line.Source })));
            OpenXmlTables.Write(book, "Shared costs", WithHeader(["Amount", "Source"],
                expenses.Select(expense => new object?[] { expense.Amount, expense.Source })));
            OpenXmlTables.Write(book, Review, WithHeader(["Source or product", "Check with the responsible team"], flags));
        }, staged => Verify(staged, id, false, token));
        return Verify(output, id, false, token);
    }

    public static WorkReceipt Summarize(string file, string id, CancellationToken token)
    {
        using (var existing = SpreadsheetDocument.Open(file, false))
        {
            CheckMarker(existing);
            if (existing.PackageProperties.Identifier == id) return Verify(file, id, true, token);
        }
        string priorHash = TaskFiles.Hash(file, token);
        var source = OpenXmlTables.Read(file, token).Single(table => table.Location.EndsWith(" / " + Detail, StringComparison.Ordinal));
        var summary = source.Rows.Skip(1).Where(row => Read(row, 0).Length != 0).Select(row =>
        {
            string reference = $"'{Detail}'!";
            int number = row.Number;
            decimal? units = Number(row, 3, decimal.MaxValue), full = Number(row, 7, decimal.MaxValue);
            return new object?[] { Read(row, 0),
                units is not null ? new SheetFormula($"{reference}D{number}", units.Value) : null,
                new SheetFormula($"{reference}G{number}", Number(row, 6, decimal.MaxValue) ?? throw new InvalidDataException("Missing cost subtotal.")),
                full is not null ? new SheetFormula($"{reference}H{number}", full.Value) : null,
                full is not null && units > 0 ? new SheetFormula($"ROUND({reference}H{number}/{reference}D{number},2)",
                    Money(full.Value / units.Value)) : null };
        }).ToArray();
        TaskFiles.CommitFile(file, id, token, staged =>
        {
            File.Copy(file, staged);
            using var package = SpreadsheetDocument.Open(staged, true);
            package.PackageProperties.Identifier = id;
            OpenXmlTables.Write(package.WorkbookPart!, Summary, WithHeader(
                ["Product", "Completed units", "Known cost subtotal", "Full cost", "Unit cost"], summary));
        }, staged => Verify(staged, id, true, token), priorHash);
        return Verify(file, id, true, token);
    }

    private static object?[][] CostRows(List<Material> materials, List<WorkLog> work, List<Expense> expenses,
        List<object?[]> flags)
    {
        var byMaterial = materials.ToLookup(row => row.Product, ProductComparer);
        var byWork = work.ToLookup(row => row.Product, ProductComparer);
        var products = materials.Select(row => row.Product).Concat(work.Select(row => row.Product))
            .Distinct(ProductComparer).Order(ProductComparer).ToArray();
        decimal totalHours = work.Sum(row => row.Hours);
        decimal pool = expenses.Sum(row => row.Amount);
        decimal allocated = 0, cumulativeHours = 0;
        var issues = flags.Select(flag => (string)flag[1]!).ToArray();
        bool unidentified = issues.Any(issue => issue.StartsWith("Missing product code", StringComparison.Ordinal));
        bool completeMaterial = !unidentified && issues.All(issue => !issue.StartsWith("Material amount", StringComparison.Ordinal));
        bool validPool = expenses.Count > 0 && totalHours > 0 && !unidentified && issues.All(issue =>
            !issue.Contains("machine hours", StringComparison.Ordinal) &&
            !issue.StartsWith("Missing or invalid shared", StringComparison.Ordinal));
        var rows = new List<object?[]>();
        foreach (string product in products)
        {
            int line = rows.Count + 2;
            var logs = byWork[product].ToArray();
            decimal? amount = byMaterial.Contains(product) ? byMaterial[product].Sum(row => row.Amount) : null;
            decimal? hours = logs.Length > 0 ? logs.Sum(log => log.Hours) : null;
            decimal? units = logs.Length > 0 && logs.All(log => log.Units.HasValue) ? logs.Sum(log => log.Units) : null;
            decimal? wages = logs.Length > 0 && logs.All(log => log.Wages.HasValue) ? logs.Sum(log => log.Wages) : null;
            cumulativeHours += hours ?? 0;
            decimal? portion = validPool && hours is not null ? Money(pool * cumulativeHours / totalHours) - allocated : null;
            string overhead = $"ROUND(SUM('Shared costs'!A2:A{expenses.Count + 1})*SUM(C2:C{line})/SUM('Work logs'!B2:B{work.Count + 1}),2)";
            if (line > 2) overhead += $"-SUM(F2:F{line - 1})";
            allocated += portion ?? 0;
            decimal subtotal = (amount ?? 0) + (wages ?? 0) + (portion ?? 0);
            bool complete = amount is not null && wages is not null && portion is not null && completeMaterial;
            if (amount is null) flags.Add([product, "Material amount is missing. Ask the warehouse for the issue and return records."]);
            if (units is null) flags.Add([product, "Completed quantity is missing. Ask production to confirm final counts."]);
            if (wages is null) flags.Add([product, "Labor cost is missing. Ask payroll or production to confirm the labor allocation."]);
            if (portion is null) flags.Add([product, "Shared cost could not be allocated. Check the expense pool and machine hours."]);
            rows.Add([product,
                Sum("Materials", "D", materials.Count, line, amount),
                Sum("Work logs", "B", work.Count, line, hours), Sum("Work logs", "C", work.Count, line, units),
                Sum("Work logs", "D", work.Count, line, wages),
                portion is not null ? new SheetFormula(overhead, portion.Value) : null,
                new SheetFormula($"SUM(B{line},E{line}:F{line})", subtotal),
                complete ? new SheetFormula($"G{line}", subtotal) : null]);
        }
        return WithHeader(["Product", "Materials", "Machine hours", "Completed units", "Labor",
            "Shared manufacturing cost", "Known cost subtotal", "Full cost"], rows).ToArray();
    }

    private static SheetFormula? Sum(string sheet, string column, int count, int line, decimal? value)
    {
        string criterion = $"\"=\"&SUBSTITUTE(SUBSTITUTE(SUBSTITUTE(A{line},\"~\",\"~~\"),\"*\",\"~*\"),\"?\",\"~?\")";
        return value is null ? null : new($"SUMIF('{sheet}'!A2:A{count + 1},{criterion},'{sheet}'!{column}2:{column}{count + 1})", value.Value);
    }

    private static WorkReceipt Verify(string file, string id, bool summary, CancellationToken token)
    {
        using (var package = SpreadsheetDocument.Open(file, false))
        {
            CheckMarker(package);
            if (package.PackageProperties.Identifier != id)
                throw new StarHubException("A different workbook already uses this destination. Choose another location.");
            var book = package.WorkbookPart ?? throw new InvalidDataException("The cost workbook is missing.");
            var sheets = book.Workbook?.Sheets?.Elements<Sheet>().ToArray() ?? [];
            var detail = sheets.Single(sheet => sheet.Name?.Value == Detail);
            var table = (WorksheetPart)book.GetPartById(detail.Id!.Value!);
            var body = table.Worksheet?.Descendants<Row>().Skip(1).ToArray() ?? [];
            token.ThrowIfCancellationRequested();
            if (body.Length == 0 || body.Any(row => row.Elements<Cell>().SingleOrDefault(cell =>
                cell.CellReference?.Value == "G" + row.RowIndex?.Value) is not { CellFormula: not null, CellValue: not null }))
                throw new InvalidDataException("The cost workbook did not pass its formula check.");
            if (summary && !sheets.Any(sheet => sheet.Name?.Value == Summary))
                throw new InvalidDataException("The product summary is missing.");
            var review = sheets.Single(sheet => sheet.Name?.Value == Review);
            var checks = (WorksheetPart)book.GetPartById(review.Id!.Value!);
            return new(file, body.Length, Math.Max(0, (checks.Worksheet?.Descendants<Row>().Count() ?? 0) - 1));
        }
    }

    private static void CheckMarker(SpreadsheetDocument document)
    {
        if (document.PackageProperties.Subject != Marker)
            throw new StarHubException("Choose a production cost workbook created by Star Hub.");
    }

    private static IEnumerable<object?[]> WithHeader(object?[] header, IEnumerable<object?[]> values) =>
        new[] { header }.Concat(values);
    private static decimal Money(decimal value) => decimal.Round(value, 2, MidpointRounding.AwayFromZero);
    private static string Key(string value) => new(value.Trim().ToLowerInvariant().Where(char.IsLetterOrDigit).ToArray());
    private static int Column(string[] headers, string aliases) => Array.FindIndex(headers, aliases.Split(',').Contains);
    private static string Read(InputRow row, int column) => column >= 0 && column < row.Values.Length ? row.Values[column].Trim() : "";
    private static decimal? Number(InputRow row, int column, decimal maximum = 10_000_000_000m) =>
        decimal.TryParse(Read(row, column), NumberStyles.Number, CultureInfo.InvariantCulture, out var value) &&
        Math.Abs(value) <= maximum ? value : null;
}
