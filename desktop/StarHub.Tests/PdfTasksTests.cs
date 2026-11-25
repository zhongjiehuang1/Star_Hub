using StarHub.Core;
using UglyToad.PdfPig;
using Xunit;

namespace StarHub.Tests;

public sealed class PdfTasksTests
{
    [Fact]
    public void TopicReplyAndRetriedRequestsUseTheLocalPdfRoute()
    {
        var missingTopic = PdfRequests.Parse("制作一份PDF", []);
        Assert.NotNull(missingTopic);
        Assert.True(missingTopic.NeedsTopic);
        var history = new[] { Turn("制作一份PDF", "你想做什么？", PdfRequests.PromptModel) };
        var example = PdfRequests.Parse("演示会计对账随便举例", history);
        Assert.NotNull(example);
        Assert.Equal(PdfDestination.Desktop, example.Destination);
        Assert.Contains("49,700.00", example.Markdown);
        Assert.Null(PdfRequests.Parse("打开任务管理器", history));

        var previous = Turn("演示会计对账随便举例", "", PdfRequests.DocumentModel) with
            { GeneratedPdf = @"C:\Users\Test\Desktop\对账.pdf" };
        var retrieve = PdfRequests.Parse("我让你放在电脑上不行吗？", [.. history, previous]);
        Assert.Equal(previous.GeneratedPdf, retrieve?.PreviousFile);
        var moved = PdfRequests.Parse("把它存到下载文件夹", [.. history, previous]);
        Assert.Equal(PdfDestination.Downloads, moved?.Destination);
        var failed = previous with { State = TurnState.Failed, GeneratedPdf = null };
        var retry = PdfRequests.Parse("把它存到下载文件夹", [.. history, failed]);
        Assert.Equal(PdfDestination.Downloads, retry?.Destination);
        Assert.Contains("49,700.00", retry?.Markdown);
        Assert.NotNull(PdfRequests.Parse("随便生成一个PDF放在桌面上", [])?.Markdown);
    }

    [Fact]
    public void PreviousDraftCanBeExportedAndExplicitTopicsArePreserved()
    {
        const string draft = "# 供应商核对\n\n这是一份完整的供应商款项核对说明。第一步为取出发票和付款凭证，然后将两边信息逐笔配对。最后保留差异和追踪凭证，避免重复入账。";
        var export = PdfRequests.Parse("把刚才的回复保存成 PDF", [Turn("说明供应商账单", draft)]);
        Assert.Equal(draft, export?.Markdown);
        var newTask = PdfRequests.Parse("随便生成一个关于咖啡烘焙的PDF", []);
        Assert.NotNull(newTask);
        Assert.Null(newTask.Markdown);
        Assert.Contains("咖啡烘焙", newTask.Brief);
        Assert.Null(PdfRequests.Parse("什么是 PDF 文件？", []));
        Assert.Null(PdfRequests.Parse("如何生成 PDF？", []));
    }

    [Fact]
    public void KeepsAnExplicitPdfFilenameAndUsesTheOfflineExample()
    {
        var request = PdfRequests.Parse("做一份会计对账演示 PDF，放到 \"C:\\Test Folder\\对账.pdf\"", []);
        Assert.Equal(@"C:\Test Folder\对账.pdf", request?.OutputPath);
        Assert.Contains("49,700.00", request?.Markdown);

        var prior = Turn("做一份会计对账演示 PDF", "", PdfRequests.DocumentModel) with
            { GeneratedPdf = @"C:\Users\Test\Desktop\对账.pdf" };
        request = PdfRequests.Parse(@"把它存到 C:\Test Folder\新对账.pdf", [prior]);
        Assert.Equal(prior.GeneratedPdf, request?.PreviousFile);
        Assert.Equal(@"C:\Test Folder\新对账.pdf", request?.OutputPath);
    }

    [Fact]
    public void WritesSearchableChineseWithoutReplacingAnExistingFileAndStoresTheLink()
    {
        var folder = Directory.CreateTempSubdirectory("starhub-pdf-test-");
        try
        {
            var first = LocalPdf.Write(folder.FullName, PdfRequests.AccountingExample(true), true, CancellationToken.None);
            var second = LocalPdf.Write(folder.FullName, PdfRequests.AccountingExample(true), true, CancellationToken.None);
            Assert.NotEqual(first, second);
            Assert.True(File.Exists(first));
            using (var pdf = PdfDocument.Open(second))
            {
                Assert.Equal(1, pdf.NumberOfPages);
                Assert.Contains("余额调节表", pdf.GetPage(1).Text);
                Assert.Contains("49,700.00", pdf.GetPage(1).Text);
            }
            var store = new LocalStore(folder.FullName);
            var saved = store.StartTurn("conversation", "随便生成一个", PdfRequests.DocumentModel, null, localOnly: true);
            store.FinishTurn(saved with { State = TurnState.Completed, GeneratedPdf = first });
            Assert.Equal(first, Assert.Single(new LocalStore(folder.FullName).ReadTurns("conversation")).GeneratedPdf);
            var copies = folder.CreateSubdirectory("copies");
            Assert.True(File.ReadAllBytes(first).SequenceEqual(File.ReadAllBytes(LocalPdf.Copy(first, copies.FullName, CancellationToken.None))));
        }
        finally
        {
            Microsoft.Data.Sqlite.SqliteConnection.ClearAllPools();
            folder.Delete(recursive: true);
        }
    }

    private static Turn Turn(string prompt, string reply, string model = "test") =>
        new(Guid.NewGuid().ToString("N"), prompt, reply, model, TurnState.Completed, "", [], null, null,
            LocalOnly: model != "test");
}
