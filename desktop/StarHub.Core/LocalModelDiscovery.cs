using System.Net.Http.Json;
using System.Text.Json;

namespace StarHub.Core;

public sealed partial class ProviderClient
{
    // In automatic mode a saved model name is a preference. Check what the local runtime actually serves.
    public async Task<Preferences> FindLocalModelAsync(Preferences settings, CancellationToken token)
    {
        var available = settings with { OllamaModel = "" };
        bool customLocal = Uri.TryCreate(settings.CompatibleEndpoint, UriKind.Absolute, out var configured) &&
            configured.IsLoopback && configured.Scheme is "http" or "https";
        if (customLocal) available = available with { CompatibleEndpoint = "", CompatibleModel = "" };
        if (settings.OllamaPort is > 0 and < 65536)
        {
            var model = await InstalledModelAsync($"http://127.0.0.1:{settings.OllamaPort}/api/tags",
                "models", "name", settings.OllamaModel, token).ConfigureAwait(false);
            if (model is not null) return available with { OllamaModel = model };
        }

        const string standard = "http://127.0.0.1:8080/v1/";
        string endpoint = customLocal ? ChatEngine.CompatibleEndpoint(settings.CompatibleEndpoint).AbsoluteUri : standard;
        var compatible = await InstalledModelAsync(endpoint + "models", "data", "id",
            customLocal ? settings.CompatibleModel : "", token).ConfigureAwait(false);
        if (compatible is null && endpoint != standard)
        {
            endpoint = standard;
            compatible = await InstalledModelAsync(endpoint + "models", "data", "id", "", token).ConfigureAwait(false);
        }
        return compatible is null ? available : available with { CompatibleEndpoint=endpoint, CompatibleModel=compatible };
    }

    private async Task<string?> InstalledModelAsync(string uri, string list, string field, string preferred, CancellationToken token)
    {
        using var timeout = CancellationTokenSource.CreateLinkedTokenSource(token);
        timeout.CancelAfter(TimeSpan.FromSeconds(2));
        try
        {
            using var response = await local.GetAsync(uri,HttpCompletionOption.ResponseHeadersRead,timeout.Token).ConfigureAwait(false);
            if (!response.IsSuccessStatusCode) return null;
            await response.Content.LoadIntoBufferAsync(64 * 1024,timeout.Token).ConfigureAwait(false);
            var json = await response.Content.ReadFromJsonAsync<JsonElement>(timeout.Token).ConfigureAwait(false);
            if (!json.TryGetProperty(list,out var items) || items.ValueKind!=JsonValueKind.Array) return null;
            string? qwen = null;
            foreach (var item in items.EnumerateArray())
            {
                if (item.ValueKind != JsonValueKind.Object || !item.TryGetProperty(field,out var id) || id.ValueKind != JsonValueKind.String ||
                    id.GetString() is not { Length: >0 and <=160 } name || name.Contains("cloud",StringComparison.OrdinalIgnoreCase)) continue;
                if (preferred.Length>0 && (name.Equals(preferred,StringComparison.OrdinalIgnoreCase) ||
                    name.Equals(preferred+":latest",StringComparison.OrdinalIgnoreCase))) return name;
                if (name.StartsWith("qwen",StringComparison.OrdinalIgnoreCase) &&
                    !name.Contains("embed",StringComparison.OrdinalIgnoreCase) && !name.Contains("rerank",StringComparison.OrdinalIgnoreCase))
                    qwen ??= name;
            }
            return qwen;
        }
        catch (Exception e) when (!token.IsCancellationRequested && e is HttpRequestException or OperationCanceledException or JsonException or IOException or InvalidOperationException) { return null; }
    }
}
