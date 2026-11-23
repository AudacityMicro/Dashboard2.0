using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.Linq;
using System.Net;
using System.Net.Http;
using System.Net.Http.Headers;
using System.Net.Sockets;
using System.Security.Cryptography;
using System.Text;
using System.Threading;
using System.Threading.Tasks;

namespace DashboardWindows {
    public sealed class Google {
        readonly Store store; readonly HttpClient http; string access = ""; DateTime expires;
        public Google(Store store, HttpClient client) { this.store = store; http = client; }
        public static string Random() { var b = new byte[32]; using (var r = RandomNumberGenerator.Create()) r.GetBytes(b); return Convert.ToBase64String(b).TrimEnd('=').Replace('+','-').Replace('/','_'); }
        public static string Challenge(string verifier) { using (var h = SHA256.Create()) return Convert.ToBase64String(h.ComputeHash(Encoding.ASCII.GetBytes(verifier))).TrimEnd('=').Replace('+','-').Replace('/','_'); }
        public static string Form(IEnumerable<KeyValuePair<string,string>> values) { return String.Join("&", values.Select(v => Uri.EscapeDataString(v.Key) + "=" + Uri.EscapeDataString(v.Value))); }
        public async Task SignIn(CancellationToken cancellation) {
            var cfg = store.Snapshot().config;
            if (!cfg.clientId.EndsWith(".apps.googleusercontent.com", StringComparison.Ordinal)) throw new InvalidDataException("Import a Google Desktop OAuth client JSON in Settings first.");
            var verifier = Random(); var state = Random(); var listener = new TcpListener(IPAddress.Loopback, 0); listener.Start();
            try {
                var redirect = "http://127.0.0.1:" + ((IPEndPoint)listener.LocalEndpoint).Port + "/";
                var args = new Dictionary<string,string> { {"client_id",cfg.clientId}, {"redirect_uri",redirect}, {"response_type","code"},
                    {"scope","openid email https://www.googleapis.com/auth/drive.file"}, {"access_type","offline"}, {"prompt","consent"},
                    {"state",state}, {"code_challenge",Challenge(verifier)}, {"code_challenge_method","S256"} };
                Process.Start(new ProcessStartInfo("https://accounts.google.com/o/oauth2/v2/auth?" + Form(args)) { UseShellExecute = true });
                var deadline = DateTime.UtcNow.AddMinutes(3); string code = null;
                using (cancellation.Register(() => listener.Stop())) {
                    while (code == null && DateTime.UtcNow < deadline) {
                        var accept = listener.AcceptTcpClientAsync();
                        if (await Task.WhenAny(accept, Task.Delay(TimeSpan.FromSeconds(180), cancellation)) != accept) throw new TimeoutException("Google sign-in timed out. Try again.");
                        using (var socket = await accept) {
                            socket.ReceiveTimeout = 5000; socket.SendTimeout = 5000;
                            using (var stream = socket.GetStream()) {
                                string request = await Task.Run(() => ReadHeader(stream));
                                var first = request.Split('\n')[0].Split(' ');
                                var query = first.Length > 1 ? System.Web.HttpUtility.ParseQueryString(new Uri(new Uri(redirect), first[1]).Query) : null;
                                bool valid = query != null && query["state"] == state && !String.IsNullOrEmpty(query["code"]);
                                var body = Encoding.UTF8.GetBytes(valid ? "Google sign-in received. Return to Dashboard." : "This request was not accepted. Return to Dashboard or retry sign-in.");
                                var header = Encoding.ASCII.GetBytes("HTTP/1.1 " + (valid ? "200 OK" : "400 Bad Request") + "\r\nContent-Type: text/plain; charset=utf-8\r\nContent-Security-Policy: default-src 'none'\r\nConnection: close\r\nContent-Length: " + body.Length + "\r\n\r\n");
                                stream.Write(header,0,header.Length); stream.Write(body,0,body.Length);
                                if (query != null && query["state"] == state && query["error"] != null) throw new InvalidOperationException("Google sign-in was declined.");
                                if (valid) code = query["code"];
                            }
                        }
                    }
                }
                if (code == null) throw new TimeoutException("Google sign-in timed out.");
                var values = new Dictionary<string,string> { {"client_id",cfg.clientId}, {"code",code}, {"redirect_uri",redirect}, {"grant_type","authorization_code"}, {"code_verifier",verifier} };
                if (!String.IsNullOrEmpty(cfg.clientSecret)) values["client_secret"] = cfg.clientSecret;
                var token = await Token(values);
                var identity = Json.Read<Dictionary<string,object>>(await Api("GET", "https://openidconnect.googleapis.com/v1/userinfo", null, Convert.ToString(token["access_token"])));
                var subject = Convert.ToString(identity["sub"]);
                if (!String.IsNullOrEmpty(cfg.subject) && subject != cfg.subject) throw new InvalidOperationException("Reconnect the original Google account. Existing captures and dashboard files stay pinned to that account.");
                if (!token.ContainsKey("refresh_token")) throw new InvalidDataException("Google did not return offline access. Reconnect and approve access.");
                access = Convert.ToString(token["access_token"]); expires = DateTime.UtcNow.AddSeconds(Convert.ToDouble(token["expires_in"]) - 60);
                store.Change(s => { s.config.refreshToken = Convert.ToString(token["refresh_token"]); s.config.subject = subject; s.config.email = Convert.ToString(identity["email"]); s.config.enabled = true; });
                store.ConfirmConnection();
            } finally { listener.Stop(); }
        }
        static string ReadHeader(Stream stream) {
            var b = new MemoryStream(); int next;
            while ((next = stream.ReadByte()) >= 0) { b.WriteByte((byte)next); if (b.Length > 16384) throw new InvalidDataException("OAuth callback too large.");
                var a = b.GetBuffer(); var n = (int)b.Length; if (n >= 4 && a[n-4] == 13 && a[n-3] == 10 && a[n-2] == 13 && a[n-1] == 10) return Encoding.ASCII.GetString(a,0,n); }
            throw new InvalidDataException("Incomplete OAuth callback.");
        }
        async Task<Dictionary<string,object>> Token(Dictionary<string,string> values) {
            using (var response = await http.PostAsync("https://oauth2.googleapis.com/token", new FormUrlEncodedContent(values))) {
                if (!response.IsSuccessStatusCode) throw new InvalidOperationException("Google authorization expired or the Desktop client is invalid. Reconnect in Settings.");
                return Json.Read<Dictionary<string,object>>(await response.Content.ReadAsStringAsync());
            }
        }
        public async Task<string> Access() {
            var c = store.Snapshot().config;
            if (!c.enabled || String.IsNullOrEmpty(c.refreshToken)) throw new InvalidOperationException("Connect Google Drive in Settings.");
            if (DateTime.UtcNow < expires && access.Length > 0) return access;
            var values = new Dictionary<string,string> { {"client_id",c.clientId}, {"refresh_token",c.refreshToken}, {"grant_type","refresh_token"} };
            if (!String.IsNullOrEmpty(c.clientSecret)) values["client_secret"] = c.clientSecret;
            var t = await Token(values); access = Convert.ToString(t["access_token"]); expires = DateTime.UtcNow.AddSeconds(Convert.ToDouble(t["expires_in"]) - 60); return access;
        }
        public async Task<string> Api(string method, string url, HttpContent content = null, string token = null) {
            using (var request = new HttpRequestMessage(new HttpMethod(method), url)) {
                request.Headers.Authorization = new AuthenticationHeaderValue("Bearer", token ?? await Access()); request.Content = content;
                using (var response = await http.SendAsync(request)) {
                    if (!response.IsSuccessStatusCode) { if ((int)response.StatusCode == 401) { access = ""; expires = DateTime.MinValue; }
                        throw new DriveException((int)response.StatusCode); }
                    return await response.Content.ReadAsStringAsync();
                }
            }
        }
        public async Task<string> NewId() { var j = Json.Read<Dictionary<string,object>>(await Api("GET","https://www.googleapis.com/drive/v3/files/generateIds?count=1&space=drive&type=files")); return Convert.ToString(((System.Collections.IList)j["ids"])[0]); }
        public async Task Ensure(string id, string key, string name, string mime, Func<HttpContent> body) {
            try {
                var existing = Json.Read<RemoteFile>(await Api("GET","https://www.googleapis.com/drive/v3/files/"+Uri.EscapeDataString(id)+"?fields=id,appProperties,trashed"));
                if (existing.trashed || existing.appProperties == null || !existing.appProperties.ContainsKey("captureId") || existing.appProperties["captureId"] != key) throw new InvalidDataException("Drive file identity conflict. No file was overwritten.");
                return;
            } catch (DriveException e) { if (e.Code != 404) throw; }
            using (var multi = new MultipartContent("related")) {
                multi.Add(new StringContent(Json.Write(new { id = id, name = name, appProperties = new { captureId = key, source = "Dashboard2.0" } }),Encoding.UTF8,"application/json"));
                var payload=body(); payload.Headers.ContentType=new MediaTypeHeaderValue(mime); multi.Add(payload);
                try { await Api("POST","https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart",multi); }
                catch (DriveException e) { if (e.Code == 409) throw new IOException("Upload awaiting reconciliation; retrying with the same file ID."); throw; }
            }
        }
        public async Task<List<RemoteFile>> Find(string name) {
            var found=new List<RemoteFile>(); string next="";
            do { var q="trashed = false and name = '"+name.Replace("'","\\'")+"'";
                var raw=await Api("GET","https://www.googleapis.com/drive/v3/files?q="+Uri.EscapeDataString(q)+"&fields=nextPageToken,files(id,name,appProperties)&pageSize=100"+(next.Length==0?"":"&pageToken="+Uri.EscapeDataString(next)));
                var page=Json.Read<FilePage>(raw); found.AddRange(page.files); next=page.nextPageToken??"";
                if (found.Count>500) throw new InvalidDataException("Too many matching files. Paste exact IDs from the phone setup prompt.");
            } while(next.Length>0); return found;
        }
        public Task<string> Read(string id) { return Api("GET","https://www.googleapis.com/drive/v3/files/"+Uri.EscapeDataString(id)+"?alt=media"); }
        public Task<string> Write(string id, object value) { return Api("PATCH","https://www.googleapis.com/upload/drive/v3/files/"+Uri.EscapeDataString(id)+"?uploadType=media",new StringContent(Json.Write(value),Encoding.UTF8,"application/json")); }
    }
    public class DriveException : IOException { public readonly int Code; public DriveException(int code):base("Google Drive returned " + code + ". Check connection, account and file access.") { Code=code; } }
    public class RemoteFile { public string id,name; public bool trashed; public Dictionary<string,string> appProperties; public override string ToString() { return name + " · " + id; } }
    public class FilePage { public List<RemoteFile> files; public string nextPageToken; }

    public sealed class Sync {
        readonly Store store; readonly Google google; readonly SemaphoreSlim gate=new SemaphoreSlim(1,1);
        public Sync(Store store,Google google) { this.store=store; this.google=google; }
        public async Task Run() {
            if (!await gate.WaitAsync(0)) return;
            try {
                var s=store.Snapshot(); if(!s.config.enabled) return;
                if(String.IsNullOrEmpty(s.config.agentFile) || String.IsNullOrEmpty(s.config.phoneFile)) throw new InvalidOperationException("Choose your phone's dashboard in Settings, or paste its setup prompt.");
                store.Accept(await google.Read(s.config.agentFile));
                var phone=await google.Read(s.config.phoneFile); Json.Read<PhoneTasks>(phone); store.Change(v=>v.phoneState=phone);
                var registry=Json.Read<PhoneTasks>(phone).health;
                if(s.config.showHealth && registry!=null && !String.IsNullOrEmpty(registry.phoneFileId)) {
                    var health=Json.Read<Dictionary<string,object>>(await google.Read(registry.phoneFileId));
                    // The PC widget caches totals/goals only, never the agent's detailed health query pages.
                    var summary=health.Where(p=>new[] { "enabled","sharing","today","goal","updatedAt" }.Contains(p.Key)).ToDictionary(p=>p.Key,p=>p.Value);
                    store.Change(v=>v.health=Json.Write(summary));
                }
                if(String.IsNullOrEmpty(s.config.desktopFile)) { var id=await google.NewId(); store.Change(v=>v.config.desktopFile=id); s=store.Snapshot(); }
                await google.Ensure(s.config.desktopFile,"dashboard-windows-"+s.deviceId,"Dashboard-windows-"+s.deviceId+"-tasks.json","application/json",()=>new StringContent(Json.Write(store.Export()),Encoding.UTF8,"application/json"));
                await google.Write(s.config.desktopFile,store.Export());
                foreach(var original in store.Snapshot().captures.Where(c=>c.state=="pending").ToList()) await Upload(original);
                store.Change(v=>v.lastSync=DateTimeOffset.UtcNow.ToString("o"));
            } finally { gate.Release(); }
        }
        async Task Upload(Capture c) {
            if(c.voiceMode=="transcript" && c.kind=="voice" && c.transcriptionStatus!="ready") return;
            if(String.IsNullOrEmpty(c.remote)) { var id=await google.NewId(); store.Change(s=>s.captures.Single(x=>x.id==c.id).remote=id); c.remote=id; }
            if(String.IsNullOrEmpty(c.manifest)) { var id=await google.NewId(); store.Change(s=>s.captures.Single(x=>x.id==c.id).manifest=id); c.manifest=id; }
            bool transcript=c.kind=="voice" && c.voiceMode=="transcript";
            await google.Ensure(c.remote,c.id,"Dashboard-"+c.id+"-"+(transcript?"transcript.txt":c.name),transcript?"text/plain":c.mime,
                ()=>transcript?(HttpContent)new StringContent(c.text,Encoding.UTF8):new StreamContent(File.OpenRead(store.Payload(c.id))));
            if(c.kind=="voice" && c.voiceMode=="both" && c.transcriptionStatus=="ready") {
                if(String.IsNullOrEmpty(c.transcriptRemote)) { var id=await google.NewId(); store.Change(s=>s.captures.Single(x=>x.id==c.id).transcriptRemote=id); c.transcriptRemote=id; }
                await google.Ensure(c.transcriptRemote,c.id,"Dashboard-"+c.id+"-transcript.txt","text/plain",()=>new StringContent(c.text,Encoding.UTF8));
            }
            var manifest=new { schemaVersion=1,id=c.id,kind=c.kind,createdAt=c.createdAt,mimeType=transcript?"text/plain":c.mime,
                text=c.voiceMode=="audio" && c.kind=="voice"?"":c.text,driveFileId=c.remote,voiceMode=c.voiceMode,originalMimeType=c.mime,
                transcriptFileId=transcript?c.remote:c.transcriptRemote,transcriptionStatus=c.transcriptionStatus,originalName=c.name,source="Dashboard2.0",devicePlatform="windows" };
            await google.Ensure(c.manifest,c.id,"Dashboard-"+c.id+".json","application/json",()=>new StringContent(Json.Write(manifest),Encoding.UTF8));
            store.Change(s=>s.captures.Single(x=>x.id==c.id).state="synced");
        }
    }
}
