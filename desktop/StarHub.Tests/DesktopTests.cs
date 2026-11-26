using System.Net;
using System.Text;
using System.Text.Json;
using Microsoft.Data.Sqlite;
using StarHub.Core;
using Xunit;

namespace StarHub.Tests;

public sealed class DesktopTests : IDisposable
{
    private readonly string directory = Path.Combine(Path.GetTempPath(), "starhub-tests-" + Guid.NewGuid().ToString("N"));
    private LocalStore Store => new(directory);

    [Fact]
    public void DesktopProfileKeepsOlderLinksAndReadsTheWebDisplayName()
    {
        var options = new JsonSerializerOptions(JsonSerializerDefaults.Web);
        var old = JsonSerializer.Deserialize<DesktopAccountProfile>("""{"email":"user@example.test","model":"gpt-6.2-sol"}""", options)!;
        var named = JsonSerializer.Deserialize<DesktopAccountProfile>("""{"email":"user@example.test","model":"gpt-6.2-sol","displayName":" 星河 " }""", options)!;
        Assert.True(old.IsValid());
        Assert.Null(old.Name);
        Assert.True(named.IsValid());
        Assert.Equal("星河", named.Name);
        Assert.Equal("本机名字", named.WithDesktopName("  本机名字  ").Name);
        Assert.Equal("星河", named.WithDesktopName("").Name);
    }

    [Fact]
    public void LocalConversationTitleSurvivesAReplyAndDeletionRemovesItsTurns()
    {
        var store = Store;
        store.StartTurn("one", "Original", "test", new Attachment("local.txt", "text/plain", [1]));
        store.StartTurn("two", "Keep this", "test", null);
        store.RenameConversation("one", "  本机对话  ");
        store.StartTurn("one", "Follow-up", "test", null);
        Assert.Equal("本机对话", store.FindConversation("one")?.Title);
        Assert.Equal("one", Assert.Single(store.Conversations("本机")).Id);
        store.DeleteConversation("one");
        Assert.Null(store.FindConversation("one"));
        Assert.Empty(store.ReadTurns("one"));
        Assert.Single(store.ReadTurns("two"));
    }

    [Fact]
    public async Task DesktopAccountCallbackIgnoresTheWrongState()
    {
        var opened = new TaskCompletionSource<Uri>(TaskCreationOptions.RunContinuationsAsynchronously);
        using var timeout = new CancellationTokenSource(TimeSpan.FromSeconds(10));
        var connection = new DesktopAccountClient().ConnectAsync(uri => opened.TrySetResult(uri), timeout.Token);
        var website = await opened.Task.WaitAsync(timeout.Token);
        Assert.Equal("https", website.Scheme);
        Assert.Equal("starhub.zhongjiehuang.com", website.Host);
        var query = website.Query[1..].Split('&').Select(pair => pair.Split('=', 2)).ToDictionary(pair => pair[0], pair => pair[1]);
        var callback = $"http://127.0.0.1:{query["desktopPort"]}/starhub/connect";
        using var client = new HttpClient(new HttpClientHandler { UseProxy = false });
        using var wrong = await client.GetAsync($"{callback}?state=incorrect&error=cancelled", timeout.Token);
        Assert.Equal(HttpStatusCode.BadRequest, wrong.StatusCode);
        Assert.False(connection.IsCompleted);
        using var cancelled = await client.GetAsync($"{callback}?state={query["desktopState"]}&error=cancelled", timeout.Token);
        Assert.Equal(HttpStatusCode.OK, cancelled.StatusCode);
        var error = await Assert.ThrowsAsync<StarHubException>(() => connection);
        Assert.Contains("cancelled", error.Message);
    }

    [Fact]
    public void SharesOpenAiCatalogueAndChoosesProviders()
    {
        var settings = new Preferences { OllamaModel = "local-model" };
        Assert.Contains(ModelCatalog.OpenAiModels, model => model.Id == settings.OpenAiModel && model.Reasoning.Length > 0);
        Assert.Contains(ModelCatalog.OpenAiModels, model => model.Id == ModelCatalog.Terra && model.Reasoning.Contains("none"));
        Assert.Contains(ModelCatalog.OpenAiModels, model => model.Id == ModelCatalog.Luna && model.Reasoning.Contains("high"));
        var automatic = new ChatOptions(Provider.Automatic);
        Assert.Equal(Provider.Ollama, ChatEngine.SelectProvider(settings, automatic, "short"));
        Assert.Equal(Provider.OpenAI, ChatEngine.SelectProvider(settings, automatic with { Reasoning = "high" }, "short"));
        Assert.Equal(Provider.OpenAI, ChatEngine.SelectProvider(settings, automatic, new string('a', 1600)));
        Assert.Equal(Provider.OpenAI, ChatEngine.SelectProvider(settings, automatic,
            "read this", new Attachment("sample.pdf", "application/pdf", [0])));
        Assert.Equal(Provider.Compatible, ChatEngine.SelectProvider(settings, new ChatOptions(Provider.Compatible), "short"));
        var custom = new Preferences { CompatibleEndpoint = "https://example.net/v1", CompatibleModel = "custom" };
        Assert.Equal(Provider.Compatible, ChatEngine.SelectProvider(custom, automatic, "short", openAiConfigured: false));
        Assert.Equal(Provider.OpenAI, ChatEngine.SelectProvider(custom, automatic with { WebSearch = true }, "short", openAiConfigured: false));
    }

    [Fact]
    public void ValidatesTheEntireLocalWindowsPlanBeforeExecutingAnyStep()
    {
        Assert.Null(PcCommands.Parse("How do I open Notepad?"));
        Assert.Equal([PcOperation.TaskManager, PcOperation.DeviceSnapshot],
            PcCommands.Parse("打开任务管理器，然后查看系统信息。"));
        Assert.Equal([PcOperation.Notepad, PcOperation.Downloads], PcCommands.Parse("/PC open notepad then open downloads"));
        Assert.Equal([PcOperation.Calculator, PcOperation.Paint, PcOperation.FileExplorer],
            PcCommands.Parse("请帮我先打开计算器，再帮我启动画图，然后打开文件资源管理器。"));
        Assert.Equal([PcOperation.Calculator], PcCommands.Parse("能帮我打开计算器吗？"));
        Assert.Equal([PcOperation.Calculator], PcCommands.Parse("Could you launch the Calculator?"));
        Assert.Null(PcCommands.Parse("打开计算器要按哪个快捷键？"));
        Assert.Empty(PcCommands.Parse("帮我打开计算器，然后执行未知命令")!);
        Assert.Empty(PcCommands.Parse("Open Notepad then execute a script")!);
        Assert.Empty(PcCommands.Parse("/pc\nunknown operation")!);

        var store = Store;
        var turn = store.StartTurn("pc", "Show system information", "Star Hub · Windows", null, localOnly: true);
        store.FinishTurn(turn with { State = TurnState.Completed, Reply = "local system details" });
        Assert.Empty(store.RecentContext("pc", Provider.OpenAI, false));
    }

    [Theory]
    [InlineData("帮我在选中的文件夹创建一份采购清单，写两项，然后用记事本打开", true)]
    [InlineData("把文件整理到两个目录，列出最后都放到了哪里", true)]
    [InlineData("请清空本机的 DNS 解析缓存，完成后告诉我执行结果。", true)]
    [InlineData("用记事本如何打开文件", false)]
    [InlineData("打开文件夹要按哪个快捷键？", false)]
    [InlineData("请在 Windows 上对比记事本和其他文字编辑器的特点", false)]
    [InlineData("介绍一下什么是 Windows 文件夹", false)]
    [InlineData("Please create a folder in Downloads and save a list there.", true)]
    [InlineData("Open File Explorer and list the files in the selected folder.", true)]
    [InlineData("Show me how to open File Explorer.", false)]
    [InlineData("Write a tutorial about Windows file management.", false)]
    public void RecognizesTaskRequestsAndLeavesInstructionQuestionsInChat(string goal, bool expected) =>
        Assert.Equal(expected, PcCommands.LooksLikeGoal(goal));

    [Theory]
    [InlineData("在选中的文件夹写入清单.txt", true)]
    [InlineData("复制报告.pdf 到桌面", true)]
    [InlineData("List the files in Downloads", true)]
    [InlineData("Launch File Explorer", false)]
    [InlineData("打开文件资源管理器", false)]
    public void RequestsFolderScopeForFileTasks(string goal, bool expected) =>
        Assert.Equal(expected, PcCommands.NeedsTaskFolder(goal));

    [Fact]
    public async Task ExecutesTypedWindowsToolsAndReturnsTheirEvidenceToThePlanner()
    {
        int rounds = 0;
        using var client = new ProviderClient(new Stub((_, body) =>
        {
            using var request = JsonDocument.Parse(body);
            var root = request.RootElement;
            Assert.Equal(ModelCatalog.Luna, root.GetProperty("model").GetString());
            Assert.False(root.GetProperty("store").GetBoolean());
            var tools = root.GetProperty("tools").EnumerateArray().Select(tool => tool.GetProperty("name").GetString()!).ToArray();
            string result;
            switch (++rounds)
            {
                case 1:
                    Assert.Contains("report_plan", tools);
                    Assert.Equal("report_plan", root.GetProperty("tool_choice").GetProperty("name").GetString());
                    result = """{"type":"function_call","call_id":"plan","name":"report_plan","arguments":"{\"summary\":\"先创建文件，然后检查结果。\"}"}""";
                    break;
                case 2:
                    Assert.Contains("write_text", tools);
                    Assert.DoesNotContain("windows_maintenance", tools);
                    result = """{"type":"function_call","call_id":"write","name":"write_text","arguments":"{\"path\":\"计划.txt\",\"text\":\"准备会议\"}"}""";
                    break;
                default:
                    var output = root.GetProperty("input").EnumerateArray().Last();
                    Assert.Equal("write", output.GetProperty("call_id").GetString());
                    Assert.Contains("verified", output.GetProperty("output").GetString());
                    result = """{"type":"message","content":[{"type":"output_text","text":"已核对计划.txt。"}]}""";
                    break;
            }
            return Reply("application/json", $$"""{"status":"completed","output":[{{result}}]}""");
        }));
        var events = new List<PcTaskStep>();
        var agent = new PcTaskAgent(client);
        var run = await agent.RunAsync("local-test-key", "帮我写一个计划文件", ModelCatalog.Luna, true, false, (tool, args, _) =>
        {
            Assert.Equal("write_text", tool);
            Assert.Equal("计划.txt", PcTaskArguments.Text(args, "path"));
            return Task.FromResult(new PcTaskResult(true, "Saved and verified"));
        }, events.Add, default);
        Assert.Equal(3, rounds);
        Assert.True(run.Finished);
        Assert.Equal("已核对计划.txt。", run.Reply);
        Assert.Collection(events, step => Assert.Equal("report_plan", step.Tool), step => Assert.True(step.Succeeded));
    }

    [Fact]
    public async Task ConfinesWindowsFileOperationsAndPreservesExistingFiles()
    {
        // The standard macOS temp path may have a symlink ancestor.
        var path = OperatingSystem.IsMacOS() ? "/private" + directory : directory;
        if (OperatingSystem.IsMacOS() && !directory.StartsWith("/var/", StringComparison.Ordinal)) path = directory;
        Directory.CreateDirectory(path);
        var folder = Path.Combine(path, "scope");
        Directory.CreateDirectory(folder);
        var workspace = new PcWorkspace(folder);
        string outside = Path.Combine(path, "outside.txt");
        File.WriteAllText(outside, "outside");
        Assert.Throws<StarHubException>(() => workspace.ReadText("../outside.txt"));
        Assert.Throws<StarHubException>(() => workspace.ReadText("C:\\Windows\\hosts.txt"));
        Assert.Throws<StarHubException>(() => workspace.TextFile("NUL.txt", false));
        Assert.Throws<StarHubException>(() => workspace.TextFile("data.txt:stream", false));
        if (!OperatingSystem.IsWindows())
        {
            File.CreateSymbolicLink(Path.Combine(folder, "link.txt"), outside);
            Assert.Throws<StarHubException>(() => workspace.ReadText("link.txt"));
        }
        Assert.True(workspace.MakeFolder("整理").Succeeded);
        Assert.True((await workspace.WriteTextAsync("整理/初稿.txt", "会议", default)).Succeeded);
        await Assert.ThrowsAsync<StarHubException>(() => workspace.WriteTextAsync("整理/初稿.txt", "overwrite", default));
        Assert.True((await workspace.TransferAsync("整理/初稿.txt", "整理/计划.txt", true, default)).Succeeded);
        Assert.False(File.Exists(Path.Combine(folder, "整理", "初稿.txt")));
        Assert.Equal("会议", File.ReadAllText(Path.Combine(folder, "整理", "计划.txt")));
        Assert.Equal("outside", File.ReadAllText(outside));
    }

    [Fact]
    public async Task LocalComputerModeReadsAndMovesAcrossFolders()
    {
        var root = Directory.CreateDirectory(Path.Combine(directory, "desktop"));
        var archive = Directory.CreateDirectory(Path.Combine(directory, "archive"));
        var source = Path.Combine(directory, "账单.txt");
        File.WriteAllText(source, "余额 49,700.00");
        var workspace = new PcWorkspace(root.FullName, allowDrivePaths: true);
        var read = await workspace.ReadFileAsync(source, default);
        var contents = Assert.IsType<PcFileContent>(read.Evidence);
        Assert.Equal("余额 49,700.00", contents.Text);
        Assert.NotNull(contents.Sha256);
        Assert.True((await workspace.TransferAsync(source, archive.FullName, false, default)).Succeeded);
        Assert.True(File.Exists(source));
        Assert.True((await workspace.TransferAsync(source, Path.Combine(root.FullName, "账单.txt"), true, default)).Succeeded);
        Assert.False(File.Exists(source));
        Assert.Equal(contents.Text, File.ReadAllText(Path.Combine(archive.FullName, "账单.txt")));
        Assert.Equal(contents.Text, File.ReadAllText(Path.Combine(root.FullName, "账单.txt")));
        Assert.Throws<StarHubException>(() => workspace.LocalPath(@"\\?\C:\Windows"));
    }

    [Fact]
    public void RecognizesTerminalAndAbsolutePathsAndRespectsComputerMode()
    {
        Assert.Equal([PcOperation.Terminal], PcCommands.Parse("打开终端"));
        var read = Assert.IsType<PcDirectAction>(PcDirect.Parse("读取 \"C:\\Test Folder\\账单.txt\""));
        Assert.Equal("read_file", read.Tool);
        Assert.Equal(@"C:\Test Folder\账单.txt", read.Arguments.GetProperty("path").GetString());
        Assert.Equal("copy_file", PcDirect.Parse(@"把 C:\source.txt 复制到 D:\Archive")?.Tool);
        Assert.Null(PcDirect.Parse("打开记事本并写入一份清单"));
        Assert.True(PcCommands.MayReferToComputer("再把结果存到桌面", followsComputerTask: true));
        Assert.False(ChatEngine.UsesDeviceAnalysis("这台电脑现在怎么样", new ChatOptions(Provider.OpenAI,
            AllowLocalDeviceAnalysis: false), file: null, followsDevice: false));
    }

    [Fact]
    public void NamespacesCompatibleKeysByExactHttpsBase()
    {
        Assert.Throws<StarHubException>(() => ChatEngine.CompatibleEndpoint("http://example.net/v1"));
        Assert.Throws<StarHubException>(() => ChatEngine.CompatibleEndpoint("https://user:secret@example.net/v1"));
        Assert.Throws<StarHubException>(() => ChatEngine.CompatibleEndpoint("https://example.net/v1?token=x"));
        Assert.Equal(ChatEngine.CompatibleCredential(ChatEngine.CompatibleEndpoint("https://example.net/v1")),
                     ChatEngine.CompatibleCredential(ChatEngine.CompatibleEndpoint("https://example.net/v1/")));
        Assert.NotEqual(ChatEngine.CompatibleCredential(ChatEngine.CompatibleEndpoint("https://example.net/v1")),
                        ChatEngine.CompatibleCredential(ChatEngine.CompatibleEndpoint("https://example.net/v2")));
    }

    [Theory]
    [InlineData("电脑现在运行得怎么样", true)]
    [InlineData("这台电脑现在怎么样？", true)]
    [InlineData("你去任务管理器里面看一看。", true)]
    [InlineData("为什么变慢", true)]
    [InlineData("为什么这台电脑变慢了？", true)]
    [InlineData("How is my PC running?", true)]
    [InlineData("推荐一台高性能电脑", false)]
    [InlineData("What is CPU utilization?", false)]
    [InlineData("任务管理器是什么？", false)]
    public void RoutesDeviceQuestionsWithoutTakingOverGeneralChat(string prompt, bool local)
    {
        Assert.Equal(local, ChatEngine.UsesDeviceAnalysis(prompt, new ChatOptions(Provider.OpenAI), null, false));
    }

    [Fact]
    public void DeviceHistoryKeepsOneSamplePerBucketAndPrunesOldData()
    {
        var store = Store;
        var now = DateTimeOffset.FromUnixTimeSeconds(1_760_000_010);
        store.RecordDeviceReading(Reading(new DeviceSample(now.AddDays(-8), 25, 45)));
        store.RecordDeviceReading(Reading(new DeviceSample(now, 71, 63)));
        store.RecordDeviceReading(Reading(new DeviceSample(now.AddSeconds(2), 83, 82)));

        var reopened = Store;
        Assert.Equal(83, reopened.LatestDeviceReading()!.Sample.CpuPercent);
        Assert.Equal(71, Assert.Single(reopened.ReadDeviceHistory(now, now.AddSeconds(2))).CpuPercent);
        Assert.Empty(reopened.ReadDeviceHistory(now.AddSeconds(1), now.AddSeconds(2)));
        using var connection = new SqliteConnection($"Data Source={Path.Combine(directory, "starhub.db")}");
        connection.Open();
        using var history = connection.CreateCommand();
        history.CommandText = "SELECT sampled FROM device_history ORDER BY slot";
        using var rows = history.ExecuteReader();
        Assert.True(rows.Read());
        Assert.Equal(now.ToUnixTimeMilliseconds(), rows.GetInt64(0));
        Assert.False(rows.Read());
    }

    [Fact]
    public void DeviceSchemaUpgradesThePreviousPreviewWithoutChangingExistingChat()
    {
        var store = Store;
        var old = store.StartTurn("existing", "A previous chat", "Existing model", null);
        store.FinishTurn(old with { State = TurnState.Completed, Reply = "Preserved reply" });
        using (var connection = new SqliteConnection($"Data Source={Path.Combine(directory, "starhub.db")}"))
        {
            connection.Open();
            using var command = connection.CreateCommand();
            command.CommandText = """
                DROP TABLE device_alert_state; DROP TABLE device_alerts;
                DROP TABLE device_latest; DROP TABLE device_history;
                ALTER TABLE turns DROP COLUMN local_only;
                ALTER TABLE turns DROP COLUMN generated_pdf;
                PRAGMA user_version = 2;
                """;
            command.ExecuteNonQuery();
        }
        SqliteConnection.ClearAllPools();

        var reopened = Store;
        var previous = Assert.Single(reopened.ReadTurns("existing"));
        Assert.Equal("Preserved reply", previous.Reply);
        Assert.False(previous.LocalOnly);
        var sample = new DeviceSample(DateTimeOffset.UtcNow, 40, 50);
        reopened.RecordDeviceReading(Reading(sample));
        Assert.Equal(sample, reopened.LatestDeviceReading()?.Sample);
    }

    [Fact]
    public void AlertSchemaPreservesTheExistingDeviceHistoryAndPrivateTurns()
    {
        var store = Store;
        var sample = new DeviceSample(DateTimeOffset.UtcNow, 42, 61);
        store.RecordDeviceReading(Reading(sample));
        var previous = store.StartTurn("device-chat", "How is my PC running?", "Local", null, localOnly: true);
        store.FinishTurn(previous with { State = TurnState.Completed, Reply = "Previous device analysis" });
        using (var connection = new SqliteConnection($"Data Source={Path.Combine(directory, "starhub.db")}"))
        {
            connection.Open();
            using var command = connection.CreateCommand();
            command.CommandText = "DROP TABLE device_alert_state; DROP TABLE device_alerts; ALTER TABLE turns DROP COLUMN generated_pdf; PRAGMA user_version = 3;";
            command.ExecuteNonQuery();
        }
        SqliteConnection.ClearAllPools();

        var reopened = Store;
        Assert.Equal(sample, reopened.LatestDeviceReading()?.Sample);
        Assert.True(Assert.Single(reopened.ReadTurns("device-chat")).LocalOnly);
        Assert.Empty(reopened.RecentContext("device-chat", Provider.OpenAI, false));
        Assert.Null(reopened.LatestDeviceAlert());
    }

    [Fact]
    public void PerformanceAlertsIgnoreSpikesAndGapsInSampling()
    {
        var store = Store;
        var now = DateTimeOffset.FromUnixTimeSeconds(1_760_000_010);
        for (int i = 7; i > 0; i--) store.RecordDeviceReading(Reading(new DeviceSample(now.AddSeconds(-i * 15), 45, 60)));
        store.RecordDeviceReading(Reading(new DeviceSample(now, 99, 61)));
        Assert.Null(DeviceAlerts.Check(store, now, chinese: true));

        now = now.AddMinutes(10);
        foreach (int offset in new[] { 115, 100, 85, 25, 14, 0 })
            store.RecordDeviceReading(Reading(new DeviceSample(now.AddSeconds(-offset), 98, 61)));
        Assert.Null(DeviceAlerts.Check(store, now, chinese: true));
        Assert.Null(store.LatestDeviceAlert());
    }

    [Fact]
    public async Task AlertUsesBaselineAndAdviceWithoutRepeatingAnOngoingIncident()
    {
        var store = Store;
        var now = DateTimeOffset.FromUnixTimeSeconds(1_760_000_010);
        for (int i = 0; i < 16; i++)
            store.RecordDeviceReading(Reading(new DeviceSample(now.AddMinutes(-20).AddSeconds(i * 15), 32, 55)));
        RecordAlertWindow(store, now, cpu: 81, memory: 60);
        var alert = DeviceAlerts.Check(store, now, chinese: true);
        Assert.NotNull(alert);
        Assert.Contains("此前中位值 32%", alert.Report);
        Assert.Contains("preview-renderer", alert.Report);
        Assert.Contains("任务管理器", alert.Message);
        Assert.Equal(alert, Store.LatestDeviceAlert());

        // A repeat after a restart and the global cooldown still stays within the same incident.
        var reopened = Store;
        now = now.AddMinutes(35);
        RecordAlertWindow(reopened, now, cpu: 95, memory: 61);
        Assert.Null(DeviceAlerts.Check(reopened, now, chinese: true));
        now = now.AddMinutes(4);
        RecordAlertWindow(reopened, now, cpu: 44, memory: 61);
        Assert.Null(DeviceAlerts.Check(reopened, now, chinese: true));
        now = now.AddMinutes(4);
        RecordAlertWindow(reopened, now, cpu: 94, memory: 61);
        Assert.NotNull(DeviceAlerts.Check(reopened, now, chinese: true));

        using var client = new ProviderClient();
        var engine = new ChatEngine(reopened, new NoCredentials(), client);
        var reply = await engine.SendAsync("device-chat", "最近一次性能提醒是什么？", null, new(),
            new ChatOptions(Provider.OpenAI), _ => { }, _ => { }, default);
        Assert.True(reply.LocalOnly);
        Assert.Contains("怎么处理", reply.Reply);
        Assert.Contains("94%", reply.Reply);
    }

    [Fact]
    public void NewPressureWaitsForTheNotificationCooldown()
    {
        var store = Store;
        var now = DateTimeOffset.FromUnixTimeSeconds(1_760_000_010);
        RecordAlertWindow(store, now, cpu: 96, memory: 55);
        var first = DeviceAlerts.Check(store, now, chinese: false);
        Assert.NotNull(first);
        RecordAlertWindow(store, now.AddMinutes(5), cpu: 40, memory: 96);
        Assert.Null(DeviceAlerts.Check(store, now.AddMinutes(5), chinese: false));
        Assert.Equal(first, store.LatestDeviceAlert());
        RecordAlertWindow(store, now.AddMinutes(31), cpu: 41, memory: 96);
        var second = DeviceAlerts.Check(store, now.AddMinutes(31), chinese: false);
        Assert.NotNull(second);
        Assert.Contains("Memory use", second.Message);
    }

    private static void RecordAlertWindow(LocalStore store, DateTimeOffset now, double cpu, double memory)
    {
        var process = new DeviceProcessUsage(37, "preview-renderer", 76, 768_000_000);
        for (int i = 7; i >= 0; i--)
            store.RecordDeviceReading(Reading(new DeviceSample(now.AddSeconds(-i * 15), cpu, memory, TopProcesses: [process])));
    }

    [Fact]
    public async Task DeviceAnalysisUsesHistoryAndStaysOutOfLaterProviderRequests()
    {
        var store = Store;
        var now = DateTimeOffset.UtcNow;
        for (int i = 0; i < 10; i++)
            store.RecordDeviceReading(Reading(new DeviceSample(now.AddMinutes(-40).AddSeconds(i * 17), 30, 60)));
        for (int i = 0; i < 5; i++)
            store.RecordDeviceReading(Reading(new DeviceSample(now.AddSeconds(-82 + i * 20), 95, 92,
                AvailableMemoryBytes: 512_000_000, DiskBusyPercent: 88, DiskLatencyMs: 42,
                TopProcesses: [new DeviceProcessUsage(37, "device-test-renderer", 76, 768_000_000)])));

        int calls = 0;
        using var remote = new Stub((_, body) =>
        {
            calls++;
            Assert.DoesNotContain("device-test-renderer", body);
            Assert.DoesNotContain("GPU呢", body);
            using var request = JsonDocument.Parse(body);
            var messages = request.RootElement.GetProperty("input");
            Assert.Equal(1, messages.GetArrayLength());
            return Reply("text/event-stream", "data: {\"type\":\"response.output_text.delta\",\"delta\":\"Normal chat.\"}\n\n" +
                "data: {\"type\":\"response.completed\"}\n\n");
        });
        using var client = new ProviderClient(remoteHandler: remote);
        var local = new ChatEngine(store, new NoCredentials(), client);
        var options = new ChatOptions(Provider.OpenAI);
        var answer = await local.SendAsync("device-chat", "为什么变慢", null, new(), options, _ => { }, _ => { }, default);
        Assert.True(answer.LocalOnly);
        Assert.Equal(TurnState.Completed, answer.State);
        Assert.Contains("较早样本均值 30%", answer.Reply);
        Assert.Contains("可能的瓶颈", answer.Reply);
        Assert.Contains("怎么处理", answer.Reply);
        Assert.Contains("device-test-renderer", answer.Reply);
        Assert.Contains("42 ms", answer.Reply);
        Assert.Equal(0, calls);

        var followUp = await local.SendAsync("device-chat", "GPU呢？", null, new(), options, _ => { }, _ => { }, default);
        Assert.True(followUp.LocalOnly);
        Assert.Equal(0, calls);
        Assert.All(Store.ReadTurns("device-chat"), turn => Assert.True(turn.LocalOnly));
        Assert.Empty(Store.RecentContext("device-chat", Provider.OpenAI, true));

        var cloud = new ChatEngine(store, new FakeCredentials(), client);
        var ordinary = await cloud.SendAsync("device-chat", "Tell me a story.", null, new(), options, _ => { }, _ => { }, default);
        Assert.False(ordinary.LocalOnly);
        Assert.Equal("Normal chat.", ordinary.Reply);
        Assert.Equal(1, calls);
    }

    [Fact]
    public async Task StaleSamplesAreClearlyHistorical()
    {
        var store = Store;
        store.RecordDeviceReading(Reading(new DeviceSample(DateTimeOffset.UtcNow.AddMinutes(-2), 51, 61)));
        using var client = new ProviderClient();
        var engine = new ChatEngine(store, new NoCredentials(), client);
        var answer = await engine.SendAsync("device-chat", "How is my PC running over the past 5 minutes?", null, new(),
            new ChatOptions(Provider.OpenAI), _ => { }, _ => { }, default);
        Assert.Contains("live reading is stale", answer.Reply);
        Assert.Contains("1 samples in the last 5 min", answer.Reply);
        Assert.DoesNotContain("CPU · now", answer.Reply);
    }

    private static DeviceReading Reading(DeviceSample sample) => new(sample, "CPU", "Memory", "GPU",
        "Disk", "Network", "Power", "Processes");

    [Fact]
    public async Task OpenAiStreamsCitationsAndPersistsBoundedLocalContext()
    {
        var store = Store;
        using var handler = new Stub((request, body) =>
        {
            Assert.Equal("https://api.openai.com/v1/responses", request.RequestUri!.AbsoluteUri);
            Assert.Equal("Bearer", request.Headers.Authorization?.Scheme);
            using var json = JsonDocument.Parse(body);
            var root = json.RootElement;
            Assert.False(root.GetProperty("store").GetBoolean());
            Assert.Equal("high", root.GetProperty("reasoning").GetProperty("effort").GetString());
            var tools = root.GetProperty("tools").EnumerateArray().Select(tool => tool.GetProperty("type").GetString()).ToArray();
            Assert.Equal(new[] { "web_search", "code_interpreter" }, tools);
            return Reply("text/event-stream", """
                data: {"type":"response.output_text.delta","delta":"A "}

                data: {"type":"response.output_text.delta","delta":"result"}

                data: {"type":"response.output_text.annotation.added","annotation":{"type":"url_citation","title":"Reference","url":"https://example.net/article"}}

                data: {"type":"response.output_text.annotation.added","annotation":{"type":"url_citation","title":"Unsafe link","url":"file:///C:/"}}

                data: {"type":"response.completed","response":{"usage":{"output_tokens":9}}}

                """ + "\n");
        });
        using var client = new ProviderClient(remoteHandler: handler);
        var engine = new ChatEngine(store, new FakeCredentials(), client);
        var buffer = new StringBuilder();
        var turn = await engine.SendAsync("chat", "Summarize", null, new Preferences(),
            new ChatOptions(Provider.OpenAI, "high", WebSearch: true, DataAnalysis: true), _ => { },
            text => buffer.Append(text), default);
        Assert.Equal(TurnState.Completed, turn.State);
        Assert.Equal("A result", buffer.ToString());
        Assert.Equal(new Source("Reference", "https://example.net/article"), Assert.Single(turn.Sources));
        Assert.Equal(9, turn.OutputTokens);
        var reopened = Store;
        var saved = Assert.Single(reopened.ReadTurns("chat"));
        Assert.Equal(turn.Reply, saved.Reply);
        var inputs = reopened.RecentContext("chat", Provider.OpenAI, false);
        Assert.Equal(["user", "assistant"], inputs.Select(input => input.Role));
        Assert.Equal(["Summarize", "A result"], inputs.Select(input => input.Text));
    }

    [Fact]
    public async Task OllamaRemainsOnLoopbackAndSavesDirectedMemory()
    {
        var store = Store;
        using var local = new Stub((request, body) =>
        {
            if (request.RequestUri!.AbsolutePath=="/api/tags") return Reply("application/json", """{"models":[{"name":"local-test"}]}""");
            Assert.Equal("http://127.0.0.1:11434/api/chat", request.RequestUri!.AbsoluteUri);
            Assert.Null(request.Headers.Authorization);
            Assert.Contains("What is a journal entry?", body);
            return Reply("application/x-ndjson", """
                {"message":{"content":"Saved"},"done":false}
                {"message":{"content":"."},"done":true,"eval_count":2}
                """);
        });
        using var remote = new Stub((_, _) => throw new InvalidOperationException("Unexpected remote request."));
        using var client = new ProviderClient(remote, local);
        var engine = new ChatEngine(store, new FakeCredentials(), client);
        var settings = new Preferences { OllamaModel = "local-test", UseMemory = true };
        var turn = await engine.SendAsync("chat", "What is a journal entry?", null, settings,
            new ChatOptions(Provider.Automatic), _ => { }, _ => { }, default);
        Assert.Equal("Saved.", turn.Reply);
        await engine.SendAsync("chat","remember that I prefer local replies",null,settings,
            new ChatOptions(Provider.Automatic),_ => { },_ => { },default);
        Assert.Equal("I prefer local replies", Assert.Single(Store.Memories()).Text);
    }

    [Fact]
    public async Task AutomaticRoutingSkipsAnOldOllamaModelWhenQwenIsRunning()
    {
        using var local = new Stub((request, _) => request.RequestUri!.AbsolutePath=="/api/tags"
            ? Reply("application/json", """{"models":[]}""")
            : Reply("application/json", """{"data":[{"id":"qwen2.5-0.5b-instruct"}]}"""));
        using var remote = new Stub((_,_)=>throw new InvalidOperationException("Unexpected remote discovery."));
        using var provider = new ProviderClient(remote,local);
        var engine = new ChatEngine(Store,new FakeCredentials(),provider);
        var settings = new Preferences { OllamaModel="removed-model" };
        var route = await engine.ResolveAsync(settings,new(Provider.Automatic),"Hello",null,default);
        Assert.Equal("qwen2.5-0.5b-instruct",route.Model);
        Assert.Equal("127.0.0.1",route.Endpoint.Host);
    }

    [Fact]
    public async Task CompatibleChatParsesCompletionAndKeepsCredentialsWithEndpoint()
    {
        using var remote = new Stub((request, body) =>
        {
            Assert.Equal("https://example.net/v1/chat/completions", request.RequestUri!.AbsoluteUri);
            Assert.Equal("local-test-key", request.Headers.Authorization?.Parameter);
            Assert.Contains("\"model\":\"model-one\"", body);
            return Reply("text/event-stream", """
                data: {"choices":[{"delta":{"content":"Compatible"},"finish_reason":null}]}

                data: {"choices":[{"delta":{},"finish_reason":"stop"}]}

                data: [DONE]

                """ + "\n");
        });
        using var client = new ProviderClient(remote);
        var engine = new ChatEngine(Store, new FakeCredentials(), client);
        var turn = await engine.SendAsync("chat", "Test", null,
            new Preferences { CompatibleEndpoint = "https://example.net/v1", CompatibleModel = "model-one" },
            new ChatOptions(Provider.Compatible), _ => { }, _ => { }, default);
        Assert.Equal(TurnState.Completed, turn.State);
        Assert.Equal("Compatible", turn.Reply);
    }

    [Fact]
    public async Task InterruptedStreamPersistsPartialResultWithoutRetry()
    {
        int attempts = 0;
        using var remote = new Stub((_, _) =>
        {
            attempts++;
            return Reply("text/event-stream", "data: {\"type\":\"response.output_text.delta\",\"delta\":\"Partial\"}\n\n");
        });
        using var client = new ProviderClient(remote);
        var engine = new ChatEngine(Store, new FakeCredentials(), client);
        var turn = await engine.SendAsync("chat", "Test", null, new Preferences(), new ChatOptions(Provider.OpenAI),
            _ => { }, _ => { }, default);
        Assert.Equal(1, attempts);
        Assert.Equal(TurnState.Partial, turn.State);
        Assert.Equal("Partial", Assert.Single(Store.ReadTurns("chat")).Reply);
    }

    [Fact]
    public async Task CancellationSavesWhatWasReceived()
    {
        using var cancellation = new CancellationTokenSource();
        using var remote = new Stub((_, _) => Reply("text/event-stream",
            "data: {\"type\":\"response.output_text.delta\",\"delta\":\"First part\"}\n\n"));
        using var client = new ProviderClient(remote);
        var engine = new ChatEngine(Store, new FakeCredentials(), client);
        var turn = await engine.SendAsync("chat", "Test", null, new Preferences(), new ChatOptions(Provider.OpenAI),
            _ => { }, _ => cancellation.Cancel(), cancellation.Token);
        Assert.Equal(TurnState.Cancelled, turn.State);
        Assert.Equal("First part", turn.Reply);
    }

    [Fact]
    public void AttachmentFollowsItsOwnHistoricalTurn()
    {
        var store = Store;
        var first = store.StartTurn("chat", "Summarize", "local", new Attachment("A.txt", "text/plain", [65]));
        store.FinishTurn(first with { Reply = "First", State = TurnState.Completed });
        var second = store.StartTurn("chat", "Summarize", "local", null);
        store.FinishTurn(second with { Reply = "Second", State = TurnState.Completed });
        var context = store.RecentContext("chat", Provider.Ollama, true);
        Assert.Equal("A.txt", context[0].File?.Name);
        Assert.Null(context[2].File);
    }

    [Fact]
    public async Task AttachmentReaderChecksSizeAndUtf8()
    {
        var input = new MemoryStream(Encoding.UTF8.GetBytes("a,b\n1,2"));
        var file = await Attachments.ReadAsync("sample.csv", input);
        Assert.Equal("text/plain", file.MediaType);
        await Assert.ThrowsAsync<StarHubException>(() => Attachments.ReadAsync("sample.txt", new MemoryStream([0xff])));
        await Assert.ThrowsAsync<StarHubException>(() => Attachments.ReadAsync("sample.txt", new MemoryStream(new byte[96_001])));
        await Assert.ThrowsAsync<StarHubException>(() => Attachments.ReadAsync("sample.exe", new MemoryStream([0])));
    }

    [Fact]
    public async Task ScheduleClaimIsAtomicAndMissedIntervalsAreCoalesced()
    {
        var store = Store;
        var now = DateTimeOffset.UtcNow;
        var start = now.AddMinutes(-60).AddSeconds(-4);
        store.AddSchedule(new TaskSchedule("s1", "Daily", "Summarize", Provider.Ollama, false, 15,
            start.ToUnixTimeMilliseconds(), true));
        var claims = await Task.WhenAll(Enumerable.Range(0, 8).Select(_ => Task.Run(() => store.ClaimDueTask(now))));
        var run = Assert.Single(claims.OfType<TaskRun>());
        Assert.InRange(Assert.Single(store.Schedules()).NextRun, now.ToUnixTimeMilliseconds() + 1, now.AddMinutes(15).ToUnixTimeMilliseconds());
        Assert.False(store.RemoveSchedule("s1"));
        Assert.Null(store.ClaimDueTask(now.AddHours(1)));
        store.MarkInterruptedTasks();
        Assert.Equal(TurnState.Interrupted, Assert.Single(store.TaskRuns("s1")).State);
        store.FinishTask(run with { State = TurnState.Completed, Result = "late" });
        Assert.Equal(TurnState.Interrupted, Assert.Single(store.TaskRuns("s1")).State);
        Assert.True(store.RemoveSchedule("s1"));
        Assert.Empty(store.TaskRuns("s1"));
    }

    [Theory]
    [InlineData(false)]
    [InlineData(true)]
    public async Task ImageToolsUseOfficialEndpointsAndReturnPng(bool editing)
    {
        byte[] png = [0x89, 0x50, 0x4e, 0x47, 13, 10, 26, 10, 20, 40];
        var model = ModelCatalog.ImageModels[0].Id;
        using var remote = new Stub((request, body) =>
        {
            Assert.Equal(editing ? "/v1/images/edits" : "/v1/images/generations", request.RequestUri!.AbsolutePath);
            Assert.Equal("local-test-key", request.Headers.Authorization?.Parameter);
            Assert.Contains(model, body);
            Assert.Contains("An illustration of a red kite", body);
            if (editing)
            {
                Assert.Contains("image[]", body);
                Assert.Contains("original.png", body);
                Assert.Contains("multipart/form-data", request.Content?.Headers.ContentType?.MediaType);
            }
            else Assert.Equal("application/json", request.Content?.Headers.ContentType?.MediaType);
            return Reply("application/json", JsonSerializer.Serialize(new
            {
                data = new[] { new { b64_json = Convert.ToBase64String(png) } }
            }));
        });
        using var client = new ProviderClient(remote);
        var image = await client.MakeImageAsync("local-test-key", model, "An illustration of a red kite",
            editing ? new Attachment("original.png", "image/png", png) : null, default);
        Assert.Equal(png, image);
    }

    [Fact]
    public async Task TranscriptionPostsAudioAndReturnsOriginalLanguage()
    {
        using var remote = new Stub((request, body) =>
        {
            Assert.Equal("/v1/audio/transcriptions", request.RequestUri!.AbsolutePath);
            Assert.Equal("multipart/form-data", request.Content?.Headers.ContentType?.MediaType);
            Assert.Contains(ModelCatalog.TranscriptionModel, body);
            Assert.Contains("audio/wav", body);
            Assert.Contains("test.wav", body);
            return Reply("application/json", """{"text":"你好，Star Hub。"}""");
        });
        using var client = new ProviderClient(remote);
        using var audio = new MemoryStream([1, 2, 3]);
        Assert.Equal("你好，Star Hub。", await client.TranscribeAsync("local-test-key", audio, "test.wav", default));
    }

    private static HttpResponseMessage Reply(string type, string text) => new(HttpStatusCode.OK)
    {
        Content = new StringContent(text, Encoding.UTF8, type)
    };

    public void Dispose()
    {
        SqliteConnection.ClearAllPools();
        if (Directory.Exists(directory)) Directory.Delete(directory, recursive: true);
    }

    private sealed class Stub(Func<HttpRequestMessage, string, HttpResponseMessage> reply) : HttpMessageHandler
    {
        protected override async Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken token) =>
            reply(request, request.Content is null ? "" : await request.Content.ReadAsStringAsync(token));
    }

    private sealed class FakeCredentials : StarHub.Core.ICredentials
    {
        public string Read(string target) => "local-test-key";
    }

    private sealed class NoCredentials : StarHub.Core.ICredentials
    {
        public string? Read(string target) => null;
    }
}
