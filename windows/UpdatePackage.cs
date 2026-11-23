using System;
using System.Collections.Generic;
using System.IO;
using System.IO.Compression;
using System.Linq;
using System.Security.Cryptography;
using System.Web.Script.Serialization;

namespace DashboardWindows {
    public class UpdateManifest { public int schemaVersion; public string version; public Dictionary<string,string> files; }
    public static class UpdatePackage {
        public static readonly string[] AppFiles={"Dashboard.exe","Dashboard.exe.config","Dashboard.Update.exe","AForge.dll","AForge.Video.dll","AForge.Video.DirectShow.dll","README.md","licenses/AForge-LICENSE.txt","licenses/GPL-3.0.txt","licenses/LGPL-3.0.txt"};
        public static string Hash(string file) { using(var hash=SHA256.Create()) using(var stream=File.OpenRead(file)) return BitConverter.ToString(hash.ComputeHash(stream)).Replace("-","").ToLowerInvariant(); }
        public static string Inside(string root,string relative) {
            if(String.IsNullOrEmpty(relative) || relative.Contains(':') || relative.Contains('\\') || Path.IsPathRooted(relative)) throw new InvalidDataException("Unsafe update path.");
            var full=Path.GetFullPath(Path.Combine(root,relative.Replace('/',Path.DirectorySeparatorChar)));
            if(!full.StartsWith(Path.GetFullPath(root).TrimEnd(Path.DirectorySeparatorChar)+Path.DirectorySeparatorChar,StringComparison.OrdinalIgnoreCase)) throw new InvalidDataException("Update path leaves its folder.");
            return full;
        }
        public static void Extract(string zip,string stage) {
            Directory.CreateDirectory(stage); long total=0; var seen=new HashSet<string>(StringComparer.OrdinalIgnoreCase);
            using(var archive=ZipFile.OpenRead(zip)) {
                if(archive.Entries.Count>32) throw new InvalidDataException("Unexpected update archive.");
                foreach(var entry in archive.Entries) {
                    var name=entry.FullName.Replace('\\','/'); var target=Inside(stage,name);
                    if(name.EndsWith("/")) { if(name!="licenses/") throw new InvalidDataException("Unexpected update folder."); continue; }
                    if(!seen.Add(name) || !(AppFiles.Contains(name,StringComparer.Ordinal) || name=="dashboard-update.json")) throw new InvalidDataException("Unexpected or duplicate update file.");
                    if(entry.Length>8*1024*1024 || total+entry.Length>32*1024*1024) throw new InvalidDataException("Update is too large.");
                    Directory.CreateDirectory(Path.GetDirectoryName(target));
                    using(var input=entry.Open()) using(var output=new FileStream(target,FileMode.CreateNew,FileAccess.Write)) {
                        byte[] buffer=new byte[8192]; long written=0; int n;
                        while((n=input.Read(buffer,0,buffer.Length))>0) { written+=n; total+=n; if(written>8*1024*1024 || total>32*1024*1024) throw new InvalidDataException("Update expanded beyond its size limit."); output.Write(buffer,0,n); }
                        output.Flush(true);
                    }
                }
            }
        }
        public static UpdateManifest Validate(string stage,string expectedVersion) {
            var manifestPath=Inside(stage,"dashboard-update.json");
            if(!File.Exists(manifestPath) || new FileInfo(manifestPath).Length>16384) throw new InvalidDataException("Update manifest is missing or too large.");
            var manifest=new JavaScriptSerializer { MaxJsonLength=16384 }.Deserialize<UpdateManifest>(File.ReadAllText(manifestPath));
            if(manifest==null || manifest.schemaVersion!=1 || String.IsNullOrEmpty(manifest.version) || manifest.version.Length>64 ||
                (expectedVersion!=null && manifest.version!=expectedVersion) || manifest.files==null || manifest.files.Count!=AppFiles.Length) throw new InvalidDataException("Update version or manifest does not match.");
            foreach(var name in AppFiles) {
                var file=Inside(stage,name);
                if(!manifest.files.ContainsKey(name) || !File.Exists(file) || !String.Equals(Hash(file),manifest.files[name],StringComparison.OrdinalIgnoreCase)) throw new InvalidDataException("Update file checksum mismatch: "+name);
            }
            if(System.Reflection.AssemblyName.GetAssemblyName(Inside(stage,"Dashboard.exe")).Name!="Dashboard") throw new InvalidDataException("This is not a Dashboard update.");
            return manifest;
        }
        static void AtomicCopy(string source,string destination) {
            Directory.CreateDirectory(Path.GetDirectoryName(destination)); var temp=destination+".update-"+Guid.NewGuid().ToString("N");
            try { File.Copy(source,temp,false); if(File.Exists(destination)) File.Replace(temp,destination,null); else File.Move(temp,destination); }
            finally { if(File.Exists(temp)) File.Delete(temp); }
        }
        public static void Apply(string stage,string destination) {
            Validate(stage,null); destination=Path.GetFullPath(destination);
            if(destination.TrimEnd(Path.DirectorySeparatorChar)==Path.GetPathRoot(destination).TrimEnd(Path.DirectorySeparatorChar) || destination.StartsWith(Path.GetFullPath(stage),StringComparison.OrdinalIgnoreCase)) throw new InvalidDataException("Invalid install folder.");
            var backup=Path.Combine(stage,"rollback"); Directory.CreateDirectory(backup); var existed=new HashSet<string>(); var touched=new List<string>();
            try {
                // Replace the main executable last. Only these app-owned files can be changed.
                foreach(var name in AppFiles.OrderBy(n=>n=="Dashboard.exe"?1:0)) {
                    var target=Inside(destination,name); var old=Inside(backup,name);
                    if(File.Exists(target)) { Directory.CreateDirectory(Path.GetDirectoryName(old)); File.Copy(target,old,true); existed.Add(name); }
                    AtomicCopy(Inside(stage,name),target); touched.Add(name);
                }
                foreach(var name in AppFiles) if(Hash(Inside(stage,name))!=Hash(Inside(destination,name))) throw new IOException("Installed update did not verify.");
            } catch {
                foreach(var name in touched.AsEnumerable().Reverse()) { var target=Inside(destination,name); if(existed.Contains(name)) AtomicCopy(Inside(backup,name),target); else if(File.Exists(target)) File.Delete(target); }
                throw;
            }
        }
    }
}
