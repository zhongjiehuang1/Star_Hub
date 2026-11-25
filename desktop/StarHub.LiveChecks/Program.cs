using System.Text;
using System.Text.Json;
using Microsoft.Data.Sqlite;
using StarHub.Core;

#if WINDOWS
if (args is ["device-alert"])
{
    WindowsChecks.VerifyDeviceAlert();
    return 0;
}
#endif

if (Environment.GetEnvironmentVariable("STARHUB_OPENAI_TEST_KEY") is not { Length: > 0 } key)
{
    Console.Error.WriteLine("The authorized OpenAI release credential was not provided.");
    return 1;
}

using var client = new ProviderClient();
try
{
    if (args.Length == 0 || args.Contains("voice")) await CheckVoiceAsync(key);
    if (args.Length == 0 || args.Contains("knowledge")) await CheckProjectAsync(key, client);
    if (args.Length == 0 || args.Contains("mcp")) await CheckMcpDraftAsync(key, client);
#if WINDOWS
    if (args.Length == 0 || args.Contains("windows")) await WindowsChecks.VerifyAsync(key, client);
    if (args.Contains("pc-task")) await WindowsChecks.VerifyPcTaskAsync(key, client);
#endif
    return 0;
}
catch (Exception error)
{
    Console.Error.WriteLine(error is StarHubException ? error.Message : $"Live check failed with {error.GetType().Name} (0x{error.HResult:X8}).");
    return 1;
}

static async Task CheckVoiceAsync(string key)
{
    using var timeout = new CancellationTokenSource(TimeSpan.FromSeconds(105));
    var token = timeout.Token;
    await using var voice = await RealtimeVoice.ConnectAsync(key, "gpt-realtime-2.1", voiceDetection: true, token);
    var first = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
    var second = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
    int heardSpeech = 0, transcripts = 0, outputBytes = 0, replies = 0;
    var replyText = new StringBuilder();

    async Task ObserveAsync()
    {
        await foreach (var update in voice.ReadAsync(token))
        {
            switch (update.Kind)
            {
                case VoiceEventKind.Warning:
                    throw new StarHubException(update.Text ?? "Realtime API warning.");
                case VoiceEventKind.SpeechStarted:
                    heardSpeech++;
                    break;
                case VoiceEventKind.UserTranscript when !string.IsNullOrWhiteSpace(update.Text):
                    transcripts++;
                    break;
                case VoiceEventKind.AssistantText:
                    replyText.Append(update.Text);
                    break;
                case VoiceEventKind.Audio:
                    outputBytes += update.Pcm?.Length ?? 0;
                    break;
                case VoiceEventKind.ResponseDone:
                    if (update.Text != "completed") throw new StarHubException("The Realtime reply did not complete.");
                    int turn = replies++;
                    string expected = turn == 0 ? "bluebird" : "sunflower";
                    if (!replyText.ToString().Contains(expected, StringComparison.OrdinalIgnoreCase))
                        throw new StarHubException($"Realtime turn {turn + 1} did not contain the expected spoken response.");
                    replyText.Clear();
                    if (turn == 0) first.TrySetResult();
                    else second.TrySetResult();
                    break;
            }
            if (replies >= 2 && transcripts >= 2) return;
        }
        throw new StarHubException("The Realtime API disconnected before completing both turns.");
    }

    async Task FeedAsync(string name)
    {
        using var source = typeof(Program).Assembly.GetManifestResourceStream($"StarHub.LiveChecks.Fixtures.{name}.pcm")!;
        var silence = new byte[1920];
        for (int i = 0; i < 5; i++) await SendAsync(silence);
        var frame = new byte[1920];
        int read;
        while ((read = await source.ReadAsync(frame, token)) > 0)
            await SendAsync(frame.AsMemory(0, read));
        for (int i = 0; i < 24 && !second.Task.IsCompleted; i++) await SendAsync(silence);

        async Task SendAsync(ReadOnlyMemory<byte> data)
        {
            await voice.AppendAsync(data, token);
            await Task.Delay(40, token);
        }
    }

    var observe = ObserveAsync();
    try
    {
        await FeedAsync("bluebird");
        await (await Task.WhenAny(first.Task, observe)).WaitAsync(token);
        await FeedAsync("sunflower");
        await (await Task.WhenAny(second.Task, observe)).WaitAsync(token);
        await observe.WaitAsync(token);
        if (heardSpeech < 2 || transcripts < 2 || outputBytes < 10_000)
            throw new StarHubException("Realtime did not return two transcriptions and audible PCM.");
        Console.WriteLine($"OpenAI Realtime passed on {Environment.OSVersion.Platform}: {replies} turns, {transcripts} input-audio transcriptions, {outputBytes} bytes of assistant PCM.");
    }
    finally
    {
        await timeout.CancelAsync();
        try { await observe; } catch (OperationCanceledException) when (timeout.IsCancellationRequested) { }
    }
}

static async Task CheckProjectAsync(string key, ProviderClient client)
{
    string location = Path.Combine(Path.GetTempPath(), "StarHub-live-" + Guid.NewGuid().ToString("N"));
    try
    {
        var store = new LocalStore(location);
        var project = store.CreateProject("Lighthouse test");
        var other = store.CreateProject("Unrelated project");
        store.SaveProjectDocument(project.Id, "Lighthouse calibration.txt", "The exact lighthouse calibration code is CITRINE-7192.");
        store.SaveProjectDocument(other.Id, "Lighthouse calibration.txt", "The exact lighthouse calibration code is DECOY-5721.");
        var engine = new ChatEngine(store, new LiveCredential(key), client);
        using var timeout = new CancellationTokenSource(TimeSpan.FromSeconds(75));
        var turn = await engine.SendAsync("live-knowledge", "What is the exact lighthouse calibration code? Reply with the code only.",
            null, new Preferences { OutputTokens = 1500 }, new ChatOptions(Provider.OpenAI, "low", ProjectId: project.Id),
            _ => { }, _ => { }, timeout.Token);
        if (turn.State != TurnState.Completed || !turn.Reply.Contains("CITRINE-7192", StringComparison.Ordinal)
            || turn.Reply.Contains("DECOY-5721", StringComparison.Ordinal) || turn.ProjectSources is not { Count: 1 }
            || store.Conversations(projectId: project.Id).Count != 1 || store.Conversations(projectId: other.Id).Count != 0)
            throw new StarHubException("The real OpenAI response did not pass the project grounding and isolation check.");
        Console.WriteLine("OpenAI Responses passed: project excerpts grounded the answer and the other project stayed isolated.");
    }
    finally
    {
        SqliteConnection.ClearAllPools();
        if (Directory.Exists(location)) Directory.Delete(location, recursive: true);
    }
}

static async Task CheckMcpDraftAsync(string key, ProviderClient client)
{
    using var schema = JsonDocument.Parse("""{"type":"object","properties":{"city":{"type":"string"}},"required":["city"],"additionalProperties":false}""");
    using var timeout = new CancellationTokenSource(TimeSpan.FromSeconds(75));
    var draft = await client.DraftMcpArgumentsAsync(key, "gpt-6.2-sol", new McpToolInfo("lookup_city", "Look up a city.", schema.RootElement),
        "Look up the city of London.", timeout.Token);
    if (!McpConnection.ReadArguments(draft).TryGetProperty("city", out var city) ||
        city.ValueKind != JsonValueKind.String || !string.Equals(city.GetString(), "London", StringComparison.OrdinalIgnoreCase))
        throw new StarHubException("The real OpenAI call did not draft the expected MCP tool arguments.");
    Console.WriteLine("OpenAI Responses passed: a function call drafted editable MCP arguments.");
}

sealed class LiveCredential(string key) : ICredentials
{
    public string Read(string target) => key;
}
