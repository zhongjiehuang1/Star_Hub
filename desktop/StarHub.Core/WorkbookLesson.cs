using System.Globalization;
using System.Security.Cryptography;
using System.Text;
using System.Text.RegularExpressions;

namespace StarHub.Core;

public sealed record LessonCell(string Address, string Label, string Value, bool Focus = false, string[]? SourceRows = null);
public sealed record LessonStep(string Title, string Guide, string Question, string Hint, LessonCell[] Cells, decimal Answer);
public sealed record WorkbookLesson(string Revision, IReadOnlyList<LessonStep> Steps, LessonStep Review)
{
    public static bool ResumeRequested(string prompt) => Regex.IsMatch(prompt.Trim(),
        @"^(?:请|帮我)?(?:继续(?:昨天的|上次的|我的)?(?:学(?:习)?|课程|练习)|接着学|resume (?:my |the )?lesson|continue learning)[。.!！?？\s]*$",
        RegexOptions.IgnoreCase | RegexOptions.CultureInvariant);

    public static bool StartRequested(string prompt) => Regex.IsMatch(prompt.Trim(),
        @"^(?:请|我想|帮我)?(?:教我|学习?|练习)(?:一下)?(?:制造业|工厂)?成本核算(?:实务)?[。.!！?？\s]*$|^(?:teach me|learn|practice) (?:manufacturing |factory )?cost(?:ing| accounting)[.!?\s]*$",
        RegexOptions.IgnoreCase | RegexOptions.CultureInvariant);

    public static WorkbookLesson Read(string path, bool chinese, DateOnly? studyDay = null)
    {
        string L(string zh, string en) => chinese ? zh : en;
        var sheets = OpenXmlTables.Read(path, default).ToDictionary(t => t.Location[(t.Location.LastIndexOf(" / ", StringComparison.Ordinal) + 3)..]);
        if (!sheets.TryGetValue("Cost detail", out var detail) || !sheets.ContainsKey("Materials") ||
            !sheets.ContainsKey("Shared costs") || !sheets.ContainsKey("Work logs") || !sheets.ContainsKey("Review"))
            throw new StarHubException(L("请使用 Star Hub 生成的成本表。", "Choose a Star Hub cost workbook."));
        // Include source values and row addresses so an edited workbook cannot reuse a stale answer key.
        var revision = Convert.ToHexString(SHA256.HashData(System.Text.Json.JsonSerializer.SerializeToUtf8Bytes(
            new[] { "Materials", "Work logs", "Shared costs", "Cost detail", "Review" }.Select(s => sheets[s].Rows))));
        var steps = new List<LessonStep>();
        static decimal? Num(InputRow r, int i) => decimal.TryParse(r.Values.ElementAtOrDefault(i), NumberStyles.Float,
            CultureInfo.InvariantCulture, out var n) ? n : null;
        static string F(decimal n) => n.ToString("0.##", CultureInfo.InvariantCulture);
        static decimal Money(decimal n) => Math.Round(n, 2, MidpointRounding.AwayFromZero);
        LessonCell Cell(string sheet, InputRow row, int col, string label, bool focus = false) =>
            new($"{sheet}!{(char)('A' + col)}{row.Number}", label, row.Values.ElementAtOrDefault(col) ?? "—", focus);
        var material = sheets["Materials"].Rows.Skip(1).FirstOrDefault(r => Num(r, 1) is not null && Num(r, 2) is not null && Num(r, 3) is not null);
        if (material is not null)
        {
            string product = material.Values[0];
            steps.Add(new(L("核对仓库领料", "Check the material issue"),
                L($"仓库把领退料记到产品 {product}。用数量乘单价，退料保留负号，再和领料金额核对。",
                  $"The warehouse assigned this issue to product {product}. Multiply quantity by price, keeping the sign on returns, and check the amount."),
                L("这笔领料金额是多少元？", "What is the amount of this issue in yuan?"),
                L("先看高亮的数量和单价，再相乘，保留两位小数。", "Multiply the highlighted quantity and price. Round to cents."),
                [Cell("Materials", material, 1, L("领料数量","Quantity"), true), Cell("Materials", material, 2, L("单价","Unit price"), true),
                 Cell("Materials", material, 4, L("仓库单据","Warehouse record"))], Money(Num(material, 1)!.Value * Num(material, 2)!.Value)));
        }
        var pool = sheets["Shared costs"].Rows.Skip(1).Sum(r => Num(r, 0) ?? 0);
        var hours = sheets["Work logs"].Rows.Skip(1).Sum(r => Num(r, 1) ?? 0);
        var allocation = detail.Rows.Skip(1).FirstOrDefault(r => Num(r, 5) is not null && Num(r, 2) > 0);
        if (allocation is not null && hours > 0)
        {
            string product = allocation.Values[0];
            int lastExpense = sheets["Shared costs"].Rows.Last().Number, lastHours = sheets["Work logs"].Rows.Last().Number;
            steps.Add(new(L("分摊车间费用", "Allocate shop costs"),
                L($"车间交工时，财务核对费用池。例如共同费用 600 元、总工时 100 小时，用了 20 小时的产品分到 120 元。现在换成表里的产品 {product}。",
                  $"Production supplies hours and finance checks the pool. For example, 20 of 100 hours gets 120 yuan of a 600 yuan pool. Now use product {product} from your workbook."),
                L($"产品 {product} 分到多少元？", $"How much goes to product {product}?"),
                L("费用池乘以本产品工时，再除以全部工时。", "Multiply the pool by the product hours, then divide by total hours."),
                [new($"Shared costs!A2:A{lastExpense}", L("共同费用合计","Expense pool"), F(pool), true),
                 new($"Work logs!B2:B{lastHours}", L("总机器工时","Total machine hours"), F(hours), true),
                 Cell("Cost detail", allocation, 2, L("产品工时","Product hours"), true)], Num(allocation, 5)!.Value));
        }
        var finished = detail.Rows.Skip(1).FirstOrDefault(r => Num(r, 7) is not null && Num(r, 3) > 0);
        if (finished is not null)
        {
            string product = finished.Values[0];
            steps.Add(new(L("核对完工单位成本", "Check finished unit cost"),
                L($"在这个无期初、期末在制品的练习中，将产品 {product} 的完整成本除以合格完工数量。现场有在制品时，先核对完工与在制品分配。",
                  $"For product {product}, this exercise assumes no opening or closing work in progress. In a factory with unfinished units, reconcile their share before calculating finished cost."),
                L("每件完工产品的成本是多少元？保留两位小数。", "What is the cost of one finished unit, rounded to cents?"),
                L("完整成本 ÷ 完工数量。小计可能缺人工或共同费用。", "Divide full cost by finished units. A subtotal may omit labor or overhead."),
                [Cell("Cost detail", finished, 7, L("完整成本","Full cost"), true), Cell("Cost detail", finished, 3, L("完工数量","Finished units"), true)],
                Money(Num(finished, 7)!.Value / Num(finished, 3)!.Value)));
        }
        var flags = sheets["Review"].Rows.Skip(1).ToArray();
        steps.Add(new(L("交表前查差错","Review before handoff"),
            L("打开 Review 核对缺单、冲突和未分配金额。仓库补领退料记录，车间补工时产量，财务核对工资和费用。每一行是一条待复核项。",
              "Check Review for missing records, conflicts and unallocated costs. Ask the warehouse for issues, production for hours and output, and finance for wages and expenses. Each row is one check."),
            L("这本工作簿还有多少条待复核项？", "How many issues still need review?"),
            L("从表头下面开始数记录。没有记录时填 0。", "Count the records below the heading. An empty review sheet means zero."),
            flags.Length == 0 ? [new("Review!A1:B1", L("来源与检查项","Source and check"), L("仅表头","Header only"), true)] :
            [Cell("Review", flags[0], 0, L("首条来源","First source"), true), Cell("Review", flags[0], 1, L("应核对","Check")),
             new($"Review!A2:A{flags[^1].Number}", L("待复核行","Review rows"), $"2 … {flags[^1].Number}", true)], flags.Length));
        // Transfer exercise uses explicitly hypothetical quantities, never writes them to the source workbook.
        decimal rate = material is null ? 8 : Math.Abs(Num(material, 2)!.Value);
        int increment = 1 + (studyDay?.DayNumber ?? 2) % 17;
        decimal quantity = (material is null ? 12 : Math.Abs(Num(material, 1)!.Value)) + increment;
        var review = new LessonStep(L("隔天复习 · 换一笔领料","Daily review · a new material issue"),
            L("这是新一批模拟单据。数量已经换过，请独立核算。", "This is a simulated new batch with a different quantity. Work it out independently."),
            L("这次领料要归集多少元？","What material amount should be charged this time?"),
            L("把新领料数量乘以单价。","Multiply the new issue quantity by the unit price."),
            [new("B2", L("模拟领料数量","Practice quantity"), F(quantity), true), new("C2", L("模拟单价","Practice price"), F(rate), true)], Money(quantity * rate));
        LessonCell WithSource(LessonCell cell)
        {
            int split = cell.Address.IndexOf('!');
            if (split < 0) return cell;
            var table = sheets[cell.Address[..split]];
            var bounds = Regex.Matches(cell.Address[(split + 1)..], @"[A-Z]+(\d+)");
            int first = int.Parse(bounds[0].Groups[1].Value, CultureInfo.InvariantCulture);
            int last = int.Parse(bounds[^1].Groups[1].Value, CultureInfo.InvariantCulture);
            var evidence = table.Rows.Where(r => r.Number >= first && r.Number <= last).Take(4).ToArray();
            string Row(InputRow r) => $"{r.Number}   " + string.Join("   ·   ", r.Values);
            var lines = new List<string> { Row(table.Rows[0]) };
            lines.AddRange(evidence.Where(r => r.Number != table.Rows[0].Number).Select(Row));
            if (evidence.Length == 4 && evidence[^1].Number < last) lines.Add("… " + last);
            return cell with { SourceRows = lines.ToArray() };
        }
        return new(revision, steps.Select(step => step with { Cells = step.Cells.Select(WithSource).ToArray() }).ToArray(), review);
    }

    public static bool UnclearAmount(string text) =>
        !Regex.IsMatch(text, @"[?？]|为什么|怎么|如何|多少|对吗|是不是|\b(?:why|how|what|should|is it)\b", RegexOptions.IgnoreCase) &&
        Regex.IsMatch(text.Trim(), @"^(?:答案|我算|应该是|共计|[零〇一二两三四五六七八九十百千万0-9]|(?:my |the )?answer\b).*?(?:[零〇一二两三四五六七八九十百千万0-9]|元|yuan)", RegexOptions.IgnoreCase) &&
        SpokenNumber(text) is null;

    public static decimal? SpokenNumber(string text)
    {
        text = text.Trim().Normalize(NormalizationForm.FormKC).TrimEnd('。', '.', '!', '！');
        var match = Regex.Match(text, @"^(?:(?:答案是|我算出来是|我算的是|应该是|我觉得是|是|一共|合计|约|my answer is|the answer is|it is|it's)\s*)?([-+]?\d+(?:,\d{3})*(?:\.\d+)?|负?[零〇一二两三四五六七八九十百千万点]+)\s*(?:元人民币|人民币|元|块钱|块|条|项|yuan)?$", RegexOptions.IgnoreCase);
        if (!match.Success) return null;
        string value = match.Groups[1].Value;
        if (decimal.TryParse(value, NumberStyles.Number, CultureInfo.InvariantCulture, out var number)) return number;
        bool negative = value.StartsWith('负');
        string[] parts = value.TrimStart('负').Replace('两','二').Replace('〇','零').Split('点');
        if (parts.Length > 2 || value.Length > 24) return null;
        const string digits = "零一二三四五六七八九";
        decimal total = 0, section = 0, digit = 0;
        foreach (var c in parts[0])
        {
            int d = digits.IndexOf(c);
            if (d >= 0) digit = digit * 10 + d;
            else if (c == '万') { total += (section + digit) * 10000; section = digit = 0; }
            else { section += (digit == 0 ? 1 : digit) * (c == '十' ? 10 : c == '百' ? 100 : 1000); digit = 0; }
        }
        total += section + digit;
        if (parts.Length == 2)
        {
            decimal place = 1;
            foreach (char c in parts[1])
            {
                int d = digits.IndexOf(c);
                if (d < 0) return null;
                total += d / (place *= 10);
            }
        }
        return negative ? -total : total;
    }
}
