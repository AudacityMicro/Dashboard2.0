using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Security.Cryptography;
using System.Text;
using System.Web.Script.Serialization;

namespace DashboardWindows {
    public static class Json {
        public static readonly JavaScriptSerializer Codec = new JavaScriptSerializer { MaxJsonLength = 2 * 1024 * 1024, RecursionLimit = 40 };
        public static string Write(object value) { lock (Codec) return Codec.Serialize(value); }
        public static T Read<T>(string value) { lock (Codec) return Codec.Deserialize<T>(value); }
    }
    public class Update { public string id, title, body; }
    public class Todo { public string id, title, due, source; public bool completed; }
    public class Edit : Todo { public long revision; public string changedAt; }
    public class Ack { public string deviceId, taskId; public long revision; }
    public class Board {
        public int schemaVersion = 1; public List<Update> updates = new List<Update>(); public List<Todo> tasks = new List<Todo>();
        public List<Ack> acknowledgedTaskEdits = new List<Ack>();
        public static Board Parse(string raw) {
            if (Encoding.UTF8.GetByteCount(raw) > 512 * 1024) throw new InvalidDataException("Dashboard file exceeds 512 KiB.");
            var root=Json.Read<Dictionary<string,object>>(raw);
            if(root==null || !root.ContainsKey("schemaVersion") || Convert.ToDecimal(root["schemaVersion"])!=1 ||
                !root.ContainsKey("updates") || !(root["updates"] is System.Collections.IList) || !root.ContainsKey("tasks") || !(root["tasks"] is System.Collections.IList)) throw new InvalidDataException("Invalid dashboard envelope.");
            if(root.ContainsKey("acknowledgedTaskEdits")) {
                var receipts=root["acknowledgedTaskEdits"] as System.Collections.IList;
                if(receipts==null || receipts.Count>2000) throw new InvalidDataException("Invalid task receipts.");
                foreach(var item in receipts) {
                    var receipt=item as Dictionary<string,object>;
                    if(receipt==null || !receipt.ContainsKey("revision")) throw new InvalidDataException("Invalid task receipt.");
                    decimal revision=Convert.ToDecimal(receipt["revision"]);
                    if(revision<=0 || revision>9000000000000000L || revision!=Decimal.Truncate(revision)) throw new InvalidDataException("Invalid task receipt revision.");
                }
            }
            var b = Json.Read<Board>(raw);
            if (b == null || b.schemaVersion != 1 || b.tasks == null || b.updates == null || b.tasks.Count > 200 || b.updates.Count > 50)
                throw new InvalidDataException("Invalid dashboard file. Keeping your last saved view.");
            if (b.tasks.Any(t => t == null || !ValidId(t.id) || String.IsNullOrWhiteSpace(t.title) || t.title.Length > 300 || (t.due??"").Length>100) || b.tasks.Select(t => t.id).Distinct().Count() != b.tasks.Count ||
                b.updates.Any(u => u == null || !ValidId(u.id) || (u.body ?? "").Length > 4000 || String.IsNullOrWhiteSpace(u.title) || u.title.Length > 200) || b.updates.Select(u=>u.id).Distinct().Count()!=b.updates.Count) throw new InvalidDataException("Invalid dashboard entries.");
            if((b.acknowledgedTaskEdits??new List<Ack>()).Any(a=>a==null || !ValidId(a.deviceId) || !ValidId(a.taskId))) throw new InvalidDataException("Invalid receipt identifiers.");
            return b;
        }
        public static bool ValidId(string id) { return !String.IsNullOrEmpty(id) && id.Length <= 100 && !id.Any(Char.IsControl); }
    }
    public class Capture {
        public string id, kind, mime, name, text = "", createdAt, state = "pending", remote = "", manifest = "", transcriptRemote = "";
        public string voiceMode = "both", transcriptionStatus = "none";
    }
    public class Config {
        public string clientId = "", clientSecret = "", refreshToken = "", subject = "", email = "";
        public string agentFile = "", phoneFile = "", instructionsFile = "", desktopFile = "";
        public string voiceMode = "both"; public double opacity = 0.62, left = 100, top = 100, width = 420, height = 650;
        public bool topmost, enabled, showHealth;
    }
    public class State {
        public Config config = new Config(); public string deviceId = Guid.NewGuid().ToString("N"), draft = "", health = "", phoneState = "";
        public long revision; public string lastSync = ""; public Board board = new Board();
        public List<Edit> edits = new List<Edit>(); public List<Capture> captures = new List<Capture>();
    }
    public sealed class Store {
        readonly object gate = new object(); public readonly string Root; State state;
        public Store(string root) {
            Root = root; Directory.CreateDirectory(root); Directory.CreateDirectory(Path.Combine(root, "captures"));
            var path = Path.Combine(root, "state.dat");
            if (!File.Exists(path)) state = new State();
            else { try { state = Decode(path); } catch { if (!File.Exists(path + ".bak")) throw new InvalidDataException("Saved data could not be opened. Restore a backup; your files have not been reset."); state = Decode(path + ".bak"); } }
            if(File.Exists(Path.Combine(root,"disconnected"))) { state.config.enabled=false; state.config.refreshToken=""; }
        }
        State Decode(string path) { return Json.Read<State>(Encoding.UTF8.GetString(ProtectedData.Unprotect(File.ReadAllBytes(path), null, DataProtectionScope.CurrentUser))); }
        public State Snapshot() { lock (gate) return Json.Read<State>(Json.Write(state)); }
        public void Disconnect() {
            using(var f=new FileStream(Path.Combine(Root,"disconnected"),FileMode.Create,FileAccess.Write,FileShare.None)) { f.WriteByte(1); f.Flush(true); }
            Change(s=> { s.config.enabled=false; s.config.refreshToken=""; });
        }
        public void ConfirmConnection() { var s=Snapshot(); if(!s.config.enabled || s.config.refreshToken.Length==0) throw new InvalidOperationException("Google connection is incomplete."); File.Delete(Path.Combine(Root,"disconnected")); }
        public void Change(Action<State> change) { lock (gate) {
            var previous=state; var next=Json.Read<State>(Json.Write(state)); change(next); state=next;
            try { Save(); } catch { state=previous; throw; }
        } }
        void Save() {
            var path = Path.Combine(Root, "state.dat"); var temp = path + ".tmp";
            var bytes = ProtectedData.Protect(Encoding.UTF8.GetBytes(Json.Write(state)), null, DataProtectionScope.CurrentUser);
            using (var file = new FileStream(temp, FileMode.Create, FileAccess.Write, FileShare.None)) { file.Write(bytes, 0, bytes.Length); file.Flush(true); }
            if (File.Exists(path)) File.Replace(temp, path, path + ".bak"); else File.Move(temp, path);
        }
        public string Payload(string id) { Guid parsed; if (!Guid.TryParse(id, out parsed)) throw new InvalidDataException("Invalid capture ID."); return Path.Combine(Root, "captures", id); }
        public void Accept(string raw) {
            var board = Board.Parse(raw);
            Change(s => {
                foreach (var ack in board.acknowledgedTaskEdits ?? new List<Ack>()) {
                    if (ack != null && ack.deviceId == s.deviceId && board.tasks.Any(t => t.id == ack.taskId))
                        s.edits.RemoveAll(e => e.id == ack.taskId && e.revision == ack.revision);
                }
                s.board = board;
            });
        }
        public void SetTask(Todo task, bool completed) {
            Change(s => {
                s.revision++;
                s.edits.RemoveAll(e => e.id == task.id);
                s.edits.Add(new Edit { id = task.id, title = task.title, due = task.due ?? "", completed = completed, source = "windows", revision = s.revision, changedAt = DateTimeOffset.UtcNow.ToString("o") });
            });
        }
        public List<Todo> Tasks() {
            var s = Snapshot(); var all = s.board.tasks.ToDictionary(t => t.id);
            if (!String.IsNullOrEmpty(s.phoneState)) try {
                var entries = Json.Read<PhoneTasks>(s.phoneState);
                foreach (var t in entries.tasks ?? new List<Todo>()) if (t!=null && Board.ValidId(t.id) && !String.IsNullOrWhiteSpace(t.title)) {
                    bool pending=(entries.taskEdits??new List<Edit>()).Any(edit=>edit.id==t.id && !(s.board.acknowledgedTaskEdits??new List<Ack>()).Any(a=>a.deviceId==entries.deviceId && a.taskId==edit.id && a.revision==edit.revision));
                    if(!all.ContainsKey(t.id) || pending) all[t.id] = t;
                }
            } catch { }
            foreach (var e in s.edits) all[e.id] = e;
            return all.Values.OrderBy(t => t.completed).ToList();
        }
        public Capture Text(string text) {
            if (String.IsNullOrWhiteSpace(text) || text.Length > 100000) throw new InvalidDataException("Enter a note of at most 100,000 characters.");
            var c = New("text", "text/plain", "note.txt"); c.text = text;
            var bytes=new UTF8Encoding(false).GetBytes(text);
            using(var file=new FileStream(Payload(c.id),FileMode.CreateNew,FileAccess.Write,FileShare.None)) { file.Write(bytes,0,bytes.Length); file.Flush(true); }
            Change(s => { s.captures.Add(c); s.draft = ""; }); return c;
        }
        public Capture Import(string file, string kind, string mime) {
            if (new FileInfo(file).Length > 25 * 1024 * 1024) throw new InvalidDataException("Files are limited to 25 MiB.");
            var c = New(kind, mime, Path.GetFileName(file)); File.Copy(file, Payload(c.id), false);
            using(var copy=new FileStream(Payload(c.id),FileMode.Open,FileAccess.Write)) copy.Flush(true);
            Change(s => s.captures.Add(c)); return c;
        }
        public static Capture New(string kind, string mime, string name) { return new Capture { id = Guid.NewGuid().ToString(), kind = kind, mime = mime, name = name, createdAt = DateTimeOffset.UtcNow.ToString("o") }; }
        public object Export() { var s = Snapshot(); return new { schemaVersion = 1, platform = "windows", deviceId = s.deviceId, revision = s.revision,
            updatedAt = DateTimeOffset.UtcNow.ToString("o"), tasks = Tasks(), taskEdits = s.edits }; }
    }
    public class PhoneTasks { public string deviceId; public List<Todo> tasks; public List<Edit> taskEdits; public HealthRegistry health; }
    public class HealthRegistry { public string phoneFileId, agentFileId; }
}
