using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.Linq;
using System.Net.Http;
using System.Text.RegularExpressions;
using System.Threading.Tasks;
using System.Windows;
using System.Windows.Controls;

namespace DashboardWindows {
    public class WindowsRelease { public string Tag,Summary,Url,Digest; public long Size; }
    public static class WindowsUpdates {
        const long MaxDownload=16*1024*1024;
        public static Version Version(string tag) {
            var m=Regex.Match(tag??"",@"^v?(\d+)\.(\d+)\.(\d+)(?:-preview\.(\d+))?$");
            if(!m.Success) throw new InvalidDataException("Unrecognized update version.");
            return new Version(Int32.Parse(m.Groups[1].Value),Int32.Parse(m.Groups[2].Value),Int32.Parse(m.Groups[3].Value),m.Groups[4].Success?Int32.Parse(m.Groups[4].Value):Int32.MaxValue);
        }
        public static WindowsRelease Parse(string raw) {
            var root=Json.Read<Dictionary<string,object>>(raw);
            if(Convert.ToBoolean(root["draft"]) || Convert.ToBoolean(root["prerelease"])) throw new InvalidDataException("This release is not published.");
            var tag=Convert.ToString(root["tag_name"]); Version(tag);
            var assets=(System.Collections.IList)root["assets"];
            foreach(Dictionary<string,object> asset in assets) if(Convert.ToString(asset["name"])=="Dashboard-Windows.zip") {
                var url=Convert.ToString(asset["browser_download_url"]); var digest=Convert.ToString(asset["digest"]); long size=Convert.ToInt64(asset["size"]);
                if(!url.StartsWith("https://github.com/AudacityMicro/Dashboard2.0/releases/download/",StringComparison.Ordinal) || !Regex.IsMatch(digest??"",@"^sha256:[0-9a-fA-F]{64}$") || size<1 || size>MaxDownload) throw new InvalidDataException("The Windows update is missing valid integrity information.");
                var summary=Convert.ToString(root["body"]).Replace("\r","").Split('\n').FirstOrDefault(line=>!String.IsNullOrWhiteSpace(line))??"Windows companion update";
                summary=summary.Trim().TrimStart('#',' '); if(summary.Length>180) summary=summary.Substring(0,177)+"…";
                return new WindowsRelease { Tag=tag,Summary=summary,Url=url,Digest=digest.Substring(7).ToLowerInvariant(),Size=size };
            }
            throw new InvalidDataException("The latest release does not include a Windows update yet.");
        }
        public static async Task<WindowsRelease> Check(HttpClient http) {
            using(var request=new HttpRequestMessage(HttpMethod.Get,"https://api.github.com/repos/AudacityMicro/Dashboard2.0/releases/latest")) {
                request.Headers.UserAgent.ParseAdd("Dashboard-Windows/"+BuildVersion.Name);
                using(var response=await http.SendAsync(request)) { response.EnsureSuccessStatusCode(); return Parse(await response.Content.ReadAsStringAsync()); }
            }
        }
        public static async Task<string> Download(HttpClient http,WindowsRelease release,string stagingRoot=null) {
            var folder=Path.Combine(stagingRoot??Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),"Dashboard2.0","Windows","updates"),Guid.NewGuid().ToString("N"));
            Directory.CreateDirectory(folder); var zip=Path.Combine(folder,"download.zip");
            using(var response=await http.GetAsync(release.Url,HttpCompletionOption.ResponseHeadersRead)) {
                response.EnsureSuccessStatusCode();
                using(var input=await response.Content.ReadAsStreamAsync()) using(var output=new FileStream(zip,FileMode.CreateNew,FileAccess.Write)) {
                    byte[] buffer=new byte[8192]; long total=0; int read;
                    while((read=await input.ReadAsync(buffer,0,buffer.Length))>0) { total+=read; if(total>MaxDownload || total>release.Size) throw new InvalidDataException("Update download exceeds its declared size."); await output.WriteAsync(buffer,0,read); }
                    if(total!=release.Size) throw new InvalidDataException("The update download was incomplete."); output.Flush(true);
                }
            }
            if(UpdatePackage.Hash(zip)!=release.Digest) throw new InvalidDataException("Update checksum verification failed. Nothing was installed.");
            // Extraction and hashing stay off the UI thread.
            await Task.Run(()=> { UpdatePackage.Extract(zip,folder); UpdatePackage.Validate(folder,release.Tag); }); return folder;
        }
        public static void Show(Window owner,Action<string> apply) {
            var panel=new StackPanel { Margin=new Thickness(20) }; var message=Widget.Text("Checking GitHub…"); panel.Children.Add(message);
            var install=Widget.Button("Download and install",null); install.IsEnabled=false; panel.Children.Add(install);
            var close=Widget.Button("Close",null); panel.Children.Add(close);
            var window=new Window { Owner=owner,ShowInTaskbar=false,Title="Update Dashboard",Width=440,Height=240,WindowStartupLocation=WindowStartupLocation.CenterOwner,Background=System.Windows.Media.Brushes.Black,Content=panel };
            close.Click+=(s,e)=>window.Close();
            WindowsRelease release=null; bool closed=false; window.Closed+=(s,e)=>closed=true;
            var http=new HttpClient { Timeout=TimeSpan.FromSeconds(60),MaxResponseContentBufferSize=2*1024*1024 };
            window.Loaded+=async (s,e)=> {
                try {
                    release=await Check(http);
                    if(closed) return;
                    int comparison=Version(release.Tag).CompareTo(Version(BuildVersion.Name));
                    message.Text=comparison>0?release.Tag+"\n\n"+release.Summary:comparison==0?"Dashboard is up to date.\n\n"+release.Summary:"This preview is newer than the published Windows release.";
                    install.IsEnabled=comparison>0;
                } catch(Exception error) { message.Text="Could not check for updates: "+error.Message; }
            };
            install.Click+=async (s,e)=> {
                install.IsEnabled=false; close.IsEnabled=false; message.Text="Downloading and verifying the Windows update…";
                try { var stage=await Download(http,release); if(closed) return; window.Close(); apply(stage); }
                catch(Exception error) { message.Text=error.Message; close.IsEnabled=true; }
            };
            try { window.ShowDialog(); } finally { http.Dispose(); }
        }
    }
}
