using System.ComponentModel;
using Microsoft.AspNetCore.Builder;
using Microsoft.Data.Sqlite;
using Microsoft.Extensions.DependencyInjection;
using ModelContextProtocol.AspNetCore;
using ModelContextProtocol.Server;
using StarHub.Core;
using Xunit;

namespace StarHub.Tests;

public sealed class ProjectAndMcpTests
{
    [Fact]
    public void ProjectMigrationPreservesV1ChatsAndScopesDocuments()
    {
        string location = Path.Combine(Path.GetTempPath(), "StarHub-project-migrate-" + Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(location);
        try
        {
            using (var old = new SqliteConnection("Data Source=" + Path.Combine(location, "starhub.db")))
            {
                old.Open();
                using var sql = old.CreateCommand();
                sql.CommandText = """
                    CREATE TABLE conversations(id TEXT PRIMARY KEY, title TEXT NOT NULL, updated INTEGER NOT NULL);
                    CREATE TABLE turns (
                        id TEXT PRIMARY KEY, conversation_id TEXT NOT NULL REFERENCES conversations(id) ON DELETE CASCADE,
                        created INTEGER NOT NULL, prompt TEXT NOT NULL, reply TEXT NOT NULL DEFAULT '',
                        model TEXT NOT NULL, state INTEGER NOT NULL, detail TEXT NOT NULL DEFAULT '',
                        sources TEXT NOT NULL DEFAULT '[]', output_tokens INTEGER,
                        file_name TEXT, file_type TEXT, file_bytes BLOB
                    );
                    INSERT INTO conversations VALUES ('legacy','Existing conversation',100);
                    INSERT INTO turns (id, conversation_id, created, prompt, reply, model, state)
                        VALUES ('legacy-turn','legacy',100,'Old question','Old reply','legacy-model',1);
                    PRAGMA user_version = 1;
                    """;
                sql.ExecuteNonQuery();
            }
            var store = new LocalStore(location);
            Assert.Equal("Old reply", Assert.Single(store.ReadTurns("legacy")).Reply);
            Assert.Equal("legacy", Assert.Single(store.Conversations()).Id);
            var east = store.CreateProject("East");
            var west = store.CreateProject("West");
            Assert.Throws<StarHubException>(() => store.StartTurn("legacy", "Wrong project", "model", null, east.Id));
            var original = store.SaveProjectDocument(east.Id, "中文计划.txt", "海底灯塔的上线日期是十二月六日。");
            _ = store.SaveProjectDocument(west.Id, "中文计划.txt", "海底灯塔的上线日期是明年五月。");
            var reachable = Assert.Single(store.SearchProject(east.Id, "灯塔上线日期"));
            Assert.Contains("十二月六日", reachable.Text);
            Assert.Empty(store.Conversations(projectId: west.Id));
            _ = store.StartTurn("east-chat", "上线日期", "model", null, east.Id);
            Assert.Equal("east-chat", Assert.Single(store.Conversations(projectId: east.Id)).Id);
            Assert.Equal("legacy", Assert.Single(store.Conversations()).Id);

            var replacement = store.SaveProjectDocument(east.Id, "中文计划.txt", "航路识别码是白鲸。");
            Assert.Equal(original.Id, replacement.Id);
            Assert.Empty(store.SearchProject(east.Id, "十二月六日"));
            Assert.Single(store.SearchProject(east.Id, "航路识别码"));
            store.DeleteProjectDocument(east.Id, original.Id);
            Assert.Empty(store.SearchProject(east.Id, "航路识别码"));
            Assert.Contains("明年五月", Assert.Single(store.SearchProject(west.Id, "灯塔上线日期")).Text);
            Assert.Equal("legacy", Assert.Single(new LocalStore(location).Conversations()).Id);
        }
        finally
        {
            SqliteConnection.ClearAllPools();
            Directory.Delete(location, recursive: true);
        }
    }

    [Fact]
    public async Task ExtractsATextLayerFromAnImportedPdf()
    {
        var builder = new UglyToad.PdfPig.Writer.PdfDocumentBuilder();
        var page = builder.AddPage(UglyToad.PdfPig.Content.PageSize.A4);
        var font = builder.AddStandard14Font(UglyToad.PdfPig.Fonts.Standard14Fonts.Standard14Font.Helvetica);
        page.AddText("Lighthouse calibration document", 14, new UglyToad.PdfPig.Core.PdfPoint(40, 780), font);
        using var file = new MemoryStream(builder.Build());
        Assert.Contains("Lighthouse calibration document", await KnowledgeDocument.ReadAsync("lighthouse.pdf", file));
    }

    [Fact]
    public async Task CallsAnActualLoopbackMcpServer()
    {
        var builder = WebApplication.CreateSlimBuilder(new WebApplicationOptions { Args = ["--urls", "http://127.0.0.1:0"] });
        builder.Services.AddMcpServer().WithHttpTransport(options => options.SessionMode = HttpServerSessionMode.Stateless)
            .WithTools<LocalMcpFixture>();
        await using var server = builder.Build();
        server.MapMcp("/mcp");
        using var timeout = new CancellationTokenSource(TimeSpan.FromSeconds(25));
        await server.StartAsync(timeout.Token);
        var address = McpConnection.Endpoint(server.Urls.Single() + "/mcp");
        await using var client = await McpConnection.ConnectAsync(address, null, timeout.Token);
        var tool = Assert.Single(client.Tools);
        Assert.Equal("echo_input", tool.Name);
        var result = await client.CallAsync(tool.Name, """{"value":"starhub-tool"}""", timeout.Token);
        Assert.False(result.IsError);
        Assert.Contains("echo:starhub-tool", result.Text);
        await Assert.ThrowsAsync<StarHubException>(() => client.CallAsync("unknown", "{}", timeout.Token));
        Assert.Throws<StarHubException>(() => McpConnection.Endpoint("http://example.com/mcp"));
        Assert.Throws<StarHubException>(() => McpConnection.ReadArguments("[1,2]"));
    }

    [Fact]
    public void ComputerProposalRejectsActionsOutsideTextEditing()
    {
        var edit = new ComputerProposal("call", [new ComputerAction("type", Text: "Plain text")], "", false);
        Assert.True(edit.CanEdit);
        Assert.False((edit with { RequiresReview = true }).CanEdit);
        Assert.False((edit with { Actions = [new ComputerAction("keypress"), .. edit.Actions] }).CanEdit);
        Assert.False((edit with { Actions = [new ComputerAction("click", Button: "right"), .. edit.Actions] }).CanEdit);
        Assert.False((edit with { Actions = [new ComputerAction("type", Text: "escape\u001b")] }).CanEdit);
        Assert.True((edit with { Actions = [new ComputerAction("screenshot")] }).ScreenshotOnly);
    }

    [McpServerToolType]
    public sealed class LocalMcpFixture
    {
        [McpServerTool(Name = "echo_input"), Description("Echo a test value.")]
        public static string Echo(string value) => "echo:" + value;
    }
}
