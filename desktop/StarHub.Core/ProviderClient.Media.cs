using System.Net.Http.Headers;
using System.Net.Http.Json;
using System.Text.Json;

namespace StarHub.Core;

public sealed partial class ProviderClient
{
    public static IReadOnlyList<string> AudioExtensions { get; } =
        [".wav", ".mp3", ".mp4", ".m4a", ".mpeg", ".mpga", ".flac", ".ogg", ".webm"];
    public const int MaxAudioBytes = 25_000_000;

    public async Task<string> TranscribeAsync(string key, Stream audio, string fileName, CancellationToken cancellationToken)
    {
        string extension = Path.GetExtension(fileName).ToLowerInvariant();
        if (!AudioExtensions.Contains(extension)) throw new StarHubException("Choose a supported audio file.");
        if (!audio.CanSeek || audio.Length - audio.Position is <= 0 or > MaxAudioBytes)
            throw new StarHubException("Audio must be between one byte and 25 MB.");
        using var request = new MultipartFormDataContent();
        request.Add(new StringContent(ModelCatalog.TranscriptionModel), "model");
        var content = new StreamContent(audio);
        content.Headers.ContentType = new MediaTypeHeaderValue(extension switch
        {
            ".wav" => "audio/wav", ".m4a" or ".mp4" => "audio/mp4", ".ogg" => "audio/ogg",
            ".webm" => "audio/webm", ".flac" => "audio/flac", _ => "audio/mpeg"
        });
        request.Add(content, "file", Path.GetFileName(fileName));
        using var response = await MediaAsync("audio/transcriptions", key, request, 1_000_000, cancellationToken).ConfigureAwait(false);
        var text = String(response.RootElement, "text")?.Trim();
        if (string.IsNullOrEmpty(text)) throw new StarHubException("The provider returned no transcript.");
        return text;
    }

    public async Task<byte[]> SpeakMandarinAsync(string key, string text, CancellationToken cancellationToken)
    {
        using var deadline = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
        deadline.CancelAfter(TimeSpan.FromSeconds(60));
        using var request = new HttpRequestMessage(HttpMethod.Post, new Uri(ChatEngine.OpenAiEndpoint, "audio/speech"))
        {
            Content = JsonContent.Create(new { model = "gpt-4o-mini-tts", voice = "coral", response_format = "mp3",
                input = text[..Math.Min(text.Length, 4000)], instructions = "Read this lesson clearly in standard Mandarin." })
        };
        request.Headers.Authorization = new AuthenticationHeaderValue("Bearer", key);
        using var response = await remote.SendAsync(request, HttpCompletionOption.ResponseHeadersRead, deadline.Token).ConfigureAwait(false);
        if (!response.IsSuccessStatusCode) throw HttpError(response.StatusCode);
        await response.Content.LoadIntoBufferAsync(8 * 1024 * 1024, deadline.Token).ConfigureAwait(false);
        var audio = await response.Content.ReadAsByteArrayAsync(deadline.Token).ConfigureAwait(false);
        if (audio.Length == 0) throw new StarHubException("No speech audio was returned.");
        return audio;
    }

    public async Task<byte[]> MakeImageAsync(string key, string model, string prompt, Attachment? input,
        CancellationToken cancellationToken)
    {
        prompt = prompt.Trim();
        if (prompt.Length is 0 or > 10_000) throw new StarHubException("Enter an image prompt of up to 10,000 characters.");
        if (!ModelCatalog.ImageModels.Any(image => image.Id == model))
            throw new StarHubException("Choose one of the available OpenAI image models.");

        using HttpContent body = input is null ? JsonContent.Create(new { model, prompt, output_format = "png" })
            : ImageEditContent(model, prompt, input);
        using var response = await MediaAsync(input is null ? "images/generations" : "images/edits",
            key, body, 28_000_000, cancellationToken).ConfigureAwait(false);
        if (!response.RootElement.TryGetProperty("data", out var list) || list.ValueKind != JsonValueKind.Array
            || list.GetArrayLength() == 0 || !list[0].TryGetProperty("b64_json", out var bytes)
            || bytes.ValueKind != JsonValueKind.String || !bytes.TryGetBytesFromBase64(out var png)
            || !png.AsSpan().StartsWith(new byte[] { 0x89, 0x50, 0x4e, 0x47, 13, 10, 26, 10 }))
            throw new StarHubException("The provider returned no supported PNG image.");
        return png;
    }

    private static MultipartFormDataContent ImageEditContent(string model, string prompt, Attachment image)
    {
        if (!image.IsImage || image.Bytes.Length is 0 or > 6 * 1024 * 1024)
            throw new StarHubException("Choose a PNG, JPEG or WebP image up to 6 MiB.");
        var request = new MultipartFormDataContent();
        request.Add(new StringContent(model), "model");
        request.Add(new StringContent(prompt), "prompt");
        request.Add(new StringContent("png"), "output_format");
        var content = new ByteArrayContent(image.Bytes);
        content.Headers.ContentType = new MediaTypeHeaderValue(image.MediaType);
        request.Add(content, "image[]", image.Name);
        return request;
    }

    private async Task<JsonDocument> MediaAsync(string path, string key, HttpContent body, int maxBytes, CancellationToken cancellationToken)
    {
        using var timeout = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
        timeout.CancelAfter(TimeSpan.FromMinutes(4));
        var token = timeout.Token;
        using var request = new HttpRequestMessage(HttpMethod.Post, new Uri(ChatEngine.OpenAiEndpoint, path)) { Content = body };
        request.Headers.Authorization = new AuthenticationHeaderValue("Bearer", key);
        using var response = await remote.SendAsync(request, HttpCompletionOption.ResponseHeadersRead, token).ConfigureAwait(false);
        if (!response.IsSuccessStatusCode) throw HttpError(response.StatusCode);
        using var input = await response.Content.ReadAsStreamAsync(token).ConfigureAwait(false);
        using var buffer = new MemoryStream();
        var chunk = new byte[65_536];
        int read;
        while ((read = await input.ReadAsync(chunk, token).ConfigureAwait(false)) > 0)
        {
            if (buffer.Length + read > maxBytes) throw new StarHubException("The provider output exceeded the local size limit.");
            buffer.Write(chunk, 0, read);
        }
        buffer.Position = 0;
        return JsonDocument.Parse(buffer);
    }
}
