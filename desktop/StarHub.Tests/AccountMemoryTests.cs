using System.Net;
using System.Net.Http.Json;
using System.Text.Json;
using StarHub.Core;
using Xunit;

namespace StarHub.Tests;

public class AccountMemoryTests
{
    private sealed class MemoryServer : HttpMessageHandler
    {
        public string Owner = Guid.NewGuid().ToString();
        public long Revision = 1;
        public List<SavedNote> Notes = [];
        public bool Offline, LoseAck;
        protected override async Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken token)
        {
            if (Offline) throw new HttpRequestException("offline");
            Assert.Equal("secret",request.Headers.Authorization?.Parameter);
            var data = await request.Content!.ReadFromJsonAsync<JsonElement>(token);
            string action=data.GetProperty("action").GetString()!;
            if (action != "read")
            {
                Assert.Equal(Revision,data.GetProperty("revision").GetInt64());
                string id=data.GetProperty("id").GetString()!;
                Notes.RemoveAll(n => n.Id==id);
                if (action!="delete") Notes.Add(new(id,"FACT",data.GetProperty("text").GetString()!));
                Revision++;
                if (LoseAck) { LoseAck=false; throw new HttpRequestException("lost reply"); }
            }
            return new(HttpStatusCode.OK) { Content=JsonContent.Create(new MemorySnapshot(Owner,Revision,true,Notes)) };
        }
    }

    [Fact]
    public async Task OfflineEditsSurviveRestartAndConflictsStaySeparateByAccount()
    {
        string root=Path.Combine(Path.GetTempPath(),Guid.NewGuid().ToString());
        try
        {
            var store=new LocalStore(root);
            var server=new MemoryServer();
            using var http=new HttpClient(server);
            var profile=new DesktopAccountProfile { MemoryOwner=server.Owner, MemoryToken="secret" };
            var sync=new AccountMemorySync(store,http);
            await sync.SyncAsync(profile,default);
            string note=Guid.NewGuid().ToString(), owner=server.Owner;
            store.ChangeAccountNote(owner,note,"Practice from a workbook");
            server.Offline=true;
            await Assert.ThrowsAsync<HttpRequestException>(()=>sync.SyncAsync(profile,default));
            store=new LocalStore(root);
            sync=new AccountMemorySync(store,http);
            Assert.Single(store.AccountNotes(owner).Notes());
            Assert.Empty(store.AccountNotes(Guid.NewGuid().ToString()).Notes());
            Assert.Empty(store.Memories());
            server.Offline=false;
            server.LoseAck=true;
            await Assert.ThrowsAsync<HttpRequestException>(()=>sync.SyncAsync(profile,default));
            await sync.SyncAsync(profile,default);
            Assert.Single(server.Notes);
            Assert.Empty(store.AccountNotes(owner).Pending);
            store.ChangeAccountNote(owner,note,"PC edit");
            server.Notes[0]=new(note,"FACT","Web edit"); server.Revision++;
            await sync.SyncAsync(profile,default);
            Assert.True(Assert.Single(store.AccountNotes(owner).Pending).Conflict);
            Assert.Equal("Web edit",Assert.Single(server.Notes).Text);
            store.ChangeAccountNote(owner,note,"Reviewed edit");
            await sync.SyncAsync(profile,default);
            Assert.Equal("Reviewed edit",Assert.Single(server.Notes).Text);
            store.ChangeAccountNote(owner,note,null);
            await sync.SyncAsync(profile,default);
            Assert.Empty(server.Notes);
            Assert.Empty(store.AccountNotes(owner).Notes());
        }
        finally { Microsoft.Data.Sqlite.SqliteConnection.ClearAllPools(); Directory.Delete(root,true); }
    }
}
