using System.Diagnostics;
using System.Net.Http.Headers;
using System.Net.Http.Json;
using System.Threading.Channels;

namespace StarHub.Core;

public sealed class DesktopDiagnostic : IDisposable
{
    private readonly string token;
    private readonly string operation;
    private readonly string id = Guid.NewGuid().ToString("N");
    private readonly long started = Stopwatch.GetTimestamp();
    private bool finished;

    private DesktopDiagnostic(string token, string operation)
    {
        this.token = token;
        this.operation = operation;
        Record(operation, "started");
    }

    public static DesktopDiagnostic? Start(DesktopAccountProfile? profile, string operation) =>
        profile?.DiagnosticsToken is { Length: >= 40 and <= 1024 } token ? new(token, operation) : null;

    public void Step(string step, bool succeeded, string code = "") =>
        Record(step, succeeded ? "completed" : "failed", code);

    public void Finish(TurnState state, string code = "")
    {
        if (finished) return;
        finished = true;
        Record(operation, state == TurnState.Interrupted ? "cancelled" : state.ToString().ToLowerInvariant(), code);
    }

    public void Dispose() => Finish(TurnState.Failed, "unhandled");

    private void Record(string action, string state, string code) =>
        Sender.Pending.Writer.TryWrite(new(token, new(action, state, id, code,
            typeof(DesktopDiagnostic).Assembly.GetName().Version!.ToString(), Environment.OSVersion.Version.Build,
            (int)Math.Min(3_600_000, Stopwatch.GetElapsedTime(started).TotalMilliseconds))));

    private void Record(string action, string state) => Record(action, state, "");

    private sealed record Entry(string Operation, string State, string Id, string Code, string Version, int WindowsBuild, int ElapsedMs);
    private sealed record PendingEntry(string Token, Entry Payload);

    private static class Sender
    {
        private static readonly HttpClient Client = new(new HttpClientHandler { AllowAutoRedirect = false, UseCookies = false })
        {
            Timeout = TimeSpan.FromSeconds(5)
        };
        internal static readonly Channel<PendingEntry> Pending = Channel.CreateBounded<PendingEntry>(new BoundedChannelOptions(48)
        {
            SingleReader = true, FullMode = BoundedChannelFullMode.DropOldest
        });

        static Sender() => _ = Task.Run(SendAsync);

        private static async Task SendAsync()
        {
            await foreach (var item in Pending.Reader.ReadAllAsync().ConfigureAwait(false))
            {
                try
                {
                    using var request = new HttpRequestMessage(HttpMethod.Post, new Uri(DesktopAccountClient.Website, "api/desktop-diagnostics"))
                    {
                        Content = JsonContent.Create(item.Payload)
                    };
                    request.Headers.Authorization = new AuthenticationHeaderValue("Bearer", item.Token);
                    request.Headers.Add("X-Starhub-Client", "desktop");
                    using var response = await Client.SendAsync(request, HttpCompletionOption.ResponseHeadersRead).ConfigureAwait(false);
                }
                catch (Exception error) when (error is HttpRequestException or OperationCanceledException or FormatException) { }
            }
        }
    }
}
