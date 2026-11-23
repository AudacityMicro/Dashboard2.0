using System;
using System.Collections.Generic;
using System.IO;
using System.IO.Compression;
using System.Linq;
using System.Net;
using System.Net.Http;
using System.Threading;
using System.Threading.Tasks;
using DashboardWindows;

static class UpdateChecks {
    public static async Task Run(string root,Action<bool,string> check) {
        Directory.CreateDirectory(root);
        var repo=new DirectoryInfo(Directory.GetCurrentDirectory()); while(repo!=null && !File.Exists(Path.Combine(repo.FullName,"app/app/build.gradle.kts"))) repo=repo.Parent;
        if(repo==null) throw new Exception("Repository root not found for package fixture.");
        string stage=Path.Combine(root,"package"); Directory.CreateDirectory(stage);
        var manifest=new UpdateManifest { schemaVersion=1,version=BuildVersion.Tag,files=new Dictionary<string,string>() };
        foreach(var name in UpdatePackage.AppFiles) {
            string source=name=="README.md" || name.StartsWith("licenses/")?Path.Combine(repo.FullName,"windows",name.Replace('/',Path.DirectorySeparatorChar)):Path.Combine(AppDomain.CurrentDomain.BaseDirectory,name);
            var target=UpdatePackage.Inside(stage,name); Directory.CreateDirectory(Path.GetDirectoryName(target)); File.Copy(source,target);
            manifest.files[name]=UpdatePackage.Hash(target);
        }
        File.WriteAllText(Path.Combine(stage,"dashboard-update.json"),Json.Write(manifest));
        check(UpdatePackage.Validate(stage,BuildVersion.Tag).version==BuildVersion.Tag,"Windows update validates every packaged app file and version");
        check(WindowsUpdates.Version("v0.1.0-preview.23")>WindowsUpdates.Version("v0.1.0-preview.22") && WindowsUpdates.Version("v0.1.0")>WindowsUpdates.Version("v0.1.0-preview.99"),"update version comparison orders previews and stable releases correctly");
        string zip=Path.Combine(root,"update.zip"); ZipFile.CreateFromDirectory(stage,zip);
        var release=WindowsUpdates.Parse(Json.Write(new { draft=false,prerelease=false,tag_name=BuildVersion.Tag,body="What's new: Compact controls and safer sync.\n\nDetails.",assets=new[] { new { name="Dashboard-Windows.zip",browser_download_url="https://github.com/AudacityMicro/Dashboard2.0/releases/download/"+BuildVersion.Tag+"/Dashboard-Windows.zip",digest="sha256:"+UpdatePackage.Hash(zip),size=new FileInfo(zip).Length } } }));
        check(release.Summary=="What's new: Compact controls and safer sync.","update dialog uses one-line patch notes");
        using(var http=new HttpClient(new ZipServer(File.ReadAllBytes(zip)))) {
            var downloaded=await WindowsUpdates.Download(http,release,Path.Combine(root,"downloads"));
            var destination=Path.Combine(root,"installed"); Directory.CreateDirectory(destination);
            File.WriteAllText(Path.Combine(destination,"user-state.marker"),"preserve me");
            UpdatePackage.Apply(downloaded,destination);
            check(UpdatePackage.Hash(Path.Combine(destination,"Dashboard.exe"))==manifest.files["Dashboard.exe"],"verified update installs the new executable");
            check(File.ReadAllText(Path.Combine(destination,"user-state.marker"))=="preserve me","self-update does not replace unrelated user files");
            File.WriteAllText(Path.Combine(destination,"Dashboard.exe.config"),"old configuration");
            using(var locked=new FileStream(Path.Combine(destination,"AForge.Video.dll"),FileMode.Open,FileAccess.Read,FileShare.None)) {
                try { UpdatePackage.Apply(downloaded,destination); throw new Exception("locked install unexpectedly succeeded"); } catch(IOException) {}
            }
            check(File.ReadAllText(Path.Combine(destination,"Dashboard.exe.config"))=="old configuration","a mid-install failure rolls back files already replaced");
            File.AppendAllText(Path.Combine(downloaded,"AForge.dll"),"corrupt");
            try { UpdatePackage.Apply(downloaded,destination); throw new Exception("corrupt update accepted"); } catch(InvalidDataException) {}
            check(UpdatePackage.Hash(Path.Combine(destination,"Dashboard.exe"))==manifest.files["Dashboard.exe"],"corrupt staged update is rejected before installed files change");
            release.Digest=new string('0',64);
            try { await WindowsUpdates.Download(http,release,Path.Combine(root,"bad-digest")); throw new Exception("bad digest accepted"); } catch(InvalidDataException) {}
            check(!Directory.GetFiles(Path.Combine(root,"bad-digest"),"Dashboard.exe",SearchOption.AllDirectories).Any(),"a bad download digest is rejected before extraction");
        }
        string malicious=Path.Combine(root,"traversal.zip"); using(var archive=ZipFile.Open(malicious,ZipArchiveMode.Create)) using(var writer=new StreamWriter(archive.CreateEntry("../outside.txt").Open())) writer.Write("escape");
        try { UpdatePackage.Extract(malicious,Path.Combine(root,"unsafe-stage")); throw new Exception("unsafe archive accepted"); } catch(InvalidDataException) {}
        check(!File.Exists(Path.Combine(root,"outside.txt")),"update extraction rejects directory traversal");
    }
    sealed class ZipServer : HttpMessageHandler {
        readonly byte[] bytes; public ZipServer(byte[] bytes) { this.bytes=bytes; }
        protected override Task<HttpResponseMessage> SendAsync(HttpRequestMessage request,CancellationToken cancellation) { return Task.FromResult(new HttpResponseMessage(HttpStatusCode.OK) { Content=new ByteArrayContent(bytes) }); }
    }
}
