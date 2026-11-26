using System.Net;
using System.Net.Http.Headers;
using System.Security.Cryptography;
using StarHub.Core;
using Xunit;

namespace StarHub.Tests;

public class OfflineUpgradeTests
{
    private sealed class Handler(Func<HttpRequestMessage, HttpResponseMessage> respond) : HttpMessageHandler
    {
        protected override Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken token) =>
            Task.FromResult(respond(request));
    }
    private sealed class ProgressSink : IProgress<int>
    {
        public int Last;
        public void Report(int value) => Last = value;
    }

    [Theory]
    [InlineData(true)]
    [InlineData(false)]
    public async Task ResumeAlsoWorksWhenServerReturnsTheEntireFile(bool honorsRange)
    {
        byte[] content = "Star Hub download fixture"u8.ToArray();
        string dir = Directory.CreateTempSubdirectory().FullName;
        try
        {
            string file = Path.Combine(dir, "model.gguf");
            await File.WriteAllBytesAsync(file + ".part", content[..5]);
            using var http = new HttpClient(new Handler(request =>
            {
                Assert.Equal(5, request.Headers.Range!.Ranges.Single().From);
                var response = new HttpResponseMessage(honorsRange ? HttpStatusCode.PartialContent : HttpStatusCode.OK)
                    { Content = new ByteArrayContent(honorsRange ? content[5..] : content) };
                if (honorsRange) response.Content.Headers.ContentRange = new ContentRangeHeaderValue(5, content.Length - 1, content.Length);
                return response;
            }));
            var progress = new ProgressSink();
            await QwenDownload.DownloadVerifiedAsync(http, [new("https://example.test/model")], file, content.Length,
                Convert.ToHexString(SHA256.HashData(content)), progress, default);
            Assert.Equal(content, await File.ReadAllBytesAsync(file));
            Assert.False(File.Exists(file + ".part"));
            Assert.Equal(100, progress.Last);
        }
        finally { Directory.Delete(dir, true); }
    }

    [Theory]
    [InlineData(true)]
    [InlineData(false)]
    public async Task BadHashOrWrongRangeNeverInstalls(bool badHash)
    {
        var root = Directory.CreateTempSubdirectory();
        try
        {
            string dest = Path.Combine(root.FullName, "model");
            using var http = new HttpClient(new Handler(_ =>
            {
                var response = new HttpResponseMessage(badHash ? HttpStatusCode.OK : HttpStatusCode.PartialContent)
                    { Content = new ByteArrayContent([2, 3]) };
                if (!badHash) response.Content.Headers.ContentRange = new ContentRangeHeaderValue(2, 3, 4);
                return response;
            }));
            await Assert.ThrowsAsync<HttpRequestException>(() => QwenDownload.DownloadVerifiedAsync(http,
                [new("https://example.test/file")], dest, 2, new string('0', 64), new ProgressSink(), default));
            Assert.False(File.Exists(dest));
            Assert.False(File.Exists(dest + ".part"));
        }
        finally { root.Delete(true); }
    }

    [Fact]
    public void RepairMustMatchTheExistingAccountAndOwner()
    {
        string owner = Guid.NewGuid().ToString();
        var legacy = new DesktopAccountProfile { Email = "a@example.test", DiagnosticsToken = "diagnostic-only" };
        var active = new DesktopAccountProfile { Email = "A@example.test", MemoryOwner = owner, MemoryToken = "scoped" };
        Assert.False(legacy.CanRestoreMemory);
        Assert.True(active.CanRestoreMemory);
        Assert.True(legacy.MatchesConnection(active));
        Assert.True(active.MatchesConnection(active.WithDesktopName("Alice")));
        Assert.False(active.MatchesConnection(new() { Email = active.Email, MemoryOwner = Guid.NewGuid().ToString() }));
        Assert.False(legacy.MatchesConnection(new() { Email = "b@example.test", MemoryOwner = owner }));
    }
}
