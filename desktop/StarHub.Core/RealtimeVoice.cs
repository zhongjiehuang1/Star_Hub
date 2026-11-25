using System.Net.WebSockets;
using System.Runtime.CompilerServices;
using System.Text;
using System.Text.Json;

namespace StarHub.Core;

public enum VoiceEventKind { Ready, SpeechStarted, SpeechStopped, UserText, UserTranscript, AssistantText, Audio, ResponseStarted, ResponseDone, Warning }
public sealed record VoiceEvent(VoiceEventKind Kind, string? Text = null, string? Item = null, string? Response = null,
    int ContentIndex = 0, byte[]? Pcm = null);

public sealed class RealtimeVoice : IAsyncDisposable
{
    public const int SampleRate = 24_000;
    public const int BytesPerMillisecond = SampleRate * 2 / 1000;
    private const int MaxEventBytes = 2 * 1024 * 1024;
    private readonly ClientWebSocket socket = new();
    private readonly SemaphoreSlim sender = new(1);

    private RealtimeVoice() { }

    public static async Task<RealtimeVoice> ConnectAsync(string key, string model, bool voiceDetection,
        CancellationToken token = default)
    {
        if (!ModelCatalog.RealtimeModels.Any(item => item.Id == model))
            throw new StarHubException("Choose a supported Realtime model.");
        var format = new { type = "audio/pcm", rate = SampleRate };
        object? detection = voiceDetection
            ? new { type = "server_vad", prefix_padding_ms = 350, silence_duration_ms = 550, create_response = true, interrupt_response = true }
            : null;
        var configuration = new
        {
            type = "session.update",
            session = new
            {
                type = "realtime",
                output_modalities = new[] { "audio" },
                instructions = "You are Star Hub's voice assistant. Answer in the user's language and keep spoken answers concise.",
                max_output_tokens = 2048,
                audio = new
                {
                    input = new
                    {
                        format, transcription = new { model = ModelCatalog.TranscriptionModel },
                        turn_detection = detection
                    },
                    output = new { format, voice = "marin" }
                }
            }
        };
        return await ConnectSessionAsync(key, "model=" + Uri.EscapeDataString(model), configuration, token).ConfigureAwait(false);
    }

    public static async Task<string> TranscribeAsync(string key, IAsyncEnumerable<byte[]> input, Action<string> update, CancellationToken token)
    {
        await using var session = await ConnectSessionAsync(key, "intent=transcription", new
        {
            type = "session.update",
            session = new
            {
                type = "transcription",
                audio = new { input = new
                {
                    format = new { type = "audio/pcm", rate = SampleRate },
                    transcription = new { model = "gpt-live-transcribe", delay = "low" },
                    turn_detection = (object?)null
                } }
            }
        }, token).ConfigureAwait(false);

        using var lifetime = CancellationTokenSource.CreateLinkedTokenSource(token);
        var sending = SendRecordingAsync();
        var preview = new StringBuilder();
        try
        {
            await foreach (var item in session.ReadAsync(lifetime.Token).ConfigureAwait(false))
            {
                if (item.Kind == VoiceEventKind.UserText) update(preview.Append(item.Text).ToString());
                if (item.Kind == VoiceEventKind.UserTranscript) return item.Text?.Trim() ?? "";
                if (item.Kind == VoiceEventKind.Warning) throw new StarHubException(item.Text ?? "Realtime transcription failed.");
            }
            throw new StarHubException("OpenAI closed the connection before returning the transcript.");
        }
        finally
        {
            lifetime.Cancel();
            await sending.ConfigureAwait(ConfigureAwaitOptions.SuppressThrowing);
        }

        async Task SendRecordingAsync()
        {
            try
            {
                await foreach (var chunk in input.WithCancellation(lifetime.Token).ConfigureAwait(false))
                    await session.AppendAsync(chunk, lifetime.Token).ConfigureAwait(false);
                await session.SendAsync(new { type = "input_audio_buffer.commit" }, lifetime.Token).ConfigureAwait(false);
            }
            catch
            {
                lifetime.Cancel();
                throw;
            }
        }
    }

    private static async Task<RealtimeVoice> ConnectSessionAsync<T>(string key, string query, T configuration, CancellationToken token)
    {
        var session = new RealtimeVoice();
        try
        {
            using var deadline = CancellationTokenSource.CreateLinkedTokenSource(token);
            deadline.CancelAfter(TimeSpan.FromSeconds(35));
            session.socket.Options.SetRequestHeader("Authorization", "Bearer " + key);
            session.socket.Options.KeepAliveInterval = TimeSpan.FromSeconds(20);
            await session.socket.ConnectAsync(
                new Uri("wss://api.openai.com/v1/realtime?" + query), deadline.Token).ConfigureAwait(false);
            await session.SendAsync(configuration, deadline.Token).ConfigureAwait(false);
            while (await session.ReceiveAsync(deadline.Token).ConfigureAwait(false) is { } incoming)
            {
                if (incoming.Kind == VoiceEventKind.Ready) return session;
                if (incoming.Kind == VoiceEventKind.Warning)
                    throw new StarHubException(incoming.Text ?? "OpenAI rejected the voice configuration.");
            }
            throw new StarHubException("OpenAI closed the connection before accepting the voice configuration.");
        }
        catch
        {
            await session.DisposeAsync().ConfigureAwait(false);
            throw;
        }
    }

    public Task AppendAsync(ReadOnlyMemory<byte> pcm, CancellationToken token)
    {
        if (pcm.Length is < 2 or > 96_000 || pcm.Length % 2 != 0)
            throw new StarHubException("Realtime audio must arrive as complete 24 kHz PCM samples.");
        return SendAsync(new { type = "input_audio_buffer.append", audio = Convert.ToBase64String(pcm.Span) }, token);
    }

    public async Task CommitAndRespondAsync(CancellationToken token)
    {
        await SendAsync(new { type = "input_audio_buffer.commit" }, token).ConfigureAwait(false);
        await SendAsync(new { type = "response.create" }, token).ConfigureAwait(false);
    }

    public Task CancelResponseAsync(string responseId, CancellationToken token) =>
        SendAsync(new { type = "response.cancel", response_id = responseId }, token);

    public Task TruncateAsync(string itemId, int contentIndex, int audioEndMs, CancellationToken token) =>
        SendAsync(new { type = "conversation.item.truncate", item_id = itemId, content_index = contentIndex,
            audio_end_ms = audioEndMs }, token);

    private async Task SendAsync<T>(T value, CancellationToken token)
    {
        var bytes = JsonSerializer.SerializeToUtf8Bytes(value);
        await sender.WaitAsync(token).ConfigureAwait(false);
        try { await socket.SendAsync(bytes.AsMemory(), WebSocketMessageType.Text, true, token).ConfigureAwait(false); }
        finally { sender.Release(); }
    }

    public async IAsyncEnumerable<VoiceEvent> ReadAsync([EnumeratorCancellation] CancellationToken token)
    {
        while (await ReceiveAsync(token).ConfigureAwait(false) is { } next) yield return next;
    }

    private async Task<VoiceEvent?> ReceiveAsync(CancellationToken token)
    {
        var buffer = new byte[16_384];
        using var message = new MemoryStream();
        while (true)
        {
            var received = await socket.ReceiveAsync(buffer.AsMemory(), token).ConfigureAwait(false);
            if (received.MessageType == WebSocketMessageType.Close) return null;
            if (received.MessageType != WebSocketMessageType.Text || message.Length + received.Count > MaxEventBytes)
                throw new StarHubException("OpenAI returned an unsupported Realtime event.");
            message.Write(buffer, 0, received.Count);
            if (!received.EndOfMessage) continue;
            message.Position = 0;
            using var json = await JsonDocument.ParseAsync(message, cancellationToken: token).ConfigureAwait(false);
            if (Parse(json.RootElement) is { } parsed) return parsed;
            message.SetLength(0);
        }
    }

    internal static VoiceEvent? Parse(JsonElement value)
    {
        var item = Str(value, "item_id");
        var responseId = Str(value, "response_id");
        var index = value.TryGetProperty("content_index", out var content) && content.TryGetInt32(out var number) ? number : 0;
        return Str(value, "type") switch
        {
            "session.updated" or "transcription_session.updated" => new(VoiceEventKind.Ready),
            "input_audio_buffer.speech_started" => new(VoiceEventKind.SpeechStarted),
            "input_audio_buffer.speech_stopped" => new(VoiceEventKind.SpeechStopped),
            "conversation.item.input_audio_transcription.delta" => new(VoiceEventKind.UserText, Str(value, "delta"), item),
            "conversation.item.input_audio_transcription.completed" =>
                new(VoiceEventKind.UserTranscript, Str(value, "transcript"), item),
            "conversation.item.input_audio_transcription.failed" =>
                new(VoiceEventKind.Warning, "OpenAI could not transcribe the last audio turn."),
            "response.output_audio_transcript.delta" =>
                new(VoiceEventKind.AssistantText, Str(value, "delta"), item, responseId, index),
            "response.output_audio.delta" when value.TryGetProperty("delta", out var data) &&
                    data.ValueKind == JsonValueKind.String && data.TryGetBytesFromBase64(out byte[]? pcm) && pcm.Length % 2 == 0 =>
                new(VoiceEventKind.Audio, Item: item, Response: responseId, ContentIndex: index, Pcm: pcm),
            "response.created" when value.TryGetProperty("response", out var started) =>
                new(VoiceEventKind.ResponseStarted, Response: Str(started, "id")),
            "response.done" when value.TryGetProperty("response", out var ended) =>
                new(VoiceEventKind.ResponseDone, Text: Str(ended, "status"), Response: Str(ended, "id")),
            "error" => new(VoiceEventKind.Warning, SafeError(value)),
            _ => null
        };
    }

    private static string? Str(JsonElement value, string name) => value.ValueKind == JsonValueKind.Object
        && value.TryGetProperty(name, out var node) && node.ValueKind == JsonValueKind.String ? node.GetString() : null;

    private static string SafeError(JsonElement value)
    {
        if (!value.TryGetProperty("error", out var error)) return "OpenAI reported a Realtime error.";
        var code = Str(error, "code");
        if (code is "response_cancel_not_active" or "conversation_already_has_active_response") return "The voice response already changed state.";
        if (code is { Length: > 0 and <= 80 } && code.All(c => char.IsAsciiLetterOrDigit(c) || c == '_'))
            return $"OpenAI reported a Realtime error ({code}).";
        return "OpenAI reported a Realtime error.";
    }

    public async ValueTask DisposeAsync()
    {
        try
        {
            using var deadline = new CancellationTokenSource(TimeSpan.FromSeconds(2));
            if (socket.State is WebSocketState.Open or WebSocketState.CloseReceived)
                await socket.CloseOutputAsync(WebSocketCloseStatus.NormalClosure, "", deadline.Token).ConfigureAwait(false);
        }
        catch (Exception error) when (error is WebSocketException or OperationCanceledException) { }
        finally
        {
            socket.Dispose();
            sender.Dispose();
        }
    }
}
