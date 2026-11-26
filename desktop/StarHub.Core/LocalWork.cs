using System.Globalization;
using System.Security.Cryptography;
using System.Text.RegularExpressions;
using MetadataExtractor;
using MetadataExtractor.Formats.Exif;
using Directory = System.IO.Directory;

namespace StarHub.Core;

public enum WorkKind { CostWorkbook, ProductSummary, PhotoArchive, Resume }

public sealed record WorkRequest(WorkKind Kind, string Input, string Output);
public sealed record WorkReceipt(string Path, int Count, int NeedsReview, bool Directory = false);
public sealed record WorkCheckpoint(string OperationId, string ConversationId, WorkRequest Request, WorkReceipt? Receipt);

public static class LocalWork
{
    private const RegexOptions Match = RegexOptions.IgnoreCase | RegexOptions.CultureInvariant;
    private static readonly Regex Avoid = new(
        @"(?:不要|别|不用|无需)\s*(?:帮我\s*)?(?:做|制作|生成|创建|整理|归档|汇总|继续)|\b(?:do not|don't|how to|how (?:do|does|can|should))\b|^(?:如何|怎么|为什么|如果|假如|请问怎么)",
        Match);
    private static readonly Regex FollowUp = new(
        @"^(?:(?:请|帮我|请帮我|麻烦|再)\s*)*(?:(?:把|将)\s*(?:刚才|刚刚|上次)\s*(?:的)?\s*(?:那份|那张|这份|这个)?\s*(?:表格|成本表|工作簿|表|Excel)?\s*(?:按|按照)\s*产品\s*汇总|summarize (?:the |that )?(?:last |previous )?(?:workbook|spreadsheet|excel file) by product)[。.!！?？\s]*$",
        Match);
    private static readonly Regex Resume = new(
        @"^(?:(?:请|帮我|请帮我)\s*)?(?:继续(?:刚才的|上次的|中断的|这个)?任务|恢复(?:刚才的|上次的|中断的)?任务|resume (?:the )?(?:last |previous |interrupted )?task)[。.!！?？\s]*$",
        Match);
    private static readonly Regex Destination = new(
        @"(?:保存到|保存至|另存为|存到|存至|放到|放在|输出到|\b(?:save|export)(?: it)? (?:to|in|as)\b)\s*(?<target>[^，。！？；\r\n]+)",
        Match);
    private static readonly Regex FolderName = new(@"^(?:the )?(?<name>桌面|我的文档|文档|下载|desktop|documents|downloads)(?:文件夹| folder)?[。.!！?？\s]*$", Match);

    public static WorkKind? Identify(string prompt)
    {
        string text = prompt.Trim();
        if (text.Length > 1500 || Avoid.IsMatch(text)) return null;
        if (FollowUp.IsMatch(text)) return WorkKind.ProductSummary;
        if (Resume.IsMatch(text)) return WorkKind.Resume;
        if (Regex.IsMatch(text, @"领料|耗料|工时|成本(?:核算)?(?:表|工作簿)|\b(?:production cost|materials?|machine hours|cost workbook)\b", Match) &&
            Regex.IsMatch(text, @"(?:做|制作|制成|生成|创建|汇成|整理成|导出)|\b(?:make|build|create|generate|export)\b", Match) &&
            Regex.IsMatch(text, @"Excel|xlsx|成本表|电子表格|工作簿|spreadsheet|workbook", Match)) return WorkKind.CostWorkbook;
        if (Regex.IsMatch(text, @"照片|图片|\b(?:photos?|pictures?|images?)\b", Match) &&
            Regex.IsMatch(text, @"整理|归档|分类|\b(?:organize|organise|sort|archive)\b", Match) &&
            Regex.IsMatch(text, @"按(?:照)?\s*(?:拍摄)?日(?:期|子)|按拍摄时间|\bby (?:capture |taken |shooting )?(?:date|day)\b", Match))
            return WorkKind.PhotoArchive;
        return null;
    }

    public static string? SourcePath(string prompt)
    {
        var destination = Destination.Match(prompt);
        return PcDirect.ExplicitPath(destination.Success ? prompt[..destination.Index] : prompt);
    }

    public static string? RequestedOutput(string prompt)
    {
        if (Destination.Match(prompt) is not { Success: true } match) return null;
        string path = match.Groups["target"].Value.Trim().TrimEnd('.', '!', '?');
        if (FolderName.Match(path) is { Success: true } named)
            return named.Groups["name"].Value == "我的文档" ? "文档" : named.Groups["name"].Value;
        return PcDirect.ExplicitPath(path) ?? throw new StarHubException("Give a full destination path or name Desktop, Documents or Downloads.");
    }

    public static WorkRequest Plan(WorkKind kind, string input, string? output, string documentsFolder)
    {
        input = TaskFiles.PathOnDevice(input);
        if (kind == WorkKind.ProductSummary) return new(kind, input, input);
        if (Directory.Exists(input) && Path.GetPathRoot(input) == input)
            throw new StarHubException("Choose the specific folder containing the source files.");
        if (!Directory.Exists(input) && !(kind == WorkKind.CostWorkbook && File.Exists(input) &&
            Path.GetExtension(input).ToLowerInvariant() is ".csv" or ".xlsx"))
            throw new StarHubException("The source folder or table does not exist.");
        if (kind == WorkKind.PhotoArchive)
            return new(kind, input, TaskFiles.PathOnDevice(output ?? Path.Combine(input, "Star Hub Photos")));
        if (kind != WorkKind.CostWorkbook) throw new InvalidOperationException("Choose a concrete task.");
        string location = TaskFiles.PathOnDevice(output ?? documentsFolder);
        if (!Directory.Exists(location) && !Path.GetExtension(location).Equals(".xlsx", StringComparison.OrdinalIgnoreCase))
            throw new StarHubException("Choose an existing output folder or a full Excel filename.");
        if (Directory.Exists(location))
        {
            string candidate = Path.Combine(location, "Star Hub - Production Cost.xlsx");
            for (int index = 2; File.Exists(candidate) || Directory.Exists(candidate); index++)
                candidate = Path.Combine(location, $"Star Hub - Production Cost ({index}).xlsx");
            location = candidate;
        }
        if (File.Exists(location))
            throw new StarHubException("This Excel file already exists. Use a different destination.");
        return new(kind, input, location);
    }

    public static WorkReceipt Execute(WorkCheckpoint checkpoint, CancellationToken token, Action<int>? progress = null)
    {
        var work = checkpoint.Request;
        token.ThrowIfCancellationRequested();
        using var operation = new Mutex(false, "StarHub.Work." + checkpoint.OperationId);
        bool acquired;
        try { acquired = operation.WaitOne(0); }
        catch (AbandonedMutexException) { acquired = true; }
        if (!acquired) throw new StarHubException("This local task is already running.");
        try
        {
            return ExecuteWork(work, checkpoint.OperationId, token, progress);
        }
        finally { operation.ReleaseMutex(); }
    }

    private static WorkReceipt ExecuteWork(WorkRequest work, string id, CancellationToken token, Action<int>? progress)
    {
        TaskFiles.PathOnDevice(work.Input);
        TaskFiles.PathOnDevice(work.Output);
        return work.Kind switch
        {
            WorkKind.CostWorkbook => CostWorkbook.Create(work.Input, work.Output, id, token),
            WorkKind.ProductSummary => CostWorkbook.Summarize(work.Input, id, token),
            WorkKind.PhotoArchive => ArchivePhotos(work, id, token, progress),
            _ => throw new InvalidOperationException("Choose a concrete task.")
        };
    }

    private static readonly HashSet<string> PhotoTypes = new(StringComparer.OrdinalIgnoreCase)
        { ".jpg", ".jpeg", ".png", ".tif", ".tiff", ".heic", ".heif", ".webp", ".dng", ".avif" };

    private static WorkReceipt ArchivePhotos(WorkRequest work, string id, CancellationToken token, Action<int>? progress)
    {
        if (PathEquals(work.Input, work.Output) || IsInside(work.Input, work.Output))
            throw new StarHubException("Choose a separate folder for the organized photos.");
        var pending = new Stack<string>();
        var photos = new List<string>();
        pending.Push(work.Input);
        var options = new EnumerationOptions { AttributesToSkip = FileAttributes.ReparsePoint | FileAttributes.Hidden };
        while (pending.TryPop(out var folder))
        {
            foreach (var entry in new DirectoryInfo(folder).EnumerateFileSystemInfos("*", options).OrderBy(entry => entry.Name))
            {
                token.ThrowIfCancellationRequested();
                if (entry is DirectoryInfo child && !PathEquals(child.FullName, work.Output))
                    pending.Push(child.FullName);
                else if (entry is FileInfo file && PhotoTypes.Contains(file.Extension))
                    photos.Add(file.FullName);
                if (photos.Count > 2500) throw new StarHubException("Choose a folder with up to 2,500 photos for this task.");
            }
        }
        if (photos.Count == 0) throw new StarHubException("The selected folder contains no supported photos.");
        int unknown = 0;
        for (int index = 0; index < photos.Count; index++)
        {
            token.ThrowIfCancellationRequested();
            string source = TaskFiles.PathOnDevice(photos[index]);
            string? date = PhotoDate(source);
            if (date is null) unknown++;
            string directory = TaskFiles.PathOnDevice(Path.Combine(work.Output, date ?? "Unknown date"));
            Directory.CreateDirectory(directory);
            string extension = Path.GetExtension(source);
            string stem = Path.GetFileNameWithoutExtension(source);
            string hash = TaskFiles.Hash(source, token);
            for (int number = 1; ; number++)
            {
                string name = number == 1 ? stem + extension : $"{stem} ({number}){extension}";
                string target = TaskFiles.PathOnDevice(Path.Combine(directory, name));
                if (Directory.Exists(target)) continue;
                if (File.Exists(target))
                {
                    if (TaskFiles.Hash(target, token) == hash) break;
                    continue;
                }
                TaskFiles.CommitFile(target, id, token, staged =>
                {
                    using var read = File.OpenRead(source);
                    using var write = new FileStream(staged, FileMode.CreateNew, FileAccess.Write);
                    read.CopyToAsync(write, token).GetAwaiter().GetResult();
                    write.Flush(flushToDisk: true);
                }, staged =>
                {
                    if (TaskFiles.Hash(staged, token) != hash) throw new IOException("The photo changed during the copy.");
                });
                break;
            }
            if ((index + 1) % 20 == 0 || index + 1 == photos.Count) progress?.Invoke(index + 1);
        }
        return new(work.Output, photos.Count, unknown, Directory: true);
    }

    private static string? PhotoDate(string path)
    {
        try
        {
            var data = ImageMetadataReader.ReadMetadata(path).OfType<ExifSubIfdDirectory>().FirstOrDefault();
            if (data?.GetString(ExifDirectoryBase.TagDateTimeOriginal) is { } original &&
                DateTime.TryParseExact(original.Trim(), "yyyy:MM:dd HH:mm:ss", CultureInfo.InvariantCulture,
                    DateTimeStyles.None, out var date))
                return date.ToString("yyyy-MM-dd", CultureInfo.InvariantCulture);
            return null;
        }
        catch (ImageProcessingException) { return null; }
    }

    private static bool PathEquals(string a, string b) => string.Equals(a, b, StringComparison.OrdinalIgnoreCase);
    private static bool IsInside(string path, string folder) => path.StartsWith(
        Path.TrimEndingDirectorySeparator(folder) + Path.DirectorySeparatorChar, StringComparison.OrdinalIgnoreCase);
}

internal static class TaskFiles
{
    public static string PathOnDevice(string requested)
    {
        if (!Path.IsPathFullyQualified(requested) || requested.StartsWith(@"\\", StringComparison.Ordinal))
            throw new StarHubException("Choose a local file or folder with a full path.");
        string path = Path.TrimEndingDirectorySeparator(Path.GetFullPath(requested));
        string root = Path.GetPathRoot(path)!;
        if (OperatingSystem.IsWindows() && (new DriveInfo(root).DriveType == DriveType.Network || path[root.Length..].Contains(':')))
            throw new StarHubException("Choose a path on a local drive.");
        for (string? part = path; part is not null; part = Path.GetDirectoryName(part))
        {
            try
            {
                if ((File.GetAttributes(part) & FileAttributes.ReparsePoint) != 0)
                    throw new StarHubException("Choose a folder or file without a link or junction.");
            }
            catch (FileNotFoundException) { }
            catch (DirectoryNotFoundException) { }
        }
        return path;
    }

    public static string Hash(string file, CancellationToken token)
    {
        using var stream = File.OpenRead(file);
        return Convert.ToHexString(SHA256.HashDataAsync(stream, token).AsTask().GetAwaiter().GetResult());
    }

    public static void CommitFile(string path, string id, CancellationToken token, Action<string> write, Action<string> verify,
        string? previousHash = null)
    {
        PathOnDevice(path);
        string parent = Path.GetDirectoryName(path)!;
        if (!Directory.Exists(parent)) throw new StarHubException("The destination folder does not exist.");
        if (!Guid.TryParseExact(id, "N", out _)) throw new InvalidDataException("Invalid local operation identifier.");
        string staged = Path.Combine(parent, $".starhub-{id}.part");
        PathOnDevice(staged);
        try
        {
            File.Delete(staged);
            write(staged);
            verify(staged);
            token.ThrowIfCancellationRequested();
            if (previousHash is not null && Hash(path, token) != previousHash)
                throw new StarHubException("The workbook changed while Star Hub was updating it. Close it and retry.");
            File.Move(staged, PathOnDevice(path), previousHash is not null);
        }
        finally { File.Delete(staged); }
    }
}
