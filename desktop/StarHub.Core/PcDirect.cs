using System.Text.Json;
using System.Text.RegularExpressions;

namespace StarHub.Core;

public sealed record PcDirectAction(string Tool, JsonElement Arguments);

public static class PcDirect
{
    private const RegexOptions MatchText = RegexOptions.IgnoreCase | RegexOptions.CultureInvariant;
    private const string Preamble = @"^(?:请[你]?\s*|麻烦[你]?\s*|帮我\s*|please\s+|can you\s+)*";
    private const string Absolute = @"(?:""(?<path>[A-Z]:[\\/][^""\r\n]+)""|“(?<path>[A-Z]:[\\/][^”\r\n]+)”|(?<path>[A-Z]:[\\/][^\s，。！？；,;""“”]+))";
    private static readonly Regex FileGoal = new(Preamble + @"(?<verb>读取(?:文件)?|读(?:一下)?|查看文件|打开(?:文件|文件夹)?|(?:创建|新建)(?:文件夹|目录)|列出(?:文件夹|目录)?|read(?: file)?\s+|open(?: file| folder)?\s+|make (?:a )?folder\s+|list(?: files in)?\s+)\s*" +
        Absolute + @"(?:\s*(?:的内容|内容|里面的文件))?\s*[。！？!.?]*$", MatchText);
    private static readonly Regex TransferGoal = new(Preamble +
        @"(?:(?:把|将)\s*" + Absolute + @"\s*(?<verb>复制|拷贝|移动|移)\s*(?:到|至)|" +
        @"(?<verb>复制|拷贝|移动|copy\s+|move\s+)\s*" + Absolute + @"\s*(?:到|至|\bto\b))\s*" + Absolute + @"\s*[。！？!?.]*$", MatchText);
    private static readonly Regex AppGoal = new(Preamble + @"(?:打开(?:一下)?|启动|open\s+|launch\s+|start\s+)\s*(?<app>[^\r\n，。！？；,;?!:/\\]{1,65})[。！？!.?]*$", MatchText);
    private static readonly Regex ShellGoal = new(Preamble + @"(?:/run\s+|运行\s*|run\s+)(?<shell>powershell|cmd)(?:\.exe)?\s*(?:命令\s*|command\s*)?(?:[:：]\s*|\s+)(?<command>[\s\S]+)$", MatchText);
    private static readonly Regex PathMention = new(Absolute, MatchText);
    private static readonly Regex PathTarget = new(@"^\s*(?:""(?<path>[A-Z]:[\\/][^""\r\n]+)""|“(?<path>[A-Z]:[\\/][^”\r\n]+)”|(?<path>[A-Z]:[\\/][^\r\n，。？！；""“”]+))\s*[。！？!.?]*$", MatchText);
    private static readonly Regex Compound = new(@"然后|并(?:且|查看|把|写|在|运行|用)|再(?:打开|用|写|查)|继续|怎么|如何|在哪里|的(?:设置|文件|文档)|\b(?:and|then|how|while)\b", MatchText);

    public static PcDirectAction? Parse(string prompt)
    {
        var text = prompt.Trim();
        if (PcCommands.HasPrefix(text)) text = text[3..].Trim();
        if (ShellGoal.Match(text) is { Success: true } shell)
            return Action("run_command", new
            {
                shell = shell.Groups["shell"].Value.ToLowerInvariant(),
                command = shell.Groups["command"].Value.Trim()
            });
        if (TransferGoal.Match(text) is { Success: true } transfer)
        {
            string verb = transfer.Groups["verb"].Value.Trim().ToLowerInvariant();
            var paths = transfer.Groups["path"].Captures;
            return Action(verb is "复制" or "拷贝" or "copy" ? "copy_file" : "move_file", new
            {
                path = paths[0].Value, destination = paths[1].Value
            });
        }
        if (FileGoal.Match(text) is { Success: true } file)
        {
            string verb = file.Groups["verb"].Value.Trim().ToLowerInvariant();
            string tool = verb.StartsWith("读", StringComparison.Ordinal) || verb.StartsWith("read", StringComparison.Ordinal) ||
                          verb == "查看文件" ? "read_file"
                : verb.StartsWith("列", StringComparison.Ordinal) || verb.StartsWith("list", StringComparison.Ordinal) ? "list_files"
                : verb.StartsWith("创建", StringComparison.Ordinal) || verb.StartsWith("新建", StringComparison.Ordinal) ||
                  verb.StartsWith("make", StringComparison.Ordinal) ? "make_folder" : "open_file";
            return Action(tool, new { path = file.Groups["path"].Value });
        }
        if (AppGoal.Match(text) is { Success: true } app && !Compound.IsMatch(text))
        {
            var name = app.Groups["app"].Value.Trim().TrimEnd('.', '。');
            if (!Regex.IsMatch(name, @"^(?:它|这个|那个|刚才的|文件|文件夹|文档|(?:an? |the )?(?:file|folder|app)|it)(?:\s|$)", MatchText))
                return Action("open_app", new { app = name });
        }
        return null;
    }

    public static string? ExplicitPath(string text)
    {
        var matches = PathMention.Matches(text);
        return matches.Count == 1 ? matches[0].Groups["path"].Value.TrimEnd('。', '.') : null;
    }

    internal static string? TargetPath(string text) => PathTarget.Match(text) is { Success: true } target
        ? target.Groups["path"].Value.TrimEnd(' ', '.') : null;

    private static PcDirectAction Action(string tool, object arguments) => new(tool, JsonSerializer.SerializeToElement(arguments));
}
