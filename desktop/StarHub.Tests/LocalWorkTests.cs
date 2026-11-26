using System.Globalization;
using DocumentFormat.OpenXml.Packaging;
using DocumentFormat.OpenXml.Spreadsheet;
using DocumentFormat.OpenXml.Validation;
using StarHub.Core;
using Xunit;

namespace StarHub.Tests;

public sealed class LocalWorkTests : IDisposable
{
    private readonly string root = Directory.CreateDirectory(Path.Combine(AppContext.BaseDirectory,
        "starhub-local-work-" + Guid.NewGuid().ToString("N"))).FullName;

    [Theory]
    [InlineData("把这批领料和工时做成 Excel，存到文档", WorkKind.CostWorkbook)]
    [InlineData("把刚才那份按产品汇总", WorkKind.ProductSummary)]
    [InlineData("把这批旅行照片按拍摄日期整理", WorkKind.PhotoArchive)]
    [InlineData("继续刚才的任务", WorkKind.Resume)]
    [InlineData("如何把领料和工时做成 Excel？", null)]
    [InlineData("帮我想想怎样分工，不要整理照片", null)]
    public void IdentifiesTypedAndSpokenGoals(string text, WorkKind? expected) =>
        Assert.Equal(expected, LocalWork.Identify(text));

    [Fact]
    public void CostWorkbookIsTraceableAndTheSameFileCanBeUpdatedAfterRestart()
    {
        string input = Directory.CreateDirectory(Path.Combine(root, "source")).FullName;
        File.WriteAllText(Path.Combine(input, "领料.csv"), "产品,领料数量,材料单价\nA,10,20\nB,5,40\n");
        File.WriteAllText(Path.Combine(input, "工时.csv"), "产品,机器工时,完工数量,人工成本\nA,120,100,100\nB,80,50,100\n");
        File.WriteAllText(Path.Combine(input, "制造费用.csv"), "制造费用\n1200\n");
        string data = Path.Combine(root, "data");
        var store = new LocalStore(data);
        var plan = LocalWork.Plan(WorkKind.CostWorkbook, input, root, root);
        var turn = store.StartTurn("costs", "make a workbook", "local", null, localOnly: true);
        var job = store.SaveWork(turn.Id, "costs", plan);
        using var interrupted = new CancellationTokenSource();
        interrupted.Cancel();
        Assert.Throws<OperationCanceledException>(() => LocalWork.Execute(job, interrupted.Token));
        store.MarkInterruptedChats();
        store = new LocalStore(data);
        var pending = Assert.IsType<WorkCheckpoint>(store.LatestWork(null, null, unfinished: true));
        Assert.Equal(job.OperationId, pending.OperationId);
        var saved = LocalWork.Execute(pending, default);
        Assert.Equal(saved, LocalWork.Execute(pending, default));
        store.CompleteWork(job.OperationId, saved);
        Assert.Null(store.LatestWork("costs", null, unfinished: true));
        Assert.Equal(saved, store.ReadTurns("costs").Single().LocalWork);
        Assert.Equal(0, saved.NeedsReview);
        Assert.Equal(720, ReadValue(saved.Path, "Cost detail", "F2"));
        Assert.Equal(480, ReadValue(saved.Path, "Cost detail", "F3"));
        Assert.Equal(1020, ReadValue(saved.Path, "Cost detail", "G2"));

        var prior = Assert.IsType<WorkCheckpoint>(new LocalStore(data).LatestWork(null, null, unfinished: false));
        var summary = LocalWork.Plan(WorkKind.ProductSummary, prior.Receipt!.Path, null, root);
        var update = new WorkCheckpoint(Guid.NewGuid().ToString("N"), "costs", summary, null);
        Assert.Equal(saved.Path, LocalWork.Execute(update, default).Path);
        Assert.Equal(10.20m, ReadValue(saved.Path, "Product summary", "E2"));
        Assert.Equal(15.60m, ReadValue(saved.Path, "Product summary", "E3"));
        Assert.NotNull(ReadValue(saved.Path, "Materials", "D2"));
        using var workbook = SpreadsheetDocument.Open(saved.Path, false);
        Assert.Empty(new OpenXmlValidator().Validate(workbook));
    }

    [Fact]
    public void ConflictingAmountsRemainReviewItems()
    {
        File.WriteAllText(Path.Combine(root, "materials.csv"), "产品,数量,单价,金额\nA,2,30,99\n");
        File.WriteAllText(Path.Combine(root, "hours.csv"), "产品,工时,产量\nA,10,20\n");
        var plan = LocalWork.Plan(WorkKind.CostWorkbook, root, root, root);
        var result = LocalWork.Execute(new(Guid.NewGuid().ToString("N"), "costs", plan, null), default);
        Assert.True(result.NeedsReview > 0);
        Assert.Null(ReadValue(result.Path, "Cost detail", "H2"));
        Assert.Null(ReadValue(result.Path, "Cost detail", "B2"));
    }

    private static decimal? ReadValue(string path, string sheetName, string reference)
    {
        using var workbook = SpreadsheetDocument.Open(path, false);
        var book = workbook.WorkbookPart!;
        var sheet = book.Workbook!.Sheets!.Elements<Sheet>().Single(item => item.Name?.Value == sheetName);
        var data = ((WorksheetPart)book.GetPartById(sheet.Id!.Value!)).Worksheet!;
        return decimal.TryParse(data.Descendants<Cell>().SingleOrDefault(cell => cell.CellReference?.Value == reference)?.CellValue?.Text,
            NumberStyles.Number, CultureInfo.InvariantCulture, out decimal value) ? value : null;
    }

    public void Dispose() => Directory.Delete(root, recursive: true);
}
