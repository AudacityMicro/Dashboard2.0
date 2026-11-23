using System;
using System.IO;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Media.Imaging;
using AForge.Video.DirectShow;

namespace DashboardWindows {
    public sealed class CameraWindow : Window {
        VideoCaptureDevice source; System.Drawing.Bitmap latest; readonly object gate=new object(); readonly Image preview=new Image(); DateTime last;
        public CameraWindow(Window owner,Action<string> save) {
            Owner=owner; ShowInTaskbar=false; Title="Take a photo"; Width=640; Height=520; WindowStartupLocation=WindowStartupLocation.CenterOwner;
            Background=System.Windows.Media.Brushes.Black;
            var panel=new DockPanel(); Content=panel;
            var devices=new FilterInfoCollection(FilterCategory.VideoInputDevice);
            var controls=new StackPanel { Orientation=Orientation.Horizontal }; DockPanel.SetDock(controls,Dock.Bottom); panel.Children.Add(controls);
            var choices=new ComboBox { MinWidth=220,DisplayMemberPath="Name",ItemsSource=devices }; controls.Children.Add(choices);
            controls.Children.Add(Widget.Button("Take photo",()=> {
                string file=Path.Combine(Path.GetTempPath(),"Dashboard-camera-"+Guid.NewGuid()+".jpg");
                try { lock(gate) { if(latest==null) { MessageBox.Show(this,"Wait for a camera frame, or choose another camera."); return; } latest.Save(file,System.Drawing.Imaging.ImageFormat.Jpeg); }
                    save(file); Close();
                } finally { if(File.Exists(file)) File.Delete(file); }
            })); panel.Children.Add(preview);
            choices.SelectionChanged+=(s,e)=> {
                Stop(); if(choices.SelectedIndex<0) return;
                source=new VideoCaptureDevice(devices[choices.SelectedIndex].MonikerString);
                source.NewFrame+=(sender,frame)=> {
                    lock(gate) { if(latest!=null) latest.Dispose(); latest=(System.Drawing.Bitmap)frame.Frame.Clone();
                        if(DateTime.UtcNow-last<TimeSpan.FromMilliseconds(150)) return; last=DateTime.UtcNow;
                        using(var buffer=new MemoryStream()) { latest.Save(buffer,System.Drawing.Imaging.ImageFormat.Bmp); buffer.Position=0;
                            var image=new BitmapImage(); image.BeginInit(); image.CacheOption=BitmapCacheOption.OnLoad; image.StreamSource=buffer; image.EndInit(); image.Freeze(); Dispatcher.BeginInvoke(new Action(()=>preview.Source=image)); }
                    }
                };
                source.VideoSourceError+=(sender,error)=>Dispatcher.BeginInvoke(new Action(()=>Title="Camera unavailable · check Windows camera permissions")); source.Start();
            };
            Closed+=(s,e)=> { Stop(); lock(gate) if(latest!=null) { latest.Dispose(); latest=null; } };
            if(devices.Count==0) { panel.Children.Add(Widget.Text("No camera was found. Upload an image from the widget instead.")); }
            else choices.SelectedIndex=0;
        }
        void Stop() { if(source==null) return; source.SignalToStop(); source.WaitForStop(); source=null; }
    }
}
