using System.Net.Http.Json;
using System.Text;
using System.Text.Json;

namespace StarHub.Core;

public sealed partial class LocalStore
{
    private static readonly HttpClient ChatTransport = new(new HttpClientHandler { AllowAutoRedirect=false, UseCookies=false })
        { Timeout=TimeSpan.FromSeconds(30) };

    public async Task<bool> UploadNextChatAsync(DesktopAccountProfile profile, CancellationToken token)
    {
        string? owner=profile.MemoryOwner;
        if (owner is null || profile.ChatToken is null) return false;
        string id, body;
        long dirty, revision;
        using (var db=Open())
        using (var tx=db.BeginTransaction())
        {
            using var find=Command(db,"SELECT id,dirty,revision,sending FROM chat_upload WHERE owner=$o AND dirty>0 ORDER BY attempted,rowid LIMIT 1",tx,("$o",owner));
            using var row=find.ExecuteReader();
            if (!row.Read()) return false;
            id=row.GetString(0); dirty=row.GetInt64(1); revision=row.GetInt64(2);
            string? pending=row.IsDBNull(3)?null:row.GetString(3);
            row.Close();
            if (pending is null)
            {
                var chat=FindConversation(id);
                var messages=new List<object>();
                foreach(var turn in ReadTurns(id))
                {
                    messages.Add(new {role="user", content=turn.Prompt});
                    if(turn.Reply.Length>0 || turn.Detail.Length>0)
                        messages.Add(new {role="assistant", content=turn.Reply.Length>0?turn.Reply:turn.Detail, model=turn.Model});
                }
                pending=JsonSerializer.Serialize(new {id="win-"+id, revision, deleted=chat is null,
                    title=chat?.Title, data=new {messages}});
                using var save=Command(db,"UPDATE chat_upload SET sending=$body,sent_dirty=$dirty WHERE id=$id",tx,
                    ("$body",pending),("$dirty",dirty),("$id",id));
                save.ExecuteNonQuery();
            }
            body=pending;
            using var attempt=Command(db,"UPDATE chat_upload SET attempted=$now WHERE id=$id",tx,("$now",Now()),("$id",id));
            attempt.ExecuteNonQuery();
            tx.Commit();
        }
        using var request=new HttpRequestMessage(HttpMethod.Post,new Uri(DesktopAccountClient.Website,"api/desktop-connection"));
        request.Headers.Authorization=new("Bearer",profile.ChatToken);
        request.Headers.Add("X-Starhub-Client","desktop");
        request.Headers.Add("X-Starhub-Scope","chat");
        request.Content=new StringContent(body,Encoding.UTF8,"application/json");
        using var reply=await ChatTransport.SendAsync(request,token);
        reply.EnsureSuccessStatusCode();
        long next=(await reply.Content.ReadFromJsonAsync<JsonElement>(token)).GetProperty("revision").GetInt64();
        using var connection=Open();
        using var ack=Command(connection,next==0
            ? "DELETE FROM chat_upload WHERE id=$id AND owner=$o AND sending=$body"
            : "UPDATE chat_upload SET revision=$next,dirty=dirty-sent_dirty,sending=NULL,sent_dirty=NULL WHERE id=$id AND owner=$o AND sending=$body",
            values:[("$id",id),("$o",owner),("$body",body),("$next",next)]);
        ack.ExecuteNonQuery();
        return true;
    }
}
