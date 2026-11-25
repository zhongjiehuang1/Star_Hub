using System.Security.Cryptography;
using System.Text;
using System.Text.RegularExpressions;

namespace StarHub.Core;

public sealed class PcWorkspace
{
    private const int TextLimit = 48 * 1024;
    private const long FileLimit = 256L * 1024 * 1024;
    private static readonly UTF8Encoding Utf8 = new(encoderShouldEmitUTF8Identifier: false, throwOnInvalidBytes: true);
    private static readonly HashSet<string> TextExtensions = new(StringComparer.OrdinalIgnoreCase)
        { ".txt", ".md", ".csv", ".tsv", ".json", ".log" };
    private static readonly Regex ReservedName = new(@"^(CON|PRN|AUX|NUL|CONIN\$|CONOUT\$|COM[1-9¹²³]|LPT[1-9¹²³])(?:\.|$)",
        RegexOptions.IgnoreCase | RegexOptions.CultureInvariant);
    private readonly string root;
    private readonly bool allowDrivePaths;

    public PcWorkspace(string folder, bool allowDrivePaths = false)
    {
        this.allowDrivePaths = allowDrivePaths;
        if (!Path.IsPathFullyQualified(folder))
            throw new StarHubException("Choose a local folder for this Windows task.");
        root = Path.TrimEndingDirectorySeparator(Path.GetFullPath(folder));
        var directory = new DirectoryInfo(root);
        if ((!allowDrivePaths && directory.Parent is null) || root.StartsWith(@"\\", StringComparison.Ordinal) || !directory.Exists)
            throw new StarHubException("Choose an existing local folder inside a drive for this Windows task.");
        if (OperatingSystem.IsWindows() && new DriveInfo(directory.Root.FullName).DriveType == DriveType.Network)
            throw new StarHubException("Choose a local folder for this Windows task.");
        if (!allowDrivePaths)
            for (DirectoryInfo? current = directory; current is not null; current = current.Parent)
                RejectLinks(current.FullName);
    }

    public PcTaskResult List(string relative)
    {
        var path = Folder(relative);
        var files = new List<object>();
        int count = 0;
        foreach (var entry in new DirectoryInfo(path).EnumerateFileSystemInfos().Take(151))
        {
            count++;
            if (count > 150) break;
            var attributes = entry.Attributes;
            if (!allowDrivePaths && (attributes & FileAttributes.ReparsePoint) != 0) continue;
            files.Add(new { name = entry.Name, directory = entry is DirectoryInfo, bytes = (entry as FileInfo)?.Length });
        }
        return new(true, $"Listed folder: {path}", new { path, entries = files, truncated = count > 150 });
    }

    public PcTaskResult Find(string folder, string name, CancellationToken token)
    {
        if (string.IsNullOrWhiteSpace(name) || name.IndexOfAny(['*', '?', '/', '\\']) >= 0)
            throw new StarHubException("Give a filename or part of a filename to search.");
        var path = Folder(folder);
        var options = new EnumerationOptions
        {
            RecurseSubdirectories = true, IgnoreInaccessible = true, AttributesToSkip = FileAttributes.ReparsePoint
        };
        var matches = new List<string>();
        foreach (var entry in Directory.EnumerateFileSystemEntries(path, "*", options))
        {
            token.ThrowIfCancellationRequested();
            if (!Path.GetFileName(entry).Contains(name, StringComparison.OrdinalIgnoreCase)) continue;
            matches.Add(entry);
            if (matches.Count > 100) break;
        }
        return new(true, $"Found {Math.Min(matches.Count, 100)} matches in {path}",
            new { matches = matches.Take(100).ToArray(), truncated = matches.Count > 100 });
    }

    public async Task<PcTaskResult> ReadFileAsync(string requested, CancellationToken token)
    {
        var path = LocalPath(requested);
        using var file = File.OpenRead(path);
        long bytes = file.Length;
        string contents;
        bool truncated;
        if (Path.GetExtension(path).Equals(".pdf", StringComparison.OrdinalIgnoreCase))
        {
            contents = await KnowledgeDocument.ReadAsync(path, file, token).ConfigureAwait(false);
            truncated = contents.Length > TextLimit;
        }
        else
        {
            using var reader = new StreamReader(file, Utf8, detectEncodingFromByteOrderMarks: true);
            char[] buffer = new char[TextLimit + 1];
            int count = await reader.ReadBlockAsync(buffer.AsMemory(), token).ConfigureAwait(false);
            contents = new string(buffer, 0, count);
            if (contents.Any(character => char.IsControl(character) && character is not ('\r' or '\n' or '\t' or '\f')))
                throw new StarHubException("This file needs its associated application to read its contents.");
            truncated = count > TextLimit;
        }
        string? hash = truncated ? null : Convert.ToHexString(await HashAsync(path, token).ConfigureAwait(false)).ToLowerInvariant();
        return new(true, $"Read {path} ({bytes} bytes){(truncated ? " · text preview" : "")}",
            new PcFileContent(path, Clip(contents, TextLimit), bytes, truncated, hash));
    }

    public PcTaskResult Inspect(string requested)
    {
        var path = LocalPath(requested, allowRoot: true);
        var attributes = File.GetAttributes(path);
        bool directory = (attributes & FileAttributes.Directory) != 0;
        long? bytes = directory ? null : new FileInfo(path).Length;
        return new(true, $"Verified path: {path}", new { path, directory, bytes, updated = File.GetLastWriteTime(path) });
    }

    private static string Clip(string text, int length) => text.Length <= length ? text :
        text[..(char.IsHighSurrogate(text[length - 1]) ? length - 1 : length)];

    public PcTaskResult ReadText(string relative)
    {
        var path = TextFile(relative);
        using var file = File.Open(path, FileMode.Open, FileAccess.Read, FileShare.Read);
        if (file.Length > TextLimit) throw new StarHubException("The text file exceeds 48 KB.");
        byte[] bytes = new byte[(int)file.Length];
        file.ReadExactly(bytes);
        return new(true, $"Read {relative} ({bytes.Length} bytes)", new { text = Utf8.GetString(bytes).TrimStart('\uFEFF') });
    }

    public PcTaskResult MakeFolder(string relative)
    {
        var target = Resolve(relative, allowRoot: false);
        CheckParent(target);
        if (File.Exists(target)) throw new StarHubException("A file already uses this folder name.");
        bool existing = Directory.Exists(target);
        Directory.CreateDirectory(target);
        Folder(relative);
        return new(true, existing ? $"Folder already exists: {relative}" : $"Created and verified folder: {relative}");
    }

    public async Task<PcTaskResult> WriteTextAsync(string relative, string text, CancellationToken token)
    {
        var target = TextFile(relative, mustExist: false);
        byte[] data = Utf8.GetBytes(text);
        if (data.Length > TextLimit) throw new StarHubException("The text exceeds 48 KB.");
        using var bytes = new MemoryStream(data, writable: false);
        await CopyNewAsync(bytes, target, token).ConfigureAwait(false);
        return new(true, $"Saved and verified text: {target} ({data.Length} bytes)");
    }

    public async Task<PcTaskResult> ReplaceTextAsync(string path, string text, string expectedSha, CancellationToken token)
    {
        var target = TextFile(path);
        var expected = await HashAsync(target, token).ConfigureAwait(false);
        if (!Convert.ToHexString(expected).Equals(expectedSha, StringComparison.OrdinalIgnoreCase))
            throw new StarHubException("The file changed. Read it again before editing.");
        byte[] buffer = Utf8.GetBytes(text);
        if (buffer.Length > TextLimit) throw new StarHubException("The text exceeds 48 KB.");
        string staged = Path.Combine(Path.GetDirectoryName(target)!, $".starhub-{Guid.NewGuid():N}.tmp");
        using var input = new MemoryStream(buffer, writable: false);
        await CopyNewAsync(input, staged, token).ConfigureAwait(false);
        try
        {
            var current = await HashAsync(target, token).ConfigureAwait(false);
            if (!current.AsSpan().SequenceEqual(expected)) throw new StarHubException("The file changed. Read it again before editing.");
            token.ThrowIfCancellationRequested();
            File.Replace(staged, target, null);
            var actual = await HashAsync(target, CancellationToken.None).ConfigureAwait(false);
            if (!actual.AsSpan().SequenceEqual(SHA256.HashData(buffer)))
                throw new StarHubException("The saved file failed its verification check.");
            return new(true, $"Updated and verified: {target}");
        }
        finally { File.Delete(staged); }
    }

    private static async Task<byte[]> HashAsync(string path, CancellationToken token)
    {
        using var opened = File.OpenRead(path);
        return await SHA256.HashDataAsync(opened, token).ConfigureAwait(false);
    }

    public async Task<PcTaskResult> TransferAsync(string relative, string destination, bool move, CancellationToken token)
    {
        var source = Resolve(relative, allowRoot: false);
        var target = Resolve(destination, allowRoot: allowDrivePaths);
        if (allowDrivePaths && Directory.Exists(target)) target = Path.Combine(target, Path.GetFileName(source));
        if (!Path.GetExtension(source).Equals(Path.GetExtension(target), StringComparison.OrdinalIgnoreCase))
            throw new StarHubException("Keep the original file extension when copying or moving a file.");
        CheckParent(target);
        using var file = File.Open(source, FileMode.Open, FileAccess.Read, move ? FileShare.Read | FileShare.Delete : FileShare.Read);
        if (!allowDrivePaths && file.Length > FileLimit) throw new StarHubException("The file exceeds 256 MB.");
        if (move)
        {
            var original = await SHA256.HashDataAsync(file, token).ConfigureAwait(false);
            token.ThrowIfCancellationRequested();
            File.Move(source, target, overwrite: false);
            using var moved = File.OpenRead(target);
            byte[] actual = await SHA256.HashDataAsync(moved, CancellationToken.None).ConfigureAwait(false);
            bool verified = !File.Exists(source) && actual.AsSpan().SequenceEqual(original);
            return new(verified, verified ? $"Moved and verified: {source} → {target}" : $"Moved file, but verification failed: {target}");
        }
        await CopyNewAsync(file, target, token).ConfigureAwait(false);
        return new(true, $"Copied and verified: {source} → {target}");
    }

    public string Folder(string relative)
    {
        var path = Resolve(relative, allowRoot: true);
        if (!Directory.Exists(path)) throw new StarHubException("The folder does not exist.");
        return path;
    }

    public string TextFile(string relative, bool mustExist = true)
    {
        var path = Resolve(relative, allowRoot: false);
        if (!TextExtensions.Contains(Path.GetExtension(path)))
            throw new StarHubException("Choose a TXT, MD, CSV, TSV, JSON or LOG file.");
        CheckParent(path);
        if (mustExist && !File.Exists(path)) throw new StarHubException("The text file does not exist.");
        return path;
    }

    private string Resolve(string relative, bool allowRoot)
    {
        if (allowDrivePaths) return ResolveDrivePath(relative, allowRoot);
        RejectLinks(root);
        if (relative == "." && allowRoot) return root;
        string path = root;
        foreach (string segment in relative.Split('/', '\\'))
        {
            if (segment.Length == 0 || segment.EndsWith('.') || segment.EndsWith(' ') ||
                segment.Any(c => char.IsControl(c) || "<>:\"|?*".Contains(c)) || ReservedName.IsMatch(segment))
                throw new StarHubException("Use a relative path inside the selected folder.");
            path = Path.Combine(path, segment);
            RejectLinks(path);
        }
        return path;
    }

    public string LocalPath(string path, bool allowRoot = false) => Resolve(path, allowRoot);

    private string ResolveDrivePath(string path, bool allowRoot)
    {
        if (string.IsNullOrWhiteSpace(path)) throw new StarHubException("Use a complete local file or folder path.");
        path = path.Replace(Path.AltDirectorySeparatorChar, Path.DirectorySeparatorChar);
        if (path.StartsWith(@"\\", StringComparison.Ordinal) ||
            Path.IsPathRooted(path) && !Path.IsPathFullyQualified(path))
            throw new StarHubException("Use a complete local file or folder path.");
        string full = Path.TrimEndingDirectorySeparator(Path.GetFullPath(path.Replace('\\', Path.DirectorySeparatorChar), root));
        var volume = Path.GetPathRoot(full)!;
        if (OperatingSystem.IsWindows() && new DriveInfo(volume).DriveType == DriveType.Network)
            throw new StarHubException("Use a path on a local drive.");
        var segments = full[volume.Length..].Split(Path.DirectorySeparatorChar, StringSplitOptions.RemoveEmptyEntries);
        if (segments.Length == 0 && !allowRoot || segments.Any(segment => segment.EndsWith('.') || segment.EndsWith(' ') ||
            segment.Any(c => char.IsControl(c) || "<>:\"|?*".Contains(c)) || ReservedName.IsMatch(segment)))
            throw new StarHubException("Use a valid local file or folder path.");
        return full;
    }

    private static void CheckParent(string path)
    {
        if (!Directory.Exists(Path.GetDirectoryName(path)))
            throw new StarHubException("The parent folder does not exist.");
    }

    private static void RejectLinks(string path)
    {
        try
        {
            if ((File.GetAttributes(path) & FileAttributes.ReparsePoint) != 0)
                throw new StarHubException("Windows tasks do not open symbolic links, junctions or other reparse points.");
        }
        catch (FileNotFoundException) { }
        catch (DirectoryNotFoundException) { }
    }

    private static async Task CopyNewAsync(Stream source, string destination, CancellationToken token)
    {
        if (File.Exists(destination) || Directory.Exists(destination))
            throw new StarHubException("The destination already exists.");
        string temporary = Path.Combine(Path.GetDirectoryName(destination)!, $".starhub-{Guid.NewGuid():N}.tmp");
        bool created = false;
        try
        {
            using (var output = new FileStream(temporary, FileMode.CreateNew, FileAccess.Write, FileShare.None))
            {
                created = true;
                await source.CopyToAsync(output, token).ConfigureAwait(false);
            }
            source.Position = 0;
            byte[] expected = await SHA256.HashDataAsync(source, token).ConfigureAwait(false);
            using (var saved = File.OpenRead(temporary))
            {
                byte[] actual = await SHA256.HashDataAsync(saved, token).ConfigureAwait(false);
                if (!actual.AsSpan().SequenceEqual(expected))
                    throw new StarHubException("The saved file failed its verification check.");
            }
            token.ThrowIfCancellationRequested();
            File.Move(temporary, destination, overwrite: false);
            created = false;
        }
        finally { if (created) File.Delete(temporary); }
    }
}

public sealed record PcFileContent(string Path, string Text, long Bytes, bool Truncated, string? Sha256);
