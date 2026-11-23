using System;
using System.Collections.Generic;
using System.Linq;
using System.Net;
using System.Net.Http;
using System.Threading;
using System.Threading.Tasks;
using DashboardWindows;

static class ConcurrentEditsChecks {
    public static async Task Run(string root,Action<bool,string> check) {
        var store=new Store(root);
        store.Change(s=> { s.config.enabled=true; s.config.refreshToken="fake"; s.config.agentFile="board"; s.config.phoneFile="phone"; s.config.desktopFile="pc"; });
        var fake=new Exchange(store.Snapshot().deviceId); using(var http=new HttpClient(fake)) {
            var sync=new Sync(store,new Google(store,http));
            store.Accept(Json.Write(fake.Board));
            store.SetTask(store.Tasks().Single(t=>t.id=="a"),true);
            var original=store.Snapshot().edits.Single().revision;
            var refresh=sync.Run();
            if(await Task.WhenAny(fake.PatchStarted.Task,Task.Delay(5000))!=fake.PatchStarted.Task) throw new Exception("Test sync never reached the controlled in-flight write.");
            check(store.Tasks().All(t=>t.completed),"phone and PC checks on different tasks survive one refresh window");
            check(store.Snapshot().edits.Single().id=="a","phone display snapshots are not echoed as new Windows edits");
            store.SetTask(store.Tasks().Single(t=>t.id=="a"),false);
            var current=store.Snapshot().edits.Single().revision;
            fake.Board.tasks[0].completed=true; fake.Board.tasks[1].completed=true;
            fake.Board.acknowledgedTaskEdits=new List<Ack> { new Ack { deviceId=fake.Pc,taskId="a",revision=original },new Ack { deviceId="phone",taskId="b",revision=7 } };
            fake.ReleasePatch.SetResult(true); await refresh;
            check(fake.Payloads.Count==2 && fake.Payloads.Last().Contains("\"revision\":"+current),"an edit during an upload is flushed in the same refresh without waiting for the timer");
            check(store.Snapshot().edits.Single().revision==current && !store.Tasks().Single(t=>t.id=="a").completed,"old in-flight acknowledgment cannot clear a newer uncheck");
            check(store.Tasks().Single(t=>t.id=="b").completed,"reconciling one checkbox retains another device's independent check");
            fake.Board.tasks[0].completed=false; fake.Board.acknowledgedTaskEdits[0].revision=current; await sync.Run();
            check(store.Snapshot().edits.Count==0 && !store.Tasks().Single(t=>t.id=="a").completed,"exact current acknowledgment converges to the reconciled board");
            store.Change(s=>s.phoneState=Json.Write(new { deviceId="phone",tasks=new[] { new Todo { id="b",title="B",completed=false } },taskEdits=new Edit[0] }));
            check(store.Tasks().Single(t=>t.id=="b").completed,"an old full-device snapshot does not undo an acknowledged checkbox");
            store.SetTask(store.Tasks().Single(t=>t.id=="a"),true); current=store.Snapshot().edits.Single().revision;
            store.Change(s=>s.phoneState=Json.Write(new { deviceId="phone",tasks=new[] { new Todo { id="a",title="A",completed=true } },taskEdits=new[] { new Edit { id="a",title="A",completed=true,revision=8 } } }));
            fake.Board.tasks[0].completed=true;
            fake.Board.acknowledgedTaskEdits=new List<Ack> { new Ack { deviceId=fake.Pc,taskId="a",revision=current },new Ack { deviceId="phone",taskId="a",revision=8 } };
            store.Accept(Json.Write(fake.Board));
            check(store.Tasks().Count(t=>t.id=="a")==1 && store.Tasks().Single(t=>t.id=="a").completed && store.Snapshot().edits.Count==0,"matching checks from both devices coalesce into one completed task");
        }
    }
    sealed class Exchange : HttpMessageHandler {
        public readonly string Pc; public Board Board=new Board { tasks=new List<Todo> { new Todo { id="a",title="A" },new Todo { id="b",title="B" } } };
        public List<string> Payloads=new List<string>();
        public TaskCompletionSource<bool> PatchStarted=new TaskCompletionSource<bool>();
        public TaskCompletionSource<bool> ReleasePatch=new TaskCompletionSource<bool>();
        public Exchange(string pc) { Pc=pc; }
        protected override async Task<HttpResponseMessage> SendAsync(HttpRequestMessage request,CancellationToken cancellation) {
            string path=request.RequestUri.AbsolutePath, response="{}";
            if(path.EndsWith("/token")) response="{\"access_token\":\"fake\",\"expires_in\":3600}";
            else if(path.EndsWith("/board")) response=Json.Write(Board);
            else if(path.EndsWith("/phone")) response=Json.Write(new { deviceId="phone",tasks=new[] { new Todo { id="b",title="B",completed=true } },taskEdits=new[] { new Edit { id="b",title="B",completed=true,revision=7 } } });
            else if(request.Method.Method=="PATCH") {
                Payloads.Add(await request.Content.ReadAsStringAsync());
                if(Payloads.Count==1) { PatchStarted.SetResult(true); if(await Task.WhenAny(ReleasePatch.Task,Task.Delay(10000))!=ReleasePatch.Task) throw new Exception("Controlled test write wasn't released."); }
            } else response=Json.Write(new { id="pc",appProperties=new { captureId="dashboard-windows-"+Pc } });
            return new HttpResponseMessage(HttpStatusCode.OK) { Content=new StringContent(response) };
        }
    }
}
