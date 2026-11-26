using System.Net;
using System.Net.Http.Headers;
using System.Net.Http.Json;
using System.Net.ServerSentEvents;
using System.Runtime.CompilerServices;
using System.Text;
using System.Text.Json;

namespace StarHub.Core;

public sealed partial class ProviderClient : IDisposable
{
    private readonly HttpClient remote;
    private readonly HttpClient local;

    public ProviderClient(HttpMessageHandler? remoteHandler = null, HttpMessageHandler? localHandler = null)
    {
        remote = new HttpClient(remoteHandler ?? NewHandler(useProxy: true));
        local = new HttpClient(localHandler ?? NewHandler(useProxy: false));
        remote.Timeout = local.Timeout = Timeout.InfiniteTimeSpan;
    }

    private static HttpClientHandler NewHandler(bool useProxy) => new()
    {
        AllowAutoRedirect = false,
        UseProxy = useProxy,
        UseCookies = false,
        AutomaticDecompression = DecompressionMethods.All
    };

    public async IAsyncEnumerable<ProviderUpdate> StreamAsync(ChatRequest chat,
        [EnumeratorCancellation] CancellationToken cancellationToken = default)
    {
        var route = chat.Route;
        var (path, body) = route.Provider switch
        {
            Provider.OpenAI => ("responses", BuildResponses(chat)),
            Provider.Ollama => ("api/chat", BuildOllama(chat)),
            Provider.Compatible => ("chat/completions", BuildCompatible(chat)),
            _ => throw new StarHubException("No execution provider was selected.")
        };

        using var request = new HttpRequestMessage(HttpMethod.Post, new Uri(route.Endpoint, path))
        {
            Content = JsonContent.Create(body)
        };
        if (!string.IsNullOrWhiteSpace(route.Credential))
            request.Headers.Authorization = new AuthenticationHeaderValue("Bearer", route.Credential);
        var client = route.Endpoint.IsLoopback ? local : remote;
        using var response = await client.SendAsync(request, HttpCompletionOption.ResponseHeadersRead, cancellationToken)
            .ConfigureAwait(false);
        if (!response.IsSuccessStatusCode) throw HttpError(response.StatusCode);
        using var stream = await response.Content.ReadAsStreamAsync(cancellationToken).ConfigureAwait(false);

        var updates = route.Provider == Provider.Ollama
            ? ReadOllama(stream, cancellationToken)
            : ReadEvents(stream, route.Provider == Provider.OpenAI, cancellationToken);
        await foreach (var update in updates.ConfigureAwait(false)) yield return update;
    }

    private static Dictionary<string, object?> BuildResponses(ChatRequest chat)
    {
        var body = new Dictionary<string, object?>
        {
            ["model"] = chat.Route.Model, ["stream"] = true, ["store"] = false,
            ["instructions"] = chat.Instructions,
            ["input"] = chat.Inputs.Select(input => new
            {
                role = input.Role,
                content = input.Role == "assistant" ? (object)input.Text : ResponseContent(input)
            }).ToArray()
        };
        if (chat.MaxOutputTokens is { } limit) body["max_output_tokens"] = limit;
        if (chat.Options.Reasoning is { Length: > 0 } effort)
            body["reasoning"] = new { effort };

        var tools = new List<object>();
        if (chat.Options.WebSearch) tools.Add(new { type = "web_search" });
        if (chat.Options.DataAnalysis)
            tools.Add(new { type = "code_interpreter", container = new { type = "auto" } });
        if (tools.Count > 0)
        {
            body["tools"] = tools;
            body["max_tool_calls"] = 8;
        }
        return body;
    }

    private static object[] ResponseContent(ChatInput input)
    {
        var content = new List<object> { new { type = "input_text", text = WithTextFile(input) } };
        if (input.File is { IsImage: true } image)
            content.Add(new { type = "input_image", image_url = DataUrl(image), detail = "auto" });
        if (input.File is { IsPdf: true } pdf)
            content.Add(new { type = "input_file", filename = pdf.Name, file_data = DataUrl(pdf) });
        return [.. content];
    }

    private static Dictionary<string, object?> BuildOllama(ChatRequest chat)
    {
        var body = new Dictionary<string, object?>
        {
            ["model"] = chat.Route.Model, ["stream"] = true,
            ["messages"] = new[] { new ChatInput("system", chat.Instructions) }.Concat(chat.Inputs).Select(input =>
            {
                var message = new Dictionary<string, object> { ["role"] = input.Role, ["content"] = WithTextFile(input) };
                if (input.File is { IsImage: true } image) message["images"] = new[] { Convert.ToBase64String(image.Bytes) };
                return message;
            }).ToArray()
        };
        if (chat.MaxOutputTokens is { } limit) body["options"] = new { num_predict = limit };
        return body;
    }

    private static Dictionary<string, object?> BuildCompatible(ChatRequest chat)
    {
        var body = new Dictionary<string, object?>
        {
            ["model"] = chat.Route.Model, ["stream"] = true,
            ["messages"] = new[] { new ChatInput("system", chat.Instructions) }.Concat(chat.Inputs).Select(input => new
            {
                role = input.Role,
                content = input.File is { IsImage: true } image ? (object)new object[]
                {
                    new { type = "text", text = input.Text },
                    new { type = "image_url", image_url = new { url = DataUrl(image) } }
                } : WithTextFile(input)
            }).ToArray()
        };
        if (chat.MaxOutputTokens is { } limit) body["max_tokens"] = limit;
        return body;
    }

    private static string WithTextFile(ChatInput input) =>
        input.File is { IsImage: false, IsPdf: false } file
            ? input.Text + "\n\nAttached document (data): " + file.Name + "\n" +
              Encoding.UTF8.GetString(file.Bytes).TrimStart('\uFEFF')
            : input.Text;

    private static string DataUrl(Attachment file) =>
        $"data:{file.MediaType};base64,{Convert.ToBase64String(file.Bytes)}";

    private static async IAsyncEnumerable<ProviderUpdate> ReadEvents(Stream stream, bool responses,
        [EnumeratorCancellation] CancellationToken cancellationToken)
    {
        bool finished = false;
        await foreach (var item in SseParser.Create(stream).EnumerateAsync(cancellationToken).ConfigureAwait(false))
        {
            if (item.Data == "[DONE]")
            {
                if (!responses) finished = true;
                break;
            }
            using var json = JsonDocument.Parse(item.Data);
            var root = json.RootElement;
            if (root.TryGetProperty("error", out var error) && error.ValueKind == JsonValueKind.Object)
                throw new StarHubException("The provider reported an error during generation.");
            if (!responses)
            {
                if (root.TryGetProperty("choices", out var choices) && choices.ValueKind == JsonValueKind.Array && choices.GetArrayLength() > 0)
                {
                    var choice = choices[0];
                    if (choice.TryGetProperty("delta", out var delta) && String(delta, "content") is { } text)
                        yield return new ProviderUpdate(Text: text);
                    if (String(choice, "finish_reason") is { } reason)
                    {
                        finished = true;
                        if (reason == "length") yield return new ProviderUpdate(Incomplete: "The model reached its output limit.");
                    }
                }
                continue;
            }

            switch (String(root, "type"))
            {
                case "response.output_text.delta":
                case "response.refusal.delta":
                    yield return new ProviderUpdate(Text: String(root, "delta"));
                    break;
                case "response.output_text.annotation.added":
                    if (root.TryGetProperty("annotation", out var annotation) && Citation(annotation) is { } source)
                        yield return new ProviderUpdate(Source: source);
                    break;
                case "response.completed":
                case "response.incomplete":
                    if (!root.TryGetProperty("response", out var terminal) || terminal.ValueKind != JsonValueKind.Object)
                        throw new StarHubException("OpenAI returned an invalid completion event.");
                    foreach (var finalSource in Sources(terminal)) yield return new ProviderUpdate(Source: finalSource);
                    if (terminal.TryGetProperty("usage", out var usage) && Integer(usage, "output_tokens") is { } count)
                        yield return new ProviderUpdate(OutputTokens: count);
                    if (String(root, "type") == "response.incomplete")
                        yield return new ProviderUpdate(Incomplete: terminal.TryGetProperty("incomplete_details", out var incomplete)
                            && incomplete.ValueKind == JsonValueKind.Object && String(incomplete, "reason") == "max_output_tokens"
                            ? "The model reached its output limit." : "The provider returned an incomplete response.");
                    finished = true;
                    break;
                case "response.failed":
                case "error":
                    throw new StarHubException("OpenAI reported an error during generation.");
            }
            if (finished) break;
        }
        if (!finished) throw new StarHubException("The stream ended before the provider confirmed completion.");
    }

    internal static IEnumerable<Source> Sources(JsonElement response)
    {
        if (!response.TryGetProperty("output", out var output) || output.ValueKind != JsonValueKind.Array)
            yield break;
        foreach (var item in output.EnumerateArray())
        {
            if (!item.TryGetProperty("content", out var content) || content.ValueKind != JsonValueKind.Array) continue;
            foreach (var block in content.EnumerateArray())
            {
                if (!block.TryGetProperty("annotations", out var annotations) || annotations.ValueKind != JsonValueKind.Array)
                    continue;
                foreach (var annotation in annotations.EnumerateArray())
                    if (Citation(annotation) is { } source) yield return source;
            }
        }
    }

    private static Source? Citation(JsonElement annotation)
    {
        if (String(annotation, "type") != "url_citation" || String(annotation, "url") is not { } url
            || url.Length > 4000 || !TryWebUri(url, out var uri)) return null;
        var title = String(annotation, "title");
        return new Source(string.IsNullOrWhiteSpace(title) ? uri!.Host : title[..Math.Min(title.Length, 250)], uri!.AbsoluteUri);
    }

    public static bool TryWebUri(string url, out Uri? uri)
    {
        return Uri.TryCreate(url, UriKind.Absolute, out uri) && uri.Scheme is "http" or "https"
            && uri.UserInfo.Length == 0 && uri.Host.Length > 0;
    }

    private static async IAsyncEnumerable<ProviderUpdate> ReadOllama(Stream stream,
        [EnumeratorCancellation] CancellationToken cancellationToken)
    {
        using var reader = new StreamReader(stream);
        while (await reader.ReadLineAsync(cancellationToken).ConfigureAwait(false) is { } line)
        {
            if (string.IsNullOrWhiteSpace(line)) continue;
            using var json = JsonDocument.Parse(line);
            var root = json.RootElement;
            if (String(root, "error") is not null)
                throw new StarHubException("Ollama reported an error. Check that the selected model is installed.");
            if (root.TryGetProperty("message", out var message) && String(message, "content") is { } text)
                yield return new ProviderUpdate(Text: text);
            if (root.TryGetProperty("done", out var done) && done.ValueKind == JsonValueKind.True)
            {
                yield return new ProviderUpdate(OutputTokens: Integer(root, "eval_count"),
                    Incomplete: String(root, "done_reason") == "length" ? "The model reached its output limit." : null);
                yield break;
            }
        }
        throw new StarHubException("Ollama disconnected before confirming completion.");
    }

    private static string? String(JsonElement element, string property) =>
        element.ValueKind == JsonValueKind.Object && element.TryGetProperty(property, out var value)
        && value.ValueKind == JsonValueKind.String ? value.GetString() : null;

    private static int? Integer(JsonElement element, string property) =>
        element.ValueKind == JsonValueKind.Object && element.TryGetProperty(property, out var value)
        && value.ValueKind == JsonValueKind.Number && value.TryGetInt32(out int number) ? number : null;

    private static StarHubException HttpError(HttpStatusCode status) => new(status switch
    {
        HttpStatusCode.Unauthorized or HttpStatusCode.Forbidden => "The provider denied access. Check the API key and model permissions.",
        HttpStatusCode.NotFound => "The model or API route was unavailable. Check the model name and endpoint.",
        HttpStatusCode.TooManyRequests => "The provider rate or account limit was reached.",
        HttpStatusCode.BadRequest => "The provider rejected this request. Check the model's input and tool support.",
        _ => $"The provider returned HTTP {(int)status}."
    });

    public void Dispose()
    {
        remote.Dispose();
        local.Dispose();
    }
}
