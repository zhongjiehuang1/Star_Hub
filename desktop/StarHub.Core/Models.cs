using System.Globalization;
using System.Text.Json;

namespace StarHub.Core;

public enum Provider { Automatic, OpenAI, Ollama, Compatible, LocalQwen }

public sealed record Preferences
{
    public string OpenAiModel { get; init; } = ModelCatalog.OpenAiModels[0].Id;
    public string? ChatOpenAiModel { get; init; }
    public string OllamaModel { get; init; } = "";
    public int OllamaPort { get; init; } = 11434;
    public string CompatibleModel { get; init; } = "";
    public string CompatibleEndpoint { get; init; } = "";
    public Provider Provider { get; init; } = Provider.Automatic;
    public int OutputTokens { get; init; } = 8192;
    public string McpEndpoint { get; init; } = "";
    public string? OpenProjectId { get; init; }
    public bool UseMemory { get; init; } = true;
    public bool OperateComputer { get; init; } = true;
    public string Theme { get; init; } = "System";
    public string Language { get; init; } = "system";

    public bool UsesChineseInterface() => Language == "zh-Hans" ||
        Language != "en" && CultureInfo.CurrentUICulture.TwoLetterISOLanguageName == "zh";
}

public sealed record CatalogModel(string Id, string Name, string[] Reasoning);
public static class ModelCatalog
{
    public const string Terra = "gpt-6.1-terra";
    public const string Luna = "gpt-6-luna";
    public static IReadOnlyList<string> ComputerModels { get; } = [Terra, Luna, "gpt-6.2-sol", "gpt-6.2-astra"];

    private static readonly CatalogEntry[] Entries = ReadModels();
    public static IReadOnlyList<CatalogModel> OpenAiModels { get; } =
        Entries.Where(entry => entry.Categories.Contains("chat") && !entry.Batch).Select(entry => entry.Model)
            .Concat(new CatalogModel[]
            {
                new(Terra, "GPT-6.1 Terra", ["none", "low", "medium", "high", "xhigh", "max"]),
                new(Luna, "GPT-6 Luna", ["none", "low", "medium", "high", "xhigh", "max"])
            })
            .DistinctBy(model => model.Id).ToArray();
    public static IReadOnlyList<CatalogModel> ImageModels { get; } =
        Entries.Where(entry => entry.Categories.Contains("image") && !entry.Batch).Select(entry => entry.Model).ToArray();
    public static IReadOnlyList<CatalogModel> RealtimeModels { get; } =
        Entries.Where(entry => entry.Categories.Contains("realtime") && entry.Model.Id.StartsWith("gpt-realtime-", StringComparison.Ordinal))
               .Select(entry => entry.Model).ToArray();
    public static string TranscriptionModel { get; } =
        Entries.First(entry => entry.Categories.Contains("transcription") && !entry.Batch).Model.Id;

    private static CatalogEntry[] ReadModels()
    {
        using var stream = typeof(ModelCatalog).Assembly.GetManifestResourceStream("StarHub.ModelCatalog.json")!;
        using var catalog = JsonDocument.Parse(stream);
        return catalog.RootElement.GetProperty("models").EnumerateArray()
            .Where(m => m.GetProperty("provider").GetString() == "openai")
            .Select(m => new CatalogEntry(new CatalogModel(m.GetProperty("id").GetString()!,
                    m.GetProperty("name").GetString()!.Replace("OpenAI: ", ""),
                    m.GetProperty("reasoning").EnumerateArray().Select(x => x.GetString()!).ToArray()),
                m.GetProperty("categories").EnumerateArray().Select(x => x.GetString()!).ToArray(),
                m.GetProperty("batch").GetBoolean()))
            .ToArray();
    }

    private sealed record CatalogEntry(CatalogModel Model, string[] Categories, bool Batch);
}

public sealed record Conversation(string Id, string Title, long Updated)
{
    public override string ToString() => Title;
}

public sealed record Source(string Title, string Url);

public enum TurnState { Running, Completed, Partial, Cancelled, Failed, Interrupted }

public sealed record Turn(string Id, string Prompt, string Reply, string Model, TurnState State,
    string Detail, IReadOnlyList<Source> Sources, int? OutputTokens, string? FileName,
    IReadOnlyList<ProjectReference>? ProjectSources = null, bool LocalOnly = false, string? GeneratedPdf = null,
    WorkReceipt? LocalWork = null);

public sealed record Attachment(string Name, string MediaType, byte[] Bytes)
{
    public bool IsImage => MediaType.StartsWith("image/", StringComparison.Ordinal);
    public bool IsPdf => MediaType == "application/pdf";
}

public sealed record ChatInput(string Role, string Text, Attachment? File = null);
public sealed record ChatOptions(Provider Provider, string? Reasoning = null, bool WebSearch = false,
    bool DataAnalysis = false, string? SystemContext = null, string? ProjectId = null, bool AllowLocalDeviceAnalysis = true);

public sealed record ModelRoute(Provider Provider, Uri Endpoint, string Model, string? Credential);
public sealed record ChatRequest(ModelRoute Route, IReadOnlyList<ChatInput> Inputs, ChatOptions Options,
    string Instructions, int? MaxOutputTokens);

public sealed record ProviderUpdate(string? Text = null, Source? Source = null, int? OutputTokens = null,
    string? Incomplete = null);

public sealed record TaskSchedule(string Id, string Name, string Prompt, Provider Provider,
    bool WebSearch, int IntervalMinutes, long NextRun, bool Enabled)
{
    public override string ToString() => Name;
}

public sealed record TaskRun(string Id, string ScheduleId, string Prompt, Provider Provider,
    bool WebSearch, long Started, TurnState State, string Result, string Detail, IReadOnlyList<Source> Sources);
public sealed record MemoryNote(string Id, string Text, long Updated)
{
    public override string ToString() => Text;
}

public interface ICredentials
{
    string? Read(string target);
}

public sealed class StarHubException(string message) : Exception(message);
