using System.Text;
using System.Text.RegularExpressions;
using UglyToad.PdfPig;
using UglyToad.PdfPig.DocumentLayoutAnalysis.TextExtractor;

namespace StarHub.Core;

public static partial class KnowledgeDocument
{
    public const int MaxTextLength = 360_000;
    private const int MaxTextBytes = 1_500_000;
    private const int MaxPdfBytes = 12 * 1024 * 1024;
    private const int PassageLength = 1400;
    private const int Overlap = 140;
    public static IReadOnlyList<string> Extensions { get; } = [".txt", ".md", ".csv", ".json", ".log", ".pdf"];

    [GeneratedRegex(@"[\p{IsCJKUnifiedIdeographs}]+|[\p{L}\p{Nd}]+", RegexOptions.CultureInvariant)]
    private static partial Regex Lexemes();

    private static readonly HashSet<string> CommonWords = new(
        ["a", "an", "and", "are", "be", "for", "from", "in", "is", "it", "of", "on", "or", "that", "the", "this", "to", "was", "what", "with"]);

    internal static IEnumerable<string> Terms(string value)
    {
        foreach (Match match in Lexemes().Matches(value))
        {
            string part = match.Value.ToLowerInvariant();
            if (part[0] is >= '\u4e00' and <= '\u9fff')
            {
                if (part.Length == 1) yield return part;
                for (int i = 0; i < part.Length - 1; i++) yield return part.Substring(i, 2);
            }
            else if (part.Length <= 60 && !CommonWords.Contains(part)) yield return part;
        }
    }

    internal static IEnumerable<string> Split(string text)
    {
        for (int offset = 0; offset < text.Length;)
        {
            int end = Math.Min(text.Length, offset + PassageLength);
            if (end < text.Length)
            {
                int boundary = text.LastIndexOfAny(['\n', ' ', '。', '；'], end - 1, 200);
                if (boundary >= 0) end = boundary + 1;
                if (char.IsHighSurrogate(text[end - 1])) end--;
            }
            string passage = text[offset..end].Trim();
            if (passage.Length > 0) yield return passage;
            if (end == text.Length) yield break;
            offset = end - Overlap;
            if (char.IsLowSurrogate(text[offset])) offset++;
        }
    }

    public static async Task<string> ReadAsync(string fileName, Stream input, CancellationToken token = default)
    {
        string extension = Path.GetExtension(fileName).ToLowerInvariant();
        if (!Extensions.Contains(extension)) throw new StarHubException("Choose a text document or a PDF.");
        int limit = extension == ".pdf" ? MaxPdfBytes : MaxTextBytes;
        using var buffer = new MemoryStream();
        byte[] block = new byte[65_536];
        int read;
        while ((read = await input.ReadAsync(block.AsMemory(), token).ConfigureAwait(false)) > 0)
        {
            if (buffer.Length + read > limit)
                throw new StarHubException("Import limits are 1.5 MB for UTF-8 text and 12 MiB for a text-based PDF.");
            buffer.Write(block, 0, read);
        }
        string text;
        try
        {
            var bytes = buffer.ToArray();
            text = extension == ".pdf"
                ? await Task.Run(() => ReadPdf(bytes, token), token).ConfigureAwait(false)
                : new UTF8Encoding(false, true).GetString(bytes).TrimStart('\uFEFF');
        }
        catch (DecoderFallbackException)
        { throw new StarHubException("Save the text file as UTF-8 before importing."); }

        if (string.IsNullOrWhiteSpace(text)) throw new StarHubException("This file has no searchable text. Scanned PDFs need a text layer.");
        if (text.Length > MaxTextLength) throw new StarHubException("The document has more than 360,000 characters. Import it in sections.");
        return text;
    }

    private static string ReadPdf(byte[] bytes, CancellationToken token)
    {
        try
        {
            using var document = PdfDocument.Open(bytes);
            if (document.NumberOfPages > 250) throw new StarHubException("Import PDFs of up to 250 pages.");
            var text = new StringBuilder();
            foreach (var page in document.GetPages())
            {
                token.ThrowIfCancellationRequested();
                text.AppendLine(ContentOrderTextExtractor.GetText(page));
                if (text.Length > MaxTextLength) throw new StarHubException("The PDF has more than 360,000 characters. Import it in sections.");
            }
            return text.ToString();
        }
        catch (Exception error) when (error is not OperationCanceledException and not StarHubException)
        { throw new StarHubException("The PDF could not be read. Check that it opens without a password."); }
    }
}
