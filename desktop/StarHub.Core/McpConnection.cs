using System.Net.Http.Headers;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using ModelContextProtocol.Client;
using ModelContextProtocol.Protocol;

namespace StarHub.Core;

public sealed record McpToolInfo(string Name, string Description, JsonElement Schema);
public sealed record McpResult(bool IsError, string Text);

public sealed class McpConnection : IAsyncDisposable
{
    private const int MaxArguments = 24_000;
    private const int MaxResult = 24_000;
    private readonly McpClient client;
    public IReadOnlyList<McpToolInfo> Tools { get; }

    private McpConnection(McpClient client, IReadOnlyList<McpToolInfo> tools)
    {
        this.client = client;
        Tools = tools;
    }

    public static Uri Endpoint(string address)
    {
        if (!Uri.TryCreate(address.Trim(), UriKind.Absolute, out var uri) || string.IsNullOrWhiteSpace(uri.Host)
            || !(uri.Scheme == Uri.UriSchemeHttps || (uri.Scheme == Uri.UriSchemeHttp && uri.IsLoopback))
            || uri.UserInfo.Length != 0 || uri.Fragment.Length != 0 || uri.Query.Length != 0)
            throw new StarHubException("Use an HTTPS MCP endpoint or local HTTP. Enter any access token separately.");
        return uri;
    }

    public static string CredentialTarget(Uri endpoint) =>
        "StarHub/MCP/" + Convert.ToHexString(SHA256.HashData(Encoding.UTF8.GetBytes(endpoint.AbsoluteUri)));

    public static async Task<McpConnection> ConnectAsync(Uri endpoint, string? credential, CancellationToken cancellationToken)
    {
        endpoint = Endpoint(endpoint.AbsoluteUri);
        using var deadline = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
        deadline.CancelAfter(TimeSpan.FromSeconds(25));
        var http = new HttpClient(new HttpClientHandler { AllowAutoRedirect = false, UseCookies = false, UseProxy = !endpoint.IsLoopback })
        { Timeout = Timeout.InfiniteTimeSpan };
        if (!string.IsNullOrWhiteSpace(credential))
            http.DefaultRequestHeaders.Authorization = new AuthenticationHeaderValue("Bearer", credential);
        var transport = new HttpClientTransport(new HttpClientTransportOptions
        {
            Endpoint = endpoint, TransportMode = HttpTransportMode.StreamableHttp,
            EnableStandaloneGetStream = false, MaxReconnectionAttempts = 0
        }, http, ownsHttpClient: true);
        McpClient? client = null;
        try
        {
            client = await McpClient.CreateAsync(transport, cancellationToken: deadline.Token).ConfigureAwait(false);
            var tools = await client.ListToolsAsync(cancellationToken: deadline.Token).ConfigureAwait(false);
            return new McpConnection(client, tools.Take(150).Select(tool =>
                new McpToolInfo(tool.Name, tool.Description ?? "", tool.JsonSchema.Clone())).ToArray());
        }
        catch
        {
            if (client is null) await transport.DisposeAsync().ConfigureAwait(false);
            else await client.DisposeAsync().ConfigureAwait(false);
            throw;
        }
    }

    public static JsonElement ReadArguments(string input)
    {
        if (input.Length > MaxArguments) throw new StarHubException("Tool arguments must be under 24,000 characters.");
        try
        {
            using var json = JsonDocument.Parse(input);
            if (json.RootElement.ValueKind != JsonValueKind.Object)
                throw new StarHubException("Tool arguments must be a JSON object.");
            return json.RootElement.Clone();
        }
        catch (JsonException)
        {
            throw new StarHubException("Tool arguments must be valid JSON.");
        }
    }

    public async Task<McpResult> CallAsync(string name, string input, CancellationToken cancellationToken)
    {
        if (!Tools.Any(tool => tool.Name == name)) throw new StarHubException("Select a tool listed by this MCP server.");
        var args = ReadArguments(input).EnumerateObject().ToDictionary(property => property.Name,
            property => (object?)property.Value.Clone(), StringComparer.Ordinal);
        using var deadline = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
        deadline.CancelAfter(TimeSpan.FromSeconds(90));
        var result = await client.CallToolAsync(name, args, cancellationToken: deadline.Token).ConfigureAwait(false);
        string text = result.StructuredContent?.GetRawText()
            ?? string.Join("\n", result.Content.OfType<TextContentBlock>().Select(content => content.Text));
        if (text.Length > MaxResult) text = text[..MaxResult] + "\n[Preview limited to 24,000 characters]";
        if (text.Length == 0)
            text = result.Content.Count == 0 ? "The server returned an empty result." : "The server returned content without a text or JSON representation.";
        return new McpResult(result.IsError == true, text);
    }

    public ValueTask DisposeAsync() => client.DisposeAsync();
}
