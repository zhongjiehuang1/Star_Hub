using System.Net;
using System.Net.Http.Headers;
using System.Security.Cryptography;

namespace StarHub.Core;

public interface ILocalQwen
{
    bool IsInstalled { get; }
    Task<ModelRoute> StartAsync(CancellationToken token);
}

public sealed class QwenDownload
{
    public const string Model = "qwen2.5-0.5b-instruct";
    public const string FileName = Model + "-q4_k_m.gguf";
    public const long Bytes = 491_400_032;
    public const string Sha256 = "74a4da8c9fdbcd15bd1f6d01d621410d31c6fc00986f5eb687824e7b93d7a9db";
    private static readonly Uri[] Sources = [
        new(DesktopAccountClient.Website, "downloads/models/" + Sha256 + "/" + FileName),
        new("https://huggingface.co/Qwen/Qwen2.5-0.5B-Instruct-GGUF/resolve/9217f5db79a29953eb74d5343926648285ec7e67/" + FileName)
    ];
    private static readonly HttpClient Client = new(new HttpClientHandler { UseCookies = false })
        { Timeout = Timeout.InfiniteTimeSpan };
    private readonly string folder;
    public string ModelPath => Path.Combine(folder, FileName);
    public bool IsInstalled => File.Exists(ModelPath) && new FileInfo(ModelPath).Length == Bytes;

    public QwenDownload(string dataDirectory) => folder = Path.Combine(dataDirectory, "models");

    public async Task InstallAsync(IProgress<int> progress, CancellationToken token)
    {
        Directory.CreateDirectory(folder);
        using var lease = File.Open(Path.Combine(folder, "qwen-install.lock"), FileMode.OpenOrCreate, FileAccess.Write, FileShare.None);
        if (IsInstalled) return;
        await DownloadVerifiedAsync(Client, Sources, ModelPath, Bytes, Sha256, progress, token).ConfigureAwait(false);
    }

    internal static async Task DownloadVerifiedAsync(HttpClient client, IReadOnlyList<Uri> sources, string destination,
        long bytes, string sha256, IProgress<int> progress, CancellationToken token)
    {
        string partial = destination + ".part";
        foreach (var source in sources)
        {
            try
            {
                await FetchAsync(client, source, partial, bytes, progress, token).ConfigureAwait(false);
                await using (var file = File.OpenRead(partial))
                {
                    var hash = await SHA256.HashDataAsync(file, token).ConfigureAwait(false);
                    if (!Convert.ToHexString(hash).Equals(sha256, StringComparison.OrdinalIgnoreCase))
                        throw new InvalidDataException("Model checksum mismatch.");
                }
                File.Move(partial, destination, overwrite: true);
                progress.Report(100);
                return;
            }
            catch (InvalidDataException) { File.Delete(partial); }
            catch (HttpIOException) when (!token.IsCancellationRequested) { }
            catch (HttpRequestException) when (!token.IsCancellationRequested) { }
            catch (OperationCanceledException) when (!token.IsCancellationRequested) { }
        }
        token.ThrowIfCancellationRequested();
        throw new HttpRequestException("The offline model download could not be completed.");
    }

    private static async Task FetchAsync(HttpClient client, Uri source, string partial, long bytes,
        IProgress<int> progress, CancellationToken token)
    {
        await using var output = new FileStream(partial, FileMode.OpenOrCreate, FileAccess.ReadWrite, FileShare.None,
            128 * 1024, FileOptions.Asynchronous | FileOptions.SequentialScan);
        if (output.Length > bytes) output.SetLength(0);
        long start = output.Length;
        if (start == bytes) return;
        using var request = new HttpRequestMessage(HttpMethod.Get, source);
        if (start > 0) request.Headers.Range = new RangeHeaderValue(start, null);
        using var timeout = CancellationTokenSource.CreateLinkedTokenSource(token);
        timeout.CancelAfter(TimeSpan.FromSeconds(45));
        using var response = await client.SendAsync(request, HttpCompletionOption.ResponseHeadersRead, timeout.Token).ConfigureAwait(false);
        response.EnsureSuccessStatusCode();
        if (response.StatusCode == HttpStatusCode.PartialContent)
        {
            var range = response.Content.Headers.ContentRange;
            if (range?.Unit != "bytes" || range.From != start || range.To != bytes - 1 || range.Length != bytes)
                throw new InvalidDataException("Unexpected model byte range.");
        }
        else if (response.StatusCode == HttpStatusCode.OK) { output.SetLength(0); start = 0; }
        else throw new InvalidDataException("Unexpected model response.");
        if (response.Content.Headers.ContentLength is long count && count != bytes - start)
            throw new InvalidDataException("Unexpected model size.");
        output.Position = start;
        await using var input = await response.Content.ReadAsStreamAsync(timeout.Token).ConfigureAwait(false);
        byte[] buffer = new byte[128 * 1024];
        int previous = -1;
        while (output.Position < bytes)
        {
            timeout.CancelAfter(TimeSpan.FromSeconds(45));
            int read = await input.ReadAsync(buffer, timeout.Token).ConfigureAwait(false);
            if (read == 0) throw new HttpRequestException("The model download was interrupted.");
            if (read > bytes - output.Position) throw new InvalidDataException("The model exceeded its expected size.");
            await output.WriteAsync(buffer.AsMemory(0, read), token).ConfigureAwait(false);
            int percent = (int)(output.Position * 99 / bytes);
            if (percent != previous) { progress.Report(percent); previous = percent; }
        }
        await output.FlushAsync(token).ConfigureAwait(false);
    }
}
