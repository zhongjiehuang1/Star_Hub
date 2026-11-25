using System.Text;

namespace StarHub.Core;

public static class Attachments
{
    public static readonly string[] Extensions =
        [".txt", ".md", ".csv", ".json", ".log", ".pdf", ".png", ".jpg", ".jpeg", ".webp"];

    public static async Task<Attachment> ReadAsync(string name, Stream stream, CancellationToken cancellationToken = default)
    {
        string type = Path.GetExtension(name).ToLowerInvariant() switch
        {
            ".pdf" => "application/pdf",
            ".png" => "image/png",
            ".jpg" or ".jpeg" => "image/jpeg",
            ".webp" => "image/webp",
            ".txt" or ".md" or ".csv" or ".json" or ".log" => "text/plain",
            _ => throw new StarHubException("Choose a text, CSV, JSON, PDF, PNG, JPEG or WebP file.")
        };
        int limit = type == "text/plain" ? 96_000 : 6 * 1024 * 1024;
        using var buffer = new MemoryStream();
        var chunk = new byte[65_536];
        int read;
        while ((read = await stream.ReadAsync(chunk, cancellationToken).ConfigureAwait(false)) > 0)
        {
            if (buffer.Length + read > limit)
                throw new StarHubException(type == "text/plain" ? "Text attachments must be under 96 KB." : "PDF and image attachments must be under 6 MiB.");
            buffer.Write(chunk, 0, read);
        }
        var bytes = buffer.ToArray();
        if (bytes.Length == 0) throw new StarHubException("The selected file is empty.");
        if (type == "text/plain")
        {
            try { _ = new UTF8Encoding(false, true).GetCharCount(bytes); }
            catch (DecoderFallbackException) { throw new StarHubException("Save the text file in UTF-8 and attach it again."); }
        }
        return new Attachment(Path.GetFileName(name), type, bytes);
    }
}
