using System.Text.RegularExpressions;

namespace StarHub.Core;

public enum PcOperation
{
    Notepad, TaskManager, Downloads, Documents, Desktop, NetworkSettings, InstalledApps,
    DeviceSnapshot, EnableStartup, DisableStartup, FlushDns, CheckImage,
    Calculator, Paint, FileExplorer, WindowsSettings, Terminal, WindowsTerminal, PowerShell, CommandPrompt
}

public static class PcCommands
{
    private static readonly Regex Separator = new(@"\s*(?:[，,]?\s*(?:然后|再(?=打开|启动|帮我|查看|检查|请)|并(?:且)?(?=打开|启动|查看|检查))|[；;]|,?\s*\b(?:and\s+)?then\b)\s*",
        RegexOptions.IgnoreCase | RegexOptions.CultureInvariant);
    private static readonly Regex Courtesy = new(
        @"^(?:(?:请|麻烦)(?:你)?[\s，,]*|(?:帮我|替我|先)[\s，,]*|(?:please|can you|could you|would you)\s+)+",
        RegexOptions.IgnoreCase | RegexOptions.CultureInvariant);
    private static readonly Regex OpenVerb = new(@"^(?:(?:打开|启动)(?:一下)?|开(?:一下|个))\s*", RegexOptions.CultureInvariant);
    private static readonly Regex EnglishOpenVerb = new(@"^(?:open|launch|start)\s+(?:the\s+)?",
        RegexOptions.IgnoreCase | RegexOptions.CultureInvariant);
    private static readonly Regex AppTrailingWords = new(@"^(打开(?:任务管理器|记事本|计算器|终端))\s*(?:一下|看看|看一看|看一下|给我看)?$",
        RegexOptions.CultureInvariant);
    private static readonly Regex DirectQuestion = new(@"^(?:(?:你)?(?:能不能|能否|可以|能|可否)\s*|(?:can|could|would) you\s+)",
        RegexOptions.IgnoreCase | RegexOptions.CultureInvariant);
    private static readonly Dictionary<string, PcOperation> Commands = new(StringComparer.OrdinalIgnoreCase)
    {
        ["打开记事本"] = PcOperation.Notepad, ["open notepad"] = PcOperation.Notepad,
        ["打开任务管理器"] = PcOperation.TaskManager, ["open task manager"] = PcOperation.TaskManager,
        ["打开计算器"] = PcOperation.Calculator, ["open calculator"] = PcOperation.Calculator,
        ["打开终端"] = PcOperation.Terminal, ["open terminal"] = PcOperation.Terminal,
        ["打开 Windows 终端"] = PcOperation.WindowsTerminal, ["打开Windows终端"] = PcOperation.WindowsTerminal,
        ["open windows terminal"] = PcOperation.WindowsTerminal, ["打开 Windows Terminal"] = PcOperation.WindowsTerminal,
        ["打开 PowerShell"] = PcOperation.PowerShell, ["打开PowerShell"] = PcOperation.PowerShell, ["open powershell"] = PcOperation.PowerShell,
        ["打开命令提示符"] = PcOperation.CommandPrompt, ["打开 CMD"] = PcOperation.CommandPrompt, ["open command prompt"] = PcOperation.CommandPrompt,
        ["打开画图"] = PcOperation.Paint, ["open paint"] = PcOperation.Paint,
        ["打开资源管理器"] = PcOperation.FileExplorer, ["open file explorer"] = PcOperation.FileExplorer,
        ["打开文件资源管理器"] = PcOperation.FileExplorer, ["open explorer"] = PcOperation.FileExplorer,
        ["打开Windows设置"] = PcOperation.WindowsSettings, ["打开Windows 设置"] = PcOperation.WindowsSettings,
        ["打开系统设置"] = PcOperation.WindowsSettings, ["open windows settings"] = PcOperation.WindowsSettings,
        ["打开下载文件夹"] = PcOperation.Downloads, ["打开下载目录"] = PcOperation.Downloads,
        ["open downloads"] = PcOperation.Downloads, ["open downloads folder"] = PcOperation.Downloads,
        ["打开文档文件夹"] = PcOperation.Documents, ["open documents"] = PcOperation.Documents, ["open documents folder"] = PcOperation.Documents,
        ["打开桌面文件夹"] = PcOperation.Desktop, ["open desktop folder"] = PcOperation.Desktop,
        ["打开网络设置"] = PcOperation.NetworkSettings, ["open network settings"] = PcOperation.NetworkSettings,
        ["打开已安装应用"] = PcOperation.InstalledApps, ["open installed apps"] = PcOperation.InstalledApps,
        ["查看系统信息"] = PcOperation.DeviceSnapshot, ["show system information"] = PcOperation.DeviceSnapshot,
        ["开启开机自启动"] = PcOperation.EnableStartup, ["enable desktop startup"] = PcOperation.EnableStartup,
        ["关闭开机自启动"] = PcOperation.DisableStartup, ["disable desktop startup"] = PcOperation.DisableStartup,
        ["清除 DNS 缓存"] = PcOperation.FlushDns, ["clear dns cache"] = PcOperation.FlushDns,
        ["检查 Windows 系统映像"] = PcOperation.CheckImage, ["check windows image"] = PcOperation.CheckImage
    };

    public static bool HasPrefix(string text) => text.StartsWith("/pc", StringComparison.OrdinalIgnoreCase) &&
        (text.Length == 3 || char.IsWhiteSpace(text[3]));

    private const string ActionTerms = "打开|启动|创建|新建|生成|整理|移动|移到|复制|清理|清除|清空|保存|检查|读取|修改|列出|列一下|写入|写进|写成|归档|重命名";
    private static readonly Regex PcGoal = new(
        $@"^(?:(?:请|麻烦)(?:你)?(?:帮我|替我)?|帮我|替我)?[\s，,]*(?:把|将|在|用|给|{ActionTerms})",
        RegexOptions.CultureInvariant);
    private static readonly Regex LocalAction = new(ActionTerms, RegexOptions.CultureInvariant);
    private static readonly Regex EnglishGoal = new(
        @"^(?:open|launch|start|create|make|organize|move|copy|save|check|read|edit|list|write|rename)\b.*\b(?:windows|file explorer|notepad|task manager|calculator|desktop|downloads|folder|directory|files?)\b",
        RegexOptions.IgnoreCase | RegexOptions.CultureInvariant);
    private static readonly Regex EnglishInstruction = new(
        @"\b(?:how\s+to|how\s+(?:do|can|should)\s+i|show me how|tell me how|which shortcut|step-by-step|tutorial|instructions)\b",
        RegexOptions.IgnoreCase | RegexOptions.CultureInvariant);
    private static readonly Regex FolderReference = new(
        @"文件(?!资源管理器)|目录|桌面|下载项|文档|\b(?:files?(?!\s+explorer)|folder|directory|downloads|documents|desktop)\b|\.(?:txt|md|csv|tsv|json|log)\b",
        RegexOptions.IgnoreCase | RegexOptions.CultureInvariant);
    private static readonly string[] LocalResources =
        ["文件", "目录", "桌面", "下载项", "文档", "记事本", "计算器", "任务管理器", "资源管理器", "网络设置", "终端", "PowerShell", "Windows", "DNS"];

    public static bool LooksLikeGoal(string prompt) => prompt.Length is > 0 and <= 6000 &&
        !prompt.EndsWith('？') && !prompt.EndsWith('?') &&
        !prompt.Contains("如何", StringComparison.Ordinal) && !prompt.Contains("怎么", StringComparison.Ordinal) &&
        ((PcGoal.IsMatch(Courtesy.Replace(prompt, "")) && LocalAction.IsMatch(prompt) &&
          LocalResources.Any(resource => prompt.Contains(resource, StringComparison.OrdinalIgnoreCase))) ||
         (EnglishGoal.IsMatch(Courtesy.Replace(prompt, "")) && !EnglishInstruction.IsMatch(prompt)));

    public static bool MayReferToComputer(string prompt, bool followsComputerTask) => LooksLikeGoal(prompt) ||
        PcDirect.ExplicitPath(prompt) is not null ||
        Regex.IsMatch(prompt, @"这台电脑|我的电脑|在电脑上|本机|本地(?:文件|应用|磁盘|数据)|任务管理器|下载的文件|\b(?:on|from) (?:this|my) (?:computer|pc)\b",
            RegexOptions.IgnoreCase | RegexOptions.CultureInvariant) ||
        (followsComputerTask && Regex.IsMatch(prompt,
            @"^(?:那就|接着|继续|然后|再|把它|用它|改成|改一下|存到|放到|删掉|打开它|保存|打开|continue\b|then\b|now\b|open it\b|save it\b)",
            RegexOptions.IgnoreCase | RegexOptions.CultureInvariant));

    public static bool NeedsTaskFolder(string goal) => FolderReference.IsMatch(goal);

    // Null is ordinary chat. An empty plan is a local request with unsupported or ambiguous steps.
    public static IReadOnlyList<PcOperation>? Parse(string prompt)
    {
        var text = prompt.Trim();
        bool explicitRequest = HasPrefix(text);
        if (explicitRequest) text = text[3..].Trim();
        if (text.Length > 1024) return explicitRequest ? [] : null;

        string[] steps = Separator.Split(text);
        var operations = new List<PcOperation>();
        foreach (var step in steps)
            if (Commands.TryGetValue(Normalize(step), out var operation))
                operations.Add(operation);
        if (!explicitRequest && operations.Count == 0) return null;
        return operations.Count == steps.Length && steps.Length <= 8 ? operations : [];
    }

    private static string Normalize(string step)
    {
        string text = step.Trim();
        if (DirectQuestion.Match(text) is { Success: true } question)
            text = text[question.Length..].TrimEnd('？', '?').TrimEnd().TrimEnd('吗', '嗎');
        text = Courtesy.Replace(text, "");
        text = EnglishOpenVerb.Replace(OpenVerb.Replace(text, "打开"), "open ")
            .TrimEnd('。', '！', '.', '!', '，', ',', '?', '？').TrimEnd();
        return AppTrailingWords.Replace(text, "$1");
    }
}
