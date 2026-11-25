using System.Net;
using System.Net.Http.Json;
using System.Net.Sockets;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;

namespace StarHub.Core;

public sealed class DesktopAccountProfile
{
    public string Email { get; init; } = "";
    public string? DisplayName { get; init; }
    public string? DesktopDisplayName { get; init; }
    public string Model { get; init; } = "";
    public string? ApiKey { get; init; }

    [System.Text.Json.Serialization.JsonIgnore]
    public string? Name => ValidName(DesktopDisplayName) ?? ValidName(DisplayName);

    private static string? ValidName(string? value) => value is { Length: > 0 and <= 240 }
        && !value.Any(char.IsControl) && !string.IsNullOrWhiteSpace(value) ? value.Trim() : null;

    public DesktopAccountProfile WithDesktopName(string? name) => new()
    {
        Email = Email, Model = Model, ApiKey = ApiKey, DisplayName = DisplayName, DesktopDisplayName = ValidName(name)
    };

    public bool IsValid() => Email is { Length: > 0 and <= 254 } && !Email.Any(char.IsControl) &&
        Model is { Length: > 0 and <= 160 } && Model.All(c => char.IsAsciiLetterOrDigit(c) || "_.:-".Contains(c)) &&
        (ApiKey is null || ApiKey is { Length: > 0 and <= 4096 } && ApiKey.All(c => c is >= '!' and <= '~'));
}

public sealed class DesktopAccountClient
{
    public static readonly Uri Website = new("https://starhub.zhongjiehuang.com/");
    private const string CallbackPath = "/starhub/connect";
    private static readonly JsonSerializerOptions WebJson = new(JsonSerializerDefaults.Web);

    public async Task<DesktopAccountProfile> ConnectAsync(Action<Uri> openBrowser, CancellationToken token)
    {
        using var deadline = CancellationTokenSource.CreateLinkedTokenSource(token);
        deadline.CancelAfter(TimeSpan.FromMinutes(4));
        var listener = new TcpListener(IPAddress.Loopback, 0);
        listener.Start(8);
        int port = ((IPEndPoint)listener.LocalEndpoint).Port;
        string state = RandomToken(), verifier = RandomToken();
        string challenge = Base64Url(SHA256.HashData(Encoding.ASCII.GetBytes(verifier)));
        try
        {
            openBrowser(new Uri(Website, $"?desktopPort={port}&desktopState={state}&desktopChallenge={challenge}"));
            string code = await ReceiveCodeAsync(listener, port, state, deadline.Token).ConfigureAwait(false);
            if (code.Length == 0) throw new StarHubException("The browser connection was cancelled.");
            using var client = new HttpClient(new HttpClientHandler { AllowAutoRedirect = false, UseCookies = false })
            {
                Timeout = TimeSpan.FromSeconds(30),
                MaxResponseContentBufferSize = 16 * 1024
            };
            using var request = new HttpRequestMessage(HttpMethod.Post, new Uri(Website, "api/desktop-connection"))
            {
                Content = JsonContent.Create(new { code, verifier })
            };
            request.Headers.Add("X-Starhub-Client", "desktop");
            using var response = await client.SendAsync(request, deadline.Token).ConfigureAwait(false);
            if (response.StatusCode == HttpStatusCode.Unauthorized)
                throw new StarHubException("The browser approval expired. Connect again.");
            if (!response.IsSuccessStatusCode)
                throw new StarHubException("Star Hub could not read your account settings. Try again.");
            var profile = await response.Content.ReadFromJsonAsync<DesktopAccountProfile>(WebJson, deadline.Token).ConfigureAwait(false);
            if (profile is null || !profile.IsValid())
                throw new StarHubException("Star Hub returned invalid account settings.");
            return profile;
        }
        catch (OperationCanceledException) when (!token.IsCancellationRequested)
        {
            throw new StarHubException("The browser connection timed out. Connect again.");
        }
        finally { listener.Stop(); }
    }

    private static async Task<string> ReceiveCodeAsync(TcpListener listener, int port, string state, CancellationToken token)
    {
        while (true)
        {
            using var client = await listener.AcceptTcpClientAsync(token).ConfigureAwait(false);
            using var requestDeadline = CancellationTokenSource.CreateLinkedTokenSource(token);
            requestDeadline.CancelAfter(TimeSpan.FromSeconds(3));
            using var stream = client.GetStream();
            try
            {
                string? headers = await ReceiveHeadersAsync(stream, requestDeadline.Token).ConfigureAwait(false);
                var callback = headers is null ? null : ParseCallback(headers, port, state);
                await ReplyAsync(stream, callback is not null, requestDeadline.Token).ConfigureAwait(false);
                if (callback is null) continue;
                return callback;
            }
            catch (OperationCanceledException) when (requestDeadline.IsCancellationRequested && !token.IsCancellationRequested) { }
            catch (IOException) when (!token.IsCancellationRequested) { }
        }
    }

    private static async Task<string?> ReceiveHeadersAsync(NetworkStream stream, CancellationToken token)
    {
        byte[] bytes = new byte[8192];
        int size = 0;
        while (size < bytes.Length)
        {
            int count = await stream.ReadAsync(bytes.AsMemory(size), token).ConfigureAwait(false);
            if (count == 0) break;
            size += count;
            var end = bytes.AsSpan(0, size).IndexOf("\r\n\r\n"u8);
            if (end >= 0) return Encoding.ASCII.GetString(bytes, 0, end);
        }
        return null;
    }

    internal static string? ParseCallback(string headers, int port, string state)
    {
        string[] lines = headers.Split("\r\n", StringSplitOptions.None);
        string[] request = lines[0].Split(' ');
        if (request.Length != 3 || request[0] != "GET" || !request[1].StartsWith(CallbackPath + "?", StringComparison.Ordinal) ||
            !lines.Skip(1).Any(line => line.Equals($"Host: 127.0.0.1:{port}", StringComparison.OrdinalIgnoreCase))) return null;
        string? receivedState = null, code = null, error = null;
        foreach (string field in request[1][(CallbackPath.Length + 1)..].Split('&'))
        {
            string[] value = field.Split('=', 2);
            if (value.Length != 2) return null;
            switch (value[0])
            {
                case "state" when receivedState is null: receivedState = value[1]; break;
                case "code" when code is null: code = value[1]; break;
                case "error" when error is null: error = value[1]; break;
                default: return null;
            }
        }
        if (receivedState is null || !CryptographicOperations.FixedTimeEquals(
                Encoding.ASCII.GetBytes(state), Encoding.ASCII.GetBytes(receivedState))) return null;
        if (error == "cancelled" && code is null) return "";
        return error is null && code is { Length: >= 40 and <= 2048 } &&
            code.All(c => char.IsAsciiLetterOrDigit(c) || c is '-' or '_') ? code : null;
    }

    private static Task ReplyAsync(NetworkStream stream, bool accepted, CancellationToken token)
    {
        string status = accepted ? "200 OK" : "400 Bad Request";
        string body = accepted ? "Return to Star Hub for Windows to finish connecting." : "Invalid Star Hub connection.";
        byte[] response = Encoding.ASCII.GetBytes($"HTTP/1.1 {status}\r\nContent-Type: text/plain; charset=utf-8\r\n" +
            $"Content-Length: {body.Length}\r\nCache-Control: no-store\r\nReferrer-Policy: no-referrer\r\nConnection: close\r\n\r\n{body}");
        return stream.WriteAsync(response.AsMemory(), token).AsTask();
    }

    private static string RandomToken() => Base64Url(RandomNumberGenerator.GetBytes(32));

    private static string Base64Url(byte[] bytes) => Convert.ToBase64String(bytes).TrimEnd('=').Replace('+', '-').Replace('/', '_');
}
