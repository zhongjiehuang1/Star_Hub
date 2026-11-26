using System.Net;
using System.Net.Http.Json;
using System.Text.Json;

namespace StarHub.Core;

public sealed record SavedNote(string Id, string Kind, string Text);
public sealed record MemorySnapshot(string Owner, long Revision, bool Enabled, List<SavedNote> Memories);
public sealed record MemoryEdit(string Id, string? Before, string? After, long? SentAt = null, bool Conflict = false);
public sealed class AccountMemory
{
    public MemorySnapshot? Remote { get; set; }
    public List<MemoryEdit> Pending { get; init; } = [];
    [System.Text.Json.Serialization.JsonIgnore]
    public bool Enabled => Remote?.Enabled == true;

    public IReadOnlyList<MemoryNote> Notes(string search = "")
    {
        var notes = Remote?.Memories.ToDictionary(n => n.Id, n => n.Text) ?? [];
        foreach (var edit in Pending)
        {
            if (edit.After is null) notes.Remove(edit.Id);
            else notes[edit.Id] = edit.After;
        }
        return notes.Where(n => n.Value.Contains(search, StringComparison.OrdinalIgnoreCase))
            .Select(n => new MemoryNote(n.Key, n.Value, 0)).ToList();
    }

    internal string? ServerText(string id) => Remote?.Memories.FirstOrDefault(n => n.Id == id)?.Text;

    internal void Change(string id, string? text)
    {
        if (!Enabled && text is not null) throw new StarHubException("Enable account memory first.");
        if (text is not null)
        {
            text = text.Trim().Normalize(System.Text.NormalizationForm.FormKC);
            if (text.Length is 0 or > 120 || text.Any(char.IsControl))
                throw new StarHubException("Enter a memory of up to 120 characters.");
            if (Notes().Any(n => n.Id != id && n.Text.Equals(text, StringComparison.OrdinalIgnoreCase))) return;
            if (Notes().Count >= 50 && !Notes().Any(n => n.Id == id)) throw new StarHubException("Account memory holds up to 50 notes.");
        }
        var old = Pending.Find(e => e.Id == id);
        string? before = ServerText(id);
        Pending.RemoveAll(e => e.Id == id);
        // A sent add followed by an offline delete must survive a lost acknowledgment.
        if (before != text || old?.SentAt is not null)
            Pending.Add(new(id, before, text, old?.SentAt));
    }

    internal void Merge(MemorySnapshot next, MemoryEdit? acknowledged = null)
    {
        if (next.Revision < (Remote?.Revision ?? -1)) return;
        Remote = next;
        foreach (var edit in Pending.ToArray())
        {
            string? actual = ServerText(edit.Id);
            if (acknowledged?.Id == edit.Id && acknowledged != edit)
            {
                // The user edited again while this request was in flight.
                Pending.Remove(edit);
                if (actual != edit.After) Pending.Add(edit with { Before = actual, SentAt = null });
                continue;
            }
            bool duplicate = edit.Before is null && edit.After is not null &&
                next.Memories.Any(n => n.Text.Equals(edit.After, StringComparison.OrdinalIgnoreCase));
            if (actual == edit.After || duplicate) Pending.Remove(edit);
            else if (actual != edit.Before || (edit.SentAt is long sent && next.Revision > sent && actual is null))
            {
                Pending[Pending.IndexOf(edit)] = edit with { Conflict = true };
            }
        }
    }
}

public sealed partial class LocalStore
{
    public AccountMemory AccountNotes(string owner) => AccessAccountNotes(owner, null);

    internal AccountMemory AccessAccountNotes(string owner, Action<AccountMemory>? change)
    {
        if (string.IsNullOrWhiteSpace(owner)) throw new ArgumentException("Missing memory owner.");
        using var db = Open();
        using var transaction = db.BeginTransaction(deferred: change is null);
        using var query = Command(db, "SELECT content FROM account_memory WHERE owner=$owner", transaction, ("$owner",owner));
        var state = query.ExecuteScalar() is string json ? JsonSerializer.Deserialize<AccountMemory>(json)! : new AccountMemory();
        if (change is not null)
        {
            change(state);
            using var write = Command(db, """
                INSERT INTO account_memory(owner,content) VALUES($owner,$content)
                ON CONFLICT(owner) DO UPDATE SET content=excluded.content
                """, transaction, ("$owner",owner), ("$content",JsonSerializer.Serialize(state)));
            write.ExecuteNonQuery();
        }
        transaction.Commit();
        return state;
    }

    public void DiscardConflicts(string owner) =>
        AccessAccountNotes(owner, state => state.Pending.RemoveAll(edit => edit.Conflict));

    public void ChangeAccountNote(string owner, string id, string? text) =>
        AccessAccountNotes(owner, state => state.Change(id,text));
}

public sealed class AccountMemorySync(LocalStore store, HttpClient? transport = null)
{
    private static readonly HttpClient DefaultClient = new(new HttpClientHandler { AllowAutoRedirect = false, UseCookies = false })
        { Timeout = TimeSpan.FromSeconds(12), MaxResponseContentBufferSize = 64 * 1024 };
    private static readonly Uri Endpoint = new(DesktopAccountClient.Website, "api/desktop-connection");

    private async Task<MemorySnapshot> Request(DesktopAccountProfile profile, object body, CancellationToken token)
    {
        using var request = new HttpRequestMessage(HttpMethod.Post, Endpoint) { Content = JsonContent.Create(body) };
        request.Headers.Authorization = new("Bearer",profile.MemoryToken);
        request.Headers.Add("X-Starhub-Client","desktop");
        using var response = await (transport ?? DefaultClient).SendAsync(request,token).ConfigureAwait(false);
        response.EnsureSuccessStatusCode();
        var state = await response.Content.ReadFromJsonAsync<MemorySnapshot>(token).ConfigureAwait(false);
        if (state is null || state.Owner != profile.MemoryOwner || state.Revision < 0 || state.Memories is null ||
            state.Memories.Count > 50 || state.Memories.Any(n => n is null || !Guid.TryParseExact(n.Id,"D",out _) ||
                n.Text is not { Length: > 0 and <= 120 }) || state.Memories.DistinctBy(n => n.Id).Count() != state.Memories.Count)
            throw new InvalidDataException("Invalid memory snapshot.");
        return state;
    }

    private static async Task<FileStream> AcquireAsync(string path, CancellationToken token)
    {
        for (int attempt=0;;attempt++)
        {
            token.ThrowIfCancellationRequested();
            try { return new FileStream(path,FileMode.OpenOrCreate,FileAccess.ReadWrite,FileShare.None); }
            catch (IOException) when (attempt<30) { await Task.Delay(150,token).ConfigureAwait(false); }
        }
    }

    public async Task SyncAsync(DesktopAccountProfile profile, CancellationToken token, bool? enabled = null)
    {
        if (profile.MemoryOwner is not { } owner || profile.MemoryToken is null) return;
        if (!Guid.TryParseExact(owner,"D",out var ownerId)) throw new InvalidDataException("Invalid memory owner.");
        string lockPath = Path.Combine(store.DirectoryPath,"memory-" + ownerId.ToString("N") + ".lock");
        using var lease = await AcquireAsync(lockPath,token).ConfigureAwait(false);
        var remote = await Request(profile,new { action="read" },token).ConfigureAwait(false);
        if (enabled is bool value && value != remote.Enabled)
            remote = await Request(profile,new { action="enabled",revision=remote.Revision,enabled=value },token).ConfigureAwait(false);
        for (int i=0;i<=50;i++)
        {
            MemoryEdit? sent = null;
            store.AccessAccountNotes(owner,state =>
            {
                state.Merge(remote);
                if (state.Remote?.Revision != remote.Revision) return;
                var next = state.Pending.FirstOrDefault(e => !e.Conflict && (remote.Enabled || e.After is null));
                if (next is null) return;
                sent = next with { SentAt=remote.Revision };
                state.Pending[state.Pending.IndexOf(next)] = sent;
            });
            if (sent is null) return;
            object body = sent.After is null ? new { action="delete",revision=remote.Revision,id=sent.Id }
                : new { action=sent.Before is null ? "add" : "edit",revision=remote.Revision,id=sent.Id,text=sent.After };
            try
            {
                remote = await Request(profile,body,token).ConfigureAwait(false);
                store.AccessAccountNotes(owner,state => state.Merge(remote,sent));
            }
            catch (HttpRequestException e) when (e.StatusCode is HttpStatusCode.BadRequest or HttpStatusCode.RequestEntityTooLarge)
            {
                store.AccessAccountNotes(owner,state => {
                    int index = state.Pending.IndexOf(sent);
                    if (index >= 0) state.Pending[index] = sent with { Conflict=true };
                });
            }
            catch (HttpRequestException e) when (e.StatusCode is HttpStatusCode.Conflict or HttpStatusCode.NotFound)
            {
                // The server rejected this revision. Nothing from this request was committed.
                store.AccessAccountNotes(owner,state => {
                    int index = state.Pending.IndexOf(sent);
                    if (index >= 0) state.Pending[index] = sent with { SentAt=null };
                });
                var fresh = await Request(profile,new { action="read" },token).ConfigureAwait(false);
                if (fresh.Revision == remote.Revision)
                    store.AccessAccountNotes(owner,state => {
                        int index = state.Pending.FindIndex(n => n.Id==sent.Id && n.After==sent.After);
                        if (index>=0) state.Pending[index]=state.Pending[index] with { Conflict=true };
                    });
                remote = fresh;
            }
        }
    }
}
