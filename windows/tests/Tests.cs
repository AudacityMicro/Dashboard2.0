using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Net;
using System.Net.Http;
using System.Text;
using System.Text.RegularExpressions;
using System.Threading;
using System.Threading.Tasks;
using DashboardWindows;

class Tests {
    static int count;
    static void Check(bool value,string name) { if(!value) throw new Exception(name); count++; Console.WriteLine("PASS "+name); }
    static void Main() {
        var root=Path.Combine(Path.GetTempPath(),"Dashboard-Windows-tests-"+Guid.NewGuid());
        try { Run(root).GetAwaiter().GetResult(); Console.WriteLine("PASS: "+count+" assertions"); }
        catch(Exception e) { Console.Error.WriteLine(e); Environment.ExitCode=1; }
        finally { if(Directory.Exists(root)) Directory.Delete(root,true); }
    }
    static async Task Run(string root) {
        Check(Google.Challenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk")=="E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM","RFC 7636 PKCE known vector");
        Check(Recorder.PcmLevel(new byte[32])==0,"audio meter reports silence without a fabricated signal");
        Check(Recorder.PcmLevel(new byte[] {0,128})==1 && Recorder.PcmLevel(new byte[] {255,63})>0.8,"audio meter scales real PCM input and handles negative full scale");
        Check(Google.Random().Length==43 && Google.Random()!=Google.Random(),"cryptographic nonce and verifier shape");
        var store=new Store(root);
        Check(!store.Snapshot().config.enabled && !store.Snapshot().config.showHealth,"Drive and health sharing initially off");
        store.Change(s=> { s.config.refreshToken="test-secret-refresh"; s.config.clientSecret="test-secret-client"; });
        Check(!Encoding.UTF8.GetString(File.ReadAllBytes(Path.Combine(root,"state.dat"))).Contains("test-secret"),"DPAPI protects saved credentials");
        Check(new Store(root).Snapshot().config.refreshToken=="test-secret-refresh","encrypted state survives restart");
        var note=store.Text("Unicode note ✓ café\nsecond line");
        Check(File.ReadAllText(store.Payload(note.id))==note.text && new Store(root).Snapshot().captures.Count==1,"note payload and queue durable before sync");
        Check(!Json.Write(store.Export()).Contains("test-secret"),"task exports contain no credentials");
        store.Accept("{\"schemaVersion\":1,\"updates\":[{\"id\":\"u\",\"title\":\"Brief\",\"body\":\"**Brief**\"}],\"tasks\":[{\"id\":\"t\",\"title\":\"Task\",\"completed\":false}]}");
        try { store.Accept("{\"schemaVersion\":1,\"tasks\":[{\"id\":\"bad\"}],\"updates\":[]}"); throw new Exception("invalid accepted"); } catch(InvalidDataException) {}
        Check(store.Snapshot().board.tasks.Single().id=="t","invalid board preserves cached tasks");
        var task=store.Tasks().Single(); store.SetTask(task,true); var revision=store.Snapshot().edits.Single().revision;
        store.SetTask(task,false);
        store.Accept(Json.Write(new Board { tasks=new List<Todo> { new Todo { id="t",title="Task",completed=true } },acknowledgedTaskEdits=new List<Ack> { new Ack { deviceId=store.Snapshot().deviceId,taskId="t",revision=revision } } }));
        Check(!store.Tasks().Single().completed && store.Snapshot().edits.Count==1,"stale acknowledgment preserves newer local edit");
        var current=store.Snapshot().edits.Single().revision;
        store.Accept(Json.Write(new Board { tasks=new List<Todo> { new Todo { id="t",title="Task",completed=true } },acknowledgedTaskEdits=new List<Ack> { new Ack { deviceId="other-device",taskId="t",revision=current } } }));
        Check(store.Snapshot().edits.Count==1,"another device cannot acknowledge local edits");
        store.Accept(Json.Write(new Board { tasks=new List<Todo> { new Todo { id="t",title="Task",completed=true } },acknowledgedTaskEdits=new List<Ack> { new Ack { deviceId=store.Snapshot().deviceId,taskId="t",revision=current } } }));
        Check(store.Tasks().Single().completed && store.Snapshot().edits.Count==0,"exact acknowledgment allows reconciled cross-device completion");
        using(var wav=new MemoryStream()) { wav.Write(new byte[364],0,364); Recorder.Finish(wav); var b=wav.ToArray(); Check(Encoding.ASCII.GetString(b,0,4)=="RIFF" && BitConverter.ToInt32(b,40)==320 && BitConverter.ToInt32(b,24)==16000,"WAV crash recovery patches exact byte count and sample rate"); }
        store.Change(s=> { s.config.enabled=true; s.config.agentFile="board"; s.config.phoneFile="phone"; });
        var server=new FakeDrive(); using(var http=new HttpClient(server)) {
            var sync=new Sync(store,new Google(store,http));
            try { await sync.Run(); throw new Exception("lost ack not simulated"); } catch(HttpRequestException) {}
            var saved=store.Snapshot().captures.Single(); var id=saved.remote;
            Check(saved.state=="pending" && id.Length>0 && saved.manifest.Length>0,"upload IDs persisted before network and lost acknowledgment");
            store=new Store(root); sync=new Sync(store,new Google(store,http)); await sync.Run();
            Check(store.Snapshot().captures.Single().state=="synced" && store.Snapshot().captures.Single().remote==id,"retry reconciles same remote IDs after restart");
            Check(server.UploadCounts.Values.All(n=>n==1),"retry never creates duplicate payload or manifest");
            Check(!server.Writes.Contains("board") && !server.Writes.Contains("phone"),"agent board and phone file stay read-only");
            Check(server.Writes.Contains(store.Snapshot().config.desktopFile),"only Windows task state is patched");
            store.Change(s=>s.config.showHealth=true); server.Health=true; await sync.Run();
            Check(store.Snapshot().health.Contains("consumedKcal") && !store.Snapshot().health.Contains("private-health-detail"),"PC caches health totals without detailed agent query records");
        }
        await ConcurrentEditsChecks.Run(Path.Combine(root,"concurrent"),Check);
        LayoutChecks.Run(Path.Combine(root,"layout"),Check);
        await UpdateChecks.Run(Path.Combine(root,"updater"),Check);
        Check(Widget.ResizeDimension(400,-500,300)==300 && Widget.ResizeDimension(400,120,300)==520,"bottom resize grows and shrinks while respecting minimum height");
        Check(DesktopIntegration.StartupCommand(@"C:\Example Folder\Dashboard.exe").EndsWith("\" --startup"),"startup quotes paths containing spaces");
        var snapshot=store.Snapshot(); snapshot.config.subject="not persisted";
        Check(store.Snapshot().config.subject!="not persisted","snapshots cannot mutate live state");
        try { store.Change(s=> { s.draft="partial"; throw new InvalidOperationException(); }); } catch(InvalidOperationException) {}
        Check(store.Snapshot().draft!="partial","failed mutations do not change committed state");
        store.Change(s=> { s.config.enabled=true; s.config.refreshToken="old-login"; });
        store.Disconnect(); File.WriteAllText(Path.Combine(root,"state.dat"),"corrupt-state");
        var recovered=new Store(root);
        Check(!recovered.Snapshot().config.enabled && recovered.Snapshot().config.refreshToken=="","backup recovery cannot resurrect a disconnected login");

    }
    class FakeDrive : HttpMessageHandler {
        public bool Health; public Dictionary<string,string> Files=new Dictionary<string,string>(); public Dictionary<string,int> UploadCounts=new Dictionary<string,int>(); public List<string> Writes=new List<string>(); int next; bool loseAck=true;
        protected override async Task<HttpResponseMessage> SendAsync(HttpRequestMessage request,CancellationToken cancellation) {
            string path=request.RequestUri.AbsolutePath; string method=request.Method.Method; string response="{}"; HttpStatusCode status=HttpStatusCode.OK;
            if(path.EndsWith("/token")) response="{\"access_token\":\"fake-access\",\"expires_in\":3600}";
            else if(path.EndsWith("/generateIds")) response="{\"ids\":[\"generated-"+(++next)+"\"]}";
            else if(path.EndsWith("/board")) response="{\"schemaVersion\":1,\"updates\":[],\"tasks\":[{\"id\":\"t\",\"title\":\"Shared task\"}]}";
            else if(path.EndsWith("/phone")) response=Health?"{\"schemaVersion\":1,\"tasks\":[],\"health\":{\"phoneFileId\":\"health\"}}":"{\"schemaVersion\":1,\"tasks\":[]}";
            else if(path.EndsWith("/health")) response="{\"sharing\":true,\"today\":{\"consumedKcal\":500,\"updatedAt\":0},\"readResults\":[\"private-health-detail\"]}";
            else if(method=="PATCH") { Writes.Add(path.Split('/').Last()); }
            else if(method=="GET") { var id=path.Split('/').Last(); if(Files.ContainsKey(id)) response="{\"id\":\""+id+"\",\"appProperties\":{\"captureId\":\""+Files[id]+"\"}}"; else status=HttpStatusCode.NotFound; }
            else if(method=="POST") {
                var content=await request.Content.ReadAsStringAsync(); var id=Regex.Match(content,"\"id\":\"([^\"]+)\"").Groups[1].Value; var capture=Regex.Match(content,"\"captureId\":\"([^\"]+)\"").Groups[1].Value;
                Files[id]=capture; UploadCounts[id]=UploadCounts.ContainsKey(id)?UploadCounts[id]+1:1;
                if(loseAck && !capture.StartsWith("dashboard-windows")) { loseAck=false; throw new HttpRequestException("simulated lost acknowledgment"); }
            }
            return new HttpResponseMessage(status) { Content=new StringContent(response) };
        }
    }
}
