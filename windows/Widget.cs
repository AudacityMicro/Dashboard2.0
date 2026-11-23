using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.Linq;
using System.Net;
using System.Net.Http;
using System.Text;
using System.Text.RegularExpressions;
using System.Threading;
using System.Threading.Tasks;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Documents;
using System.Windows.Input;
using System.Windows.Media;
using System.Windows.Media.Imaging;
using System.Windows.Threading;
using Microsoft.Win32;

namespace DashboardWindows {
    public static class Program {
        [STAThread] public static void Main(string[] args) {
            ServicePointManager.SecurityProtocol=SecurityProtocolType.Tls12;
            bool created; using(var single=new Mutex(true,"Local\\DashboardWindowsWidget",out created)) {
                if(!created) return;
                try {
                    var root=Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),"Dashboard2.0","Windows");
                    if(args.Length==2 && args[0]=="--test-data") root=Path.GetFullPath(args[1]);
                    var app=new Application(); app.ShutdownMode=ShutdownMode.OnMainWindowClose;
                    var styles="<ResourceDictionary xmlns='http://schemas.microsoft.com/winfx/2006/xaml/presentation'><Style TargetType='Button'><Setter Property='Foreground' Value='White'/><Setter Property='Background' Value='#253F4959'/><Setter Property='BorderThickness' Value='0'/><Setter Property='Padding' Value='12,8'/><Setter Property='Margin' Value='3'/><Setter Property='Cursor' Value='Hand'/><Setter Property='Template'><Setter.Value><ControlTemplate TargetType='Button'><Border Background='{TemplateBinding Background}' CornerRadius='12' Padding='{TemplateBinding Padding}'><ContentPresenter HorizontalAlignment='Center' VerticalAlignment='Center'/></Border></ControlTemplate></Setter.Value></Setter></Style><Style TargetType='TextBox'><Setter Property='Background' Value='#353F4959'/><Setter Property='Foreground' Value='White'/><Setter Property='CaretBrush' Value='White'/><Setter Property='BorderThickness' Value='0'/><Setter Property='Padding' Value='10'/><Setter Property='Margin' Value='3'/></Style><Style TargetType='CheckBox'><Setter Property='Foreground' Value='White'/><Setter Property='Margin' Value='4,7'/></Style></ResourceDictionary>";
                    app.Resources=(ResourceDictionary)System.Windows.Markup.XamlReader.Parse(styles);
                    app.Run(new Widget(new Store(root)));
                } catch(Exception e) { MessageBox.Show("Dashboard could not start. Your data has not been reset.\n"+e.Message,"Dashboard"); }
            }
        }
    }
    public sealed class Widget : Window {
        readonly Store store; readonly Google google; readonly Sync sync; readonly HttpClient http;
        readonly StackPanel body=new StackPanel(); readonly TextBlock status=Text("");
        readonly Button voice=Button("●",null); readonly DispatcherTimer timer=new DispatcherTimer(), clock=new DispatcherTimer();
        readonly CancellationTokenSource lifetime=new CancellationTokenSource();
        readonly SolidColorBrush surface=new SolidColorBrush();
        Recorder recording; Capture active; DateTime began; bool stopping, rendering;
        public Widget(Store store) {
            this.store=store; http=new HttpClient { Timeout=TimeSpan.FromSeconds(60),MaxResponseContentBufferSize=2*1024*1024 };
            google=new Google(store,http); sync=new Sync(store,google);
            var c=store.Snapshot().config;
            surface.Color=Color.FromArgb((byte)(255*c.opacity),18,23,31);
            Title="Dashboard Windows"; WindowStyle=WindowStyle.None; AllowsTransparency=true; Background=Brushes.Transparent;
            Foreground=Brushes.White; FontFamily=new FontFamily("Segoe UI"); FontSize=14;
            Width=Math.Max(300,Math.Min(c.width,SystemParameters.WorkArea.Width)); Height=Math.Max(300,Math.Min(c.height,SystemParameters.WorkArea.Height));
            MinWidth=300; MinHeight=300; Left=Math.Max(SystemParameters.VirtualScreenLeft,Math.Min(c.left,SystemParameters.WorkArea.Right-100));
            Top=Math.Max(SystemParameters.VirtualScreenTop,Math.Min(c.top,SystemParameters.WorkArea.Bottom-100)); Topmost=c.topmost; ResizeMode=ResizeMode.CanResizeWithGrip;
            var root=new Grid { Margin=new Thickness(10) }; Content=root;
            root.RowDefinitions.Add(new RowDefinition { Height=GridLength.Auto }); root.RowDefinitions.Add(new RowDefinition()); root.RowDefinitions.Add(new RowDefinition { Height=GridLength.Auto });
            var bar=new DockPanel(); var menu=Button("⋮",()=>Menu()); menu.ToolTip="Menu"; DockPanel.SetDock(menu,Dock.Right); bar.Children.Add(menu);
            var title=Text("Dashboard"); title.FontSize=13; title.Opacity=0.75; title.VerticalAlignment=VerticalAlignment.Center; title.Margin=new Thickness(12,0,0,0);
            title.MouseLeftButtonDown+=(s,e)=> { if(e.LeftButton==MouseButtonState.Pressed) DragMove(); }; bar.Children.Add(title); root.Children.Add(Card(bar));
            var scroll=new ScrollViewer { Content=body,VerticalScrollBarVisibility=ScrollBarVisibility.Auto,HorizontalScrollBarVisibility=ScrollBarVisibility.Disabled }; Grid.SetRow(scroll,1); root.Children.Add(scroll);
            var footer=new StackPanel(); Grid.SetRow(footer,2); root.Children.Add(footer);
            status.TextWrapping=TextWrapping.Wrap; status.FontSize=12; status.Foreground=new SolidColorBrush(Color.FromRgb(245,201,140)); footer.Children.Add(status);
            var captures=new System.Windows.Controls.Primitives.UniformGrid { Columns=4 };
            var note=Button("✎",()=>Note()); note.ToolTip="Text note"; captures.Children.Add(note);
            voice.ToolTip="Voice note · press and hold"; voice.FontSize=24;
            voice.PreviewMouseLeftButtonDown+=(s,e)=> { e.Handled=true; StartRecording(); };
            voice.PreviewMouseLeftButtonUp+=(s,e)=> { e.Handled=true; StopRecording(); };
            voice.LostMouseCapture+=(s,e)=> { if(recording!=null) StopRecording(); }; captures.Children.Add(voice);
            var camera=Button("▣",()=>Camera()); camera.ToolTip="Take a photo"; captures.Children.Add(camera);
            var upload=Button("↑",()=>Import()); upload.ToolTip="Upload image or file"; captures.Children.Add(upload); footer.Children.Add(Card(captures));
            clock.Interval=TimeSpan.FromMilliseconds(200); clock.Tick+=(s,e)=> { voice.Content="● "+(DateTime.UtcNow-began).ToString(@"m\:ss"); if(DateTime.UtcNow-began>TimeSpan.FromMinutes(10)) StopRecording(); };
            timer.Interval=TimeSpan.FromSeconds(60); timer.Tick+=async (s,e)=>await Refresh();
            Loaded+=async (s,e)=> { await RecoverRecordings(); Render(); timer.Start(); await Refresh(); };
            Closing+=(s,e)=> {
                if(recording!=null) { e.Cancel=true; StopRecording(); return; }
                timer.Stop(); lifetime.Cancel(); store.Change(v=> { v.config.left=Left; v.config.top=Top; v.config.width=Width; v.config.height=Height; });
                http.Dispose();
            };
            Render();
        }
        public static TextBlock Text(string text) { return new TextBlock { Text=text,Foreground=Brushes.White,TextWrapping=TextWrapping.Wrap,Margin=new Thickness(3) }; }
        public static Button Button(string label,Action click) { var b=new Button { Content=label }; if(click!=null) b.Click+=(s,e)=>click(); return b; }
        Border Card(UIElement child) { return new Border { Child=child,CornerRadius=new CornerRadius(18),Padding=new Thickness(12),Margin=new Thickness(0,4,0,4),Background=surface }; }
        void Menu() {
            var menu=new ContextMenu();
            Action<string,Action> add=(name,action)=> { var item=new MenuItem { Header=name }; item.Click+=(s,e)=>action(); menu.Items.Add(item); };
            add("Refresh",async ()=>await Refresh()); add("Settings / Google Drive",()=>Settings()); add("Capture inbox",()=>Inbox());
            add("Copy agent setup prompt",()=>CopyPrompt()); add("Check for updates",()=>Process.Start(new ProcessStartInfo("https://github.com/AudacityMicro/Dashboard2.0/releases/latest") { UseShellExecute=true }));
            add("Close",()=>Close()); menu.IsOpen=true;
        }
        async Task Refresh() {
            try { await sync.Run(); SetStatus(store.Snapshot().config.enabled ? "" : "Connect Google Drive through ⋮ → Settings. Notes work offline."); }
            catch(Exception e) { SetStatus(e.Message); }
            if(!lifetime.IsCancellationRequested) Render();
        }
        void SetStatus(string value) { status.Text=value; status.Visibility=String.IsNullOrEmpty(value)?Visibility.Collapsed:Visibility.Visible; }
        public void Render() {
            if(rendering) return; rendering=true;
            try {
                var s=store.Snapshot(); body.Children.Clear();
                if(!s.config.enabled) {
                    var setup=new StackPanel(); setup.Children.Add(Text("Your notes, tasks and agent updates—on your desktop."));
                    setup.Children.Add(Button("Connect Google Drive",()=>Settings())); body.Children.Add(Card(setup));
                }
                foreach(var update in s.board.updates) { var p=new StackPanel(); if(!String.IsNullOrWhiteSpace(update.title)) { var t=Text(update.title); t.FontWeight=FontWeights.SemiBold; p.Children.Add(t); }
                    p.Children.Add(Markdown(update.body??"")); body.Children.Add(Card(p)); }
                var tasks=store.Tasks(); var open=tasks.Where(t=>!t.completed).ToList();
                if(tasks.Count>0) {
                    var p=new StackPanel(); foreach(var task in open) p.Children.Add(TaskRow(task));
                    var completed=tasks.Where(t=>t.completed).ToList(); if(completed.Count>0) { var expander=new Expander { Header="✓ "+completed.Count,Foreground=Brushes.White,FontSize=12,Opacity=0.75 }; var done=new StackPanel(); foreach(var task in completed) done.Children.Add(TaskRow(task)); expander.Content=done; p.Children.Add(expander); }
                    body.Children.Add(Card(p));
                }
                if(s.config.showHealth && s.health.Length>0) try {
                    var j=Json.Read<Dictionary<string,object>>(s.health);
                    if(j.ContainsKey("sharing") && Convert.ToBoolean(j["sharing"]) && j.ContainsKey("today")) {
                        var today=(Dictionary<string,object>)j["today"]; var p=new StackPanel();
                        Func<string,string> kcal=key=>today.ContainsKey(key)&&today[key]!=null?Math.Round(Convert.ToDouble(today[key])).ToString("0"):"—";
                        p.Children.Add(Text("Eaten "+kcal("consumedKcal")+"   ·   Remaining "+kcal("remainingKcal")+"   ·   Burned "+kcal("totalBurnedKcal")+" kcal"));
                        var updated=today.ContainsKey("updatedAt")?DateTimeOffset.FromUnixTimeMilliseconds(Convert.ToInt64(today["updatedAt"])).ToLocalTime().ToString("g"):"unknown time";
                        p.Children.Add(Text("Phone snapshot · "+Convert.ToString(today["date"])+" · updated "+updated)); body.Children.Add(Card(p));
                    }
                } catch { }
                if(s.board.updates.Count==0 && tasks.Count==0 && s.config.enabled) { var empty=Text("Drop a thought. Your agent's updates will appear here."); empty.Opacity=0.65; body.Children.Add(Card(empty)); }
                var pending=s.captures.Count(c=>c.state!="synced"); if(pending>0) { var count=Button(pending+" saved capture"+(pending==1?"":"s")+" waiting",()=>Inbox()); body.Children.Add(count); }
            } finally { rendering=false; }
        }
        UIElement TaskRow(Todo task) {
            var check=new CheckBox { Content=task.title+(String.IsNullOrEmpty(task.due)?"":" · "+task.due),IsChecked=task.completed };
            check.Click+=async (s,e)=> { store.SetTask(task,check.IsChecked==true); Render(); await Refresh(); }; return check;
        }
        public static UIElement Markdown(string source) {
            var p=new StackPanel(); bool code=false;
            foreach(var raw in source.Replace("\r","").Split('\n')) {
                if(raw.StartsWith("```")) { code=!code; continue; }
                var line=raw; var text=new TextBlock { TextWrapping=TextWrapping.Wrap,Foreground=Brushes.White,Margin=new Thickness(3,2,3,2) };
                if(code) { text.FontFamily=new FontFamily("Consolas"); text.Text=line; }
                else {
                    var heading=Regex.Match(line,@"^(#{1,6})\s+"); if(heading.Success) { line=line.Substring(heading.Length); text.FontWeight=FontWeights.SemiBold; text.FontSize=heading.Groups[1].Value.Length==1?18:15; }
                    if(line.StartsWith("> ")) { line=line.Substring(2); text.Margin=new Thickness(12,2,3,2); text.Opacity=0.8; }
                    line=Regex.Replace(line,@"^\s*[-*+]\s+","• ");
                    var pattern=@"(\*\*([^*]+)\*\*|`([^`]+)`|\[([^\]]+)\]\((https?://[^\s)]+)\)|\*([^*]+)\*)"; int at=0;
                    foreach(Match m in Regex.Matches(line,pattern)) {
                        text.Inlines.Add(new Run(line.Substring(at,m.Index-at)));
                        if(m.Groups[2].Success) text.Inlines.Add(new Bold(new Run(m.Groups[2].Value)));
                        else if(m.Groups[3].Success) text.Inlines.Add(new Run(m.Groups[3].Value) { FontFamily=new FontFamily("Consolas") });
                        else if(m.Groups[6].Success) text.Inlines.Add(new Italic(new Run(m.Groups[6].Value)));
                        else { var link=new Hyperlink(new Run(m.Groups[4].Value)) { NavigateUri=new Uri(m.Groups[5].Value),Foreground=Brushes.LightSkyBlue }; link.RequestNavigate+=(s,e)=> { Process.Start(new ProcessStartInfo(e.Uri.AbsoluteUri) { UseShellExecute=true }); e.Handled=true; }; text.Inlines.Add(link); }
                        at=m.Index+m.Length;
                    }
                    text.Inlines.Add(new Run(line.Substring(at)));
                }
                p.Children.Add(text);
            }
            return p;
        }
        Window Dialog(string title,double width,double height,UIElement content) { return new Window { Owner=this,Title=title,Width=width,Height=height,MinWidth=300,MinHeight=250,WindowStartupLocation=WindowStartupLocation.CenterOwner,Background=new SolidColorBrush(Color.FromRgb(24,29,38)),Foreground=Brushes.White,Content=content }; }
        void Note() {
            var panel=new DockPanel { Margin=new Thickness(16) }; var actions=new StackPanel { Orientation=Orientation.Horizontal,HorizontalAlignment=HorizontalAlignment.Right }; DockPanel.SetDock(actions,Dock.Bottom); panel.Children.Add(actions);
            var edit=new TextBox { Text=store.Snapshot().draft,AcceptsReturn=true,AcceptsTab=true,TextWrapping=TextWrapping.Wrap,VerticalScrollBarVisibility=ScrollBarVisibility.Auto,FontSize=17 }; panel.Children.Add(edit);
            var window=Dialog("New note",520,520,panel); bool saved=false;
            var debounce=new DispatcherTimer { Interval=TimeSpan.FromMilliseconds(500) }; debounce.Tick+=(s,e)=> { debounce.Stop(); store.Change(v=>v.draft=edit.Text); };
            edit.TextChanged+=(s,e)=> { debounce.Stop(); debounce.Start(); };
            actions.Children.Add(Button("Save",async ()=> { try { store.Text(edit.Text); saved=true; window.Close(); Render(); await Refresh(); } catch(Exception e) { MessageBox.Show(window,e.Message,"Note"); } }));
            window.Closing+=(s,e)=> { debounce.Stop(); if(!saved && edit.Text.Length>0 && MessageBox.Show(window,"Leave this note? Your draft will stay on this PC.","Keep draft",MessageBoxButton.YesNo)==MessageBoxResult.No) { e.Cancel=true; return; } if(!saved) store.Change(v=>v.draft=edit.Text); };
            window.ShowDialog();
        }
        async void Import() {
            var picker=new OpenFileDialog { Title="Upload image or file",Multiselect=true };
            if(picker.ShowDialog(this)!=true) return;
            try { var paths=picker.FileNames; await Task.Run(()=> { foreach(var path in paths) { var ext=Path.GetExtension(path).ToLowerInvariant(); var mime=ext==".png"?"image/png":ext==".jpg"||ext==".jpeg"?"image/jpeg":ext==".gif"?"image/gif":ext==".pdf"?"application/pdf":ext==".txt"?"text/plain":"application/octet-stream"; store.Import(path,mime.StartsWith("image/")?"image":"file",mime); } }); Render(); await Refresh(); }
            catch(Exception e) { SetStatus(e.Message); }
        }
        void Camera() {
            try { new CameraWindow(this,path=> { store.Import(path,"image","image/jpeg"); Render(); var ignored=Refresh(); }).ShowDialog(); }
            catch(Exception e) { SetStatus("Camera unavailable: "+e.Message+". You can still upload an image."); }
        }
        void StartRecording() {
            if(recording!=null || stopping) return;
            active=Store.New("voice","audio/wav","voice.wav"); active.voiceMode=store.Snapshot().config.voiceMode; active.state="recording";
            store.Change(s=>s.captures.Add(active)); recording=new Recorder();
            try { recording.Start(store.Payload(active.id)); began=DateTime.UtcNow; voice.FontSize=27; voice.Height=90; voice.Background=Brushes.DarkRed; Mouse.Capture(voice); clock.Start(); }
            catch(Exception e) { recording.Dispose(); recording=null; store.Change(s=>s.captures.Single(c=>c.id==active.id).state="failed"); SetStatus(e.Message); }
        }
        async void StopRecording() {
            if(recording==null || stopping) return; stopping=true; clock.Stop();
            var item=active; recording.Dispose(); recording=null; Mouse.Capture(null); voice.Content="●"; voice.Height=Double.NaN; voice.FontSize=24; voice.Background=new SolidColorBrush(Color.FromArgb(37,63,73,89));
            try { await FinishVoice(item); Render(); await Refresh(); } catch(Exception e) { SetStatus(e.Message); } finally { stopping=false; }
        }
        async Task FinishVoice(Capture item) {
            string text="", result="none";
            if(new FileInfo(store.Payload(item.id)).Length<=44) { store.Change(s=>s.captures.Single(c=>c.id==item.id).state="failed"); return; }
            if(item.voiceMode!="audio") {
                SetStatus("Transcribing locally…");
                try { text=await Recorder.Transcribe(store.Payload(item.id)); result=text.Length==0?"no_speech":"ready"; }
                catch { result="failed"; }
            }
            store.Change(s=> { var c=s.captures.Single(v=>v.id==item.id); c.text=text; c.transcriptionStatus=result; c.state="pending"; });
            if(item.voiceMode=="transcript" && result!="ready") SetStatus("Transcript unavailable. Original audio is safe on this PC; retry from Capture inbox.");
        }
        async Task RecoverRecordings() {
            foreach(var c in store.Snapshot().captures.Where(c=>c.state=="recording")) try { using(var f=new FileStream(store.Payload(c.id),FileMode.Open,FileAccess.ReadWrite)) Recorder.Finish(f); await FinishVoice(c); }
                catch { store.Change(s=>s.captures.Single(v=>v.id==c.id).state="failed"); }
        }
        void Inbox() {
            var panel=new StackPanel { Margin=new Thickness(15) }; var player=new MediaPlayer(); var win=Dialog("Capture inbox",520,600,new ScrollViewer { Content=panel,VerticalScrollBarVisibility=ScrollBarVisibility.Auto });
            foreach(var c in store.Snapshot().captures.AsEnumerable().Reverse().Take(100)) {
                var p=new StackPanel(); p.Children.Add(Text(c.kind+" · "+c.createdAt+" · "+c.state));
                if(c.text.Length>0) p.Children.Add(Text(c.text));
                if(c.kind=="voice") { p.Children.Add(Button("Play audio",()=> { player.Open(new Uri(store.Payload(c.id))); player.Play(); }));
                    if(c.voiceMode!="audio" && c.transcriptionStatus!="ready") p.Children.Add(Button("Retry local transcript",async ()=> { await FinishVoice(c); await Refresh(); })); }
                else if(c.kind=="image") try { var image=new BitmapImage(); image.BeginInit(); image.CacheOption=BitmapCacheOption.OnLoad; image.UriSource=new Uri(store.Payload(c.id)); image.DecodePixelWidth=420; image.EndInit(); p.Children.Add(new Image { Source=image,MaxHeight=220 }); } catch { }
                panel.Children.Add(Card(p));
            }
            win.Closed+=(s,e)=>player.Close(); win.ShowDialog();
        }
        public void CopyPrompt() {
            var s=store.Snapshot(); if(s.config.desktopFile.Length==0 || s.config.instructionsFile.Length==0) { SetStatus("Connect the phone dashboard and finish syncing before copying the prompt."); return; }
            Clipboard.SetText("Connect this Windows Dashboard to my existing agent using your own Drive tools. Read instructions file ID "+s.config.instructionsFile+" before every run. Merge guide version 10 from https://raw.githubusercontent.com/AudacityMicro/Dashboard2.0/v0.1.0-preview.22/AGENT_STARTER.md into the same guide, preserving my edits. Dashboard board (edit): file ID "+s.config.agentFile+". Phone state (read-only): file ID "+s.config.phoneFile+". Windows state (read-only): file ID "+s.config.desktopFile+". Monitor all device taskEdits, reconcile edits, and write acknowledgedTaskEdits in the agent board as described in the guide. Never write the device files. Process Windows capture manifests alongside phone captures; stable UUIDs prevent duplicates. Show useful daily information with Markdown, no app/setup commentary. Preserve my existing monitoring schedule and memory. Report blockers in chat, not on the dashboard.");
            SetStatus("Agent setup prompt copied.");
        }
        void Settings() {
            var panel=new StackPanel { Margin=new Thickness(18) }; var window=Dialog("Dashboard settings",560,650,new ScrollViewer { Content=panel,VerticalScrollBarVisibility=ScrollBarVisibility.Auto });
            Action<string> label=t=>panel.Children.Add(Text(t));
            label("Background transparency"); var opacity=new Slider { Minimum=0.1,Maximum=1,Value=store.Snapshot().config.opacity,Margin=new Thickness(8) };
            opacity.ValueChanged+=(s,e)=> { surface.Color=Color.FromArgb((byte)(255*opacity.Value),18,23,31); }; panel.Children.Add(opacity);
            window.Closed+=(s,e)=>store.Change(v=>v.config.opacity=opacity.Value);
            var pin=new CheckBox { Content="Keep above other windows",IsChecked=Topmost }; pin.Click+=(s,e)=> { Topmost=pin.IsChecked==true; store.Change(v=>v.config.topmost=Topmost); }; panel.Children.Add(pin);
            var health=new CheckBox { Content="Display the phone's shared Health snapshot",IsChecked=store.Snapshot().config.showHealth };
            health.Click+=async (s,e)=> { store.Change(v=> { v.config.showHealth=health.IsChecked==true; if(!v.config.showHealth) v.health=""; }); await Refresh(); }; panel.Children.Add(health);
            label("Voice recordings · local Windows speech engine required for transcripts");
            var modes=new ComboBox { ItemsSource=new[] { "Transcript only · audio stays on this PC","Audio only · exact recording to Drive","Both audio and local transcript" },SelectedIndex=store.Snapshot().config.voiceMode=="transcript"?0:store.Snapshot().config.voiceMode=="audio"?1:2,Margin=new Thickness(5) };
            modes.SelectionChanged+=(s,e)=>store.Change(v=>v.config.voiceMode=modes.SelectedIndex==0?"transcript":modes.SelectedIndex==1?"audio":"both"); panel.Children.Add(modes);
            label("Google Drive · sign-in opens your browser");
            panel.Children.Add(Button("Import Desktop OAuth client JSON",()=> {
                var file=new OpenFileDialog { Filter="Google OAuth JSON|*.json" };
                if(file.ShowDialog(window)!=true) return;
                try { var root=Json.Read<Dictionary<string,object>>(File.ReadAllText(file.FileName)); var client=(Dictionary<string,object>)root["installed"];
                    store.Disconnect(); store.Change(v=> { v.config.clientId=Convert.ToString(client["client_id"]); v.config.clientSecret=client.ContainsKey("client_secret")?Convert.ToString(client["client_secret"]):""; }); label("Desktop OAuth client imported; stored with Windows account encryption."); }
                catch { MessageBox.Show(window,"Choose a Desktop app OAuth client JSON downloaded from Google Cloud. Android clients are not compatible."); }
            }));
            var sign=Button("Sign in to Google",null); sign.Click+=async (s,e)=> { sign.IsEnabled=false; try { await google.SignIn(lifetime.Token); label("Connected: "+store.Snapshot().config.email); await Discover(); await Refresh(); } catch(Exception ex) { MessageBox.Show(window,ex.Message,"Google Drive"); } finally { sign.IsEnabled=true; } }; panel.Children.Add(sign);
            panel.Children.Add(Button("Find my phone dashboard",async ()=> { try { await Discover(); label("Dashboard files linked."); await Refresh(); } catch(Exception e) { MessageBox.Show(window,e.Message); } }));
            label("Or paste the setup prompt copied from your phone:"); var prompt=new TextBox { AcceptsReturn=true,Height=90,TextWrapping=TextWrapping.Wrap }; panel.Children.Add(prompt);
            panel.Children.Add(Button("Link these files",async ()=> { try {
                var ids=Regex.Matches(prompt.Text,@"file ID ([A-Za-z0-9_-]+)").Cast<Match>().Select(m=>m.Groups[1].Value).ToList();
                if(ids.Count<3) throw new InvalidDataException("Paste the full compact phone setup prompt (instructions, dashboard, phone tasks). No token is needed.");
                store.Change(v=> { v.config.instructionsFile=ids[0]; v.config.agentFile=ids[1]; v.config.phoneFile=ids[2]; }); await Refresh();
            } catch(Exception e) { MessageBox.Show(window,e.Message); } }));
            panel.Children.Add(Button("Copy updated agent setup prompt",()=>CopyPrompt()));
            panel.Children.Add(Button("Disconnect Google Drive",()=> { store.Disconnect(); SetStatus("Drive disconnected. Captures and tasks remain on this PC. Revoke the Google grant in your Google Account if desired."); Render(); }));
            panel.Children.Add(Button("Done",()=>window.Close())); window.ShowDialog();
        }
        async Task Discover() {
            var boards=await google.Find("Dashboard-agent.json"); var phones=await google.Find("Dashboard-phone-tasks.json"); var guides=await google.Find("Dashboard-agent-instructions.md");
            if(boards.Count!=1 || phones.Count!=1 || guides.Count!=1) throw new InvalidDataException("No unique phone setup found. Paste the setup prompt from your phone to choose exact files. Sign in to the same Google account using a Desktop client from the same Cloud project.");
            Board.Parse(await google.Read(boards[0].id)); store.Change(s=> { s.config.agentFile=boards[0].id; s.config.phoneFile=phones[0].id; s.config.instructionsFile=guides[0].id; });
        }
    }
}
