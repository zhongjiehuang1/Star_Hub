using System.Text.RegularExpressions;

namespace StarHub.Core;

public enum PdfDestination { Desktop, Downloads, Documents }

public sealed record PdfRequest(string Brief, PdfDestination Destination, bool Chinese, bool NeedsTopic = false,
    string? Markdown = null, string? PreviousFile = null, string? OutputPath = null);

public static class PdfRequests
{
    public const string PromptModel = "Star Hub · PDF brief";
    public const string DocumentModel = "Star Hub · PDF";
    private const RegexOptions Text = RegexOptions.IgnoreCase | RegexOptions.CultureInvariant;
    private static readonly Regex Format = new(@"(?<![A-Za-z])pdf(?![A-Za-z])", Text);
    private static readonly Regex Action = new(@"做|制作|生成|创建|出一份|写|导出|保存|存|放|给我|下载|转成|转为|整理|文件|附件|\b(?:make|create|generate|write|produce|export|save|put|download|give|convert|file|attachment)\b", Text);
    private static readonly Regex Tutorial = new(@"(?:怎么|如何|怎样)(?:才能|用)?(?:做|制作|生成|创建|导出|保存|转)|什么是|是什么|为什么不能|为何不能|\b(?:what is|how (?:can|do|should) (?:i|we)|how to)\b", Text);
    private static readonly Regex ExistingContent = new(@"刚才|上面|上述|上一条|那份|这个回复|这篇|这份内容|你(?:刚才)?的(?:回复|回答)|\b(?:above|previous reply|last answer|your reply|this response|that draft)\b", Text);
    private static readonly Regex SaveOrRetrieve = new(@"^(?:(?:我(?:是)?(?:要|想要|让你)|你|就|请|帮我|麻烦你|能不能|可以|直接|把它|把文件)\s*)*(?:给我|下载|发给我|放|存|保存|导出|打开(?:文件|它)?|生成|做)(?:\s*(?:在|到|成|一个|一份|吧|吗|行吗|就行|不行吗|上|里|文件|附件|电脑|本机|桌面|下载目录|下载文件夹|文档文件夹|文档目录|文档|pdf|[，。！？?.,!]))*$|^(?:where(?:'s| is) (?:the|my) file|download it|give me the file|save it(?: to .+)?|put it(?: on .+)?)[.!?]?$", Text | RegexOptions.NonBacktracking);
    private static readonly Regex AnyExample = new(@"随便|任意|你决定|举个例|举例|\b(?:any|anything|sample|example|you choose)\b", Text);
    private static readonly Regex UnspecifiedExample = new(@"^(?:(?:我说|帮我|给我|请|就|随便|任意|来|生成|制作|做|一个|一份|个|示例|例子|例|吧|pdf|附件|文件|保存|放|存|在|到|桌面|下载文件夹|文档文件夹|电脑|上|里|[\s，。！？?.,!]))+$|^(?:(?:please|make|a|an|any|example|generate|sample|pdf|you choose|[\s?.!]))+$", Text | RegexOptions.NonBacktracking);
    private static readonly Regex Accounting = new(@"会计对账|银行对账|余额调节|\bbank reconciliation\b", Text);
    private static readonly Regex OnlyAccountingBrief = new(@"^(?:(?:我想说|请|帮我|就|来一份|演示|关于|的|用|一个|一份|做|制作|生成|会计对账|银行对账|银行存款余额调节|随便|举个例|举例|给个|示例|案例|演练|保存|放|存|在|到|桌面|下载文件夹|文档文件夹|电脑|上|里|可以|就行|吧|pdf|[\s，。！？?.,!]))+$|^(?:(?:a|an|the|sample|example|of|a short|bank reconciliation|[\s?.!]))+$", Text | RegexOptions.NonBacktracking);
    private static readonly Regex BareRequest = new(@"^(?:(?:我想要|想要|我让你|你能不能|能不能|能否|可以|你|请|帮我|麻烦|给我|制作|做|生成|创建|导出|保存|下载|来|一份|一个|个|pdf|文件|附件|吗|吧|[\s，。！？?.,!]))+$|^(?:(?:can you|could you|give me|please|i want|make|create|generate|produce|a|an|me|pdf|file|document|[\s?.!]))+$", Text | RegexOptions.NonBacktracking);
    private static readonly Regex End = new(@"^(?:取消|算了|不用了|换个话题|cancel|never mind)[.!?。！？\s]*$", Text);
    private static readonly Regex Han = new(@"[\u3400-\u9fff]", Text);
    private static readonly Regex SaveLocation = new(@"保存(?:到|在|为)|存(?:到|在|为)|放(?:到|在)|输出到|写到|\b(?:save|put|export|write)(?:\s+it)?\s+(?:to|in|as)\s+", Text);
    private const string RecentPdf = @"(?:(?:刚才|刚刚|刚生成|上次)(?:生成|保存|制作)?的?\s*(?:这[个份]|那[个份])?|这[个份]|那[个份])\s*PDF(?:\s*文件)?";
    private const string RecycleVerb = @"(?:删除|删掉|删了|删掉吧|移(?:到|入)回收站)";
    private static readonly Regex RecycleRequest = new(
        @"^(?:请|帮我|请你|麻烦你|能不能|能否|可以|\s)*(?:(?:把|将)?\s*" + RecentPdf + @"\s*" + RecycleVerb +
        @"|(?:删除|删掉|删了)\s*" + RecentPdf + @")(?:吧|吗|一下|\s|[。！？?!.])*$|" +
        @"^(?:(?:please|can you|could you)\s+)*(?:(?:delete|recycle|remove)\s+(?:this|that|the (?:last|previous|most recent))\s+pdf|" +
        @"(?:move|send)\s+(?:this|that|the (?:last|previous|most recent))\s+pdf\s+to\s+the recycle bin)[.!?\s]*$", Text);

    public static bool IsRecycleRequest(string prompt) => RecycleRequest.IsMatch(prompt.Trim());

    public static string? LatestSavedPath(IReadOnlyList<Turn> history) =>
        history.LastOrDefault(turn => turn.GeneratedPdf is not null)?.GeneratedPdf;

    public static bool Explicit(string prompt) => Format.IsMatch(prompt) &&
        (Action.IsMatch(prompt) || prompt.Trim().Equals("PDF", StringComparison.OrdinalIgnoreCase)) && !Tutorial.IsMatch(prompt);

    public static PdfRequest? Parse(string prompt, IReadOnlyList<Turn> history, bool hasAttachment = false)
    {
        prompt = prompt.Trim();
        if (prompt.Length == 0 || End.IsMatch(prompt) || Tutorial.IsMatch(prompt) || IsRecycleRequest(prompt)) return null;
        bool direct = Explicit(prompt);
        var location = SaveLocation.Match(prompt);
        string? outputPath = location.Success ? PcDirect.TargetPath(prompt[(location.Index + location.Length)..]) : null;
        string contentBrief = outputPath is null ? prompt : prompt[..location.Index].TrimEnd(' ', ',', '，');
        var anchor = history.LastOrDefault(turn => turn.Model is PromptModel or DocumentModel || Explicit(turn.Prompt));
        bool waiting = history.LastOrDefault()?.Model == PromptModel;
        if (!direct && (PcCommands.Parse(prompt) is { Count: > 0 } || DeviceDiagnostics.Parse(prompt, false) is not null))
            return null;
        bool followUp = SaveOrRetrieve.IsMatch(prompt) || AnyExample.IsMatch(prompt) && prompt.Length <= 36 ||
            outputPath is not null && (contentBrief.Length == 0 || Regex.IsMatch(contentBrief,
                @"^(?:(?:请|帮我|把|将|之前的|这个|这份|刚才的|文件|它|pdf|\s)|the |it)+$", Text));
        if (!direct && !waiting && !(anchor is not null && followUp)) return null;

        bool chinese = Han.IsMatch(prompt) || (anchor is not null && Han.IsMatch(anchor.Prompt));
        var destination = FindDestination(prompt) ?? (anchor is null ? null : FindDestination(anchor.Prompt)) ?? PdfDestination.Desktop;
        bool reference = ExistingContent.IsMatch(prompt);
        string? previous = LatestSavedPath(history);
        if (!hasAttachment && previous is not null && !reference && (followUp || BareRequest.IsMatch(prompt)) &&
            !AnyExample.IsMatch(prompt))
            return new(prompt, destination, chinese, PreviousFile: previous, OutputPath: outputPath);

        string? priorDraft = null;
        if (!hasAttachment && (reference || anchor is not null && (followUp || BareRequest.IsMatch(prompt))))
            priorDraft = history.Reverse().FirstOrDefault(turn => IsDraft(turn, reference))?.Reply;
        if (priorDraft is not null && !AnyExample.IsMatch(prompt))
            return new(prompt, destination, chinese, Markdown: priorDraft, OutputPath: outputPath);

        string? priorTopic = history.Reverse().Select(turn => turn.Prompt).FirstOrDefault(text =>
            Accounting.IsMatch(text) || Explicit(text) && !BareRequest.IsMatch(text) && !SaveOrRetrieve.IsMatch(text));
        bool onlyDestination = SaveOrRetrieve.IsMatch(prompt) && FindDestination(prompt) is not null;
        bool bare = BareRequest.IsMatch(contentBrief);
        bool example = AnyExample.IsMatch(prompt);
        if (!hasAttachment && !waiting && !example && !onlyDestination && bare && priorTopic is null)
            return new(prompt, destination, chinese, NeedsTopic: true, OutputPath: outputPath);

        if (outputPath is null && waiting && anchor is not null && SaveLocation.Match(anchor.Prompt) is { Success: true } prior)
            outputPath = PcDirect.TargetPath(anchor.Prompt[(prior.Index + prior.Length)..]);

        bool inherited = !waiting && (bare || followUp || reference);
        string brief = inherited && priorTopic is not null ? priorTopic + "\n" + prompt : prompt;
        bool accountingSample = Accounting.IsMatch(contentBrief) && OnlyAccountingBrief.IsMatch(contentBrief) ||
            inherited && priorTopic is not null && Accounting.IsMatch(priorTopic) && OnlyAccountingBrief.IsMatch(priorTopic);
        bool sample = accountingSample || example && UnspecifiedExample.IsMatch(prompt) &&
            (priorTopic is null || Accounting.IsMatch(priorTopic) && !Regex.IsMatch(priorTopic, @"\d"));
        if (!hasAttachment && sample)
            return new(brief, destination, chinese, Markdown: AccountingExample(chinese), OutputPath: outputPath);

        return new(brief, destination, chinese, OutputPath: outputPath);
    }

    public static PdfDestination? FindDestination(string prompt)
    {
        if (Regex.IsMatch(prompt, @"桌面|\bdesktop\b", Text)) return PdfDestination.Desktop;
        if (Regex.IsMatch(prompt, @"下载(?:目录|文件夹)|\bdownloads\b", Text)) return PdfDestination.Downloads;
        if (Regex.IsMatch(prompt, @"文档(?:目录|文件夹)|\bdocuments folder\b", Text)) return PdfDestination.Documents;
        return null;
    }

    private static bool IsDraft(Turn turn, bool explicitReference)
    {
        if (turn.LocalOnly || turn.State != TurnState.Completed || turn.Reply.Trim().Length < (explicitReference ? 10 : 80)) return false;
        string reply = turn.Reply.TrimStart();
        return reply.Contains("\n#") || reply.Contains("\n|") ||
            !Regex.IsMatch(reply, @"^(?:抱歉|对不起|Sorry|I (?:cannot|can't|can’t)).{0,120}(?:不能|无法|没法|cannot|can't|can’t)", Text);
    }

    public static string AccountingExample(bool chinese) => chinese ? """
        # 银行存款对账演示

        星河商贸有限公司 · 2026 年 10 月 31 日 · 金额单位为人民币元

        本示例使用虚构数据，演示如何把银行对账单余额和企业银行存款日记账余额调整到同一金额。

        ## 余额调节表

        | 项目 | 银行对账单侧 | 企业日记账侧 |
        | :--- | ---: | ---: |
        | 调整前余额 | 48,000.00 | 50,000.00 |
        | 加：企业已存入、银行尚未入账的款项 | 6,000.00 | - |
        | 减：企业已开出、银行尚未扣款的支票 | 4,300.00 | - |
        | 减：银行已扣、企业尚未入账的手续费 | - | 300.00 |
        | **调整后余额** | **49,700.00** | **49,700.00** |

        ## 核对过程

        1. 将银行和企业的流水按金额、日期及凭证编号逐笔配对。
        2. 核实未达款项。6,000.00 元存款和 4,300.00 元支票属于到账时间差，后续查验银行流水。
        3. 银行已扣的 300.00 元手续费在企业账上遗漏。取得凭证后，借记财务费用，贷记银行存款，各 300.00 元。
        4. 核对算式。银行侧为 48,000.00 + 6,000.00 - 4,300.00 = 49,700.00，企业侧为 50,000.00 - 300.00 = 49,700.00。

        **结果**　调整后余额一致，差额为 0.00 元。保存对账单、余额调节表和支持凭证，并跟踪未达款项。
        """ : """
        # Bank reconciliation example

        Star River Trading · 31 October 2026 · Amounts in CNY

        This example uses fictional data to compare a bank statement with the company cash ledger.

        ## Reconciliation

        | Item | Bank statement | Company ledger |
        | :--- | ---: | ---: |
        | Balance before adjustments | 48,000.00 | 50,000.00 |
        | Add: deposit in transit | 6,000.00 | - |
        | Less: outstanding cheque | 4,300.00 | - |
        | Less: bank fee not yet recorded | - | 300.00 |
        | **Adjusted balance** | **49,700.00** | **49,700.00** |

        ## How the difference was resolved

        1. Match ledger entries and statement entries by reference, amount and date.
        2. Check the 6,000.00 deposit and 4,300.00 cheque on the next statement. These are timing differences.
        3. Record the 300.00 fee with a debit to bank charges and a credit to bank for 300.00.
        4. Verify both balances. Bank: 48,000.00 + 6,000.00 - 4,300.00 = 49,700.00. Ledger: 50,000.00 - 300.00 = 49,700.00.

        **Result** Both adjusted balances agree. Keep the reconciliation and supporting evidence.
        """;
}
